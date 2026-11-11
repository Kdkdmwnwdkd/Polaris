// 审计 F19：read_file 的 limit 是不是真的限制。
//
// 为什么这条能真跑：TextFileSlice 只依赖 java.io（零 Android），可以拿真文件在 JVM 上逐条断言。
// 修前 ReadFileTool 是 `file.readText()` 整份载入再 substring，limit 只限制「返回给模型的那一段」，
// 对堆不生效——这条 harness 用 36MB 的文件 + -Xmx64m 把这件事钉住。
//
// 运行：scripts/qa/run-text-file-slice-verification.sh

import com.beichen.polaris.core.TextFileSlice
import java.io.File

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"
        println("  FAIL  $name — ${t.message}")
    }
}

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/** 抓「页首/页尾有孤立代理」——这正是旧 substring 分页的产物。 */
private fun assertNoLoneSurrogate(s: String, what: String) {
    if (s.isEmpty()) return
    if (s[0].isLowSurrogate()) throw AssertionError("$what：页首是孤立低位代理（上一页把它劈开了）")
    for (i in s.indices) {
        val c = s[i]
        when {
            c.isHighSurrogate() -> {
                val next = if (i + 1 < s.length) s[i + 1] else '\u0000'
                if (!next.isLowSurrogate()) throw AssertionError("$what：下标 $i 处是孤立高位代理")
            }
            c.isLowSurrogate() -> {
                val prev = if (i > 0) s[i - 1] else '\u0000'
                if (!prev.isHighSurrogate()) throw AssertionError("$what：下标 $i 处是孤立低位代理")
            }
        }
    }
}

private fun tmp(name: String, text: String): File =
    File.createTempFile("f19-", "-$name").apply { writeText(text); deleteOnExit() }

fun main() {
    println("F19 read_file 分页：limit 是不是真的限制")

    println("\n[A] 分页行为")
    check("A1 小文件一次读完：全文、hasMore=false、nextOffset=长度") {
        val t = "你好，世界\nhello"
        val s = TextFileSlice.read(tmp("a1", t), 0, 1024)
        eq(s.text, t, "全文")
        eq(s.hasMore, false, "hasMore")
        eq(s.nextOffset, t.length, "nextOffset")
    }

    check("A2 超长文件只返回一页（limit 生效）") {
        val t = "x".repeat(1000)
        val s = TextFileSlice.read(tmp("a2", t), 0, 100)
        eq(s.text.length, 100, "本页字符数")
        eq(s.hasMore, true, "hasMore")
        eq(s.nextOffset, 100, "nextOffset")
    }

    check("A3 逐页读到底：拼接结果必须逐字符等于原文（含中文/emoji/CRLF）") {
        val unit = "第一行\r\n第二行 😀🚀\n第三条 中文\n"
        val t = unit.repeat(40)
        val f = tmp("a3", t)
        val sb = StringBuilder()
        var off = 0
        var guard = 0
        while (true) {
            val s = TextFileSlice.read(f, off, 97)
            assertNoLoneSurrogate(s.text, "第 $guard 页")
            sb.append(s.text)
            if (!s.hasMore) break
            off = s.nextOffset
            if (++guard > 10_000) throw AssertionError("分页没有前进（死循环）")
        }
        eq(sb.toString(), t, "拼接结果")
    }

    check("A4 页尾不劈代理对：第 100 个码元是 emoji 时本页多带 1 个码元") {
        val t = "a".repeat(99) + "😀" + "b".repeat(10)
        val s = TextFileSlice.read(tmp("a4", t), 0, 100)
        eq(s.text.length, 101, "本页字符数（emoji 占 2 个码元，被完整纳入）")
        eq(s.text.endsWith("😀"), true, "页尾是完整的 emoji")
        assertNoLoneSurrogate(s.text, "本页")
        eq(s.nextOffset, 101, "nextOffset 要对齐到码点边界")
    }

    check("A5 用 nextOffset 续读：第二页从下一页字符开始，不重不漏") {
        val t = "a".repeat(99) + "😀" + "b".repeat(10)
        val f = tmp("a5", t)
        val p1 = TextFileSlice.read(f, 0, 100)
        val p2 = TextFileSlice.read(f, p1.nextOffset, 100)
        eq(p2.text, "b".repeat(10), "第二页内容")
        eq(p1.text + p2.text, t, "两页拼接")
        eq(p2.hasMore, false, "第二页已读完")
    }

    check("A6 手工把 offset 传到代理对中间：跳过半个码点，不产出非法页") {
        val t = "a".repeat(99) + "😀" + "b".repeat(10)
        val s = TextFileSlice.read(tmp("a6", t), 100, 100) // 100 正是低位代理
        assertNoLoneSurrogate(s.text, "页")
        eq(s.text, "b".repeat(10), "从下一个码点开始")
        eq(s.nextOffset, 111, "nextOffset 与实际消费对齐")
    }

    check("A7 offset 超过文件长度：空页、hasMore=false、nextOffset=实际长度") {
        val t = "短文件"
        val s = TextFileSlice.read(tmp("a7", t), 999, 100)
        eq(s.text, "", "空页")
        eq(s.hasMore, false, "hasMore")
        eq(s.nextOffset, t.length, "nextOffset")
    }

    check("A8 limit 有硬顶：传 1000 万也只给上限那么多") {
        val t = "z".repeat(TextFileSlice.MAX_LIMIT + 5000)
        val s = TextFileSlice.read(tmp("a8", t), 0, 10_000_000)
        eq(s.text.length, TextFileSlice.MAX_LIMIT, "本页字符数")
        eq(s.hasMore, true, "后面还有")
    }

    check("A9 limit ≤ 0 当 1（不抛异常、不返回空）") {
        val f = tmp("a9", "abc")
        eq(TextFileSlice.read(f, 0, 0).text, "a", "limit=0")
        eq(TextFileSlice.read(f, 0, -5).text, "a", "limit=-5")
    }

    check("A10 空文件：空页、不越界") {
        val s = TextFileSlice.read(tmp("a10", ""), 0, 100)
        eq(s.text, "", "空页")
        eq(s.hasMore, false, "hasMore")
        eq(s.nextOffset, 0, "nextOffset")
    }

    println("\n[B] 内存（本进程以 -Xmx64m 启动，旧实现 readText 一个 36MB 文件就会 OOM）")
    check("B1 36MB 文件读 32 字符：内存只与本页有关，不随文件大小增长") {
        val chars = 12_000_000 // '中' 占 3 字节 → 约 36MB
        val f = File.createTempFile("f19-", "-big")
        f.deleteOnExit()
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            val block = "中".repeat(10_000)
            var written = 0
            while (written < chars) {
                w.write(block)
                written += block.length
            }
        }
        val bytes = f.length()
        if (bytes < 30L * 1024 * 1024) throw AssertionError("测试文件不够大（$bytes 字节），本项失去意义")
        val s = TextFileSlice.read(f, 0, 32)
        eq(s.text.length, 32, "本页字符数")
        eq(s.hasMore, true, "hasMore")
        eq(s.text, "中".repeat(32), "内容")
    }

    println()
    if (failures.isNotEmpty()) {
        println("失败清单：")
        failures.forEach { println("  - $it") }
    }
    println("=== 合计 $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
