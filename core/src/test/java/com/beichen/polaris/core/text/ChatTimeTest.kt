package com.beichen.polaris.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatTime 的时区判据（2026-11-23 真机 bug 的回归集）。
 *
 * ## 为什么这组必须存在
 *
 * 改造前 `partsOf` 按 UTC 拆时分，注释还写着「最多差一天，可接受」。真机上这话一眼被推翻：
 * 北京时间 21:45 的消息显示成 `13:45`，用户直接问「这时间是哪里的，看不懂」。
 * 更隐蔽的是跨日边界——北京时间 00:30 在 UTC 里属于前一天，会被打成「昨天 16:30」。
 *
 * 所以判据锁的不是「格式好看」，而是**偏移真的被用上了**：
 *  - 同一时刻换一个 offset，时钟必须跟着变（UTC 21:45 → 北京 05:45 次日）；
 *  - 跨日时日期与周几都要进位，不能只改时分；
 *  - 偏移为 0 时行为必须与改造前逐字一致（老断言不能被动过）。
 *
 * 基准时刻：`1_700_000_000_000` = 2023-11-14 22:13:20 UTC（周二）。
 */
class ChatTimeTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 86_400_000L
        const val BEIJING = ChatTime.OFFSET_BEIJING_MS

        /** UTC-5（纽约冬令时）：用来确认负偏移不会被算成前一天的次日。 */
        const val NEW_YORK = -5 * 3_600_000
    }

    /** CT1：偏移必须真的参与拆解，而不是只改个参数名。 */
    @Test
    fun `CT1 同一时刻按北京时间显示要进位到次日`() {
        // 22:13 UTC + 8h = 次日 06:13
        assertEquals("06:13", ChatTime.format(NOW, NOW, BEIJING))
        assertEquals("2023-11-15 06:13", ChatTime.precise(NOW, BEIJING))
    }

    /** CT2：负偏移（美洲）也要正确——floorDiv 而不是整除。 */
    @Test
    fun `CT2 负偏移：UTC 22点13 在纽约是当日 17点13`() {
        assertEquals("17:13", ChatTime.format(NOW, NOW, NEW_YORK))
        assertEquals("2023-11-14 17:13", ChatTime.precise(NOW, NEW_YORK))
    }

    /** CT3：周几按本地日历算，不是按 UTC 天数算。 */
    @Test
    fun `CT3 跨日周几：UTC 周六 22点13 在北京已是周日`() {
        // NOW - 3 天 = 2023-11-11 22:13 UTC（周六）→ 北京 11-12 06:13（周日）
        assertEquals("周日 06:13", ChatTime.format(NOW - 3 * DAY, NOW, BEIJING))
        // 同一时刻按 UTC 仍是周六——两边不同才说明偏移生效了
        assertEquals("周六 22:13", ChatTime.format(NOW - 3 * DAY, NOW, 0))
    }

    /** CT4：offset=0 时与改造前逐字一致（老的 W-30 断言口径不能变）。 */
    @Test
    fun `CT4 UTC 偏移下行为与改造前一致`() {
        assertEquals("22:13", ChatTime.format(NOW, NOW, 0))
        assertEquals("昨天 22:13", ChatTime.format(NOW - DAY, NOW, 0))
        assertEquals("11月4日", ChatTime.format(NOW - 10 * DAY, NOW, 0))
        assertEquals("2023-11-14 22:13", ChatTime.precise(NOW, 0))
    }

    /** CT5：无效时间戳仍然返回空串（UI 据此不渲染，不能因为多一个参数就漏判）。 */
    @Test
    fun `CT5 时间戳为 0 返回空串，与偏移无关`() {
        assertTrue(ChatTime.format(0, NOW, BEIJING).isEmpty())
        assertTrue(ChatTime.precise(0, BEIJING).isEmpty())
        assertTrue(ChatTime.format(NOW, 0, BEIJING).isEmpty())
    }

    /** CT6：localOffset 对无效时间也要给一个可用值（不能因为 ms=0 就除以 0 或抛）。 */
    @Test
    fun `CT6 localOffset 对 0 也返回当前时区偏移`() {
        val now = ChatTime.localOffset(System.currentTimeMillis())
        val fromZero = ChatTime.localOffset(0)
        // 中国的偏移是 +8h；这里不写死 8，只要求「是个合理值且与此刻一致」
        assertTrue("偏移应在 ±14h 内，实际 $now", now in -14 * 3_600_000..14 * 3_600_000)
        assertEquals(now, fromZero)
    }
}
