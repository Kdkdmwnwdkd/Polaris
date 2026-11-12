package com.beichen.polaris.core.llm

import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice
import com.beichen.polaris.core.net.EndpointPolicy
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
 */
object BalanceService {

    /**
     * 查询余额。
     * @return Result.success(人类可读余额文案)；失败带原因（网络/不支持/解析失败）。
     */
    suspend fun query(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        client: OkHttpClient = LlmHttp.short
    ): Result<String> {
        val spec = BalanceParsing.endpointOf(providerId) ?: return Result.failure(
            UnsupportedOperationException("该服务商未提供余额接口，请到官网控制台查看")
        )
        val base = baseUrl.trimEnd('/')
        val url = when {
            spec.path.startsWith("http") -> spec.path
            else -> "$base${spec.path}"
        }
        // 审计 F6：余额接口同样要带 Bearer，判据与对话请求共用 EndpointPolicy。
        // 返回 failure 而不是 throw：本方法对外就是 Result 语义，UI 直接展示原因。
        EndpointPolicy.refusalFor(url, apiKey.isNotBlank())?.let { reason ->
            return Result.failure(java.io.IOException(reason))
        }
        return runCatching {
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
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}：${body.take(160)}")
                }
                if (slice.truncated) {
                    throw IllegalStateException("余额响应超过 1 MB，已中止解析")
                }
                BalanceParsing.parse(providerId, body)
                    ?: throw IllegalStateException("响应里没有可识别的余额字段")
            }
        }
    }
}
