package com.beichen.polaris.core.build

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 构建任务状态机 + 持久化（复用 06 号文档媒体任务的同款思路：
 * JSON 文件落盘、杀进程重进续查）。
 *
 * 阶段链：PUSHING → QUEUED → BUILDING → DOWNLOADING → SUCCEEDED / FAILED。
 * 「云端排队中」是显式阶段——CI 队列可能排几分钟，文案必须说清，不能给人卡死感。
 */
@Serializable
enum class BuildStage {
    /** 正在把工作区改动推成 commit（Git Data API）。 */
    PUSHING,

    /** 已 dispatch，等 GitHub Actions 起 run。 */
    QUEUED,

    /** run 进行中（queued → in_progress 之间由轮询推进）。 */
    BUILDING,

    /** 下载 release/latest 的 APK。 */
    DOWNLOADING,

    /** 全链路成功，APK 已落盘可安装。 */
    SUCCEEDED,

    /** 任一阶段失败，[BuildTask.error] 是可读原因。 */
    FAILED,

    /**
     * 本地停止跟踪（页面关闭 / 用户停止）。
     *
     * 与 [FAILED] 分开：CI 可能仍在 GitHub 上跑，写「失败」会让人以为云端挂了；
     * 停在 [BUILDING] 又会让人以为还在盯着。这两种误导都会导致「到底要不要去 GitHub 看一眼」判断错。
     */
    CANCELLED
}

/** 构建任务记录（持久化）。 */
@Serializable
data class BuildTask(
    val id: String,
    /**
     * 有默认值是**为了让未知枚举可降级**，不是为了方便构造。
     *
     * 新版写下的 `CANCELLED` 被旧版读到会整体解析失败 → [BuildStore.list] 按空处理 →
     * 下一次 upsert 直接把整个历史覆盖成一条。一次回滚就清空构建历史，代价太大。
     * 配 `coerceInputValues = true`，未知值落到 [BuildStage.FAILED]，历史至少还在。
     */
    val stage: BuildStage = BuildStage.FAILED,
    /** 触发时的 commit sha（精确定位「哪次提交出的包」）。 */
    val commitSha: String? = null,
    /** CI run number——App 内「构建 #N」与 GitHub run number 一一对应。 */
    val runNumber: Long? = null,
    /**
     * run 的数值主键（`GET /actions/runs/{id}` 用的那个 id）。
     *
     * 与 [runNumber] 不是一回事：`run_number` 是仓库内自增序号，只出现在 list 响应里；
     * 进程被系统杀掉后重进，只有这个 id 能直接续查那一轮 run 的真实状态。
     * 没有它就只能按 sha 去 list 里翻，翻不到（run 已过期、超出首页）就彻底断了线索。
     */
    val runId: Long? = null,
    /** 下载进度 0..1（仅 DOWNLOADING 阶段非空）。 */
    val downloadProgress: Float? = null,
    /** 落盘 APK 路径（SUCCEEDED 时非空）。 */
    val apkPath: String? = null,
    /** APK SHA-256（下载后计算，供完整性核对）。 */
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isTerminal: Boolean
        get() = stage == BuildStage.SUCCEEDED ||
            stage == BuildStage.FAILED ||
            stage == BuildStage.CANCELLED
}

/** 构建任务持久化（filesDir/builds/builds.json，新的在前，最多保留 50 条）。 */
object BuildStore {

    // coerceInputValues：未知枚举（旧版读新版写的 CANCELLED）落到默认值而不是抛异常，
    // 否则整份 builds.json 解析失败会被当成空历史。
    // encodeDefaults：给 stage 加了默认值后，默认的 encodeDefaults=false 会把
    // stage=FAILED 的字段**整个省略**，旧版读到缺字段同样解析失败——反而更糟。
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val lock = Any()
    private const val MAX_HISTORY = 50

    private fun file(context: Context): File =
        File(File(context.filesDir, "builds"), "builds.json")

    /**
     * 读历史；主文件不可用时由 [AtomicJsonFile.readWithBackupOrNull] 回退 `.bak`。
     *
     * 归零的后果不止「看不见记录」——下一次 [upsert] 会把整份历史覆盖成一条。
     */
    fun list(context: Context): List<BuildTask> = synchronized(lock) {
        val text = AtomicJsonFile.readWithBackupOrNull(file(context)) { decodeOrNull(it) != null }
            ?: return@synchronized emptyList()
        decodeOrNull(text).orEmpty().sortedByDescending { it.createdAt }
    }

    /** 解析失败返回 null（不抛：调用方拿 null 判断「这份内容不可用」）。 */
    private fun decodeOrNull(text: String): List<BuildTask>? =
        runCatching { json.decodeFromString(ListSerializer(BuildTask.serializer()), text) }
            .getOrElse { e ->
                AgentLogger.e("BuildStore", "builds.json 解析失败：${e.message}")
                null
            }

    fun upsert(context: Context, task: BuildTask) = synchronized(lock) {
        val all = (list(context).filter { it.id != task.id } + task.copy(updatedAt = System.currentTimeMillis()))
            .sortedByDescending { it.createdAt }
            .take(MAX_HISTORY)
        writeAll(context, all)
    }

    /** APK 落盘目录：filesDir/builds/apk/。 */
    fun apkDir(context: Context): File =
        File(context.filesDir, "builds/apk").apply { mkdirs() }

    /**
     * 清掉不再被历史引用的 APK。
     *
     * 一次构建的包 45 MB 上下，而 [upsert] 只在历史超过 [MAX_HISTORY] 时才丢记录——
     * 也就是说构建 20 次就有 **900 MB** 谁也不会再打开的 APK 躺在私有目录里，
     * 用户唯一的感知是「手机空间又少了」，还查不到是谁占的。
     *
     * 保留最近 [keep] 个成功记录指向的包（够回滚装上一版），其余删除。
     * 只在**新包落档后**调用：下载进度回调每秒可能触发多次，不能挂在那条路径上。
     */
    fun pruneApks(context: Context, keep: Int = 3) {
        val referenced = list(context)
            .asSequence()
            .filter { it.stage == BuildStage.SUCCEEDED && it.apkPath != null }
            .take(keep)
            .mapNotNull { it.apkPath }
            .toSet()
        val files = apkDir(context).listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?: return
        files.filter { it.absolutePath !in referenced }.forEach { old ->
            val size = old.length()
            if (runCatching { old.delete() }.getOrDefault(false)) {
                AgentLogger.d("BuildStore", "清理旧 APK ${old.name}（$size B）")
            }
        }
    }

    /**
     * 落盘走 [AtomicJsonFile]（全仓唯一原子写实现）。
     *
     * 此前这里是手写的 `writeText → rename`，漏了 `fd.sync()`：`writeText` 返回只代表
     * 数据进了页缓存，rename 是元数据操作，两者落盘顺序不保证。断电后可能出现
     * 「rename 生效、文件长度 0」——而本模块的前提正是「后台构建会被系统杀掉」。
     */
    private fun writeAll(context: Context, tasks: List<BuildTask>) {
        val text = json.encodeToString(ListSerializer(BuildTask.serializer()), tasks)
        // encodeDefaults 保证 stage 的默认值也写进 JSON，旧版才不会因缺字段解析失败
        if (!AtomicJsonFile.write(file(context), text)) {
            AgentLogger.e("BuildStore", "写 builds.json 失败")
        }
    }
}
