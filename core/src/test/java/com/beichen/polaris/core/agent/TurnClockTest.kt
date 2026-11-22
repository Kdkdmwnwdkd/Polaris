package com.beichen.polaris.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnClock 的判据：R5 时间线「可信」的最小集合。
 *
 * 这一组刻意只断言**信封**（turnId / step / elapsed / scope）与**单条 turn.end**，
 * 不断言事件种类有多少——后者是产品决定，改了不该红；前者一旦错了，整条时间线就
 * 不能用来定位「卡在哪个 tool.start」。
 */
class TurnClockTest {

    private class Recorder : TurnObserver {
        val events = ArrayList<TurnEvent>()
        override fun onEvent(event: TurnEvent) {
            events += event
        }
    }

    /** T1：信封四要素（turnId / step / elapsed / scope）与载荷原样透传。 */
    @Test
    fun `T1 事件信封带 turnId 与 scope，载荷原样透传`() {
        val rec = Recorder()
        val clock = TurnClock("t-1", "sub:标题", rec)

        clock.turnStarted(inputChars = 42, tools = 3)

        val e = rec.events.single() as TurnEvent.TurnStarted
        assertEquals("t-1", e.turnId)
        assertEquals("sub:标题", e.scope)
        assertEquals(0, e.step)
        assertTrue("elapsedMs 不得为负：${e.elapsedMs}", e.elapsedMs >= 0L)
        assertEquals(42, e.inputChars)
        assertEquals(3, e.visibleToolCount)
    }

    /** T2：step() 返回本次步号，后续事件采用同一个步号（否则时间线对不齐）。 */
    @Test
    fun `T2 step 返回本次步号并被后续事件采用`() {
        val rec = Recorder()
        val clock = TurnClock("t-2", "main", rec)

        assertEquals(3, clock.step(3))
        assertEquals(3, clock.currentStep())

        clock.llmStarted(step = clock.currentStep(), attempt = 2)
        val e = rec.events.single() as TurnEvent.LlmCallStarted
        assertEquals(3, e.step)
        assertEquals(2, e.attempt)
    }

    /** T3：turn.end 只有一条，且 steps = 末步号 + 1（步号 0 起）。 */
    @Test
    fun `T3 turn_end 唯一且 steps 等于末步号加一`() {
        val rec = Recorder()
        val clock = TurnClock("t-3", "main", rec)
        clock.step(4)

        clock.emitTurnEnd("cancelled")

        val e = rec.events.single() as TurnEvent.TurnEnded
        assertEquals("t-3", e.turnId)
        assertEquals("main", e.scope)
        assertEquals("cancelled", e.status)
        assertEquals(4, e.step)
        assertEquals(5, e.steps)
    }

    /** T4：elapsed 非负且不回退（用相对时间就是为了「不出现负数或跳跃」）。 */
    @Test
    fun `T4 elapsed 非负且单调不回退`() {
        val clock = TurnClock("t-4", "main", Recorder())
        val a = clock.elapsed()
        Thread.sleep(2)
        val b = clock.elapsed()
        assertTrue("elapsed 不得为负：$a", a >= 0L)
        assertTrue("elapsed 不得回退：$a -> $b", b >= a)
    }

    /** T5：观察者抛异常不得穿透 emit（观测不能有副作用能力）。 */
    @Test
    fun `T5 观察者抛异常不穿透 emit`() {
        val clock = TurnClock("t-5", "main", TurnObserver { throw IllegalStateException("boom") })

        clock.turnStarted(inputChars = 1, tools = 1)
        clock.emitTurnEnd("failed")
    }

    /** T6：TurnEndStatus 默认 completed，只被 set 改写。 */
    @Test
    fun `T6 TurnEndStatus 默认 completed 且只被 set 改写`() {
        val status = TurnEndStatus()
        assertEquals("completed", status.value)
        status.set("failed")
        assertEquals("failed", status.value)
    }
}
