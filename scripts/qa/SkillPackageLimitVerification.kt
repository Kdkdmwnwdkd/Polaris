#!/usr/bin/env kotlin
/**
 * 审计 F10「本地 zip 导入没有体积上限」验证 harness。
 *
 * ## 这条缺陷的真身
 *
 * 技能包有三条入口，其中两条是**直接读流**：
 *
 * ```
 * URL → URL(url).openStream().readBytes()
 * SAF → contentResolver.openInputStream(uri).readBytes()
 * ```
 *
 * 都没有上限。而 `OutOfMemoryError` 是 `Error` 不是 `Exception`——`runCatching` 抓不住，
 * 于是「导入失败」直接变成进程崩溃。市场的下载路径早就有 8MB 上限、20s 读超时、
 * 60s 整通上限（`MarketClient` 的三道限制），本地导入却从旁边绕了过去。
 *
 * 同一个动作（取一个 zip 交给 `installZip`）两条入口两套规矩，这正是审计里反复出现的形态：
 * 判据写在一处，另一条路没走它。
 *
 * ## 覆盖
 *  A. `readCapped`：边界（== limit 通过、+1 字节中止）、空流、分块无长度、不关闭入参
 *  B. `requireWithin`：预判（声明值超限立刻拒）
 *  C. 上限常量与市场同源（8MB）
 *  D. 错误消息面向用户可读（含「上限」与 MB 数）
 *
 * 只编译 `SkillPackage.kt`：它只用 `java.io`，不拖 Android，也不拖序列化插件。
 *
 * 运行：scripts/qa/run-skill-package-limit-verification.sh
 */

import com.beichen.polaris.skill.SkillPackage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/** 断言抛 IOException（不是别的异常，也不是「什么都没发生」）。 */
private fun throwsIo(what: String, body: () -> Unit): String {
    try {
        body()
    } catch (e: IOException) {
        return e.message.orEmpty()
    } catch (t: Throwable) {
        throw AssertionError("$what：抛的是 ${t::class.simpleName}，应为 IOException")
    }
    throw AssertionError("$what：没有抛异常（超限被静默放过）")
}

/** 一个不带长度、按块吐字节的流（模拟分块传输 / content:// 没有 Content-Length）。 */
private class ChunkStream(private val chunk: Int, private val total: Int) : InputStream() {
    private var sent = 0
    override fun read(): Int = if (sent >= total) -1 else { sent++; 42 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (sent >= total) return -1
        val n = minOf(chunk, len, total - sent)
        for (i in 0 until n) b[off + i] = 42
        sent += n
        return n
    }
}

private class CloseTracker(private val inner: InputStream) : InputStream() {
    var closed = false
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    override fun close() { closed = true; inner.close() }
}

/* --------------------------------- A 读取 --------------------------------- */

private fun testReadCapped() {
    check("A1 刚好等于上限：放行（差一字节拒、等于上限收，边界不能提前一格）") {
        val limit = 1024
        val exact = ByteArray(limit) { 7 }
        val out = SkillPackage.readCapped(ByteArrayInputStream(exact), "包", limit)
        eq(out.size, limit, "刚好到上限应当收下")
    }

    check("A2 上限 + 1 字节：中止并抛 IOException") {
        val limit = 1024
        val over = ByteArray(limit + 1) { 7 }
        val msg = throwsIo("超一字节") { SkillPackage.readCapped(ByteArrayInputStream(over), "包", limit) }
        expect(msg.indexOf("上限") >= 0) { "错误消息应说明是上限问题，实际：$msg" }
    }

    check("A3 空流：返回空数组，不是 null、不抛") {
        eq(SkillPackage.readCapped(ByteArrayInputStream(ByteArray(0)), "包", 1024).size, 0, "空流")
    }

    check("A4 分块流不带长度也能截住（只信 Content-Length 是不够的）") {
        // 声称没有长度、按 100 字节一块吐 5000 字节：只有边读边计数才能拦住。
        val msg = throwsIo("分块超限") {
            SkillPackage.readCapped(ChunkStream(chunk = 100, total = 5000), "包", 1000)
        }
        expect(msg.indexOf("上限") >= 0) { "应报上限，实际：$msg" }
    }

    check("A5 不关闭调用方的流（二层 use 会二次关闭）") {
        val tracker = CloseTracker(ByteArrayInputStream(ByteArray(10)))
        SkillPackage.readCapped(tracker, "包", 1024)
        expect(!tracker.closed) { "readCapped 不该替调用方关闭流" }
    }

    check("A6 内容原样（不是全 0、也不是截断到一半）") {
        val src = ByteArray(4096) { (it % 251).toByte() }
        val out = SkillPackage.readCapped(ByteArrayInputStream(src), "包", 8192)
        expect(src.contentEquals(out)) { "读出的字节应与输入逐字节相同" }
    }
}

/* --------------------------------- B 预判 --------------------------------- */

private fun testRequireWithin() {
    check("B1 声明值等于上限：放行") {
        SkillPackage.requireWithin(SkillPackage.MAX_ZIP_BYTES.toLong(), "包")
    }

    check("B2 声明值超限：立刻拒，不等到读完") {
        val msg = throwsIo("声明超限") {
            SkillPackage.requireWithin(SkillPackage.MAX_ZIP_BYTES + 1L, "包")
        }
        expect(msg.indexOf("超过") >= 0) { "应说明超过上限，实际：$msg" }
    }

    check("B3 未知长度（-1，分块传输）不预判失败，交给 readCapped") {
        SkillPackage.requireWithin(-1L, "包")
    }
}

/* --------------------------------- C 常量 --------------------------------- */

private fun testLimitValue() {
    check("C1 上限是 8MB（与市场 MarketRules.MAX_PACKAGE_BYTES 相等，由 runner 断言两边数字）") {
        eq(SkillPackage.MAX_ZIP_BYTES, 8 * 1024 * 1024, "上限")
    }

    check("C2 上限不能是 0 或负数（否则任何包都被拒）") {
        expect(SkillPackage.MAX_ZIP_BYTES > 0) { "上限必须为正" }
    }
}

/* --------------------------------- D 文案 --------------------------------- */

private fun testMessages() {
    check("D1 超限消息里带 MB 数（用户要知道多大多小才对）") {
        val msg = throwsIo("消息") {
            SkillPackage.readCapped(ByteArrayInputStream(ByteArray(2048)), "所选文件", 1024)
        }
        expect(msg.indexOf("所选文件") >= 0) { "消息应指明是哪个东西超限：$msg" }
        expect(msg.indexOf("MB") >= 0) { "消息应带单位：$msg" }
    }

    check("D2 预判消息同样可读") {
        val msg = throwsIo("消息") { SkillPackage.requireWithin(9L * 1024 * 1024, "下载的包") }
        expect(msg.indexOf("下载的包") >= 0) { "消息应指明主语：$msg" }
        expect(msg.indexOf("MB") >= 0) { "消息应带单位：$msg" }
    }
}

/* --------------------------------- 入口 ----------------------------------- */

fun main() {
    println("== F10 技能包体积上限（本地导入的三条入口） ==")
    println("[A] readCapped 边界")
    testReadCapped()
    println("[B] requireWithin 预判")
    testRequireWithin()
    println("[C] 上限常量")
    testLimitValue()
    println("[D] 文案")
    testMessages()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
