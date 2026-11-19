package com.beichen.polaris.core.runtime

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.Consent
import com.beichen.polaris.core.DEFAULT_SYSTEM_PROMPT
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.AgentLoop
import com.beichen.polaris.core.agent.AgentSeams
import com.beichen.polaris.core.agent.AttemptOutputTracker
import com.beichen.polaris.core.agent.ContextBudget
import com.beichen.polaris.core.agent.Hooks
import com.beichen.polaris.core.agent.NetworkRecoveryWaiter
import com.beichen.polaris.core.agent.NoopHooks
import com.beichen.polaris.core.agent.NoopTurnObserver
import com.beichen.polaris.core.agent.RealAgentLoop
import com.beichen.polaris.core.agent.RetryBackoffPolicy
import com.beichen.polaris.core.agent.ToolExecutor
import com.beichen.polaris.core.agent.TurnObserver
import com.beichen.polaris.core.agent.journal.RunScope
import com.beichen.polaris.core.agent.journal.TurnJournal
import com.beichen.polaris.core.llm.RetryProfile

/**
 * `RealAgentLoop` 的**唯一生产构造点**（v4 §3.2 不变量 1）。
 *
 * ## 为什么这层不是无意义包装
 *
 * 改造前，主界面 / 桌宠 / 子代理三处各自 `RealAgentLoop(...)`，谁构造了 loop、
 * 该不该绑定 runtime、source 是什么，全由调用方自决。不变量 1 要的不是「少写几行」，
 * 而是把**「谁拥有 loop 的构造」**这个所有权，从三个入口收拢到一个点：
 *
 * - 将来 loop 构造时统一绑定 runtime / [RunSource] / 事件投影，只改这一处；
 * - 任何「加一个构造参数」的需求，都从「三处都要改」变成「改这里 + 改 [RealAgentLoop]」——
 *   与 `ToolCatalog`（加工具改哪几处）是同一纪律：单一真源。
 *
 * ## 参数与 [RealAgentLoop] 构造签名逐字对齐
 *
 * 本 `create` 的参数与默认值**完整复刻** [RealAgentLoop] 的构造参数（含默认值引用前置
 * 参数的顺序），否则会有人因为「工厂漏了一个参数」而绕回直接 `RealAgentLoop(...)`，
 * 不变量 1 随即失守。将来 [RealAgentLoop] 加参数，这里必须同步加——这条由静态门禁
 * `run-runtime-invariants-verification.sh` 的 C1 盯住（生产代码里 `RealAgentLoop(` 只许
 * 出现在本文件）。
 *
 * 返回 [AgentLoop] 接口而非具体类：收口之后 ui / pet / entry 只依赖抽象，
 * 不再 import `RealAgentLoop`（不变量 2）。
 */
object AgentLoopFactory {

    fun create(
        llmProvider: () -> LLMClient,
        registry: ToolRegistry,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxSteps: Int = 60,
        maxParallelTools: Int = 5,
        providerRetry: Int = -1,
        retryProfileProvider: () -> RetryProfile = { RetryProfile.DEFAULT },
        maxHistoryMessages: Int = 40,
        contextBudgetTokens: Int = ContextBudget.DEFAULT_BUDGET_TOKENS,
        keepRecentImages: Int = ContextBudget.DEFAULT_KEEP_IMAGES,
        consent: Consent = Consent.unset("RealAgentLoop"),
        checkpoint: ((List<ChatMessage>) -> Unit)? = null,
        journal: TurnJournal? = null,
        journalTurn: TurnJournal.Turn? = null,
        scope: String = RunScope.MAIN,
        memoryProvider: (() -> String)? = null,
        onEvent: ((AgentEvent) -> Unit)? = null,
        shouldContinue: (() -> Boolean)? = null,
        onTextDelta: ((String) -> Unit)? = null,
        onReasoningDelta: ((String) -> Unit)? = null,
        attemptOut: AttemptOutputTracker = AttemptOutputTracker(),
        hooks: Hooks = NoopHooks,
        seams: AgentSeams = AgentSeams.NONE,
        observer: TurnObserver = NoopTurnObserver,
        executorFactory: (() -> ToolExecutor)? = null,
        forceFullTools: Boolean = false,
        activeGroups: MutableSet<String>? = null,
        networkRecovery: NetworkRecoveryWaiter = NetworkRecoveryWaiter.TIMER_ONLY,
        backoffPlan: (attempt: Int) -> Long = RetryBackoffPolicy::backoffMillis,
        rateLimitPlan: (attempt: Int, retryAfterMs: Long) -> Long =
            RetryBackoffPolicy::rateLimitBackoffMillis,
        rateLimitBackoff: suspend (attempt: Int, retryAfterMs: Long) -> Unit =
            RetryBackoffPolicy.networkAwareRateLimit(rateLimitPlan, networkRecovery),
        retryBackoff: suspend (attempt: Int) -> Unit =
            RetryBackoffPolicy.networkAware(backoffPlan, networkRecovery),
        llmCallTimeoutMs: Long = 300_000L,
        turnBudgetMs: Long = 30 * 60 * 1000L,
    ): AgentLoop = RealAgentLoop(
        llmProvider = llmProvider,
        registry = registry,
        systemPrompt = systemPrompt,
        maxSteps = maxSteps,
        maxParallelTools = maxParallelTools,
        providerRetry = providerRetry,
        retryProfileProvider = retryProfileProvider,
        maxHistoryMessages = maxHistoryMessages,
        contextBudgetTokens = contextBudgetTokens,
        keepRecentImages = keepRecentImages,
        consent = consent,
        checkpoint = checkpoint,
        journal = journal,
        journalTurn = journalTurn,
        scope = scope,
        memoryProvider = memoryProvider,
        onEvent = onEvent,
        shouldContinue = shouldContinue,
        onTextDelta = onTextDelta,
        onReasoningDelta = onReasoningDelta,
        attemptOut = attemptOut,
        hooks = hooks,
        seams = seams,
        observer = observer,
        executorFactory = executorFactory,
        forceFullTools = forceFullTools,
        activeGroups = activeGroups,
        networkRecovery = networkRecovery,
        backoffPlan = backoffPlan,
        rateLimitPlan = rateLimitPlan,
        rateLimitBackoff = rateLimitBackoff,
        retryBackoff = retryBackoff,
        llmCallTimeoutMs = llmCallTimeoutMs,
        turnBudgetMs = turnBudgetMs,
    )
}
