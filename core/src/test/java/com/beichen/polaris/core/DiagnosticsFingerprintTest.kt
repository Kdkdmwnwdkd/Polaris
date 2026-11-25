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
}
