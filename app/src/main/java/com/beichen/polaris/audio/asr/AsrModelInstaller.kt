package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.net.BoundedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 离线识别模型的下载 / 删除。
 *
 * ## 为什么不塞进 APK
 *
 * 装包已经 361MB（两套 TTS 模型在里面）。再塞 52MB 识别模型，对「只想让桌宠说话」
 * 的人就是白付的流量与存储。而这套模型**只有第一次点语音时才会用到**——
 * 按需下载能把它从「所有人的成本」变成「用的人的 52MB」。
 *
 * ## 为什么要有 manifest
 *
 * 四个文件分开下，任何一个截断都必须被发现。只看「文件存在」不够——
 * 断在半截的 onnx 会让原生层以 `failed to create native OnlineRecognizer` 失败，
 * 而那句话对用户毫无意义。manifest 里带 size 与 sha256，下完逐个核对，
 * 坏的那个重下，不会把损坏当成功。
 *
 * manifest 格式刻意用纯文本（每行 `名字 大小 sha256`），不用 JSON：
 * 少一个解析器、少一类「字段名拼错」的故障，而且 CI 里用 shell 就能生成。
 */
class AsrModelInstaller(context: Context) {

    private val tag = "AsrDownload"

    private val asr = StreamingAsr(context)
    private val dir: File get() = asr.modelDir

    /** 模型放在 latest Release 里，与 APK 同源——国内用户已经在用这条路下 361MB 的包。 */
    private val baseUrl: String =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest"

    fun isInstalled(): Boolean = asr.isInstalled()

    fun bytesOnDisk(): Long = asr.sizeBytes()

    /**
     * 下载并校验。
     *
     * 失败会抛异常，消息直接给人看（「服务端还没准备好」「第 3 个文件校验不对」）。
     */
    suspend fun download(onProgress: (done: Long, total: Long, label: String) -> Unit) =
        withContext(Dispatchers.IO) {
            val entries = fetchManifest()
            val total = entries.sumOf { it.size }
            var done = 0L
            dir.mkdirs()
            entries.forEach { entry ->
                val target = File(dir, entry.name)
                if (target.isFile && target.length() == entry.size && entry.sha256(target)) {
                    done += entry.size
                    onProgress(done, total, entry.name)
                    return@forEach
                }
                val part = File(dir, entry.name + ".part")
                downloadFile("$baseUrl/${entry.asset}", part) { read ->
                    onProgress(done + read, total, entry.name)
                }
                if (part.length() != entry.size || !entry.sha256(part)) {
                    part.delete()
                    throw IllegalStateException("${entry.name} 校验不对（应为 ${entry.size} 字节）")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("${entry.name} 落盘失败")
                done += entry.size
                onProgress(done, total, entry.name)
            }
            AgentLogger.i(tag, "离线识别模型就绪：${bytesOnDisk() / 1048576}MB → ${dir.absolutePath}")
        }

    /** 删掉模型目录。先释放识别器，否则原生层还拿着文件句柄（Android/Linux 允许删，但内存里那份不会消失）。 */
    fun remove() {
        asr.release()
        dir.deleteRecursively()
        AgentLogger.i(tag, "离线识别模型已删除（${dir.absolutePath}）")
    }

    // —— 内部 ——

    private class Entry(val name: String, val asset: String, val size: Long, val sha256: String) {
        fun sha256(f: File): Boolean = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
        }.getOrDefault(false)
    }

    private fun fetchManifest(): List<Entry> {
        val req = Request.Builder().url("$baseUrl/asr-manifest.txt").build()
        val body = LlmHttp.short.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val why = if (resp.code == 404) "服务端还没上传模型" else "网络问题"
                throw IllegalStateException("模型清单取不到（HTTP ${resp.code}）：$why")
            }
            // 清单走有上限读取（审计 F21）：它是外部输入，长度由服务端决定。
            // 64KB 已经比真实清单大两个数量级（4 行，约 300 字节）。
            resp.body?.let { BoundedBody.read(it.byteStream(), 64L * 1024).text }.orEmpty()
        }
        val entries = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@mapNotNull null
                // 清单里写的是服务器上的资产名，本地落盘名是它的后缀部分：
                // `asr-encoder.int8.onnx` → `encoder.int8.onnx`，这样识别器按前缀就能找到。
                Entry(
                    name = parts[0].removePrefix("asr-"),
                    asset = parts[0],
                    size = parts[1].toLongOrNull() ?: return@mapNotNull null,
                    sha256 = parts[2],
                )
            }
            .toList()
        if (entries.size < 3) throw IllegalStateException("模型清单不完整（${entries.size} 项）")
        return entries
    }

    private fun downloadFile(url: String, target: File, onBytes: (Long) -> Unit) {
        val req = Request.Builder().url(url).build()
        LlmHttp.download.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载失败（HTTP ${resp.code}）：$url")
            }
            val body = resp.body ?: throw IllegalStateException("下载响应为空")
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
    }
}
