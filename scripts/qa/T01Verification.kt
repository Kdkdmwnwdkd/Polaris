#!/usr/bin/env kotlin
/**
 * Phase 1 T01（Agent 运行时内核抽象）QA 行为验证 harness。
 *
 * 背景：仓库无任何测试源集（roadmap 验收要求单测），且本机离线 Gradle 缓存中
 * 没有 JUnit jar，无法在本地跑标准单测。本 harness 用纯 main() + 手写断言，
 * 直接对 assembleDebug 编译产物里的 core 契约类做行为验证——与 T01 验收要点逐条对应：
 *  ① weather/wordcount 两 skill 走新 loop 全流程（流式 + 工具 + 回喂）；
 *  ①' 流式：TextDelta → onTextDelta 拼合，Completed 整条 commit；
 *  ② 并发工具调用结果保序（含图片回流顺序）；
 *  ③ executor 违约（实现抛异常）→ 回合 Failed(ToolExecutionFailed)、不回喂、非取消；
 *  ③' 工具执行业务异常 → 仍回喂错误 ToolMessage（T02 语义不破）；
 *  ④ 取消 / stop() / shouldContinue 三路径下树尾闭合不变量；
 *  T04 熔断不破；Hooks 四时机计数；update() 生效；descriptor 映射字段；
 *  OpenAIClient SSE 工具调用累积（纯函数级，不发网络）。
 *
 * 运行方式见 scripts/qa/run-t01-verification.sh（kotlin-compiler-embeddable 编译 + java 运行）。
 */

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.Consent
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.OpenAIClient
import com.beichen.polaris.core.OpenAiToolCallAccumulator
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolPart
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.agent.Hooks
import com.beichen.polaris.core.agent.NoopHooks
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.agent.RegistryToolExecutor
import com.beichen.polaris.core.agent.ToolCallOutcome
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.core.agent.ToolExecutionFailedException
import com.beichen.polaris.core.agent.ToolExecutor
import com.beichen.polaris.core.llm.ChatStreamEvent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetSocketAddress
import kotlin.text.Charsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("expected to contain <$needle> in <${haystack?.take(120)}>")
    }
}

/** 跑一块并捕获异常（供「应当抛出」类断言；没抛返回 error 的 AssertionError）。 */
private suspend fun catchThrowable(block: suspend () -> Unit): Throwable =
    try {
        block()
        throw AssertionError("expected an exception but nothing was thrown")
    } catch (t: Throwable) {
        t
    }

/* ------------------------------ 测试替身 ------------------------------ */

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
private fun JsonObject.prim(key: String): String? = (this[key] as? JsonPrimitive)?.content
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** 脚本化 Fake LLM：按调用次序返回预设 AssistantMessage；streamChat 吃接口默认实现。 */
private class FakeLlm(private val script: List<AssistantMessage>) : LLMClient {
    override val id: String = "fake"
    private var i = 0

    /** LLM 轮次计数（chat 与 streamChat 共用）。 */
    val rounds = AtomicInteger(0)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        rounds.incrementAndGet()
        val r = script[minOf(i, script.size - 1)]
        i++
        return r
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 流式剧本 Fake：streamChat 按脚本发射 TextDelta + Completed（harness ①' 用）。 */
private class StreamingFakeLlm(private val script: List<List<ChatStreamEvent>>) : LLMClient {
    override val id: String = "fake-stream"
    private var i = 0
    val rounds = AtomicInteger(0)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        rounds.incrementAndGet()
        val events = script[minOf(i, script.size - 1)]
        i++
        return events.filterIsInstance<ChatStreamEvent.Completed>().last().message
    }

    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> = flow {
        rounds.incrementAndGet()
        val events = script[minOf(i, script.size - 1)]
        i++
        events.forEach { emit(it) }
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/**
 * weather 语义假工具：readOnly=true（可并行），带真实 JsonSchema（descriptor 断言用）
 * 与 systemPrompt（buildSystemPrompt 拼接断言用）。
 */
private class WeatherTool : AgentTool {
    override val name = "weather"
    override val description = "查询城市天气"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("city", "string", "城市名"),
        required = listOf("city")
    )
    override val systemPrompt = "weather 工具：先问清城市再调用。"
    override val readOnly = true
    var executions = 0

    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        val city = args.str("city")
        return ToolResult.ok(code = "weather_ok", message = "$city 今天晴，气温 26℃")
    }
}

/** wordcount 语义假工具：readOnly=true，统计入参文本长度。 */
private class WordCountTool : AgentTool {
    override val name = "wordcount"
    override val description = "统计文本字符数"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "待统计文本"),
        required = listOf("text")
    )
    override val readOnly = true
    var executions = 0

    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        return ToolResult.ok(code = "count_ok", message = "共 ${args.str("text").length} 字符")
    }
}

/** 通用假工具：readOnly 可配、可配延时、可配副作用/异常、可配图片回流。 */
private class FakeTool(
    override val name: String,
    override val readOnly: Boolean,
    private val delayMs: Long = 0,
    private val onExecute: (suspend () -> Unit)? = null,
    private val imageUrl: String? = null
) : AgentTool {
    override val description: String = "fake $name"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    var executions = 0

    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        onExecute?.invoke()
        if (delayMs > 0) delay(delayMs)
        val parts = if (imageUrl != null) listOf(ToolPart.Image(imageUrl)) else emptyList()
        return ToolResult.ok(code = "fake_ok", message = "$name done", data = "payload-$name", parts = parts)
    }
}

/**
 * 违约 executor（验收③）：execute 抛 RuntimeException——模拟 executor **实现代码**
 * 漏洞（区别于工具 execute 抛的业务异常）。
 */
private class RogueExecutor : ToolExecutor {
    val calls = AtomicInteger(0)

    override suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome {
        calls.incrementAndGet()
        throw RuntimeException("rogue NPE inside executor (${call.name})")
    }

    override fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome =
        ToolCallOutcome.Interrupted
}

/** 取消替身 executor：execute 抛 CancellationException——验证「取消绝不是失败」。 */
private class CancelRogueExecutor : ToolExecutor {
    override suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome {
        throw CancellationException("rogue cancel (${call.name})")
    }

    override fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome =
        ToolCallOutcome.Interrupted
}

/** 记录型 Hooks（四时机命中计数；并行批次下用原子量，防计数丢失）。 */
private class RecordingHooks : Hooks {
    val beforeToolCall = AtomicInteger(0)
    val afterToolCall = AtomicInteger(0)
    val beforeStop = AtomicInteger(0)
    val beforeSerialization = AtomicInteger(0)
    val toolCallNames: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    override fun beforeToolCall(call: ToolCall) {
        beforeToolCall.incrementAndGet()
        toolCallNames += call.name
    }

    override fun afterToolCall(call: ToolCall, message: ToolMessage) {
        afterToolCall.incrementAndGet()
    }

    override fun beforeStop() {
        beforeStop.incrementAndGet()
    }

    override fun beforeSerialization(messages: List<ChatMessage>) {
        beforeSerialization.incrementAndGet()
    }
}

/** T05 树尾闭合不变量：已 commit 的 ToolCall 必有配对 ToolMessage。 */
private fun invariantHolds(history: List<ChatMessage>): Boolean {
    val toolIds = history.filterIsInstance<ToolMessage>().map { it.id }.toSet()
    return history.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }.all { it.id in toolIds }
}

/* ------------------------------ ①：全流程（适配层 run） ------------------------------ */

private suspend fun testT01FullFlow() {
    println("\n== T01-① 全流程：weather/wordcount 走新 loop（流式默认通道 + 工具 + 回喂） ==")

    check("Assistant 整条 commit 含 ToolCall；工具真执行；结果回喂；终文本返回") {
        val weather = WeatherTool()
        val counter = WordCountTool()
        val reg = ToolRegistry().register(weather).register(counter)
        val calls = listOf(
            ToolCall("call_w", "weather", argsOf("city" to "北京")),
            ToolCall("call_c", "wordcount", argsOf("text" to "今天天气不错"))
        )
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", calls),
                AssistantMessage("北京今天晴，气温 26℃；文本共 7 字符。")
            )
        )
        // 直连 RealAgentLoop（适配层已删）：这是 ChatViewModel 生产链路同一条构造路径
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            onEvent = { events += it }
        )
        val history = loop.send("北京天气怎么样？顺便数数这句话")

        // 工具真执行
        assertEquals("weather executions", 1, weather.executions)
        assertEquals("wordcount executions", 1, counter.executions)
        // Assistant 整条 commit 含 ToolCall（id/name/arguments 原样进历史）
        val assistants = history.filterIsInstance<AssistantMessage>()
        assertEquals("assistant count", 2, assistants.size)
        assertEquals("committed tool calls", calls, assistants[0].toolCalls)
        // 结果回喂进历史（统一契约文本）
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("tool messages", 2, toolMsgs.size)
        assertContains("weather fed back", toolMsgs[0].content, "北京 今天晴，气温 26℃")
        assertContains("wordcount fed back", toolMsgs[1].content, "共 6 字符")
        assertEquals("not error", false, toolMsgs[0].isError)
        // 终文本返回
        assertEquals("final answer", "北京今天晴，气温 26℃；文本共 7 字符。", (history.last() as AssistantMessage).content)
        // buildSystemPrompt 仍拼接工具自带 systemPrompt
        assertContains("tool systemPrompt", (history[0] as SystemMessage).content, "weather 工具：先问清城市再调用。")
    }

    check("ToolStarted/ToolFinished 事件：各 2 次，且每次 Started 先于同 id 的 Finished") {
        val weather = WeatherTool()
        val counter = WordCountTool()
        val reg = ToolRegistry().register(weather).register(counter)
        val calls = listOf(
            ToolCall("call_w", "weather", argsOf("city" to "北京")),
            ToolCall("call_c", "wordcount", argsOf("text" to "abc"))
        )
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            onEvent = { events += it }
        )
        loop.send("x")

        val started = events.filterIsInstance<AgentEvent.ToolStarted>()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals("started count", 2, started.size)
        assertEquals("finished count", 2, finished.size)
        started.forEach { s ->
            val fi = finished.indexOfFirst { it.message.id == s.call.id }
            assertTrue("finished exists for ${s.call.id}", fi >= 0)
            assertTrue(
                "started(${events.indexOf(s)}) before finished(${events.indexOf(finished[fi])})",
                events.indexOf(s) < events.indexOf(finished[fi])
            )
        }
    }

    check("send 返回完整历史：取最后一条 AssistantMessage 即最终回答") {
        val weather = WeatherTool()
        val reg = ToolRegistry().register(weather)
        val calls = listOf(ToolCall("call_w", "weather", argsOf("city" to "北京")))
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("最终回答")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        loop.send("x")
        assertEquals(
            "runAgent",
            "最终回答",
            loop.send("再问一次").filterIsInstance<AssistantMessage>().lastOrNull()?.content.orEmpty()
        )
    }
}

/* ------------------------------ ①'：流式 ------------------------------ */

private suspend fun testT01Streaming() {
    println("\n== T01-①' 流式：TextDelta → onTextDelta，Completed 整条 commit ==")

    check("假 client streamChat 发 5 个 TextDelta 再 Completed：拼合文本 = 终答案") {
        val deltas = listOf("北京", "今天", "晴，", "气温", " 26℃")
        val full = deltas.joinToString("")
        val streaming = StreamingFakeLlm(
            listOf(
                listOf(
                    *deltas.map { ChatStreamEvent.TextDelta(it) }.toTypedArray(),
                    ChatStreamEvent.Completed(AssistantMessage(full))
                )
            )
        )
        val sink = StringBuilder()
        val loop = RealAgentLoop(
            llmProvider = { streaming },
            registry = ToolRegistry(),
            providerRetry = 0,
            onTextDelta = { sink.append(it) }
        )
        val history = loop.send("北京天气")
        assertEquals("delta concat", full, sink.toString())
        assertEquals("final answer", full, (history.last() as AssistantMessage).content)
        // TextDelta 不进历史：Assistant 只有 Completed 那一条
        assertEquals("single assistant in history", 1, history.filterIsInstance<AssistantMessage>().size)
    }

    check("流式下三段式仍成立：Completed 带 ToolCall → 工具执行 → 回喂 → 第二轮流式终答") {
        val weather = WeatherTool()
        val reg = ToolRegistry().register(weather)
        val streaming = StreamingFakeLlm(
            listOf(
                listOf(
                    ChatStreamEvent.TextDelta("让我先查天气"),
                    ChatStreamEvent.Completed(
                        AssistantMessage("让我先查天气", listOf(ToolCall("call_w", "weather", argsOf("city" to "北京"))))
                    )
                ),
                listOf(
                    ChatStreamEvent.TextDelta("北京"),
                    ChatStreamEvent.TextDelta("今天晴"),
                    ChatStreamEvent.Completed(AssistantMessage("北京今天晴"))
                )
            )
        )
        val sink = StringBuilder()
        val loop = RealAgentLoop(
            llmProvider = { streaming },
            registry = reg,
            providerRetry = 0,
            onTextDelta = { sink.append(it) }
        )
        val history = loop.send("北京天气")
        assertEquals("weather executed", 1, weather.executions)
        assertEquals("final answer", "北京今天晴", (history.last() as AssistantMessage).content)
        assertContains("deltas round1", sink.toString(), "让我先查天气")
        assertContains("deltas round2", sink.toString(), "北京今天晴")
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("fed back", 1, toolMsgs.size)
        assertContains("fed back content", toolMsgs[0].content, "北京 今天晴，气温 26℃")
        assertEquals("llm rounds", 2, streaming.rounds.get())
    }
}

/* ------------------------------ ②：并发保序 ------------------------------ */

private suspend fun testT01Ordering() {
    println("\n== T01-② 并发工具调用保序（maxParallel=3 分块；图片回流同序） ==")

    check("7 个只读调用一批（maxParallel=3）：结果顺序 = 调用顺序；图片回流顺序同样保序") {
        val reg = ToolRegistry()
        // 递减延时：若按完成序收集则会逆序——严格验证保序
        val delays = listOf(180L, 150L, 120L, 90L, 60L, 30L, 10L)
        delays.forEachIndexed { i, d ->
            reg.register(FakeTool("r$i", readOnly = true, delayMs = d, imageUrl = "data:image/jpeg;base64,IMG$i"))
        }
        val calls = delays.indices.map { ToolCall("c$it", "r$it", argsOf()) }
        val started = System.currentTimeMillis()
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 3,
            providerRetry = 0
        )
        val history = loop.send("x")
        val elapsed = System.currentTimeMillis() - started
        val ids = history.filterIsInstance<ToolMessage>().map { it.id }
        assertEquals("result order", calls.map { it.id }, ids)
        // 图片回流顺序 = 调用顺序（E1 父协程按序合并）
        val imgs = history.filterIsInstance<UserMessage>().lastOrNull { it.images.isNotEmpty() }?.images ?: emptyList()
        assertEquals("image order", (0 until 7).map { "data:image/jpeg;base64,IMG$it" }, imgs)
        // 并行证据：串行需 ≥640ms，并行分块理论 ≈300ms；留充分余量
        assertTrue("parallel (elapsed=${elapsed}ms < 500ms)", elapsed < 500)
    }

    check("含写整批串行：执行顺序与调用顺序一致（T03 判据不破）") {
        val order = mutableListOf<String>()
        val reg = ToolRegistry()
        reg.register(FakeTool("read", readOnly = true, onExecute = { order += "read" }))
        reg.register(FakeTool("write", readOnly = false, onExecute = { order += "write" }))
        val calls = listOf(
            ToolCall("c0", "read", argsOf("path" to "a.txt")),
            ToolCall("c1", "write", argsOf("path" to "b.txt")),
            ToolCall("c2", "read", argsOf("path" to "c.txt"))
        )
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, maxParallelTools = 5, providerRetry = 0)
        loop.send("x")
        assertEquals("serial order", listOf("read", "write", "read"), order)
    }
}

/* ------------------------------ ③：executor 违约 / ③'：业务异常 ------------------------------ */

private suspend fun testT01ExecutorBreach() {
    println("\n== T01-③ executor 违约回合级失败（不回喂 / 非取消 / 可捕获） ==")

    check("注入违约 executor：回合抛 ToolExecutionFailedException，该调用无 ToolMessage 回喂") {
        val reg = ToolRegistry().register(WeatherTool())
        val rogue = RogueExecutor()
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("call_w", "weather", argsOf("city" to "北京")))),
                AssistantMessage("done")
            )
        )
        var snapshot: List<ChatMessage> = emptyList()
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            executorFactory = { rogue },
            checkpoint = { snapshot = it.toList() }
        )
        val thrown = catchThrowable { loop.send("x") }
        assertTrue("is ToolExecutionFailedException (was ${thrown.javaClass.simpleName})", thrown is ToolExecutionFailedException)
        val failed = thrown as ToolExecutionFailedException
        assertEquals("toolName in exception", "weather", failed.toolName)
        assertTrue("cause is RuntimeException", failed.reason is RuntimeException)
        assertTrue("not CancellationException", thrown !is CancellationException)
        // 该调用不回喂模型（checkpoint 快照里无配对 ToolMessage）
        assertTrue("no feedback for the breached call", snapshot.filterIsInstance<ToolMessage>().none { it.id == "call_w" })
        // executor 真被调用（不是绕道未知工具路径）
        assertEquals("rogue invoked", 1, rogue.calls.get())
        // 违约消息含工具名与原因（业务 bug 显形）
        assertContains("message has tool name", failed.message, "weather")
        assertContains("message has cause", failed.message, "rogue NPE")
    }

    check("取消绝不是失败：executor 抛 CancellationException → 原样上抛 + 树尾闭合，不变 Failed") {
        val reg = ToolRegistry().register(WeatherTool())
        val fake = FakeLlm(
            listOf(AssistantMessage("", listOf(ToolCall("c0", "weather", argsOf("city" to "北京")))))
        )
        var snapshot: List<ChatMessage> = emptyList()
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            executorFactory = { CancelRogueExecutor() },
            checkpoint = { snapshot = it.toList() }
        )
        val thrown = catchThrowable { loop.send("x") }
        assertTrue("rethrown cancellation (was ${thrown.javaClass.simpleName})", thrown is CancellationException)
        assertTrue("not ToolExecutionFailedException", thrown !is ToolExecutionFailedException)
        assertTrue("closure filled", snapshot.filterIsInstance<ToolMessage>().any { it.id == "c0" })
        assertTrue("invariant", invariantHolds(snapshot))
    }
}

private suspend fun testT01BusinessFailure() {
    println("\n== T01-③' 工具业务异常/审批拒绝：仍回喂错误 ToolMessage（T02 语义不破） ==")

    check("工具 execute 抛 RuntimeException：错误 ToolMessage 回喂，回合正常完成") {
        val boom = FakeTool("boom", readOnly = true, onExecute = { throw RuntimeException("tool boom") })
        val reg = ToolRegistry().register(boom)
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "boom", argsOf()))),
                AssistantMessage("recovered")
            )
        )
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("exactly one tool message", 1, toolMsgs.size)
        assertEquals("isError", true, toolMsgs[0].isError)
        assertContains("exception code", toolMsgs[0].content, "code=exception")
        assertEquals("turn completes", "recovered", (history.last() as AssistantMessage).content)
    }

    check("审批拒绝：approval_denied 回喂，不真执行") {
        val tap = object : AgentTool {
            override val name = "ui_tap"
            override val description = ""
            override val parameters = JsonSchema.objectSchema()
            override val needsApproval = true
            var executions = 0
            override suspend fun execute(args: JsonObject): ToolResult {
                executions++
                return ToolResult.ok(message = "tapped")
            }
        }
        val reg = ToolRegistry().apply { register(tap) }
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "ui_tap", argsOf()))),
                AssistantMessage("ok")
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            // 本用例测的是**审批路径**（拒绝 → approval_denied 回喂、不真执行），
            // 与「哪些工具对模型可见」无关。W-102 起默认面只留 file 组，ui_tap 会被裁掉、
            // 走「未知工具」分支，审批根本不会被问到——那样测的就不是审批了。
            // 强制全量把裁剪这个变量隔离，用例才只测它该测的那件事。
            forceFullTools = true,
            consent = Consent.ask { _, _ -> false }
        )
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("exactly one tool message", 1, toolMsgs.size)
        assertContains("denied code", toolMsgs[0].content, "approval_denied")
        assertEquals("not executed", 0, tap.executions)
        assertEquals("turn completes", "ok", (history.last() as AssistantMessage).content)
    }
}

/* ------------------------------ ④：取消 / stop / shouldContinue 树尾闭合 ------------------------------ */

private suspend fun testT01CancelClosure() = runBlocking {
    println("\n== T01-④ stop 后树尾闭合（取消 / stop() / shouldContinue 三路径） ==")

    check("取消发生在工具执行中途：NonCancellable 补全后重抛 CancellationException") {
        var runJob: Job? = null
        val reg = ToolRegistry()
        reg.register(FakeTool("slow1", readOnly = true, delayMs = 5_000))
        reg.register(FakeTool("slow2", readOnly = true, delayMs = 5_000, onExecute = { runJob?.cancel() }))
        reg.register(FakeTool("slow3", readOnly = true, delayMs = 5_000))
        val calls = listOf(
            ToolCall("c0", "slow1", argsOf()),
            ToolCall("c1", "slow2", argsOf()),
            ToolCall("c2", "slow3", argsOf())
        )
        var lastSnapshot: List<ChatMessage> = emptyList()
        val fake = FakeLlm(listOf(AssistantMessage("", calls)))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 5,
            providerRetry = 0,
            checkpoint = { lastSnapshot = it.toList() }
        )
        var thrown: Throwable? = null
        val done = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch {
                try {
                    loop.send("x")
                } catch (t: Throwable) {
                    thrown = t
                } finally {
                    done.complete(Unit)
                }
            }
            runJob = job // 工具里 cancel 它 = 用户按「停止」
            done.await()
        }
        assertTrue("rethrown (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        val filled = lastSnapshot.filterIsInstance<ToolMessage>()
        assertEquals("all 3 filled", 3, filled.size)
        assertContains("cancelled code", filled[0].content, "code=cancelled")
        assertContains("cancelled hint", filled[0].content, "hint:")
    }

    check("写工具被 kill：onInterrupt 判 Unknown → code=cancelled_unknown（提醒先确认副作用）") {
        var runJob: Job? = null
        val reg = ToolRegistry()
        reg.register(FakeTool("writer", readOnly = false, delayMs = 5_000, onExecute = { runJob?.cancel() }))
        val calls = listOf(ToolCall("c0", "writer", argsOf()))
        var lastSnapshot: List<ChatMessage> = emptyList()
        val fake = FakeLlm(listOf(AssistantMessage("", calls)))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 5,
            providerRetry = 0,
            checkpoint = { lastSnapshot = it.toList() }
        )
        var thrown: Throwable? = null
        val done = CompletableDeferred<Unit>()
        runBlocking {
            val job = launch {
                try {
                    loop.send("x")
                } catch (t: Throwable) {
                    thrown = t
                } finally {
                    done.complete(Unit)
                }
            }
            runJob = job
            done.await()
        }
        assertTrue("rethrown (was ${thrown?.javaClass?.simpleName})", thrown is CancellationException)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        val filled = lastSnapshot.filterIsInstance<ToolMessage>()
        assertEquals("one filled", 1, filled.size)
        assertContains("unknown code", filled[0].content, "code=cancelled_unknown")
        assertContains("unknown hint", filled[0].content, "确认")
    }

    check("shouldContinue=false：优雅收尾补停止说明后返回") {
        val reg = ToolRegistry()
        reg.register(FakeTool("r", readOnly = true))
        val calls = listOf(ToolCall("c0", "r", argsOf()))
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("next")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            maxParallelTools = 5,
            providerRetry = 0,
            shouldContinue = { false }
        )
        val history = loop.send("x")
        assertContains("stop note", (history.last() as AssistantMessage).content, "已根据请求停止")
        assertTrue("invariant", invariantHolds(history))
    }

    check("shouldContinue 通道：工具执行中要求收尾 → 优雅停止，第二轮 LLM 不发生") {
        // R-19：原来是 loop.stop()。那条通道全仓零调用、已删除；这里改用生产可注入的
        // shouldContinue 钩子触发同一段收尾代码（`if (!shouldContinueNow())`），
        // 断言一条不少——测的是行为，不是某个方法名。
        val keepGoing = AtomicBoolean(true)
        val reg = ToolRegistry()
        reg.register(FakeTool("stopper", readOnly = true, onExecute = { keepGoing.set(false) }))
        val calls = listOf(ToolCall("c0", "stopper", argsOf()))
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("next")))
        val loop = RealAgentLoop(
            llmProvider = { fake }, registry = reg, maxParallelTools = 5, providerRetry = 0,
            shouldContinue = { keepGoing.get() }
        )
        val history = loop.send("x")
        assertContains("stop note", (history.last() as AssistantMessage).content, "已根据请求停止")
        assertEquals("second round never happens", 1, fake.rounds.get())
        assertTrue("invariant", invariantHolds(history))
    }
}

/* ------------------------------ T04：熔断不破 ------------------------------ */

private suspend fun testT01FailureGuard() {
    println("\n== T01-T04 熔断不破：同一失败调用第 3 次经新 loop 不再真执行 ==")

    check("恒失败工具打转 5 轮：真执行 2 次，第 3 次起熔断回喂") {
        val failTool = object : AgentTool {
            override val name = "flaky"
            override val description = ""
            override val parameters = JsonSchema.objectSchema()
            var executions = 0
            override suspend fun execute(args: JsonObject): ToolResult {
                executions++
                return ToolResult.fail("io_error", "always fail", "change params")
            }
        }
        val reg = ToolRegistry().apply { register(failTool) }
        val call = ToolCall("c0", "flaky", argsOf("path" to "same.txt"))
        val fake = FakeLlm(List(5) { AssistantMessage("", listOf(call)) })
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, maxSteps = 5, providerRetry = 0)
        val history = loop.send("x")
        assertEquals("real executions", 2, failTool.executions) // 第 3 次起熔断
        val blocked = history.filterIsInstance<ToolMessage>().count { it.content.contains("requires_changed_call") }
        assertTrue("blocked msgs (got $blocked)", blocked >= 1)
    }
}

/* ------------------------------ H2 回归：默认 maxSteps ------------------------------ */

private suspend fun testT01MaxStepsDefault() {
    println("\n== T01 H2 回归：适配层 AgentLoop 默认 maxSteps=40（hotfix5 漏改点）==")

    // W-46：默认步数已从 40 上调到 60（适配层与 RealAgentLoop 同步），本断言随之更新；
    // 它防的仍是「默认值被悄悄改回 16」这一类退化，口径以当前真源为准。
    check("不传 maxSteps：恒工具调用打满默认 60 步后收尾（防退化回 16）") {
        val tool = object : AgentTool {
            override val name = "noop"
            override val description = ""
            override val parameters = JsonSchema.objectSchema()
            override suspend fun execute(args: JsonObject): ToolResult =
                ToolResult.ok(message = "done")
        }
        val reg = ToolRegistry().apply { register(tool) }
        val call = ToolCall("c0", "noop", argsOf("path" to "same.txt"))
        val fake = FakeLlm(List(60) { AssistantMessage("", listOf(call)) })
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>().size
        assertEquals("default maxSteps tool executions", 60, toolMsgs)
    }

    check("显式传 maxSteps=5 仍以传入值为准（默认值不劫持显式传参）") {
        val tool = object : AgentTool {
            override val name = "noop"
            override val description = ""
            override val parameters = JsonSchema.objectSchema()
            override suspend fun execute(args: JsonObject): ToolResult =
                ToolResult.ok(message = "done")
        }
        val reg = ToolRegistry().apply { register(tool) }
        val call = ToolCall("c0", "noop", argsOf("path" to "same.txt"))
        val fake = FakeLlm(List(20) { AssistantMessage("", listOf(call)) })
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, maxSteps = 5, providerRetry = 0)
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>().size
        assertEquals("explicit maxSteps respected", 5, toolMsgs)
    }
}

/* ------------------------------ Hooks 四时机 ------------------------------ */

private suspend fun testT01Hooks() {
    println("\n== T01-Hooks 四时机命中计数 ==")

    check("两轮对话 + 一批 2 个工具：serialization=2 / toolCall=2 / afterToolCall=2 / beforeStop=1") {
        val hooks = RecordingHooks()
        val reg = ToolRegistry().register(WeatherTool()).register(WordCountTool())
        val calls = listOf(
            ToolCall("call_w", "weather", argsOf("city" to "北京")),
            ToolCall("call_c", "wordcount", argsOf("text" to "abc"))
        )
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0, hooks = hooks)
        loop.send("x")
        assertEquals("beforeSerialization", 2, hooks.beforeSerialization.get())
        assertEquals("beforeToolCall", 2, hooks.beforeToolCall.get())
        assertEquals("afterToolCall", 2, hooks.afterToolCall.get())
        assertEquals("beforeStop", 1, hooks.beforeStop.get())
        assertEquals("tool names", setOf("weather", "wordcount"), hooks.toolCallNames.toSet())
    }

    check("NoopHooks 默认可用（不传 hooks 不崩）") {
        val reg = ToolRegistry().register(WeatherTool())
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "weather", argsOf("city" to "北京")))),
                AssistantMessage("done")
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg,
            providerRetry = 0,
            hooks = NoopHooks
        )
        val history = loop.send("x")
        assertEquals("done", "done", (history.last() as AssistantMessage).content)
    }
}

/* ------------------------------ update() 生效 ------------------------------ */

private suspend fun testT01Update() {
    println("\n== T01-update() 运行时更新（null = 保持现值） ==")

    check("update(systemPrompt)：下一轮 SystemMessage 体现新提示") {
        val fake = FakeLlm(listOf(AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = ToolRegistry(), providerRetry = 0)
        loop.update(systemPrompt = "CUSTOM-SYSTEM-PROMPT-12345")
        val history = loop.send("hi")
        assertContains("system prompt", (history[0] as SystemMessage).content, "CUSTOM-SYSTEM-PROMPT-12345")
    }

    check("update(memoryProvider)：系统提示追加记忆摘要与使用引导") {
        val fake = FakeLlm(listOf(AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = ToolRegistry(), providerRetry = 0)
        loop.update(memoryProvider = { "MEMORY-SUMMARY-XYZ" })
        val history = loop.send("hi")
        assertContains("memory summary", (history[0] as SystemMessage).content, "MEMORY-SUMMARY-XYZ")
        assertContains("memory usage prompt", (history[0] as SystemMessage).content, "memory_write")
    }

    check("未启用记忆：系统提示不拼记忆引导（不白占上下文）") {
        val fake = FakeLlm(listOf(AssistantMessage("done")))
        val loop = RealAgentLoop(llmProvider = { fake }, registry = ToolRegistry(), providerRetry = 0)
        val history = loop.send("hi")
        val p = (history[0] as SystemMessage).content
        assertTrue("无 memory_usage 段", !p.contains("<memory_usage>"))
        assertTrue("无 memory_write 引导", !p.contains("memory_write"))
    }

    check("同一会话连续两轮：系统提示逐字节一致（KV 前缀可复用）") {
        val fake = FakeLlm(listOf(AssistantMessage("一"), AssistantMessage("二")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = ToolRegistry().register(WeatherTool()),
            providerRetry = 0
        )
        val p1 = (loop.send("第一轮")[0] as SystemMessage).content
        val p2 = (loop.send("第二轮")[0] as SystemMessage).content
        assertTrue("两轮提示逐字节一致（长度 ${p1.length} vs ${p2.length}）", p1 == p2)
        assertTrue("提示非空", p1.isNotBlank())
    }

    check("update(maxSteps=1)：下一轮即按新上限收尾") {
        val reg = ToolRegistry().register(WeatherTool())
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "weather", argsOf("city" to "北京")))),
                AssistantMessage("done")
            )
        )
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, maxSteps = 16, providerRetry = 0)
        loop.update(maxSteps = 1)
        val history = loop.send("x")
        // 文案随产品调整（「已用满 N 步」比「已达到最大步数」更贴近用户理解），断言同步
        assertContains("max steps warning", (history.last() as AssistantMessage).content, "本次任务已用满 1 步")
    }

    check("update(registry)：新表对执行 / 系统提示拼接即时生效") {
        val emptyReg = ToolRegistry()
        val weatherReg = ToolRegistry().register(WeatherTool())
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "weather", argsOf("city" to "北京")))),
                AssistantMessage("done")
            )
        )
        val loop = RealAgentLoop(llmProvider = { fake }, registry = emptyReg, providerRetry = 0)
        loop.update(registry = weatherReg)
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("one tool message", 1, toolMsgs.size)
        assertEquals("not unknown tool", false, toolMsgs[0].isError)
        assertContains("new registry systemPrompt", (history[0] as SystemMessage).content, "weather 工具：先问清城市再调用。")
    }
}

/* ------------------------------ descriptor 映射 ------------------------------ */

private suspend fun testT01Descriptor() {
    println("\n== T01-descriptor 映射字段正确性 ==")

    check("descriptors()/descriptorOf()：name/wireName/inputSchemaJson 字段正确且为合法 JSON") {
        val reg = ToolRegistry().register(WeatherTool()).register(WordCountTool())
        val descriptors = reg.descriptors()
        assertEquals("size", 2, descriptors.size)
        assertEquals("names match", reg.names().toSet(), descriptors.map { it.name }.toSet())
        val w = descriptors.first { it.name == "weather" }
        assertEquals("wireName default == name", "weather", w.wireName)
        assertTrue("schema non-blank", w.inputSchemaJson.isNotBlank())
        val schemaObj = Json.parseToJsonElement(w.inputSchemaJson).jsonObject
        assertEquals("type", "object", schemaObj.prim("type"))
        val propsObj = schemaObj["properties"] as? JsonObject
        assertTrue("has properties", propsObj != null)
        assertTrue("city prop exists", propsObj?.containsKey("city") == true)
        val required = schemaObj.arr("required")
        assertTrue("required contains city", required?.any { (it as? JsonPrimitive)?.content == "city" } == true)
        assertEquals("descriptorOf hit", "weather", reg.descriptorOf("weather")?.name)
        assertEquals("descriptorOf miss", null, reg.descriptorOf("ghost"))
        // 纯函数：两次调用结果相等
        assertEquals("pure mapping", reg.descriptors(), reg.descriptors())
    }
}

/* ------------------------------ OpenAIClient SSE 工具调用累积（纯函数级） ------------------------------ */

private fun chunkToolCall(index: Int, id: String?, name: String?, args: String?): JsonObject = buildJsonObject {
    put("index", index)
    id?.let { put("id", it) }
    putJsonObject("function") {
        name?.let { put("name", it) }
        args?.let { put("arguments", it) }
    }
}

private suspend fun testT01OpenAiSse() {
    println("\n== T01-OpenAIClient SSE tool_calls 累积（纯函数级，不发网络） ==")

    check("两个调用按 index 归组：id/name 首片、arguments 多片拼接，顺序保持") {
        val acc = OpenAiToolCallAccumulator()
        // 调用 0：id+name 首片，arguments 分 3 片
        acc.absorb(buildJsonArray { add(chunkToolCall(0, "call_1", "get_weather", "{\"ci")) })
        acc.absorb(buildJsonArray { add(chunkToolCall(0, null, null, "ty\":\"北")) })
        acc.absorb(buildJsonArray { add(chunkToolCall(0, null, null, "京\"}")) })
        // 同帧到达两个调用的分片（空 arguments 片无害）
        acc.absorb(
            buildJsonArray {
                add(chunkToolCall(0, null, null, ""))
                add(chunkToolCall(1, "call_2", "wordcount", "{\"text\":\"abc\"}"))
            }
        )
        val calls = acc.build()
        assertEquals("count", 2, calls.size)
        assertEquals("id0", "call_1", calls[0].id)
        assertEquals("name0", "get_weather", calls[0].name)
        assertEquals("arg0 city", "北京", (calls[0].arguments["city"] as? JsonPrimitive)?.content)
        assertEquals("id1", "call_2", calls[1].id)
        assertEquals("name1", "wordcount", calls[1].name)
        assertEquals("arg1 text", "abc", (calls[1].arguments["text"] as? JsonPrimitive)?.content)
    }

    check("无名调用被丢弃；id 缺失按 index 回退 call_<index>；参数截断退化空对象") {
        val acc = OpenAiToolCallAccumulator()
        acc.absorb(buildJsonArray { add(chunkToolCall(0, "call_x", null, "{\"a\":1}")) })
        assertEquals("nameless dropped", 0, acc.build().size)

        val acc2 = OpenAiToolCallAccumulator()
        acc2.absorb(buildJsonArray { add(chunkToolCall(3, null, "t", "{}")) })
        assertEquals("fallback id", "call_3", acc2.build()[0].id)

        val acc3 = OpenAiToolCallAccumulator()
        acc3.absorb(buildJsonArray { add(chunkToolCall(0, "call_y", "t", "{\"a\":")) }) // 截断的 JSON
        val c3 = acc3.build()
        assertEquals("count", 1, c3.size)
        assertEquals("broken args degrade", 0, c3[0].arguments.size)
    }
}

/* ------------------------------ onInterrupt 分类 ------------------------------ */

private suspend fun testT01OnInterrupt() {
    println("\n== T01-onInterrupt 自判：只读 Interrupted / 写 Unknown / 未知按只读 ==")

    check("RegistryToolExecutor.onInterrupt 按 readOnly 分类") {
        val reg = ToolRegistry().register(WeatherTool()).register(FakeTool("writer", readOnly = false))
        val executor = RegistryToolExecutor(reg, FailureGuard())
        val wVerdict = executor.onInterrupt(ToolCall("1", "weather", argsOf()), reg.descriptorOf("weather")!!)
        assertTrue("readonly -> Interrupted", wVerdict == ToolCallOutcome.Interrupted)
        val writeVerdict = executor.onInterrupt(ToolCall("2", "writer", argsOf()), reg.descriptorOf("writer")!!)
        assertTrue("write -> Unknown", writeVerdict == ToolCallOutcome.Unknown)
        val ghostVerdict = executor.onInterrupt(
            ToolCall("3", "ghost", argsOf()),
            ToolDescriptor("ghost", "ghost", "{}")
        )
        assertTrue("unknown -> Interrupted", ghostVerdict == ToolCallOutcome.Interrupted)
    }
}

/* ------------------------------ 未知工具文案（T02 不破） ------------------------------ */

private suspend fun testT01UnknownTool() {
    println("\n== T01-未知工具走新 loop：模糊匹配文案回喂，回合继续 ==")

    check("未知工具（编辑距离 1）：unknown_tool + 建议名回喂，终答正常返回") {
        val reg = ToolRegistry().register(WeatherTool())
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", listOf(ToolCall("c0", "weatherr", argsOf("city" to "北京")))),
                AssistantMessage("done")
            )
        )
        val loop = RealAgentLoop(llmProvider = { fake }, registry = reg, providerRetry = 0)
        val history = loop.send("x")
        val toolMsgs = history.filterIsInstance<ToolMessage>()
        assertEquals("one tool message", 1, toolMsgs.size)
        assertEquals("isError", true, toolMsgs[0].isError)
        assertContains("unknown text", toolMsgs[0].content, "未知工具 'weatherr'")
        assertContains("suggestion", toolMsgs[0].content, "你是否想调用 'weather'？")
        assertEquals("turn continues", "done", (history.last() as AssistantMessage).content)
    }
}

/* ------------------------------ Round1 修复：收尾决定的回合作用域 ------------------------------ */

private suspend fun testRound1StopScope() {
    println("\n== 收尾决定回合作用域：第 1 回合收尾不影响第 2 回合 ==")

    check("第 1 回合工具执行中要求收尾：优雅停止；同一 loop 第 2 回合完整运行（判定每次现算）") {
        // R-19：stop() 已删，用 shouldContinue 表达同一场景。第 1 回合把它置 false，
        // 第 2 回合置回 true——验证「收尾决定只在当轮生效」，不会像布尔标志那样跨轮残留。
        val keepGoing = AtomicBoolean(true)
        // 只有第 1 回合那次执行要求收尾。若每次执行都置 false，第 2 回合工具一跑就被收尾，
        // 这条断言永远测不到「判定每次现算」——看似变红，其实是夹具自身把场景写死了。
        val stopOnce = AtomicBoolean(true)
        val stopper = FakeTool("stopper", readOnly = true, onExecute = {
            if (stopOnce.compareAndSet(true, false)) keepGoing.set(false)
        })
        val reg = ToolRegistry().register(stopper)
        val calls = listOf(ToolCall("c0", "stopper", argsOf()))
        val fake = FakeLlm(
            listOf(
                AssistantMessage("", calls),           // 第 1 回合 round1：工具（执行中要求收尾）
                AssistantMessage("", calls),           // 第 2 回合 round1：工具（钩子已置回 true）
                AssistantMessage("second-turn-final")  // 第 2 回合 round2：终答
            )
        )
        val loop = RealAgentLoop(
            llmProvider = { fake }, registry = reg, maxParallelTools = 5, providerRetry = 0,
            shouldContinue = { keepGoing.get() }
        )
        // 第 1 回合：工具执行中置 false → 优雅收尾，第二轮 LLM 不发生
        val h1 = loop.send("x")
        assertContains("turn1 stop note", (h1.last() as AssistantMessage).content, "已根据请求停止")
        assertEquals("turn1 rounds", 1, fake.rounds.get())
        assertEquals("turn1 executions", 1, stopper.executions)
        assertTrue("turn1 invariant", invariantHolds(h1))
        // 第 2 回合：钩子置回 true，同一 loop 再发——必须完整跑完（没有残留的停止意图）
        keepGoing.set(true)
        val h2 = loop.send("again")
        assertEquals("turn2 rounds", 3, fake.rounds.get())
        assertEquals("turn2 executions", 2, stopper.executions)
        assertEquals("turn2 final", "second-turn-final", (h2.last() as AssistantMessage).content)
        assertTrue("turn2 no stop note", !(h2.last() as AssistantMessage).content.contains("已根据请求停止"))
        assertTrue("turn2 invariant", invariantHolds(h2))
    }

}

/* ------------------------------ Round1 修复：SSE 网络段 IO 调度 ------------------------------ */

/** 计数型 Fake LLM：记录 chat() 调用次数；streamChat 吃接口默认实现（Round1 默认路径断言用）。 */
private class CountingLlm(private val reply: AssistantMessage) : LLMClient {
    override val id: String = "counting"
    val chatCalls = AtomicInteger(0)

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        chatCalls.incrementAndGet()
        return reply
    }

    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/**
 * 只转发 chat()、不覆盖 streamChat 的客户端——接口默认实现（chat() + 单条 Completed）
 * 的被测对象。OpenAIClient 自身覆盖了 streamChat，测默认路径必须绕开它。
 */
private class ChatOnlyClient(private val delegate: OpenAIClient) : LLMClient {
    override val id: String = "chat-only"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = delegate.chat(messages, tools)

    override suspend fun testPing(): PingResult = delegate.testPing()
}

/** 记录 OkHttp 调用发生在哪个线程（应用层拦截器同步跑在 execute() 的调用线程上）。 */
private class ThreadRecorderInterceptor(private val sink: MutableList<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        sink += Thread.currentThread().name
        return chain.proceed(chain.request())
    }
}

/** SSE 剧本：两个文本增量 + 一个跨两片累积的 tool_call + [DONE] 哨兵（原始串，反斜杠原样上链路）。 */
private fun ssePayload(): String = """
    data: {"choices":[{"delta":{"content":"你"}}]}

    data: {"choices":[{"delta":{"content":"好"}}]}

    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}

    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"北京\"}"}}]}}]}

    data: [DONE]

""".trimIndent()

/** 非流式整条 JSON（含 content + tool_calls）——默认 streamChat 走 chat() 的被测响应。 */
private fun chatPayload(): String = """
    {"choices":[{"message":{"role":"assistant","content":"整条回答","tool_calls":[{"id":"call_9","type":"function","function":{"name":"wordcount","arguments":"{\"text\":\"abc\"}"}}]}}]}
""".trimIndent()

/**
 * 起一个本地双模端点（JDK 内置 HttpServer，零新增依赖）：
 * 请求体含 `"stream":true` → 按 [sseScript] 次序返回 SSE 剧本（末条重复）；
 * 否则返回非流式整条 JSON（chatPayload）。
 *
 * @return Pair(baseUrl, server)（用完后必须 server.stop(0)）。
 */
private fun startFakeLlmServer(vararg sseScript: String): Pair<String, HttpServer> {
    val script = sseScript.toList().ifEmpty { listOf(ssePayload()) }
    val idx = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/chat/completions") { ex ->
        val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
        val streaming = body.contains("\"stream\":true")
        val payload = if (streaming) script[minOf(idx.getAndIncrement(), script.size - 1)] else chatPayload()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { out -> out.write(bytes) }
    }
    server.start()
    return "http://127.0.0.1:${server.address.port}/v1" to server
}

/**
 * 构造一条 SSE 剧本：逐字符文本增量 + 可选 tool_call（两片累积：首片 id+name+"{\"ci"，
 * 次片 "ty\":\"<city>\"}"）+ [DONE] 哨兵。原始串保证反斜杠原样上链路（JSON 转义）。
 */
private fun sseScript(text: String, tool: Pair<String, String>?): String = buildString {
    text.forEach { ch ->
        append("""data: {"choices":[{"delta":{"content":"$ch"}}]}""").append("\n\n")
    }
    if (tool != null) {
        val (name, city) = tool
        append(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"$name","arguments":"{\"ci"}}]}}]}"""
        ).append("\n\n")
        append(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"$city\"}"}}]}}]}"""
        ).append("\n\n")
    }
    append("data: [DONE]").append("\n\n")
}

private suspend fun testRound1SseIoDispatch() {
    println("\n== Round1-SSE 网络段强制 IO 调度（结构断言）+ 默认 streamChat 路径不变 ==")

    check("默认 streamChat 实现路径不受 IO 修复影响：chat() 恰一次 + [TextDelta, Completed]（content 非空）") {
        val reply = AssistantMessage("hello world")
        val llm = CountingLlm(reply)
        val events = llm.streamChat(emptyList(), emptyList()).toList()
        assertEquals("event count", 2, events.size)
        assertTrue("first is TextDelta", events[0] is ChatStreamEvent.TextDelta)
        assertEquals("delta text", "hello world", (events[0] as ChatStreamEvent.TextDelta).text)
        assertTrue("second is Completed", events[1] is ChatStreamEvent.Completed)
        assertEquals("completed message", reply, (events[1] as ChatStreamEvent.Completed).message)
        assertEquals("chat called exactly once", 1, llm.chatCalls.get())
    }

    check("默认 streamChat（纯 ToolCall 空 content）：只发 Completed，chat() 恰一次") {
        val reply = AssistantMessage("", listOf(ToolCall("c0", "t", argsOf())))
        val llm = CountingLlm(reply)
        val events = llm.streamChat(emptyList(), emptyList()).toList()
        assertEquals("event count", 1, events.size)
        assertTrue("only Completed", events[0] is ChatStreamEvent.Completed)
        assertEquals("completed message", reply, (events[0] as ChatStreamEvent.Completed).message)
        assertEquals("chat called exactly once", 1, llm.chatCalls.get())
    }

    check("真 SSE streamChat：网络段跑在 Dispatchers.IO 线程；收集方留在 FakeMain；delta 拼合 + ToolCall 累积正确") {
        val (baseUrl, server) = startFakeLlmServer()
        try {
            val ioThreads = Collections.synchronizedList(mutableListOf<String>())
            val client = OpenAIClient(
                config = LLMConfig(baseUrl = baseUrl, apiKey = "test-key", model = "test-model"),
                http = OkHttpClient.Builder().addInterceptor(ThreadRecorderInterceptor(ioThreads)).build()
            )
            val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "FakeMain") }.asCoroutineDispatcher()
            try {
                val collectThreads = Collections.synchronizedList(mutableListOf<String>())
                val deltas = StringBuilder()
                var completed: AssistantMessage? = null
                // 模拟 Android 场景：收集方在受限单线程（FakeMain）——未修复时网络段会在这里跑
                withContext(mainDispatcher) {
                    client.streamChat(emptyList(), emptyList()).collect { event ->
                        collectThreads += Thread.currentThread().name
                        when (event) {
                            is ChatStreamEvent.TextDelta -> deltas.append(event.text)
                            // hotfix26（P1-9）：推理过程增量——本用例只关心正文，思考事件显式忽略
                            is ChatStreamEvent.ReasoningDelta -> Unit
                            is ChatStreamEvent.Completed -> completed = event.message
                        }
                    }
                }
                // SSE 解析与累积行为不破
                assertEquals("deltas", "你好", deltas.toString())
                assertEquals("completed content", "你好", completed?.content)
                assertEquals("tool call count", 1, completed?.toolCalls?.size)
                assertEquals("tool call name", "get_weather", completed?.toolCalls?.get(0)?.name)
                assertEquals("tool call arg", "北京", (completed?.toolCalls?.get(0)?.arguments?.get("city") as? JsonPrimitive)?.content)
                // Blocker 修复证据：网络段（execute/read 经拦截器观测）发生在 IO 调度器线程
                assertTrue(
                    "http on IO dispatcher (was ${ioThreads.firstOrNull()})",
                    ioThreads.isNotEmpty() && ioThreads.all { it.matches(Regex("DefaultDispatcher-worker-\\d+")) }
                )
                // emit 从 IO 线程发出，但收集仍发生在收集方上下文（FakeMain）——上下文不泄漏
                assertTrue(
                    "collected on FakeMain (was ${collectThreads.firstOrNull()})",
                    collectThreads.isNotEmpty() && collectThreads.all { it == "FakeMain" }
                )
            } finally {
                mainDispatcher.close()
            }
        } finally {
            server.stop(0)
        }
    }

    check("真 SSE streamText：网络段同样跑在 Dispatchers.IO（一处修两通道）；增量文本逐条下发") {
        val (baseUrl, server) = startFakeLlmServer()
        try {
            val ioThreads = Collections.synchronizedList(mutableListOf<String>())
            val client = OpenAIClient(
                config = LLMConfig(baseUrl = baseUrl, apiKey = "test-key", model = "test-model"),
                http = OkHttpClient.Builder().addInterceptor(ThreadRecorderInterceptor(ioThreads)).build()
            )
            val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "FakeMain") }.asCoroutineDispatcher()
            try {
                val collectThreads = Collections.synchronizedList(mutableListOf<String>())
                val chunks = mutableListOf<String>()
                withContext(mainDispatcher) {
                    client.streamText(emptyList(), emptyList()).collect { chunk ->
                        collectThreads += Thread.currentThread().name
                        chunks += chunk
                    }
                }
                assertEquals("chunks", listOf("你", "好"), chunks)
                assertTrue(
                    "http on IO dispatcher (was ${ioThreads.firstOrNull()})",
                    ioThreads.isNotEmpty() && ioThreads.all { it.matches(Regex("DefaultDispatcher-worker-\\d+")) }
                )
                assertTrue(
                    "collected on FakeMain (was ${collectThreads.firstOrNull()})",
                    collectThreads.isNotEmpty() && collectThreads.all { it == "FakeMain" }
                )
            } finally {
                mainDispatcher.close()
            }
        } finally {
            server.stop(0)
        }
    }

    check("默认 streamChat 走 chat()（真客户端非流式 JSON）：[TextDelta, Completed] 行为不变；Main 收集无 flow 不变量冲突，HTTP 经 chat() 既有 IO 包裹") {
        val (baseUrl, server) = startFakeLlmServer()
        try {
            val ioThreads = Collections.synchronizedList(mutableListOf<String>())
            val delegate = OpenAIClient(
                config = LLMConfig(baseUrl = baseUrl, apiKey = "test-key", model = "test-model"),
                http = OkHttpClient.Builder().addInterceptor(ThreadRecorderInterceptor(ioThreads)).build()
            )
            val client = ChatOnlyClient(delegate)
            val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "FakeMain") }.asCoroutineDispatcher()
            try {
                val collectThreads = Collections.synchronizedList(mutableListOf<String>())
                var events: List<ChatStreamEvent> = emptyList()
                // 默认路径（chat() 内部 withContext(IO)）：emit 发生在收集方上下文，
                // 无 flow 不变量冲突——从 FakeMain 收集同样安全
                withContext(mainDispatcher) {
                    events = client.streamChat(emptyList(), emptyList()).toList().also {
                        collectThreads += Thread.currentThread().name
                    }
                }
                assertEquals("event count", 2, events.size)
                assertTrue("first is TextDelta", events[0] is ChatStreamEvent.TextDelta)
                assertEquals("delta text", "整条回答", (events[0] as ChatStreamEvent.TextDelta).text)
                assertTrue("second is Completed", events[1] is ChatStreamEvent.Completed)
                val msg = (events[1] as ChatStreamEvent.Completed).message
                assertEquals("content", "整条回答", msg.content)
                assertEquals("tool call count", 1, msg.toolCalls.size)
                assertEquals("tool call name", "wordcount", msg.toolCalls[0].name)
                assertEquals("tool call arg", "abc", (msg.toolCalls[0].arguments["text"] as? JsonPrimitive)?.content)
                assertTrue(
                    "http on IO dispatcher (was ${ioThreads.firstOrNull()})",
                    ioThreads.isNotEmpty() && ioThreads.all { it.matches(Regex("DefaultDispatcher-worker-\\d+")) }
                )
                assertTrue(
                    "collected on FakeMain (was ${collectThreads.firstOrNull()})",
                    collectThreads.isNotEmpty() && collectThreads.all { it == "FakeMain" }
                )
            } finally {
                mainDispatcher.close()
            }
        } finally {
            server.stop(0)
        }
    }

    check("端到端热路径复现：RealAgentLoop + 真 OpenAIClient SSE（FakeMain 收集）——网络段 IO、打字机增量、ToolCall 回喂、终答整条") {
        // QA Blocker 的报告路径：ChatViewModel(viewModelScope/Main) -> loop -> OpenAI 兼容 SSE。
        // JVM 上无法复现 NetworkOnMainThreadException，但可结构复现整条链路 + 调度断言。
        val (baseUrl, server) = startFakeLlmServer(
            sseScript("让我先查", "get_weather" to "北京"), // round1：文本增量 + tool_call（两片累积）
            sseScript("北京今天晴", null)                    // round2：纯文本终答
        )
        try {
            val ioThreads = Collections.synchronizedList(mutableListOf<String>())
            val client = OpenAIClient(
                config = LLMConfig(baseUrl = baseUrl, apiKey = "test-key", model = "test-model"),
                http = OkHttpClient.Builder().addInterceptor(ThreadRecorderInterceptor(ioThreads)).build()
            )
            val weather = FakeTool("get_weather", readOnly = true)
            val reg = ToolRegistry().register(weather)
            val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "FakeMain") }.asCoroutineDispatcher()
            try {
                val deltaThreads = Collections.synchronizedList(mutableListOf<String>())
                val deltas = StringBuilder()
                val loop = RealAgentLoop(
                    llmProvider = { client },
                    registry = reg,
                    providerRetry = 0,
                    onTextDelta = { text ->
                        deltas.append(text)
                        deltaThreads += Thread.currentThread().name
                    }
                )
                val history = withContext(mainDispatcher) { loop.send("北京天气怎么样？") }
                // 打字机增量 = 两轮文本拼合；回调留在收集方上下文（FakeMain）
                assertEquals("deltas", "让我先查北京今天晴", deltas.toString())
                assertTrue(
                    "delta callbacks on FakeMain (was ${deltaThreads.firstOrNull()})",
                    deltaThreads.isNotEmpty() && deltaThreads.all { it == "FakeMain" }
                )
                // 三段式：ToolCall 整条 commit -> 工具真执行 -> 结果回喂
                assertEquals("weather executed", 1, weather.executions)
                val toolMsgs = history.filterIsInstance<ToolMessage>()
                assertEquals("fed back", 1, toolMsgs.size)
                assertEquals("not error", false, toolMsgs[0].isError)
                // 终答 = round2 的 Completed 整条
                assertEquals("final", "北京今天晴", (history.last() as AssistantMessage).content)
                // 两轮 SSE 的网络段都跑在 IO 调度器线程
                assertEquals("http call count", 2, ioThreads.size)
                assertTrue(
                    "all http on IO dispatcher (was ${ioThreads.firstOrNull()})",
                    ioThreads.all { it.matches(Regex("DefaultDispatcher-worker-\\d+")) }
                )
                assertTrue("invariant", invariantHolds(history))
            } finally {
                mainDispatcher.close()
            }
        } finally {
            server.stop(0)
        }
    }
}

/* ------------------------------ main ------------------------------ */

fun main() = runBlocking {
    println("=== Phase1 T01（Agent 运行时内核抽象）QA 行为验证 ===")
    testT01FullFlow()
    testT01Streaming()
    testT01Ordering()
    testT01ExecutorBreach()
    testT01BusinessFailure()
    testT01CancelClosure()
    testT01FailureGuard()
    testT01MaxStepsDefault()
    testT01Hooks()
    testT01Update()
    testT01Descriptor()
    testT01OpenAiSse()
    testT01OnInterrupt()
    testT01UnknownTool()
    testRound1StopScope()
    testRound1SseIoDispatch()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
