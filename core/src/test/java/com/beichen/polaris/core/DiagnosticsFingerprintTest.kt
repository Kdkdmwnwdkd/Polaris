package com.beichen.polaris.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断包正文指纹的行为断言（2026-11-25）。
 *
 * ## 这批断言锁的是什么
 *
 * 不是「SHA-256 算得对」——那个不会错。锁的是**判据的形状**：
 *
 * 1. 长度固定 [DiagnosticsFingerprint.HEX_CHARS] 位、全小写；
 * 2. 同一份字节两次算出来一样（否则日志与文件里的两份值天然对不上）；
 * 3. **换一个字节就变**——这是它能识破「4KB 块重放」的前提；
 * 4. 真机那份坏文件的形状：正文 + 往前 4096 字节处的重放。这条必须能分辨，
 *    否则加了这个指纹也还是白搭。
 *
 * 第 4 条是从 2026-11-25 的现场反推出来的：diagnostics 28672 字节里，
 * 末 1933 字节与偏移 `26739 - 4096` 处逐字节相同，`turns` / `events` 同样。
 * 三个文件大小都是 4096 的整数倍，而同一次会话「复制文本」存出来的
 * 54226 字节文件完好——写入端对、传输端错。
 */
class DiagnosticsFingerprintTest {

    /** `SHA-256("")` 的前 8 字节。空输入也要给一个确定值，别返回空串。 */
    @Test
    fun `空正文的指纹是确定的`() {
        assertEquals("e3b0c44298fc1c14", DiagnosticsFingerprint.of(ByteArray(0)))
    }

    /** 标准向量 `SHA-256("abc")`：防止有人把摘要当输入、或截错了字节序。 */
    @Test
    fun `已知向量 abc`() {
        assertEquals("ba7816bf8f01cfea", DiagnosticsFingerprint.of("abc".toByteArray()))
    }

    @Test
    fun `长度恒为十六位且全小写`() {
        val samples = listOf(
            "",
            "a",
            "北辰诊断包",
            "x".repeat(5000),
        )
        for (s in samples) {
            val f = DiagnosticsFingerprint.of(s.toByteArray(Charsets.UTF_8))
            assertEquals("样本长度=${s.length}", DiagnosticsFingerprint.HEX_CHARS.toLong(), f.length.toLong())
            assertTrue("样本长度=${s.length} 出现非小写十六进制字符：$f", f.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    /** 同一份字节两次必须一致——日志里那份和文件里那份靠这个才能对拍。 */
    @Test
    fun `同内容两次结果相同`() {
        val bytes = "诊断包正文".repeat(300).toByteArray(Charsets.UTF_8)
        assertEquals(DiagnosticsFingerprint.of(bytes), DiagnosticsFingerprint.of(bytes.copyOf()))
    }

    /** 换一个字节必须变。碰撞概率与「把两份不同的文件当成同一份」是同一件事。 */
    @Test
    fun `单字节改动即改变指纹`() {
        val a = "第一份正文".toByteArray(Charsets.UTF_8)
        val b = a.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(DiagnosticsFingerprint.of(a), DiagnosticsFingerprint.of(b))
    }

    /**
     * 核心回归：**真机那份坏文件的形状**。
     *
     * 正文 `N` 字节，坏文件是 `正文 + 正文[N-4096, N-4096+1933)`。
     * 两个指纹必须不同——否则这个字段根本区分不了「传坏的」和「原样的」。
     */
    @Test
    fun `块重放出来的文件指纹与原文件不同`() {
        // 造一段比 4096 长的正文，模拟一份日志尾部。
        val body = buildString {
            var i = 0
            while (length < 20_000) {
                append(i++).append(" 行内容，用来把正文撑过 4096 字节\n")
            }
        }.toByteArray(Charsets.UTF_8)

        val replayLen = 1933
        val from = body.size - 4096
        val replayed = body + body.copyOfRange(from, from + replayLen)

        assertEquals((body.size + replayLen).toLong(), replayed.size.toLong())
        assertNotEquals(
            "重放后的文件必须与原文件指纹不同，否则这个字段无法识破传输损坏",
            DiagnosticsFingerprint.of(body),
            DiagnosticsFingerprint.of(replayed),
        )
    }

    /** 按「正文字节数」截断后必须还原出原指纹——这是排障时要做的那一步。 */
    @Test
    fun `截到正文字节数可还原指纹`() {
        val body = "日志正文".repeat(2000).toByteArray(Charsets.UTF_8)
        val original = DiagnosticsFingerprint.of(body)

        val garbage = body + body.copyOfRange(0, 700)
        val recovered = garbage.copyOfRange(0, body.size)

        assertEquals(original, DiagnosticsFingerprint.of(recovered))
    }

    /** 算法不可用时给占位而不是抛异常：导出路径上不能为了一个日志字段整单失败。 */
    @Test
    fun `占位值不与任何真实指纹形状冲突`() {
        assertFalse(DiagnosticsFingerprint.UNAVAILABLE.length == DiagnosticsFingerprint.HEX_CHARS)
    }

    /**
     * 自校验段里写的「完整文件应为」必须**精确等于**真实拼接结果。
     *
     * 这一条守的是自指：段里那个数字包含它自己的位数，改一位数就换一段长度。
     * 取值覆盖 1/2/3/4/5/6 位正文长度——位数每跳一次都会让段长变一字节，
     * 迭代少算一轮就会写出一个「差 1」的数字，而差 1 已经足够让用户判错。
     */
    @Test
    fun `自校验段声明的完整大小与真实拼接一致`() {
        // 0 与各数量级边界：位数跳变恰好发生在 10 的幂和 10 的幂减一之间
        val sizes = listOf(0, 1, 9, 10, 99, 100, 999, 1_000, 9_999, 10_000, 99_999, 100_000, 800_000)
        for (n in sizes) {
            val body = ByteArray(n) { 'x'.code.toByte() }
            for (endsWithNewline in listOf(true, false)) {
                val bodyText = String(body, Charsets.UTF_8)
                val seal = DiagnosticsFingerprint.sealOf(body, endsWithNewline)
                val actual = (bodyText + seal).toByteArray(Charsets.UTF_8).size
                val declared = Regex("完整文件应为：(\\d+) 字节")
                    .find(seal)?.groupValues?.get(1)?.toIntOrNull()
                val label = "正文 $n 字节 / 结尾换行=$endsWithNewline"
                assertTrue("$label 段里没有『完整文件应为』这一行", declared != null)
                assertEquals(label, actual.toLong(), declared!!.toLong())
            }
        }
    }

    /** 正文没有结尾换行时，段首要先补一个，保证「自校验」前面空一行。 */
    @Test
    fun `正文不以换行结尾时段首补换行`() {
        val noNl = DiagnosticsFingerprint.sealOf("abc".toByteArray(Charsets.UTF_8), bodyEndsWithNewline = false)
        assertTrue("段首应为两个换行（补的一个 + 空行）", noNl.startsWith("\n\n—— 自校验 ——"))

        val withNl = DiagnosticsFingerprint.sealOf("abc\n".toByteArray(Charsets.UTF_8), bodyEndsWithNewline = true)
        assertTrue("已有换行时只留空行那一个", withNl.startsWith("\n—— 自校验 ——"))
    }

    /** 段里那份指纹必须与 `of(正文)` 一致，否则文件里和日志里两份值天然对不上。 */
    @Test
    fun `段内指纹与正文指纹同源`() {
        val body = "北辰诊断包正文".toByteArray(Charsets.UTF_8)
        val seal = DiagnosticsFingerprint.sealOf(body, bodyEndsWithNewline = true)
        assertTrue(seal.contains("正文指纹：${DiagnosticsFingerprint.of(body)}"))
        assertTrue(seal.contains("正文字节数：${body.size}"))
    }
}
