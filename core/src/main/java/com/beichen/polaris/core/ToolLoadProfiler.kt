package com.beichen.polaris.core

/**
 * 工具装配的**逐项计时器**：回答「`registry` 那一段 1000+ms 到底花在谁身上」。
 *
 * ## 为什么需要它
 *
 * `StartupMark` 只给出「从上一个打点到 `mark("registry")`」的一整段数字。这一段里有
 * 45 个 `register(XxxTool(...))`，整段慢时无法区分两种完全不同的成因：
 *
 *  - **少数几个工具构造真重**（构造器里读盘、探测环境、初始化引擎）→ 该改那几个；
 *  - **均匀分布**（每个 15~25ms）→ 那是 JVM 类加载/校验/解释执行的开销，
 *    改单个工具没有任何用，只能改「什么时候做这件事」（挪出首帧 / 预热）。
 *
 * 靠猜会把第二种当成第一种去修，改完发现没变——本项目在 `refresh` 段已经吃过一次
 * 「归因只对一半」的亏（先疑 `refreshGrants`，真凶是 `refreshLogs`）。所以这次先量。
 *
 * ## 为什么能在不改 45 个调用点的前提下量出来
 *
 * Kotlin 里 `register(XxxTool(...))` 的实参**先求值、再进函数体**。因此
 * 「上一次 `register` 进入时刻 → 这一次 `register` 进入时刻」的间隔，
 * 恰好等于**本次实参的构造 + 类加载**耗时。于是在 [ToolRegistry.register] 开头
 * 打一个点就够了，调用点一行都不用动。
 *
 * 副作用：第一个工具没有「上一次」，因此不计入样本（它同时承担基准点角色）。
 *
 * ## 可测性
 *
 * 时钟可注入（[clock]），所以行为断言能用假时钟精确验证排序、总量、首样本跳过，
 * 不依赖真实耗时——真实耗时断言在 CI 上必然是 flaky 的。
 *
 * @param slowMillis 单项慢阈值：`report` 里只列超过它的项（默认 12ms）。
 * @param clock 时钟（毫秒）。默认取**单调**时钟 `System.nanoTime()`；测试注入假时钟。
 */
class ToolLoadProfiler(
    private val slowMillis: Long = 12L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /** 单个工具的装配耗时样本。 */
    data class Sample(val name: String, val millis: Long)

    private val samples = mutableListOf<Sample>()
    private var last = -1L

    /**
     * 记录一次 `register` 进入时刻。
     *
     * 第一个样本被跳过：它没有前驱，无法把耗时归给谁；它的时刻只用作基准。
     * 之后每次调用产生一条 [Sample]，`millis` = 距上一次调用的间隔，
     * 也就是**本次实参的构造耗时**。
     *
     * 时钟回拨（`now < last`）记为 0 而不是负数：默认时钟已经是单调的 `nanoTime`，
     * 但注入的时钟未必单调。负样本会把 [total] 抵消掉、把 [slowest] 排序搞乱，
     * 而「测出来是负数」在日志里只会被当成 bug，不会被认为是时钟问题。
     */
    @Synchronized
    fun mark(name: String) {
        val now = clock()
        if (last >= 0L) samples += Sample(name, if (now >= last) now - last else 0L)
        last = now
    }

    /** 全部样本（快照，按装配顺序）。 */
    @Synchronized
    fun snapshot(): List<Sample> = samples.toList()

    /** 样本总毫秒数（= 已归因的工具装配耗时之和）。 */
    @Synchronized
    fun total(): Long = samples.sumOf { it.millis }

    /**
     * 最慢的若干项（降序）。同耗时时按名字升序，保证输出稳定——
     * 否则同样数据的两次调用会给出不同顺序，日志没法比对。
     */
    @Synchronized
    fun slowest(limit: Int = 8): List<Sample> = samples
        .sortedWith(compareByDescending<Sample> { it.millis }.thenBy { it.name })
        .take(limit)

    /**
     * 一行可读报告：归因总量 + 超阈值的慢项。
     *
     * 没有样本时返回「无样本」而不是空串——空串会让日志里少一行，
     * 「少一行」和「这段没跑」在事后无法区分。
     */
    fun report(limit: Int = 8): String {
        val all = snapshot()
        if (all.isEmpty()) return "工具装配：无样本（可能未接入 profiler）"
        val slow = slowest(limit).filter { it.millis >= slowMillis }
        val head = "工具装配 ${all.size} 项，合计 ${total()}ms"
        if (slow.isEmpty()) return "$head；无单项超过 ${slowMillis}ms（分布均匀 → 类加载开销）"
        return head + "；最慢：" + slow.joinToString(", ") { "${it.name}=${it.millis}ms" }
    }
}
