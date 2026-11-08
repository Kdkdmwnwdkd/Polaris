package com.beichen.polaris.core.llm

/**
 * 供应商重试档位（R10）。
 *
 * ## 为什么不能全局一套
 *
 * R10 之前 `providerRetry` 是 [com.beichen.polaris.core.agent.RealAgentLoop] 的构造参数
 * （默认 3），`isRetryable` 是全局判据（429 / ≥500）。也就是说，官方直连和聚合中转商
 * 共用同一套次数与判据。真实差异是：
 *
 *  - 官方直连的 429 通常带 `Retry-After: 3`，重试两次就过去了；
 *  - 中转商往往在高峰期连续 429，两次不够，且它给的 `Retry-After` 动辄 60 秒。
 *
 * 更麻烦的是**判据**：大量中转商把限流返回成 `400 + {"error":"quota exceeded"}`，
 * 全局判据把它当参数错误直接快败——用户看到的是「请求失败」，实际只是限流。
 * 这层由 [ErrorNormalizer] 归一化后交给本档位判定，两件事分开：
 * 本类只回答「这家愿意重试几次、认哪些码」，不回答「这个错误到底是什么」。
 *
 * ## 为什么 backoff 序列没有进来
 *
 * 参照实现（dsh）把 `backoff` 也挂在 route 上。这里**故意不跟**：现有退避已经比它细——
 * 429 走专用序列并尊重 `Retry-After`，其余走通用序列，且两者都带「网络恢复即刻续跑」。
 * 把序列也 profile 化，只会让 harness 的注入点多一层，换不来任何行为改善。
 * 要调某一家的退避节奏，改 [com.beichen.polaris.core.agent.RetryBackoffPolicy] 即可，
 * 那里的参数本来就是常量。
 *
 * @param name     档位名（日志用，别写中文——日志按 ASCII 过滤更省事）
 * @param maxRetries 重试次数（不含首次），总请求数 = maxRetries + 1
 * @param retryableCodes 认作「瞬时失败」的 HTTP 状态码
 * @param allowQuotaRetry 额度类失败是否重试。
 *
 *   这是与官方直连最关键的一处差异：官方 402 重试一百次也是 402，
 *   而聚合商的 402/403 常常是「这个上游没额度了」，换一个上游就好了——
 *   下一次尝试由 Key 池轮换到别的 Key，所以重试**确实**有意义。
 */
data class RetryProfile(
    val name: String,
    val maxRetries: Int,
    val retryableCodes: Set<Int>,
    val allowQuotaRetry: Boolean = false,
) {

    fun isRetryableCode(code: Int): Boolean = code in retryableCodes

    companion object {

        /**
         * 官方直连：3 次重试。
         *
         * 3 次是 hotfix10 在真机上试出来的：连续 3 次 429 之后基本是「今天的额度用完了」，
         * 再重试只是让用户多等半分钟才看到同一句错误。
         */
        val OFFICIAL = RetryProfile(
            name = "official",
            maxRetries = 3,
            retryableCodes = setOf(408, 409, 425, 429, 500, 502, 503, 504),
        )

        /**
         * 聚合中转：4 次重试，且把 402 / 403 也当瞬时失败。
         *
         * 中转商的 402（余额）和 403（风控）经常是**临时**的——换到下一个上游就好了，
         * 这是它们和官方直连最大的行为差异。多给一次机会，代价是一次退避的时间。
         */
        val AGGREGATOR = RetryProfile(
            name = "aggregator",
            maxRetries = 4,
            retryableCodes = setOf(402, 403, 408, 409, 425, 429, 500, 502, 503, 504),
            allowQuotaRetry = true,
        )

        /** 本地推理：不重试。同一段 prompt + 同一个 native 库，重试只是再失败一次。 */
        val LOCAL = RetryProfile(
            name = "local",
            maxRetries = 0,
            retryableCodes = emptySet(),
        )

        val DEFAULT: RetryProfile = OFFICIAL

        /** 按协议风格取默认档位（新增供应商仍然只需要加一条数据）。 */
        fun byStyle(style: String): RetryProfile =
            if (style == ProviderCatalog.STYLE_LOCAL_LLAMA) LOCAL else OFFICIAL
    }
}

/**
 * 错误归一化（R10）：把「HTTP 状态 + 响应体」收敛成一个稳定类别。
 *
 * ## 为什么不能只认状态码
 *
 * 参照实现的原则是「按稳定码路由，绝不解析 message」。那条原则在**只有官方 adapter**
 * 的世界里成立，在这里不成立：35 家里大量中转商把限流写成
 * `400 + {"error":{"message":"quota exceeded"}}`，把余额不足写成
 * `403 + {"error":"insufficient balance"}`。只认状态码会把它们全判成「参数错误」，
 * 用户看到的是一句无从下手的失败。
 *
 * ## 三层，逐层收紧
 *
 *  1. **状态码**：429 / 402 / 503 这类无歧义的，直接定性；
 *  2. **结构化字段**：OpenAI 兼容端点多数带 `error.code` / `error.type`
 *     （`rate_limit_exceeded` / `insufficient_quota` / `invalid_api_key`）；
 *  3. **正文关键词**：兜底。命中时**必须**由调用方记一条 `provider.misclassified`——
 *     否则这一层会永远是一团模糊的 `contains("rate")`，谁也不知道它救过谁、误判过谁。
 *
 * 第三层的关键不是「能不能判对」，而是「判错了能不能被发现」。所以 [classify] 返回
 * [Verdict.fromKeyword] 标志，调用方据此决定要不要记日志。
 */
object ErrorNormalizer {

    /** 失败类别。**只用来决定重试与文案**，不参与业务分支。 */
    enum class FailureClass {
        /** 鉴权失败：Key 错、过期、没权限。重试无意义。 */
        AUTH,

        /** 限流：瞬时，退避后可恢复。 */
        RATE_LIMIT,

        /** 额度/余额不足：今天之内重试无意义，但换 Key 或换上游可解。 */
        QUOTA,

        /** 服务端故障：5xx，重试有意义。 */
        SERVER,

        /** 请求本身有问题：参数错、模型名错。重试无意义。 */
        BAD_REQUEST,

        /** 网络层（超时 / 不可达 / 连接中断）。 */
        NETWORK,

        /** 判不出来。调用方按「保守」处理：不重试但记日志。 */
        UNKNOWN,
    }

    /**
     * 归一化结论。
     *
     * @param cls         类别
     * @param fromKeyword 是否由**第三层关键词**判出（调用方据此记 misclassified 日志）
     * @param evidence    命中依据（状态码 / 字段 / 关键词原文），日志用，截断到 40 字符
     */
    data class Verdict(
        val cls: FailureClass,
        val fromKeyword: Boolean,
        val evidence: String,
    )

    /** 关键词表：只收**明确指向**「不是参数错误」的说法，宁可漏判也不误判。 */
    private val RATE_KEYWORDS = listOf(
        "rate limit", "rate_limit", "too many requests", "requests per minute",
        "rpm limit", "tpm limit", "请求过于频繁", "限流", "频率限制",
    )

    private val QUOTA_KEYWORDS = listOf(
        "quota", "insufficient balance", "insufficient_quota", "余额不足",
        "额度不足", "欠费", "credit", "billing",
    )

    private val AUTH_KEYWORDS = listOf(
        "invalid api key", "invalid_api_key", "api key not valid", "unauthorized",
        "authentication", "鉴权失败", "key 无效",
    )

    /**
     * 归一化一次失败。
     *
     * 判定顺序**不可调换**：状态码优先于字段，字段优先于关键词。
     * 反过来会出现「429 的 body 里恰好有 billing 字样」被判成余额不足。
     */
    fun classify(code: Int, body: String): Verdict {
        // 第一层：状态码无歧义的直接定性。402 归 QUOTA 而不是 RATE_LIMIT——
        // 两者在重试策略上不同：QUOTA 允许「换 Key 再试」，RATE_LIMIT 只等不退。
        when (code) {
            401, 403 -> return Verdict(FailureClass.AUTH, false, "http $code")
            402 -> return Verdict(FailureClass.QUOTA, false, "http $code")
            429 -> return Verdict(FailureClass.RATE_LIMIT, false, "http $code")
            408, 504 -> return Verdict(FailureClass.NETWORK, false, "http $code")
        }
        if (code >= 500) return Verdict(FailureClass.SERVER, false, "http $code")

        // 第二层：结构化字段。只在这些状态码上做（4xx 的其余部分），
        // 因为 429/5xx 已经被上面接住了，没必要再解析 body。
        if (code in 400..499) {
            val lower = body.lowercase()
            // 只认带引号的键，避免把用户 prompt 里的 "code" 当字段
            if (lower.contains("\"code\"") || lower.contains("\"type\"")) {
                when {
                    lower.contains("rate_limit") || lower.contains("too_many_requests") ->
                        return Verdict(FailureClass.RATE_LIMIT, false, "field: rate_limit")
                    lower.contains("insufficient_quota") || lower.contains("billing") ->
                        return Verdict(FailureClass.QUOTA, false, "field: quota")
                    lower.contains("invalid_api_key") || lower.contains("authentication_error") ->
                        return Verdict(FailureClass.AUTH, false, "field: auth")
                }
            }

            // 第三层：正文关键词兜底。命中即记 fromKeyword=true。
            RATE_KEYWORDS.firstOrNull { lower.contains(it) }?.let {
                return Verdict(FailureClass.RATE_LIMIT, true, "kw: $it")
            }
            QUOTA_KEYWORDS.firstOrNull { lower.contains(it) }?.let {
                return Verdict(FailureClass.QUOTA, true, "kw: $it")
            }
            AUTH_KEYWORDS.firstOrNull { lower.contains(it) }?.let {
                return Verdict(FailureClass.AUTH, true, "kw: $it")
            }
        }

        return Verdict(
            cls = if (code in 400..499) FailureClass.BAD_REQUEST else FailureClass.UNKNOWN,
            fromKeyword = false,
            evidence = "http $code",
        )
    }

    /**
     * 这个类别是否值得重试（类别层面的默认答案）。
     *
     * AUTH / BAD_REQUEST / UNKNOWN 默认不重试：前两个重试必然同错，第三个说明
     * 我们根本没看懂发生了什么——「看不懂就重发」是副作用事故的标准起点。
     *
     * **最终判据不在这里**：调用方还要叠加档位的逃生门（`retryableCodes` 命中即重试）
     * 与额度策略（`allowQuotaRetry`）。本函数只回答「这个类别本身值不值得」，
     * 不替档位做决定——两处判据分开，才不会出现「改了档位但行为没变」。
     */
    fun isRetryableByDefault(cls: FailureClass): Boolean = when (cls) {
        FailureClass.RATE_LIMIT, FailureClass.SERVER, FailureClass.NETWORK -> true
        FailureClass.QUOTA -> true
        FailureClass.AUTH, FailureClass.BAD_REQUEST, FailureClass.UNKNOWN -> false
    }
}
