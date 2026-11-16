package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.LlmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 技能包 zip 的传输层（从 `ChatViewModel` 搬出来）。
 *
 * ## 为什么不在原来的位置改
 *
 * 原来的实现在 `ChatViewModel.downloadSkillZip`，用共享的 `LlmHttp.short` 直接下载。
 * `short` 默认**自动跟随重定向**，于是 `LoadSkillLogic.validateUrl` 只校验了第一跳：
 * 一个 `302 Location: http://内网/evil.zip` 就能把「https + 公网」这两条前提一起绕过，
 * 而下载下来的脚本会以宿主身份执行。
 *
 * 修法只有一条路：关掉自动重定向，**手动逐跳校验**。这需要循环与状态，塞回
 * `ChatViewModel`（已顶到行数上限，余量 20 行）会让它更胖；放这里还能被单独读。
 *
 * ## 边界
 *
 * - 只做传输：URL 合法性仍由 [LoadSkillLogic.validateUrl] 判，安装侧仍有 Zip Slip / 炸弹检查。
 * - 体积上限按**实际读到的字节**算，不信 `Content-Length`。
 * - 最多 3 跳；每跳都用同一套 https + 非内网校验，不给「首跳干净、后面脏」留口子。
 */
object SkillZipFetcher {

    /** 重定向上限。GitHub Release → CDN 通常 1–2 跳，3 跳够用且能挡住环。 */
    private const val MAX_REDIRECTS = 3

    private val client = LlmHttp.short.newBuilder()
        // 关键：不自动跟随。跟随了就只有首跳能被校验。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetch(url: String, maxBytes: Int = LoadSkillLogic.MAX_ZIP_BYTES): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching { follow(url, maxBytes) }
        }

    /**
     * 逐跳抓取，最多 [MAX_REDIRECTS] 次跳转。
     *
     * 写成独立函数而不是塞进 `runCatching` 的 lambda：`return@` 从 inline lambda 里
     * 非局部返回、再在末尾补一句不可达的 throw，是「能编译但读不出对错」的写法。
     * here-return 让控制流一次读完。
     */
    private fun follow(url: String, maxBytes: Int): ByteArray {
        var current = url
        var hops = 0
        while (true) {
            var location: String? = null
            val bytes: ByteArray? = client.newCall(
                Request.Builder()
                    .url(current)
                    .header("User-Agent", "Polaris")
                    .get()
                    .build()
            ).execute().use { resp ->
                if (resp.isRedirect) {
                    // Location 可能是相对路径，按当前 URL 解析后再交给校验
                    location = resp.header("Location")
                        ?.let { resp.request.url.resolve(it)?.toString() ?: it }
                    null
                } else {
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    readCapped(resp.body?.byteStream(), maxBytes)
                }
            }

            if (bytes != null) return bytes

            val next = location ?: throw IOException("重定向缺少 Location")
            LoadSkillLogic.validateUrl(next)?.let { why ->
                // 不是"当做失败就算了"：这一条要明确告诉模型/用户，
                // 否则它会以为是网络抖动，反复重试同一个地址。
                throw IOException("重定向被拒绝（$why）：$next")
            }
            hops += 1
            if (hops > MAX_REDIRECTS) throw IOException("重定向超过 $MAX_REDIRECTS 跳，已中止")
            current = next
        }
    }

    private fun readCapped(input: java.io.InputStream?, maxBytes: Int): ByteArray {
        val stream = input ?: throw IOException("响应体为空")
        val out = ByteArrayOutputStream()
        stream.use { src ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("技能包超过 ${maxBytes / 1024 / 1024} MB 上限，已中止")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
