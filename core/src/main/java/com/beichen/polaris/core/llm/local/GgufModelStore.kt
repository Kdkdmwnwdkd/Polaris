package com.beichen.polaris.core.llm.local

import com.beichen.polaris.core.llm.LlmHttp
import android.content.Context
import android.provider.OpenableColumns
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 内置 GGUF 模型目录条目。
 *
 * 全部为 HuggingFace 上广泛使用的 Q4_K_M 量化（体积/质量的黄金分割点）：
 * Q4_K_M 在 4bit 权重 + fp16 激活下，1.5B~4B 模型在手机 CPU 上可跑到
 * 5~15 tok/s，且文件 1~2.5GB，主流 8GB 内存机型无压力。
 *
 * @param supportsSystemPrompt false 时（如 Gemma-2）客户端不会注入系统提示，
 *       工具调用协议改为附在首条 user 消息上（见 LocalLlamaClient）。
 */
data class GgufCatalogEntry(
    val fileName: String,
    val displayName: String,
    val url: String,
    val approxBytes: Long,
    val paramSize: String,
    val description: String,
    val supportsSystemPrompt: Boolean = true
)

/** 已下载到本地的模型文件。 */
data class DownloadedModel(
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * GGUF 模型文件管理：内置目录 + 下载（断点续传）+ 本地文件清单。
 *
 * 存放位置：App 私有目录 files/models/（卸载 App 即清理，不需要存储权限）。
 * 下载走 OkHttp 异步回调 + suspendCancellableCoroutine：协程取消时自动取消
 * 网络请求，切页面/返回不会把下载挂死在后台。
 */
class GgufModelStore(private val context: Context) {

    private val modelsDir: File = LocalLlamaSetting.modelsDir(context)

    private val client = LlmHttp.download

    /** 内置目录：按参数量升序，越靠后越吃内存。 */
    fun catalog(): List<GgufCatalogEntry> = listOf(
        GgufCatalogEntry(
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            displayName = "Qwen2.5 0.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxBytes = 398_000_000,
            paramSize = "0.5B",
            description = "体积最小、出字最快，闲聊与整理够用；复杂工具调用选 1.5B 起"
        ),
        GgufCatalogEntry(
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            displayName = "Qwen2.5 1.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            approxBytes = 1_037_000_000,
            paramSize = "1.5B",
            description = "官方量化，中英文均衡，入门首选"
        ),
        GgufCatalogEntry(
            fileName = "qwen2.5-1.5b-instruct-q8_0.gguf",
            displayName = "Qwen2.5 1.5B Instruct（Q8_0 · GPU 用）",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q8_0.gguf",
            approxBytes = 1_894_532_128,
            paramSize = "1.5B",
            description = "想用 GPU 加速就选它：Adreno 驱动编不过 Q4_K 的着色器，Q8_0 才能卸载到显卡"
        ),
        GgufCatalogEntry(
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            displayName = "Qwen2.5 Coder 1.5B",
            url = "https://huggingface.co/lmstudio-community/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            approxBytes = 1_037_000_000,
            paramSize = "1.5B",
            description = "写代码专用微调，工具调用更稳"
        ),
        GgufCatalogEntry(
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            displayName = "Llama 3.2 1B Instruct",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            approxBytes = 807_000_000,
            paramSize = "1B",
            description = "体积最小，英文强、中文一般"
        ),
        GgufCatalogEntry(
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            displayName = "Gemma 2 2B IT",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            approxBytes = 1_710_000_000,
            paramSize = "2B",
            description = "轻量多语言；不支持系统提示词",
            supportsSystemPrompt = false
        ),
        GgufCatalogEntry(
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            displayName = "Qwen2.5 3B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            approxBytes = 1_881_000_000,
            paramSize = "3B",
            description = "质量/速度平衡点，推荐 6GB+ 内存"
        ),
        GgufCatalogEntry(
            fileName = "qwen2.5-3b-instruct-q8_0.gguf",
            displayName = "Qwen2.5 3B Instruct（Q8_0 · GPU 用）",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q8_0.gguf",
            approxBytes = 3_616_088_480,
            paramSize = "3B",
            description = "3B 里唯一能用 GPU 的版本（Q4_K 在 Adreno 上必崩）；3.4GB，建议 8GB 内存"
        ),
        GgufCatalogEntry(
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            displayName = "Llama 3.2 3B Instruct",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            approxBytes = 1_880_000_000,
            paramSize = "3B",
            description = "英文任务更强，中文一般"
        ),
        GgufCatalogEntry(
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            displayName = "Qwen3 4B",
            url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            approxBytes = 2_497_000_000,
            paramSize = "4B",
            description = "新一代架构，本地质量天花板；需 8GB+ 内存"
        )
    )

    /** 本地已下载的模型（按修改时间倒序；跳过 .part 半成品）。 */
    fun listDownloaded(): List<DownloadedModel> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.map { DownloadedModel(it.name, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun isDownloaded(fileName: String): Boolean =
        File(modelsDir, fileName).let { it.isFile && it.length() > 0 }

    /**
     * W-45：导入用户**已有**的 .gguf 文件（系统文件选择器给的是 content:// URI）。
     *
     * 真机反馈原话：「我已下好的话，不需要和内置的下（载）」。之前只有内置目录一条路，
     * 用户手机里存着模型也只能重下一遍——几 GB 的流量和等待。
     *
     * 校验两道，防的是"选错文件后加载崩溃"这种更难受的失败：
     *  1) 扩展名补成 .gguf；
     *  2) 文件头必须是 "GGUF" 魔数（4 字节 ASCII），否则直接拒绝并说明原因。
     */
    suspend fun importFrom(
        uri: android.net.Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val target = uniqueTarget(sanitize(queryDisplayName(uri)))
            val part = File(modelsDir, target.name + ".part")
            modelsDir.mkdirs()
            val total = querySize(uri)

            resolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                }
            } ?: throw IOException("无法读取所选文件（可能没有持久化访问权限）")

            val head = ByteArray(4)
            val read = part.inputStream().use { it.read(head) }
            if (read < 4 || String(head, Charsets.US_ASCII) != "GGUF") {
                part.delete()
                throw IOException("不是 GGUF 模型文件（缺少 GGUF 文件头），请确认选的是 .gguf")
            }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("写入失败，存储空间可能不足")
            }
            AgentLogger.i(
                "LocalLlama",
                "已导入本地模型 ${target.name}（${target.length() / 1024 / 1024} MB）"
            )
            target.name
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) return it }
                }
            }
        }
        return "imported-${System.currentTimeMillis()}.gguf"
    }

    private fun querySize(uri: android.net.Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 文件名清洗：只留安全字符，扩展名统一 .gguf（SAF 给的名字可能没有后缀）。 */
    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (cleaned.isBlank() || cleaned == ".gguf") {
            return "model-${System.currentTimeMillis()}.gguf"
        }
        return if (cleaned.endsWith(".gguf", ignoreCase = true)) cleaned else "$cleaned.gguf"
    }

    /** 同名不覆盖：my.gguf → my-1.gguf → my-2.gguf。 */
    private fun uniqueTarget(name: String): File {
        var f = File(modelsDir, name)
        if (!f.exists()) return f
        val stem = name.removeSuffix(".gguf").removeSuffix(".GGUF")
        var i = 1
        while (f.exists()) {
            f = File(modelsDir, "$stem-$i.gguf")
            i++
        }
        return f
    }

    fun modelFile(fileName: String): File = File(modelsDir, fileName)

    fun delete(fileName: String): Boolean {
        // 正在用的模型先卸载，避免 native 层持有已删除文件的 mmap
        val setting = LocalLlamaSetting(context)
        if (setting.activeModel == fileName) {
            LocalLlamaEngine.unload()
            setting.activeModel = ""
        }
        val f = File(modelsDir, fileName)
        val part = File(modelsDir, "$fileName.part")
        part.delete()
        return !f.exists() || f.delete()
    }

    /**
     * 下载目录条目到 models/（断点续传：.part 半成品 + Range 请求）。
     *
     * ## 下载源顺序（W-23 修）
     * HuggingFace 在国内直连不可达（沙箱实测超时），先走 **hf-mirror.com 国内镜像**
     * （实测 302 可达），镜像失败再回退官方源。不这样做用户会看到「点下载没反应」。
     *
     * @param onProgress (已下载字节, 总字节或 -1) 在 OkHttp 回调线程调用，
     *        实现方只需把值写进 StateFlow，不要碰 UI。
     * @return 成功时 Result.success(模型文件)；失败带 IOException（含取消）。
     */
    suspend fun download(
        entry: GgufCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        val target = File(modelsDir, entry.fileName)
        if (target.isFile && target.length() > 0) {
            return Result.success(target)
        }

        val sources = listOfNotNull(mirrorUrl(entry.url), entry.url).distinct()
        var lastError: Throwable? = null
        for ((index, url) in sources.withIndex()) {
            val result = downloadOnce(url, entry, target, onProgress)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            AgentLogger.w(
                "LocalLlama",
                "下载源 ${index + 1}/${sources.size} 失败：$url（${lastError?.message}）"
            )
        }
        return Result.failure(lastError ?: IOException("没有可用的下载源"))
    }

    /** HuggingFace → 国内镜像（hf-mirror.com）；非 HF 地址原样返回（不打镜像）。 */
    private fun mirrorUrl(url: String): String? =
        if (url.contains("huggingface.co/")) {
            url.replaceFirst("https://huggingface.co/", "https://hf-mirror.com/")
        } else null

    /** 单源下载一次（内部含断点续传）。 */
    private suspend fun downloadOnce(
        url: String,
        entry: GgufCatalogEntry,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> {
        val part = File(modelsDir, "${entry.fileName}.part")
        val resumeFrom = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url)
        if (resumeFrom > 0) {
            builder.header("Range", "bytes=$resumeFrom-")
            AgentLogger.i("LocalLlama", "断点续传 ${entry.fileName} @ ${resumeFrom / 1024 / 1024} MB")
        }

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(builder.build())
            // 协程取消 → 取消网络请求，回调线程随后会以 IOException 结束
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 保留 .part：多源重试时可继续断点；只有协程取消才清理
                    if (!cont.isActive) part.delete()
                    if (cont.isActive) cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val msg = "下载失败 HTTP ${response.code}"
                            if (cont.isActive) cont.resume(Result.failure(IOException(msg)))
                            return
                        }
                        val body = response.body ?: throw IOException("空响应体")
                        val append = response.code == 206 && resumeFrom > 0
                        if (!append && resumeFrom > 0) {
                            // 服务器忽略 Range 返回 200：不能往半成品后面拼，从头下
                            part.delete()
                        }
                        val base = if (append) resumeFrom else 0L
                        val total = if (body.contentLength() > 0) base + body.contentLength() else -1L
                        modelsDir.mkdirs()
                        part.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(256 * 1024)
                                var downloaded = base
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                        if (!part.renameTo(target)) {
                            throw IOException("临时文件重命名失败")
                        }
                        AgentLogger.i(
                            "LocalLlama",
                            "下载完成 ${entry.fileName}（${target.length() / 1024 / 1024} MB）"
                        )
                        if (cont.isActive) cont.resume(Result.success(target))
                    } catch (e: Exception) {
                        part.delete()
                        if (cont.isActive) {
                            cont.resume(Result.failure(e as? IOException ?: IOException(e)))
                        }
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    /** 简单 GGUF 完整性校验：文件头 magic 必须是 "GGUF"。 */
    fun isValidGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 8) return false
        return runCatching {
            file.inputStream().use { inp ->
                val magic = ByteArray(4)
                inp.read(magic, 0, 4)
                String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)
    }
}
