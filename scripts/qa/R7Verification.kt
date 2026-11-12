#!/usr/bin/env kotlin
/**
 * R7（执行作用域统一 + 非主路径纳入护栏）行为验证 harness。
 *
 * ## 为什么单独一套
 * R7 修掉的是三个「**别的路径不受管**」类的缺陷。它们的共同点是：
 * 主路径上一切正常，出问题的一定是**旁路**——而旁路恰恰是用户最容易踩到的。
 *
 *  1. **技能脚本绕过执行器**。`SkillRuntimeBridge.tool()` 改造前直接
 *     `registry.execute(call)`：没有超时预算（R2 第二层对技能完全失效）、
 *     没有熔断、没有回合日志。技能调一次 `agent.writeFile()` 真的改了文件，
 *     日志里却一片空白——崩溃恢复因此判不出「技能是否已生效」。
 *
 *  2. **技能 callId 确定性重复**。改造前 id 是 `skill:<id>:<工具名>`：
 *     同一脚本连调两次同一工具 → 同一个 id → 回喂模型的 ToolMessage 重复 id，
 *     恢复器按 id 配对时把两条 DISP 配到一条 SETTLE 上，判定结果就是错的。
 *
 *  3. **子代理不在事件流里**。子代理跑在独立的 `RealAgentLoop` 里，
 *     改造前连日志句柄都没有——用户报「卡住了」时只看到主代理在等，
 *     完全看不见子代理在干什么。
 *
 * 覆盖：
 *  R7-1  作用域载体：派生句柄同文件、序号连续、scope 正确落盘
 *  R7-2  v1 老日志仍可读（崩溃恢复的依据是历史日志）
 *  R7-3  执行域上下文跨协程可见（入口安装、出口读取）
 *  R7-4  技能工具调用有 DISP/SETTLE，scope=skill:<id>
 *  R7-5  技能 callId 唯一（两次调用不撞 id）
 *  R7-6  技能路径受超时预算约束（超时返回可自纠的结果而非卡死）
 *  R7-7  技能路径有熔断（同一失败工具反复调会被拦）
 *  R7-8  子代理的工具调用进主回合日志，scope=sub:<标题>
 *  R7-9  主代理路径不受影响（scope 仍是 main，日志结构照旧）
 *  R7-10 回合日志 NDJSON 导出（一行一对象，可选单回合）
 *  R7-13 内置技能按资源目录名定位（用包名式 id 会静默退化成提示词形态）
 *  R7-14 脚本读到文件原文，不是给模型看的包装文本
 *  R8-1  死循环脚本被指令看门狗中断（线程可回收，不是只放弃等待）
 *
 * 运行：scripts/qa/run-r7-verification.sh
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.AgentScope
import com.beichen.polaris.core.agent.AgentScopeContext
import com.beichen.polaris.core.agent.NoopHooks
import com.beichen.polaris.core.agent.RegistryToolExecutor
import com.beichen.polaris.core.agent.SubAgentRunner
import com.beichen.polaris.core.agent.SubTask
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.core.agent.ToolExecutor
import com.beichen.polaris.core.agent.currentAgentScope
import com.beichen.polaris.core.agent.journal.RunEvent
import com.beichen.polaris.core.agent.journal.RunScope
import com.beichen.polaris.core.agent.journal.TurnJournal
import com.beichen.polaris.skill.SkillManifest
import com.beichen.polaris.skill.SkillExecutor
import com.beichen.polaris.skill.SkillRuntimeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: expected=$expected actual=$actual")
}

/** 可控的假工具：能睡、能失败，用来测预算与熔断。 */
private class FakeTool(
    override val name: String,
    override val readOnly: Boolean = true,
    private val sleepMs: Long = 0L,
    private val failCode: String? = null
) : AgentTool {
    override val description: String = "fake tool"
    override val parameters: JsonSchema = JsonSchema.objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult {
        if (sleepMs > 0) delay(sleepMs)
        return if (failCode != null) {
            ToolResult.fail(code = failCode, message = "boom", hint = "retry later")
        } else {
            ToolResult.ok(message = "done")
        }
    }
}

/**
 * 模拟 `read_file`：`message` 是「已读取 …（本次 N 字符）」，`data` 才是正文。
 *
 * 真实工具就是这么返回的（[com.beichen.polaris.tools.ReadFileTool]），
 * 而脚本需要的是 `data`。
 */
private class RawReadFile : AgentTool {
    override val name: String = "read_file"
    override val description: String = "read a file"
    override val parameters: JsonSchema = JsonSchema.objectSchema()
    override val readOnly: Boolean = true

    override suspend fun execute(args: JsonObject): ToolResult =
        ToolResult.ok(code = "read_ok", message = "已读取 notes.txt（本次 11 字符）", data = "hello world")
}

/** 只回固定答复的假模型。 */
private class FakeClient(private val responses: List<AssistantMessage>) : LLMClient {
    override val id: String = "fake"
    var calls = 0
        private set

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage {
        val r = responses.getOrElse(calls) { responses.last() }
        calls++
        return r
    }

    override suspend fun testPing(): com.beichen.polaris.core.PingResult =
        com.beichen.polaris.core.PingResult(200, "ok")
}

/** 建一个带日志的回合，供作用域相关用例共用。 */
private suspend fun newTurn(dir: java.io.File): Pair<TurnJournal, TurnJournal.Turn> {
    val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
    val turn = journal.begin("r7-turn-1")
    return journal to turn
}

fun main() = runBlocking {
    val base = java.io.File("/tmp/qa-r7")
    base.mkdirs()
    base.listFiles()?.forEach { it.deleteRecursively() }

    // ——————————————————————— R7-1 作用域载体 ———————————————————————

    check("R7-1 派生句柄写同一文件、序号连续") {
        val dir = java.io.File(base, "scope")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val main = journal.begin("turn-A")
        val skill = main.scoped(RunScope.skill("demo"))

        // begin() 内部已写一条 OPEN
        skill.toolDispatched(1, "call-1")
        skill.toolSettled(1, "call-1", com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED)
        main.toolDispatched(1, "call-2")
        main.toolSettled(1, "call-2", com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED)
        main.turnClosed("completed")

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-A")
        assertEquals("R7-1 事件数", 6, events.size)
        val seqs = events.map { it.seq }
        assertEquals("R7-1 序号连续且唯一", seqs.sorted(), seqs)
        assertTrue("R7-1 序号从 1 起", seqs.min() == 1L)
        val skillEvents = events.filter { it.scope.startsWith(RunScope.SKILL_PREFIX) }
        assertEquals("R7-1 技能事件数", 2, skillEvents.size)
        val mainEvents = events.filter { it.scope == RunScope.MAIN }
        assertEquals("R7-1 主路径事件数", 4, mainEvents.size)
    }

    check("R7-1 scope 落盘后读回一致") {
        val dir = java.io.File(base, "scope2")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-B")
        turn.scoped(RunScope.subagent("查资料")).toolDispatched(0, "c1")
        turn.turnClosed("completed")

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-B")
        val scoped = events.first { it is RunEvent.ToolDispatched } as RunEvent.ToolDispatched
        assertEquals("R7-1 scope 值", RunScope.subagent("查资料"), scoped.scope)
        assertEquals("R7-1 类别可判", RunScope.KIND_SUBAGENT, RunScope.kindOf(scoped.scope))
    }

    // ——————————————————————— R7-2 老日志兼容 ———————————————————————

    check("R7-2 v1 老日志仍可读（scope 归 main）") {
        val dir = java.io.File(base, "legacy")
        dir.mkdirs()
        // 模拟改造前产生的 v1 文件
        java.io.File(dir, "turn-Legacy.rjl").writeText(
            "v1\t1\t100\tturn-Legacy\tOPEN\n" +
                "v1\t2\t110\tturn-Legacy\tDISP\t1\tcall-x\n" +
                "v1\t3\t120\tturn-Legacy\tSETTLE\t1\tcall-x\tSUCCEEDED\t\n" +
                "v1\t4\t130\tturn-Legacy\tCLOSE\tcompleted\n"
        )
        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-Legacy")
        assertEquals("R7-2 读到 4 条", 4, events.size)
        assertTrue("R7-2 全部 scope=main", events.all { it.scope == RunScope.MAIN })
        val disp = events[1] as RunEvent.ToolDispatched
        assertEquals("R7-2 字段完好", "call-x", disp.callId)
    }

    // ——————————————————————— R7-3 执行域上下文 ———————————————————————

    check("R7-3 执行域跨协程可见") {
        val scope = AgentScope(turn = null, scope = RunScope.MAIN)
        val seen = withContext(AgentScopeContext(scope)) {
            // 内层再切一次线程（模拟工具执行换线程），上下文仍要跟过去
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                currentAgentScope()
            }
        }
        assertTrue("R7-3 上下文可见", seen != null)
        assertEquals("R7-3 作用域一致", RunScope.MAIN, seen?.scope)
    }

    check("R7-3 不在执行域内读到 null") {
        assertTrue("R7-3 域外为 null", currentAgentScope() == null)
    }

    // ——————————————————————— R7-4/5/6/7 技能路径 ———————————————————————

    val skillManifest = SkillManifest(
        id = "demo-skill",
        name = "演示技能",
        version = "1.0",
        tools = listOf("fake_read", "fake_slow", "fake_fail"),
        entry = "main.js",
        trust = "packaged"
    )

    /**
     * 用**生产代码的同一构造逻辑**搭一个技能桥。
     *
     * 刻意不手搓 executor：DISP 的落盘点是本轮最容易写错的地方，
     * 手搓一份「差不多的」执行器就等于测了一个不存在的东西。
     */
    fun buildSkillBridge(
        registry: ToolRegistry,
        turn: TurnJournal.Turn?,
        budgetMs: Long = 5_000L
    ): SkillRuntimeBridge {
        val skillExecutor = SkillExecutor(
            registry = registry,
            workspace = java.io.File(base, "ws"),
            jsEngine = null,
            readBody = { "" },
            readEntry = { null }
        )
        val scope = turn?.let {
            AgentScope(
                turn = it.scoped(RunScope.skill("demo-skill")),
                scope = RunScope.skill("demo-skill"),
                currentStep = java.util.concurrent.atomic.AtomicInteger(2)
            )
        }
        val dispatchedStep = java.util.concurrent.atomic.AtomicInteger(0)
        val executor: ToolExecutor = skillExecutor.buildToolExecutor(
            scope, dispatchedStep, readOnlyBudgetMs = budgetMs, writeBudgetMs = budgetMs
        )
        return SkillRuntimeBridge(
            registry = registry,
            skill = skillManifest,
            workspace = java.io.File(base, "ws"),
            executor = executor,
            agentScope = scope,
            dispatchedStep = dispatchedStep
        )
    }

    check("R7-4 技能工具调用有 DISP/SETTLE 且 scope 正确") {
        val dir = java.io.File(base, "skill-log")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-S")
        val registry = ToolRegistry().apply { register(FakeTool("fake_read")) }
        val bridge = buildSkillBridge(registry, turn)

        val out = bridge.tool("fake_read", mapOf("x" to 1))
        assertTrue("R7-4 返回内容", out.contains("done"))

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-S")
        val disp = events.filterIsInstance<RunEvent.ToolDispatched>()
        val settle = events.filterIsInstance<RunEvent.ToolSettled>()
        assertEquals("R7-4 一条 DISP", 1, disp.size)
        assertEquals("R7-4 一条 SETTLE", 1, settle.size)
        assertEquals("R7-4 DISP scope", RunScope.skill("demo-skill"), disp[0].scope)
        assertEquals("R7-4 SETTLE scope", RunScope.skill("demo-skill"), settle[0].scope)
        assertEquals("R7-4 步号沿用主回合", 2, disp[0].step)
        assertEquals("R7-4 id 配对", disp[0].callId, settle[0].callId)
        assertEquals(
            "R7-4 终态成功",
            com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED,
            settle[0].status
        )
    }

    check("R7-5 技能 callId 唯一（两次调用不撞 id）") {
        val dir = java.io.File(base, "skill-ids")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-I")
        val registry = ToolRegistry().apply { register(FakeTool("fake_read")) }
        val bridge = buildSkillBridge(registry, turn)

        bridge.tool("fake_read", mapOf("x" to 1))
        bridge.tool("fake_read", mapOf("x" to 2))

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-I")
        val disp = events.filterIsInstance<RunEvent.ToolDispatched>()
        assertEquals("R7-5 两条 DISP", 2, disp.size)
        assertTrue("R7-5 callId 不同", disp[0].callId != disp[1].callId)
        val settles = events.filterIsInstance<RunEvent.ToolSettled>()
        assertEquals("R7-5 两条 SETTLE", 2, settles.size)
        assertEquals(
            "R7-5 每个 callId 各有一次结算",
            disp.map { it.callId }.toSet(),
            settles.map { it.callId }.toSet()
        )
    }

    check("R7-6 技能路径受超时预算约束") {
        val dir = java.io.File(base, "skill-budget")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-BUD")
        val registry = ToolRegistry().apply {
            register(FakeTool("fake_slow", readOnly = true, sleepMs = 3_000L))
        }
        // 预算 200ms，工具睡 3 秒 → 必须被判超时
        val bridge = buildSkillBridge(registry, turn, budgetMs = 200L)

        val start = System.currentTimeMillis()
        val out = bridge.tool("fake_slow", mapOf<String, Any?>())
        val cost = System.currentTimeMillis() - start
        assertTrue("R7-6 未被掐断则超时（实际 ${cost}ms）", cost < 2_000L)
        assertTrue("R7-6 返回超时文案", out.contains("超时"))

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-BUD")
        val settle = events.filterIsInstance<RunEvent.ToolSettled>()
        assertEquals("R7-6 有结算事件", 1, settle.size)
        assertEquals(
            "R7-6 终态为失败",
            com.beichen.polaris.core.agent.journal.ToolSettleStatus.FAILED,
            settle[0].status
        )
    }

    check("R7-7 技能路径有熔断（同一失败工具反复调会被拦）") {
        val dir = java.io.File(base, "skill-guard")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-G")
        val registry = ToolRegistry().apply {
            register(FakeTool("fake_fail", readOnly = true, failCode = "boom"))
        }
        val bridge = buildSkillBridge(registry, turn)

        repeat(5) { bridge.tool("fake_fail", mapOf("x" to 1)) }

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-G")
        val disp = events.filterIsInstance<RunEvent.ToolDispatched>()
        // 熔断在派发前拦截 → 派发次数应少于调用次数
        assertTrue("R7-7 熔断拦住了重复调用（派发 ${disp.size} 次 < 5 次）", disp.size < 5)
    }

    // ——————————————————————— R7-8 子代理接入 ———————————————————————

    check("R7-8 子代理的工具调用进主回合日志，scope=sub:<标题>") {
        val dir = java.io.File(base, "sub-log")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val mainTurn = journal.begin("turn-SUB")

        // 子代理只保留 NO_SIDE_EFFECT_TOOLS 白名单里的工具，名字必须对上，
        // 否则工具表被裁掉、子代理压根看不到它（这正是改造前子代理「一片空白」
        // 的一部分原因：白名单外的工具连存在都不存在）。
        val registry = ToolRegistry().apply { register(FakeTool("read_file")) }
        // 子代理第一轮就要调工具，第二轮给结论
        val client = FakeClient(
            listOf(
                AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall("tc-1", "read_file", JsonObject(emptyMap())))
                ),
                AssistantMessage(content = "子代理结论")
            )
        )
        val runner = SubAgentRunner(
            llmProvider = { client },
            baseRegistry = { registry }
        )

        // 模拟「主代理的执行域」：子代理从这里继承日志句柄
        val scope = AgentScope(turn = mainTurn, scope = RunScope.MAIN)
        withContext(AgentScopeContext(scope)) {
            runner.run(listOf(SubTask("查资料", "读一下文件")))
        }

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-SUB")
        val subDisp = events.filterIsInstance<RunEvent.ToolDispatched>()
            .filter { it.scope == RunScope.subagent("查资料") }
        assertTrue("R7-8 子代理调用进主回合日志（${subDisp.size} 条）", subDisp.isNotEmpty())
        assertTrue(
            "R7-8 子代理事件 category 正确",
            RunScope.kindOf(RunScope.subagent("查资料")) == RunScope.KIND_SUBAGENT
        )
        // 子代理不得写回合级/步级事件：它的一步收口会把主代理未应答的调用清空，
        // 崩溃恢复因此漏判「可能已生效」的写操作——数据损坏级别。
        assertTrue("R7-8 子代理没写 STEP", events.none { it is RunEvent.StepClosed })
        assertTrue("R7-8 子代理没写 ASSIST", events.none { it is RunEvent.AssistantCommitted })
        assertEquals(
            "R7-8 子代理没写 CLOSE",
            0,
            events.count { it is RunEvent.TurnClosed && it.scope != RunScope.MAIN }
        )
    }

    // ——————————————————————— R7-9 主路径不受影响 ———————————————————————

    check("R7-9 主代理路径 scope 仍是 main") {
        val dir = java.io.File(base, "main-path")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-M")
        turn.assistantCommitted(0, listOf(ToolCall("c1", "read_file", JsonObject(emptyMap()))))
        turn.toolDispatched(0, "c1")
        turn.toolSettled(0, "c1", com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED)
        turn.stepClosed(0)
        turn.turnClosed("completed")

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-M")
        assertEquals("R7-9 事件数不变", 6, events.size)
        assertTrue("R7-9 全部 scope=main", events.all { it.scope == RunScope.MAIN })
    }

    // ——————————————————————— R7-11 作用域边界 ———————————————————————

    check("R7-11 派生句柄不写回合级/步级事件") {
        val dir = java.io.File(base, "owner")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-O")
        val sub = turn.scoped(RunScope.subagent("x"))

        // 派生句柄尝试写回合级/步级事件——全部应当被忽略
        sub.turnOpened()
        sub.assistantCommitted(0, listOf(ToolCall("sc-1", "read_file", JsonObject(emptyMap()))))
        sub.stepClosed(0)
        sub.turnClosed("completed")

        // 拥有者正常写
        turn.toolDispatched(0, "main-1")
        turn.toolSettled(0, "main-1", com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED)
        turn.turnClosed("completed")

        val events = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-O")
        // 只有 begin 的 OPEN + 主句柄的 DISP/SETTLE/CLOSE = 4 条
        assertEquals("R7-11 派生句柄的回合级事件未落盘", 4, events.size)
        assertTrue("R7-11 没有 STEP 事件", events.none { it is RunEvent.StepClosed })
        assertTrue("R7-11 没有 ASSIST 事件", events.none { it is RunEvent.AssistantCommitted })
        assertEquals("R7-11 只有一个 CLOSE", 1, events.count { it is RunEvent.TurnClosed })
    }

    check("R7-12 技能的失败熔断不跨执行累积") {
        val dir = java.io.File(base, "skill-guard2")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val registry = ToolRegistry().apply {
            register(FakeTool("fake_fail", readOnly = true, failCode = "boom"))
        }

        // 第一次执行：连调 3 次 → 第 3 次被熔断（只派发 2 次）
        val turn1 = journal.begin("turn-G1")
        val b1 = buildSkillBridge(registry, turn1)
        repeat(3) { b1.tool("fake_fail", mapOf("x" to 1)) }
        val d1 = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-G1")
            .filterIsInstance<RunEvent.ToolDispatched>()
        assertEquals("R7-12 第一次执行熔断生效（2 次派发）", 2, d1.size)

        // 第二次执行：同样的工具应当能重新执行。
        // 主路径的熔断器是**回合作用域**的（每回合 resetForTurn），
        // 技能若跨执行累积，某次失败就会让该技能的工具在此后所有回合里被永久熔断。
        val turn2 = journal.begin("turn-G2")
        val b2 = buildSkillBridge(registry, turn2)
        b2.tool("fake_fail", mapOf("x" to 1))
        val d2 = com.beichen.polaris.core.agent.journal.RunJournal(dir).load("turn-G2")
            .filterIsInstance<RunEvent.ToolDispatched>()
        assertEquals("R7-12 第二次执行未被上次的熔断挡住", 1, d2.size)
    }

    // ——————————————————————— R7-10 NDJSON 导出 ———————————————————————

    check("R7-10 NDJSON 一行一对象、字段自解释") {
        val dir = java.io.File(base, "ndjson")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-J")
        turn.assistantCommitted(0, listOf(ToolCall("c1", "read_file", JsonObject(emptyMap()))))
        turn.toolDispatched(0, "c1")
        turn.toolSettled(0, "c1", com.beichen.polaris.core.agent.journal.ToolSettleStatus.SUCCEEDED)
        turn.stepClosed(0)
        turn.turnClosed("completed")

        val ndjson = com.beichen.polaris.core.agent.journal.RunJournal(dir).exportNdjson("turn-J")
        val lines = ndjson.trim().lines()
        assertEquals("R7-10 行数=事件数", 6, lines.size)
        assertTrue("R7-10 每行是对象", lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue("R7-10 含 type 字段", lines.all { it.contains("\"type\":") })
        assertTrue("R7-10 含 scope 字段", lines.all { it.contains("\"scope\":") })
        assertTrue("R7-10 含 turn 字段", lines.all { it.contains("\"turn\":") })
        assertTrue("R7-10 工具事件带 call", lines.any { it.contains("\"type\":\"disp\"") && it.contains("\"call\":\"c1\"") })
    }

    check("R7-10 NDJSON 转义：引号被转义、每行仍是完整对象") {
        val dir = java.io.File(base, "ndjson-esc")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-E")
        // 结束原因里塞进引号——TSV 侧只净化分隔符，引号原样保留，JSON 侧必须再转义一次
        turn.turnClosed("he said \"hi\" then left")

        val ndjson = com.beichen.polaris.core.agent.journal.RunJournal(dir).exportNdjson("turn-E")
        val lines = ndjson.trim().lines()
        assertEquals("R7-10 单回合两行", 2, lines.size)
        assertTrue("R7-10 含转义引号", ndjson.contains("\\\""))
        assertTrue("R7-10 无裸换行", lines.none { it.isEmpty() })
        assertTrue("R7-10 每行闭合", lines.all { it.startsWith("{") && it.endsWith("}") })
    }

    check("R7-10 JSON 导出与 TSV 存储信息一致（不增不减）") {
        val dir = java.io.File(base, "ndjson-parity")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val turn = journal.begin("turn-P")
        turn.assistantCommitted(0, listOf(ToolCall("c1", "read_file", JsonObject(emptyMap()))))
        turn.toolDispatched(0, "c1")
        turn.toolSettled(0, "c1", com.beichen.polaris.core.agent.journal.ToolSettleStatus.FAILED, "boom")
        turn.turnClosed("completed")

        val store = com.beichen.polaris.core.agent.journal.RunJournal(dir)
        val events = store.load("turn-P")
        val ndjson = store.exportNdjson("turn-P")
        assertEquals("R7-10 行数=事件数", events.size, ndjson.trim().lines().size)
        // 事件里有的字段必须都在；导出不得凭空多出事件里没有的信息
        assertTrue("R7-10 settle 带 status", ndjson.contains("\"status\":\"FAILED\""))
        assertTrue("R7-10 settle 带 error", ndjson.contains("\"error\":\"boom\""))
        assertTrue("R7-10 assist 带 calls 数组", ndjson.contains("\"calls\":["))
    }

    check("R7-10 导出全部回合按时间从旧到新") {
        val dir = java.io.File(base, "ndjson-all")
        val journal = TurnJournal(com.beichen.polaris.core.agent.journal.RunJournal(dir))
        val t1 = journal.begin("turn-A1")
        t1.turnClosed("completed")
        Thread.sleep(5)
        val t2 = journal.begin("turn-A2")
        t2.turnClosed("completed")

        val all = com.beichen.polaris.core.agent.journal.RunJournal(dir).exportAllNdjson()
        val lines = all.trim().lines()
        assertTrue("R7-10 导出非空", lines.isNotEmpty())
        assertTrue(
            "R7-10 旧回合在前",
            lines.indexOfFirst { it.contains("\"turn\":\"turn-A1\"") } <
                lines.indexOfFirst { it.contains("\"turn\":\"turn-A2\"") }
        )
    }

    check("R7-13 内置技能按资源目录名定位，不用包名式 id") {
        // 真机 bug：wordcount 的 id 是 com.you.wordcount，资源目录是 wordcount。
        // 用 id 拼 "assets/skills/<id>/main.js" 必然落空 → entry 读不到 →
        // 脚本形态静默退化成提示词形态，skill: 作用域一条都不会产生。
        assertEquals(
            "R7-13 内置技能取 assetDir",
            "wordcount",
            com.beichen.polaris.skill.skillResourceDir("wordcount", "com.you.wordcount")
        )
        assertEquals(
            "R7-13 导入技能回退到 id（其目录就是 id）",
            "my.skill",
            com.beichen.polaris.skill.skillResourceDir("", "my.skill")
        )
        assertEquals(
            "R7-13 缺省 assetDir 也回退 id",
            "my.skill",
            com.beichen.polaris.skill.skillResourceDir(null, "my.skill")
        )
    }

    check("R7-14 脚本读到的是文件原文，不是给模型看的包装文本") {
        // 真机 bug：read_file 的 ToolResult 是 message + data，content 会把
        // "已读取 notes.txt（本次 74 字符…）" 拼在正文前面。脚本拿 content 去统计，
        // 22 词 / 6 行——实际文件只有 13 词 / 3 行。
        val registry = ToolRegistry().apply { register(RawReadFile()) }
        val bridge = com.beichen.polaris.skill.SkillRuntimeBridge(
            registry = registry,
            skill = com.beichen.polaris.skill.SkillManifest(
                id = "com.you.wordcount", name = "单词统计", version = "1.0.0",
                tools = listOf("read_file"), trust = "packaged",
            ),
            workspace = java.io.File("/tmp"),
            executor = RegistryToolExecutor(registry, FailureGuard()),
        )
        val got = bridge.readFile("notes.txt")
        assertEquals("R7-14 必须是原文", "hello world", got)
        assertTrue("R7-14 不得混入 message", !got.contains("已读取"))
    }

    // —— R8：脚本中断能力 ——
    //
    // 这条用例真跑 10 秒死循环，不为快，为的是**验证线程真的退出了**：
    // 只测「返回 null」是测不出泄漏的——旧实现同样返回 null，但 worker 会永久空转。
    // 所以断言分两段：结果必须为 null，且中断之后引擎还能继续求值。
    check("R8-1 死循环脚本被指令看门狗中断，且线程可回收") {
        val engine = com.beichen.polaris.skill.RhinoJsEngine()
        val t0 = System.currentTimeMillis()
        val result = engine.eval("(function(){ while(true){} })", emptyMap())
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("超时应返回 null（放弃结果、回退提示词形态）", result == null)
        assertTrue(
            "应在 8~14 秒内返回（脚本截止 10s），实际 ${elapsed}ms",
            elapsed in 8_000L..14_000L
        )
        // 中断后引擎仍可用：说明脚本线程已经退出，没有把调度器 worker 占死
        val again = engine.eval("(function(){ return 'alive'; })()", emptyMap())
        assertEquals("中断后应能继续求值", "alive", again)
    }

    // —— 沙箱边界（2026-11-12 收口） ——
    //
    // 修前是 6 条前缀的黑名单 + `initStandardObjects()`，两处都能穿：
    //   1. 黑名单只管**列出来的**包，`Packages.<未列包>.<类>.<静态方法>()` 照样执行
    //      （用同版本 Rhino 复现过：静态方法执行、静态字段读写、对象引用持有都成立）；
    //   2. `Packages` / `java` 是 initStandardObjects 装进去的**全局对象**，
    //      根本不经过 ClassShutter——黑名单写得再全也拦不住。
    // Android 上 `android.*` 正好落在漏掉的包里（`ActivityThread.currentApplication()`）。
    // 修后：ClassShutter 恒 false（任何宿主类不可见）+ initSafeStandardObjects()。

    check("R9-1 脚本拿不到 Packages / java 等宿主全局") {
        val engine = com.beichen.polaris.skill.RhinoJsEngine()
        assertEquals(
            "Packages 必须不存在（initSafeStandardObjects）",
            "undefined",
            engine.eval("(function(){ return typeof Packages })()", emptyMap())
        )
        assertEquals(
            "java 必须不存在",
            "undefined",
            engine.eval("(function(){ return typeof java })()", emptyMap())
        )
    }

    check("R9-2 未列包名的宿主类同样访问不到") {
        val engine = com.beichen.polaris.skill.RhinoJsEngine()
        // 选 kotlin.*：修前黑名单里没有它，而 kotlin-stdlib 一定在 classpath 上，
        // 所以这条在修前能拿到类（typeof 返回 function/object），修后必须抛。
        // 用「能拿到类」而不是「能执行方法」作判据，是为了不依赖具体类的方法签名。
        val out = engine.eval(
            "(function(){ try { return typeof Packages.kotlin.Unit } catch (e) { return 'BLOCKED' } })()",
            emptyMap()
        )
        assertEquals("必须被拒（修前可访问）", "BLOCKED", out)
    }

    println()
    println("=== R7 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
