package com.beichen.polaris.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 日志级别：从细到粗。真机排查时主要看 WARN / ERROR。
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条日志记录。存内存环形缓冲 + 异步落盘，供 UI「运行日志（日记）」页直接渲染。
 *
 * @param stack 异常堆栈（已按需截断），仅在 e/w 传 Throwable 时存在。
 */
data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val stack: String? = null
) {
    /** 单行格式：`HH:mm:ss.SSS LEVEL [tag] message`，有堆栈时换行追加。 */
    fun format(): String {
        val head = "${formatClock(timeMs)} ${level.name} [$tag] $message"
        val st = stack
        return if (st.isNullOrBlank()) head else "$head\n${st.take(PREVIEW_MAX)}"
    }
}

/**
 * 运行日志核心（L8「运行日志（日记）」，净室原创）。
 *
 * 解决的问题：真机运行出错时看不到任何线索，只能靠猜。这里把 LLM 请求/响应、
 * 工具调用/结果、HTTP 错误、异常堆栈全部落到 `dir/agent-<yyyyMMdd>.log`，同时保留
 * 一份内存环形缓冲给 UI 实时展示。
 *
 * 设计要点：
 *  - **纯 Kotlin，不 import android.***：core 层可在 JVM 单测里直接跑。
 *  - **绝不记录明文敏感数据**：[record] 里统一对消息做 [sanitize]，API Key / Bearer /
 *    api_key / token / password 一律替换为 `***`；[SENSITIVE_TOOLS] 中的工具只记长度不记原文。
 *  - **写盘失败不能崩溃**：所有 IO 都包了 runCatching，日志只是旁路，不能拖垮主线程业务。
 *  - **写盘串行化 + 定期清理**：落盘走单线程 IO 协程避免行交错；超过 [LOG_RETENTION_DAYS]
 *    天的历史日志在 [init] 时清理，避免无限堆积。
 */
object AgentLogger {

    /** 内存缓冲上限：超过后丢弃最旧记录，避免长时间运行 OOM。 */
    private const val MAX_MEMORY = 500

    /**
     * 内存缓冲容量（对外可见）。
     *
     * UI 取超过这个数的日志没有意义——缓冲里就只有这么多。
     * 2026-11-25 之前 `LogsFacade` 自己写死 200，于是「恢复 500 条 / 导出 500 条 /
     * 页面 200 条」三个数字对不上，用户搜不到被截掉的 300 条。现在统一到这里。
     */
    const val MEMORY_CAPACITY = MAX_MEMORY

    /** 单文件轮转阈值：2MB。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 磁盘保留天数：超过该天数的 `agent-<yyyyMMdd>.log` 在初始化时被删除。 */
    private const val LOG_RETENTION_DAYS = 7L

    /** 一天毫秒数，仅用于保留天数换算。 */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 回读历史时每个文件最多看这么多行（尾部）。 */
    private const val TAIL_LINES = 2000

    /** 「目录里没有 agent 日志」这条 WARN 的最小间隔：搜索按输入触发，不节流会刷屏。 */
    private const val DISK_MISS_WARN_INTERVAL_MS = 30_000L

    /**
     * 点完「删除全部日志」之后的静默期：这段时间里「目录为空」是用户自己造成的，不算故障。
     *
     * 为什么非要静默：那条 WARN 走 [record] → [appendToDisk]，**它会把刚删空的目录又建出文件**。
     * 真机 2026-11-26 10:29 就是这个样子——用户删完立刻搜了一次，屏幕上显示
     * 「日志文件 1 个：agent-20261126.log(181B)」，181B 正是这条 WARN 自己。
     * 用写文件的方式记录「没有文件」，把要证明的事实毁掉了。
     */
    private const val CLEAR_QUIET_MS = 5_000L

    /** 上次写「目录里没有日志」的时间戳。仅用于节流，读写在 IO 线程，@Volatile 足够。 */
    @Volatile
    private var lastDiskMissWarnAt = 0L

    /**
     * 上次「删除全部日志」的时刻，用于 [CLEAR_QUIET_MS] 静默期判定。
     *
     * 初值 0：`now - 0` 远大于静默期，未清空过的进程不受影响。
     */
    @Volatile
    private var lastClearAt = 0L

    /**
     * 反解 [LogEntry.format] 的单行格式：`HH:mm:ss.SSS LEVEL [tag] message`。
     *
     * 与那边是一对，改格式必须两边一起改——所以正则写成整行匹配（`matchEntire`），
     * 格式一变匹配立刻失败（退化成"这条历史读不出来"），而不是悄悄解析出半截内容。
     */
    private val LINE_RE =
        Regex("""^(\d{2}):(\d{2}):(\d{2})\.(\d{3}) (DEBUG|INFO|WARN|ERROR) \[([^\]]*)] (.*)$""")

    private val buffer: MutableList<LogEntry> =
        Collections.synchronizedList(ArrayList<LogEntry>())

    /**
     * 写盘串行化：`limitedParallelism(1)` 让落盘只用一个线程，
     * 既不阻塞 IO 线程池其他任务，也不会因每条日志一个协程导致行交错/堆积。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    @Volatile
    private var logDir: File? = null

    /**
     * 写盘代数：[clear] 时递增，队列里排着的旧写入醒来发现代号变了就丢弃。
     *
     * 没有它的话，「清空」和「异步落盘」会打架——删完文件，排队的 append 又把旧行写回来，
     * 用户看到的是「清空了但旧日志还在」（2026-11-25 真机）。
     */
    @Volatile
    private var generation = 0

    /**
     * 落盘与清空共用的锁：保证「代数比对 + 写文件」不会被「递增 + 删文件」插进中间。
     *
     * 单独一把锁，不复用 [buffer]——buffer 的临界区只该管内存，把文件 IO 塞进去
     * 会让 UI 线程取日志时陪着等磁盘。
     */
    private val writeLock = Any()

    /**
     * 该工具的入参与输出是否「只记长度、不记原文」（审计 F12）。
     *
     * 判据本体在 [LogRedaction]，与 `ToolCatalog` 一起是零依赖纯 Kotlin——
     * 这样「哪些工具算敏感」能被 JVM harness 直接断言，而不是只靠 grep 源码。
     */
    fun isSensitiveTool(name: String): Boolean = LogRedaction.isSensitiveTool(name)

    /**
     * URL 进日志前先裁掉 query 与 fragment（审计 F12）。
     * query 里常见 `?code=` / `?access_token=`，一条 DEBUG 日志就够把授权码带出去。
     */
    fun redactUrl(url: String): String = LogRedaction.redactUrl(url)

    /**
     * 日志脱敏：把 ApiKey / Bearer / api_key / token / password 等敏感片段替换为 `***`。
     *
     * 公开给调用方（如 LLMClient 抛出的 IOException 文案、ChatViewModel 写进聊天气泡的错误文本）
     * 在离开日志系统之前先自行脱敏。规则本体在 [LogRedaction]。
     *
     * @param s 原始文本；为空或正则异常时原样返回，保证「旁路不影响主流程」。
     */
    fun sanitize(s: String): String = LogRedaction.sanitize(s)

    /** 初始化落盘目录、清理历史日志并启动异步写盘（幂等，可重复调用切换到新目录）。 */
    /**
     * 初始化日志目录。**幂等**——重复调用是 no-op。
     *
     * 幂等是必需的：`init` 会回读上一次运行的尾巴追加进内存缓冲，
     * 调两次就把同一段历史**追加两遍**，UI 上看到的日志会重复。
     *
     * （R3 把首次调用从 ChatViewModel 挪到了 Application.onCreate，
     * 那里是进程级最早点；ChatViewModel 里保留一次调用作兜底。）
     */
    fun init(dir: File) {
        if (initialized.compareAndSet(false, true)) {
            doInit(dir)
            return
        }
        // 已初始化：只补目录（万一首次 init 失败过），不重复回读历史
        runCatching { if (!dir.exists()) dir.mkdirs() }
        logDir = dir
    }

    /**
     * 断开落盘目录（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 复位 `initialized`：不清的话，下一个用例 `init(另一个临时目录)` 只会走到
     * 「已初始化 → 补目录」那条分支，不重新回读、也不重置序号，断言全部取决于
     * 前一个用例跑没跑。
     *
     * 代价是：reset 之后再次 init 会**重新回读历史日志尾巴**。这在生产里不该发生
     * （生产从不在运行期调 reset），在 harness 里正好是想要的干净起点。
     */
    fun reset() {
        logDir = null
        initialized.set(false)
    }

    /** 初始化标记（保证 [init] 只真正执行一次）。 */
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun doInit(dir: File) {
        runCatching { if (!dir.exists()) dir.mkdirs() }
        logDir = dir
        // 采集必须在任何一行日志写盘之前。
        //
        // `已就绪` 那行本身会走 appendToDisk，把当天的文件建出来——如果先写再采，
        // 「启动前到底有没有今天的文件」这个信息就被自己抹掉了。真机上正是靠这一点
        // 才分得清「文件被删了」和「文件从来没建过」。
        val existingBrief = runCatching {
            val list = logFilesIn(dir)
            if (list.isEmpty()) {
                "（无）"
            } else {
                "${list.size} 个：" + list.joinToString(" · ") { "${it.name}(${it.length()}B)" }
            }
        }.getOrDefault("（读取失败）")
        // 先回读上一次运行的尾巴，再写本次启动的第一条。
        //
        // 顺序不能反：反了的话「已就绪」这行会排在历史之前，UI 按时间正序渲染，
        // 一眼看过去像是上次的日志跑到了本次之后。
        val restored = runCatching { restoreTail(dir) }.getOrDefault(0)
        if (restored > 0) {
            i(
                "AgentLogger",
                "已恢复上次运行的最后 $restored 条日志（崩溃前那几行在最上面，" +
                    "不要点「清空」，否则本次取证就没了）",
            )
        }
        i("AgentLogger", "日志目录已就绪：${dir.absolutePath}")
        // 每次启动一行清单：这是「昨天那份还在不在」的对照锚点。
        // 没有它的时候，只能靠「搜某个词有没有结果」反推，而反推的前提一错就要多改一版。
        i("AgentLogger", "启动时目录内日志文件：$existingBrief")
        // 清理放到串行 IO 协程：不阻塞主线程，也不与正在进行的写盘竞争
        ioScope.launch { cleanupOldLogs(dir, LOG_RETENTION_DAYS) }
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.INFO, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = record(LogLevel.ERROR, tag, msg, t)

    /**
     * 工具调用入参。
     * 敏感工具只记 `参数已脱敏(长度=N)`，绝不记原文。
     *
     * @param sensitive 由调用方判定「这个工具这次调用敏不敏感」，默认查 [LogRedaction] 的
     *   静态名单。**动态注册的工具（MCP）必须显式传 true**——它们不在 `ToolCatalog` 里，
     *   静态名单覆盖不到（审计 F13）。判据在调用方手里，因为只有它拿得到工具实例。
     */
    fun toolCall(
        name: String,
        argsPreview: String,
        sensitive: Boolean = LogRedaction.isSensitiveTool(name)
    ) {
        val shown = if (sensitive) {
            "参数已脱敏(长度=${argsPreview.length})"
        } else {
            argsPreview
        }
        d("Tool", "→ $name $shown")
    }

    /**
     * 工具返回结果（是否出错 / 耗时 / 结果预览）。
     * 敏感工具只记 `结果已脱敏(长度=N)`，绝不记内容。
     *
     * @param sensitive 同 [toolCall]：动态注册的工具不在静态名单里，调用方必须显式判定。
     */
    fun toolResult(
        name: String,
        ok: Boolean,
        ms: Long,
        preview: String,
        sensitive: Boolean = LogRedaction.isSensitiveTool(name)
    ) {
        val level = if (ok) LogLevel.DEBUG else LogLevel.WARN
        val shown = if (sensitive) {
            "结果已脱敏(长度=${preview.length})"
        } else {
            preview
        }
        record(level, "Tool", "← $name ${if (ok) "ok" else "ERROR"} ${ms}ms $shown")
    }

    /** LLM 请求发出前的记录：只记模型名/消息数/工具数，绝不记 apiKey。 */
    fun llmRequest(model: String, msgCount: Int, toolCount: Int) {
        i("LLM", "→ 请求 model=$model messages=$msgCount tools=$toolCount")
    }

    /**
     * 记录本次请求实际使用的端点/模型/Key 指纹，让 401 能在日志里自证。
     *
     * 只记 head/tail/length 三段指纹，沿用 [sanitize] 的脱敏铁律：**绝不**记完整 Key。
     * 当 Key 夹带了不可见字符（removedCount>0）时追加 ⚠️ 告警——这是本次 401 Bug 的
     * 关键诊断信号：用户肉眼看不出的 NBSP/零宽字符，在这里被抓个正着。
     *
     * @param endpoint 实际发起请求的完整 URL（已拼接 /chat/completions）。
     * @param model 实际使用的模型名。
     * @param apiKey 用户原始 Key（只取指纹，不入日志全文）。
     */
    fun apiTrace(endpoint: String, model: String, apiKey: String) {
        val insp = ConfigSanitizer.inspectKey(apiKey)
        val finger = "key[len=${insp.length} head=${insp.head} tail=${insp.tail}]"
        val msg = if (insp.removedCount > 0) {
            "→ 端点 endpoint=$endpoint model=$model $finger " +
                "⚠️ API Key 含 ${insp.removedCount} 个不可见字符（已自动清除，可能粘贴不完整/夹带空格）"
        } else {
            "→ 端点 endpoint=$endpoint model=$model $finger"
        }
        record(LogLevel.INFO, "LLM", msg)
    }

    /** LLM 响应（含耗时与预览）。 */
    fun llmResponse(model: String, ok: Boolean, ms: Long, preview: String) {
        val level = if (ok) LogLevel.INFO else LogLevel.ERROR
        record(level, "LLM", "← 响应 model=$model ${if (ok) "ok" else "FAIL"} ${ms}ms $preview")
    }

    /**
     * HTTP 非 2xx：记状态码 + 响应体预览（截断 800），这是定位鉴权/参数错误的关键。
     * 调用方（LLMClient）已先行脱敏；[record] 会再脱一次，双保险防止 401 响应体回显密钥。
     */
    fun httpError(code: Int, bodyPreview: String) {
        record(LogLevel.ERROR, "HTTP", "请求失败 code=$code body=${bodyPreview.take(PREVIEW_MAX)}")
    }

    /** 最近的日志（最新的在前），默认 200 条。 */
    fun recent(limit: Int = 200): List<LogEntry> = synchronized(buffer) {
        val size = buffer.size
        val from = (size - limit).coerceAtLeast(0)
        val tail = ArrayList<LogEntry>(size - from)
        for (i in size - 1 downTo from) tail.add(buffer[i])
        tail
    }

    /** 导出全文（按时间正序），供用户复制到剪贴板排查。 */
    fun snapshotText(limit: Int = 500): String = synchronized(buffer) {
        val from = (buffer.size - limit).coerceAtLeast(0)
        (from until buffer.size).joinToString("\n") { buffer[it].format() }
    }

    /**
     * 在「内存缓冲 + 磁盘历史」里搜关键词（2026-11-25）。
     *
     * ## 为什么搜磁盘
     * UI 原来只展示内存里最新 200 条（`LogsFacade`），大约十几分钟的量，
     * 而磁盘上留着 `agent-<yyyyMMdd>.log` **7 天**、每天最多 4MB（2MB 正文 + 一份轮转归档）。
     * 用户上午见过的 401、昨天那次工具失败，全都在文件里，只是页面没让他看见。
     * 搜索若能覆盖这 7 天，才谈得上「找得到」。
     *
     * ## 顺序与截断
     * 先内存（最新）后磁盘，磁盘按**日期倒序**，同一天先读 `.log` 再读 `.log.1`
     * （后者是轮转出去的前半段，更旧）。命中数到 [limit] 就停——
     * 找 `401` 这类常见串通常在今天的文件里就够了，只有罕见词才会读满 7 天。
     *
     * ## 边界
     *  - 只看主行：`format()` 有堆栈时返回多行，堆栈续行没有独立时间戳，
     *    混进结果会让「最新在前」失去依据。要搜堆栈内容，主行的异常摘要通常也在。
     *  - 去重按整行文本：内存与文件里同一条会同时出现（落盘后内存不删），
     *    不去重就会看到两条一模一样的记录。
     *  - 全程 `runCatching`：文件被删/权限不足/读到半行，都只是少几条结果。
     *
     * **阻塞调用**（逐行读磁盘）。UI 侧必须放到 IO 线程，不要在组合期直接调。
     *
     * @param terms 已按空白拆好的关键词，全部命中才算命中（AND）
     * @param maxDays 最多回溯几天（磁盘保留期是 7 天，超过也没文件）
     * @param limit 结果上限
     */
    fun search(
        terms: List<String>,
        maxDays: Int = 7,
        limit: Int = 300
    ): LogSearchResult {
        if (terms.isEmpty()) return LogSearchResult.EMPTY

        val hits = ArrayList<LogHit>(limit.coerceAtMost(64))
        val seen = HashSet<String>()
        var truncated = false

        // ① 内存缓冲（最新在前）。
        //
        // 内存必须参与：落盘走的是异步串行协程，刚发生的那几十条可能还没写进文件，
        // 而「刚出的错」恰恰是最常被搜的东西。
        val memory = synchronized(buffer) { ArrayList(buffer) }
        for (i in memory.indices.reversed()) {
            if (hits.size >= limit) { truncated = true; break }
            // 只看主行：有堆栈时 format() 是多行，拿去当一行会破坏对齐与去重键。
            val head = memory[i].format().substringBefore('\n')
            if (!seen.add(head)) continue
            // 日期由 timeMs 算，不靠「它在内存里」猜。
            //
            // 猜错过一次：这批内存行里混着 [restoreTail] 从**昨天**文件读回来的历史，
            // 曾经一律当 null（=「本次」）显示，于是昨天的 23:12 被标成本次运行。
            if (LogSearch.matches(head, terms)) hits += LogHit(head, formatDay(memory[i].timeMs))
        }

        // 读一次到局部：logDir 是 @Volatile，循环中途被 reset 会出现「半天读不到文件」的半截行为。
        val dir = logDir
        val diskDays = ArrayList<String>()
        val memoryHits = hits.size
        // 无条件列出目录里的日志文件。
        //
        // 上一版只在「一个文件都没扫到」时列，于是「有今天、没有昨天」这种缺失
        // 完全隐形：屏幕只写「已搜索 11-25 当天的日志」，看起来像正常结果。
        // 真机上 11-22/11-23/11-24 三份文件在 885→888 之间消失了，而四条删除路径
        // （clear / cleanupOldLogs / rotate / init）都不该删它们——没有清单就只能猜。
        val logFiles = ArrayList<String>()
        if (dir != null) {
            logFilesIn(dir).forEach { logFiles += "${it.name}(${it.length()}B)" }
            val now = System.currentTimeMillis()
            for (offset in 0 until maxDays) {
                if (hits.size >= limit) { truncated = true; break }
                val day = formatDay(now - offset * DAY_MS)
                val parts = listOf(File(dir, "agent-$day.log"), File(dir, "agent-$day.log.1"))
                    .filter { it.isFile }
                if (parts.isEmpty()) continue
                diskDays += day
                for (file in parts) {
                    if (hits.size >= limit) { truncated = true; break }
                    // 只保留「最新的 limit 条命中」。
                    //
                    // 正序读文件时**不能读到 limit 就停**——那样拿到的是文件里最早的
                    // limit 条，而用户要的是最近的。所以用有界窗口收：满了就从头部丢一条，
                    // 同时记下「丢过」，读完窗口里就是末尾（最新）的那些，内存也恒定。
                    val dayHits = ArrayDeque<String>()
                    var dayOverflow = false
                    runCatching {
                        file.useLines { lines ->
                            for (raw in lines) {
                                // 先字面匹配再跑正则：2MB 文件约 1.7 万行，
                                // 每行都 matchEntire 一次是白花时间，正则只该落在命中行上。
                                if (!LogSearch.matches(raw, terms)) continue
                                // 非主行（堆栈续行）不成条：它没有自己的时间戳。
                                if (LINE_RE.matchEntire(raw) == null) continue
                                if (!seen.add(raw)) continue
                                dayHits.addLast(raw)
                                if (dayHits.size > limit) {
                                    dayHits.removeFirst()
                                    dayOverflow = true
                                }
                            }
                        }
                    }
                    if (dayOverflow) truncated = true
                    // 窗口内是正序（早→晚），倒序输出才符合「最新在前」。
                    for (raw in dayHits.reversed()) {
                        if (hits.size >= limit) { truncated = true; break }
                        hits += LogHit(raw, day)
                    }
                }
            }
        }
        // `days` 回答的是「这次的 N 条来自哪几天」，所以按**命中行自己的日期**汇总，
        // 而不是「扫过哪些有文件的天」——后者会把空白天也算进范围：
        // 屏幕上写着「在 11-22 至 11-25 的日志中命中 102 条」，实际一条 11-22 的都没有。
        //
        // 一条都没命中时反过来：这时用户想知道的是「你到底搜过哪儿」，
        // 用 diskDays（扫描范围），空态文案才答得上来。
        val days = if (hits.isEmpty()) {
            diskDays
        } else {
            hits.map { it.day }.distinct().sortedDescending()
        }

        // 一个文件都没扫到：把目录里到底有什么记下来，别让「今天」这种兜底文案
        // 把失败说成正常。真机上一次这样的静默，代价是三轮猜测性的改版
        // （2026-11-25：搜昨天的串有结果、搜不存在的词却说「已搜索今天」）。
        val otherEntries = if (dir != null && diskDays.isEmpty()) {
            collectMissDiagnostics(dir)
        } else {
            emptyList()
        }

        return LogSearchResult(
            hits = hits,
            days = days,
            truncated = truncated,
            fromDisk = hits.size - memoryHits,
            dirPath = dir?.absolutePath,
            otherEntries = otherEntries,
            files = logFiles
        )
    }

    /**
     * 目录里没有 `agent-*.log` 时的取证：列几个实际条目，并往日志里写一条（30 秒最多一次）。
     *
     * 为什么列「别的条目」而不是只说「目录为空」：真机上目录里几乎总有 `turns-*.ndjson`，
     * 所以「空目录」和「日志写到了别处/别的名字」看起来一模一样，而修法完全不同。
     * 列表里出现 `agent_20261125.log`（下划线）或根本只有 turns 文件，一眼就能分辨。
     *
     * 为什么节流：搜索是按输入触发的，敲一个词可能跑十几次，不节流会把真正有用的行淹掉。
     */
    private fun collectMissDiagnostics(dir: File): List<String> {
        val entries = runCatching {
            dir.listFiles()
                ?.take(LogSearchResult.MAX_OTHER_ENTRIES)
                ?.map { "${it.name}(${it.length()}B)" }
                ?: emptyList()
        }.getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        // 刚删完的静默期：目录为空是用户自己按的，不该被记成故障，更不该因此重建文件。
        // 静默期外的节流照旧（搜索按输入触发，不节流会刷屏）。
        if (now - lastClearAt >= CLEAR_QUIET_MS &&
            now - lastDiskMissWarnAt >= DISK_MISS_WARN_INTERVAL_MS
        ) {
            lastDiskMissWarnAt = now
            val detail = if (entries.isEmpty()) "目录为空" else "实际条目：" + entries.joinToString("，")
            w(
                "LogSearch",
                "目录 ${dir.absolutePath} 下没有任何 agent-*.log（$detail）——" +
                    "本次搜索只能覆盖内存缓冲里的记录",
            )
        }
        return entries
    }

    /**
     * 目录里的日志文件（`agent-*.log` / `.log.1`），新的在前。
     *
     * 只此一处定义「哪些文件算日志」：搜索页的文件清单与启动时的取证共用它。
     * 两处各写一遍过滤条件，迟早会在某次改动后分叉，然后屏幕和日志各说一套。
     */
    private fun logFilesIn(dir: File): List<File> = runCatching {
        dir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith("agent-") &&
                    (it.name.endsWith(".log") || it.name.endsWith(".log.1"))
            }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 清空日志：内存缓冲 + 目录下**全部**日志文件（当日、历史、轮转归档）。
     *
     * ## 为什么必须删全部（2026-11-25 真机）
     *
     * 原来只删 `agent-<今天>.log` 与 `agent-<今天>.log.1`。用户点过「清空」之后，
     * 昨天、前天的文件一行没动，搜索照样命中两三天前的记录——用户的反馈原话是
     * 「我每次有时候都清空的，但不知道为什么你还能收到两三天前的」。
     * 对他而言「清空」= 删掉日志；对代码而言只是删掉今天这一份。日志里带着提问原文，
     * 所以这不是显示问题，是**没删干净**。
     *
     * ## 为什么先递增 [generation]，且要和写入共用一把锁
     *
     * 落盘是异步的（单线程 IO 协程）。点清空那一刻，队列里可能还排着几条 [appendToDisk]；
     * 删完文件后它们醒来，会重建当天文件并把旧内容写回去——用户看到「已清空」却又冒出旧行。
     * 递增代数让排队中的写入带一个过期标记，醒来先比对，过期直接丢弃。
     *
     * 光有代数还不够：`if (gen != generation) return` 与随后的 `appendText` 之间不是原子的。
     * 检查刚通过、clear 就插进来递增并删文件、这条再写下去——旧行照样回来。
     * 这个窗口在本地快机器上几乎撞不到，CI 的慢机器上 500 条积压时一次就中（2026-11-25 run 892）。
     * 所以「比对 + 写」和「递增 + 删」都必须落在同一把 [writeLock] 里。
     *
     * @return 实际删除的文件数（0 表示目录里本来就没有文件）。
     */
    fun clear(): Int {
        synchronized(buffer) { buffer.clear() }
        return synchronized(writeLock) {
            generation++
            // 静默期起点：随后的搜索即使发现「目录为空」，也不再写那条会自建文件的 WARN。
            lastClearAt = System.currentTimeMillis()
            val dir = logDir ?: return@synchronized 0
            var deleted = 0
            logFilesIn(dir).forEach { f ->
                if (runCatching { f.delete() }.getOrDefault(false)) deleted++
            }
            deleted
        }
    }

    /** 当日日志文件：`dir/agent-<yyyyMMdd>.log`；未初始化时返回 null。 */
    fun currentLogFile(): File? {
        val dir = logDir ?: return null
        return runCatching { File(dir, "agent-${formatDay(System.currentTimeMillis())}.log") }.getOrNull()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 唯一出口：所有日志消息在入缓冲/落盘前统一过一遍 [sanitize]，一处覆盖全部日志形态。
     */
    private fun record(level: LogLevel, tag: String, msg: String, t: Throwable? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, sanitize(msg), stackOf(t))
        runCatching {
            synchronized(buffer) {
                buffer.add(entry)
                // 环形缓冲：超过上限移除最旧
                var overflow = buffer.size - MAX_MEMORY
                while (overflow > 0) { buffer.removeAt(0); overflow-- }
            }
        }
        val dir = logDir ?: return
        val gen = generation
        ioScope.launch { appendToDisk(dir, entry, gen) }
    }

    private fun appendToDisk(dir: File, entry: LogEntry, gen: Int) {
        // 比对与写入必须在同一把锁里：中间被 clear 插进来，旧行就会被写回刚删干净的文件。
        synchronized(writeLock) {
            runCatching {
                // 排队期间用户点了「清空」：这条属于上一代，丢掉。
                if (gen != generation) return
                if (!dir.exists() && dir.mkdirs().not()) return
                val file = currentLogFile() ?: File(dir, "agent-${formatDay(entry.timeMs)}.log")
                if (file.exists() && file.length() > MAX_FILE_BYTES) rotate(file)
                file.appendText(entry.format() + "\n")
            }
        }
    }

    /** 轮转：当前文件重命名为 `<name>.1`（旧的 .1 直接覆盖删除）。 */
    private fun rotate(file: File) {
        val backup = File(file.parentFile, "${file.name}.1")
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { file.renameTo(backup) }
    }

    /**
     * 把上一次运行留在磁盘上的最后若干条日志读回内存缓冲。
     *
     * ## 为什么非做不可
     * 内存缓冲是**进程级**的。native 崩溃（SIGSEGV / SIGABRT，[CrashLogger] 抓不到）、
     * 被系统 OOM killer 杀掉、用户从最近任务划掉——这几种情况下一次启动时 `buffer` 是空的，
     * 用户点开「运行日志」只看得到**本次**启动之后的那十几行，而崩溃发生在**上一次**运行里。
     *
     * 于是「闪退 + 日志一片空白」成了固定现象：不是没记，是记了却没人看得到。
     * 2026-10-29 真机连续五次闪退，每次贴过来的日志都只有启动段（`崩溃捕获已安装`、
     * `设备画像`、`后台 WebView 已就绪`），一行推理记录都没有——就是这个原因。
     *
     * ## 边界
     *  - 只回读当天与前一天的 `agent-<yyyyMMdd>.log`：更早的跟这次崩溃无关，
     *    读进来只会把真正相关的那几十行挤出 [MAX_MEMORY]。
     *  - 每个文件只取尾部 [TAIL_LINES] 行：整份文件可能 2MB，而现场在结尾。
     *  - 超出 [MAX_MEMORY] 从头部丢，保留最近的。
     *  - 一切异常都吞掉：日志是旁路，不能因为回读失败耽误启动。
     *
     * @return 实际恢复的条数（0 表示没有历史或全部解析失败）
     */
    private fun restoreTail(dir: File): Int {
        val now = System.currentTimeMillis()
        // 前一天也读：崩溃正好落在零点前后时，现场留在昨天那份文件里。
        //
        // 日期要跟着文件走。**文件名就是这一行所属的日期**，而 `HH:mm:ss.SSS` 里没有日期。
        // 早先按「今天该时刻」还原，昨天 08:00 的行就变成了今天 08:00——今天早已过了 8 点，
        // 连「未来则减一天」那条兜底都救不了它。于是恢复出来的历史与刚写的行混在同一天，
        // 搜索结果里昨天的事全标成今天。
        val files = listOf(formatDay(now - DAY_MS), formatDay(now))
            .map { day -> day to File(dir, "agent-$day.log") }
            .filter { it.second.isFile }

        val parsed = ArrayList<LogEntry>()
        for ((day, file) in files) {
            runCatching {
                val lines = file.readLines()
                val from = (lines.size - TAIL_LINES).coerceAtLeast(0)
                for (raw in lines.subList(from, lines.size)) {
                    val m = LINE_RE.matchEntire(raw)
                    if (m == null) {
                        // 不是新记录：多半是上一条的堆栈续行（[LogEntry.format] 会换行追加）。
                        val last = parsed.lastOrNull()
                        if (last != null && raw.isNotBlank()) {
                            parsed[parsed.lastIndex] =
                                last.copy(stack = ((last.stack ?: "") + "\n" + raw).trim())
                        }
                        continue
                    }
                    val v = m.groupValues
                    parsed.add(
                        LogEntry(
                            timeMs = clockToMillis(day, v[1], v[2], v[3], v[4]),
                            // 级别字符串是我们自己写的，认不出时按 INFO 处理，
                            // 不因为一条坏记录丢掉整批历史。
                            level = runCatching { LogLevel.valueOf(v[5]) }.getOrDefault(LogLevel.INFO),
                            tag = v[6],
                            message = v[7],
                        )
                    )
                }
            }
        }
        if (parsed.isEmpty()) return 0

        val tail = if (parsed.size > MAX_MEMORY) {
            parsed.subList(parsed.size - MAX_MEMORY, parsed.size)
        } else {
            parsed
        }
        synchronized(buffer) { buffer.addAll(0, tail) }
        return tail.size
    }

    /**
     * 把 `yyyyMMdd` + `HH:mm:ss.SSS` 还原成时间戳。
     *
     * 日期必须由调用方给（来自日志文件名），不能拿「现在」去猜：昨天文件里的 08:00
     * 与今天文件里的 08:00 字面完全一样，只有文件名能分开。早先那版用「今天该时刻 +
     * 未来则减一天」，只能救回 23:50 这种跨零点的行，昨天白天的行全塌到今天。
     *
     * 解析失败时返回当前时刻——宁可这一条的时间差一天，也不能把它整条丢掉。
     */
    private fun clockToMillis(day: String, h: String, m: String, s: String, ms: String): Long =
        runCatching {
            synchronized(clockLock) {
                dayTimeFormat.parse("$day $h:$m:$s.$ms")!!.time
            }
        }.getOrDefault(System.currentTimeMillis())

    /**
     * 清理过期日志：`agent-<yyyyMMdd>.log` / `.log.1`，保留最近 [keepDays] 天。
     * 文件名中的日期解析失败（非 `yyyyMMdd` 或非法日期）时跳过该文件，不做猜测删除。
     */
    private fun cleanupOldLogs(dir: File, keepDays: Long) {
        runCatching {
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - keepDays * DAY_MS
            // 局部 SimpleDateFormat（非线程安全，故不用共享实例）；lenient=false 拒绝 20241340 这类非法日期
            val dayParser = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply { isLenient = false }
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (!name.startsWith("agent-")) continue
                if (!name.endsWith(".log") && !name.endsWith(".log.1")) continue
                val dayPart = name.removePrefix("agent-").substringBefore(".log")
                val dayMs = runCatching { dayParser.parse(dayPart) }.getOrNull()?.time ?: continue
                if (dayMs < cutoff) runCatching { file.delete() }
            }
        }
    }

    private fun stackOf(t: Throwable?): String? =
        t?.let { runCatching { it.stackTraceToString() }.getOrNull()?.takeIf { it.isNotBlank() } }
}

/* ------------------------- 时间格式（SimpleDateFormat 非线程安全，加锁访问） ------------------------- */

/** 预览文本（堆栈、响应体等）统一截断长度。 */
private const val PREVIEW_MAX = 800

private val clockLock = Any()
private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

/** 回读历史用：日期与时刻一起解析，避免「这行是哪天的」靠猜。lenient=false 拒绝 11-31 这类非法日。 */
private val dayTimeFormat = SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS", Locale.getDefault())
    .apply { isLenient = false }

private fun formatClock(ms: Long): String = synchronized(clockLock) { clockFormat.format(Date(ms)) }
private fun formatDay(ms: Long): String = synchronized(clockLock) { dayFormat.format(Date(ms)) }
