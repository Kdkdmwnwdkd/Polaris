package com.beichen.polaris.core.runtime

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.AgentLoop
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RuntimeBoundAgentLoop] 的接线正确性：**执行权**真的落到了运行时上。
 *
 * 第三轮之后 loop 的构造点只有一个，但 `acquireTurn` 在生产路径上仍是零调用——
 * 队列层是一份没人用的库代码。本类的每一条测试都对着一个「接线漏一处就静默失效」：
 *
 * | 漏了会怎样 | 对应测试 |
 * |---|---|
 * | `send` 不 acquire → 队列层形同虚设 | [同 session 的第二个 send 排队] |
 * | `finally` 不 close → 一次失败永久锁死该入口 | [delegate 抛异常仍归还回合] |
 * | 不按代次过滤 → 旧回合迟到事件污染新回合 | [cancel 后旧回合的开始类事件被丢弃] |
 * | 收尾事件也丢 → 取消后工具卡永久「执行中」（T05） | [cancel 后旧回合的收尾类事件仍放行] |
 *
 * ## 每个挂起点都套 [withTimeout]
 *
 * 与 [AgentRuntimeTest] 同一理由：变异会故意把互斥改坏，那时 `await` 永远不返回。
 * 套上超时，坏变异是「测试红」，不是「门禁卡死」——后者结论都出不来。
 */
class RuntimeBoundAgentLoopTest {

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }

    /** 只实现接口最小面，send 的行为由用例注入。 */
    private class FakeLoop(
        private val body: suspend (String) -> List<ChatMessage> = { emptyList() },
    ) : AgentLoop {
        var updateCalls = 0

        override suspend fun send(
            userInput: String,
            images: List<String>,
            priorMessages: List<ChatMessage>,
        ): List<ChatMessage> = body(userInput)

        override fun update(
            systemPrompt: String?,
            memoryProvider: (() -> String)?,
            registry: ToolRegistry?,
            maxSteps: Int?,
        ) {
            updateCalls++
        }
    }

    private fun started(id: String) =
        AgentEvent.ToolStarted(ToolCall(id = id, name = "probe", arguments = JsonObject(emptyMap())))

    private fun finished(id: String) =
        AgentEvent.ToolFinished(ToolMessage(id = id, name = "probe", content = "ok"))

    private class Harness(
        val rt: AgentRuntime,
        val gate: TurnEventGate,
        val loop: RuntimeBoundAgentLoop,
        val events: MutableList<AgentEvent>,
    )

    private fun harness(
        rt: AgentRuntime = AgentRuntime.of(),
        session: String = "s",
        source: RunSource = RunSource.MAIN,
        body: suspend (String) -> List<ChatMessage> = { emptyList() },
    ): Harness {
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val gate = TurnEventGate(rt, session) { events.add(it) }
        val loop = RuntimeBoundAgentLoop(FakeLoop(body), rt, gate, session, source)
        return Harness(rt, gate, loop, events)
    }

    // —— 执行权：send 必须占用并归还回合 ——

    @Test
    fun `send 期间占用回合 结束后归还`() = runBlocking {
        val rt = AgentRuntime.of()
        var during = -1
        val h = harness(rt = rt, body = { during = rt.activeTurns(); emptyList() })
        withTimeout(TIMEOUT_MS) { h.loop.send("hi") }
        assertEquals("send 执行期间必须持有回合所有权", 1, during)
        assertEquals("send 返回后必须归还，否则该入口永久锁死", 0, rt.activeTurns())
    }

    @Test
    fun `delegate 抛异常仍归还回合`() = runBlocking {
        val h = harness(body = { error("boom") })
        var threw = false
        try {
            withTimeout(TIMEOUT_MS) { h.loop.send("hi") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("用例自身前提：delegate 的异常必须原样上抛", threw)
        assertEquals("异常路径也必须走 finally 归还", 0, h.rt.activeTurns())
    }

    @Test
    fun `同 session 的第二个 send 排队 不并发进入 delegate`() = runBlocking {
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val h = harness(body = { input ->
            val now = concurrent.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            order.add("start:$input")
            delay(120)
            order.add("end:$input")
            concurrent.decrementAndGet()
            emptyList()
        })

        val first = async { h.loop.send("a") }
        delay(30)  // 让第一个真的进入 delegate
        val second = async { h.loop.send("b") }
        withTimeout(TIMEOUT_MS) { awaitAll(first, second) }

        assertEquals("同一 (session, source) 不允许两个回合同时进入 loop", 1, peak.get())
        assertEquals(
            "第二个 send 必须排在第一个之后（不是交错）",
            listOf("start:a", "end:a", "start:b", "end:b"),
            order.toList(),
        )
        assertEquals(0, h.rt.activeTurns())
    }

    @Test
    fun `不同 session 的 send 可并行`() = runBlocking {
        val rt = AgentRuntime.of()
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val body: suspend (String) -> List<ChatMessage> = {
            val now = concurrent.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(120)
            concurrent.decrementAndGet()
            emptyList()
        }
        val a = harness(rt = rt, session = "s1", body = body)
        val b = harness(rt = rt, session = "s2", body = body)

        withTimeout(TIMEOUT_MS) {
            awaitAll(async { a.loop.send("x") }, async { b.loop.send("y") })
        }
        assertEquals("不同 session 是两个说话人，队列层不该把它们串起来", 2, peak.get())
    }

    // —— 代次号：迟到事件按代次丢弃 ——

    @Test
    fun `cancel 后旧回合的开始类事件被丢弃`() = runBlocking {
        val h = harness()
        val lease = withTimeout(TIMEOUT_MS) { h.rt.acquireTurn("s", RunSource.MAIN) }
        h.gate.bind(lease)
        // 取消前：正常透传
        h.gate.emit(started("c1"))
        assertEquals(1, h.events.size)

        h.rt.cancel("s")
        h.gate.emit(started("c2"))
        assertEquals("代次号已过期，开始类事件不得再进 UI", 1, h.events.size)
        lease.close()
    }

    @Test
    fun `cancel 后旧回合的收尾类事件仍放行`() = runBlocking {
        val h = harness()
        val lease = withTimeout(TIMEOUT_MS) { h.rt.acquireTurn("s", RunSource.MAIN) }
        h.gate.bind(lease)

        h.rt.cancel("s")
        // T05：取消时 loop 会在树尾闭合里补 ToolFinished，让「执行中」卡落终态。
        // 它必然带着过期代次号，丢了就让 UI 永久卡在「执行中」。
        h.gate.emit(finished("c1"))
        assertEquals("收尾事件是闭合已有状态，不能按代次丢弃", 1, h.events.size)
        lease.close()
    }

    @Test
    fun `cancel 后新回合的事件正常透传`() = runBlocking {
        val h = harness()
        val old = withTimeout(TIMEOUT_MS) { h.rt.acquireTurn("s", RunSource.MAIN) }
        h.gate.bind(old)
        h.rt.cancel("s")
        old.close()

        val fresh = withTimeout(TIMEOUT_MS) { h.rt.acquireTurn("s", RunSource.MAIN) }
        h.gate.bind(fresh)
        h.gate.emit(started("new"))
        assertEquals("新回合拿到的是新代次号，事件必须放行", 1, h.events.size)
        fresh.close()
    }

    @Test
    fun `未绑定回合时事件不转发`() = runBlocking {
        val h = harness()
        h.gate.emit(started("orphan"))
        assertFalse("send 之外不该有事件流", h.events.isNotEmpty())
    }

    @Test
    fun `事件闸门按会话隔离 不影响其他会话`() = runBlocking {
        val rt = AgentRuntime.of()
        val a = harness(rt = rt, session = "s1")
        val b = harness(rt = rt, session = "s2")
        // 套超时：变异 R6 会把所有键并成一个门，第二个 acquire 就永远不返回——
        // 不套的话坏变异不是「测试红」，而是门禁自己卡到 JUnit 超时（判 INVALID，
        // 看起来像用例本身有问题）。
        val la = withTimeout(TIMEOUT_MS) { rt.acquireTurn("s1", RunSource.MAIN) }
        val lb = withTimeout(TIMEOUT_MS) { rt.acquireTurn("s2", RunSource.MAIN) }
        a.gate.bind(la)
        b.gate.bind(lb)

        rt.cancel("s1")
        a.gate.emit(started("a1"))
        b.gate.emit(started("b1"))

        assertEquals("取消 s1 不该影响 s2 的事件流", 0, a.events.size)
        assertEquals(1, b.events.size)
        la.close(); lb.close()
    }
}
