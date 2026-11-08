package com.beichen.polaris.core.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Key 池运行时（W-21）：持有「冷却状态表 + 轮询游标」。
 *
 * 设计取舍：
 *  - 状态只在内存（进程级）：重启重置，避免一次限流把 Key 判死几天；
 *  - 线程安全用 ConcurrentHashMap：LLM 请求在 IO 线程池，可能并发；
 *  - 决策全部委托 [KeyPoolPolicy] 纯函数，本类只做状态存取与游标推进——
 *    同一份判定逻辑在 harness 里可独立验证。
 */
class KeyPoolRuntime {

    private val states = ConcurrentHashMap<String, KeyPoolPolicy.KeySlotState>()
    private var cursor: Int = 0

    @Volatile
    private var lastSummary: String = ""

    fun stateOf(slotId: String): KeyPoolPolicy.KeySlotState =
        states[slotId] ?: KeyPoolPolicy.KeySlotState()

    fun statesSnapshot(): Map<String, KeyPoolPolicy.KeySlotState> = states.toMap()

    /** 记录失败并把该 Key 置入冷却。 */
    fun markFailure(
        slotId: String,
        code: Int,
        error: String,
        nowMs: Long = System.currentTimeMillis(),
        retryAfterSeconds: Long? = null
    ) {
        val next = KeyPoolPolicy.afterFailure(
            state = stateOf(slotId),
            code = code,
            error = error,
            nowMs = nowMs,
            retryAfterSeconds = retryAfterSeconds
        )
        states[slotId] = next
    }

    /** 成功后清零。 */
    fun markSuccess(slotId: String) {
        states[slotId] = KeyPoolPolicy.afterSuccess()
    }

    /** 取下一把可用 Key 的下标；推进游标保证同一轮不会总撞同一把。 */
    fun nextUsableIndex(
        slots: List<KeyPoolPolicy.KeySlot>,
        nowMs: Long = System.currentTimeMillis()
    ): Int? {
        val idx = KeyPoolPolicy.pickUsable(slots, statesSnapshot(), nowMs, cursor)
        if (idx != null) cursor = (idx + 1) % slots.size.coerceAtLeast(1)
        return idx
    }

    /** 当前状态摘要（给用户/日志，不含 Key 本体）。 */
    fun summary(
        slots: List<KeyPoolPolicy.KeySlot>,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val s = KeyPoolPolicy.summary(slots, statesSnapshot(), nowMs)
        lastSummary = s
        return s
    }

    fun lastSummary(): String = lastSummary

    /** 供设置页「立即重置冷却」用：清掉所有失败态。 */
    fun resetAll() {
        states.clear()
        cursor = 0
        lastSummary = ""
    }
}
