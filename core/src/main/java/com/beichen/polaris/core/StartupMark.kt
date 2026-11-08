package com.beichen.polaris.core

/**
 * 启动分段计时的**门面**：把「打点」这件事从调用方文件里彻底拿走。
 *
 * ## 为什么要多这一层
 *
 * [StartupTrace] 负责「记时间」，但它不负责「在哪儿打点」。打点必须落在具体的
 * 初始化步骤旁边（否则测的就不是那段代码），于是每个打点都要在被观测的文件里
 * 占一行——而 `ChatViewModel`（3900 行）与 `RealAgentLoop`（1560 行）都已经被
 * arch-guard 冻结了行数上限（只减不增）。
 *
 * 结果就是：加埋点会让这两个大文件超限。抬基线是错的做法（那等于放弃约束）。
 * 正确的做法是**把埋点代码抽出去**——本类就是抽出去的那部分：
 *
 *  - 调用方只写 `startup.mark("skills")` 一行；
 *  - 打点的记录与汇总全在本类里；
 *  - 大文件的行数增长被压到最低（埋点本身占的行，靠压缩同文件里的冗长注释抵消）。
 *
 * ## 用法
 *
 * ```kotlin
 * private val startup = StartupMark()
 * // …初始化…
 * val skills = buildSkills().also { startup.mark("skills") }
 * // …更多初始化…
 * startup.logSummary("ViewModel 初始化分段")   // 落一行日志
 * ```
 */
class StartupMark(
    /** 慢段阈值（毫秒）：低于它的段不进「慢段」清单。默认 100ms。 */
    private val slowThresholdMillis: Long = 100L
) {

    private val trace = StartupTrace()

    /**
     * 工具装配逐项计时：[ToolRegistry] 每次 `register` 打一个点，回答
     * 「`registry` 那一段到底花在哪个工具上、还是均匀摊在类加载上」。
     *
     * 放在这里而不是让调用方自己 new 一个：`ChatViewModel` 已是冻结行数的文件，
     * 每多一个字段都要从别处抠一行出来。门面本来就负责「启动期观测」，
     * 由它持有 profiler 是零成本的。
     */
    val toolProfiler = ToolLoadProfiler()

    /**
     * 结束上一段并开始下一段。
     *
     * @param name 段名（如 "skills" / "registry"）。
     * @return 该段耗时（毫秒）。
     */
    fun mark(name: String): Long = trace.mark(name)

    /** 已记录的段（快照，按记录顺序）。 */
    fun snapshot(): List<StartupTrace.Segment> = trace.snapshot()

    /**
     * 落一行汇总日志：每段毫秒数 + 超阈值的慢段。
     *
     * @param label 前缀（如 "ViewModel 初始化分段"）。
     */
    fun logSummary(label: String) {
        AgentLogger.i("Startup", trace.logLine(label, slowThresholdMillis))
        // 段级数字只说明「registry 这一段慢」，逐项数字才说明「慢在谁身上」。
        // 两条一起落，事后不必再猜。
        AgentLogger.i("Startup", toolProfiler.report())
    }

    /**
     * 收尾：闭合最后一段并落汇总。
     *
     * 存在理由很朴素：`mark("refresh")` 紧跟 `logSummary(...)` 是两行样板，
     * 而调用方（`ChatViewModel`）的行数是冻结的。合成一次调用，
     * 语义也更清楚——「这里既结束了最后一段，也给出了总账」。
     */
    fun finish(lastSegment: String, label: String) {
        mark(lastSegment)
        // 收尾之后不再接受打点：`buildInitialSkills()` 会被 `reloadSkills()` 再调一次，
        // 那次打点属于「导入技能时的重载」，不是启动分段。不封口就会污染快照。
        trace.seal()
        logSummary(label)
    }
}
