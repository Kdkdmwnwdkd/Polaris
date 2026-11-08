package com.beichen.polaris.core.build

import android.content.Context
import com.beichen.polaris.core.AgentLogger
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
    FAILED
}

/** 构建任务记录（持久化）。 */
@Serializable
data class BuildTask(
    val id: String,
    val stage: BuildStage,
    /** 触发时的 commit sha（精确定位「哪次提交出的包」）。 */
    val commitSha: String? = null,
    /** CI run number——App 内「构建 #N」与 GitHub run number 一一对应。 */
    val runNumber: Long? = null,
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
        get() = stage == BuildStage.SUCCEEDED || stage == BuildStage.FAILED
}

/** 构建任务持久化（filesDir/builds/builds.json，新的在前，最多保留 50 条）。 */
object BuildStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private const val MAX_HISTORY = 50

    private fun file(context: Context): File =
        File(File(context.filesDir, "builds"), "builds.json")

    fun list(context: Context): List<BuildTask> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(BuildTask.serializer()), f.readText())
        }.getOrElse { e ->
            AgentLogger.e("BuildStore", "builds.json 解析失败，按空处理：${e.message}")
            emptyList()
        }.sortedByDescending { it.createdAt }
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

    private fun writeAll(context: Context, tasks: List<BuildTask>) {
        val f = file(context)
        f.parentFile?.mkdirs()
        runCatching { f.writeText(json.encodeToString(ListSerializer(BuildTask.serializer()), tasks)) }
            .onFailure { AgentLogger.e("BuildStore", "写 builds.json 失败：${it.message}") }
    }
}
