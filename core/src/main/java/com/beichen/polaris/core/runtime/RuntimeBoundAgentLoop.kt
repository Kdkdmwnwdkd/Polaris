package com.beichen.polaris.core.runtime

import com.beichen.polaris.core.AgentEvent
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.AgentLoop

/**
 * 回合事件闸门：把「代次号」从机制变成**行为**。
 *
 * ## 为什么事件需要闸门
 *
 * [AgentRuntime.cancel] 自增代次号，这本身只是个计数器——只有当**有人拿它做判断**时，
 * 它才产生「迟到事件被丢弃」的效果。生产链路里事件从 loop 流向 UI，唯一自然的拦截点
 * 就是这里：事件进 UI 之前先问一句「发这条事件的回合，还是当前这一代吗」。
 *
 * 不拦会怎样（v4 §3.2 的原话）：布尔 `isRunning` 表达不了「第 2 回合已开始、
 * 第 1 回合的迟到事件还在流里」。用户点停止后立刻重问，旧回合从取消传播到真正退出
 * 之间有几十毫秒的窗口，其间工具并行线程仍可能推 `ToolStarted`——
 * 新回合的界面上就会凭空长出一张旧回合的工具卡，而且永远不会结束。
 *
 * ## 收尾事件例外（T05）
 *
 * 取消后 `RealAgentLoop` 会在树尾闭合里为未完成的工具补一条 `ToolFinished`，
 * 让「执行中」卡落到终态。这条事件**代次号必然已过期**（用户先 cancel 才触发收尾），
 * 如果一并丢弃，UI 会永久停在「执行中」——比污染更糟。
 *
 * 所以判据不是「事件新不新」，而是「这条事件在干什么」：
 * - **开始/增量类**（[AgentEvent.ToolStarted] / [AgentEvent.RetryStarted] /
 *   [AgentEvent.OutputTruncated]）：给界面**新增**状态 → 过期必丢；
 * - **收尾类**（[AgentEvent.ToolFinished] / [AgentEvent.RetryFinished]）：
 *   给界面**闭合**已有状态 → 即使是旧代次也放行。
 *
 * 这不是给旧事件开后门：闭合事件只能作用于已经存在的 id（`ToolFinished` 按 call.id
 * upsert），不会凭空造出新卡片。
 */
class TurnEventGate internal constructor(
    private val runtime: AgentRuntime,
    private val session: String,
    private val sink: ((AgentEvent) -> Unit)?,
) {

    /**
     * 当前回合的租约快照。`@Volatile`：send 在调用方协程写，事件来自工具并行执行的
     * 线程池读——没有它，读线程可能长期看不到 bind。
     */
    @Volatile
    private var lease: TurnLease? = null

    /** 回合开始/结束时绑定与解绑。null = 当前没有回合在跑。 */
    internal fun bind(lease: TurnLease?) {
        this.lease = lease
    }

    /**
     * 事件出口。未绑定回合（send 之外）一律丢：loop 不应在回合外发事件，
     * 真发了说明接线错了，静默转给 UI 只会让错误更难查。
     */
    fun emit(event: AgentEvent) {
        val held = lease ?: return
        val stale = runtime.generation(session) != held.generation
        if (stale && !isCloseout(event)) return
        sink?.invoke(event)
    }

    private fun isCloseout(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.ToolFinished, AgentEvent.RetryFinished -> true
        is AgentEvent.ToolStarted, is AgentEvent.RetryStarted,
        is AgentEvent.OutputTruncated -> false
    }
}

/**
 * 把 [AgentLoop] 的一次 `send` 绑到 [AgentRuntime] 的回合所有权上——**执行权收口**。
 *
 * ## 与 [AgentLoopFactory] 的分工
 *
 * 第三轮收的是「构造权」：生产代码只在一处 `RealAgentLoop(...)`。但构造点收拢之后，
 * 跑起来的回合仍然没有任何人向 runtime 登记——`acquireTurn` 在生产路径上一次都没被调用过，
 * 队列层形同虚设。本类补上这一步：
 *
 * | 阶段 | 谁负责 | 做什么 |
 * |---|---|---|
 * | 构造 | [AgentLoopFactory] | 决定用哪个 runtime、哪个 (session, source) |
 * | 执行 | 本类 | `acquireTurn` 拿所有权 → 跑 → `close` 归还 |
 * | 事件 | [TurnEventGate] | 用租约里的代次号丢弃迟到事件 |
 *
 * ## 为什么在 send 内部拿锁，而不是包一层外置的「调用前先 acquire」
 *
 * 外置方案要求每个调用点记得「先 acquire 再 send」，漏一个就静默绕过队列层——
 * 与「构造点收拢到一个」是同一类问题。放在 send 里，**任何一个手里有 loop 的人
 * 都不可能绕开**，这是不变量在类型层落地，而不是靠纪律。
 *
 * ## 异常与取消
 *
 * `finally` 里归还：取消（`CancellationException`）、超时、模型报错都必须释放，
 * 否则一次失败会让该 (session, source) 永久锁死——比不排队更糟。
 */
class RuntimeBoundAgentLoop internal constructor(
    private val delegate: AgentLoop,
    private val runtime: AgentRuntime,
    private val gate: TurnEventGate,
    private val session: String,
    private val source: RunSource,
) : AgentLoop {

    override suspend fun send(
        userInput: String,
        images: List<String>,
        priorMessages: List<ChatMessage>,
    ): List<ChatMessage> {
        val held = runtime.acquireTurn(session, source)
        gate.bind(held)
        try {
            return delegate.send(userInput, images, priorMessages)
        } finally {
            // 先解绑再归还：归还会唤醒排队中的下一个 send，它 bind 之前不能让本回合
            // 的残留事件继续以「当前回合」的身份透传。
            gate.bind(null)
            held.close()
        }
    }

    override fun update(
        systemPrompt: String?,
        memoryProvider: (() -> String)?,
        registry: ToolRegistry?,
        maxSteps: Int?,
    ) = delegate.update(systemPrompt, memoryProvider, registry, maxSteps)
}
