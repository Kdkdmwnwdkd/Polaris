package com.beichen.polaris.core.llm

/**
 * 余额接口与响应解析（纯函数，无网络无 Android）——harness 直接验证。
 */
object BalanceParsing {

    /** 一个供应商的余额接口描述。 */
    data class Endpoint(val path: String)

    /**
     * 供应商 → 余额接口。路径相对 baseUrl；OpenRouter 的 baseUrl 通常是 `/api/v1`，
     * 它的 credits 在 `/credits`（同前缀），直接拼即可。
     */
    fun endpointOf(providerId: String): Endpoint? = when (providerId.lowercase()) {
        "deepseek" -> Endpoint("/user/balance")
        "moonshot", "kimi" -> Endpoint("/users/me/balance")
        "siliconflow", "silicon" -> Endpoint("/user/info")
        "openrouter" -> Endpoint("/credits")
        else -> null
    }

    /**
     * 解析响应 JSON → 人类可读文案；无法识别返回 null。
     * 不抛异常（爬各家格式时总会遇到变体，失败就是"读不到"）。
     */
    fun parse(providerId: String, json: String): String? = when (providerId.lowercase()) {
        "deepseek" -> parseDeepSeek(json)
        "moonshot", "kimi" -> parseMoonshot(json)
        "siliconflow", "silicon" -> parseSiliconFlow(json)
        "openrouter" -> parseOpenRouter(json)
        else -> null
    }

    private fun parseDeepSeek(json: String): String? {
        // {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",...}]}
        val infos = Regex(""""balance_infos"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return null
        val currency = Regex(""""currency"\s*:\s*"([^"]+)"""").find(infos)?.groupValues?.get(1) ?: ""
        val total = Regex(""""total_balance"\s*:\s*"([^"]+)"""").find(infos)?.groupValues?.get(1)
            ?: return null
        return "余额 $total ${if (currency == "CNY") "元" else currency}".trim()
    }

    private fun parseMoonshot(json: String): String? {
        // {"code":0,"data":{"available_balance":123.45,"voucher_balance":...}}
        val v = Regex(""""available_balance"\s*:\s*"?([0-9.]+)"?""").find(json)?.groupValues?.get(1)
            ?: return null
        return "可用余额 $v"
    }

    private fun parseSiliconFlow(json: String): String? {
        // {"code":20000,"data":{"balance":"12.34","chargeBalance":"..."}}
        val v = Regex(""""balance"\s*:\s*"?([0-9.]+)"?""").find(json)?.groupValues?.get(1)
            ?: return null
        return "余额 $v"
    }

    private fun parseOpenRouter(json: String): String? {
        // {"data":{"total_credits":10.0,"total_usage":3.5}}
        val credits = Regex(""""total_credits"\s*:\s*"?([0-9.]+)"?""").find(json)?.groupValues?.get(1)
            ?: return null
        val usage = Regex(""""total_usage"\s*:\s*"?([0-9.]+)"?""").find(json)?.groupValues?.get(1)
            ?: "0"
        val c = credits.toDoubleOrNull()
        val u = usage.toDoubleOrNull()
        return if (c != null && u != null) {
            "余额 ${"%.2f".format(c - u)}（额度 $credits / 已用 $usage）"
        } else {
            "额度 $credits（已用 $usage）"
        }
    }
}
