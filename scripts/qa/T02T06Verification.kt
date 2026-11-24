#!/usr/bin/env kotlin
/**
 * Phase 1 第二批（T02+T03+T04+T05+T06）QA 行为验证 harness。
 *
 * 背景：仓库无任何测试源集（roadmap 验收要求单测），且本机离线 Gradle 缓存中
 * 没有 JUnit jar，无法在本地跑标准单测。本 harness 用纯 main() + 手写断言，
 * 直接对 assembleDebug 编译产物里的 core 契约类做行为验证——与审查要点逐条对应。
 *
 * 运行方式见 scripts/qa/run-verification.sh（kotlin-compiler-embeddable 编译 + java 运行）。
 *
 * W-8（真机日志诊断批次）追加 [testW8GuardTurnScope]：熔断器回合作用域重置 +
 * 瞬时故障豁免——修「service_not_ready 类瞬时失败把恒定指纹（无参 ui_tree）毒化后
 * 跨回合永久挡死」的根因（真机日志 23:58：服务已就绪，ui_tree 仍被熔断拦截）。
 */

import com.beichen.polaris.core.*
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.agent.dispatchLine
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    if (haystack?.contains(needle) != true) throw AssertionError("expected to contain <$needle> in <${haystack?.take(120)}>")
}

/* ------------------------------ 测试替身 ------------------------------ */

/** 脚本化 Fake LLM：按调用次序返回预设 AssistantMessage。 */
private class FakeLlm(private val script: List<AssistantMessage>) : LLMClient {
    override val id: String = "fake"
    private var i = 0
    override suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage {
        val r = script[minOf(i, script.size - 1)]
        i++
        return r
    }
    override suspend fun testPing(): PingResult = PingResult(200, "")
}

/** 通用假工具：readOnly 可配、可配延时、可配异常/取消行为。 */
private class FakeTool(
    override val name: String,
    override val readOnly: Boolean,
    private val delayMs: Long = 0,
    private val onExecute: (suspend () -> Unit)? = null
) : AgentTool {
    override val description: String = "fake $name"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    var executions = 0
    override suspend fun execute(args: JsonObject): ToolResult {
        executions++
        onExecute?.invoke()
        if (delayMs > 0) delay(delayMs)
        return ToolResult.ok(code = "fake_ok", message = "$name done", data = "payload-$name")
    }
}

private fun argsOf(vararg kv: Pair<String, String>): JsonObject = buildJsonObject {
    kv.forEach { (k, v) -> put(k, v) }
}

/* ------------------------------ T02：统一契约 ------------------------------ */

private suspend fun testT02Contract() {
    println("\n== T02 工具结果统一契约 ==")

    check("formatToolResult 纯函数幂等（同 result 两次调用完全相等）") {
        val r = ToolResult.ok(code = "read_ok", message = "已读取 a.txt", data = "内容")
        assertEquals("idempotent", formatToolResult(r), formatToolResult(r))
    }
    check("formatToolResult 输出形态：ok 无 hint 行，字段齐全") {
        val r = ToolResult.ok(code = "read_ok", message = "已读取 a.txt", data = "内容")
        assertEquals(
            "shape",
            "[ok] code=read_ok\nmessage: 已读取 a.txt\ndata: 内容",
            formatToolResult(r)
        )
    }
    check("formatToolResult 失败形态：[error] + hint 行") {
        val r = ToolResult.fail("missing_param", "缺少 url", "请提供 url")
        val s = formatToolResult(r)
        assertContains("error tag", s, "[error] code=missing_param")
        assertContains("hint line", s, "hint: 请提供 url")
    }
    check("fail() 工厂：空白 hint 被 require 拦截（失败必带 hint）") {
        var threw = false
        try {
            ToolResult.fail("x", "m", "   ")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("require hint", threw)
    }
    check("ok() 工厂：hint 恒 null，isError=false") {
        val r = ToolResult.ok(message = "m")
        assertEquals("hint", null, r.hint)
        assertEquals("isError", false, r.isError)
    }
    check("未知工具消息：含编辑距离建议 + 可用工具列表（≤20）") {
        val reg = ToolRegistry()
        repeat(25) { reg.register(FakeTool("tool_%02d".format(it), readOnly = true)) }
        reg.register(FakeTool("read_file", readOnly = true))
        val msg = reg.unknownToolMessage("read_fil") // 距离 1
        assertContains("suggestion", msg, "read_file")
        assertContains("unknown", msg, "未知工具 'read_fil'")
        val listed = msg.substringAfter("当前可用工具（前 20 个）：").substringBefore("。请从")
        assertEquals("list bound", 20, listed.split(", ").size)
    }
    check("suggestToolName 有界：距离 ≤2 才建议，乱名不瞎建议") {
        val reg = ToolRegistry()
        reg.register(FakeTool("read_file", readOnly = true))
        reg.register(FakeTool("write_file", readOnly = false))
        assertEquals("near hit", "read_file", reg.suggestToolName("read_fil"))
        assertEquals("far miss", null, reg.suggestToolName("zzqqxxwwvv"))
        assertEquals("blank", null, reg.suggestToolName("  "))
    }
    check("editDistanceWithin 边界：已知距离值正确判定") {
        assertTrue("kitten/sitting d=3", editDistanceWithin("kitten", "sitting", 3))
        assertTrue("kitten/sitting d=2 false", !editDistanceWithin("kitten", "sitting", 2))
        assertTrue("abc/abd d=1", editDistanceWithin("abc", "abd", 1))
        assertTrue("abc/abd d=0 false", !editDistanceWithin("abc", "abd", 0))
        assertTrue("self d=0", editDistanceWithin("abc", "abc", 0))
        assertTrue("empty vs 3", editDistanceWithin("", "abc", 3))
        assertTrue("empty vs 2 false", !editDistanceWithin("", "abc", 2))
        assertTrue("len diff > max false", !editDistanceWithin("abcdef", "abc", 2))
    }
    check("ToolRegistry.execute（Skill 桥路径）未知工具也走统一契约") {
        val reg = ToolRegistry()
        val m = reg.execute(ToolCall("id1", "nope", argsOf()))
        assertEquals("isError", true, m.isError)
        assertContains("unknown text", m.content, "未知工具 'nope'")
        assertContains("hint", m.content, "hint:")
    }
}

/* ------------------------------ T06：截断 ------------------------------ */

private suspend fun testT06Truncation() {
    println("\n== T06 工具结果体积上限 + 截断 ==")

    check("恰好 24000 字符不截断") {
        val text = "a".repeat(MAX_TOOL_RESULT_CHARS)
        assertEquals("exact", text, truncateToolResult(text))
    }
    check("24001 字符截断后恒 ≤24000 且带体量提示") {
        val text = "a".repeat(MAX_TOOL_RESULT_CHARS + 1)
        val out = truncateToolResult(text)
        assertTrue("bound", out.length <= MAX_TOOL_RESULT_CHARS)
        assertContains("suffix", out, "共 24001 字符")
        assertTrue("not identical", out != text)
    }
    check("头尾都保留：开头与结尾标记都在，中间被省略") {
        // 这条是「只留头部」的回归断言：改造前尾部结论必被丢掉
        val head = "HEAD_MARK"
        val tail = "TAIL_MARK"
        val text = head + "x".repeat(60_000) + tail
        val out = truncateToolResult(text)
        assertTrue("bound", out.length <= MAX_TOOL_RESULT_CHARS)
        assertTrue("keep head", out.startsWith(head))
        assertTrue("keep tail", out.endsWith(tail))
        assertContains("omitted note", out, "中间省略")
    }
    check("截断是确定性的：同输入两次结果完全相等") {
        val text = "z".repeat(50_000)
        assertEquals("deterministic", truncateToolResult(text), truncateToolResult(text))
    }
    check("后缀从预算预留：keep+suffix 不超上限（100K 输入）") {
        val text = "b".repeat(100_000)
        val out = truncateToolResult(text)
        assertTrue("bound", out.length <= MAX_TOOL_RESULT_CHARS)
        assertContains("suffix", out, "共 100000 字符")
    }
    check("批量多调用各自独立截断、不错位（每个结果带自己的体量）") {
        val texts = listOf("x".repeat(30_000), "short one", "y".repeat(24_001))
        val outs = texts.map { truncateToolResult(formatToolResult(ToolResult.ok(message = "m", data = it))) }
        assertTrue("t1 bound", outs[0].length <= MAX_TOOL_RESULT_CHARS)
        assertTrue("t2 untouched", !outs[1].contains("截断"))
        assertTrue("t3 bound", outs[2].length <= MAX_TOOL_RESULT_CHARS)
        assertContains("t1 suffix", outs[0], "共 ")
        assertContains("t3 suffix", outs[2], "共 ")
    }
}

/* ------------------------------ T04：熔断 ------------------------------ */

private suspend fun testT04FailureGuard() {
    println("\n== T04 失败熔断 + 调用指纹去重 ==")
    val guard = FailureGuard()
    val args = argsOf("path" to "a.txt")

    check("新指纹放行") {
        val v = guard.check(guard.fingerprint("read_file", args), false)
        assertTrue("allow", v == GuardVerdict.Allow)
    }
    check("第 2 次原样重试仍放行（失败 1 次后）") {
        val fp = guard.fingerprint("t", argsOf("k" to "v1"))
        guard.check(fp, false); guard.onExecuted(fp, failed = true)
        val v = guard.check(fp, false)
        assertTrue("allow 2nd", v == GuardVerdict.Allow)
        guard.onExecuted(fp, failed = true)
        val v3 = guard.check(fp, false)
        assertTrue("blocked 3rd", v3 is GuardVerdict.Blocked)
        assertEquals("code", "requires_changed_call", (v3 as GuardVerdict.Blocked).code)
    }
    check("同批重复判据：无历史状态也拦截") {
        val fp = guard.fingerprint("t", argsOf("k" to "v2"))
        val v = guard.check(fp, duplicateInBatch = true)
        assertTrue("blocked", v is GuardVerdict.Blocked)
        assertEquals("code", "duplicate_in_batch", (v as GuardVerdict.Blocked).code)
    }
    check("成功清指纹（含 failures 归零：失败2次→成功→再失败1次仍放行）") {
        val fp = guard.fingerprint("t", argsOf("k" to "v3"))
        guard.onExecuted(fp, failed = true)
        guard.onExecuted(fp, failed = true)
        guard.onExecuted(fp, failed = false) // 成功清
        assertEquals("state cleared", FailureState(), guard.state(fp))
        guard.onExecuted(fp, failed = true) // 重新计一次
        assertTrue("allow after clear+fail1", guard.check(fp, false) == GuardVerdict.Allow)
    }
    check("被拦截调用不更新状态（模型必须改参数）") {
        val fp = guard.fingerprint("t", argsOf("k" to "v4"))
        guard.onExecuted(fp, failed = true)
        guard.onExecuted(fp, failed = true) // 现在第 3 次会被拦
        val before = guard.state(fp)
        guard.check(fp, false) // 拦截
        assertEquals("unchanged", before, guard.state(fp))
    }
    check("指纹归一化：键序不同同指纹、值不同不同指纹") {
        val a = argsOf("a" to "1", "b" to "2")
        val b = argsOf("b" to "2", "a" to "1")
        assertEquals("same fp", guard.fingerprint("t", a), guard.fingerprint("t", b))
        val c = argsOf("a" to "9", "b" to "2")
        assertTrue("diff fp", guard.fingerprint("t", a) != guard.fingerprint("t", c))
    }
    check("fnv1a64：确定性 + 16 位小写十六进制") {
        val h1 = fnv1a64("hello")
        val h2 = fnv1a64("hello")
        assertEquals("deterministic", h1, h2)
        assertTrue("format", h1.length == 16 && h1.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue("different input", fnv1a64("hello") != fnv1a64("hellp"))
    }
}

/* ------------------------------ W-8：熔断回合作用域 + 瞬时豁免 ------------------------------ */

/**
 * W-8（真机日志诊断批次）：修「恒定指纹被瞬时故障毒化、跨回合永久挡死」。
 *
 * 真机日志根因（23:52-23:58）：无障碍绑定前 ui_tree 连续两次 service_not_ready
 * （瞬时故障），失败计数随 loop 活在 ViewModel 上、全仓无人 clear；服务 23:57:47
 * 就绪后，下个回合的 ui_tree 仍被 requires_changed_call 挡死——模型「有眼（截图）
 * 无手」，任务必败。两处修复：
 *  1. 瞬时故障三不（不计数/不清除/不拦截）——isTransientFailureCode 判据；
 *  2. 回合作用域 resetForTurn——send() 入口清空，回合内打转防护不变。
 */
private suspend fun testW8GuardTurnScope() {
    println("\n== W-8 熔断回合作用域 + 瞬时故障豁免（真机日志 23:58 ui_tree 被挡死根因）==")
    val guard = FailureGuard()

    check("isTransientFailureCode：基础设施码 true，参数类错误 false") {
        assertTrue("service_not_ready", isTransientFailureCode("service_not_ready"))
        assertTrue("service_unavailable", isTransientFailureCode("service_unavailable"))
        assertTrue(
            "capture_not_enabled 已随 W-12 删除屏幕捕捉",
            !isTransientFailureCode("capture_not_enabled")
        )
        assertTrue("io_error not transient", !isTransientFailureCode("io_error"))
        assertTrue("missing_param not transient", !isTransientFailureCode("missing_param"))
        assertTrue("empty not transient", !isTransientFailureCode(""))
    }

    check("瞬时失败三不·不计数：transient=true 累加 5 次仍放行（修前第 3 次起误挡）") {
        val fp = guard.fingerprint("ui_tree", argsOf())
        repeat(5) { guard.onExecuted(fp, failed = true, transient = true) }
        assertEquals("state untouched", FailureState(), guard.state(fp))
        assertTrue("still allow", guard.check(fp, false) == GuardVerdict.Allow)
    }

    check("瞬时失败三不·不清除：参数错误 2 次后被瞬时失败包围，熔断判定不受影响") {
        val fp = guard.fingerprint("t", argsOf("k" to "w8-noClear"))
        guard.onExecuted(fp, failed = true) // 参数错误计 1
        guard.onExecuted(fp, failed = true, transient = true) // 瞬时：不动状态
        assertEquals("failures still 1", 1, guard.state(fp).failures)
        guard.onExecuted(fp, failed = true) // 参数错误计 2 → 该拦了
        assertTrue("blocked after 2 real failures", guard.check(fp, false) is GuardVerdict.Blocked)
        guard.onExecuted(fp, failed = true, transient = true) // 瞬时不清除
        assertTrue("still blocked", guard.check(fp, false) is GuardVerdict.Blocked)
    }

    check("resetForTurn：跨回合污染清除（修前只有杀 App 能清）") {
        val fp = guard.fingerprint("t", argsOf("k" to "w8-reset"))
        guard.onExecuted(fp, failed = true)
        guard.onExecuted(fp, failed = true)
        assertTrue("blocked before reset", guard.check(fp, false) is GuardVerdict.Blocked)
        guard.resetForTurn()
        assertEquals("state cleared", FailureState(), guard.state(fp))
        assertTrue("allow after reset", guard.check(fp, false) == GuardVerdict.Allow)
    }

    check("resetForTurn 后重新计 2 次仍熔断（回合内打转防护不退化）") {
        val fp = guard.fingerprint("t", argsOf("k" to "w8-reset2"))
        guard.onExecuted(fp, failed = true)
        guard.onExecuted(fp, failed = true)
        guard.resetForTurn()
        guard.onExecuted(fp, failed = true)
        assertTrue("allow 1st after reset", guard.check(fp, false) == GuardVerdict.Allow)
        guard.onExecuted(fp, failed = true)
        assertTrue("blocked 3rd in new turn", guard.check(fp, false) is GuardVerdict.Blocked)
    }
}

/* ------------------------------ T03：并发判据 ------------------------------ */

private suspend fun testT03Concurrency() = runBlocking {
    println("\n== T03 并发判据（含写串行 / 全只读并行保序） ==")

    check("batchHasMutation：任一 !readOnly 即 true") {
        val reg = ToolRegistry()
        reg.register(FakeTool("read_file", readOnly = true))
        reg.register(FakeTool("write_file", readOnly = false))
        assertTrue("has write", reg.batchHasMutation(listOf(
            ToolCall("1", "read_file", argsOf()), ToolCall("2", "write_file", argsOf())
        )))
    }
    check("batchHasMutation：全只读才 false；未知工具按只读") {
        val reg = ToolRegistry()
        reg.register(FakeTool("read_file", readOnly = true))
        assertTrue("all read", !reg.batchHasMutation(listOf(
            ToolCall("1", "read_file", argsOf()), ToolCall("2", "read_file", argsOf())
        )))
        assertTrue("unknown as read", !reg.batchHasMutation(listOf(ToolCall("3", "ghost", argsOf()))))
    }
    check("全只读并行：7 个调用（maxParallel=3 分块）结果保序") {
        val reg = ToolRegistry()
        // 递减延时：若按完成序收集则会逆序——严格验证保序
        val delays = listOf(180L, 150L, 120L, 90L, 60L, 30L, 10L)
        delays.forEachIndexed { i, d -> reg.register(FakeTool("r$i", readOnly = true, delayMs = d)) }
        val calls = delays.indices.map { ToolCall("c$it", "r$it", argsOf()) }
        val started = System.currentTimeMillis()
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 3, providerRetry = 0
        )
        val history = loop.send("x")
        val elapsed = System.currentTimeMillis() - started
        val ids = history.filterIsInstance<ToolMessage>().map { it.id }
        assertEquals("order", calls.map { it.id }, ids)
        // 并行证据：串行需 ≥640ms，并行分块理论 ≈300ms；留充分余量
        assertTrue("parallel (elapsed=${elapsed}ms < 500ms)", elapsed < 500)
    }
    check("含写整批串行：执行顺序与调用顺序一致") {
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
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 5, providerRetry = 0
        )
        loop.send("x")
        assertEquals("serial order", listOf("read", "write", "read"), order)
    }
    check("派发遥测：整行拼装（顺序 / 模式 / 短 id）") {
        // 断言纯函数的**整行输出**：格式一改就红。比对「日志里包含某段字符串」
        // 强——后者在实现搬家的重构里照样绿，等于没验。
        assertEquals(
            "parallel",
            "派发 step=1 本批 2 个：hasMutation=false（并行） order=[c0,c1]",
            dispatchLine(1, listOf("c0", "c1"), hasMutation = false)
        )
        assertEquals(
            "serial",
            "派发 step=3 本批 2 个：hasMutation=true（串行） order=[c0,c1]",
            dispatchLine(3, listOf("c0", "c1"), hasMutation = true)
        )
        // 短 id 只留 10 位：真实 id 前 6 位全是 `call_0`，截太短会同批重名
        assertEquals(
            "short id",
            "派发 step=0 本批 1 个：hasMutation=false（并行） order=[call_00_ny]",
            dispatchLine(0, listOf("call_00_ny3UxcMRXPYSqmrW54W09467"), hasMutation = false)
        )
    }
    check("派发遥测：并行与串行两条路径都真的写日志") {
        // 用**独特的 callId** 认领本轮那行，不数总数：T02T06 前面几十个用例
        // 已经写了五六十条同样的「派发 …」，按条数断言只会数到它们。
        fun logged(tag: String): Boolean =
            AgentLogger.recent(500).any { it.message.endsWith("$tag]") }
        val readReg = ToolRegistry().apply {
            register(FakeTool("r0", readOnly = true))
            register(FakeTool("r1", readOnly = true))
        }
        val e1 = runCatching {
            RealAgentLoop(
                llmProvider = {
                    FakeLlm(
                        listOf(
                            AssistantMessage("", listOf(ToolCall("zz_par_probe", "r0", argsOf()))),
                            AssistantMessage("done")
                        )
                    )
                },
                registry = readReg, maxParallelTools = 5, providerRetry = 0
            ).send("x")
        }.exceptionOrNull()
        assertTrue("parallel send threw: ${e1?.javaClass?.simpleName}: ${e1?.message}", e1 == null)
        assertTrue("parallel batch logged", logged("zz_par_pro"))

        val writeReg = ToolRegistry().apply {
            register(FakeTool("read", readOnly = true))
            register(FakeTool("write", readOnly = false))
        }
        val e2 = runCatching {
            RealAgentLoop(
                llmProvider = {
                    FakeLlm(
                        listOf(
                            AssistantMessage("", listOf(
                                ToolCall("zz_ser_read", "read", argsOf()),
                                ToolCall("zz_ser_probe", "write", argsOf())
                            )),
                            AssistantMessage("done")
                        )
                    )
                },
                registry = writeReg, maxParallelTools = 5, providerRetry = 0
            ).send("x")
        }.exceptionOrNull()
        assertTrue("serial send threw: ${e2?.javaClass?.simpleName}: ${e2?.message}", e2 == null)
        assertTrue("serial batch logged", logged("zz_ser_pro"))
    }
}

/* ------------------------------ T05：取消 + 树尾闭合 ------------------------------ */

private fun invariantHolds(history: List<ChatMessage>): Boolean {
    val toolIds = history.filterIsInstance<ToolMessage>().map { it.id }.toSet()
    return history.filterIsInstance<AssistantMessage>()
        .flatMap { it.toolCalls }
        .all { it.id in toolIds }
}

private suspend fun testT05CancelClosure() = runBlocking {
    println("\n== T05 可取消 + 树尾闭合不变量 ==")

    check("取消发生在工具执行中途：NonCancellable 内补全后重抛 CancellationException") {
        var runJob: Job? = null
        val reg = ToolRegistry()
        reg.register(FakeTool("slow1", readOnly = true, delayMs = 5_000))
        reg.register(FakeTool("slow2", readOnly = true, delayMs = 5_000,
            onExecute = { runJob?.cancel() })) // 模拟「执行中途」外部取消
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
            registry = reg, maxParallelTools = 5, providerRetry = 0,
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
        val outcome = Result.failure<Unit>(thrown ?: error("loop.run 未抛出"))
        assertTrue("rethrown (was ${thrown?.javaClass?.simpleName})", outcome.exceptionOrNull() is CancellationException)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        val filled = lastSnapshot.filterIsInstance<ToolMessage>()
        assertEquals("all 3 filled", 3, filled.size)
        assertContains("cancelled code", filled[0].content, "code=cancelled")
        assertContains("cancelled hint", filled[0].content, "hint:")
    }
    check("工具抛普通异常：不触发取消路径，错误结果直接入史（不误闭合）") {
        val reg = ToolRegistry()
        reg.register(FakeTool("ok1", readOnly = true))
        reg.register(FakeTool("boom", readOnly = true, onExecute = { throw RuntimeException("boom") }))
        reg.register(FakeTool("ok2", readOnly = true))
        val calls = listOf(
            ToolCall("c0", "ok1", argsOf()),
            ToolCall("c1", "boom", argsOf()),
            ToolCall("c2", "ok2", argsOf())
        )
        var lastSnapshot: List<ChatMessage> = emptyList()
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 5, providerRetry = 0,
            checkpoint = { lastSnapshot = it.toList() }
        )
        loop.send("x")
        val msgs = lastSnapshot.filterIsInstance<ToolMessage>()
        assertEquals("exactly 3 (no dup fill)", 3, msgs.size)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        assertEquals("boom error", true, msgs[1].isError)
        assertContains("exception code", msgs[1].content, "code=exception")
    }
    check("审批回调抛 CancellationException：重抛而非吞成「用户拒绝」") {
        var lastSnapshot: List<ChatMessage> = emptyList()
        val reg = ToolRegistry()
        val tap = object : AgentTool {
            override val name = "ui_tap"
            override val description = ""
            override val parameters = JsonSchema.objectSchema()
            override val needsApproval = true
            override suspend fun execute(args: JsonObject) = ToolResult.ok(message = "tapped")
        }
        reg.register(tap)
        val calls = listOf(ToolCall("c0", "ui_tap", argsOf()))
        val fake = FakeLlm(listOf(AssistantMessage("", calls)))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 5, providerRetry = 0,
            // 测的是「审批回调抛取消要重抛、不能吞成用户拒绝」，不是工具面裁剪。
            // W-102 起默认面只留 file 组，ui_tap 会被裁掉走「未知工具」分支，审批不被触发。
            forceFullTools = true,
            consent = Consent.ask { _, _ -> throw CancellationException("user left") },
            checkpoint = { lastSnapshot = it.toList() }
        )
        val outcome = runCatching { loop.send("x") }
        assertTrue("rethrown", outcome.exceptionOrNull() is CancellationException)
        assertTrue("invariant", invariantHolds(lastSnapshot))
        assertTrue("no approval_denied text",
            lastSnapshot.filterIsInstance<ToolMessage>().none { it.content.contains("approval_denied") })
    }
    check("shouldContinue=false：优雅收尾补停止说明后返回") {
        val reg = ToolRegistry()
        reg.register(FakeTool("r", readOnly = true))
        val calls = listOf(ToolCall("c0", "r", argsOf()))
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("next")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 5, providerRetry = 0,
            shouldContinue = { false }
        )
        val history = loop.send("x")
        assertContains("stop note", (history.last() as AssistantMessage).content, "已根据请求停止")
    }
}

/* ------------------------------ 集成：T04 熔断 + 图片收集 ------------------------------ */

private suspend fun testIntegration() = runBlocking {
    println("\n== 集成：T04 第 3 次熔断 / 并行图片收集 ==")

    check("同一失败调用打转：第 3 次不再真执行（AgentLoop 级）") {
        val call = ToolCall("c0", "flaky", argsOf("path" to "same.txt"))
        // 恒失败工具：模型 5 轮都请求同一个调用，前 2 轮真执行，第 3 轮起熔断
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
        val reg2 = ToolRegistry().apply { register(failTool) }
        val fake = FakeLlm(List(5) { AssistantMessage("", listOf(call)) })
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg2, maxSteps = 5, providerRetry = 0
        )
        val history = loop.send("x")
        assertEquals("real executions", 2, failTool.executions) // 第 3 次起熔断
        val blocked = history.filterIsInstance<ToolMessage>().count { it.content.contains("requires_changed_call") }
        assertTrue("blocked msgs (got $blocked)", blocked >= 1)
    }
    check("并行批次图片收集：15 个截图类调用全部回流（images 无丢失）") {
        val reg = ToolRegistry()
        repeat(15) { i ->
            reg.register(object : AgentTool {
                override val name = "shot$i"
                override val description = ""
                override val parameters = JsonSchema.objectSchema()
                override val readOnly = true
                override suspend fun execute(args: JsonObject): ToolResult {
                    delay(2) // 放大并发窗口
                    return ToolResult.ok(
                        message = "shot$i",
                        parts = listOf(ToolPart.Image("data:image/jpeg;base64,IMG$i"))
                    )
                }
            })
        }
        val calls = (0 until 15).map { ToolCall("c$it", "shot$it", argsOf()) }
        var lastSnapshot: List<ChatMessage> = emptyList()
        val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
        val loop = RealAgentLoop(
            llmProvider = { fake },
            registry = reg, maxParallelTools = 5, providerRetry = 0,
            checkpoint = { lastSnapshot = it.toList() }
        )
        loop.send("x")
        val visionFeed = lastSnapshot.filterIsInstance<UserMessage>().lastOrNull { it.images.isNotEmpty() }
        val imgs = visionFeed?.images ?: emptyList()
        assertEquals("no image lost", 15, imgs.size)
        assertEquals("merge order == calls order",
            (0 until 15).map { "data:image/jpeg;base64,IMG$it" }, imgs)
    }

    check("压力：50 轮并行图片收集无丢失（验证 images 列表并发安全）") {
        repeat(50) { round ->
            val reg = ToolRegistry()
            repeat(15) { i ->
                reg.register(object : AgentTool {
                    override val name = "shot${round}_$i"
                    override val description = ""
                    override val parameters = JsonSchema.objectSchema()
                    override val readOnly = true
                    override suspend fun execute(args: JsonObject): ToolResult =
                        ToolResult.ok(message = "s", parts = listOf(ToolPart.Image("data:image/jpeg;base64,IMG")))
                })
            }
            val calls = (0 until 15).map { ToolCall("c$it", "shot${round}_$it", argsOf()) }
            var lastSnapshot: List<ChatMessage> = emptyList()
            val fake = FakeLlm(listOf(AssistantMessage("", calls), AssistantMessage("done")))
            val loop = RealAgentLoop(
                llmProvider = { fake },
                registry = reg, maxParallelTools = 5, providerRetry = 0,
                checkpoint = { lastSnapshot = it.toList() }
            )
            loop.send("x")
            val imgs = lastSnapshot.filterIsInstance<UserMessage>().lastOrNull { it.images.isNotEmpty() }?.images ?: emptyList()
            if (imgs.size != 15) throw AssertionError("round $round: expected 15 images, got ${imgs.size}")
        }
    }

    check("压力：20 轮 x 200 协程并发 onExecuted 不同指纹无丢失（FailureGuard.states 并发安全）") {
        repeat(20) { round ->
            val guard = FailureGuard()
            val n = 200
            runBlocking {
                coroutineScope {
                    (0 until n).map { i ->
                        async(Dispatchers.Default) {
                            val fp = guard.fingerprint("t$i", argsOf("i" to "$i"))
                            guard.check(fp, false)
                            guard.onExecuted(fp, failed = true)
                        }
                    }.awaitAll()
                }
            }
            val live = (0 until n).count { i ->
                guard.state(guard.fingerprint("t$i", argsOf("i" to "$i"))).failures == 1
            }
            if (live != n) throw AssertionError("round $round: expected $n live states, got $live")
        }
    }
}



fun main() = runBlocking {
    println("=== Phase1 第二批（T02-T06）QA 行为验证 ===")
    testT02Contract()
    testT06Truncation()
    testT04FailureGuard()
    testW8GuardTurnScope()
    testT03Concurrency()
    testT05CancelClosure()
    testIntegration()
    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
