package com.beichen.polaris.core.agent

import com.beichen.polaris.core.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 守卫链的语义（2026-11-19）。
 *
 * ## 为什么这几条必须真跑
 *
 * [ToolGuardChain] 的全部安全性质都写在 KDoc 里，没有一条能靠源码扫描证明：
 *
 * | 性质 | 写错的后果 | 静态检查看得出来吗 |
 * |---|---|---|
 * | 崩溃 = 拒绝 | 改成 `catch { Abstain }` 就是静默放权，装了守卫反而更危险 | 看不出 |
 * | 超时 = 拒绝 | 一次卡死让整回合停在一步，用户只看到「卡住了」 | 看不出 |
 * | 首个 Deny 即终止 | 后续守卫无法「推翻」拒绝，链才单调递减 | 看不出 |
 * | 取消要穿透 | 点「停止」被兜成一条错误结果，界面显示「工具失败」而不是「已取消」 | 看不出 |
 *
 * 原来只有行为段 D7 覆盖了「用各自 timeoutMs」一条，而行为段在 CI 上是 SKIP。
 *
 * ## 为什么超时用例只能用 `delay`
 *
 * 生产上真正的坏守卫是「内部跑阻塞 IO，不响应挂起取消」。那种情况在 JVM 上测不出来：
 * `Thread.sleep` 占住唯一的事件循环线程，`withTimeoutOrNull` 自己也调度不上。
 * 所以这里只证明「超时会被判成拒绝」，不假装证明了「阻塞守卫也能被打断」。
 */
class ToolGuardChainTest {

    private fun ctx(name: String = "shell_run") =
        GuardContext(ToolCall("id-$name", name, JsonObject(emptyMap())), null, 0, "main")

    private fun guard(
        decision: GuardDecision = GuardDecision.Abstain,
        timeoutMs: Long = ToolGuardChain.GUARD_TIMEOUT_MS,
        body: (suspend () -> GuardDecision)? = null,
    ): ToolGuard = object : ToolGuard {
        override val timeoutMs: Long get() = timeoutMs
        override suspend fun check(ctx: GuardContext): GuardDecision = body?.invoke() ?: decision
    }

    private fun denyCode(d: GuardDecision): String? = (d as? GuardDecision.Deny)?.code

    // ------------------------------------------------------------ 正常路径

    @Test
    fun `空链对任何调用都弃权`() = runBlocking {
        assertEquals(GuardDecision.Abstain, ToolGuardChain.NONE.check(ctx()))
    }

    @Test
    fun `全部弃权等于放行`() = runBlocking {
        val chain = ToolGuardChain(listOf(guard(), guard(), guard()))
        assertEquals(GuardDecision.Abstain, chain.check(ctx()))
    }

    @Test
    fun `首个拒绝即终止，后面的守卫不再被询问`() = runBlocking {
        // 「后面还能说话」就意味着后面的守卫可以推翻前一个的拒绝——链不再是单调递减的。
        val called = AtomicInteger(0)
        val chain = ToolGuardChain(
            listOf(
                guard(),
                guard(GuardDecision.Deny("path_denied", "越界读")),
                guard(body = { called.incrementAndGet(); GuardDecision.Abstain }),
            )
        )
        assertEquals("path_denied", denyCode(chain.check(ctx())))
        assertEquals("拒绝之后仍在询问后续守卫", 0, called.get())
    }

    @Test
    fun `拒绝原样透传，不被改写`() = runBlocking {
        // code 是模型的学习信号（按 code 换路走）。包一层就会让它永远看不到真实原因。
        val original = GuardDecision.Deny("egress_blocked", "内容会外发", "先问用户")
        val chain = ToolGuardChain(listOf(guard(original)))
        assertEquals(original, chain.check(ctx()))
    }

    // ------------------------------------------------------- 崩溃 = 拒绝

    @Test
    fun `守卫抛异常按拒绝处理`() = runBlocking {
        val chain = ToolGuardChain(listOf(guard(body = { error("boom") })))
        val d = chain.check(ctx())
        assertEquals("守卫崩溃不能被当成放行", "guard_failed", denyCode(d))
    }

    @Test
    fun `守卫抛 Error 也按拒绝处理`() = runBlocking {
        // catch (e: Exception) 会漏掉 NoClassDefFoundError / StackOverflowError ——
        // 那恰恰是「写得很烂的守卫」最常见的崩溃形态。
        val chain = ToolGuardChain(listOf(guard(body = { throw StackOverflowError("recursion") })))
        assertEquals("guard_failed", denyCode(chain.check(ctx())))
    }

    @Test
    fun `崩溃的拒绝文案带守卫序号`() = runBlocking {
        val chain = ToolGuardChain(
            listOf(guard(), guard(body = { error("boom") }))
        )
        val msg = (chain.check(ctx()) as GuardDecision.Deny).message
        assertTrue("报障时定不到是哪一条守卫：$msg", msg.contains("第 2 个"))
    }

    @Test
    fun `崩溃之后不再询问后续守卫`() = runBlocking {
        val called = AtomicInteger(0)
        val chain = ToolGuardChain(
            listOf(
                guard(body = { error("boom") }),
                guard(body = { called.incrementAndGet(); GuardDecision.Abstain }),
            )
        )
        chain.check(ctx())
        assertEquals(0, called.get())
    }

    // ------------------------------------------------------- 超时 = 拒绝

    @Test
    fun `守卫超时按拒绝处理`() = runBlocking {
        val chain = ToolGuardChain(listOf(guard(timeoutMs = 50, body = { delay(500); GuardDecision.Abstain })))
        assertEquals("guard_timeout", denyCode(chain.check(ctx())))
    }

    @Test
    fun `超时用守卫自己的上限而不是链默认值`() = runBlocking {
        // 需要问用户的守卫会把上限放大到 60 秒。链若按默认 3 秒算，
        // 用户还没读完弹窗就收到 guard_timeout。
        val chain = ToolGuardChain(listOf(guard(timeoutMs = 100, body = { delay(500); GuardDecision.Abstain })))
        val msg = (chain.check(ctx()) as GuardDecision.Deny).message
        assertTrue("超时文案没有体现实际预算（应为 100ms）：$msg", msg.contains("100ms"))
    }

    @Test
    fun `未覆盖 timeoutMs 的守卫继承链默认值`() = runBlocking {
        assertEquals(ToolGuardChain.GUARD_TIMEOUT_MS, guard().timeoutMs)
    }

    // ------------------------------------------------------- 取消要穿透

    @Test
    fun `CancellationException 不被兜成拒绝`() = runBlocking {
        // 点「停止」要能立刻退出。兜成 Deny 的话，用户看到的是「工具执行失败」，
        // 而实际上是他自己取消的——这类假错误会让人反复重试。
        val chain = ToolGuardChain(listOf(guard(body = { throw CancellationException("user stopped") })))
        var cancelled: Throwable? = null
        try {
            chain.check(ctx())
        } catch (e: Throwable) {
            cancelled = e
        }
        assertTrue("取消被兜住了：${cancelled?.let { it::class.java.simpleName }}", cancelled is CancellationException)
    }

    @Test
    fun `弃权与拒绝混合时按序判定`() = runBlocking {
        val chain = ToolGuardChain(
            listOf(guard(), guard(), guard(GuardDecision.Deny("last", "最后一道")), guard())
        )
        assertEquals("last", denyCode(chain.check(ctx())))
    }
}
