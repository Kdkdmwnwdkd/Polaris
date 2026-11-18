package com.beichen.polaris.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作回执的判定（2026-11-19）。
 *
 * ## 为什么这段必须测
 *
 * 它决定「告诉模型点击生效了没有」。两个方向都危险，而且方向相反：
 *
 * - **误报生效**（超时当已变化）：模型以为点到了，实际被弹窗挡住。它不会重试，
 *   整条任务在无人知晓的地方断掉。
 * - **误报没生效**（界面变了也判没变）：模型开始重复点同一位置，可能触发两次提交。
 *
 * 原来的实现和 `AgentAccessibilityService` 绑在一起，JVM 一行都跑不了，只能上真机点。
 *
 * ## 为什么用真实 delay 而不是虚拟时间
 *
 * `kotlinx-coroutines-test` 要额外依赖，而这里要验证的恰恰是「轮询**确实**退让了」。
 * 虚拟时间会把 `delay` 变成立刻返回，于是「删掉 delay」这种变异测不出来——
 * 而那正是最坏的一种：忙等循环里没有挂起点，`withTimeoutOrNull` 检测不到超时，
 * 函数**永远不会返回**。
 *
 * 为此每条用例都给 `sense` 加了调用次数上限：超限主动抛断言失败，
 * 让「忙等」表现成红，而不是让 CI 卡在那里。
 */
class ScreenChangeReceiptTest {

    /** 计数 + 上限：防止变异把轮询变成死循环，让失败可见。 */
    private class Screen(var value: Int, private val limit: Int = 2_000) {
        var calls = 0
        fun sense(): Int {
            calls++
            if (calls > limit) throw AssertionError("轮询没有退让：$calls 次调用仍未超时")
            return value
        }
    }

    // ------------------------------------------------------------ 检测到变化

    @Test(timeout = 5_000)
    fun `界面已变化时立即返回 true`() = runBlocking {
        // 动作之前拍的是 0，动作之后读到 42——第一次取指纹就该退出，不该空等 600ms。
        val screen = Screen(42)
        val changed = awaitFingerprintChange(before = 0, timeoutMs = 500, pollMs = 20) { screen.sense() }
        assertTrue("界面已经变了却报没变化", changed)
        assertEquals("第一次就变了，不该继续轮询", 1, screen.calls)
    }

    @Test(timeout = 5_000)
    fun `第 N 次轮询时才变化也算检测到`() = runBlocking {
        var n = 0
        val changed = awaitFingerprintChange(before = 0, timeoutMs = 500, pollMs = 10) {
            if (++n >= 3) 42 else 0
        }
        assertTrue("第 3 次才变就漏了", changed)
        assertEquals(3, n)
    }

    // ---------------------------------------------------------------- 超时

    @Test(timeout = 5_000)
    fun `界面一直不变时超时返回 false`() = runBlocking {
        val screen = Screen(0)
        val changed = awaitFingerprintChange(before = 0, timeoutMs = 120, pollMs = 20) { screen.sense() }
        assertFalse("超时被当成了「已变化」——模型会以为点击生效，实际什么都没发生", changed)
        assertTrue("轮询次数 ${screen.calls} 与 120ms/20ms 对不上", screen.calls in 3..12)
    }

    @Test(timeout = 5_000)
    fun `轮询有间隔，不是忙等`() = runBlocking {
        // 忙等会在 200ms 内调上千次；正常轮询是 200/50 = 4 次左右。
        val screen = Screen(0)
        awaitFingerprintChange(before = 0, timeoutMs = 200, pollMs = 50) { screen.sense() }
        assertTrue("200ms 内取了指纹 ${screen.calls} 次——没有 delay，主线程会被烧满", screen.calls <= 8)
    }

    @Test(timeout = 5_000)
    fun `超时窗口为零时直接判没变化且不取指纹`() = runBlocking {
        val screen = Screen(42)
        val changed = awaitFingerprintChange(before = 0, timeoutMs = 0, pollMs = 20) { screen.sense() }
        assertFalse(changed)
        assertEquals("timeout<=0 不该再去取指纹", 0, screen.calls)
    }

    @Test(timeout = 5_000)
    fun `负超时按零处理`() = runBlocking {
        val screen = Screen(42)
        assertFalse(awaitFingerprintChange(before = 0, timeoutMs = -100, pollMs = 20) { screen.sense() })
        assertEquals(0, screen.calls)
    }

    // -------------------------------------------------------------- 异常传播

    @Test(timeout = 5_000)
    fun `取指纹抛异常时向上传播`() = runBlocking {
        // 无障碍服务断开时 getUiTreeSnapshot 会抛。吞掉它会让 runOnService 误判成
        // 「动作成功但界面没变」，模型开始盲点。
        var thrown: Throwable? = null
        try {
            awaitFingerprintChange(before = 0, timeoutMs = 500, pollMs = 20) { error("service dead") }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("异常被吞了：${thrown?.message}", thrown is IllegalStateException)
    }

    @Test(timeout = 5_000)
    fun `异常不会等到超时才抛`() = runBlocking {
        val started = System.currentTimeMillis()
        runCatching { awaitFingerprintChange(before = 0, timeoutMs = 2_000, pollMs = 20) { error("boom") } }
        val cost = System.currentTimeMillis() - started
        assertTrue("异常被当成了「没变化」，白等 ${cost}ms", cost < 1_000)
    }

    @Test(timeout = 5_000)
    fun `取消向上传播而不是变成 false`() = runBlocking {
        // 用户点「停止」时抛 CancellationException。兜成 false 的话，runOnService 会把
        // 它当成「动作执行了但界面没变」，回一条 no_effect 给模型。
        var thrown: Throwable? = null
        try {
            awaitFingerprintChange(before = 0, timeoutMs = 5_000, pollMs = 20) {
                throw kotlinx.coroutines.CancellationException("user stopped")
            }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("取消被兜住了", thrown is kotlinx.coroutines.CancellationException)
    }

    // ------------------------------------------------------------ 常量与文案

    @Test
    fun `默认窗口是 600ms、轮询 80ms`() {
        // 这两个数是真机 P95 的经验值，不是随手写的。改了要同步改这里和 NO_EFFECT_HINT。
        assertEquals(600L, SCREEN_CHANGE_TIMEOUT_MS)
        assertEquals(80L, SCREEN_CHANGE_POLL_MS)
    }

    @Test
    fun `提示文案里的时间与常量同源`() {
        // 文案写死「600ms」而常量改成 800 的话，模型看到的是一个不存在的窗口。
        assertTrue(
            "文案里的时间与常量不一致：$NO_EFFECT_HINT",
            NO_EFFECT_HINT.contains("${SCREEN_CHANGE_TIMEOUT_MS}ms")
        )
        assertTrue("提示必须明确劝阻重复点击", NO_EFFECT_HINT.contains("不要重复点"))
    }
}
