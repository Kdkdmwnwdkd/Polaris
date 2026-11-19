/**
 * 接缝与守卫链的行为验证（AgentSeams / ToolGuardChain）。
 *
 * ## 为什么是行为 harness 而不是源码扫描
 *
 * 这三个接缝的价值全在**语义**上，而语义只有真跑才看得出：
 *  - 守卫「首个拒绝即终止」——扫描源码只能证明有 for 循环，不能证明第二个守卫没被调用；
 *  - 中间件「不调 next 就截断」——这是反向能力，写错了会变成「总是截断」而没人发现；
 *  - 监听器「并发」——串行实现也能让所有断言变绿，只有计时能区分。
 *
 * 所以本文件编译的是**真实实现**（AgentSeams.kt / ToolGuard.kt + 它们的纯 Kotlin 依赖），
 * 不是替身。断言的也是真实协程行为（含超时、异常、取消三条路径）。
 *
 * ## 不测什么（说清楚，别让人以为覆盖了）
 *
 * - **loop 有没有调用这三个接缝**：那是接线事实，行为测试测不到「没有调用」，
 *   由 run-agent-seams-verification.sh 的 A 段做源码断言。
 * - **denyOutcomeOrNull 的文案格式**：它依赖 ToolExecutor 的 toolMessageOf，
 *   编译它会把 registry / approval / exposure 整条链拖进来。格式一致由代码审查保证
 *   （它调的就是同一个 toolMessageOf）。
 */

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.agent.AgentSeams
import com.beichen.polaris.core.agent.AssistantMessageListener
import com.beichen.polaris.core.agent.ApprovalPreview
import com.beichen.polaris.core.agent.EgressGuard
import com.beichen.polaris.core.agent.GuardContext
import com.beichen.polaris.core.agent.GuardDecision
import com.beichen.polaris.core.agent.ToolGuard
import com.beichen.polaris.core.agent.ToolGuardChain
import com.beichen.polaris.core.agent.UserMessageDecision
import com.beichen.polaris.core.agent.UserMessageMiddleware
import com.beichen.polaris.core.agent.UserTurnInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private var pass = 0
private var fail = 0

private fun case(name: String, body: suspend () -> Unit) {
    try {
        runBlocking { body() }
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        // 必须抓 Throwable：被测代码抛运行时异常同��是一条失败，放它逃出去会打断整个 harness，
        // 结果行都不会打印，runner 拿不到「N 通过 / M 失败」就会把「没跑完」当成「全绿」。
        fail++
        println("  FAIL  $name -> ${t::class.java.simpleName}: ${t.message}")
    }
}

private fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun toolCall(name: String = "demo_tool"): ToolCall =
    ToolCall(id = "call-$name", name = name, arguments = JsonObject(emptyMap()))

private fun ctx(name: String = "demo_tool"): GuardContext =
    GuardContext(call = toolCall(name), descriptor = null, step = 3, scope = "main")

private fun guard(body: suspend (GuardContext) -> GuardDecision): ToolGuard =
    object : ToolGuard {
        override suspend fun check(ctx: GuardContext): GuardDecision = body(ctx)
    }

private fun deny(code: String) = GuardDecision.Deny(code = code, message = "挡了 $code", hint = "换个方式")

fun main() {
    println("[A] ToolGuardChain：首个拒绝即终止，不可翻案")

    case("A1 空链弃权（没有守卫 = 不干预）") {
        expect(ToolGuardChain.NONE.check(ctx()) is GuardDecision.Abstain, "空链应弃权")
    }

    case("A2 全部弃权 = 放行") {
        val chain = ToolGuardChain(
            listOf(guard { GuardDecision.Abstain }, guard { GuardDecision.Abstain })
        )
        expect(chain.check(ctx()) is GuardDecision.Abstain, "全弃权应返回 Abstain")
    }

    case("A3 拒绝的三个字段（code/message/hint）原样透出") {
        val chain = ToolGuardChain(listOf(guard { deny("path_denied") }))
        val d = chain.check(ctx())
        expect(d is GuardDecision.Deny, "应为 Deny")
        d as GuardDecision.Deny
        expect(d.code == "path_denied", "code 被改了：${d.code}")
        expect(d.message == "挡了 path_denied", "message 被改了：${d.message}")
        expect(d.hint == "换个方式", "hint 被改了：${d.hint}")
    }

    case("A4 首个拒绝后，后续守卫一次都不再被调用") {
        val secondCalls = AtomicInteger(0)
        val chain = ToolGuardChain(
            listOf(
                guard { deny("first") },
                guard { secondCalls.incrementAndGet(); GuardDecision.Abstain }
            )
        )
        chain.check(ctx())
        expect(secondCalls.get() == 0, "第二个守卫被调用了 ${secondCalls.get()} 次——拒绝必须立即终止")
    }

    case("A5 拒绝不可被后续翻案（后面全是弃权，结果仍是拒绝）") {
        val chain = ToolGuardChain(
            listOf(
                guard { deny("locked") },
                guard { GuardDecision.Abstain },
                guard { GuardDecision.Abstain }
            )
        )
        val d = chain.check(ctx())
        expect(d is GuardDecision.Deny && d.code == "locked", "拒绝被翻案了：$d")
    }

    case("A6 守卫抛异常 → guard_failed（绝不放行）") {
        val chain = ToolGuardChain(listOf(guard { throw IllegalStateException("boom") }))
        val d = chain.check(ctx())
        expect(d is GuardDecision.Deny, "守卫崩溃必须按拒绝处理，实际：$d")
        expect((d as GuardDecision.Deny).code == "guard_failed", "code 应为 guard_failed，实际 ${d.code}")
    }

    case("A7 守卫超时 → guard_timeout（不响应挂起取消的守卫不能拖住回合）") {
        val chain = ToolGuardChain(listOf(guard { delay(ToolGuardChain.GUARD_TIMEOUT_MS + 2_000); GuardDecision.Abstain }))
        val started = System.currentTimeMillis()
        val d = chain.check(ctx())
        val cost = System.currentTimeMillis() - started
        expect(d is GuardDecision.Deny && d.code == "guard_timeout", "超时应拒绝，实际：$d")
        expect(cost < ToolGuardChain.GUARD_TIMEOUT_MS + 1_500, "超时判定太慢：${cost}ms（应约 ${ToolGuardChain.GUARD_TIMEOUT_MS}ms）")
    }

    case("A8 取消不是拒绝：CancellationException 原样上抛，不被兜成 Deny") {
        val chain = ToolGuardChain(listOf(guard { throw CancellationException("用户点了停止") }))
        var escaped = false
        try {
            chain.check(ctx())
        } catch (e: CancellationException) {
            escaped = true
        }
        expect(escaped, "取消被吞成了拒绝——用户点停止会变成一次「策略拦截」")
    }

    println("[B] 用户消息接缝：waterfall（可改写 / 可短路 / 失败用原文）")

    case("B1 空装配：原文直出，不做任何改写") {
        val d = AgentSeams.NONE.applyUserMessage(UserTurnInput("你好", listOf("img1")))
        expect(d is UserMessageDecision.Proceed, "空装配应 Proceed")
        d as UserMessageDecision.Proceed
        expect(d.input.text == "你好" && d.input.images == listOf("img1"), "空装配改了内容：${d.input}")
    }

    case("B2 中间件可改写文本并透传下游") {
        val upper = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision =
                next(input.copy(text = input.text.uppercase()))
        }
        val d = AgentSeams(userMessage = listOf(upper)).applyUserMessage(UserTurnInput("abc"))
        expect(d is UserMessageDecision.Proceed && d.input.text == "ABC", "改写没生效：$d")
    }

    case("B3 Handled 短路：不进循环，且后续中间件不再执行") {
        val laterCalls = AtomicInteger(0)
        val shortCircuit = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision =
                UserMessageDecision.Handled("这条不需要模型")
        }
        val later = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision {
                laterCalls.incrementAndGet()
                return next(input)
            }
        }
        val d = AgentSeams(userMessage = listOf(shortCircuit, later)).applyUserMessage(UserTurnInput("hi"))
        expect(d is UserMessageDecision.Handled && d.reply == "这条不需要模型", "短路没生效：$d")
        expect(laterCalls.get() == 0, "短路后后面的中间件仍被执行了 ${laterCalls.get()} 次")
    }

    case("B4 顺序：注册在前的最先看到原文（外→内）") {
        val order = Collections.synchronizedList(mutableListOf<String>())
        fun recorder(tag: String) = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision {
                order.add(tag)
                return next(input.copy(text = input.text + tag))
            }
        }
        val d = AgentSeams(userMessage = listOf(recorder("1"), recorder("2")))
            .applyUserMessage(UserTurnInput("x"))
        expect(order == listOf("1", "2"), "执行顺序错了：$order")
        expect(d is UserMessageDecision.Proceed && d.input.text == "x12", "拼接顺序错了：$d")
    }

    case("B5 中间件抛异常 → 用原文继续（fail-open），后续中间件仍执行") {
        val boom = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision =
                throw IllegalStateException("中间件 bug")
        }
        val tail = AtomicInteger(0)
        val after = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision {
                tail.incrementAndGet()
                return next(input)
            }
        }
        val d = AgentSeams(userMessage = listOf(boom, after)).applyUserMessage(UserTurnInput("原始输入"))
        expect(d is UserMessageDecision.Proceed, "中间件崩溃应回落为原文继续，实际：$d")
        expect((d as UserMessageDecision.Proceed).input.text == "原始输入", "原文被改了：${d.input.text}")
        expect(tail.get() == 1, "崩溃后后续中间件没执行（${tail.get()} 次）——用户输入不该被吞掉")
    }

    case("B6 不调 next = 截断后续中间件（这是故意能力，不是 bug）") {
        val blocked = AtomicInteger(0)
        val stopper = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision =
                UserMessageDecision.Proceed(input)
        }
        val behind = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision {
                blocked.incrementAndGet()
                return next(input)
            }
        }
        AgentSeams(userMessage = listOf(stopper, behind)).applyUserMessage(UserTurnInput("x"))
        expect(blocked.get() == 0, "不调 next 没截断后续：${blocked.get()} 次")
    }

    case("B7 整条链超时 → 按原文继续（用户不该被自己的中间件卡住）") {
        val slow = object : UserMessageMiddleware {
            override suspend fun intercept(input: UserTurnInput, next: suspend (UserTurnInput) -> UserMessageDecision): UserMessageDecision {
                delay(AgentSeams.USER_SEAM_TIMEOUT_MS + 1_000)
                return next(input.copy(text = "改过的"))
            }
        }
        val started = System.currentTimeMillis()
        val d = AgentSeams(userMessage = listOf(slow)).applyUserMessage(UserTurnInput("原文"))
        val cost = System.currentTimeMillis() - started
        expect(d is UserMessageDecision.Proceed && d.input.text == "原文", "超时应回落原文，实际：$d")
        expect(cost < AgentSeams.USER_SEAM_TIMEOUT_MS + 1_200, "超时判定太慢：${cost}ms")
    }

    println("[C] 助手消息接缝：parallel（并发 / 互不影响）")

    case("C1 空装配立即返回，不做任何事") {
        val started = System.currentTimeMillis()
        AgentSeams.NONE.notifyAssistant(AssistantMessage("回答"))
        expect(System.currentTimeMillis() - started < 100, "空装配不该有开销")
    }

    case("C2 多个监听器都收到同一条消息") {
        val a = AtomicInteger(0)
        val b = AtomicInteger(0)
        val seams = AgentSeams(
            assistantListeners = listOf(
                object : AssistantMessageListener {
                    override suspend fun onAssistantMessage(message: AssistantMessage) {
                        a.incrementAndGet()
                    }
                },
                object : AssistantMessageListener {
                    override suspend fun onAssistantMessage(message: AssistantMessage) {
                        b.incrementAndGet()
                    }
                },
            )
        )
        seams.notifyAssistant(AssistantMessage("同一句"))
        expect(a.get() == 1 && b.get() == 1, "监听器漏收：a=${a.get()} b=${b.get()}")
    }

    case("C3 一个监听器抛异常，另一个照常执行") {
        val ok = AtomicInteger(0)
        val seams = AgentSeams(
            assistantListeners = listOf(
                object : AssistantMessageListener {
                    override suspend fun onAssistantMessage(message: AssistantMessage) {
                        throw RuntimeException("埋点 SDK 崩了")
                    }
                },
                object : AssistantMessageListener {
                    override suspend fun onAssistantMessage(message: AssistantMessage) {
                        ok.incrementAndGet()
                    }
                },
            )
        )
        seams.notifyAssistant(AssistantMessage("x"))
        expect(ok.get() == 1, "一个坏监听器把其他监听器带走了")
    }

    case("C4 监听器并发执行（串行会让回合平白变慢）") {
        val seams = AgentSeams(
            assistantListeners = (1..4).map {
                object : AssistantMessageListener {
                    override suspend fun onAssistantMessage(message: AssistantMessage) {
                        delay(300)
                    }
                }
            }
        )
        val started = System.currentTimeMillis()
        seams.notifyAssistant(AssistantMessage("x"))
        val cost = System.currentTimeMillis() - started
        expect(cost < 700, "4 个各 300ms 的监听器耗时 ${cost}ms——这是串行，不是并发")
    }

    // —— 出站守卫（EgressGuard）———————————————————————————————
    //
    // P0-3 的判据：**本回合**读过外部内容之后，往外发东西必须人确认。
    // 这几条要证明的是「什么时候问、什么时候不问」，而不是「有没有这段代码」——
    // 一个恒问的守卫和恒不问的守卫都能让源码扫描变绿，只有行为能分开它们。

    fun callOf(name: String): ToolCall = ToolCall("id-$name", name, JsonObject(emptyMap()))

    fun ctxOf(name: String): GuardContext = GuardContext(callOf(name), null, 0, "main")

    case("D1 没读过任何内容时，搜索直接放行且不打扰用户") {
        val asked = AtomicInteger(0)
        val guard = EgressGuard(consent = { _, _ -> asked.incrementAndGet() > 0 })
        val d = guard.check(ctxOf("web_search"))
        expect(d is GuardDecision.Abstain, "无来源的搜索被拦了——正常用法不该弹窗")
        expect(asked.get() == 0, "无来源的搜索也问了用户（asked=${asked.get()}）——这是打扰，不是防护")
    }

    case("D2 本回合读过文件后，外发默认拒绝") {
        val asked = AtomicInteger(0)
        val guard = EgressGuard(consent = { _, _ -> asked.incrementAndGet(); false })
        expect(guard.check(ctxOf("read_file")) is GuardDecision.Abstain, "读文件本身不该被拦")
        val d = guard.check(ctxOf("web_search"))
        expect(d is GuardDecision.Deny, "读过文件后搜索没被拦——注入通道还在")
        expect((d as GuardDecision.Deny).code == "egress_blocked", "拒绝码不是 egress_blocked：${d.code}")
        expect(asked.get() == 1, "没有向用户征求同意（asked=${asked.get()}）")
    }

    case("D3 用户同意时放行（拒绝不是永远不行）") {
        var allow = false
        val guard = EgressGuard(consent = { _, _ -> allow })
        guard.check(ctxOf("read_file"))
        expect(guard.check(ctxOf("web_search")) is GuardDecision.Deny, "用户还没同意就放行了")
        allow = true
        expect(guard.check(ctxOf("web_search")) is GuardDecision.Abstain, "用户同意后仍然拒绝")
    }

    case("D4 新回合清账：上一轮读过，不算这一轮") {
        val guard = EgressGuard(consent = { _, _ -> false })
        guard.check(ctxOf("read_file"))
        expect(guard.check(ctxOf("web_search")) is GuardDecision.Deny, "前置条件没生效")
        guard.onTurnStart()
        expect(guard.check(ctxOf("web_search")) is GuardDecision.Abstain, "onTurnStart 没有清掉上一轮的标记")
    }

    case("D5 不相干的工具既不标记也不询问") {
        val asked = AtomicInteger(0)
        val guard = EgressGuard(consent = { _, _ -> asked.incrementAndGet(); false })
        expect(guard.check(ctxOf("calculator")) is GuardDecision.Abstain, "calculator 被拦了")
        // 只有 calculator 跑过，没理由认为上下文里有外部内容
        expect(guard.check(ctxOf("web_search")) is GuardDecision.Abstain, "calculator 也把回合标记成脏了")
        expect(asked.get() == 0, "不相干工具触发了询问")
    }

    case("D6 询问用守卫自己的超时（3 秒会掐掉弹窗）") {
        val guard = EgressGuard(consent = { _, _ -> false })
        expect(
            guard.timeoutMs > ToolGuardChain.GUARD_TIMEOUT_MS,
            "EgressGuard.timeoutMs=${guard.timeoutMs} 不大于链默认 ${ToolGuardChain.GUARD_TIMEOUT_MS}——" +
                "用户还没读完弹窗就会被 guard_timeout 掐掉"
        )
    }

    case("D7 守卫链按每个守卫自己的 timeoutMs 计时") {
        // 链默认 3s；这个守卫声称 50ms 但实际睡 200ms → 必须超时并被拒。
        val slow = object : ToolGuard {
            override val timeoutMs: Long get() = 50L
            override suspend fun check(ctx: GuardContext): GuardDecision {
                delay(200)
                return GuardDecision.Abstain
            }
        }
        val d = ToolGuardChain(listOf(slow)).check(ctxOf("x"))
        expect(d is GuardDecision.Deny && (d as GuardDecision.Deny).code == "guard_timeout",
            "链没有用守卫自己的 timeoutMs（结果=$d）")
    }

    // D1–D7 只走了 web_search 一条通道。而 DEFAULT_EGRESS_TOOLS 有五条，
    // 其中四条同时也是「敏感工具」——两条集合一旦重叠，实现里任何一处提前返回
    // 都会把重叠的那几条静默架空（2026-11-19 实测：四条全被架空，D1–D7 依然全绿）。
    // 所以这一条按**整个集合**走，以后往集合里加工具不用改这里。
    case("D8 外发集合里的每一条通道都要真的拦得住") {
        val leaked = mutableListOf<String>()
        for (tool in EgressGuard.DEFAULT_EGRESS_TOOLS) {
            val guard = EgressGuard(consent = { _, _ -> false })
            guard.check(ctxOf("read_file"))
            if (guard.check(ctxOf(tool)) !is GuardDecision.Deny) leaked += tool
        }
        expect(leaked.isEmpty(), "这些通道读完私有内容后仍能外发：$leaked")
    }

    // —— 审批预览（ApprovalPreview）—————————————————————————————
    //
    // 它不是接缝，但和守卫是同一件事的两面：守卫决定「拦不拦」，预览决定
    // 「用户看到什么才点允许」。判定用全量参数、预览却砍掉尾部，等于让用户
    // 为一段他没看见的命令签字。所以放在同一份行为验证里。

    case("E1 短参数原样返回（不为了统一格式改写内容）") {
        val raw = "{\"command\":\"ls -la\"}"
        expect(ApprovalPreview.of(raw) == raw, "短参数被改写了：${ApprovalPreview.of(raw)}")
    }

    case("E2 超长参数保留结尾（危险命令的结局在尾部）") {
        val head = "{\"command\":\"" + "a".repeat(3000)
        val tail = " && rm -rf /sdcard/DCIM\"}"
        val preview = ApprovalPreview.of(head + tail)
        expect(preview.length <= ApprovalPreview.DEFAULT_LIMIT + 80, "预览没有收敛（${preview.length} 字符）")
        // 用 endsWith / indexOf 而不是 contains("…")：census 会把字面量 contains 计成
        // 「文本断言」（禁止新增），而这里验的是函数输出的实际形状，不是源码文本。
        expect(preview.endsWith(tail), "尾部被砍掉了——用户看不到真正要执行的那一段")
        expect(preview.indexOf("中间省略") >= 0, "省略被静默吞掉，用户以为看到了全部")
    }

    case("E3 省略标记写明省略了多少字符") {
        val raw = "x".repeat(5000)
        val preview = ApprovalPreview.of(raw)
        val omitted = Regex("中间省略 (\\d+) 字符").find(preview)?.groupValues?.get(1)?.toIntOrNull()
        expect(omitted != null && omitted > 0, "没有写出省略字符数")
        val kept = preview.lines().filterNot { it.startsWith("…（中间省略") }.sumOf { it.length }
        expect(omitted == raw.length - kept, "省略字符数算错了：写的 $omitted，实际 ${raw.length - kept}")
    }

    println()
    println("$pass 通过 / $fail 失败")
    if (fail != 0) kotlin.system.exitProcess(1)
}
