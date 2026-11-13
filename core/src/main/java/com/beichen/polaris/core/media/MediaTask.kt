package com.beichen.polaris.core.media

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.runCancellable
import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 媒体任务模型 + 持久化 + 下载落盘（调研 06 号文档「任务状态机 + 持久化」直译）。
 *
 * 存储：`filesDir/media/tasks.json`（与 MemoryStore 同款朴素方案，不自建数据库）。
 * 资产真理：完成的 URL 下载到 `filesDir/media/outputs/<taskId>/`——
 * **本地路径才是 truth**（各家 URL 都会过期），UI 与 Agent 工具只认本地路径。
 */

/** 任务状态（UI 三态与持久化四态对齐：SUBMITTED 是「已提交未开跑」的瞬时态）。 */
@Serializable
enum class TaskStatus { SUBMITTED, RUNNING, SUCCEEDED, FAILED }

/** 持久化任务记录（直接 @Serializable 落 JSON，字段带默认值兼容旧文件）。 */
@Serializable
data class MediaTask(
    val id: String,
    val providerId: String,
    val providerTaskId: String? = null,
    val request: MediaRequest,
    val status: TaskStatus,
    val progress: Float? = null,
    /** 本地落盘路径（资产真理，UI 只认这个）。 */
    val resultPaths: List<String> = emptyList(),
    /** 云端 URL（备份/分享用，会过期）。 */
    val resultUrls: List<String> = emptyList(),
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isActive: Boolean get() = status == TaskStatus.SUBMITTED || status == TaskStatus.RUNNING
}

/**
 * 任务持久化（JSON 文件）。杀进程重进 App 按 tasks.json 续查——
 * 这是任务模型的灵魂：长任务不随进程消亡。
 */
object MediaTaskStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private fun file(context: Context): File =
        File(File(context.filesDir, "media"), "tasks.json")

    /** 全部任务（新的在前）。 */
    fun list(context: Context): List<MediaTask> = synchronized(lock) {
        readAll(context).sortedByDescending { it.createdAt }
    }

    /**
     * 读全量任务。解析失败时**先把坏文件隔离**，再按空处理。
     *
     * 修前这里只 `runCatching { ... }.getOrElse { emptyList() }`：日志里留一句，
     * 返回空列表，然后任何一个 `upsert` 都会拿这份空列表去覆盖 `tasks.json`——
     * 一次解析失败 = 整个任务史被抹掉，且用户在界面上只会看到「任务都没了」。
     *
     * 现在把坏文件改名成 `tasks.corrupt-<时间戳>.json` 留在同目录：新写入不会碰它，
     * 用户/开发者仍能捞回原文。文件本身没删，只是不再被当作当前库。
     */
    private fun readAll(context: Context): List<MediaTask> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MediaTask>>(f.readText())
        }.getOrElse { e ->
            // 只有「内容确实解析不了」才隔离。IO 失败（存储满、瞬时 EIO、文件被占用）
            // 说明文件本身很可能是好的，改名等于把好文件藏起来——用户看到的仍然是
            // 「任务全没了」，而下次启动也读不回来。那种情况保持原文件不动，下次再试。
            val corrupt = e is SerializationException
            val kept = if (corrupt) quarantine(f) else null
            val where = when {
                kept != null -> "已隔离为 ${kept.name}"
                corrupt -> "改名失败，保持原文件不动"
                else -> "疑似读取失败（非解析错误），保持原文件不动"
            }
            AgentLogger.e(
                "MediaTaskStore",
                "tasks.json 读取失败（${e.javaClass.simpleName}: ${e.message}），$where；本次按空库启动"
            )
            emptyList()
        }
    }

    /** 把损坏文件改名隔离，返回隔离后的文件；改名失败返回 null（调用方据此保持原文件不动）。 */
    private fun quarantine(f: File): File? = runCatching {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        var target = File(f.parentFile, "tasks.corrupt-$stamp.json")
        var seq = 2
        while (target.exists() && seq < 100) {
            target = File(f.parentFile, "tasks.corrupt-$stamp-$seq.json")
            seq++
        }
        if (f.renameTo(target)) target else null
    }.getOrNull()

    /** 插入或更新（按 id）。 */
    fun upsert(context: Context, task: MediaTask) = synchronized(lock) {
        val all = list(context).filter { it.id != task.id } + task.copy(updatedAt = System.currentTimeMillis())
        writeAll(context, all.sortedByDescending { it.createdAt })
    }

    /** 删除任务及其输出目录。 */
    fun delete(context: Context, id: String) = synchronized(lock) {
        writeAll(context, list(context).filter { it.id != id })
        outputsDir(context, id).deleteRecursively()
    }

    /** 新建任务的 id（调用方填好其余字段后 upsert）。 */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 全量落盘，走 [AtomicJsonFile]（tmp → fsync → rename）。
     *
     * 原地 `writeText` 写到一半被杀（长任务常伴随后台被杀/断电），留下的是半截 JSON，
     * 下次启动就走进 [readAll] 的解析失败分支——那条分支再干净也只能保住历史，
     * 保不住「这次写的内容」。
     */
    private fun writeAll(context: Context, tasks: List<MediaTask>) {
        val text = runCatching {
            json.encodeToString(ListSerializer(MediaTask.serializer()), tasks)
        }.getOrElse { e ->
            AgentLogger.e("MediaTaskStore", "序列化 tasks 失败，本次不写：${e.message}")
            return
        }
        // 走 [AtomicJsonFile]：手写版缺 fd.sync()，断电后可能「rename 生效、文件长度 0」。
        // 读路径仍保留 [readAll] 的坏文件隔离——那份语义比通用回退更细，不在本次收口范围。
        if (!AtomicJsonFile.write(file(context), text)) {
            AgentLogger.e("MediaTaskStore", "写 tasks.json 失败")
        }
    }

    /** 任务输出目录：filesDir/media/outputs/<taskId>/。 */
    fun outputsDir(context: Context, taskId: String): File =
        File(File(context.filesDir, "media/outputs"), taskId)
}

/**
 * 结果下载器：把云端 URL（或 data URI）落到任务输出目录。
 *
 * data URI（OpenAI gpt-image-1 的 b64_json 封装）直接解码落盘，不走网络。
 * 扩展名优先取 URL 路径，其次 Content-Type，最后按 kind 兜底。
 */
object MediaDownloader {

    private val http = LlmHttp.download

    /**
     * 下载全部 URL，返回成功落盘的本地路径列表（部分失败不阻断，
     * 失败的 URL 记入日志——作品库少一张图好过整个任务判失败）。
     */
    suspend fun download(
        context: Context,
        taskId: String,
        urls: List<String>,
        kind: MediaKind
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = MediaTaskStore.outputsDir(context, taskId).apply { mkdirs() }
        val paths = mutableListOf<String>()
        urls.forEachIndexed { i, url ->
            try {
                paths += fetch(dir, i, url, kind)
            } catch (c: CancellationException) {
                // 取消不是「这一张下载失败」：必须上抛。
                // 旧写法 `runCatching {}` 会把 CancellationException 收成单张失败并继续下一张，
                // 结果用户点了停止、任务状态机已收尾，后台仍在把 4 张图下完。
                throw c
            } catch (e: Throwable) {
                AgentLogger.e("MediaDownloader", "下载失败 $url：${e.message}")
            }
        }
        paths
    }

    private suspend fun fetch(dir: File, index: Int, url: String, kind: MediaKind): String {
        val fallbackExt = if (kind == MediaKind.TEXT_TO_VIDEO) "mp4" else "png"
        val out = File(dir, "%03d.%s".format(index, extOf(url, fallbackExt)))
        if (url.startsWith("data:")) {
            // data:[<mime>][;base64],<payload>
            val comma = url.indexOf(',')
            require(comma > 0) { "非法 data URI" }
            val meta = url.substring(0, comma)
            val payload = url.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
            }
            if (!meta.contains(";base64")) {
                // 非 base64 的 data URI 按文本内容处理（罕见），仍可落盘
            }
            // base64 解码是纯 CPU 段，插不进取消信号；落盘前主动让一次
            currentCoroutineContext().ensureActive()
            out.writeBytes(bytes)
            return out.absolutePath
        }
        val request = Request.Builder().url(url).get().build()
        return http.runCancellable(request) { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应体")
            try {
                copyCancellable(body.byteStream(), out)
            } catch (t: Throwable) {
                // 半张图 / 半个视频不留在 outputs 目录：取消后它既不会被引用，也没人清理
                out.delete()
                throw t
            }
            val ct = body.contentType()?.subtype
            val named = if (ct != null && out.extension != ct) {
                // Content-Type 与 URL 推测不一致时以 Content-Type 为准（如 ?x=1 丢弃后缀）
                File(dir, "%03d.%s".format(index, ct.substringBefore('+')))
            } else out
            if (named == out || !out.renameTo(named)) out.absolutePath else named.absolutePath
        }
    }

    /**
     * 分块拷贝 + 每块前检查协程取消。
     *
     * 不用 `copyTo`：它是一次读到底的阻塞循环，取消信号只能在整份数据读完后才生效，
     * 对几百 MB 的视频等于「没有取消」。
     */
    private suspend fun copyCancellable(input: InputStream, out: File) {
        out.outputStream().use { os ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
            }
        }
    }

    private fun extOf(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/', "")
        return if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) ext
        else fallback
    }
}
