package com.beichen.polaris.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获落盘（真机第四轮 **一号优先级**：先让崩溃留下证据，再谈归因）。
 *
 * **动机**：用户真机实测「开启屏幕捕捉 → App 立刻闪退」，但日志页一条异常都抓不到——
 * 进程被杀时内存里的日志缓冲来不及落盘，证据随之蒸发，只能靠猜。本类把崩溃现场
 * **同步**写进 `filesDir/crash/crash-<yyyyMMdd-HHmmss>.txt`（`flush` + `fsync`），
 * 下次启动即可在「运行日志」页读到完整堆栈。
 *
 * **三条铁律**：
 *  1. **同步落盘**：进程马上要死，绝不能异步（`fsync` 保证数据出页缓存）；
 *  2. **绝不吞掉崩溃**：写完**必须**把异常交回原 handler（系统崩溃流程照常）；
 *  3. **自身绝不抛**：写文件失败、格式化失败、原 handler 抛异常，一律内部吞掉。
 *
 * **可测性**：报告格式化（[formatReport]）、保留策略（[retentionPlan]）、
 * 崩溃派发（[dispatch]）全部是 internal **纯函数**，不依赖 Android，
 * QA harness（scripts/qa/CrashLogVerification.kt）可在纯 JVM 下逐条断言。
 */
object CrashLogger {

    /** 崩溃报告目录名（相对 filesDir）：`filesDir/crash/`。 */
    const val DIR_NAME: String = "crash"

    /** 崩溃文件名前缀。 */
    const val FILE_PREFIX: String = "crash-"

    /** 崩溃文件名后缀。 */
    const val FILE_SUFFIX: String = ".txt"

    /** 崩溃文件保留份数上限：超出删最旧的，防止长期占用用户空间。 */
    const val MAX_KEEP: Int = 10

    /** 文件名时间戳格式：`yyyyMMdd-HHmmss`（字典序 = 时间序，排序即按时间排）。 */
    private const val NAME_STAMP: String = "yyyyMMdd-HHmmss"

    /** 报告内人类可读时间格式。 */
    private const val READABLE_STAMP: String = "yyyy-MM-dd HH:mm:ss.SSS"

    /** 文件名 → 时间戳的解析格式（去分隔符）。 */
    private const val PARSE_STAMP: String = "yyyyMMddHHmmss"

    /** 设备信息的兜底值：任何一项取不到都不许让报告写不出来。 */
    private const val UNKNOWN: String = "?"

    /** 单份报告进入日志时的预览长度（日志页一行放得下为准）。 */
    private const val LOG_PREVIEW: Int = 300

    /** 是否已安装（幂等：Application 可能被多次创建/多进程各调一次）。 */
    @Volatile
    private var installed: Boolean = false

    /** 安装时保存的**原** handler：崩溃必须回传给它，不能吞。 */
    @Volatile
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 安装时快照的设备/版本信息（崩溃时再去读 PackageManager 风险太高）。 */
    @Volatile
    private var deviceInfo: DeviceInfo = DeviceInfo()

    /** 崩溃落盘目录（install 时缓存，崩溃时不再依赖 Context）。 */
    @Volatile
    private var crashDir: File? = null

    /* ============================== 安装 ============================== */

    /**
     * 刻意的 no-op（登记在 [com.beichen.polaris.AppGraph] 里，`resettable = false`）。
     *
     * 崩溃捕获是**进程级一次性**设施：handler 一旦挂到 `Thread.getDefaultUncaughtExceptionHandler`
     * 就无法安全摘下——卸载会让此后的崩溃静默消失（比没有捕获更糟），重装会套娃成
     * 多层 handler，同一个崩溃写多份报告。它持有的是事实（已安装 / 原 handler 引用 /
     * 崩溃目录），不是需要清理的运行态。
     *
     * 真要在 harness 里换一套崩溃目录，只能新起一个 JVM。
     */
    fun reset() {
        // 故意为空。理由见 KDoc：卸载 handler 会丢掉崩溃证据。
    }

    /**
     * 安装全局崩溃捕获。**必须在 Application.onCreate 最早处调用**，
     * 这样连后续初始化（ViewModel / ContentProvider / 其他组件）的崩溃都能留下证据。
     *
     * 幂等：重复调用只生效一次（避免多进程/热重启把 handler 套娃）。
     *
     * @param context 任意 Context；内部只用 applicationContext，不持有 Activity。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = runCatching { context.applicationContext }.getOrDefault(context)
        val dir = File(app.filesDir, DIR_NAME)
        crashDir = dir
        deviceInfo = readDeviceInfo(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        defaultHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            dispatch(
                thread = thread,
                throwable = throwable,
                defaultHandler = previous,
                timeMs = System.currentTimeMillis(),
                info = deviceInfo,
                write = { report -> writeReportSync(dir, report) }
            )
        }
        runCatching {
            AgentLogger.i(
                "CrashLogger",
                "崩溃捕获已安装：${dir.absolutePath}（保留最近 $MAX_KEEP 份）"
            )
        }
        // 上次崩溃的记录回放到日志：这是用户「下次启动能看到」的关键一步
        replayPendingIntoLogger(app)
    }

    /**
     * 把已存在的崩溃文件回放到 [AgentLogger]（WARN 级，带摘要与首行堆栈）。
     *
     * Application.onCreate 时日志目录尚未就绪（由 ViewModel 初始化），故本方法
     * **可重复调用**：日志页进入时再调一次，回放内容即可随当日日志落盘。
     *
     * @return 本次回放的崩溃文件数。
     */
    fun replayPendingIntoLogger(context: Context): Int {
        val files = listCrashFiles(context)
        if (files.isEmpty()) return 0
        val dir = crashDir(context)
        AgentLogger.w(
            "CrashLogger",
            "检测到 ${files.size} 次崩溃记录：${files.first().fileName} 位于 ${dir.absolutePath}"
        )
        for (record in files) {
            AgentLogger.w(
                "CrashLogger",
                "崩溃记录 ${record.fileName}：${
                    record.summary.take(LOG_PREVIEW)
                }"
            )
            val text = readCrashText(context, record.fileName)
            val stackHead = stackHeadOf(text)
            if (stackHead.isNotBlank()) {
                AgentLogger.w("Crash", "崩溃 ${record.fileName} 堆栈首行：$stackHead")
            }
        }
        return files.size
    }

    /* ============================== 对外查询（日志页） ============================== */

    /** 崩溃文件落盘目录：`filesDir/crash/`（不存在时顺便创建）。 */
    fun crashDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    /**
     * 列出崩溃文件（**最新的在前**）。
     *
     * @return 每条含文件名、发生时间、异常摘要；读文件失败不会中断整个列表。
     */
    fun listCrashFiles(context: Context): List<CrashRecord> {
        val dir = crashDir(context)
        val names = runCatching {
            dir.listFiles()?.map { it.name }?.filter { it.isCrashFileName() } ?: emptyList()
        }.getOrDefault(emptyList())
        return retentionPlan(names, Int.MAX_VALUE).keep.map { name ->
            val file = File(dir, name)
            val text = runCatching { file.readText() }.getOrDefault("")
            val parsed = parseReport(text)
            CrashRecord(
                fileName = name,
                timeMs = parseTimeMs(name).takeIf { it > 0 } ?: runCatching { file.lastModified() }.getOrDefault(0L),
                exceptionType = parsed.exceptionType,
                summary = parsed.summary.ifBlank { name },
                text = text
            )
        }
    }

    /** 读取某份崩溃报告全文（读失败返回可读的错误说明，绝不抛）。 */
    fun readCrashText(context: Context, fileName: String): String =
        runCatching { File(crashDir(context), fileName).readText() }
            .getOrDefault("（读取崩溃文件失败：$fileName）")

    /** 清空所有崩溃文件（日志页「清空崩溃记录」）。返回删除成功的数量。 */
    fun clearCrashFiles(context: Context): Int {
        val dir = crashDir(context)
        var deleted = 0
        for (name in runCatching { dir.listFiles()?.map { it.name } ?: emptyList() }.getOrDefault(emptyList())) {
            if (!name.isCrashFileName()) continue
            if (runCatching { File(dir, name).delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /**
     * 日志页顶部一行汇总（最低限度可读要求）：
     * `检测到 N 次崩溃记录：crash-xxx.txt 位于 <filesDir>/crash/`。
     */
    fun summary(context: Context): String {
        val files = listCrashFiles(context)
        if (files.isEmpty()) return "暂无崩溃记录（目录：${crashDir(context).absolutePath}）"
        val latest = files.first()
        return "检测到 ${files.size} 次崩溃记录：${latest.fileName} 位于 ${crashDir(context).absolutePath}"
    }

    /* ============================== 未查看崩溃（启动横幅 B） ============================== */

    /** 已读文件名集合的持久化名（SharedPreferences，避免改文件名破坏崩溃文件识别）。 */
    private const val SEEN_PREFS_NAME: String = "crash_seen"

    /** 已读集合在 [SEEN_PREFS_NAME] 中的键。 */
    private const val SEEN_PREFS_KEY: String = "seen_files"

    /**
     * **未查看崩溃文件筛选**（纯函数，零 Android 依赖——QA harness 可直接断言）。
     *
     * 从「目录内全部文件名」里挑出**仍存在、且不在已读集合**的崩溃文件名，
     * 按文件名（= 时间戳）降序（最新在前）。非崩溃文件名一律忽略。
     *
     * @param all 目录内现有文件名（顺序任意，含非崩溃文件）。
     * @param seen 已读文件名集合（来自 SharedPreferences）。
     */
    internal fun unseenCrashFiles(all: List<String>, seen: Set<String>): List<String> =
        all.filter { it.isCrashFileName() && it !in seen }.sortedDescending()

    /** 读取已读集合（只读现存崩溃文件的已读名）；读取失败 → 空集合，**绝不影响启动**。 */
    fun seenFiles(context: Context): Set<String> = runCatching {
        prefs(context).getStringSet(SEEN_PREFS_KEY, emptySet())?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    /**
     * 未查看的崩溃记录（最新在前）。读取失败 / 目录不存在 → 空列表（静默降级）。
     *
     * 顺序与 [listCrashFiles] 一致（最新在前），供启动横幅与日志页消费。
     */
    fun unseenCrashRecords(context: Context): List<CrashRecord> = runCatching {
        val records = listCrashFiles(context)
        val unseenNames = unseenCrashFiles(records.map { it.fileName }, seenFiles(context)).toSet()
        records.filter { it.fileName in unseenNames }
    }.getOrDefault(emptyList())

    /** 是否存在未查看崩溃（启动横幅判据）；读取失败 → false（静默降级，不打扰）。 */
    fun hasUnseen(context: Context): Boolean =
        runCatching { unseenCrashRecords(context).isNotEmpty() }.getOrDefault(false)

    /**
     * 把**当前仍存在**的全部崩溃文件标记为已读（横幅「查看」/「关闭」时调用）。
     *
     * 只落现存文件名，天然清理掉已删除文件的陈旧记录，避免集合无限增长。
     * 写盘失败一律吞掉（标记失败不影响主流程，最坏下次再提示一次）。
     */
    fun markAllSeen(context: Context) {
        runCatching {
            val current = listCrashFiles(context).map { it.fileName }.toSet()
            prefs(context).edit().putStringSet(SEEN_PREFS_KEY, current).apply()
        }
    }

    /** 把单个崩溃文件标记为已读（在已有已读集合上追加；写盘失败吞掉）。 */
    fun markSeen(context: Context, fileName: String) {
        runCatching {
            if (!fileName.isCrashFileName()) return
            val merged = seenFiles(context).toMutableSet().apply { add(fileName) }
            prefs(context).edit().putStringSet(SEEN_PREFS_KEY, merged).apply()
        }
    }

    /** 已读标记用的 SharedPreferences（applicationContext，不持有 Activity）。 */
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SEEN_PREFS_NAME, Context.MODE_PRIVATE)

    /* ============================== 纯函数：报告格式化 ============================== */

    /**
     * 崩溃时的设备/版本快照。全部带默认值——任何一项拿不到都退化成 `?`，
     * **绝不**因为读不到信息而让报告写不出来。
     */
    internal data class DeviceInfo(
        val manufacturer: String = UNKNOWN,
        val model: String = UNKNOWN,
        val sdkInt: Int = 0,
        val versionName: String = UNKNOWN,
        val versionCode: Long = 0L,
        val packageName: String = UNKNOWN
    )

    /**
     * 格式化崩溃报告（纯函数，零 Android 依赖）。
     *
     * 报告必须包含：发生时间、线程名、**完整堆栈**、异常类型与消息、
     * 机型 / Android SDK / App versionName / versionCode / 包名。
     *
     * @param timeMs 崩溃发生时刻（epoch 毫秒）。
     * @param threadName 崩溃线程名。
     * @param throwable 未捕获异常；其 `message` 若自身抛异常（魔改 ROM 见过）也由调用方兜住。
     * @param info 设备与版本快照。
     */
    internal fun formatReport(
        timeMs: Long,
        threadName: String,
        throwable: Throwable,
        info: DeviceInfo
    ): String {
        val type = throwable.javaClass.name
        // 脱敏在**进入报告之前**做，不是写盘时做：报告在内存里就只有遮过的文本，
        // 任何后续路径（预览、分享、复制）都不需要再记得处理一次。
        val message = SecretRedactor.redact(
            runCatching { throwable.message }.getOrDefault(null)?.trim().orEmpty()
        )
        // 堆栈也要遮：`Caused by: java.io.IOException: unexpected url: ...?key=sk-...` 这类
        // 内层异常的消息会原样出现在堆栈文本里，只遮顶层 message 等于没遮。
        val stack = SecretRedactor.redact(stackTraceOf(throwable))
        val sb = StringBuilder()
        sb.appendLine("========== 北辰 崩溃报告 ==========")
        sb.appendLine("发生时间：${formatReadable(timeMs)}")
        sb.appendLine("时间戳(ms)：$timeMs")
        sb.appendLine("线程名：$threadName")
        sb.appendLine("异常类型：$type")
        sb.appendLine("异常消息：${message.ifBlank { "（无）" }}")
        sb.appendLine("崩溃摘要：$type${if (message.isBlank()) "" else ": $message"}")
        sb.appendLine("设备信息：")
        sb.appendLine("  厂商：${info.manufacturer}")
        sb.appendLine("  机型：${info.model}")
        sb.appendLine("  Android SDK：${info.sdkInt}")
        sb.appendLine("  App 版本名：${info.versionName}")
        sb.appendLine("  App 版本号：${info.versionCode}")
        sb.appendLine("  包名：${info.packageName}")
        sb.appendLine("---------- 完整堆栈 ----------")
        sb.appendLine(stack)
        sb.appendLine("========== 报告结束 ==========")
        return sb.toString()
    }

    /** 完整堆栈文本（含 Caused by 链）；序列化本身出错时退化为一行说明。 */
    internal fun stackTraceOf(t: Throwable): String = runCatching {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        sw.toString().trimEnd()
    }.getOrDefault("（堆栈序列化失败：${runCatching { t.javaClass.name }.getOrDefault("?")}）")

    /** `epoch → yyyy-MM-dd HH:mm:ss.SSS`（Locale.US，避免受系统语言影响）。 */
    internal fun formatReadable(timeMs: Long): String = runCatching {
        SimpleDateFormat(READABLE_STAMP, Locale.US).format(Date(timeMs))
    }.getOrDefault(timeMs.toString())

    /** `epoch → crash-<yyyyMMdd-HHmmss>.txt`。 */
    internal fun crashFileName(timeMs: Long): String =
        FILE_PREFIX + runCatching {
            SimpleDateFormat(NAME_STAMP, Locale.US).format(Date(timeMs))
        }.getOrDefault(timeMs.toString()) + FILE_SUFFIX

    /**
     * 从文件名反解时间戳：`crash-20240501-120000.txt` → epoch。
     * 解析失败返回 0（调用方退化为文件 lastModified）。
     */
    internal fun parseTimeMs(fileName: String): Long {
        val digits = fileName
            .removePrefix(FILE_PREFIX)
            .takeWhile { it.isDigit() || it == '-' }
            .filter { it.isDigit() }
        if (digits.length < 14) return 0L
        return runCatching {
            SimpleDateFormat(PARSE_STAMP, Locale.US).parse(digits.take(14))?.time ?: 0L
        }.getOrDefault(0L)
    }

    /** 是否为合法崩溃文件名：`crash-<...>.txt`。 */
    internal fun String.isCrashFileName(): Boolean =
        startsWith(FILE_PREFIX) && endsWith(FILE_SUFFIX) && length > FILE_PREFIX.length + FILE_SUFFIX.length

    /** 报告解析结果（供日志页列表展示）。 */
    internal data class ReportSummary(
        val exceptionType: String = UNKNOWN,
        val message: String = "",
        val timeText: String = "",
        val summary: String = ""
    )

    /** 从报告全文里解析出类型/消息/时间/摘要；格式不认识时退化为默认值，绝不抛。 */
    internal fun parseReport(text: String): ReportSummary {
        if (text.isBlank()) return ReportSummary()
        var type = UNKNOWN
        var message = ""
        var timeText = ""
        var summary = ""
        for (line in text.lineSequence()) {
            when {
                type == UNKNOWN && line.startsWith("异常类型：") ->
                    type = line.removePrefix("异常类型：").trim().ifBlank { UNKNOWN }
                message.isEmpty() && line.startsWith("异常消息：") ->
                    message = line.removePrefix("异常消息：").trim()
                timeText.isEmpty() && line.startsWith("发生时间：") ->
                    timeText = line.removePrefix("发生时间：").trim()
                summary.isEmpty() && line.startsWith("崩溃摘要：") ->
                    summary = line.removePrefix("崩溃摘要：").trim()
            }
        }
        val head = text.lineSequence().firstOrNull { it.trimStart().startsWith("at ") }?.trim().orEmpty()
        return ReportSummary(
            exceptionType = type,
            message = message,
            timeText = timeText,
            summary = summary.ifBlank { head.ifBlank { type } }
        )
    }

    /** 报告全文里的堆栈首行（`at ...`），用于日志页列表副标题。 */
    internal fun stackHeadOf(text: String): String =
        text.lineSequence().firstOrNull { it.trimStart().startsWith("at ") }?.trim().orEmpty()

    /* ============================== 纯函数：保留策略 ============================== */

    /** 保留策略结果：[keep] 为要保留的文件名（**最新的在前**），[delete] 为要删除的。 */
    internal data class RetentionPlan(val keep: List<String>, val delete: List<String>)

    /**
     * 崩溃文件保留策略（纯函数）：按文件名（= 时间戳）降序排序，保留最新 [keep] 份，其余删除。
     *
     * 文件名格式为 `crash-<yyyyMMdd-HHmmss>.txt`，字典序即时间序，故**排序不需要读文件**。
     *
     * @param names 目录内现有文件名（顺序任意）。
     * @param keep 保留份数；≤0 表示全部删除（保留 0 份）。
     */
    internal fun retentionPlan(names: List<String>, keep: Int): RetentionPlan {
        if (names.isEmpty()) return RetentionPlan(emptyList(), emptyList())
        val sorted = names.sortedDescending()
        val keepCount = keep.coerceAtLeast(0).coerceAtMost(sorted.size)
        return RetentionPlan(
            keep = sorted.take(keepCount),
            delete = sorted.drop(keepCount)
        )
    }

    /* ============================== 纯函数：崩溃派发 ============================== */

    /**
     * 未捕获异常的统一派发（纯函数，Android 无关，可测）：
     * **先尽力写盘，再无条件调用原 handler**。
     *
     * - [write] 抛异常 → 记为写盘失败，**继续**调用原 handler；
     * - [formatReport] 抛异常（如 `Throwable.message` 自身抛）→ 同上；
     * - 原 handler 抛异常 → 内部吞掉（进程此刻已在崩溃流程里，不能再炸一次）；
     * - 本函数**绝不向外抛**。
     *
     * @return 崩溃报告是否成功写盘（false 时崩溃仍会照常交给原 handler）。
     */
    internal fun dispatch(
        thread: Thread,
        throwable: Throwable,
        defaultHandler: Thread.UncaughtExceptionHandler?,
        timeMs: Long = System.currentTimeMillis(),
        info: DeviceInfo = DeviceInfo(),
        write: (String) -> Unit
    ): Boolean {
        var written = false
        runCatching {
            val report = formatReport(timeMs, thread.name, throwable, info)
            write(report)
            written = true
        }
        runCatching { defaultHandler?.uncaughtException(thread, throwable) }
        return written
    }

    /* ============================== 内部实现（同步落盘） ============================== */

    /**
     * 同步写盘：`write → flush → fd.sync()`。
     *
     * **为什么必须 fsync**：进程马上要被杀，只 flush 到 OS 页缓存也可能丢；
     * `FileDescriptor.sync()` 强制刷到存储，这是「崩溃留证」能成立的前提。
     *
     * @return 是否写成功（失败一律吞掉，返回 false）。
     */
    private fun writeReportSync(dir: File, report: String): Boolean =
        runCatching {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false
            val file = uniqueFile(dir, crashFileName(System.currentTimeMillis()))
            FileOutputStream(file, false).use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            trimToMaxKeep(dir)
            true
        }.getOrDefault(false)

    /** 同名（同一秒内多次崩溃）时追加 `-2`、`-3`… 序号，避免互相覆盖丢证据。 */
    private fun uniqueFile(dir: File, name: String): File {
        val base = name.removeSuffix(FILE_SUFFIX)
        var candidate = File(dir, name)
        var seq = 2
        while (candidate.exists() && seq < 100) {
            candidate = File(dir, "$base-$seq$FILE_SUFFIX")
            seq++
        }
        return candidate
    }

    /** 按 [retentionPlan] 把目录裁剪到 [MAX_KEEP] 份（删除失败不影响主流程）。 */
    private fun trimToMaxKeep(dir: File) {
        val names = runCatching {
            dir.listFiles()?.map { it.name }?.filter { it.isCrashFileName() } ?: emptyList()
        }.getOrDefault(emptyList())
        val plan = retentionPlan(names, MAX_KEEP)
        for (name in plan.delete) {
            runCatching { File(dir, name).delete() }
        }
    }

    /** 读取设备与版本信息（任何一项失败都退化为 `?`）。 */
    private fun readDeviceInfo(context: Context): DeviceInfo = runCatching {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: UNKNOWN,
            model = Build.MODEL ?: UNKNOWN,
            sdkInt = Build.VERSION.SDK_INT,
            versionName = pi.versionName ?: UNKNOWN,
            versionCode = versionCode,
            packageName = context.packageName ?: UNKNOWN
        )
    }.getOrDefault(DeviceInfo())
}

/** 日志页可渲染的崩溃记录。 */
data class CrashRecord(
    val fileName: String,
    val timeMs: Long,
    val exceptionType: String,
    val summary: String,
    val text: String = ""
)
