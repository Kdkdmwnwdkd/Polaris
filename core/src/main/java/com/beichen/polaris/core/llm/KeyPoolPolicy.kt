package com.beichen.polaris.core.llm

/**
 * 多 Key 轮询与限流切换的**决策纯函数**（W-21，缺口清单 #41）。
 *
 * 为什么单独成文件：无 Android、无网络、无时钟依赖（时间全部由调用方注入），
 * QA harness 可直接编译运行；生产侧（OpenAIClient）与测试侧共享同一份真源。
 *
 * 处理的是真实痛点：单 Key 一旦被限流（429 / 额度耗尽），整轮任务立刻断流，
 * 用户只看到一句报错。本策略让请求在同一档案的多个 Key 之间自动切换，
 * 并对被限流的 Key 施加冷却，冷却期内不再浪费请求。
 *
 * 判定口径：
 *  - [isRateLimited]：429/402/503，或响应体命中额度/限流关键词 → 换 Key 有意义；
 *  - [isAuthFailure]：401/403 → **不换 Key**（换一把也是同样的错，快速失败更诚实）；
 *  - 冷却时长：优先服务端 `Retry-After`，否则指数退避 30→60→120→300 秒封顶。
 */
object KeyPoolPolicy {

    /** 一个 Key 槽位。 */
    data class KeySlot(
        val id: String,
        val label: String,
        val key: String
    )

    /** 槽位运行态（内存态，冷却不持久化：重启即重置，避免误判长期封禁）。 */
    data class KeySlotState(
        val failCount: Int = 0,
        val cooldownUntilMs: Long = 0L,
        val lastCode: Int = 0,
        val lastError: String = ""
    ) {
        fun cooling(nowMs: Long): Boolean = cooldownUntilMs > nowMs
        fun remainingMs(nowMs: Long): Long = (cooldownUntilMs - nowMs).coerceAtLeast(0)
    }

    /** 冷却档位（秒）：第 1/2/3/4+ 次连续失败分别对应。 */
    private val BACKOFF_SECONDS = longArrayOf(30L, 60L, 120L, 300L)
    private const val MAX_COOLDOWN_MS = 300_000L
    /** 服务端 Retry-After 的上限：超过 10 分钟的请求不如直接告诉用户。 */
    private const val RETRY_AFTER_CAP_SECONDS = 600L

    private val QUOTA_WORDS = listOf(
        "rate limit", "rate_limit", "ratelimit", "quota", "insufficient_quota",
        "exceeded your current quota", "too many requests", "overloaded",
        "requests per", "tpm", "rpd", "resource exhausted"
    )

    /** 是否属于「换 Key 可能有用」的限流/额度类失败。 */
    fun isRateLimited(code: Int, body: String): Boolean {
        if (code == 402 || code == 503) return true
        if (code != 429) return false
        return true // 429 本身就是限流，无需再看 body
    }

    /** 响应体命中额度/限流关键词（用于 200 以外的其他码，如 400 包裹的额度错误）。 */
    fun bodyMentionsQuota(body: String): Boolean {
        val lower = body.lowercase()
        return QUOTA_WORDS.any { lower.contains(it) }
    }

    /**
     * 综合判定：这个失败值不值得换 Key。
     * 401/403 是鉴权问题——换一把 Key 大概率也是同样结果，直接失败，不浪费用户时间。
     */
    fun worthFailover(code: Int, body: String): Boolean {
        if (isAuthFailure(code)) return false
        return isRateLimited(code, body) || bodyMentionsQuota(body)
    }

    /** 鉴权失败：401/403。 */
    fun isAuthFailure(code: Int): Boolean = code == 401 || code == 403

    /**
     * 计算冷却时长（毫秒）。
     *
     * @param code HTTP 状态码
     * @param failCount 该 Key 累计失败次数（本次之前）
     * @param retryAfterSeconds 服务端 `Retry-After` 头（秒），无则 null
     */
    fun cooldownMs(code: Int, failCount: Int, retryAfterSeconds: Long? = null): Long {
        // 服务端明确给了等待时间 —— 听它的，但封顶避免无限等待
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return (retryAfterSeconds * 1000L).coerceAtMost(RETRY_AFTER_CAP_SECONDS * 1000L)
        }
        val idx = (failCount).coerceIn(0, BACKOFF_SECONDS.lastIndex)
        return (BACKOFF_SECONDS[idx] * 1000L).coerceAtMost(MAX_COOLDOWN_MS)
    }

    /** 记录一次失败后的新状态。 */
    fun afterFailure(
        state: KeySlotState,
        code: Int,
        error: String,
        nowMs: Long,
        retryAfterSeconds: Long? = null
    ): KeySlotState {
        val failCount = state.failCount + 1
        return KeySlotState(
            failCount = failCount,
            cooldownUntilMs = nowMs + cooldownMs(code, state.failCount, retryAfterSeconds),
            lastCode = code,
            lastError = error
        )
    }

    /** 成功后清零（连续成功不再受历史失败影响）。 */
    fun afterSuccess(): KeySlotState = KeySlotState()

    /**
     * 选一把当前可用的 Key：从 [cursor] 起轮转，跳过冷却中的槽位。
     *
     * 纯函数：[cursor] 由调用方维护并回传，保证同一轮内不会每次都撞同一把 Key。
     *
     * @return 命中的槽位下标；全部冷却中返回 null（调用方据此报「全部限流」）。
     */
    fun pickUsable(
        slots: List<KeySlot>,
        states: Map<String, KeySlotState>,
        nowMs: Long,
        cursor: Int
    ): Int? {
        if (slots.isEmpty()) return null
        val start = ((cursor % slots.size) + slots.size) % slots.size
        for (i in 0 until slots.size) {
            val idx = (start + i) % slots.size
            val st = states[slots[idx].id]
            if (st == null || !st.cooling(nowMs)) return idx
        }
        return null
    }

    /** 全部槽位中「最早恢复」的剩余毫秒数（用于给用户明确等待预期）。 */
    fun earliestRecoveryMs(
        slots: List<KeySlot>,
        states: Map<String, KeySlotState>,
        nowMs: Long
    ): Long {
        val rests = slots.mapNotNull { states[it.id]?.remainingMs(nowMs) }.filter { it > 0 }
        return rests.minOrNull() ?: 0L
    }

    /** 当前可用数量（用于状态摘要）。 */
    fun usableCount(
        slots: List<KeySlot>,
        states: Map<String, KeySlotState>,
        nowMs: Long
    ): Int = slots.count { states[it.id]?.cooling(nowMs) != true }

    /**
     * 给用户/日志的一行状态摘要：有几把可用、几把在冷却、最早多久恢复。
     * Key 本体绝不出现在文案里（只给 label）。
     */
    fun summary(
        slots: List<KeySlot>,
        states: Map<String, KeySlotState>,
        nowMs: Long
    ): String {
        if (slots.isEmpty()) return "未配置备用 Key（当前只有主 Key）"
        val usable = usableCount(slots, states, nowMs)
        val cooling = slots.size - usable
        return if (cooling == 0) {
            "备用 Key $usable/$usable 把全部可用"
        } else {
            val wait = earliestRecoveryMs(slots, states, nowMs) / 1000
            "备用 Key $usable/${slots.size} 把可用，$cooling 把冷却中" +
                if (wait > 0) "（最早 ${wait} 秒后恢复）" else ""
        }
    }
}
