package com.beichen.polaris.core.agent

/**
 * 重试退避的**纯数值排期**：只算「第几次重试该等多久」，不碰协程、不碰 Android。
 *
 * ## 为什么从 `RetryBackoffPolicy` 里拆出来（2026-11-22）
 *
 * `RetryBackoffPolicy` 除了这几条算式，还带着两个 `suspend` 钩子
 * （[RetryBackoffPolicy.networkAware] / [RetryBackoffPolicy.networkAwareRateLimit]），
 * 它们的参数类型 [NetworkRecoveryWaiter] 定义在 `NetworkRecovery.kt` 里，而那个文件
 * `import android.net.ConnectivityManager`。
 *
 * 结果是一条很隐蔽的链：**想给退避算式写一条能在 JVM 上跑的断言，就得先编进 Android SDK。**
 * 于是判据一直停在 `scripts/qa/Hotfix3Verification.kt` 这一档——它能跑（CI 上有 app 产物），
 * 但进不了 `mutation-gate.py` 的编译闭包（那里只有 kotlinc + JUnit，没有 android.jar）。
 * 而「没有变异证明的断言」正是这个仓库最忌讳的那种绿。
 *
 * 拆开之后：本文件零依赖，`core/src/test/.../RetryScheduleTest.kt` 与
 * `mutation-gate.py` 的 `retry` 组都只编这两个文件，跑一遍不到两秒。
 *
 * ## 常量仍是**唯一来源**
 *
 * 改退避时长只改这里。[RetryBackoffPolicy] 里的同名成员是转发，不是第二份拷贝。
 */
object RetrySchedule {

    /** 退避基数（第 1 次重试的等待毫秒）。 */
    const val BASE_MS: Long = 3_000L

    /** 单次退避上限（序列最后一项）。 */
    const val MAX_MS: Long = 25_000L

    /**
     * 退避序列（可配常量）：第 n 次重试取第 n 项，超出长度一律取末项封顶。
     * 改这一处即可整体调节「等多久」，无需动重试循环。
     */
    val BACKOFF_SEQUENCE_MS: List<Long> = listOf(3_000L, 10_000L, 25_000L)

    /**
     * **429 限流专用**退避序列（hotfix10）：第 n 次重试取第 n 项，越界取末项封顶。
     * 限流要「等得久、试得少」——通用序列 3s/10s 对限流等于没等（真机 19:05 实证：
     * 连续 3 次 429 后整个回合直接失败）。
     */
    val RATE_LIMIT_SEQUENCE_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L)

    /** 429 单次退避封顶（含服务端 Retry-After 也不得超过它，防坏头值卡死回合）。 */
    const val MAX_RATE_LIMIT_MS: Long = 60_000L

    /**
     * 第 [attempt] 次重试（1-based）前应等待的毫秒数：取 [BACKOFF_SEQUENCE_MS] 的第
     * [attempt] 项（越界取末项封顶）；非法序号（≤0）按第 1 次处理，避免调用方传错导致
     * 永久忙等。
     */
    fun backoffMillis(attempt: Int): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        return BACKOFF_SEQUENCE_MS.getOrElse(index) { MAX_MS }.coerceAtLeast(0L)
    }

    /**
     * **429 限流专用**退避（hotfix10）：[RATE_LIMIT_SEQUENCE_MS] = 10s → 30s → 60s 封顶。
     *
     * 为什么单列：限流不是网络抖动——用 3s/10s 的通用序列硬刚只会连续再挨两次 429
     * （真机 19:05 实证：3s/10s 后第 3 次 429 直接整个回合失败）。限流的正确姿势是
     * 「等得久、试得少」，故序列显著拉长；同时尊重服务端 `Retry-After`：
     * 若服务端要求等更久，取两者较大值（再封顶 [MAX_RATE_LIMIT_MS]，防坏头值卡死）。
     *
     * @param attempt 1-based 重试序号。
     * @param retryAfterMs 服务端 `Retry-After` 毫秒（0 = 未提供）。
     */
    fun rateLimitBackoffMillis(attempt: Int, retryAfterMs: Long = 0L): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        val base = RATE_LIMIT_SEQUENCE_MS.getOrElse(index) { MAX_RATE_LIMIT_MS }
        val serverAsk = retryAfterMs.coerceAtLeast(0L)
        return maxOf(base, serverAsk).coerceAtMost(MAX_RATE_LIMIT_MS)
    }
}
