package com.beichen.polaris.skill.market

import com.beichen.polaris.core.llm.LlmHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 插件市场的网络客户端（W-48）。
 *
 * ## 为什么单独一个客户端，不复用 LlmHttp
 *
 * LLM 通道的读超时是 180 秒（推理模型首 token 可能很慢），而市场请求是**普通静态资源**：
 * 20 秒不响应就该失败，否则刷新市场会让界面转圈几分钟。两者语义不同，不能共用一套超时。
 *
 * ## 三道限制（防「远端把手机拖死」）
 *
 * 1. **协议**：只允许 http/https（[MarketRules.validHttpUrl]），挡掉 `file:` / `content:`；
 * 2. **体积**：索引 ≤ 512KB、包 ≤ 8MB，**先看 Content-Length，再边读边计数**——
 *    只信 header 不够（分块传输没有长度），只靠事后检查也不够（那时内存已经吃满）；
 * 3. **完整性**：索引声明了 `sha256` 就必须匹配，防止「索引与包分别被替换」。
 *
 * 所有失败都收敛成 `Result.failure`，异常消息面向用户可读（UI 直接展示）。
 */
class MarketClient(
    private val http: OkHttpClient = defaultClient()
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 拉取一个源的索引并清洗。 */
    suspend fun fetchIndex(sourceUrl: String): Result<MarketIndex> = withContext(Dispatchers.IO) {
        runCatching {
            val url = MarketRules.validHttpUrl(sourceUrl)
                ?: throw IllegalArgumentException("市场地址必须是 http/https")
            val text = getText(url, MAX_INDEX_BYTES)
            val parsed = runCatching { json.decodeFromString<MarketIndex>(text) }
                .getOrElse { throw IllegalArgumentException("索引不是合法的市场 JSON：${it.message}") }
            val clean = MarketRules.sanitizeIndex(parsed, url)
            if (clean.plugins.isEmpty()) {
                // 空源也按失败上报：UI 只有「错误」与「条目」两种展示，静默返回空列表会让
                // 用户分不清「源里真没有插件」和「源挂了/格式不对」。消息里带上原始条数，
                // 一看就知道是「本来就空」还是「全被校验拒了」。
                throw IllegalStateException(
                    if (parsed.plugins.isEmpty()) "索引里没有插件"
                    else "索引里 ${parsed.plugins.size} 条插件均未通过校验"
                )
            }
            clean
        }
    }

    /**
     * 下载插件 zip（带体积与哈希校验），返回原始字节交给 [com.beichen.polaris.skill.SkillImportRepository]。
     *
     * 注意：这里**只负责取包与校验**，解压与落盘全部复用已有的 `installZip`（含 Zip Slip 防护）。
     */
    suspend fun downloadPlugin(plugin: MarketPlugin): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = MarketRules.validHttpUrl(plugin.downloadUrl)
                ?: throw IllegalArgumentException("插件下载地址非法")
            val bytes = getBytes(url, MAX_PACKAGE_BYTES)
            if (plugin.sha256.isNotBlank()) {
                val actual = sha256(bytes)
                if (!actual.equals(plugin.sha256, ignoreCase = true)) {
                    throw IllegalStateException("完整性校验失败（期望 ${plugin.sha256.take(8)}…，实际 ${actual.take(8)}…）")
                }
            }
            bytes
        }
    }

    /**
     * 按用户给的地址取一个技能包（审计 F10）。
     *
     * 存在的意义是**让本地「从 URL 导入」不再自己开一条网络路径**。它原来写的是
     * `URL(url).openStream().readBytes()`：没有体积上限（OOM 崩进程）、没有超时
     * （连接挂住只能杀 App）、也不走统一 HTTP 客户端。市场安装走这里、本地导入走那条，
     * 同一个动作两套规矩——和「F8 判定成立但告知不成立」是同一类漂移。
     *
     * 不复用 [downloadPlugin]：那条要对索引声明的 `sha256` 校验，用户手输的地址没有哈希可对。
     */
    suspend fun fetchPackage(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = MarketRules.validHttpUrl(url)
                ?: throw IllegalArgumentException("地址必须是 http/https：${url.take(60)}")
            getBytes(clean, MAX_PACKAGE_BYTES)
        }
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    private fun getText(url: String, limit: Int): String =
        String(getBytes(url, limit), Charsets.UTF_8)

    /** 受限读取：Content-Length 预判 + 流式计数，任何一步超限立即中止。 */
    private fun getBytes(url: String, limit: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, application/zip, */*")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}（${url.take(80)}）")
            }
            val declared = resp.body?.contentLength() ?: -1L
            if (declared > limit) {
                throw IOException("响应过大（声明 $declared 字节，上限 $limit）")
            }
            val source = resp.body?.byteStream() ?: throw IOException("响应为空")
            // 初始容量只是优化：declared 可能是 -1（分块传输无长度），直接 toInt() 会得到 -1
            // 并让 ByteArrayOutputStream 抛 IllegalArgumentException——这里必须夹到 [0, limit]。
            val initial = minOf(maxOf(declared, 0L), limit.toLong()).toInt()
            val out = java.io.ByteArrayOutputStream(initial)
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                if (out.size() + n > limit) {
                    throw IOException("响应超出上限（$limit 字节）")
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            // 关键：Byte 是有符号的，`"%02x".format(it)` 会把 -128 打成 8 位（ffffff80），
            // 算出的哈希永远对不上索引里的 64 位十六进制。必须先 `and 0xff` 转成无符号。
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        /** 索引上限：正常索引几十 KB，512KB 已极宽松。 */
        const val MAX_INDEX_BYTES = 512 * 1024

        /** 单包上限 8MB（与 [MarketRules.MAX_PACKAGE_BYTES] 一致）。 */
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024

        private const val USER_AGENT = "Polaris-Market/1.0"

        /**
         * 市场专用客户端：20s 读超时、60s 整通上限。
         *
         * 用 `callTimeout` 而不是只设读超时，是因为「慢速滴流」的服务器每次都能在 20s 内
         * 吐几个字节，读超时永远不触发；整通上限能兜住这种拖时间。
         */
        fun defaultClient(): OkHttpClient = LlmHttp.short
    }
}
