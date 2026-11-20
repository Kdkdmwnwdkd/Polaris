package com.beichen.polaris.core.agent

/**
 * 冷启动恢复的**唯一**结论：把「运行态对账」与「回合日志扫描」两份证据合成一个对象。
 *
 * ## 为什么必须合成
 *
 * 这两份证据本来各跑各的：
 *  - [AgentRunState] 记「整个任务跑到第几步」，产出「上次任务被中断，已自动继续」；
 *  - 回合日志记「本回合每个工具调用越没越过执行边界」，产出「3 个写操作结局未知」。
 *
 * 分开跑时有一个**只会在真机上复现**的漏洞：自动续跑只等运行态（`runStateReady`），
 * 日志扫描是另一个协程，于是「有写操作可能已生效」这件事**赶不上**裁决——
 * 应用照样发一句「继续」，模型看到的历史里也没有「上次那个 write_file 可能已经生效」，
 * 于是把同一件事再做一遍。代码全绿、日志全绿，只有用户的文件被写了两遍。
 *
 * 所以合成点必须在这里：**结果**只有一份，谁先谁后都不成立。
 *
 * ## 不放 ViewModel 的理由
 *
 * 文案与计数都是纯数据变换，放进 ViewModel 就只能靠 grep 断言。放这里可以被 harness
 * 真编真跑（[scripts/qa/AutoResumeVerification.kt] 的 C 段）。
 */
data class StartupRecovery private constructor(
    val state: AgentRunState?,
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

    /** 日志里的一条未应答调用。只保留裁决需要的两件事，不把 journal 包拖进来。 */
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

        val EMPTY = build(null, emptyList())

        /**
         * 合成。纯函数：不读时间、不碰 IO、不写日志。
         *
         * 顺序敏感——[unknownTools] 用 `distinct()` 保序去重，提示里先说哪个工具
         * 取决于日志里的声明顺序，这与模型当时的意图一致。
         */
        fun build(state: AgentRunState?, orphans: List<Orphan>, anomalies: Int = 0): StartupRecovery {
            val unknown = orphans.filter { it.mayHaveTakenEffect }
            return StartupRecovery(
                state = state,
                unknownWrites = unknown.size,
                safeRetries = orphans.size - unknown.size,
                unknownTools = unknown.map { it.toolName }.filter { it.isNotBlank() }.distinct(),
                anomalies = anomalies,
            )
        }
    }
}
