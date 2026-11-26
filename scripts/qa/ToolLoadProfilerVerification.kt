#!/usr/bin/env kotlin
/**
 * 工具装配计时器 harness：验证 [com.beichen.polaris.core.ToolLoadProfiler]。
 *
 * ## 为什么要有它
 *
 * `registry` 段是启动初始化里最大的一项（实测 1070~1213ms），但「这一段慢」不等于
 * 「某个工具慢」。两种成因的修法完全相反：
 *
 *  - 少数工具构造真重 → 改那几个工具；
 *  - 均匀摊开（每项 15~25ms）→ 那是类加载，改单个工具没用，只能改「什么时候装」。
 *
 * 靠猜会选错方向。所以先有一个**可信的**逐项计时器，再谈优化。这个 harness 验的就是
 * 「它数得对不对」——假时钟让每条断言都确定，不依赖机器快慢。
 *
 * ## 断言方式
 *
 * 全部是**行为断言**：构造 profiler、喂假时钟、比对返回值。没有一条是对源码做文本匹配，
 * 所以 `assertion-census.py` 的文本断言数不会因此增加。
 *
 * 运行：bash scripts/qa/run-tool-load-profiler-verification.sh
 */

import com.beichen.polaris.core.ToolLoadProfiler

private var pass = 0
private val fails = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ✅ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        fails += name
        println("  ❌ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "实际=$actual 期望=$expected")

/** 按给定序列返回时间戳的时钟；用尽后停在最后一个值（防止越界抛异常掩盖断言失败）。 */
private class SeqClock(private val times: LongArray) : () -> Long {
    private var i = 0
    override fun invoke(): Long {
        val v = if (i < times.size) times[i] else times.last()
        i++
        return v
    }
}

private fun profiler(times: LongArray, slowMillis: Long = 12L) =
    ToolLoadProfiler(slowMillis = slowMillis, clock = SeqClock(times))

fun main() {
    println("=== ToolLoadProfiler harness ===")

    // ── 1. 首样本跳过 ────────────────────────────────────────────────────
    // 第一个工具没有前驱，任何耗时都无法归给它。若把它算进去，第一项会背上
    // 「从 profiler 创建到它构造完」的全部时间（含无关字段初始化），变成假慢项。
    run {
        val p = profiler(longArrayOf(0, 10, 30))
        p.mark("a"); p.mark("b"); p.mark("c")
        eq("首样本被跳过：3 次 mark 只留 2 条样本", p.snapshot().size, 2)
        // 项数必须等于 mark 次数（= 注册数），不是样本数。真机上这两者差 1，
        // 日志写「装配 59 项」而 registry 有 60，读包的人会以为有工具没装上。
        eq("项数按 mark 次数报：3 次 mark = 3 项", p.markedCount(), 3)
        eq("样本名对应后一个工具", p.snapshot().map { it.name }, listOf("b", "c"))
        eq("间隔即构造耗时", p.snapshot().map { it.millis }, listOf(10L, 20L))
        eq("合计 = 各段之和", p.total(), 30L)
    }

    // ── 2. 排序：降序 + 同耗时按名字升序（输出必须稳定）──────────────────
    // 不稳定排序会让同样数据在两次日志里给出不同顺序，事后无法比对。
    run {
        val p = profiler(longArrayOf(0, 5, 35, 40), slowMillis = 0)
        p.mark("a"); p.mark("b"); p.mark("c"); p.mark("d")
        eq("slowest 降序", p.slowest(2).map { it.name }, listOf("c", "b"))
        eq("同耗时按名字升序", p.slowest(3).map { it.name }, listOf("c", "b", "d"))
        eq("limit 生效", p.slowest(1).size, 1)
        eq("合计 = 40", p.total(), 40L)
    }

    // ── 3. report 的三种形态（精确相等，不做子串匹配）────────────────────
    run {
        val empty = profiler(longArrayOf(0))
        eq(
            "无样本时直说「可能未接入」，不是空串",
            empty.report(), "工具装配：无样本（可能未接入 profiler）"
        )
    }
    run {
        val p = profiler(longArrayOf(0, 5, 35, 40), slowMillis = 100)
        p.mark("a"); p.mark("b"); p.mark("c"); p.mark("d")
        eq(
            "全部低于阈值时标注「分布均匀 → 类加载」",
            p.report(), "工具装配 4 项，合计 40ms；无单项超过 100ms（分布均匀 → 类加载开销）"
        )
    }
    run {
        val p = profiler(longArrayOf(0, 5, 35, 40), slowMillis = 12)
        p.mark("a"); p.mark("b"); p.mark("c"); p.mark("d")
        eq(
            "超阈值项进「最慢」并带毫秒数",
            p.report(), "工具装配 4 项，合计 40ms；最慢：c=30ms"
        )
    }

    // ── 4. 阈值边界是「>=」不是「>」──────────────────────────────────────
    // 阈值写 30 时，正好 30ms 的那项必须被列出来：它就是要抓的「慢」，
    // 用严格大于会让边界值悄悄漏掉。
    run {
        val p = profiler(longArrayOf(0, 5, 35, 40), slowMillis = 30)
        p.mark("a"); p.mark("b"); p.mark("c"); p.mark("d")
        eq(
            "正好等于阈值也算慢项",
            p.report(), "工具装配 4 项，合计 40ms；最慢：c=30ms"
        )
    }

    // ── 5. 快照隔离 ─────────────────────────────────────────────────────
    run {
        val p = profiler(longArrayOf(0, 10, 20, 30))
        p.mark("a"); p.mark("b")
        val snap = p.snapshot()
        p.mark("c")
        eq("已取快照不随后续 mark 变化", snap.size, 1)
        eq("内部样本继续增长", p.snapshot().size, 2)
    }

    // ── 6. 时钟回拨不产生负样本 ──────────────────────────────────────────
    // 默认时钟已是单调 nanoTime；但注入时钟未必单调。负值会把合计抵消、
    // 把排序搞乱，且日志里只会被当成 bug 而不是时钟问题。
    run {
        val p = profiler(longArrayOf(100, 50, 80))
        p.mark("a"); p.mark("b"); p.mark("c")
        eq("回拨段记为 0", p.snapshot().map { it.millis }, listOf(0L, 30L))
        eq("合计不为负", p.total(), 30L)
    }

    // ── 7. 并发不丢样本 ─────────────────────────────────────────────────
    // register 在主线程，但 MCP 工具是**运行时异步**注册的（另起线程），
    // 两者会在启动后期并发。丢一条样本就会让「合计」低报，把慢项藏起来。
    run {
        val p = ToolLoadProfiler(slowMillis = 0) { System.nanoTime() / 1_000_000 }
        val threads = 8
        val perThread = 50
        val workers = (0 until threads).map { t ->
            Thread {
                repeat(perThread) { i -> p.mark("t$t-$i") }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        // 首样本恒被跳过，且只跳一次
        eq(
            "8 线程 × 50 次 = 400 次 mark，样本 399 条",
            p.snapshot().size, threads * perThread - 1
        )
        eq("名字无重复（没有任何一次被覆盖）", p.snapshot().map { it.name }.toSet().size, threads * perThread - 1)
    }

    println()
    if (fails.isEmpty()) {
        println("全部通过：$pass 条断言")
    } else {
        println("失败 ${fails.size} 条 / 通过 $pass 条")
        fails.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
