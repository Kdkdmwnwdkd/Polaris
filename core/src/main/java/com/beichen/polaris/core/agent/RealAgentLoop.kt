package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.DEFAULT_SYSTEM_PROMPT
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LlmHttpException
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.MEMORY_USAGE_PROMPT
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolApproval
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.agent.journal.RunScope
import com.beichen.polaris.core.agent.journal.ToolSettleStatus
import com.beichen.polaris.core.agent.journal.TurnJournal
import com.beichen.polaris.core.llm.ChatStreamEvent
import com.beichen.polaris.core.llm.ErrorNormalizer
import com.beichen.polaris.core.llm.FinishReason
import com.beichen.polaris.core.llm.RetryProfile
import com.beichen.polaris.core.Prompt
import com.beichen.polaris.core.PromptAssembler
import com.beichen.polaris.core.Section
import com.beichen.polaris.core.Stability
import com.beichen.polaris.core.time.Monotonic
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R2 第一层超时：单次 LLM 调用（一次 `streamChat` 收集）在预算内没有完成。
 *
 * 为什么要有独立类型：`withTimeoutOrNull` 吞掉超时后必须给重试层一个**明确不可重试**
 * 的信号。若退回 `else -> true` 的默认判定，一次 300 秒的卡死会被重试 3 次，
 * 用户要干等 20 分钟才看到报错——那正是「不白等」要修的东西。
 */
class LlmCallTimeoutException(val timeoutMs: Long) : RuntimeException(
    "LLM 调用超时：${timeoutMs / 1000} 秒内没有完成（连接可能已挂死）"
)

/**
 * [AgentLoop] 的默认实现（T01）：回合主循环——规划 -> 调工具 -> 回填 -> 再规划，
 * 直到 LLM 不再请求工具调用。外部行为逐条保持（见下方「保留行为」清单）。
 * 旧的薄适配层（原样转发 20 个构造参数）已删除：ChatViewModel 直连本类，
 * 避免「两处默认值必须一致」靠人记而反复漏改。
 *
 * **三段式工具执行**（T01 契约化后的回合内工具阶段）：
 *  1. **解析**：registry.descriptorOf(call.name) 拿 [ToolDescriptor]（未知工具 →
 *     合成占位 descriptor，executor 内走 T02 模糊匹配文案）；
 *  2. **执行**：[ToolExecutor]（默认 [RegistryToolExecutor]）——T03 并发判据
 *     （含写串行 / 全只读并行 + maxParallelTools 分块保序）、T04 熔断、审批、
 *     统一契约格式化、T06 截断、图片收集全部内聚在 executor 内；
 *  3. **commit + 回喂**：结果按调用序整批进历史（保序），图片汇合后附
 *     「请看下面由工具返回的画面：」UserMessage 回灌视觉模型（E1：局部 images
 *     列表由父协程按序合并，杜绝并发写丢更新）。
 *
 * **流式（T01）**：LLM 通道从裸 `chat()` 演进为 [LLMClient.streamChat]——
 * TextDelta 只进 [onTextDelta] 回调（UI 打字机），进历史的一定是 Completed
 * 整条 AssistantMessage（含 ToolCall）。默认 streamChat 实现 = chat() + 单条
 * Completed，故未覆盖流式的四个既有客户端行为零变化。
 *
 * **保留行为（回归面，逐条不许丢）**：
 *  - T02 统一契约（formatToolResult 单一出口 + 未知工具模糊匹配文案）；
 *  - T03 并发判据（batchHasMutation 写串行 / 全只读并行 + maxParallelTools 分块保序
 *    + E1 局部 images 父协程合并）；
 *  - T04 熔断（指纹 / check / onExecuted / 第 3 次拦截 / 同批 duplicate 预计算）；
 *  - T05（每轮 ensureActive / NonCancellable 闭合 / shouldContinue 优雅收尾 /
 *    取消绝不吞成拒绝或 Failed）；
 *  - T06 截断；callWithRetry（429/5xx 重试、4xx 快败、取消不重试）；maybeCompact；
 *  - buildSystemPrompt；审批流（审批回调抛 CancellationException 必须重抛）；
 *    checkpoint；onEvent 两个事件（并行按 call.id 归并由 UI 负责）；日志点保留。
 *
 * **取消 vs 失败的两条红线**：
 *  - CancellationException 永远不是失败：绝不被 executor 吞成 outcome、绝不被
 *    回合级兜底吞成 [ToolExecutionFailedException]——原样上抛，由 T05 树尾闭合收尾。
 *  - executor 自身违约（实现代码抛异常，如 NPE/Error）→ 回合级兜底转
 *    [ToolExecutionFailedException] 上抛：整个回合失败、该调用不回喂模型
 *    （业务 bug 显形）。此路径**不做**树尾闭合——回合作废，历史不回喂；
 *    与取消路径（历史要继续用，必须闭合）语义相反，刻意区分。
 *
 * @param llmProvider lambda 而非实例：模型配置可在运行时变化（每轮调用时取最新）。
 * @param registry 当前可用工具集合（受 Project 工具白名单约束）。
 * @param systemPrompt 基础系统提示（工具自带提示与记忆摘要在其上拼接）。
 * @param maxSteps 单轮最大步数（默认 **60**）。上调直接放大 token 消耗与单回合时长
 *   （最坏 N 次 LLM 往返），上下文侧靠 maxHistoryMessages/maybeCompact 兜底。
 * @param maxParallelTools 全只读批次并行上限（分块限流，默认 5）。
 * @param providerRetry 供应商瞬时失败重试次数（默认 3，共 4 次尝试；hotfix10 上调：
 *   429 限流序列 10s/30s/60s 需 3 次重试才走得完，2 次会在第 3 次 429 后直接失败）。
 * @param maxHistoryMessages 上下文滑动窗口上限（默认 40）。
 * @param approval 危险工具审批回调（null = 关闭）。
 * @param checkpoint 界面投影回调（每批消息变更后调用）。**不是恢复依据**——
 *   它拿到的是一份内存列表快照，进程被杀时最后一次快照已经丢了；
 *   崩溃恢复只认 [journal]。
 * @param journal 回合日志（R1，null = 不留恢复依据，行为与改造前一致）。
 *   非 null 时每步按「模型响应落盘 → 派发落盘 → 执行 → 结果落盘 → 步关闭」写盘，
 *   进程在工具执行窗口被杀，重启后由 [com.beichen.polaris.core.agent.journal.RunJournalRepair]
 *   判定「越没越过执行边界」，据此决定补「可安全重试」还是「可能已生效，先核对」。
 * @param memoryProvider 记忆摘要提供者（null = 关闭）。
 * @param onEvent 过程事件回调（null = 关闭，保持旧行为）。
 * @param shouldContinue 每轮结束复查的续跑钩子（null = 关闭）。返回 false 则本轮收尾。
 * @param onTextDelta 流式文本增量回调（null = 关闭）。
 * @param onReasoningDelta 功能五（P1-9）：推理过程增量回调（null = 关闭）。
 * @param hooks 执行过程钩子（默认 [NoopHooks]）。
 * @param executorFactory executor 工厂：每批工具执行前取一次（null = 按当前字段
 *   构造 [RegistryToolExecutor]）。测试可注入违约 executor 验证回合级兜底；
 *   update(registry) 也因此对默认 executor 即时生效。
 * @param forceFullTools Phase 2 DoD④「本地模型工具暴露降级」：强制全量工具开关
 *   （默认 false = 按 provider 自动）。W-102 起云端与端侧都只默认给 file 组
 *   （+ 常驻 misc），其余靠 `__use_tools` 开；置 true 可强制全量（开发者/设置项逃生门）。
 * @param backoffPlan 第 n 次重试（1-based）应等待的计划毫秒数（默认
 *   [RetryBackoffPolicy.backoffMillis]：3s → 10s → 25s 封顶的**可配常量序列**）。
 *   测试可注入小值序列以保持 harness 毫秒级。
 * @param networkRecovery 退避期间的网络恢复等待器（默认
 *   [NetworkRecoveryWaiter.TIMER_ONLY] 纯定时降级；Android 侧由
 *   [ConnectivityNetworkRecoveryWaiter] 注入）。等待期间网络恢复 → 立即结束等待、
 *   马上重试（真机第三轮 Bug A2：关掉飞行模式后应「秒续」而不是干等）。
 * @param retryBackoff 重试退避钩子（「准备重试」事件抛出**之后**、下一次尝试**之前**
 *   调用，参数为 1-based 重试序号）。默认由 [backoffPlan] + [networkRecovery] 组合而成
 *   （[RetryBackoffPolicy.networkAware]）；测试可注入即时版以保持 harness 毫秒级。
 *   断网恢复了这种「等一会儿再试」才有意义的场景，全靠它区分「重试」与「原地冲锋」。
 */
class RealAgentLoop(
    private val llmProvider: () -> LLMClient,
    registry: ToolRegistry,
    systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    maxSteps: Int = 60,
    private val maxParallelTools: Int = 5,
    /**
     * 供应商瞬时失败重试次数（不含首次）。
     *
     * **`-1` = 跟随 [retryProfileProvider] 的档位**（R10 起的默认）。显式传非负值可覆盖，
     * 主要给 harness 用（`providerRetry = 2` 让它精确控制尝试次数）。
     *
     * 改成一枚哨兵而不是继续写 3：35 家供应商共用同一个次数是 R10 修掉的缺陷——
     * 官方直连 3 次够用，聚合中转商高峰期 3 次不够；而本地推理一次都不该重试。
     * 用 -1 表示「没意见」，比用 3 表示「没意见」诚实：3 是一个具体主张，会被误读成
     * 「所有供应商都该重试 3 次」。
     */
    private val providerRetry: Int = -1,
    /**
     * 当前供应商的重试档位（R10）。lambda 而非值：模型配置可在运行时切换，
     * 档位必须跟着变，否则切到聚合商之后还在用官方档的次数。
     */
    private val retryProfileProvider: () -> RetryProfile = { RetryProfile.DEFAULT },
    private val maxHistoryMessages: Int = 40,
    /**
     * W-10 上下文预算（估算 token）。每轮请求前按此预算构建**请求视图**：
     * 超预算时从最老的完整步开始丢（保留任务锚点与当前步），UI/落盘历史不动。
     *
     * 默认 24k token：覆盖 32k 上下文的常见小模型；[maxHistoryMessages] 作为条数硬上限
     * 继续生效（双闸）。纯函数实现见 [ContextBudget]。
     */
    private val contextBudgetTokens: Int = ContextBudget.DEFAULT_BUDGET_TOKENS,
    /** W-10：请求视图里最多保留最近几条工具截图的 base64（更早的剥成文字标记）。 */
    private val keepRecentImages: Int = ContextBudget.DEFAULT_KEEP_IMAGES,
    private val approval: ToolApproval? = null,
    private val checkpoint: ((List<ChatMessage>) -> Unit)? = null,
    /** R1：回合日志（null = 不留恢复依据，harness 与无状态调用走这条）。 */
    private val journal: TurnJournal? = null,
    /**
     * R7：外部已开启的回合日志句柄。**优先于** [journal]。
     *
     * 子代理用它把工具调用记进**主回合**的日志文件（scope=`sub:<标题>`），
     * 而不是自己开一个新文件。
     *
     * 为什么不新开文件：崩溃恢复是**逐文件**判定的（扫描 → 找出未闭合回合 → 补尾）。
     * 子代理单独成文件的话，「主回合到底有没有产生副作用」就得跨文件聚合——
     * 而子代理的写工具虽被白名单挡住，脚本形态技能却能写文件。
     * 同文件 + scope 标签，既保留「谁产生的」信息，又让恢复判定保持单文件自洽。
     */
    private val journalTurn: TurnJournal.Turn? = null,
    /**
     * R7：本执行域的作用域名。子代理传 `sub:<标题>`，主代理用 [RunScope.MAIN]。
     * 出现在 R5 时间线的每条事件上。
     */
    private val scope: String = RunScope.MAIN,
    memoryProvider: (() -> String)? = null,
    private val onEvent: ((AgentEvent) -> Unit)? = null,
    private val shouldContinue: (() -> Boolean)? = null,
    private val onTextDelta: ((String) -> Unit)? = null,
    /** 功能五（P1-9）：推理过程增量回调（null = 关闭）。与 [onTextDelta] 分流。 */
    private val onReasoningDelta: ((String) -> Unit)? = null,
    private val attemptOut: AttemptOutputTracker = AttemptOutputTracker(), // R-09：已上屏字符数
    private val hooks: Hooks = NoopHooks,
    /**
     * R5：回合结构化事件观察者（null = 关闭）。
     *
     * 与 [hooks] / [onEvent] 的分工见 [TurnEvent] 的类注释：Hooks 面向执行器观测，
     * AgentEvent 面向 UI 驱动，本观察者面向**排障**——一条带回合号与耗时的完整时间线。
     *
     * 判据：用户报「卡住了」时，能定位到最后一个 `tool.start`。
     */
    private val observer: TurnObserver = NoopTurnObserver,
    private val executorFactory: (() -> ToolExecutor)? = null,
    private val forceFullTools: Boolean = false,
    /**
     * W-102 粘性激活的工具组（跨回合、跨请求持久）。
     *
     * 传 null 时每次 [runTurn] 新建空集，行为与改造前逐字节一致——harness 与无状态调用
     * 走这条路，不背「上一次的组还在」的隐含状态。
     *
     * 传入时必须是有序集合（`LinkedHashSet`）：[KernelExposure.enforceCap] 的淘汰顺序
     * 依赖插入序做 LRU。移动端由 ChatViewModel 持有并落盘（`ToolActivationStore`）。
     */
    private val activeGroups: MutableSet<String>? = null,
    private val networkRecovery: NetworkRecoveryWaiter = NetworkRecoveryWaiter.TIMER_ONLY,
    private val backoffPlan: (attempt: Int) -> Long = RetryBackoffPolicy::backoffMillis,
    /**
     * 429 限流专用退避计划（hotfix10，默认 [RetryBackoffPolicy.rateLimitBackoffMillis]：
     * 10s → 30s → 60s 封顶 + 尊重服务端 Retry-After）。
     *
     * 与 [backoffPlan] 分开而非合并：harness 大量注入 `backoffPlan = { 小值 }` 保持毫秒级，
     * 合并会破坏所有既有注入点；分开则「普通故障走通用序列、限流走限流序列」各行其道。
     */
    private val rateLimitPlan: (attempt: Int, retryAfterMs: Long) -> Long =
        RetryBackoffPolicy::rateLimitBackoffMillis,
    /**
     * 429 限流专用退避钩子（hotfix10，默认 [RetryBackoffPolicy.networkAwareRateLimit]）。
     * 与 [retryBackoff] 分开：harness 大量注入 `retryBackoff`/`backoffPlan` 保持毫秒级，
     * 且 429 与服务端 Retry-After 需要额外的毫秒参数——合并签名会破坏所有既有注入点。
     */
    private val rateLimitBackoff: suspend (attempt: Int, retryAfterMs: Long) -> Unit =
        RetryBackoffPolicy.networkAwareRateLimit(rateLimitPlan, networkRecovery),
    private val retryBackoff: suspend (attempt: Int) -> Unit =
        RetryBackoffPolicy.networkAware(backoffPlan, networkRecovery),
    /**
     * R2 第一层：单次 LLM 调用的上限（毫秒，默认 300 秒）。
     *
     * 流式响应没有「整通超时」（[com.beichen.polaris.core.llm.LlmHttp] 的 `callTimeout = 0`），
     * 长回答可以跑几分钟，按整通计时会误杀。这里卡的是**一次调用**的上限：
     * 300 秒还没结束，基本是连接挂死而不是在生成。
     *
     * 端侧模型（provider id 以 `local-` 开头）自动放宽到 3 倍：手机上跑 7B 生成
     * 两千 token 确实要几分钟，用云端的尺子量它会天天误杀。
     */
    private val llmCallTimeoutMs: Long = 300_000L,
    /**
     * R2 第三层：单个回合的总预算（毫秒，默认 30 分钟）。
     *
     * 与 [maxSteps] 互补：步数限制防「同参打转」，时间限制防「每步都很慢」——
     * 60 步 × 每步 30 秒就是半小时，用户在旁边看着屏幕不动，只会以为卡死了。
     *
     * 走**软截止**而不是 `withTimeout` 硬杀：到点后在步边界优雅收尾，补一条
     * 「已暂停，回复继续」的助手消息。硬杀会抛 `TimeoutCancellationException`，
     * 那是 `CancellationException` 的子类，会被当成「用户点停止」走树尾闭合，
     * 用户看到的是一堆「已取消」而不是一句人话。
     */
    private val turnBudgetMs: Long = 30 * 60 * 1000L
) : AgentLoop {

    /**
     * T04：失败熔断 + 调用指纹去重。
     *
     * **回合作用域（W-8）**：随 loop 活在 ViewModel 上（不随每批重建的 executor 丢失），
     * 但 [send] 入口 [FailureGuard.resetForTurn] 清空——回合内跨批次保留（防模型同参
     * 打转的本意不变），跨回合/跨会话不继承。修前全仓无人 clear：真机日志里上个回合
     * 两次 service_not_ready（瞬时故障）把恒定指纹的失败计数永久留下，下个回合服务
     * 就绪后 ui_tree 仍被 requires_changed_call 挡死，模型「有眼无手」任务必败。
     */
    private val failureGuard = FailureGuard()

    /**
     * 本步已尝试的请求次数（P5-1）。
     *
     * 只给 `llm.end` 事件用：确定性错误（401 / 400 / 额度不足）本该一次就失败，
     * 若 attempt > 1 说明重试策略把不该重试的东西重试了——「确定性错误平均白等
     * 38 秒」那项指标就是从这里算的。用 @Volatile 而不是局部变量：发射点在
     * `callWithRetry` 之外，而一次只有一个回合在跑（UI 侧串行保证）。
     */
    @Volatile
    private var lastAttempts: Int = 1

    /** 可运行时更新字段（[update] 只改这四项；@Volatile：update 可能来自 UI 线程）。 */
    @Volatile
    private var registry: ToolRegistry = registry

    @Volatile
    private var systemPrompt: String = systemPrompt

    @Volatile
    private var memoryProvider: (() -> String)? = memoryProvider

    @Volatile
    private var maxSteps: Int = maxSteps

    /**
     * 执行一轮对话，返回完整消息历史（语义 = 旧 AgentLoop.run()）。
     *
     * 结束路径：① LLM 不再请求工具（正常结束）② shouldContinue 返回 false 优雅收尾
     * ③ 达到 maxSteps；取消路径抛 CancellationException（树尾闭合后重抛）；
     * executor 违约路径抛 [ToolExecutionFailedException]（回合作废，不回喂）。
     *
     * **熔断状态回合作用域（W-8）**：入口无条件 [FailureGuard.resetForTurn]——
     * 上一回合的失败指纹（尤其 service_not_ready 类瞬时故障的累加）不污染本轮；
     * 回合内跨批次保留，同参打转防护不受影响。
     */
    override suspend fun send(
        userInput: String,
        images: List<String>,
        priorMessages: List<ChatMessage>
    ): List<ChatMessage> {
        AgentLogger.i("AgentLoop", "开始运行，输入：${userInput.take(120)}（历史 ${priorMessages.size} 条）")
        // W-8：熔断状态回合作用域化——上一回合的失败指纹（含 service_not_ready
        // 瞬时故障累加）绝不被本轮继承；回合内跨批次保留，防同参打转的本意不变。
        failureGuard.resetForTurn()
        return runTurn(userInput, images, priorMessages)
    }

    /**
     * 便捷重载（无历史 / 无图）：契约方法不能用默认参数实现（Kotlin 覆盖不允许重复默认值），
     * 而 harness 与子代理大量直接对具体类型调 `send("hi")`，故保留这两个入口。
     * 生产链路一律用三参数版本显式传历史，避免「以为带了历史其实没带」。
     */
    suspend fun send(userInput: String): List<ChatMessage> =
        send(userInput, emptyList(), emptyList())

    /** 便捷重载（无历史，带图）。 */
    suspend fun send(userInput: String, images: List<String>): List<ChatMessage> =
        send(userInput, images, emptyList())

    /** [send] 的回合体（抽出后 [send] 只负责回合作用域的状态重置，异常路径也走得到 finally）。 */
    private suspend fun runTurn(
        userInput: String,
        images: List<String> = emptyList(),
        priorMessages: List<ChatMessage> = emptyList()
    ): List<ChatMessage> {
        // R5：turnId **无条件**生成。
        //
        // 改造前 turnId 只在有日志时生成（`journal.begin(newTurnId())`），没日志的
        // 链路（harness、子代理）就没有回合号——而 R5 的排障价值恰恰依赖「跨回合
        // 可对照」：多个回合交错时，没有回合号的时间线等于没有时间线。
        // 所以这里先造号，再按有无日志分别使用。
        val turnId = newTurnId()
        val clock = TurnClock(turnId)
        // R5：回合结束状态。由 [TurnClock] 记录、各出口设置，finally 统一发事件——
        // 保证「有且只有一条 turn.end」（漏发就看不出回合是被取消还是卡死）。
        val status = TurnEndStatus()
        // R7：注入的句柄优先（子代理把调用记进主回合文件），否则按 journal 开一个新回合。
        //
        // 两条路径产出的 Turn 语义一致：都有日志、没有的都是「退回无日志的现状」。
        val turn: TurnJournal.Turn? = journalTurn ?: journal?.begin(turnId)
        if (turn == null) {
            try {
                return runTurnBody(userInput, images, priorMessages, null, clock, status)
            } catch (cancel: CancellationException) {
                status.set("cancelled")
                throw cancel
            } catch (e: Throwable) {
                status.set("failed")
                throw e
            } finally {
                clock.emitTurnEnd(status.value)
            }
        }
        var reason = TurnJournal.REASON_COMPLETED
        // R7：把执行域挂进协程上下文。
        //
        // 技能脚本与子代理都在**别人的调用栈里**执行（脚本在 Rhino 线程、工具在
        // registry 里），要让他们写到同一份回合日志，就得有一份「当前回合句柄」的
        // 传递通道。逐层加参数会污染每一层签名；用协程上下文则只改两端——
        // 入口安装、出口读取，中间层与新执行路径都不用记得改。
        val agentScope = AgentScope(turn = turn, scope = scope)
        try {
            return withContext(AgentScopeContext(agentScope)) {
                runTurnBody(userInput, images, priorMessages, turn, clock, status)
            }
        } catch (cancel: CancellationException) {
            reason = TurnJournal.REASON_CANCELLED
            status.set("cancelled")
            throw cancel
        } catch (e: Throwable) {
            reason = TurnJournal.REASON_FAILED
            status.set("failed")
            throw e
        } finally {
            // NonCancellable：取消路径上直接写会立刻再抛一次，日志就断在最后一步。
            // 收尾本身很快（一次 fsync），代价可以接受。
            withContext(NonCancellable) {
                clock.emitTurnEnd(status.value)
                turn.turnClosed(reason)
                // R7：prune 归 journal 管——注入的句柄（子代理）不该替主回合清理文件。
                journal?.prune()
            }
        }
    }

    /** 回合 ID：时间序（便于 prune 按名排序）+ 随机后缀（同毫秒并发也不撞）。 */
    private fun newTurnId(): String =
        "${System.currentTimeMillis().toString(36)}-${java.util.UUID.randomUUID().toString().take(8)}"

    /**
     * 回合内的时间基准（R5）。
     *
     * 事件里的 [TurnEvent.elapsedMs] 是**相对回合开始**的毫秒数。用相对时间而非
     * 墙钟有两个理由：日志跨设备对照时不会因时区/自动校时错位；回合中途改系统时间
     * 也不会让时间线出现负数或跳跃。
     *
     * 顺带把「回合状态」也放这里——三个出口（正常结束 / 取消 / 失败）都要写
     * `turn.end`，放在一个地方能保证**有且只有一条**。
     */
    private inner class TurnClock(val turnId: String) {
        /** R7：本执行域的作用域名（主代理 = main，子代理 = sub:<标题>）。 */
        val scope: String = this@RealAgentLoop.scope
        private val startMs = System.currentTimeMillis()
        private var lastStep = 0

        fun elapsed(): Long = System.currentTimeMillis() - startMs

        fun step(n: Int): Int {
            lastStep = n
            return n
        }

        fun currentStep(): Int = lastStep

        /** 发出一个事件。观察者抛异常也不能影响回合——观测不该有副作用能力。 */
        fun emit(event: TurnEvent) {
            runCatching { observer.onEvent(event) }
        }

        /** 发回合结束事件（整个回合唯一一条）。 */
        fun emitTurnEnd(status: String) {
            emit(
                TurnEvent.TurnEnded(
                    turnId, currentStep(), elapsed(), status, currentStep() + 1, scope
                )
            )
        }

        // —— 事件构造的便捷入口（P5-1）——
        //
        // 每个发射点原本都要重复五六个信封参数（turnId / step / elapsed / scope），
        // 十处发射点就是八九十行纯样板。搬进来之后，发射点一行一个事件，
        // 「这一回合在什么时机发了哪些事件」能一眼看完——而这件事在排障时
        // 恰恰比「每个事件怎么拼出来的」重要得多。
        fun turnStarted(inputChars: Int, tools: Int) =
            emit(TurnEvent.TurnStarted(turnId, 0, elapsed(), inputChars, tools, scope))

        fun promptPlanned(step: Int, tools: Int, tokens: Int, groups: List<String>) =
            emit(TurnEvent.PromptPlanned(turnId, step, elapsed(), tools, tokens, groups, scope))

        fun llmStarted(step: Int, attempt: Int) =
            emit(TurnEvent.LlmCallStarted(turnId, step, elapsed(), attempt, scope))

        /** @param code 归一化失败类别（见 `failureCodeOf`），成功传 null。 */
        fun llmEnded(
            step: Int, attempt: Int, durMs: Long, ok: Boolean, code: String? = null, calls: Int = 0
        ) = emit(TurnEvent.LlmCallEnded(turnId, step, elapsed(), attempt, durMs, ok, code, calls, scope))

        fun toolStarted(step: Int, tool: String, args: String, callId: String) =
            emit(TurnEvent.ToolCallStarted(turnId, step, elapsed(), tool, args, callId, scope))

        fun toolEnded(
            step: Int, tool: String, code: String, isError: Boolean, durMs: Long, callId: String
        ) = emit(TurnEvent.ToolCallEnded(turnId, step, elapsed(), tool, code, isError, durMs, callId, scope))

        fun stepClosed(step: Int, toolCount: Int) =
            emit(TurnEvent.StepClosed(turnId, step, elapsed(), toolCount, scope))
    }

    /**
     * 回合结束状态（R5）。
     *
     * 为什么不用一个局部 `var`：`runTurn` 的 finally 与 `runTurnBody` 的多个 return
     * 出口都要写它，跨方法用局部变量需要传出参数，啰嗦且容易漏。封成一个小对象后，
     * 每个出口只调 [set]，最后由 finally 统一发事件。
     *
     * 状态值的意义（排障时能直接看出回合是怎么结束的）：
     * - `completed`：模型正常给完了回答；
     * - `cancelled`：用户点了停止，或协程被取消；
     * - `failed`：异常（模型调用失败、executor 违约等）；
     * - `budget_exceeded`：超出回合总预算，优雅收尾；
     * - `max_steps`：用满步数上限；
     * - `stopped`：中途收到停止请求（优雅停止通道）。
     */
    private class TurnEndStatus {
        @Volatile
        var value: String = "completed"
            private set

        fun set(status: String) {
            value = status
        }
    }

    /**
     * [runTurn] 的回合体（包一层是为了让日志的「开/关」包住所有出口，
     * 包括三个正常 return 与两条异常路径）。
     *
     * @param turn 本回合的日志句柄，null = 不记录。
     * @param clock R5 的时间基准与回合号锚点。
     */
    private suspend fun runTurnBody(
        userInput: String,
        images: List<String>,
        priorMessages: List<ChatMessage>,
        turn: TurnJournal.Turn?,
        clock: TurnClock,
        status: TurnEndStatus
    ): List<ChatMessage> {
        val loopStart = Monotonic.nowMs() // 单调时钟：回合预算不能受校时/改时间影响（4.2 同类）
        // 内核暴露（core/KernelExposure）：按能力档位选工具面——W-102 起云端与端侧都只默认
        // 激活 file 组（+ 常驻 misc），其余靠 `__use_tools` 开。开过的组由元工具就地写回
        // 集合，因此工具面在循环内**每轮重算**（见下方 repeat 块首）。
        // W-102：优先用外部持有的**粘性**集合——同一会话里第二次「帮我截图」不该再
        // 开一遍 ui 组（开一次 = 一次完整往返 + 一次前缀失效）。未注入时退回每回合新建，
        // 行为与改造前逐字节一致（harness 走这条）。
        val active = this.activeGroups ?: linkedSetOf()
        val exposure0 = currentExposure(active)
        logExposure(exposure0)
        // 系统提示只拼一次：它承载「身份 + 规则 + 工具用法」，逐轮重拼会让本地推理的 KV 前缀
        // 每轮失效（这正是改造前每轮都慢一截的原因之一）。后启用的组靠工具 description 自解释。
        // 拼装按变化频率分三段（见 buildSystemPrompt），记忆摘要压在最后一段。
        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage(buildSystemPrompt(exposure0.visible).text))
        // W-46：历史对话插在系统提示之后、当前输入之前——多点追问（「它」「继续」「第二个方案」）
        // 才有所指。priorMessages 只用于**构造请求**，返回值不含它们（调用方按返回值落盘，
        // 若把历史一并返回会被重复写入会话）。裁剪统一交给 buildRequestMessages → ContextBudget。
        messages.addAll(priorMessages)
        // 功能四（P0-2）：用户手动附带的图片随首条用户消息一起发
        messages.add(UserMessage(userInput, images))
        // W-49：本轮输入在 messages 里的下标。解图（pruneImages）必须按它锚定——
        // 多轮会话里「首条 UserMessage」是历史最老的那条且不带图，把它当锚点等于没保护，
        // 反例见 ContextBudget.pruneImages 的 KDoc。
        val turnInputIndex = messages.lastIndex
        // 对外返回值 = 系统提示 + 本回合新增部分，**不含历史**（历史只用于构造请求）。
        // 保留 SystemMessage 是契约：调用方与 harness 一直把 history[0] 当系统提示读；
        // 而历史若一并返回，ChatViewModel 的落盘循环会把上一轮的问答再写一遍（重复）。
        fun turnResult(): List<ChatMessage> = messages.drop(priorMessages.size)
        checkpoint?.invoke(messages)

        // R5：回合开始事件。工具面规模取「当前暴露面」的可见工具数——
        // 用户报「模型没按我说的做」时，第一眼要确认的就是「它到底看得见哪些工具」。
        clock.turnStarted(userInput.length, exposure0.visible.size)
        // 截断提示每回合最多报一次：多步循环里同一原因会反复出现，刷屏反而让人忽略。
        var truncatedNotified = false

        repeat(maxSteps) { step ->
            clock.step(step)
            // R7：把当前步号同步给执行域——技能脚本要用它写日志（DISP/SETTLE 带步号），
            // 否则技能产生的事件会挂在一个错误的步上，排障时对不上模型的那一轮。
            currentAgentScope()?.currentStep?.set(step)
            // T05：每轮开始复查取消——UI 点「停止」后 ≤1 轮内退出
            currentCoroutineContext().ensureActive()
            // R2 第三层：回合总预算软截止。只在**步边界**判，不在工具执行中途打断——
            // 中途硬杀会把「工具可能已生效」的状态留给用户，比多跑一步更糟。
            // 单步过长由第二层（工具级预算）负责，两层各管各的时间尺度。
            val elapsed = Monotonic.nowMs() - loopStart
            if (elapsed > turnBudgetMs) {
                AgentLogger.w(
                    "AgentLoop",
                    "回合超时：已运行 ${elapsed}ms（预算 ${turnBudgetMs}ms），在第 $step 步前优雅收尾"
                )
                hooks.beforeStop()
                status.set("budget_exceeded")
                messages.add(
                    AssistantMessage(
                        "⏱ 本次任务已连续运行 ${turnBudgetMs / 60_000} 分钟，先停在这里（防止一直占着设备）。\n" +
                            "已完成的工作和结果都保留着，回复「继续」即可接着做；" +
                            "如果某一步特别慢，可以把那件事拆小一点再让我做。"
                    )
                )
                return turnResult()
            }
            // 每轮重算工具面：上一步若调了 __use_tools，激活的组要立刻体现在下一轮请求里。
            // scopedRegistry 仍按暴露面收紧执行侧——被裁工具走「未启用」路径并提示先开它所在的组。
            val exposure = currentExposure(active)
            val turnRegistry = scopedRegistry(exposure)
            // T01 钩子：序列化前（每次 LLM 请求前一次；快照传参，钩子看不到后续变更）
            hooks.beforeSerialization(messages.toList())
            val stepStart = System.currentTimeMillis()
            // W-10：只把「裁剪后的请求视图」发给 LLM——旧截图剥图、超预算丢最老步；
            // messages 本身保持完整（UI 工具卡片与落盘历史不受影响）。
            // 视图在重试循环外算一次：同一请求的多次重试必须发完全相同的载荷。
            val requestMessages = buildRequestMessages(messages, turnInputIndex)
            // R5：请求视图算完（暴露 + 预算 + 截断）→ prompt.plan。
            // 记估算 token 是为了回答「是不是上下文撑爆了」——这类问题在改代码时看不出来，
            // 只有把当次的估算值与可见工具数放在一起看才有意义。
            clock.promptPlanned(
                step = step,
                tools = exposure.visible.size,
                tokens = requestMessages.sumOf { ContextBudget.messageTokens(it) },
                groups = exposure.groups.map { it.first },
            )
            // R5：llm.call。重试循环在 callWithRetry 内部，因此这里发的是**首次尝试**；
            // 重试由 callWithRetry 自己发 RetryStarted（既有通道），不重复计入时间线——
            // 时间线的粒度是「一次请求往返」，重试属于同一次往返的内部行为。
            clock.llmStarted(step, attempt = 1)
            val llmStart = System.currentTimeMillis()
            val resp = try {
                callWithRetry { chatOnce(requestMessages, exposure) }
            } catch (e: Throwable) {
                // R5：失败也要留一条 llm.end ——「卡住」与「失败了」在用户嘴里是一样的
                // （都是没回话），必须能从时间线区分开。
                // P5-1：带上真实尝试次数与归一化失败类别。修前写死 attempt=1 且
                // code 一律 "llm_error"，于是「401 重试 3 次白等 38 秒」在流水里
                // 与「429 退避后成功」长得一模一样，度量无从分辨。
                clock.llmEnded(
                    step = step,
                    attempt = lastAttempts,
                    durMs = System.currentTimeMillis() - llmStart,
                    ok = false,
                    code = failureCodeOf(e),
                )
                // 取消不是失败。点「停止」会取消本协程，CancellationException 从这里穿出去；
                // 原来一律打 ERROR，真机日志就留下「ERROR [LLM] 调用失败
                // kotlinx.coroutines.JobCancellationException」——用户看到红色 ERROR，
                // 以为刚才那下停止把 App 搞崩了。
                if (e is CancellationException) {
                    AgentLogger.i("LLM", "本次调用已取消（用户点停止）")
                } else {
                    AgentLogger.e("LLM", "调用失败", e)
                }
                throw e
            }
            // 成功也带真实尝试次数：>1 就是「靠重试救回来的请求」，
            // 这个比例决定退避序列该不该继续加长。
            clock.llmEnded(
                step = step,
                attempt = lastAttempts,
                durMs = System.currentTimeMillis() - llmStart,
                ok = true,
                calls = resp.toolCalls.size,
            )
            AgentLogger.i(
                "AgentLoop",
                "LLM 返回：${System.currentTimeMillis() - stepStart}ms，toolCalls=${resp.toolCalls.size}"
            )
            messages.add(resp)
            // 厂商明说「撞到长度上限」：这条回答就是不完整的，界面上却与「说完了」一模一样。
            // 只上报事实，不代模型续写——续不续由用户决定。
            if (FinishReason.isTruncated(resp.finishReason) && !truncatedNotified) {
                truncatedNotified = true
                onEvent?.invoke(AgentEvent.OutputTruncated(resp.finishReason))
            }
            checkpoint?.invoke(messages)

            // LLM 不再请求工具调用 -> 给出最终回答，结束循环
            if (resp.toolCalls.isEmpty()) {
                AgentLogger.i(
                    "AgentLoop",
                    "运行结束：${clock.elapsed()}ms，消息数=${messages.size}"
                )
                hooks.beforeStop()
                status.set("completed")
                return turnResult()
            }

            // R1「先落盘再执行」：把「模型声明了哪些调用」写进日志**之后**才允许执行。
            // 顺序反了的话，进程在这两条语句之间被杀就无法区分「没跑」与「跑了但没记」，
            // 只能保守假设全部可能已生效——每个卡死的回合都会退化成一次人工核对。
            turn?.assistantCommitted(step, resp.toolCalls)

            // T01 三段式之执行 + commit：本批工具执行（T03 并发判据在 executor/loop 协作下生效）
            val stepImages = mutableListOf<String>()
            // 已定案的调用结果暂存。批次被取消时 executeBatch 的**整个返回值丢失**
            // （addAll 在 try 之后），但已经跑完的调用有确定结局、没有疑问。
            // 若不带出来，闭合逻辑只看 messages 里有没有配对 ToolResult，就会把这些
            // 成功的调用当成「未执行」送进 onInterrupt 重判 -> cancelled_unknown，
            // 于是模型被告知「写操作可能已生效，先向用户确认」——对一次成功的读取，
            // 这是纯噪音；对一次成功的写入，则会让用户去核对一件已经完成的事。
            val settled = ConcurrentHashMap<String, ToolMessage>()
            val results = try {
                executeBatch(resp.toolCalls, stepImages, turnRegistry, turn, step, clock, settled)
            } catch (cancel: CancellationException) {
                // T05 树尾闭合：不信任取消时 execute() 的返回值——工具被 kill 后可能返回假成功。
                // 在 NonCancellable 内给本 Assistant 里所有**未配对**的 ToolCall 补错误 ToolResult，
                // 保证「已 commit 的 ToolCall 必有配对 ToolResult」，再重抛取消。
                withContext(NonCancellable) {
                    closePendingToolResults(
                        resp.toolCalls, messages, turnRegistry, turn, step, clock, settled
                    )
                }
                throw cancel
            }
            // 注：ToolExecutionFailedException 不在此捕获——回合级失败语义就是
            // 「整轮作废、不回喂」，原样上抛给调用方（业务 bug 显形）。
            messages.addAll(results)
            // 若本轮有工具返回图片（如 ui_screenshot），附到下一轮对话让视觉模型“看见”
            if (stepImages.isNotEmpty()) {
                messages.add(UserMessage("请看下面由工具返回的画面：", stepImages.toList()))
            }
            checkpoint?.invoke(messages)
            // W-10：压缩不再就地改 messages（旧实现 maybeCompact 会删掉 UI 已展示的工具卡片，
            // 且在「刚丢完又新增」之间来回抖动）——改为下一次请求时按预算构建只读视图，
            // 见 buildRequestMessages。此处保留 checkpoint，UI/落盘仍拿到完整历史。

            // R1：本步所有调用都已应答，关闭该步。修复器据此不再往这个步里补结果。
            turn?.stepClosed(step)
            // R5：本步收口事件。工具事件已成对发出（每个 tool.start 都有 tool.end），
            // 这一条把「本步一共跑了几个工具」标出来——排障时能一眼看出是哪一批卡住。
            clock.stepClosed(step, resp.toolCalls.size)

            // T05：每轮结束再复查一次（工具批可能执行很久，结束时 UI 可能已点停止）
            currentCoroutineContext().ensureActive()
            // 只有 shouldContinue 显式返回 false 才停；null（未配置）不影响流程
            if (!shouldContinueNow()) {
                AgentLogger.i("AgentLoop", "shouldContinue 返回 false，本轮中途收尾")
                hooks.beforeStop()
                status.set("stopped")
                messages.add(AssistantMessage("⏹ 已根据请求停止。"))
                return turnResult()
            }
        }

        AgentLogger.w("AgentLoop", "达到最大步数 $maxSteps，任务可能未完成")
        hooks.beforeStop()
        status.set("max_steps")
        messages.add(
            AssistantMessage(
                "⚠️ 本次任务已用满 $maxSteps 步（防止无限打转的硬上限），不是字数或 token 用完。\n" +
                    "回复「继续」即可接着做，历史和工作区都保留着。"
            )
        )
        AgentLogger.i(
            "AgentLoop",
            "运行结束：${clock.elapsed()}ms，消息数=${messages.size}"
        )
        return turnResult()
    }

    /** [AgentLoop.update]：按非 null 项更新配置，null = 保持现值。 */
    override fun update(
        systemPrompt: String?,
        memoryProvider: (() -> String)?,
        registry: ToolRegistry?,
        maxSteps: Int?
    ) {
        systemPrompt?.let { this.systemPrompt = it }
        memoryProvider?.let { this.memoryProvider = it }
        registry?.let { this.registry = it }
        maxSteps?.let { this.maxSteps = it }
        AgentLogger.i(
            "AgentLoop",
            "运行时配置已更新（systemPrompt/memoryProvider/registry/maxSteps 按非 null 项生效）"
        )
    }

    /**
     * 执行一批工具调用（T03 并发判据 + T04 同批重复判定 + T01 三段式执行/commit）。
     *
     * - 同批重复：按指纹预计算 duplicate 标志（只放行每指纹首个），后续同名同参调用
     *   直接熔断回错误，不真执行（判定在 [FailureGuard.check]，标志经
     *   [BatchToolExecutor] 重传 executor）。
     * - 含写操作（任一工具 !readOnly）→ 整批**串行**（for 顺序执行，日志可见顺序）；
     *    全批只读 → async + awaitAll **并行**（按 maxParallelTools 分块限流）。
     * - 保序 commit：两种路径的结果列表都与调用序一一对应（块序 + 块内 awaitAll 保序）。
     *
     * Phase 2 DoD④：[turnRegistry] 为本回合的工具视图（未裁剪 = 原 registry；裁剪时仅含
     * 暴露工具）。descriptor 解析、并发判据、executor 一律以它为界——被裁工具据此「查无此工具」。
     */
    private suspend fun executeBatch(
        calls: List<ToolCall>,
        images: MutableList<String>,
        turnRegistry: ToolRegistry,
        turn: TurnJournal.Turn?,
        step: Int,
        clock: TurnClock,
        // 每有一个调用产出终态 message 就登记，供取消路径取回（见 send() 中的说明）。
        settled: MutableMap<String, ToolMessage>
    ): List<ToolMessage> {
        val seenFingerprints = HashSet<String>()
        val duplicateFlags = calls.map { call ->
            val fp = failureGuard.fingerprint(call.name, call.arguments)
            !seenFingerprints.add(fp) // 已出现过 -> 同批重复
        }
        // T01 三段式之解析：descriptor 快照（不在本回合工具视图里 -> 合成占位，executor 内分流）。
        // Phase 2 DoD④：以「回合级工具视图」解析——被裁工具在此为 null，走「未启用」路径。
        val descriptors = calls.map { turnRegistry.descriptorOf(it.name) }
        val unknownCount = descriptors.count { it == null }
        if (unknownCount > 0) {
            AgentLogger.w("AgentLoop", "本批含 $unknownCount 个不在本回合工具视图的调用（分组未启用或名称写错）")
        }
        // 元工具 `__use_tools` 标了 `readOnly = true`，但那只是「不需要审批」的意思，
        // **不代表没有副作用**：它会就地修改 active 组集合。而 `batchHasMutation` 只看
        // `readOnly` 判串并行，于是模型一次发两个 `__use_tools` 时，两个协程会同时
        // `remove`/`addAll` 同一个 LinkedHashSet——轻则顺序错乱（LRU 失效），重则
        // ConcurrentModificationException。这里按名字补一条，把它归入串行（`__find_tools` 纯读，并行无害）。
        val hasMutation = turnRegistry.batchHasMutation(calls) ||
            calls.any { it.name == KernelExposure.USE_TOOLS }
        AgentLogger.i(
            "AgentLoop",
            "本批 ${calls.size} 个调用：hasMutation=$hasMutation（${if (hasMutation) "串行" else "并行"}）"
        )
        // R1：带上 turn/step——执行器要在审批之后、真正动手之前记「越过执行边界」
        val executor = toolExecutor(turnRegistry, turn, step)
        return if (hasMutation) {
            // 含写整批串行：单协程顺序执行，直接写 images 无并发问题；顺序确定，
            // 便于日志审计与用户预期
            calls.mapIndexed { i, call ->
                executeOne(
                    executor, call, descriptors[i], duplicateFlags[i], images, turn, step, clock, settled
                ).message
            }
        } else {
            // 全批只读：并行（分块限流，块间仍保序；块内 awaitAll 保序）
            // E1 修复：images（send() 的 stepImages）是普通 ArrayList，不能被多个
            // async 协程并发 addAll——并发写会丢更新（同索引互相覆盖）、 resize 时
            // 可能 ArrayIndexOutOfBoundsException、且无 happens-before 可见性无保证。
            // 改为每个 async 只写自己的局部列表，awaitAll 汇合后由父协程按调用序
            // 合并：数据竞争从构造上消除，收集顺序仍确定（块序 + 块内调用序）。
            calls.withIndex()
                .chunked(maxParallelTools)
                .flatMap { chunk ->
                    coroutineScope {
                        chunk.map { (i, call) ->
                            async {
                                val localImages = mutableListOf<String>()
                                executeOne(
                                    executor, call, descriptors[i], duplicateFlags[i], localImages,
                                    turn, step, clock, settled
                                ) to localImages
                            }
                        }.awaitAll()
                            .onEach { (_, localImages) -> images.addAll(localImages) }
                            .map { (completed, _) -> completed.message }
                    }
                }
        }
    }

    /**
     * 单步执行 + 过程事件上报（Hotfix2/T01）。
     *
     * ToolStarted 在真正执行前抛出（此时参数已定、结果未出，UI 显示「执行中」卡）；
     * ToolFinished 在产出终态 message 后抛出（UI 把同一张卡翻成终态）。
     * 并行执行时多个调用的事件会交错到达，UI 侧按 call.id 归并，不依赖顺序。
     *
     * 异常语义（T01 验收③）：
     *  - CancellationException 原样上抛（取消绝不是失败，由树尾闭合收尾）；
     *  - 其余异常 = executor 自身违约 → 包装 [ToolExecutionFailedException] 上抛，
     *    整个回合失败、该调用不回喂模型。
     */
    private suspend fun executeOne(
        executor: ToolExecutor,
        call: ToolCall,
        descriptor: ToolDescriptor?,
        duplicateInBatch: Boolean,
        images: MutableList<String>,
        turn: TurnJournal.Turn?,
        step: Int,
        clock: TurnClock,
        settled: MutableMap<String, ToolMessage>
    ): ToolCallOutcome.Completed {
        onEvent?.invoke(AgentEvent.ToolStarted(call))
        // R5：tool.start —— 排在**执行之前**。
        //
        // 位置很关键：排障要回答「卡在哪一次调用」，而「卡住」的表现恰恰是
        // 「只有 start 没有 end」。若 start 排在执行之后，卡住时就只剩上一条 end，
        // 定位到的会是**上一次成功的调用**——指向错误的现场。
        // callId（P5-1）：不带的话，同一步并行两个同名工具时配对只能靠猜
        clock.toolStarted(step, call.name, call.arguments.toString().take(120), call.id)
        val toolStart = System.currentTimeMillis()
        val outcome = try {
            val d = descriptor ?: ToolDescriptor(call.name, call.name, "{}")
            if (executor is BatchToolExecutor) {
                executor.execute(call, d, duplicateInBatch)
            } else {
                executor.execute(call, d)
            }
        } catch (cancel: CancellationException) {
            // 取消绝不是失败：绝不上包装修、绝不变 Failed，原样上抛
            throw cancel
        } catch (e: Throwable) {
            // executor 实现代码违约（NPE/Error/替身故意抛）：回合级失败，不回喂模型
            AgentLogger.e("AgentLoop", "executor 违约（${call.name}），回合失败", e)
            throw ToolExecutionFailedException(call.name, e)
        }
        if (outcome is ToolCallOutcome.Completed) {
            images.addAll(outcome.images)
            onEvent?.invoke(AgentEvent.ToolFinished(outcome.message))
            // R5：tool.end —— 与上面的 tool.start 配对。
            clock.toolEnded(
                step, call.name, outcome.code, outcome.message.isError,
                System.currentTimeMillis() - toolStart, call.id
            )
            // R1：有结局就落盘。业务失败也算「有结局」——日志层只关心「这条调用还会不会被补」，
            // 失败原因由 ToolMessage 携带，不进日志（日志是恢复依据，不是排障全文）。
            turn?.toolSettled(
                step,
                call.id,
                if (outcome.message.isError) ToolSettleStatus.FAILED else ToolSettleStatus.SUCCEEDED
            )
            // 登记「已定案」：同批后续调用若被取消，send() 里能把这条成功结果原样取回，
            // 不必让闭合逻辑重新判定它（重判只会得到 cancelled_unknown）。
            settled[call.id] = outcome.message
            return outcome
        }
        // 防御：execute() 契约规定只返回 Completed（业务失败也在内）。若实现违规
        // 返回中断态，按取消闭合补齐一条错误 ToolResult 后继续回合——不变量
        // 「已 commit 的 ToolCall 必有配对 ToolResult」不依赖 executor 自律。
        AgentLogger.w("AgentLoop", "executor 对 ${call.name} 返回了中断态 outcome，按闭合语义补齐")
        val filled = interruptToolMessage(call, outcome)
        onEvent?.invoke(AgentEvent.ToolFinished(filled))
        // R5：这条也要补 tool.end，否则时间线里留下一个没有终态的 start——
        // 而「有 start 无 end」正是「卡住」的判据，不能被防御路径污染。
        clock.toolEnded(
            step, call.name,
            if (outcome is ToolCallOutcome.Unknown) "cancelled_unknown" else "cancelled",
            true, System.currentTimeMillis() - toolStart, call.id
        )
        // 防御路径同样有确定的终态 message，一并登记——它已经不会再变了。
        val closed = ToolCallOutcome.Completed(filled, filled.images)
        settled[call.id] = filled
        return closed
    }

    /**
     * T05 树尾闭合：给 [calls] 中所有**未配对**（历史里还没有同 id ToolMessage）的
     * ToolCall 补一条错误 ToolResult，并抛 ToolFinished 事件把 UI「执行中」卡翻终态
     * （不存在永远转圈的工具卡）。
     *
     * T01：每个未配对调用先经 [ToolExecutor.onInterrupt] 自判 Interrupted/Unknown
     * （只读→确定未完成；写→可能已有副作用），文案据此区分 code=cancelled 与
     * code=cancelled_unknown——后者明确要求模型先向用户确认再决定是否重试。
     *
     * 不变量保证点：本方法是 send() 取消路径的唯一收尾——调用后 messages 中每个已
     * commit 的 Assistant.toolCalls 元素都必有配对 ToolMessage，历史再发请求不会
     * 因「未闭合 tool_call」被 API 拒绝。
     */
    private suspend fun closePendingToolResults(
        calls: List<ToolCall>,
        messages: MutableList<ChatMessage>,
        turnRegistry: ToolRegistry,
        turn: TurnJournal.Turn?,
        step: Int,
        clock: TurnClock,
        // 已产出终态的调用（id -> 结果）。非空即「跑完了，只是没来得及入 messages」。
        settled: Map<String, ToolMessage> = emptyMap()
    ) {
        val paired = messages.filterIsInstance<ToolMessage>().mapTo(HashSet()) { it.id }
        val executor = toolExecutor(turnRegistry)
        var filled = 0
        var recovered = 0
        for (call in calls) {
            if (call.id in paired) continue
            // 先看是否已经跑完。批次被取消时 addAll(results) 没有执行，这些结果
            // 只存在于 settled 里。它们的 tool.end 与终态落盘在 executeOne 中已经做过，
            // 这里只需把 message 补进历史，不能重判——重判会把成功改写为 cancelled_unknown。
            val done = settled[call.id]
            if (done != null) {
                messages.add(done)
                recovered++
                continue
            }
            val descriptor = turnRegistry.descriptorOf(call.name) ?: ToolDescriptor(call.name, call.name, "{}")
            val verdict = executor.onInterrupt(call, descriptor)
            val msg = interruptToolMessage(call, verdict)
            messages.add(msg)
            onEvent?.invoke(AgentEvent.ToolFinished(msg))
            // R5：取消闭合也要补 tool.end。这些调用在时间线里已经有 tool.start
            // （执行前埋的），若这里不补终态，时间线就会留下「有 start 无 end」的
            // 假卡点——把「用户点了停止」误报成「卡住了」。
            clock.toolEnded(
                step, call.name,
                if (verdict is ToolCallOutcome.Unknown) "cancelled_unknown" else "cancelled",
                true, 0L, call.id
            )
            // R1：取消也要落终态。否则重启后修复器看到「已派发、无结果」，
            // 会把它判成 OUTCOME_UNKNOWN——用户明明点了停止，下一轮却被要求「先核对」。
            // 执行层已经给出判定（只读确定未完成 / 写操作结局未知），日志照抄即可。
            turn?.toolSettled(step, call.id, ToolSettleStatus.fromOutcome(verdict))
            filled++
        }
        if (filled > 0 || recovered > 0) {
            checkpoint?.invoke(messages)
            AgentLogger.w(
                "AgentLoop",
                "取消闭合：补填 $filled 个待决 ToolResult，回收 $recovered 个已完成结果"
            )
        }
    }

    /**
     * 单次 LLM 调用（T01 流式通道）：收集 [LLMClient.streamChat]——
     * TextDelta 转 [onTextDelta] 回调（UI 打字机，不进历史），Completed 整条
     * commit（进历史的唯一形态）。未覆盖流式的客户端走默认实现（chat() +
     * 单条 Completed），行为与旧裸 chat() 调用等价。
     *
     * R-09：重试以「整次收集」为粒度，已发出的增量无法回滚。所以 [callWithRetry]
     * 在可见输出超过 [RETRY_VISIBLE_CHARS] 后放弃重试（详见 RetryVisibility.kt）。
     */
    private suspend fun chatOnce(messages: List<ChatMessage>, exposure: KernelExposure.Plan): AssistantMessage {
        var completed: AssistantMessage? = null
        attemptOut.reset()
        // R2 第一层：整次「收集一条完整回答」的预算。端侧放宽到 3 倍，见构造参数注释。
        val budget = if (llmProvider().id.startsWith("local-")) llmCallTimeoutMs * 3 else llmCallTimeoutMs
        val streamFinished = withTimeoutOrNull(budget) {
            // 发给 LLM 的工具定义用**本轮**暴露面（默认 file 组 + 元工具，均含元工具）。
            // 与系统提示同源的部分只在首轮一致；后续轮次因 __use_tools 启用新组而扩大，
            // 新组的用法由工具 description 自解释，系统提示不再重拼（保 KV 前缀稳定）。
            llmProvider().streamChat(messages, exposure.visible).collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> { onTextDelta?.invoke(event.text); attemptOut.add(event.text) }
                    // 功能五（P1-9）：推理过程增量与正文分流上抛（UI 折叠块实时渲染），
                    // 同样不进历史——进历史的只有 Completed 整条（message.reasoning）
                    is ChatStreamEvent.ReasoningDelta -> onReasoningDelta?.invoke(event.text)
                    is ChatStreamEvent.Completed -> completed = event.message
                }
            }
            true
        } ?: false
        // 超时与「流结束但没给 Completed」是两件事，必须分开报：前者是环境问题，
        // 后者是供应商违反流式契约——混成一句「超时」会让排查方向跑偏。
        if (!streamFinished) {
            AgentLogger.w("LLM", "单次调用超时：${budget}ms（provider=${llmProvider().id}）")
            throw LlmCallTimeoutException(budget)
        }
        return completed
            ?: throw IllegalStateException("streamChat 未产生 Completed 事件（供应商违反流式契约）")
    }

    /**
     * 失败 → 机器可读码（P5-1）。
     *
     * 与 `AgentLogger` 里那句话（`第 2 次调用失败，准备重试：...`）的分工：
     * 那句话给人看，这个码给脚本分类。**码的取值域就是
     * [ErrorNormalizer.FailureClass]**（小写）——不另造一套词汇表，否则
     * 「什么样算确定性错误」会出现两个互相矛盾的答案。
     *
     * 度量脚本靠它筛出「本该 1 秒内失败」的那批调用：
     * `auth` / `quota` / `bad_request` 三类都不该重试。
     */
    private fun failureCodeOf(e: Throwable): String = when (e) {
        is CancellationException -> "cancelled"
        is LlmHttpException ->
            ErrorNormalizer.classify(e.code, e.serverBody).cls.name.lowercase(java.util.Locale.ROOT)
        is LlmCallTimeoutException -> "timeout"
        is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "network"
        else -> "llm_error"
    }

    /**
     * 供应商瞬时失败重试（429/5xx 与网络异常才重试）。
     *
     * 401/403/400 重试多少次都不会变，必须一次快败，否则填错 Key 要白等 providerRetry 次。
     *
     * **Phase 2 DoD③：重试过程对 UI 可见**。每次「准备重试」抛
     * [AgentEvent.RetryStarted]（attempt/max/reason）；本轮最终成功 / 最终失败 /
     * 用户取消时**恰好一次**抛 [AgentEvent.RetryFinished] 收尾——由 `finally`
     * 统一保证（含取消路径），UI 据此清除「正在恢复」横幅。
     *
     * 语义边界：
     *  - 「准备重试」只在**确实还有下一次尝试**时抛出（`attempt < providerRetry`），
     *    故 attempt 序号 1..providerRetry，绝不出现 attempt > max；总请求数 ≤ max + 1。
     *  - 首次即成功、或非可重试错误快败时**从不**抛 RetryStarted（无状态可清），
     *    收尾的 `retrying` 标志同时守护 RetryFinished 不空抛。
     *  - 取消（CancellationException）原样上抛、绝不重试；`finally` 立即清状态——
     *    T05 不变量：用户点停止后不得残留「正在恢复」。
     *
     * **退避**：宣布「准备重试」后、重发之前先走 [retryBackoff]（3s → 10s → 25s 封顶）。
     * `UnknownHostException`（飞行模式）是瞬时返回的，无退避时三次尝试几十毫秒打完，
     * 「正在恢复」刚置位就被清掉；退避也给了网络真正的恢复时间。
     * 不可重试错误（401/403/400）不等待、一次即失败。
     *
     * **网络恢复即刻重试**：默认退避钩子由 [NetworkRecoveryWaiter] 监听默认网络，
     * 一恢复就提前结束等待（关掉飞行模式后「秒续」）；监听在等待结束时注销。
     *
     * **取消优先**：退避期间被取消必须立刻退出（[delay] 自带取消）；钩子返回后再补一次
     * `ensureActive()`——即使是 `Thread.sleep` 这种不可取消钩子，也不会多跑一次无用请求。
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        val profile = retryProfileProvider()
        // R10：次数来自档位；显式传非负值可以覆盖（harness 靠它精确控制尝试次数）
        val maxRetries = if (providerRetry >= 0) providerRetry else profile.maxRetries
        // P5-1：本步实际尝试了几次。写进 llm.end 的 attempt 字段——
        // 修前那里写死 `attempt = 1`，于是「401 白等 38 秒」在流水里看起来
        // 只是一次很慢的成功调用，度量脚本无从判断它本该 1 秒内失败。
        lastAttempts = 1
        var last: Throwable? = null
        // 本轮是否对外抛出过重试状态（决定收尾是否要抛 RetryFinished）
        var retrying = false
        try {
            repeat(maxRetries + 1) { attempt ->
                lastAttempts = attempt + 1
                try {
                    return block()
                } catch (cancel: CancellationException) {
                    // 取消立即上抛，绝不重试；finally 会立刻清掉「正在恢复」
                    throw cancel
                } catch (e: Throwable) {
                    last = e
                    // R-09：不可重试，或已上屏一大段（整次重来只会让同一段再长一遍）
                    val retryable = isRetryable(e, profile)
                    if (!retryAllowed(retryable, attemptOut)) {
                        AgentLogger.w(
                            "LLM",
                            "放弃重试（可重试=$retryable，已输出 ${attemptOut.chars} 字符，第 ${attempt + 1} 次）：" +
                                e.message?.take(160)
                        )
                        throw e
                    }
                    AgentLogger.w(
                        "LLM",
                        "第 ${attempt + 1} 次调用失败，准备重试：${e.message?.take(160)}"
                    )
                    // 仅当确实还有下一次尝试时才通知 UI「准备重试」（attempt 为 1-based 重试序号）
                    if (attempt < maxRetries) {
                        onEvent?.invoke(
                            AgentEvent.RetryStarted(attempt + 1, maxRetries, retryReason(e))
                        )
                        retrying = true
                        // 先公告再退避：让「正在恢复（n/N）」有稳定可见时长，并留出恢复窗口
                        // hotfix10：429 限流走专用序列（10s/30s/60s + 尊重 Retry-After），
                        // 不再用 3s/10s 通用序列硬刚限流（真机 19:05 连续 3 次 429 挂掉的根因）。
                        val rateLimited = (e as? LlmHttpException)?.takeIf { it.code == 429 }
                        if (rateLimited != null) {
                            rateLimitBackoff(attempt + 1, rateLimited.retryAfterMs)
                        } else {
                            retryBackoff(attempt + 1)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            throw last ?: IllegalStateException("未知错误")
        } finally {
            // 成功 / 最终失败 / 取消——只要抛出过重试状态就必须收尾清除（T05：取消不残留「正在恢复」）
            if (retrying) onEvent?.invoke(AgentEvent.RetryFinished)
        }
    }

    /**
     * 可重试错误 → 面向用户的一句话原因（横幅「正在恢复（n/N）· 原因」用）。
     * 纯函数：仅依赖异常类型与 HTTP 状态码，**绝不回显**可能夹带 Key 的原始 message。
     *
     * 429 额外带上服务端给的原因（`extractServerHint`）：真机 agnes 的 body 里写着
     * 「请登录platform进行实名认证以获取更高rpm」——这句话才是用户该看的，
     * 只说「请求过于频繁」等于让人对着墙重试。
     */
    private fun retryReason(e: Throwable): String = when {
        e is LlmHttpException && e.code == 429 ->
            com.beichen.polaris.core.extractServerHint(e.serverBody)
                ?.let { "请求过于频繁（429）· $it" }
                ?: "请求过于频繁（429）"
        e is LlmHttpException && e.code >= 500 -> "服务端错误（${e.code}）"
        e is LlmHttpException -> "HTTP ${e.code}"
        e is java.net.UnknownHostException -> "网络不可达"
        e is java.net.SocketTimeoutException -> "网络超时"
        e is java.net.ConnectException -> "连接失败"
        e is java.io.IOException -> "网络异常"
        else -> "暂时故障"
    }

    /**
     * 是否值得重试：[LlmHttpException] 自身决定（429/5xx 可重试，401/403/400 不可），
     * 其余（超时、DNS、连接中断等网络 IO 异常）一律视为可重试。
     */
    /**
     * 这个异常值不值得重试（R10：按当前供应商的档位判定）。
     *
     * 三层判据，顺序不能换：
     *  1. 明确不该重试的异常类型（调用超时 / 本地推理）直接否——它们与供应商无关；
     *  2. HTTP 错误先经 [ErrorNormalizer] 归一化成类别（状态码 → 结构化字段 → 关键词）；
     *  3. 类别落到档位上：额度类看 `allowQuotaRetry`，其余类别默认可否，
     *     但**档位的码集合是逃生门**——判成 UNKNOWN/BAD_REQUEST 而原始状态码在集合里时仍重试。
     *
     * 为什么要有逃生门：归一化是新逻辑，误判的代价是「本该重试的被快败」。
     * 留一条「档位说这个码可以重试」的后路，比让新逻辑单点决定更安全。
     */
    private fun isRetryable(e: Throwable, profile: RetryProfile): Boolean = when (e) {
        // R2：一次调用超时说明连接已挂死，重试只会把 300 秒再等一遍（×4 = 20 分钟）。
        is LlmCallTimeoutException -> false
        // 本地推理失败是确定性的：同一段 prompt + 同一个 native 库，重试只是再失败一次。
        // 走退避重试会让用户干等 3s+10s 才看到一句「出错了」。
        is com.beichen.polaris.core.llm.local.LocalInferenceException -> false
        is LlmHttpException -> {
            val verdict = ErrorNormalizer.classify(e.code, e.serverBody)
            // 第三层（关键词）命中时记一条：这一层最容易误判，没有这条日志就永远不知道
            // 它是救过命还是在乱判。provider 取不到时降级成 "?"，绝不因为记日志而抛异常。
            if (verdict.fromKeyword) {
                val pid = runCatching { llmProvider().id }.getOrDefault("?")
                AgentLogger.w(
                    "LLM",
                    "provider.misclassified id=$pid code=${e.code} class=${verdict.cls} " +
                        "evidence=${verdict.evidence.take(40)}"
                )
            }
            when {
                verdict.cls == ErrorNormalizer.FailureClass.QUOTA -> profile.allowQuotaRetry
                // 逃生门：归一化说「不该重试」，但档位认这个状态码
                !ErrorNormalizer.isRetryableByDefault(verdict.cls) ->
                    profile.isRetryableCode(e.code)
                else -> true
            }
        }
        else -> true
    }

    /**
     * 每批工具执行前取一次 executor（默认按**当前**字段构造，update(registry) 即时生效）。
     *
     * Phase 2 DoD④：默认 executor 绑定的 registry 为「回合级工具视图」[reg]——未裁剪时
     * 即原 `registry`（行为不变）；裁剪时仅含暴露工具，被裁工具走「未启用」路径（靠
     * [RegistryToolExecutor] 的 fullRegistry 参数与全量表对照判定）。
     * executorFactory 注入（测试替身）优先级不变，忽略 [reg]。
     */
    private fun toolExecutor(reg: ToolRegistry, turn: TurnJournal.Turn? = null, step: Int = 0): ToolExecutor =
        executorFactory?.invoke() ?: RegistryToolExecutor(
            reg,
            failureGuard,
            approval,
            hooks,
            // W-11：结果上限与上下文预算对齐——本地 2k 预算下 24k 字符的一条结果就撑爆 n_ctx
            maxResultChars = ContextBudget.toolResultLimitChars(llmProvider().providerId, contextBudgetTokens),
            // R1：把「越过执行边界」的记账点交给执行器——放在审批之后，见 beforeEffect 注释
            beforeEffect = { call -> turn?.toolDispatched(step, call.id) ?: true },
            // fullRegistry：让执行器区分「工具不存在」与「存在但分组未启用」
            fullRegistry = registry
        )

    /**
     * 计算本回合的工具暴露面（委托 [KernelExposure.plan]）。
     *
     * provider 决定档位：W-102 起两者默认面都是 file 组 + 元工具，差别只在 mode
     * （端侧 META 更强调「先查再开」）；[forceFullTools] = true 时全量（开发者开关）。
     *
     * @param active 已启用的组；**会被就地修改**（元工具 `__use_tools` 执行时写入）。
     *   由 [RealAgentLoop.activeGroups] 跨回合持有时即「粘性激活」——开过的组不会
     *   下一轮就消失，只会在撞上限时被滞回淘汰。
     */
    private fun currentExposure(active: MutableSet<String>): KernelExposure.Plan =
        KernelExposure.plan(registry.list(), llmProvider().providerId, forceFullTools, active)

    /**
     * 由暴露面构造「回合级工具视图 registry」：
     *  - 未裁剪（全量）→ 原样返回 [registry]，零开销；
     *  - 发生裁剪 → 新建仅含暴露工具的 registry，使被裁工具在该视图内「查无此工具」，
     *    从而 descriptor 解析为 null、executor 走「未启用」路径（指出所属组 + 开组指引），不抛异常。
     *
     * 注：仅影响本回合的「工具视图」，全局 [registry]（Skill 桥等复用）不变。
     */
    private fun scopedRegistry(plan: KernelExposure.Plan): ToolRegistry =
        if (!plan.trimmed) {
            registry
        } else {
            ToolRegistry().apply { plan.visible.forEach { register(it) } }
        }

    /**
     * 裁剪发生时打一条 INFO：写明模式、暴露数、裁掉数与已启用组。
     * 未裁剪时不打——避免每回合刷屏。
     */
    private fun logExposure(plan: KernelExposure.Plan) {
        if (!plan.trimmed) return
        AgentLogger.i(
            "ToolExposure",
            "工具面裁剪：provider=${llmProvider().id} mode=${plan.mode} 暴露 ${plan.visible.size} 个（含 2 个元工具），" +
                "裁掉 ${plan.hiddenCount} 个；已启用组=${plan.activated.joinToString(",")}"
        )
    }

    /**
     * 续跑判定（T05）：只有显式 shouldContinue 能要求「本轮跑完就收尾」。
     * null（未配置）→ 继续。
     *
     * 曾经这里还与 `stopRequested` 并列（接口上那个已删的 stop() 实现）。R-19 删掉那条
     * 通道：生产从未调用，留着只会让人误以为「有个优雅停止在用」。真正的停止是
     * `Job.cancel()`，见 [AgentLoop] 的注释。
     */
    private fun shouldContinueNow(): Boolean = shouldContinue?.invoke() ?: true

    /**
     * 系统提示分段拼装（[PromptAssembler]）：按「变化频率」排三段，而不是拼成一整块。
     *
     * - **FROZEN**：身份与规则（[systemPrompt]），整个会话不变，排在最前；
     * - **SESSION**：本回合工具面的用法说明，只随工具面变（`__use_tools` 激活新组时会变）；
     * - **TURN**：记忆摘要与使用引导，随时可能变，必须压在最后。
     *
     * 这样端侧推理的 KV 前缀最坏也能复用「FROZEN + SESSION」这一段；段内顺序由数据
     * 决定（工具按暴露顺序、段按固定 key），同内容两次组装逐字节一致。
     *
     * 记忆段只在**确实有摘要**时才拼：没有可读记忆还教一遍 memory_usage，等于白占
     * 上下文，小模型的注意力会被这条用不上的指令分走。
     *
     * Phase 2 DoD④：以 [tools]（= 本回合工具面）而非全量 `registry.list()` 拼接——
     * 工具被裁掉后，其 systemPrompt 片段也一并消失，避免出现「你可以调 ui_swipe」这类
     * 已经不给的提示（否则模型会调不存在的工具）。
     */
    private fun buildSystemPrompt(tools: List<AgentTool>): Prompt {
        val sections = ArrayList<Section>(4)
        if (systemPrompt.isNotBlank()) {
            sections += Section("identity", systemPrompt, Stability.FROZEN)
        }
        val hints = tools.mapNotNull { it.systemPrompt.takeIf { s -> s.isNotBlank() } }
        if (hints.isNotEmpty()) {
            sections += Section("tools", hints.joinToString("\n\n"), Stability.SESSION)
        }
        val summary = memoryProvider?.let { runCatching { it() }.getOrDefault("") }.orEmpty().trim()
        if (summary.isNotEmpty()) {
            sections += Section("memory", summary, Stability.TURN)
            sections += Section("memory-usage", MEMORY_USAGE_PROMPT, Stability.TURN)
        }
        val prompt = PromptAssembler.assemble(sections)
        logPromptPrefix(prompt)
        return prompt
    }

    /** 上一次组装出的稳定前缀指纹（跨回合，仅本实例）。 */
    private var lastPromptSignature: String? = null

    /**
     * 前缀指纹变化时留一条证据：用户反馈「这一轮特别慢」时，看这里就能区分
     * 「只是 TURN 段在变（正常）」还是「规则段被改了、整段前缀作废（要查）」。
     */
    private fun logPromptPrefix(p: Prompt) {
        val prev = lastPromptSignature
        lastPromptSignature = p.signature
        when {
            prev == null ->
                AgentLogger.i(
                    "Prompt",
                    "提示组装：${p.chars} 字符，稳定前缀 ${p.signature}（FROZEN 段 ${p.frozenChars} 字符）"
                )
            prev != p.signature ->
                AgentLogger.i(
                    "Prompt",
                    "提示前缀已变（$prev → ${p.signature}）：KV 复用被截断在 FROZEN 之后，" +
                        "本轮变化段=${p.turnKeys.joinToString(",").ifEmpty { "无" }}"
                )
            else -> AgentLogger.d("Prompt", "提示前缀未变（${p.signature}），KV 可整段复用 ${p.chars} 字符")
        }
    }

    /**
     * W-10：为本轮 LLM 请求构建**只读视图**——先剥旧截图，再按 token 预算压历史。
     *
     * 顺序有意为之：先剥图再算预算。一张 768px JPEG 的 base64 约 6~12 万字符，若先按
     * 字符预算压历史，会把「只是图片大」误判成「文字太多」，白白丢掉有用的工具结果。
     *
     * 产物只用于 `chatOnce`，不写回 [runTurn] 的 messages——取消/落盘/UI 仍是完整历史。
     * 无超限时 [ContextBudget] 原样返回入参（零分配）。
     *
     * @param turnInputIndex 本轮输入消息在 [messages] 里的下标，作为「不裁图」的锚点
     *   （W-49：不传则退回「首条 UserMessage」，多轮会话下会保护错对象）
     */
    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        turnInputIndex: Int? = null
    ): List<ChatMessage> {
        // 本地 llama.cpp 默认 n_ctx=4096，不能用云端预算——按通道自适应（见 ContextBudget）
        // R10：云端按窗口收敛。R11：用 providerId——id 是协议名，28 家都叫 "openai"，查窗口恒得 0
        val pid = llmProvider().providerId
        val budget = ContextBudget.budgetForProvider(
            pid, contextBudgetTokens, com.beichen.polaris.core.llm.ProviderCatalog.contextWindowOf(pid)
        )
        val pruned = ContextBudget.pruneImages(
            messages,
            keepLast = keepRecentImages,
            anchorIndex = turnInputIndex
        )
        val fitted = ContextBudget.fitToBudget(
            pruned,
            budgetTokens = budget,
            maxMessages = maxHistoryMessages
        )
        if (fitted.size != pruned.size || pruned.size != messages.size) {
            // 折叠与单纯丢弃要能分开看：前者留了摘要，后者什么也没留。用户问「它怎么忘了
            // 前面做过什么」时，先看这一行有没有「已折叠」。
            val folded = fitted.any { it is UserMessage && it.content.startsWith(ContextBudget.FOLD_MARK) }
            AgentLogger.i(
                "ContextBudget",
                "请求视图：${messages.size} 条 / 估算 ${ContextBudget.totalTokens(messages)} tok" +
                    " → ${fitted.size} 条 / 估算 ${ContextBudget.totalTokens(fitted)} tok" +
                    "（预算 $budget tok，${if (folded) "早期对话已折叠" else "早期对话已丢弃"}）"
            )
        }
        return fitted
    }
}

/**
 * 供应商重试退避策略（纯函数，零 Android 依赖——QA harness 可直接对校验发货单）。
 *
 * **可配常量序列 [BACKOFF_SEQUENCE_MS] = 3s → 10s → 25s**（真机第三轮 Bug A1）：
 *  - 第 1 次重试等 3s：足够 Compose 渲染「正在恢复（1/N）」横幅，也覆盖一次短暂抖动；
 *  - 第 2 次等 10s：给 Wi-Fi / 移动网络一个真实的重连窗口（用户原话「等个半分钟一分钟」）；
 *  - 第 3 次起 25s 封顶：配合「网络恢复即刻重试」，真正断网时不会干等满额。
 * 注：默认 providerRetry=2 → 实际等待 3s + 10s（第 3 次失败后不再等待）；
 * 序列三项之和 38s 需 providerRetry=3 才会走满（见 scripts/qa/HOTFIX3-NOTES.md）。
 *
 * 公开（而非 internal）：[RealAgentLoop] 的构造默认值需引用它，且 QA harness
 * 要脱离 Android 环境直接断言退避毫秒序列（故本对象零 Android / 零 ViewModel 依赖）。
 */
object RetryBackoffPolicy {

    /** 退避基数（第 1 次重试的等待毫秒）。 */
    const val BASE_MS: Long = 3_000L

    /** 单次退避上限（序列最后一项）。 */
    const val MAX_MS: Long = 25_000L

    /**
     * 退避序列（可配常量）：第 n 次重试取第 n 项，超出长度一律取末项封顶。
     * 改这一处即可整体调节「等多久」，无需动重试循环。
     */
    val BACKOFF_SEQUENCE_MS: List<Long> = listOf(3_000L, 10_000L, 25_000L)

    /**
     * **429 限流专用**退避序列（hotfix10）：第 n 次重试取第 n 项，越界取末项封顶。
     * 限流要「等得久、试得少」——通用序列 3s/10s 对限流等于没等（真机 19:05 实证：
     * 连续 3 次 429 后整个回合直接失败）。
     */
    val RATE_LIMIT_SEQUENCE_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L)

    /** 429 单次退避封顶（含服务端 Retry-After 也不得超过它，防坏头值卡死回合）。 */
    const val MAX_RATE_LIMIT_MS: Long = 60_000L

    /**
     * 第 [attempt] 次重试（1-based）前应等待的毫秒数：取 [BACKOFF_SEQUENCE_MS] 的第
     * [attempt] 项（越界取末项封顶）；非法序号（≤0）按第 1 次处理，避免调用方传错导致
     * 永久忙等。
     */
    fun backoffMillis(attempt: Int): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        return BACKOFF_SEQUENCE_MS.getOrElse(index) { MAX_MS }.coerceAtLeast(0L)
    }

    /**
     * **429 限流专用**退避（hotfix10）：[RATE_LIMIT_SEQUENCE_MS] = 10s → 30s → 60s 封顶。
     *
     * 为什么单列：限流不是网络抖动——用 3s/10s 的通用序列硬刚只会连续再挨两次 429
     * （真机 19:05 实证：3s/10s 后第 3 次 429 直接整个回合失败）。限流的正确姿势是
     * 「等得久、试得少」，故序列显著拉长；同时尊重服务端 `Retry-After`：
     * 若服务端要求等更久，取两者较大值（再封顶 [MAX_RATE_LIMIT_MS]，防坏头值卡死）。
     *
     * @param attempt 1-based 重试序号。
     * @param retryAfterMs 服务端 `Retry-After` 毫秒（0 = 未提供）。
     */
    fun rateLimitBackoffMillis(attempt: Int, retryAfterMs: Long = 0L): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        val base = RATE_LIMIT_SEQUENCE_MS.getOrElse(index) { MAX_RATE_LIMIT_MS }
        val serverAsk = retryAfterMs.coerceAtLeast(0L)
        return maxOf(base, serverAsk).coerceAtMost(MAX_RATE_LIMIT_MS)
    }

    /**
     * 默认退避钩子（纯定时，无网络监听）：按 [backoffMillis] 挂起等待
     * （[kotlinx.coroutines.delay] 自带取消语义——退避途中取消会立刻以
     * CancellationException 返回）。
     */
    val DEFAULT: suspend (attempt: Int) -> Unit = networkAware(::backoffMillis, NetworkRecoveryWaiter.TIMER_ONLY)

    /**
     * 网络感知的退避钩子：[RealAgentLoop] 的默认退避由本函数组装。
     *
     * 计划等待 [plan] 毫秒，但期间交给 [waiter] 监听——网络恢复则**提前返回**并立刻
     * 发起下一次尝试；返回前等待器负责注销监听。等待结束时记一条 INFO（计划时长 +
     * 是被恢复打断还是等满），方便真机排查「等了多久、为什么提前重试」。
     */
    fun networkAware(
        plan: (attempt: Int) -> Long,
        waiter: NetworkRecoveryWaiter
    ): suspend (attempt: Int) -> Unit = { attempt ->
        val plannedMs = plan(attempt).coerceAtLeast(0L)
        val interrupted = waiter.awaitRecovery(plannedMs)
        AgentLogger.i(
            "LLM",
            "第 $attempt 次重试退避结束：计划 ${plannedMs}ms，" +
                (if (interrupted) "网络已恢复 → 立即重试" else "等待满额 → 按序列重试")
        )
    }

    /**
     * 429 限流专用版 [networkAware]（hotfix10）：计划毫秒由
     * [rateLimitPlan]（attempt + 服务端 Retry-After）给出，其余语义一致
     * （网络恢复可提前结束等待；等待结束记 INFO）。
     */
    fun networkAwareRateLimit(
        plan: (attempt: Int, retryAfterMs: Long) -> Long,
        waiter: NetworkRecoveryWaiter
    ): suspend (attempt: Int, retryAfterMs: Long) -> Unit = { attempt, retryAfterMs ->
        val plannedMs = plan(attempt, retryAfterMs).coerceAtLeast(0L)
        val interrupted = waiter.awaitRecovery(plannedMs)
        AgentLogger.i(
            "LLM",
            "第 $attempt 次重试（429 限流）退避结束：计划 ${plannedMs}ms" +
                (if (retryAfterMs > 0) "（服务端 Retry-After=${retryAfterMs}ms）" else "") +
                (if (interrupted) "，网络已恢复 → 立即重试" else "，等待满额 → 按序列重试")
        )
    }
}
