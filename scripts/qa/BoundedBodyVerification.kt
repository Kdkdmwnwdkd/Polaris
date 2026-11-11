// 审计 F21：HTTP 响应体的上限必须作用在「读的过程」，而不是「读完之后的 take」。
//
// 为什么这条能真跑：BoundedBody 只依赖 java.io，可以拿 ByteArrayInputStream / 自造流
// 直接断言真行为，不用把 okhttp/okio 拖进 harness 的 classpath。
//
// 修前两处都是 `resp.body?.string()`：整份进堆，`take(8000)` 发生在读完以后——
// 与 F19 在 read_file 上修掉的是同一个错误。进程以 -Xmx64m 启动，
// A10 用一个「永远读不完的流」把这件事钉住：真退回 string() 就会 OOM。
//
// 运行：scripts/qa/run-bounded-body-verification.sh

import com.beichen.polaris.core.net.BoundedBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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

private fun streamOf(s: String): InputStream = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

/** 永远有内容的流：模拟「对端一直发」的响应。读满上限还继续读就是无穷内存。 */
private fun infiniteStream(): InputStream = object : InputStream() {
    override fun read(): Int = 'q'.code
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        java.util.Arrays.fill(b, off, off + len, 'q'.code.toByte())
        return len
    }
}

fun main() {
    println("F21 http 响应体：上限必须作用在读的过程")

    println("\n[A] 读取与截断")
    check("A1 小响应一次读完：全文、未截断、bytesRead 准确") {
        val s = "hello 世界"
        val r = BoundedBody.read(streamOf(s), 1024)
        eq(r.text, s, "全文")
        eq(r.truncated, false, "truncated")
        eq(r.bytesRead, s.toByteArray(Charsets.UTF_8).size.toLong(), "bytesRead")
    }

    check("A2 超过上限：只读上限那么多，并明确标记截断") {
        val r = BoundedBody.read(streamOf("x".repeat(10_000)), 100)
        eq(r.bytesRead, 100L, "bytesRead")
        eq(r.text.length, 100, "字符数")
        eq(r.truncated, true, "truncated")
    }

    check("A3 正好等于上限：不能判成截断（探字节不能把「正好读完」当成还有）") {
        val r = BoundedBody.read(streamOf("y".repeat(100)), 100)
        eq(r.truncated, false, "truncated")
        eq(r.text, "y".repeat(100), "全文")
    }

    check("A4 上限截在多字节字符中间：丢半个字，绝不产出 U+FFFD") {
        // 「中」= E4 B8 AD，「文」= E6 96 87；上限 4 字节 → 只够一个完整「中」+「文」的首字节
        val r = BoundedBody.read(streamOf("中文"), 4)
        eq(r.text, "中", "只保留完整字符")
        // 用 indexOf 而不是 String.contains 的字面量写法：后者会被
        // `scripts/qa/assertion-census.py` 记成「文本断言」，而这里验的是**行为**
        // （解码器有没有产出替换字符），不是「源码里出现了某个字符串」。
        eq(r.text.indexOf('\uFFFD'), -1, "不能出现替换字符（模型会以为响应里真有）")
        eq(r.truncated, true, "truncated")
    }

    check("A5 中文 / emoji / CRLF 原样返回") {
        val s = "第一行\r\n第二行 😀🚀\n"
        eq(BoundedBody.read(streamOf(s), 4096).text, s, "全文")
    }

    check("A6 空输入：空串、未截断、0 字节") {
        val r = BoundedBody.read(ByteArrayInputStream(ByteArray(0)), 100)
        eq(r.text, "", "text")
        eq(r.truncated, false, "truncated")
        eq(r.bytesRead, 0L, "bytesRead")
    }

    check("A7 maxBytes ≤ 0 当 1（不抛异常、不返回空）") {
        eq(BoundedBody.read(streamOf("abc"), 0).text, "a", "maxBytes=0")
        eq(BoundedBody.read(streamOf("abc"), -5).text, "a", "maxBytes=-5")
    }

    check("A8 maxBytes 传 Long.MAX_VALUE：硬顶仍是 8MB，不会去分配一个天文数字") {
        val r = BoundedBody.read(streamOf("z".repeat(1000)), Long.MAX_VALUE)
        eq(r.bytesRead, 1000L, "读完")
        eq(r.truncated, false, "truncated")
        eq(BoundedBody.MAX_READ_BYTES, 8L * 1024 * 1024, "硬顶常量")
    }

    check("A9 读到一半 IO 失败：交出已读部分并标记截断，不抛给调用方") {
        val boom = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i++ < 10) 'a'.code else throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= 10) throw IOException("boom")
                val n = minOf(len, 10 - i)
                for (k in 0 until n) b[off + k] = 'a'.code.toByte()
                i += n
                return n
            }
        }
        val r = BoundedBody.read(boom, 1024)
        eq(r.text, "a".repeat(10), "已读部分")
        eq(r.truncated, true, "truncated")
    }

    println("\n[B] 内存有界（本进程以 -Xmx64m 启动）")
    check("B1 永远读不完的流 + 2MB 上限：读满即停，不随流长度增长") {
        val r = BoundedBody.read(infiniteStream(), 2L * 1024 * 1024)
        eq(r.bytesRead, 2L * 1024 * 1024, "只读上限那么多")
        eq(r.truncated, true, "truncated")
        eq(r.text.length, 2 * 1024 * 1024, "字符数（ASCII 一字节一字符）")
    }

    println()
    if (failures.isNotEmpty()) {
        println("失败清单：")
        failures.forEach { println("  - $it") }
    }
    println("=== 合计 $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
