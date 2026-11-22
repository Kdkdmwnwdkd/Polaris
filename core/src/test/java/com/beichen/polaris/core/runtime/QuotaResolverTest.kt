package com.beichen.polaris.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [QuotaResolver] 的判据。
 *
 * 兜底值刻意用 **7 / 1234**，不用 60 / 30 分钟：如果实现把兜底写死成常量，
 * 用默认值当兜底的测试是看不出来的——那正是这套判据要防的「看起来在管，其实没接线」。
 */
class QuotaResolverTest {

    private val fallbackSteps = 7
    private val fallbackDeadline = 1_234L

    @Test
    fun `QT1 全零配额落回兜底——0 是「没表态」，不是「零步」`() {
        val l = QuotaResolver.resolve(Quota(), fallbackSteps, fallbackDeadline)
        assertEquals("步数必须落回传入的兜底", 7, l.maxSteps)
        assertEquals("预算必须落回传入的兜底", 1_234L, l.deadlineMs)
    }

    @Test
    fun `QT2 只给步数时只覆盖步数——预算仍落兜底`() {
        val l = QuotaResolver.resolve(Quota(maxSteps = 3), fallbackSteps, fallbackDeadline)
        assertEquals(3, l.maxSteps)
        assertEquals(1_234L, l.deadlineMs)
    }

    @Test
    fun `QT3 只给预算时只覆盖预算——步数仍落兜底`() {
        val l = QuotaResolver.resolve(Quota(deadlineMs = 5_000L), fallbackSteps, fallbackDeadline)
        assertEquals(7, l.maxSteps)
        assertEquals(5_000L, l.deadlineMs)
    }

    @Test
    fun `QT4 两项都给时都不落兜底`() {
        val l = QuotaResolver.resolve(
            Quota(maxSteps = 3, deadlineMs = 5_000L), fallbackSteps, fallbackDeadline,
        )
        assertEquals(3, l.maxSteps)
        assertEquals(5_000L, l.deadlineMs)
    }

    @Test
    fun `QT5 负数按没设处理——防误传 -1 变成零步`() {
        val l = QuotaResolver.resolve(
            Quota(maxSteps = -1, deadlineMs = -9L), fallbackSteps, fallbackDeadline,
        )
        assertEquals(7, l.maxSteps)
        assertEquals(1_234L, l.deadlineMs)
    }

    @Test
    fun `QT6 兜底步数非正时当场抛错——静默零步比崩溃更难查`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuotaResolver.resolve(Quota(), 0, fallbackDeadline)
        }
    }

    @Test
    fun `QT7 兜底预算非正时当场抛错`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuotaResolver.resolve(Quota(), fallbackSteps, 0L)
        }
    }
}
