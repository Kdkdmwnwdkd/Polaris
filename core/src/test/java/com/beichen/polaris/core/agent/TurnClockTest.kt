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

    /**
     * T6：TurnEndStatus 默认 **Unknown**（fail-closed），只被 set 改写。
     *
     * 修前默认是 `completed`：`runTurn` 的 finally 无条件发事件，任何新增出口只要
     * 忘了 set，日志里就显示「完成」——把「没做完」伪装成「做完了」，而且伪装得
     * 恰好是最该被看见的那一端（用满步数 / 超预算 / 用户停止）。
     *
     * 现在默认 Unknown：忘了 set 会留下 `status=unknown`，排障时一眼看见，
     * 而不是静默混进「完成」里。
     */
    @Test
    fun `T6 TurnEndStatus 默认 Unknown 且只被 set 改写`() {
        val status = TurnEndStatus()
        assertEquals(TurnExit.Unknown, status.value)
        assertTrue("默认值不能是 Completed——那是 fail-open", status.value != TurnExit.Completed)
        status.set(TurnExit.MaxSteps)
        assertEquals(TurnExit.MaxSteps, status.value)
        assertTrue("用满步数不算完成", !status.value.isCompleted)
    }

    /**
     * T7：取消收尾要拿得到「从 start 到此刻」的真实时长。
     *
     * 真机上两次 `shell sleep 30` 分别跑了 2.2s / 9.7s，取消路径写死 0L，全被记成
     * dur=0——遥测里「用户等了多久才按停止」就此消失。这里断言两件事：派发过的
     * 调用能给出正数，没派发的给 0（它确实一秒没跑，0 是事实不是缺失）。
     */
    @Test
    fun `T7 取消时能取到从 start 起的真实时长`() {
        val clock = TurnClock("t-7", "main", Recorder())

        assertEquals("没派发过的调用应为 0", 0L, clock.elapsedSinceToolStart("call_从未派发"))

        clock.toolStarted(step = 0, tool = "shell_run", args = "{}", callId = "call_00_aaa")
        Thread.sleep(5)
        val waited = clock.elapsedSinceToolStart("call_00_aaa")
        assertTrue("派发过的调用必须有正时长，实际 $waited", waited >= 5L)

        // 同批并行的另一个调用不能被这笔记录影响（按 callId 分开，不是按 step）
        clock.toolStarted(step = 0, tool = "read_file", args = "{}", callId = "call_01_bbb")
        assertEquals("不同 callId 互不串账", 0L, clock.elapsedSinceToolStart("call_02_从未"))
    }

    /**
     * T8：日志短 id 必须能区分同批并行调用。
     *
     * 真实 id 是 `call_00_...` / `call_01_...` / `call_02_...`。截 6 位时三者全是
     * `call_0`，日志看着有值实则全一样——2026-11-23 的真机验证正是被它误导，
     * 差点把一个不存在的「call id 重复」缺陷当成真问题去改 UI 的归并键。
     */
    @Test
    fun `T8 短 id 能区分同批的三个并行调用`() {
        val a = "call_00_ny3UxcMRXPYSqmrW54W09467"
        val b = "call_01_24jFmEeHoRWosChWFM5r8187"
        val c = "call_02_igDt5Wn26OWfuRW41pfT1641"

        val shorts = listOf(a, b, c).map { shortCallId(it) }
        assertEquals("三个并行调用的短 id 必须互不相同，实际 $shorts", 3, shorts.toSet().size)
        // 长度有界：不能退化成打印全 id（一行两个 30 位长串就看不出哪行是哪行）
        assertTrue("短 id 不得长于 ${SHORT_CALL_ID_LEN}：$shorts", shorts.all { it.length <= SHORT_CALL_ID_LEN })
        // 空 id 有占位符，不能打印成空串（否则日志里 call= 后面什么都没有）
        assertEquals("-", shortCallId(""))
    }
}
