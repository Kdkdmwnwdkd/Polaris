package com.beichen.polaris.core.llm

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
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}：${body.take(160)}")
                }
                BalanceParsing.parse(providerId, body)
                    ?: throw IllegalStateException("响应里没有可识别的余额字段")
            }
        }
    }
}
