package com.beichen.polaris.core.agent

/**
 * 回合结构化事件（R5）：一条回合的**可观测时间线**。
 *
 * ## 为什么需要它
 * 用户报「卡住了」时，现状能回答的只有「模型没回话」。要定位到**哪一步卡住**，
 * 需要一条按时间排序、带回合标识、带耗时的结构化时间线——而不是从散落的
 * `AgentLogger` 行里靠猜（那些行没有回合号，多个回合交错时根本对不上）。
 *
 * 判据（R5 的落地目标）：**能定位到最后一个 `tool.start`**。
 * 也就是说，时间线必须满足：每条事件带 `turnId`；工具事件成对（start/end），
 * 且能看出「开始了但没有 end」——那就是卡住的那一个。
 *
 * ## 8 个事件与时间线的对应
 *
 * | 事件 | 里程碑 | 载荷 |
 * |---|---|---|
 * | [TurnStarted] | 回合开始 | 输入长度、工具面规模 |
 * | [PromptPlanned] | 请求视图算完 | 可见工具数、估算 token、暴露面组 |
 * | [LlmCallStarted] | 发请求前 | 步号 |
 * | [LlmCallEnded] | 请求返回/失败 | 步号、耗时、是否成功 |
 * | [ToolCallStarted] | 工具开始执行 | 步号、工具名、参数摘要 |
 * | [ToolCallEnded] | 工具产出终态 | 步号、工具名、结果码、耗时、是否错误 |
 * | [StepClosed] | 本步收口 | 步号、本步工具数 |
 * | [TurnEnded] | 回合结束 | 状态、步数、总耗时 |
 *
 * ## 与既有事件体系的关系（不要重复造）
 * - [AgentEvent]（4 个）：**UI 驱动**用——工具卡翻终态、恢复横幅。它面向「现在屏幕上要显示什么」，
 *   不带回合号与耗时，也不需要。
 * - [Hooks]（4 个时机）：**执行器观测**用——序列化前 / 工具前后 / 回合收尾。
 * - [TurnEvent]（本文件，8 个）：**排障**用——一条回合的完整时间线。
 *
 * 三者职责不同，故并存。排障口径统一走本文件；UI 与执行器观测各走自己的通道。
 *
 * ## 载荷纪律
 * 事件是**排障材料**，会被打进日志。因此：
 * - **不携带工具的完整输出**（可能几百 KB，且可能含用户隐私）；
 * - **不携带完整参数**（只留摘要，够认出是哪次调用即可）；
 * - **不携带模型回复正文**（只留长度与工具调用数）。
 *
 * 载荷越界时排障价值会归零（要么日志爆掉，要么日志本身变成隐私事故）。
 */
sealed interface TurnEvent {

    /** 回合标识。与 [com.beichen.polaris.core.agent.journal.TurnJournal] 的 turnId 同源。 */
    val turnId: String

    /** 步号（0 起）。回合内的第几次「模型请求 → 工具批次」循环。 */
    val step: Int

    /** 相对回合开始的毫秒数。用**相对时间**而非墙钟，跨设备对照日志时不会因时区/校时错位。 */
    val elapsedMs: Long

    /**
     * 执行作用域（R7）：这条事件由谁产生——主代理、技能脚本还是子代理。
     *
     * R5 的时间线假定「一个回合只有一条执行线」。R7 之后不成立：技能脚本与子代理
     * 会在同一个回合里发起自己的调用。没有这个维度就分不清「主代理在等」
     * 与「技能脚本在等」——而这两者的处置方式完全不同。
     *
     * 默认 [com.beichen.polaris.core.agent.journal.RunScope.MAIN]，即改造前的行为。
     */
    val scope: String get() = com.beichen.polaris.core.agent.journal.RunScope.MAIN

    /** 回合开始。 */
    data class TurnStarted(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 用户输入长度（字符数，不含内容）。 */
        val inputChars: Int,
        /** 本回合暴露的工具数。 */
        val visibleToolCount: Int,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 请求视图算完：暴露面 + 预算 + 截断，一次算好下发。 */
    data class PromptPlanned(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 本次请求实际带上的工具数。 */
        val visibleToolCount: Int,
        /** 请求消息的估算 token 数。 */
        val estimatedTokens: Int,
        /** 激活的工具组（按序）。 */
        val groups: List<String>,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 发请求前。 */
    data class LlmCallStarted(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 本次是第几次尝试（1 起；重试会递增）。 */
        val attempt: Int,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 请求返回（含失败与取消）。 */
    data class LlmCallEnded(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        val attempt: Int,
        /** 本次调用耗时。 */
        val durationMs: Long,
        /** 是否成功产出 Completed 消息。 */
        val success: Boolean,
        /** 失败时的机器可读码（成功为 null）。 */
        val code: String? = null,
        /** 模型返回的工具调用数。 */
        val toolCallCount: Int = 0,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 工具开始执行。 */
    data class ToolCallStarted(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 工具名。 */
        val tool: String,
        /** 参数摘要（截断），够认出是哪一次调用即可。 */
        val argsSummary: String,
        /**
         * 调用 id（P5-1）。与 `ToolCall.id`、工具消息的 id 同源。
         *
         * 加它之前，「同一步里并行两次 `read_file`」在时间线上是两个一模一样的
         * `tool.start`，end 也只有两个一模一样的 `tool.end`——配对全靠猜，
         * 而「哪一个卡住了」正是排障要回答的问题。默认空串：harness 里的
         * 手写事件不需要关心它。
         */
        val callId: String = "",
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /**
     * 工具产出终态。
     *
     * [code] 是结果码（来自工具的 ToolResult.code，如 "ok" / "approval_denied" /
     * "tool_timeout_unknown"）。它比 isError 更有用：失败原因千差万别，
     * 只记「失败了」等于没记。
     */
    data class ToolCallEnded(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        val tool: String,
        /** 结果码；非工具自身产出（如取消闭合）时为合成码。 */
        val code: String,
        val isError: Boolean,
        val durationMs: Long,
        /** 调用 id，与 [ToolCallStarted.callId] 配对。见那里的说明。 */
        val callId: String = "",
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 本步收口：本批工具全部有终态。 */
    data class StepClosed(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 本步执行的工具数。 */
        val toolCount: Int,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent

    /** 回合结束。 */
    data class TurnEnded(
        override val turnId: String,
        override val step: Int,
        override val elapsedMs: Long,
        /** 结束状态（completed / cancelled / failed / max_steps / budget_exceeded）。 */
        val status: String,
        /** 本回合走过的步数。 */
        val steps: Int,
        override val scope: String = com.beichen.polaris.core.agent.journal.RunScope.MAIN,
    ) : TurnEvent
}

/**
 * 回合事件观察者（R5）。
 *
 * **非挂起**函数，理由与 [Hooks] 相同：调用点在并行 async 里、树尾闭合的
 * NonCancellable 里，非挂起保证任何时机调用都不会二次抛取消。需要挂起操作
 * （如落盘）请自行 launch。
 *
 * 默认实现为空——不传观察者时零开销（生产链路目前只接了日志实现，见
 * `LoggingTurnObserver`）。
 */
fun interface TurnObserver {
    fun onEvent(event: TurnEvent)
}

/** 什么都不做的观察者。 */
object NoopTurnObserver : TurnObserver {
    override fun onEvent(event: TurnEvent) = Unit
}
