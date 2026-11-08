package com.beichen.polaris.core.agent.journal

/**
 * 回合内执行日志的事件模型（规格 M2）。
 *
 * 设计约束（三条，改之前先读）：
 *  1. **只追加**：事件一旦写入不修改、不删除。需要"更正"就再写一条，由读取方按 seq 取最后一条。
 *  2. **先记录后生效**：`ToolDispatched` 必须先于工具主体落盘——这个顺序是崩溃恢复能判"跑没跑"的唯一依据。
 *  3. **日志不是会话**：它只覆盖"当前这一个回合"，回合结束后折叠进会话正文再归档。
 *     因此这里没有编辑、没有分支、没有墓碑事件——那些是会话层的事。
 *
 * 与 `ToolCallOutcome` 的关系：
 *  - [ToolSettleStatus] 是**日志视角**的终态（只关心"是否已派发"）；
 *  - `ToolCallOutcome` 是**执行视角**的终态（还关心"有没有副作用"）。
 *  两者正交，映射见 [ToolSettleStatus.fromOutcome]。
 *
 * @property seq 单调递增序号，从 1 开始，同一回合内不重用。
 * @property atMs 事件时间（epoch millis）。合成事件复用前一条的时间，保证可复现。
 * @property scope 执行作用域（R7）：这条事件由谁产生——主代理、技能脚本还是子代理。
 *   默认 [RunScope.MAIN]，即改造前的行为。
 */
sealed interface RunEvent {

    val seq: Long
    val atMs: Long
    val turnId: String

    /**
     * 执行作用域（R7）。
     *
     * 同一个回合日志里会同时出现主代理、技能脚本、子代理三类调用的事件。
     * 没有这个维度就无法回答「这条 DISP 是谁写的」——而技能脚本能产生真实副作用，
     * 崩溃恢复时必须能区分。
     */
    val scope: String get() = RunScope.MAIN

    /** 回合开始。写入后即表示"这个回合存在"，恢复时据此判断是否需要补尾。 */
    data class TurnOpened(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        override val scope: String = RunScope.MAIN
    ) : RunEvent

    /**
     * 模型响应已提交：assistant 文本 + 本步声明的工具调用。
     *
     * **这条事件必须先于任何工具执行写入**。否则崩溃后无法区分
     * "模型没说完" 和 "工具已经跑了但结果没记"，只能全部保守重试。
     *
     * @property calls 本步声明的调用（id 用于配对，name 用于事后判断副作用类别）。
     */
    data class AssistantCommitted(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        val step: Int,
        val calls: List<ToolCallRef>,
        override val scope: String = RunScope.MAIN
    ) : RunEvent

    /**
     * 某次工具调用**进入执行**。写入成功后才允许真正调用工具主体。
     *
     * 这是整套机制里最贵的一行：多一次落盘，换来"崩溃后知道哪些调用可能已经产生副作用"。
     */
    data class ToolDispatched(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        val step: Int,
        val callId: String,
        override val scope: String = RunScope.MAIN
    ) : RunEvent

    /** 工具结果落地（正常结束、失败、超时、拒绝都走这里）。 */
    data class ToolSettled(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        val step: Int,
        val callId: String,
        val status: ToolSettleStatus,
        val errorCode: String? = null,
        override val scope: String = RunScope.MAIN
    ) : RunEvent

    /** 步结束。写在这里表示"该步的所有调用都已应答"，恢复时据此不再补该步。 */
    data class StepClosed(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        val step: Int,
        override val scope: String = RunScope.MAIN
    ) : RunEvent

    /**
     * 回合结束。
     *
     * @property reason completed / blocked / cancelled / failed / interrupted（后者只由修复器合成）。
     */
    data class TurnClosed(
        override val seq: Long,
        override val atMs: Long,
        override val turnId: String,
        val reason: String,
        override val scope: String = RunScope.MAIN
    ) : RunEvent
}

/** 工具调用的最小标识：id 负责配对，name 负责事后判断副作用类别。 */
data class ToolCallRef(val id: String, val name: String)

/**
 * 日志视角的调用终态。
 *
 * 只有四态，且**不含"失败"与"未知"的因果区分**——那是执行层的判断。
 * 这里回答的问题只有一个：崩溃发生时，这次调用越过了执行边界没有。
 */
enum class ToolSettleStatus {

    /** 正常返回。 */
    SUCCEEDED,

    /** 已派发，确定没有产生效果（参数错误、超时后进程被回收等）。 */
    FAILED,

    /** 已派发，但结局没有记录。**可能已经生效**。 */
    OUTCOME_UNKNOWN,

    /** 未派发，确定没有生效。可以安全重试。 */
    NOT_DISPATCHED;

    companion object {
        /**
         * 执行层终态 → 日志终态。
         *
         * `Interrupted`（只读被 kill）映射为 [FAILED] 而不是 [OUTCOME_UNKNOWN]，
         * 因为执行层已经判定它没有副作用；日志层不需要再保守一次。
         */
        fun fromOutcome(outcome: com.beichen.polaris.core.agent.ToolCallOutcome): ToolSettleStatus =
            when (outcome) {
                is com.beichen.polaris.core.agent.ToolCallOutcome.Completed -> SUCCEEDED
                is com.beichen.polaris.core.agent.ToolCallOutcome.Interrupted -> FAILED
                is com.beichen.polaris.core.agent.ToolCallOutcome.Unknown -> OUTCOME_UNKNOWN
                is com.beichen.polaris.core.agent.ToolCallOutcome.NotDispatched -> NOT_DISPATCHED
            }
    }
}
