package com.beichen.polaris.core.llm

import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice
import com.beichen.polaris.core.net.EndpointPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 余额查询（W-29，缺口 #42）：按供应商查账户余额。
 *
 * ## 现实：没有统一接口
 * 余额是各家的私接口，字段名、路径都不一样：
 *  - DeepSeek：`GET /user/balance` → `balance_infos[].total_balance` + `currency`
 *  - Moonshot/Kimi：`GET /users/me/balance` → `data.available_balance`
 *  - 硅基流动：`GET /user/info` → `data.balance`
 *  - OpenRouter：`GET /credits` → `data.total_credits - data.total_usage`
 *  - OpenAI / Anthropic / Gemini：**没有余额接口**（只能去官网看账单）
 *
 * 所以本类只覆盖"确实有接口"的几家；其余如实返回「该服务商未提供余额接口」，
 * 不编造数字。
 *
 * 解析逻辑放在 [BalanceParsing]（纯函数，harness 可测），本类只做网络与路径拼装。
 * 失败文案统一走 [BalanceFailure]——不要直接 `it.message`（真机上 message 为 null，
 * 界面上就成了「查询失败：null」，详见该文件注释）。
 */
object BalanceService {

    /**
     * 查询余额。
     * @return Result.success(人类可读余额文案)；失败带原因（网络/不支持/解析失败）。
     *
     * ## 为什么整个函数体都在 Dispatchers.IO 里（2026-11-22 真机缺陷）
     *
     * 本方法原本是 `suspend` 但内部直接用阻塞的 `execute()`，**没有切线程**，
     * 而调用方是 `rememberCoroutineScope().launch`——Main dispatcher。于是 Android
     * 抛 `NetworkOnMainThreadException`（无 message），被 `runCatching` 吞成 failure，
     * 界面显示「查询失败：null」。
     *
     * 全项目只有这一处漏了：ClaudeClient / GeminiClient / LocalLlamaClient / MCP /
     * 市场 / 媒体生成都写了 `withContext(Dispatchers.IO)`。判据见 W-29 的 F 组。
     */
    suspend fun query(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        client: OkHttpClient = LlmHttp.short
    ): Result<String> = withContext(Dispatchers.IO) {
        val spec = BalanceParsing.endpointOf(providerId) ?: return@withContext Result.failure(
            UnsupportedOperationException("该服务商未提供余额接口，请到官网控制台查看")
        )
        val urls = BalanceParsing.candidateUrls(baseUrl, spec.path)
        if (urls.isEmpty()) return@withContext Result.failure(
            IllegalArgumentException("没有填写接口地址，无法查询余额")
        )
        // 审计 F6：余额接口同样要带 Bearer，判据与对话请求共用 EndpointPolicy。
        // 返回 failure 而不是 throw：本方法对外就是 Result 语义，UI 直接展示原因。
        urls.forEach { url ->
            EndpointPolicy.refusalFor(url, apiKey.isNotBlank())?.let { reason ->
                return@withContext Result.failure(java.io.IOException(reason))
            }
        }
        val tried = mutableListOf<String>()
        var last: Throwable? = null
        for (url in urls) {
            tried += url
            // 显式 try/catch 而不是 runCatching：runCatching 连 CancellationException
            // 一起捕——用户切走页面时协程被取消，也会被当成「查询失败」写进界面。
            val outcome: Result<String> = try {
                Result.success(fetch(url, providerId, apiKey, client))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Result.failure(t)
            }
            outcome.getOrNull()?.let { return@withContext Result.success(it) }
            val error = outcome.exceptionOrNull() ?: continue
            last = error
            // 只有「路径不存在」值得换下一个候选。鉴权失败、超时、解析失败，
            // 换个地址也是同样的结果，多打一次请求只会让报错更晚出现。
            if (!(error is BalanceHttpError && error.code == 404)) {
                return@withContext Result.failure(error)
            }
        }
        return@withContext Result.failure(
            if (tried.size > 1) {
                IllegalStateException(
                    "余额接口 404：试过 ${tried.joinToString(" 与 ")}。" +
                        "该服务商的余额接口可能不在这条路径下，请检查接口地址" +
                        (last?.message?.let { "（最后一次：$it）" } ?: "")
                )
            } else {
                last ?: IllegalStateException("余额查询失败")
            }
        )
    }

    /**
     * 界面直接用的一句：成功是余额文案，失败是 [BalanceFailure] 的人话。
     *
     * 有它，调用点就是一行赋值——不需要在 Compose 里写 `fold` 再决定失败怎么措辞。
     * 措辞一旦散在界面层，就会各自演化回 `it.message`（这正是「查询失败：null」的来路）。
     */
    suspend fun queryText(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        client: OkHttpClient = LlmHttp.short
    ): String = query(providerId, baseUrl, apiKey, client)
        .fold({ it }, { BalanceFailure.describe(it) })

    /** 404 是唯一值得换候选地址重试的错误，所以单独给一个带 code 的类型。 */
    private class BalanceHttpError(val url: String, val code: Int, val body: String) :
        IllegalStateException("HTTP $code @ $url：${body.take(160)}")

    private fun fetch(
        url: String,
        providerId: String,
        apiKey: String,
        client: OkHttpClient
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            // 审计 F35：同 F34。余额接口是第三方地址（用户可填），响应体不受我们控制。
            // 1MB 对 JSON 余额来说已经极宽松；撞到就明说，不把半截 JSON 交给 parser。
            val slice = resp.body?.byteStream()
                ?.let { BoundedBody.read(it, 1L * 1024 * 1024) }
                ?: BodySlice.EMPTY
            val body = slice.text
            if (!resp.isSuccessful) throw BalanceHttpError(url, resp.code, body)
            if (slice.truncated) throw IllegalStateException("余额响应超过 1 MB，已中止解析")
            return BalanceParsing.parse(providerId, body)
                ?: throw IllegalStateException("响应里没有可识别的余额字段（$url）")
        }
    }
}
