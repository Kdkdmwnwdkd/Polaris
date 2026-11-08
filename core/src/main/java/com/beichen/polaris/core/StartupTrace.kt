package com.beichen.polaris.core

/**
 * 启动分段计时器：把「启动慢」从一句抱怨变成可归因的数字。
 *
 * ## 为什么需要它
 *
 * 之前只有一句结论：「`ChatViewModel` 初始化有约 9 秒主线程重活」。这句话的问题
 * 是**不可证伪**——它没说重在哪一段，于是既没法判断该不该修，也没法验证修完有没有
 * 变好，只能凭感觉。
 *
 * 埋点的判据很简单：**每一段都要能报出毫秒数**。报不出来的段，说明它根本不是问题
 * （或埋点位置错了）；报出几百毫秒以上的段，就是下一个要动的对象。
 *
 * ## 为什么不用 `android.os.Trace` / systrace
 *
 * 那两样都要外部工具（Perfetto / atrace）才能读，而本项目的验证链路是
 * 「构建机出包 → 验证机装包 → 拉日志」。日志是这条链路上**唯一一定会被读到**的
 * 载体，所以计时结果直接落日志。
 *
 * ## 为什么是纯 Kotlin（不依赖 Android）
 *
 * 这样它能被 QA harness 单独编译并断言（见 `scripts/qa/run-startup-trace-verification.sh`），
 * 不必等 Gradle 产物。「埋点自己写错了」这种 bug 也就能被自动抓到，而不是靠人眼看
 * 日志时发现数字不对。
 *
 * ## 用法
 *
 * ```kotlin
 * val trace = StartupTrace()
 * trace.mark("registry")     // 记「上一段」到「现在」的耗时
 * ...
 * trace.summary()            // "registry=12ms, skills=340ms, ..."（按序拼接）
 * ```
 *
 * 线程安全：多线程 mark 时不会互相覆盖（内部用同步块）。这很重要——启动期确实有
 * 多个线程会打点（主线程 + 后台 IO 协程）。
 */
class StartupTrace(
    /** 时钟源：默认 `System.currentTimeMillis()`。测试可注入假时钟。 */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 一段计时：段名 + 该段耗时（毫秒）。 */
    data class Segment(val name: String, val millis: Long)

    private val lock = Any()
    private val segments = mutableListOf<Segment>()
    private var lastTick: Long = clock()

    /**
     * 是否已收尾。收尾后 [mark] 不再记录任何段。
     *
     * ## 为什么需要
     *
     * 打点写在**会被重复调用的方法**里（比如 `buildInitialSkills()` 在导入/卸载技能时
     * 会经 `reloadSkills()` 再跑一遍）。启动观测的逻辑终点是 `finish()`——之后的打点
     * 混进来，`snapshot()` / `summary()` 就会把「启动之后的某次重载」也算成启动分段，
     * 数字当场失真。
     */
    private var sealed = false

    /**
     * 结束上一段并开始下一段。
     *
     * 已 [seal] 时直接返回 0 且不记录——收尾之后的打点属于「别的观测」，不属于启动分段。
     *
     * @param name 段名（如 "registry" / "skills" / "refreshLogs"）。
     * @return 该段耗时（毫秒）；已收尾时为 0。
     */
    fun mark(name: String): Long = synchronized(lock) {
        if (sealed) return@synchronized 0L
        val now = clock()
        val delta = now - lastTick
        lastTick = now
        segments += Segment(name, delta)
        delta
    }

    /**
     * 收尾：此后 [mark] 不再生效。
     *
     * 幂等——重复调用不改变任何已记录的段。
     */
    fun seal() = synchronized(lock) { sealed = true }

    /** 是否已收尾（供断言与调用方判断）。 */
    fun isSealed(): Boolean = synchronized(lock) { sealed }

    /** 已记录的段（快照，按记录顺序）。 */
    fun snapshot(): List<Segment> = synchronized(lock) { segments.toList() }

    /** 总耗时：最后一段结束时刻 − 第一段开始时刻。 */
    fun totalMillis(): Long = synchronized(lock) {
        if (segments.isEmpty()) 0L else segments.sumOf { it.millis }
    }

    /**
     * 一行摘要，直接落日志：`registry=12ms, skills=340ms, total=352ms`。
     *
     * 空序列时返回 `"<no segments>"`——**不返回空串**。空串在日志里会被人一眼略过，
     * 而「本该有数据却没有」本身就是要查的信号。
     */
    fun summary(): String = synchronized(lock) {
        if (segments.isEmpty()) {
            "<no segments>"
        } else {
            val body = segments.joinToString(", ") { "${it.name}=${it.millis}ms" }
            "$body, total=${totalMillis()}ms"
        }
    }

    /**
     * 超过阈值的段名（按耗时倒序）。
     *
     * 用途：启动日志里只把「值得看的段」单独列出来，省得在一堆 0ms 里翻找。
     *
     * @param thresholdMillis 阈值（毫秒），默认 100ms。
     */
    fun slowSegments(thresholdMillis: Long = 100L): List<Segment> =
        snapshot().filter { it.millis >= thresholdMillis }.sortedByDescending { it.millis }

    /**
     * 汇总成一行日志文本（含每段毫秒数与超阈值的慢段）。
     *
     * ## 为什么要单独一个方法
     *
     * 这行日志是**唯一**的产出物——所有打点都是为了最后能打出这一行。
     * 把格式化逻辑收在这里，`ChatViewModel` 那边就只剩一次调用，不必为了一行日志
     * 在那个已经 3900 行的大文件里再堆十几行拼接代码。
     *
     * @param label 前缀（如 "ViewModel 初始化分段"）。
     * @param thresholdMillis 慢段阈值（毫秒），默认 100ms。
     */
    fun logLine(label: String, thresholdMillis: Long = 100L): String = buildString {
        append(label).append("：").append(summary())
        val slow = slowSegments(thresholdMillis)
        if (slow.isNotEmpty()) {
            append("；慢段：").append(slow.joinToString(", ") { "${it.name}=${it.millis}ms" })
        }
    }
}
