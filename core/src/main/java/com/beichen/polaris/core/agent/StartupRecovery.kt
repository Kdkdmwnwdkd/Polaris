package com.beichen.polaris.core.agent

/**
 * 冷启动恢复的**日志侧结论**：一次回合日志扫描留下了什么。
 *
 * ## 它为什么存在
 *
 * 「上次进程死在哪」有两个互不相同的证据源：
 *  - 运行态（`AgentRunState`）记「整个任务跑到第几步」，产出「上次任务被中断，已自动继续」；
 *  - 回合日志记「本回合每个工具调用越没越过执行边界」，产出「3 个写操作结局未知」。
 *
 * 以前这两件事各跑各的协程，自动续跑只等前者——于是「有写操作可能已生效」赶不上裁决：
 * 应用照样发一句「继续」，模型看到历史里「我来写这个文件」就断了，照着重做一遍。
 * 代码全绿、日志全绿，只有用户的文件被写了两遍。
 *
 * 现在日志侧的结论由 [com.beichen.polaris.core.agent.journal.TurnJournal.recoveryFor] 产出，
 * 与运行态一起在**同一条 IO 链**上完成，再由上层合流成一次裁决。
 *
 * ## 为什么这个类不含运行态
 *
 * 刻意只装日志事实。带 `AgentRunState` 的话，它就依赖 `AgentRunStateStore`（序列化 + 文件），
 * 于是 `run-journal-verification.sh` 编不动它——「恢复结论」这一层又只剩 grep 断言。
 * 现在它只依赖标准库，能被那个 harness 真编真跑（J 段逐条断言）。
 *
 * 合流点在上层的 `StartupSnapshot`（app 层），那里才有 `_interruptedNotice` 需要的
 * 「运行态那句提示」。
 */
data class StartupRecovery private constructor(
    /** 越过了执行边界、但没记到结果的写操作数。 */
    val unknownWrites: Int,
    /** 声明了但没派发，重试绝对安全的调用数。 */
    val safeRetries: Int,
    /** 结局未知的工具名（去重、保序），给提示语用。 */
    val unknownTools: List<String> = emptyList(),
    /** 日志自身的不一致条数，只进诊断，不参与裁决。 */
    val anomalies: Int = 0,
) {

    /**
     * 有写操作可能已生效：**不能自动续跑**。
     *
     * 这不是保守，是正确性问题——模型看不见「上次那次写可能成功了」，
     * 它只会照着历史里那句「我来写这个文件」再做一遍。
     */
    val resumeBlocked: Boolean get() = unknownWrites > 0

    /** 日志里的一条未应答调用。只保留裁决需要的两件事。 */
    data class Orphan(val toolName: String, val mayHaveTakenEffect: Boolean)

    /** 恢复期要注入下一轮请求的说明；没有孤儿调用时为 null。 */
    val note: String?
        get() {
            if (unknownWrites == 0 && safeRetries == 0) return null
            return buildString {
                append("上次执行被系统中止。")
                if (unknownWrites > 0) {
                    append("其中 $unknownWrites 个写操作（")
                    append(unknownTools.joinToString("、"))
                    append("）可能已经生效——继续之前建议先核对结果，我不会自动重做。")
                }
                if (safeRetries > 0) {
                    if (unknownWrites > 0) append(" 另有 ")
                    append("$safeRetries 个调用还没开始执行，可以安全重试。")
                }
            }
        }

    companion object {

        val EMPTY = build(emptyList())

        /**
         * 合成。纯函数：不读时间、不碰 IO、不写日志。
         *
         * 顺序敏感——[unknownTools] 用 `distinct()` 保序去重，提示里先说哪个工具
         * 取决于日志里的声明顺序，这与模型当时的意图一致。
         *
         * 构造是 private 的：只能经这里造。否则调用方可以 `StartupRecovery(state, 0, 0)`
         * 拿一份「什么都没发生」的结论，把日志事实整个吞掉——而且不报错。
         */
        fun build(orphans: List<Orphan>, anomalies: Int = 0): StartupRecovery {
            val unknown = orphans.filter { it.mayHaveTakenEffect }
            return StartupRecovery(
                unknownWrites = unknown.size,
                safeRetries = orphans.size - unknown.size,
                unknownTools = unknown.map { it.toolName }.filter { it.isNotBlank() }.distinct(),
                anomalies = anomalies,
            )
        }
    }
}
