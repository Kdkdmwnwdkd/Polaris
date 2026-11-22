package com.beichen.polaris.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避排期的行为断言（2026-11-22）。
 *
 * ## 为什么要有这一份
 *
 * 退避数值此前只有 `scripts/qa/Hotfix3Verification.kt` 在守（104 条断言，含限流序列、
 * Retry-After 取大、网络恢复打断等待）。那份 harness 是好的，但它编不进退避的
 * **纯数值部分**——`RetryBackoffPolicy.networkAware` 的形参类型 `NetworkRecoveryWaiter`
 * 在 `NetworkRecovery.kt` 里，而那个文件 import android.net。于是：
 *
 * - harness 只能在「有 app 产物」的环境跑；
 * - `mutation-gate.py`（只有 kotlinc + JUnit）根本没有 retry 组。
 *
 * 2026-11-22 把数值拆进 [RetrySchedule]（零依赖）后，这一组才能被变异门禁覆盖。
 * 断言不多，但每一条都对应一个**会静默烧钱或静默变慢**的改动方向：
 * 越界不封顶 → 无限等待；限流退化成通用序列 → 连着再挨 429；忽略 Retry-After → 无视服务端。
 */
class RetryScheduleTest {

    // ------------------------------------------------------------ 通用退避

    @Test
    fun `B1 通用退避按序列取值`() {
        assertEquals(3_000L, RetrySchedule.backoffMillis(1))
        assertEquals(10_000L, RetrySchedule.backoffMillis(2))
        assertEquals(25_000L, RetrySchedule.backoffMillis(3))
    }

    @Test
    fun `B2 越界取末项封顶`() {
        // 没封顶的后果不是「等久一点」，是回合卡在一个不会结束的等待里。
        assertEquals(25_000L, RetrySchedule.backoffMillis(4))
        assertEquals(25_000L, RetrySchedule.backoffMillis(99))
    }

    @Test
    fun `B3 非法序号按第 1 次处理`() {
        // 调用方传错（0 或负数）时不能变成忙等，也不能抛 IndexOutOfBounds。
        assertEquals(3_000L, RetrySchedule.backoffMillis(0))
        assertEquals(3_000L, RetrySchedule.backoffMillis(-5))
    }

    // ------------------------------------------------------------ 429 限流退避

    @Test
    fun `R1 限流序列每一档都比通用序列更久`() {
        // 这就是 hotfix10 的回归点：限流退回 3s/10s 等于没等，第 3 次 429 直接整个回合失败。
        for (attempt in 1..3) {
            assertTrue(
                "第 $attempt 次重试：限流 ${RetrySchedule.rateLimitBackoffMillis(attempt)}ms " +
                    "应长于通用 ${RetrySchedule.backoffMillis(attempt)}ms",
                RetrySchedule.rateLimitBackoffMillis(attempt) > RetrySchedule.backoffMillis(attempt)
            )
        }
    }

    @Test
    fun `R2 服务端要得更久时取大值`() {
        // 服务端说 45s、我们本来只打算等 10s → 按 45s 等，否则等于没尊重 Retry-After。
        assertEquals(45_000L, RetrySchedule.rateLimitBackoffMillis(1, retryAfterMs = 45_000L))
        // 服务端只要求 5s、我们自己该等 30s → 取 30s（不能因为服务端说得短就提前撞上去）。
        assertEquals(30_000L, RetrySchedule.rateLimitBackoffMillis(2, retryAfterMs = 5_000L))
    }

    @Test
    fun `R3 限流退避封顶 60s`() {
        // 越界取末项：60s。
        assertEquals(60_000L, RetrySchedule.rateLimitBackoffMillis(4))
        // 坏头值（300s）不得把回合卡死 5 分钟。
        assertEquals(60_000L, RetrySchedule.rateLimitBackoffMillis(1, retryAfterMs = 300_000L))
    }

    @Test
    fun `R4 负 Retry-After 当作未提供`() {
        // 某些网关会回负数。负值不能把 base 拉低（maxOf 保底），也不能让结果变负。
        assertEquals(10_000L, RetrySchedule.rateLimitBackoffMillis(1, retryAfterMs = -1L))
    }
}
