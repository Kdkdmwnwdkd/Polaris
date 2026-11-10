package com.beichen.polaris.skill

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.AgentScope
import com.beichen.polaris.core.agent.DEFAULT_READ_ONLY_BUDGET_MS
import com.beichen.polaris.core.agent.DEFAULT_WRITE_BUDGET_MS
import com.beichen.polaris.core.agent.NoopHooks
import com.beichen.polaris.core.agent.RegistryToolExecutor
import com.beichen.polaris.core.agent.ToolCallOutcome
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.core.agent.ToolExecutor
import com.beichen.polaris.core.agent.currentAgentScope
import com.beichen.polaris.core.agent.journal.RunScope
import com.beichen.polaris.core.agent.journal.ToolSettleStatus
import com.beichen.polaris.core.time.Monotonic
import com.beichen.polaris.core.llm.primOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.ClassShutter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Skill 执行引擎（Phase 2 核心）：
 *
 * 一个 Skill 现在有两种运行形态：
 *  1. prompt 形态（无 entry）：只把指令正文作为 use_skill 的返回，由模型自行遵循。
 *  2. script 形态（有 entry + 可用 JsEngine）：执行 main.js，脚本通过 [SkillRuntimeBridge] 安全调用工具。
 *
 * 脚本与工具之间隔离在 [SkillRuntimeBridge] 里：脚本只能调用技能 manifest 中声明过的 tools 白名单，
 * 越权调用会被拦截；这对应你计划的「Skill 只提供工具白名单 + 权限」。
 */

/** JS 执行引擎抽象：把脚本与具体引擎（Rhino / 未来的 QuickJS）解耦。 */
interface JsEngine {
    suspend fun eval(source: String, bindings: Map<String, Any?>): Any?
    fun close()
}

/** 脚本单次求值超时上限（毫秒）：超过即放弃脚本结果、回退提示词形态。 */
private const val SCRIPT_TIMEOUT_MS = 10_000L

/**
 * 外层 `withTimeoutOrNull` 在脚本截止之上多等的宽限（毫秒）。
 *
 * 内层指令看门狗负责精确中断；外层只兜「卡在桥调用里」——那种情况脚本没在执行指令，
 * 看门狗没有机会触发。留 5 秒足以让看门狗先动手，避免两个超时同时生效时日志自相矛盾。
 */
private const val OUTER_GRACE_MS = 5_000L

/**
 * 指令看门狗的检查间隔（解释执行的指令条数）。
 *
 * 1 万条：单次 `currentTimeMillis` 的开销摊薄到可忽略，而在低端机上执行 1 万条指令
 * 也只要几毫秒，超时精度足够。调小会让长脚本多花几个百分点，调大会让中断变迟钝。
 */
private const val INSTRUCTION_CHECK_INTERVAL = 10_000

/** 脚本一律禁止触碰的 Java 包名前缀：JDK（反射/IO/网络）、Rhino 自身、宿主 App 内部类。 */
private val BLOCKED_CLASS_PREFIXES = arrayOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "org.mozilla.javascript.",
    "com.beichen.polaris."
)

/**
 * 基于 Mozilla Rhino 的 JS 引擎：纯 Java 实现，无需 NDK/原生库，可直接在 Android ART 上运行。
 * 选用它而非 QuickJS，是因为本工程可在无原生编译链的环境直接落地脚本能力；
 * 后续若需更小更快，可新增 QuickJsEngine 实现本接口、在 [SkillExecutor] 处替换即可。
 *
 * 沙箱加固（L11）：
 *  1. ClassShutter 屏蔽上表中的包：脚本拿不到 Packages.java.net.Socket / Class.forName / 宿主内部类，
 *     唯一出口是注入的 agent 桥（其工具调用受 manifest 白名单 + L5 全局能力门双重约束）。
 *  2. optimizationLevel = -1（解释执行）——这不是性能取舍，是**中断能力的前提**：
 *     只有解释执行才会按指令数回调 [DeadlineContext.observeInstructionCount]。
 *  3. 指令数看门狗：见 [DeadlineContext]。
 *
 * ## 超时语义（R8 修复）
 * R7 之前这里写着「Rhino 无法强制中断死循环，只能不等它」。那句话在当时是对的——
 * 只靠 `withTimeoutOrNull` 确实只能放弃结果，`evaluateString` 是阻塞调用、不响应协程取消，
 * `while(true){}` 会把 `Dispatchers.Default` 的一个 worker 永久占住（技能多来自市场导入，
 * 质量不可控，一次死循环就少一个调度线程，直到进程重启）。
 *
 * 现在用 Rhino 自带的指令观察钩子真正杀掉脚本线程，不需要换引擎：
 * 解释执行模式下每 N 条指令回调一次，在里面查墙钟即可抛异常。
 * 外层 `withTimeoutOrNull` 保留为**兜底**（阈值放宽），覆盖「卡在桥调用里」的情况——
 * 那种时候脚本根本没在执行指令，指令看门狗看不到它。
 */
class RhinoJsEngine : JsEngine {

    /**
     * 带墙钟截止的 Rhino 上下文：**能真正中断死循环**。
     *
     * ## 为什么查墙钟而不是数指令
     * 不同脚本每毫秒执行的指令数差两个数量级（空循环 vs 大量字符串拼接）。
     * 按指令数设上限，要么把正常脚本切得太早，要么对慢脚本形同虚设。
     * 指令计数只用来控制**检查频率**（每 1 万条查一次，单次开销摊薄到可忽略，
     * 而 1 万条指令在低端机上也只要几毫秒，超时精度足够）。
     *
     * ## 为什么必须继承 Context 而不是用 `Context.enter()`
     * [observeInstructionCount] 是 protected 方法，只有子类能覆盖；
     * `Context.enter()` 走工厂创建的是基类实例，钩子永远不会被调到。
     * 用 `Context.enter(cx)` 把自建实例挂到当前线程——它是公开的静态方法，
     * 不需要替换全局 `ContextFactory`（那会影响进程内所有 Rhino 使用者）。
     */
    private class DeadlineContext(private val deadlineMs: Long) : org.mozilla.javascript.Context() {
        init { setInstructionObserverThreshold(INSTRUCTION_CHECK_INTERVAL) }

        override fun observeInstructionCount(instructionCount: Int) {
            // 看门狗只在解释器执行指令时被调到；时钟必须单调，否则系统对时（回拨）
            // 会让这个判据永远为假，脚本从此不再被截断。
            if (Monotonic.nowMs() >= deadlineMs) throw ScriptTimeout()
        }
    }

    /** 脚本超时的内部信号：不暴露给调用方，[eval] 把它折成 `null`（= 回退提示词形态）。 */
    private class ScriptTimeout : RuntimeException("技能脚本执行超时")

    override suspend fun eval(source: String, bindings: Map<String, Any?>): Any? =
        // 外层只是兜底：脚本卡在桥调用里（http_request / tool 执行）时指令看门狗看不到它——
        // 那会儿根本没有解释器指令在跑。阈值必须**大于**内层截止，否则外层先返回 null，
        // 日志里会出现「已判超时但线程还在跑」的假象，排障时对不上。
        withTimeoutOrNull(SCRIPT_TIMEOUT_MS + OUTER_GRACE_MS) {
            withContext(Dispatchers.Default) {
                // 截止时间在进入 Default 之后才算：排队等 worker 的时间不该算进脚本预算
                val deadline = Monotonic.nowMs() + SCRIPT_TIMEOUT_MS
                val cx = DeadlineContext(deadline)
                org.mozilla.javascript.Context.enter(cx)
                try {
                    cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
                    cx.optimizationLevel = -1
                    // 脚本唯一能碰到的宿主对象是 [buildAgentObject] 现搭的纯 JS 对象，
                    // 不再是 Java 反射封装——所以这里可以一刀切：任何宿主类名都不放行。
                    cx.setClassShutter(ClassShutter { className ->
                        !BLOCKED_CLASS_PREFIXES.any { p -> className.startsWith(p) }
                    })
                    val scope = cx.initStandardObjects()
                    // 桥接对象必须**逐方法**转成 JS 函数，不能交给 Context.javaToJS：
                    // 它走 Rhino 的 JavaMembers 反射封装，而 JavaMembers 类里引用了
                    // `javax.lang.model.SourceVersion`——那是 JDK 编译器 API，Android 没有。
                    // 类解析失败会抛 NoClassDefFoundError，脚本直接执行不了（真机已复现：
                    // `技能脚本执行失败：Failed resolution of: Ljavax/lang/model/SourceVersion;`）。
                    // 顺带的好处是能力面从「bridge 的全部 public 方法」收窄到下面这 6 个。
                    val jsBindings = HashMap<String, Any?>(bindings.size)
                    for ((k, v) in bindings) {
                        jsBindings[k] = if (v is SkillRuntimeBridge) {
                            buildAgentObject(cx, scope, v)
                        } else {
                            org.mozilla.javascript.Context.javaToJS(v, scope)
                        }
                    }
                    for ((k, v) in jsBindings) scope.put(k, scope, v)
                    // 包成函数表达式：(source)；若求值结果是个函数，则注入 (input, agent) 调用
                    val value = cx.evaluateString(scope, "($source)", "skill.js", 1, null)
                    if (value is org.mozilla.javascript.Function) {
                        return@withContext value.call(
                            cx, scope, scope,
                            arrayOf(jsBindings["input"], jsBindings["agent"])
                        )
                    }
                    return@withContext value
                } catch (e: ScriptTimeout) {
                    // 到这里说明看门狗真的把脚本线程打断了——与「放弃等待但线程还在转」不同，
                    // 这次线程会正常退到 finally 并 exit()，不会泄漏 worker。
                    AgentLogger.w(
                        "Skill",
                        "脚本超过 ${SCRIPT_TIMEOUT_MS}ms，已强制中断：${source.take(80).replace('\n', ' ')}"
                    )
                    return@withContext null
                } finally {
                    org.mozilla.javascript.Context.exit()
                }
            }
        }

    /**
     * 把 [SkillRuntimeBridge] 摊成纯 JS 对象：六个函数，别的一概没有。
     *
     * 参数一律用 `Context.toString` 取值，对象参数由 [org.mozilla.javascript.NativeJSON]
     * 序列化后再交给桥——桥那边只认 String / Map / JsonObject，直接喂 NativeObject
     * 会得到「参数无法解析为对象」。
     */
    private fun buildAgentObject(
        cx: org.mozilla.javascript.Context,
        scope: org.mozilla.javascript.Scriptable,
        bridge: SkillRuntimeBridge,
    ): org.mozilla.javascript.Scriptable {
        val obj = cx.newObject(scope)

        fun arg(args: Array<Any?>, i: Int): String =
            org.mozilla.javascript.Context.toString(args.getOrNull(i))

        fun define(name: String, fn: (Array<Any?>) -> Any?) {
            obj.put(
                name, obj,
                object : org.mozilla.javascript.BaseFunction() {
                    override fun getFunctionName(): String = name
                    override fun call(
                        cx: org.mozilla.javascript.Context,
                        scope: org.mozilla.javascript.Scriptable,
                        thisObj: org.mozilla.javascript.Scriptable,
                        args: Array<Any?>,
                    ): Any? = fn(args)
                }
            )
        }

        define("readFile") { a -> bridge.readFile(arg(a, 0)) }
        define("writeFile") { a -> bridge.writeFile(arg(a, 0), arg(a, 1)) }
        define("notify") { a -> bridge.notify(arg(a, 0), arg(a, 1)) }
        define("log") { a ->
            bridge.log(if (a.isEmpty()) null else arg(a, 0))
            org.mozilla.javascript.Undefined.instance
        }
        define("http") { a ->
            bridge.http(
                method = arg(a, 0),
                url = arg(a, 1),
                body = a.getOrNull(2)?.let { org.mozilla.javascript.Context.toString(it) },
            )
        }
        define("tool") { a ->
            val raw = a.getOrNull(1)
            val payload = if (raw == null || raw === org.mozilla.javascript.Undefined.instance) {
                null
            } else {
                org.mozilla.javascript.NativeJSON.stringify(cx, scope, raw, null, null) as? String
            }
            bridge.tool(arg(a, 0), payload)
        }
        return obj
    }

    override fun close() {}
}

/**
 * 暴露给 Skill 脚本的安全运行时桥：脚本通过它调用工具与环境。
 * 所有工具调用都经过 [SkillManifest.tools] 白名单校验，越权即拦截。
 *
 * ## R7：脚本的工具调用现在与主路径同规格
 *
 * 改造前这里直接 `registry.execute(call)`——绕开了执行器，于是：
 *  - **没有超时预算**（R2 第二层完全失效）：脚本里一次 `agent.http()` 挂死，
 *    唯一的兜底是外层 10 秒脚本看门狗，而那只是「放弃结果」，Rhino 线程照样卡住；
 *  - **没有熔断**（T04）：脚本可以反复调同一个必失败的工具，把预算耗光；
 *  - **没有回合日志**：脚本调 `agent.writeFile()` 真实改了文件，日志里却一片空白——
 *    崩溃恢复因此会漏判「技能是否已经写过文件」。
 *
 * 现在脚本走 [ToolExecutor]（与主路径同一套预算与熔断），
 * 并在执行域里成对写 `ToolDispatched` / `ToolSettled`（scope=`skill:<id>`）。
 */
class SkillRuntimeBridge(
    private val registry: ToolRegistry,
    private val skill: SkillManifest,
    @Suppress("UNUSED_PARAMETER") private val workspace: File,
    /** R7：工具执行器（超时预算 + 熔断 + DISP 落盘）。由 [SkillExecutor] 构造。 */
    private val executor: ToolExecutor,
    /** R7：本次脚本执行的域（日志句柄 + 作用域 + 步号）。null = 不留日志。 */
    private val agentScope: AgentScope? = null,
    /**
     * R7：最近一次 DISP 写入时的步号（由执行器的 `beforeEffect` 写入）。
     *
     * DISP 与 SETTLE 必须落在同一步：DISP 写在执行前、SETTLE 写在执行后，
     * 中间主回合可能已推进到下一步。用这个共享计数器把步号对齐，
     * 否则恢复器按 (step, callId) 配对时会对不上。
     */
    private val dispatchedStep: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0)
) {
    private val logs = StringBuilder()

    /**
     * R7：脚本内工具调用的序号。
     *
     * 改造前 callId 是 `skill:<id>:<工具名>`——**确定性**的。同一脚本连调两次
     * `agent.readFile()` 会得到**同一个 id**，于是：
     *  - 回喂模型的 ToolMessage 出现重复 id，模型侧配对混乱；
     *  - 回合日志里两条 DISP 共用一个 id，恢复器按 id 配对时会把它们错配
     *    （第一条的 SETTLE 被算到第二条头上），崩溃时判定出的「哪个调用已生效」就是错的。
     *
     * 加序号后每次调用唯一，代价只是 id 变长。
     */
    private val callSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 调用已注册工具。脚本中：agent.tool('http_request', {...}) */
    fun tool(name: String, args: Any?): String = invoke(name, args).content

    /**
     * 校验链 + 执行，返回完整 [ToolResult]。
     *
     * 拆出这一层是为了让 [raw] 能拿到 `data`：脚本要的是文件原文 / 响应体，
     * 而 [ToolResult.content] 会把 `message`（"已读取 notes.txt（本次 74 字符…）"）
     * 拼在前面。真机就是这么踩到的——wordcount 报 22 词 / 6 行，文件实际只有
     * 13 词 / 3 行，多出来的全是这句说明。
     */
    private fun invoke(name: String, args: Any?): ToolResult {
        // 第一道：manifest 白名单（fail-closed；空表 = 不允许任何工具，要全量必须显式 "*"）
        if (!isToolAllowedByWhitelist(skill.tools, name)) {
            return ToolResult.fail(
                code = "tool_not_allowed",
                message = "⛔ 技能「${skill.name}」无权使用工具 $name（白名单：" +
                    (skill.tools.joinToString().ifBlank { "空" }) + "）",
                hint = "在技能 manifest 的 tools 白名单里声明该工具，或改用已授权的工具。"
            )
        }
        // 第二道：全局能力门（应用内置真相源，防「自证白名单」越权拿高危权限）
        checkCapabilityGate(name, skill.trust)?.let {
            return ToolResult.fail(
                code = "capability_denied",
                message = it,
                hint = "换用能力档位更低的工具，或把技能来源改为可信来源后重试。"
            )
        }
        val obj = toJsonObject(args)
            ?: return ToolResult.fail(
                code = "bad_args",
                message = "⛔ 参数无法解析为对象：$args",
                hint = "传一个普通对象字面量，例如 {path: 'a.txt'}。"
            )
        // 第三道：需要人工确认的工具，脚本**不得代调用**。
        //
        // 不做「脚本内弹审批」的原因：JS 求值在非挂起的 Rhino 线程里，审批是 suspend 的；
        // runBlocking 会卡住这条线程，且 SCRIPT_TIMEOUT 的取消无法中断它——用户不点弹窗
        // 就会永久占住一个线程。这里直接拒绝，让模型回到主对话里走正常审批链路，
        // 是当前架构下唯一不会引入死锁的 fail-closed 做法。
        //
        // R4：判据从工具级 `needsApproval` 换成参数级 `needsApprovalFor(args)`。
        // 差别在 shell_run：`ls` 无副作用、`rm -rf` 有——工具级布尔会把两者一起拒掉，
        // 技能脚本于是连「看一眼目录」都做不到。参数级判据与主对话走同一套规则
        // （[com.beichen.polaris.core.agent.ShellCommandRules]），不会出现两处口径不一。
        val target = registry.get(name)
        if (target?.needsApprovalFor(obj) == true) {
            // W-54：唯一的例外是 manifest 里**事先声明过**的域名（[SkillNetGrant]）。
            //
            // 判据是纯函数，同一个函数也被传给了执行器的 approval（见 [buildToolExecutor]）——
            // 两处口径必须一致，否则会出现「桥放行了、执行器又拒了」这种自相矛盾的结果。
            when (
                val verdict = SkillNetGrant.evaluate(
                    toolName = name,
                    url = obj["url"]?.primOrNull()?.contentOrNull,
                    trust = skill.trust,
                    netHosts = skill.net_hosts,
                    skillName = skill.name,
                )
            ) {
                is SkillNetGrant.Verdict.Granted -> Unit
                is SkillNetGrant.Verdict.Denied -> return ToolResult.fail(
                    code = "script_net_denied",
                    message = "⛔ ${verdict.reason}",
                    hint = verdict.hint
                )
                is SkillNetGrant.Verdict.NotNetwork -> return ToolResult.fail(
                    code = "script_approval_denied",
                    message = "⛔ 工具 $name 需要用户逐次确认，脚本不能代为调用。" +
                        "请在主对话中直接调用该工具（Agent 会弹出确认框）。",
                    hint = "回到主对话，由用户确认后再发起该工具调用。"
                )
            }
        }
        val call = ToolCall(nextCallId(name), name, obj)
        return runBlocking { executeWithJournal(call, obj) }
    }

    /**
     * 取工具的**原始负载**（[ToolResult.data]）；没有 data 或失败时退回完整文本。
     *
     * 脚本做的是「拿数据做事」，不是「读给人看」——包装文本混进去会让所有
     * 文本处理类技能的结果系统性偏大。
     */
    private fun raw(name: String, args: Any?): String {
        val r = invoke(name, args)
        return if (r.ok) (r.data ?: r.message) else r.content
    }

    /**
     * R7：执行一次脚本内的工具调用，并补上回合日志的终态。
     *
     * **DISP 不在这里写**——由执行器的 `beforeEffect` 写（见 [buildToolExecutor]）。
     *
     * 这一点很要紧：`DISP` 的语义是「**越过了执行边界**」，而熔断拦截、审批拒绝、
     * 未知工具这些路径都**没有**越过。若在这里先写 DISP、再交给执行器判定，
     * 就会给「根本没执行」的调用留下 DISP——崩溃恢复看到「有 DISP 无 SETTLE」
     * 就会判成「结局未知」，让用户去核对一个从未发生过的写操作。
     *
     * 与主路径保持同一套时序：熔断判定 → 审批 → 写 DISP → 真正执行 → 写 SETTLE。
     */
    private suspend fun executeWithJournal(call: ToolCall, args: JsonObject): ToolResult {
        val scope = agentScope
        val turn = scope?.turn
        val step = dispatchedStep.get()
        val descriptor = registry.descriptorOf(call.name)
            ?: ToolDescriptor(call.name, call.name, "{}")
        val outcome = try {
            executor.execute(call, descriptor)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：原样上抛，由上层闭合处理。
            throw cancel
        } catch (e: Throwable) {
            turn?.toolSettled(step, call.id, ToolSettleStatus.FAILED)
            return ToolResult.fail(
                code = "exception",
                message = "⛔ 工具 ${call.name} 执行异常：${e.message}",
                hint = "检查参数与前置条件；持续失败请换用其他工具或告知用户。"
            )
        }

        val status = if (outcome is ToolCallOutcome.Completed && outcome.message.isError) {
            ToolSettleStatus.FAILED
        } else {
            ToolSettleStatus.fromOutcome(outcome)
        }
        turn?.toolSettled(step, call.id, status)

        return when (outcome) {
            is ToolCallOutcome.Completed -> outcome.result
                ?: ToolResult.ok(code = outcome.code, message = outcome.message.content)
            ToolCallOutcome.Interrupted -> ToolResult.fail(
                code = "interrupted",
                message = "⛔ 工具 ${call.name} 被中断（只读调用，确定未完成，可以直接重试）。",
                hint = "直接重试该工具调用。"
            )
            ToolCallOutcome.Unknown -> ToolResult.fail(
                code = "unknown",
                message = "⛔ 工具 ${call.name} 被中断（写操作，状态未知——请先用只读方式核对结果，" +
                    "确认没有生效再决定是否重做；无法确认时告知用户）。",
                hint = "先用只读工具核对副作用是否已发生，再决定是否重做。"
            )
            ToolCallOutcome.NotDispatched -> ToolResult.fail(
                code = "not_dispatched",
                message = "⛔ 工具 ${call.name} 未被派发（未执行，可以重试）。",
                hint = "直接重试该工具调用。"
            )
        }
    }

    /** 唯一 callId：技能 id + 工具名 + 序号（见 [callSeq] 的说明）。 */
    private fun nextCallId(toolName: String): String =
        "skill:${skill.id}:$toolName#${callSeq.incrementAndGet()}"

    /** 读取工作区文件。脚本中：agent.readFile('notes.txt') */
    fun readFile(path: String): String = raw("read_file", mapOf("path" to path))

    /** 写入工作区文件。脚本中：agent.writeFile('out.txt', 'hi') */
    fun writeFile(path: String, content: String): String = tool("write_file", mapOf("path" to path, "content" to content))

    /** 发起 HTTP 请求。脚本中：agent.http('GET', 'https://...') */
    fun http(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val args = mutableMapOf<String, Any?>("method" to method, "url" to url)
        if (body != null) args["body"] = body
        if (headers.isNotEmpty()) args["headers"] = headers
        return raw("http_request", args)
    }

    /**
     * 发送通知。脚本中：agent.notify('标题', '内容')
     *
     * 键名必须是 `body`：`NotificationTool` 读的是 `body`，这里曾经传 `text`，
     * 于是脚本发的通知**标题有、正文恒为空**——脚本侧看不出问题（调用成功返回），
     * 用户只看到一条空白通知。两处字段名不同步不会编译失败，只会静默丢内容。
     */
    fun notify(title: String, text: String): String = tool("send_notification", mapOf("title" to title, "body" to text))

    /** 记录脚本运行日志（会随结果一并返回给模型，便于调试）。 */
    fun log(msg: Any?) { logs.appendLine(msg?.toString() ?: "null") }

    fun getLog(): String = logs.toString()
}

/** Skill 执行结果。 */
data class SkillRunResult(val text: String, val scriptExecuted: Boolean)

/**
 * Skill 执行器：根据技能形态选择 prompt / script 运行方式。
 * @param jsEngine 可用的 JS 引擎；为 null 时所有技能回退为 prompt 形态。
 * @param readBody 读取技能指令正文（Android 侧从 assets 读取）。
 * @param readEntry 读取技能脚本源码（assets/skills/<id>/<entry>）。
 *
 * 失败熔断器（R7）**每次技能执行现建**：脚本是一次性执行域，
 * 失败指纹不该跨执行累积——否则某次失败会让该技能的工具在此后所有回合里
 * 都被熔断（主路径的熔断器是回合作用域的，技能必须对齐这个语义）。
 * 同一次执行内脚本反复调同一个必失败的工具仍会被熔断。
 */
class SkillExecutor(
    private val registry: ToolRegistry,
    private val workspace: File,
    private val jsEngine: JsEngine? = null,
    private val readBody: (SkillManifest) -> String,
    private val readEntry: (SkillManifest) -> String?,
) {

    /**
     * R7：脚本专用的工具执行器。
     *
     * - `approval`：脚本不得代弹审批，也**不允许静默放行**。需确认的工具在
     *   [SkillRuntimeBridge] 第三道门就已被拒绝（按参数级判据）；这里给执行器一个
     *   「只批准 manifest 事先声明过的域名」的策略作为第二道网——审计 F4 之前传的是 null，
     *   而执行器把 null 解释成「不需要审批」，两层保护实际上只剩一层。
     *   现在即使第三道门被改坏，执行器也会把危险调用拒成 `approval_denied`。
     *
     *   W-54：这里**不是恒 false**，而是调 [SkillNetGrant]——与第三道门同一份纯函数。
     *   只放行「技能声明过、且非公网明文」的 `http_request`，别的照旧拒。
     *   两处若各写一套判据，必然漂移成「桥放了、执行器又拒」或反之。
     * - `beforeEffect`：**在这里**写 DISP，而不是在脚本侧写。
     *   执行器的时序是「熔断判定 → 审批 → 写 DISP → 真正执行」——
     *   被熔断或被审批拒绝的调用**没有越过执行边界**，就不该留下 DISP。
     *   若在脚本侧先写 DISP 再交给执行器判定，崩溃恢复会把一个从未执行的写操作
     *   判成「结局未知」，让用户去核对一件没发生过的事。
     *
     * @param scope 本次技能执行的域（日志句柄 + 作用域 + 步号）。
     * @param dispatchedStep 回调：DISP 写入时的步号，供 SETTLE 使用（两者必须同一步）。
     * @param skill 本次执行的技能；null 时审批恒拒（harness / 无技能上下文的调用）。
     */
    internal fun buildToolExecutor(
        scope: AgentScope?,
        dispatchedStep: java.util.concurrent.atomic.AtomicInteger,
        failureGuard: FailureGuard = FailureGuard(),
        readOnlyBudgetMs: Long = DEFAULT_READ_ONLY_BUDGET_MS,
        writeBudgetMs: Long = DEFAULT_WRITE_BUDGET_MS,
        skill: SkillManifest? = null
    ): ToolExecutor = RegistryToolExecutor(
        registry = registry,
        failureGuard = failureGuard,
        approval = { tool, args ->
            skill != null && SkillNetGrant.allows(
                toolName = tool.name,
                url = args["url"]?.primOrNull()?.contentOrNull,
                trust = skill.trust,
                netHosts = skill.net_hosts,
                skillName = skill.name,
            )
        },
        hooks = NoopHooks,
        beforeEffect = { call ->
            val step = scope?.currentStep?.get() ?: 0
            dispatchedStep.set(step)
            scope?.turn?.toolDispatched(step, call.id) ?: true
        },
        readOnlyBudgetMs = readOnlyBudgetMs,
        writeBudgetMs = writeBudgetMs
    )

    suspend fun runSkill(skill: SkillManifest, inputJson: String): SkillRunResult {
        val entry = skill.entry?.let { readEntry(skill) }
        if (entry != null && jsEngine != null) {
            // 非 PACKAGED 来源禁止执行脚本，强制回退提示词形态（契约化只读的核心约束）
            if (!policyFor(skill.trust).allowScript) {
                return SkillRunResult(
                    "⚠️ 技能「${skill.name}」来源「${skill.trust}」不允许执行脚本，已回退为提示词模式。\n\n${readBody(skill)}",
                    false
                )
            }
            // R7：派生技能作用域。日志句柄与主回合**同一个文件**（派生句柄），
            // 事件带上 skill:<id> 标签——崩溃恢复时能说清「这次写操作是技能干的」。
            val scope = skillScope(skill)
            // DISP 与 SETTLE 必须落在同一步：DISP 由执行器写（执行前），
            // SETTLE 由脚本侧写（执行后），中间主回合可能已经推进到下一步。
            // 用这个共享计数器把 DISP 的步号传给 SETTLE。
            val dispatchedStep = java.util.concurrent.atomic.AtomicInteger(0)
            // R7：每次技能执行现建熔断器（见构造注释）。
            val toolExecutor = buildToolExecutor(
                scope, dispatchedStep, failureGuard = FailureGuard(), skill = skill
            )
            return runCatching {
                val bridge = SkillRuntimeBridge(
                    registry, skill, workspace, toolExecutor, scope, dispatchedStep
                )
                val engine = jsEngine!!
                val out = engine.eval(entry, mapOf("input" to inputJson, "agent" to bridge))
                // eval 返回 null 即超时（看门狗放弃了这个脚本的结果）；
                // 脚本显式 return null 也走同一分支，代价可接受——宁可回退提示词也不让用户干等。
                if (out == null) {
                    AgentLogger.e("Skill", "脚本执行超时：${skill.name}")
                    return@runCatching SkillRunResult(
                        "⚠️ 脚本执行超时（>10s），已回退为提示词模式。\n\n${readBody(skill)}",
                        false
                    )
                }
                SkillRunResult(resultToString(out, bridge), true)
            }.getOrElse { e ->
                SkillRunResult("⚠️ 技能脚本执行失败：${e.message}\n\n${readBody(skill)}", false)
            }
        }
        return SkillRunResult(readBody(skill), false)
    }

    /**
     * R7：派生技能的执行域。
     *
     * 拿不到当前域（不在回合里，或本回合无日志）时返回 null——
     * 脚本照常执行，只是不留日志，与改造前一致。
     */
    private suspend fun skillScope(skill: SkillManifest): AgentScope? {
        val parent = currentAgentScope() ?: return null
        val name = RunScope.skill(skill.id)
        return AgentScope(
            turn = parent.turn?.scoped(name),
            scope = name,
            currentStep = parent.currentStep
        )
    }

    private fun resultToString(out: Any?, bridge: SkillRuntimeBridge): String {
        val s = when (out) {
            null -> ""
            is String -> out
            else -> out.toString()
        }
        val log = bridge.getLog().trim()
        return if (log.isNotEmpty()) "$s\n\n--- skill log ---\n$log" else s
    }
}

/** 把脚本传来的参数（Rhino NativeObject / JsonObject / String / Map）归一为 JsonObject。 */
private fun toJsonObject(args: Any?): JsonObject? = when (args) {
    null -> null
    is JsonObject -> args
    is String -> runCatching { Json.parseToJsonElement(args) }.getOrNull() as? JsonObject
    is Map<*, *> -> mapToJsonObject(args)
    else -> null
}

private fun mapToJsonObject(m: Map<*, *>): JsonObject = buildJsonObject {
    for ((k, v) in m) {
        val key = k?.toString() ?: continue
        when (v) {
            null -> put(key, JsonNull)
            is String -> put(key, v)
            is Number -> put(key, v.toDouble())
            is Boolean -> put(key, v)
            is Map<*, *> -> put(key, mapToJsonObject(v))
            is List<*> -> put(key, listToJsonArray(v))
            else -> put(key, v.toString())
        }
    }
}

private fun listToJsonArray(list: List<*>): JsonArray = JsonArray(list.map { item ->
    when (item) {
        null -> JsonNull
        is String -> JsonPrimitive(item)
        is Number -> JsonPrimitive(item.toDouble())
        is Boolean -> JsonPrimitive(item)
        is Map<*, *> -> mapToJsonObject(item)
        is List<*> -> listToJsonArray(item)
        else -> JsonPrimitive(item.toString())
    }
})
