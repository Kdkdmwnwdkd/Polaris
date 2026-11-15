package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.GitHubMirror
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
 * 装包已经 361MB（两套 TTS 模型在里面）。再塞 24MB 识别模型，对「只想让桌宠说话」
 * 的人就是白付的流量与存储。而这套模型**只有第一次点语音时才会用到**——
 * 按需下载能把它从「所有人的成本」变成「用的人的 24MB」。
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

    /**
     * 模型放在我们自己的 latest Release 里，与 APK 同源。
     *
     * 注意「自家仓库」不等于「能下」：真机上 `github.com:443` 直接超时（同网络下
     * `api.github.com` 通）。所以这里的地址只是**官方地址**，实际请求由
     * [GitHubMirror] 展开成「镜像 → 镜像 → 直连」的候选表。
     */
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
                // downloadFile 自己会在多个源之间换，并把校验通过的文件落到 target。
                // 进度从「本文件起点」重新计：换源会从头再来，不能让进度条往回跳又往前冲。
                val base = done
                downloadFile(entry, target) { read ->
                    onProgress(base + read, total, entry.name)
                }
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

    /**
     * 取清单。**逐源尝试**，并把每个源失败的原因都留下来。
     *
     * 为什么错误要汇总而不是「最后一次的失败」：真机上的失败长这样——
     * `failed to connect to github.com…after 10000ms`。只有这一句的话，
     * 用户无法判断是「模型没上传」还是「网络到不了」；汇总里能看到
     * 「镜像返回的是错误页 / 直连超时」，一眼分得清。
     */
    private fun fetchManifest(): List<Entry> {
        val failures = ArrayList<String>()
        for (src in GitHubMirror.sources("$baseUrl/$MANIFEST")) {
            val body = try {
                fetchText(src.url)
            } catch (t: Throwable) {
                failures += "${src.label}：${t.message}"
                AgentLogger.w(tag, "清单下载失败（${src.label}）：${t.message}")
                continue
            }
            val entries = parseManifest(body)
            if (entries.size >= MIN_ENTRIES) {
                AgentLogger.i(tag, "模型清单来自 ${src.label}（${entries.size} 项）")
                return entries
            }
            // 加速通道在限流/维护时会返回 200 + 一张 HTML 说明页，解析出来是 0 项。
            failures += "${src.label}：返回的不是清单（解析出 ${entries.size} 项）"
        }
        throw IllegalStateException("模型清单取不到——${failures.joinToString("；")}")
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return LlmHttp.short.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val why = if (resp.code == 404) "服务端还没上传模型" else "网络问题"
                throw IllegalStateException("HTTP ${resp.code}：$why")
            }
            // 清单走有上限读取（审计 F21）：它是外部输入，长度由服务端决定。
            // 64KB 已经比真实清单大两个数量级（4 行，约 300 字节）。
            resp.body?.let { BoundedBody.read(it.byteStream(), 64L * 1024).text }.orEmpty()
        }
    }

    private fun parseManifest(body: String): List<Entry> {
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
        return entries
    }

    /**
     * 下**一个文件**：多源轮换，校验通过才落到 [target]。
     *
     * 三道关缺一不可：
     * 1. 传输是否完整（少字节直接换源，不要拿半截文件去试）
     * 2. sha256 是否与清单一致（第三方镜像可能给错页、给旧版，甚至被替换）
     * 3. 落盘是否成功（`.part` → 正式名，避免下次启动把半截文件当成品）
     *
     * 只有第 3 步失败才往外抛——那两个源都试完了还没成功，重试也没意义，
     * 该让用户看到原因。
     */
    private fun downloadFile(entry: Entry, target: File, onBytes: (Long) -> Unit) {
        val official = "$baseUrl/${entry.asset}"
        val failures = ArrayList<String>()
        val part = File(target.parentFile, target.name + ".part")

        for (src in GitHubMirror.sources(official)) {
            part.delete()
            var written = 0L
            val got = runCatching {
                fetchTo(src.url, part) { n ->
                    written += n
                    onBytes(n)
                }
            }
            if (got.isFailure) {
                failures += "${src.label}：${got.exceptionOrNull()?.message}"
                AgentLogger.w(tag, "${entry.name} 下载失败（${src.label}）：${got.exceptionOrNull()?.message}")
                continue
            }
            if (part.length() != entry.size || !entry.sha256(part)) {
                failures += "${src.label}：校验不符（${part.length()} / ${entry.size} 字节）"
                AgentLogger.w(
                    tag,
                    "${entry.name} 校验不符（${src.label}）：${part.length()} / ${entry.size} 字节",
                )
                continue
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("${entry.name} 落盘失败")
            AgentLogger.i(tag, "${entry.name} 就绪（来自 ${src.label}，${written} 字节）")
            return
        }
        throw IllegalStateException("${entry.name} 下载失败——${failures.joinToString("；")}")
    }

    private fun fetchTo(url: String, part: File, onBytes: (Long) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        LlmHttp.download.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应为空")
            part.outputStream().use { out ->
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

    private companion object {
        /** 清单文件名，CI 与这里必须一致。 */
        const val MANIFEST = "asr-manifest.txt"

        /** 4 个文件（encoder/decoder/joiner/tokens）；少于这个数说明返回的不是清单。 */
        const val MIN_ENTRIES = 4

        /**
         * 部分镜像对不带 UA 的请求直接回 403，与「文件不存在」的 404 混在一起分不清。
         */
        const val USER_AGENT = "Polaris"
    }
}
