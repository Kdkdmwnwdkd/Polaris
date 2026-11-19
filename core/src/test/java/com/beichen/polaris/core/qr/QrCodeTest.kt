package com.beichen.polaris.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 二维码编码器的行为断言（2026-11-19）。
 *
 * ## 判据从哪来（这一条最重要）
 *
 * 二维码「编错了」不会抛异常，只会生成一张扫不出来的图。所以判据只能是**解一遍**。
 * 而自己写的解码器可能是「同一套逻辑的另一份拷贝」——编码器把 A 放错到 B，解码器
 * 也从 B 读，两边一起错，测试照样全绿。
 *
 * 因此这里用两级锚点：
 *
 * 1. [QrFixtures] 是 **python-qrcode 5.x** 生成的矩阵。解码器能把这些解回原文，
 *    说明它按标准读（表、位序、掩码、反交错都对），而不是「跟我们一致」。
 * 2. 再用它解 [QrCode.encode] 的输出，并与 fixture **逐格比对**。
 *
 * 第 2 步的逐格比对是本轮发现缺陷的地方：修复前 v1/v7/v13 三组各差 1 格，位置都在
 * `(row 8, col size-8)`。
 *
 * ## 缺陷：第二份格式信息恒定缺一位
 *
 * 格式信息 15 位在两处冗余。第二处是「row 8 的 col size-8..size-1」（8 位）+
 * 「col 8 的 row size-7..size-1」（7 位）。原实现两段都按 8 位写：
 *
 * ```kotlin
 * for (i in 0..7) m.set(size - 1 - i, 8, bits[i])      // 写到 row size-8（数据区）
 * for (i in 8..14) m.set(8, size - 15 + i, bits[i])    // 从 col size-7 起，漏了 size-8
 * ```
 *
 * 结果位 7（落在 row8 / col size-8）**两个地方都没写**，而 row size-8 / col 8 反而
 * 覆盖了一格数据位。缺的是 BCH 校验位，所以解码器能靠 BCH 纠回来（错 1 位 ≤ 3），
 * **扫得出来**——这正是它一直没被发现的原因。代价是这份冗余的纠错余量从 3 位降到 2 位：
 * 第一份一旦被污损 4 位，原本还有第二份兜底，现在兜不住。
 *
 * 所以断言不看「能不能扫」，而看 [QrDecoder.Decoded.formatRawEqual]：**两份原始值必须
 * 逐位相同**（ISO 18004 §8.9 的硬性要求）。这条断言与掩码无关，八个掩码各跑一遍。
 *
 * ## 另一个缺陷：容量多报 1 字节
 *
 * `capacityBytes` 原写 `dataCodewords - 2`，把 20 位头（v10+ 的 16 位计数）当 16 位算。
 * L 档报 426（实为 425）、M 档报 332（实为 331）。界面拿它当上限提示时，用户按提示
 * 填满会在 `encode` 里吃到 `QrTooLongException`——**提示与行为不一致**。
 */
class QrCodeTest {

    // —— 工具 ——

    private fun matrixOf(m: QrCode.Matrix): Array<BooleanArray> =
        Array(m.size) { r -> BooleanArray(m.size) { c -> m[r, c] } }

    private fun decodeEncoded(text: String, level: Int): QrDecoder.Decoded =
        QrDecoder.decode(matrixOf(QrCode.encode(text, level)))

    /** 逐格差异，用于失败时给出**位置**而不只是「不相等」。 */
    private fun diff(a: Array<BooleanArray>, b: Array<BooleanArray>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (r in a.indices) for (c in a.indices) if (a[r][c] != b[r][c]) out.add(r to c)
        return out
    }

    // ================= 组 1：解码器本身（外部锚点） =================

    @Test
    fun `参考矩阵能被解回原文`() {
        for (f in QrFixtures.ALL) {
            val d = QrDecoder.decode(f.matrix())
            assertEquals("v${f.size} 文本不符", f.text, d.text)
        }
    }

    @Test
    fun `参考矩阵的版本级别与掩码被正确读出`() {
        for (f in QrFixtures.ALL) {
            val d = QrDecoder.decode(f.matrix())
            assertEquals("版本", (f.size - 17) / 4, d.version)
            assertEquals("级别", f.level, d.level)
            assertEquals("掩码", f.mask, d.mask)
        }
    }

    @Test
    fun `参考矩阵的两份格式信息逐位相同且无需纠错`() {
        for (f in QrFixtures.ALL) {
            val d = QrDecoder.decode(f.matrix())
            assertTrue("v${d.version} 两份格式信息原始值不同", d.formatRawEqual)
            assertEquals("v${d.version} 参考矩阵不该需要纠错", 0, d.formatDistance)
        }
    }

    @Test
    fun `参考矩阵的纠错码字自洽（RS 校验子全零）`() {
        for (f in QrFixtures.ALL) {
            val d = QrDecoder.decode(f.matrix())
            assertTrue("v${d.version} L${d.level} 的 RS 校验子非零", d.syndromesClean)
        }
    }

    // ================= 组 2：与参考实现逐格相同 =================

    @Test
    fun `编码结果与参考实现逐格相同`() {
        for (f in QrFixtures.ALL) {
            val m = QrCode.encode(f.text, f.level)
            assertEquals("尺寸", f.size, m.size)
            val d = diff(matrixOf(m), f.matrix())
            if (d.isNotEmpty()) {
                fail(
                    "v${(f.size - 17) / 4} L${f.level} 与参考矩阵差 ${d.size} 格：" +
                        d.take(6).joinToString { "(r${it.first},c${it.second})" }
                )
            }
        }
    }

    @Test
    fun `编码结果的两份格式信息逐位相同`() {
        for (f in QrFixtures.ALL) {
            val d = decodeEncoded(f.text, f.level)
            assertTrue(
                "v${d.version} mask=${d.mask} 两份格式信息原始值不同" +
                    "（少写一位会让这一份恒定要靠 BCH 纠错）",
                d.formatRawEqual
            )
        }
    }

    // ================= 组 3：八种掩码全覆盖 =================

    /**
     * 缺陷与掩码有关：位 7 的**值**随掩码变化，若某个掩码下它恰好是 0，旧实现不露馅。
     * 所以八个掩码必须各跑一遍，只覆盖「恰好露馅的那几个」等于没覆盖。
     */
    private val maskCases = listOf(
        Triple("8", 0, 4),
        Triple("Kfw", 0, 7),
        Triple("0ax:", 0, 3),
        Triple("g2", 0, 2),
        Triple("R?j9Oj", 0, 5),
        Triple(".EU3Nl-u", 0, 1),
        Triple("UXZAX?RnT", 0, 0),
        Triple("FkHo7noy-fr8ii7:S", 0, 6)
    )

    @Test
    fun `八种掩码下格式信息都完整`() {
        val seen = HashSet<Int>()
        for ((text, level, expectMask) in maskCases) {
            val d = decodeEncoded(text, level)
            assertEquals("「$text」的掩码选择变了（评分逻辑改动会走到这里）", expectMask, d.mask)
            assertTrue("mask=$expectMask 两份格式信息不同", d.formatRawEqual)
            assertEquals("mask=$expectMask 文本", text, d.text)
            seen.add(d.mask)
        }
        assertEquals("八种掩码没有全部覆盖", 8, seen.size)
    }

    // ================= 组 4：版本覆盖（v1..v13 的块结构） =================

    /**
     * 块结构表是最容易抄错的东西：抄错一格，反交错就整体错位，而**编码器自己不会报错**。
     * 这里用「刚好填满该版本 L 档」的输入逼出每个版本，再由解码器验证 RS 校验子——
     * 表错了校验子必然非零。
     */
    private val versionCases = listOf(
        53 to 29,    // v3
        106 to 37,   // v5
        134 to 41,   // v6
        154 to 45,   // v7（第一个带版本信息的版本）
        192 to 49,   // v8
        230 to 53,   // v9
        321 to 61,   // v11（v10+ 计数 16 位，容量比 dataCodewords-2 少）
        367 to 65    // v12
    )

    @Test
    fun `每个版本的块结构都能被独立解码器还原`() {
        for ((len, size) in versionCases) {
            val text = "V".repeat(len)
            val m = QrCode.encode(text, 0)
            assertEquals("长度 $len 应选 v${(size - 17) / 4}（size=$size）", size, m.size)
            val d = QrDecoder.decode(matrixOf(m))
            assertEquals("v${d.version} 文本", text, d.text)
            assertTrue("v${d.version} RS 校验子非零——块结构表错了", d.syndromesClean)
            assertTrue("v${d.version} 两份格式信息不同", d.formatRawEqual)
        }
    }

    @Test
    fun `v7 及以上的版本信息两份一致且高位等于版本号`() {
        for ((len, size) in versionCases) {
            if (size < 45) continue   // v7 = 45
            val m = QrCode.encode("V".repeat(len), 0)
            val version = (size - 17) / 4
            val left = readVersionInfo(m, size, leftBottom = true)
            val right = readVersionInfo(m, size, leftBottom = false)
            assertEquals("v$version 版本信息两份不同", left, right)
            assertEquals("v$version 版本信息高 6 位", version, left shr 12)
        }
    }

    /** 读 18 位版本信息：左下 3 行×6 列（i%3 列、i/3 行）与右上转置。 */
    private fun readVersionInfo(m: QrCode.Matrix, size: Int, leftBottom: Boolean): Int {
        var v = 0
        for (i in 0 until 18) {
            val bit = if (leftBottom) m[size - 11 + i % 3, i / 3] else m[i / 3, size - 11 + i % 3]
            if (bit) v = v or (1 shl i)
        }
        return v
    }

    // ================= 组 5：容量与边界 =================

    @Test
    fun `容量按 16 位计数头算，不是 dataCodewords 减 2`() {
        // v13/L：428 码字 → (428×8−20)/8 = 425；v13/M：334 → 331
        assertEquals(425, QrCode.capacityBytes(0))
        assertEquals(331, QrCode.capacityBytes(1))
    }

    @Test
    fun `满容量可以编码，多一字节必须抛出而不是静默截断`() {
        for (level in 0..1) {
            val cap = QrCode.capacityBytes(level)
            val m = QrCode.encode("Z".repeat(cap), level)
            assertEquals("满容量应落在 v13", 69, m.size)
            try {
                QrCode.encode("Z".repeat(cap + 1), level)
                fail("超容量没有抛异常（静默截断或越界）")
            } catch (e: QrCode.QrTooLongException) {
                assertEquals(cap + 1, e.bytes)
            }
        }
    }

    @Test
    fun `空串可以编码且解回空串`() {
        val d = decodeEncoded("", 1)
        assertEquals("", d.text)
        assertEquals(1, d.version)
    }

    @Test
    fun `长度一变化就选到能装下的最小版本`() {
        // v1/L 数据 19 码字，头 12 位 → 最多 18 字节；(19-2)*8=136 > 12 + 8*18? 见断言
        assertEquals(21, QrCode.encode("a".repeat(17), 0).size)
        assertEquals(25, QrCode.encode("a".repeat(18), 0).size)
    }

    // ================= 组 6：结构性质（不依赖解码器） =================

    @Test
    fun `三个定位图形与分隔符`() {
        val m = QrCode.encode("HELLO", 1)
        val size = m.size
        for ((r0, c0) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            for (r in 0 until 7) for (c in 0 until 7) {
                val ring = r == 0 || r == 6 || c == 0 || c == 6
                val core = r in 2..4 && c in 2..4
                assertEquals("定位图形 ($r0,$c0) 内 ($r,$c)", ring || core, m[r0 + r, c0 + c])
            }
        }
        // 分隔符（定位图形外一圈）必须是白
        for (i in 0..7) {
            assertFalse("分隔符 row7 col$i", m[7, i])
            assertFalse("分隔符 row$i col7", m[i, 7])
        }
    }

    @Test
    fun `时序图形逐格交替`() {
        val m = QrCode.encode("HELLO", 1)
        val size = m.size
        for (i in 8 until size - 8) {
            assertEquals("row6 col$i", i % 2 == 0, m[6, i])
            assertEquals("col6 row$i", i % 2 == 0, m[i, 6])
        }
    }

    @Test
    fun `暗模块恒为深`() {
        for (f in QrFixtures.ALL) {
            val m = QrCode.encode(f.text, f.level)
            assertTrue("v${(f.size - 17) / 4} 的暗模块应为深", m[m.size - 8, 8])
        }
    }

    @Test
    fun `对齐图形出现在 v2 及以上`() {
        // v2/M 能装 27 字节；30 字节会落到 v3（size 29），下面断言 (18,18) 就取错位置了
        val m = QrCode.encode("a".repeat(20), 1)
        assertEquals(25, m.size)
        // v2 的对齐图形中心 (18,18)，5×5 外框深、中心深、中间白
        for (dr in -2..2) for (dc in -2..2) {
            val expect = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
            assertEquals("对齐图形 ($dr,$dc)", expect, m[18 + dr, 18 + dc])
        }
    }

    // ================= 组 7：UTF-8 =================

    @Test
    fun `中文往返`() {
        val text = "配置：glm-4-九b"
        val d = decodeEncoded(text, 0)
        assertEquals(text, d.text)
        assertEquals("UTF-8 中文字节数", 19, text.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `四字节 emoji 往返`() {
        val text = "模型：🧠🚀 完成"
        val d = decodeEncoded(text, 1)
        assertEquals(text, d.text)
    }

    @Test
    fun `满容量的中文也能往返`() {
        val text = "配".repeat(QrCode.capacityBytes(1) / 3)
        val d = decodeEncoded(text, 1)
        assertEquals(text, d.text)
    }

    @Test
    fun `超长的中文抛异常而不是截断`() {
        try {
            QrCode.encode("配".repeat(200), 1)   // 600 字节 > 331
            fail("应抛 QrTooLongException")
        } catch (e: QrCode.QrTooLongException) {
            assertEquals(600, e.bytes)
        }
    }

    // ================= 组 8：异常消息 =================

    /** 消息里的档位必须与**真实**档位一致：写死一个，另一个档位就在说反话。 */
    @Test
    fun `异常里的档位与真实档位一致`() {
        for ((level, tag) in listOf(0 to "/L", 1 to "/M")) {
            try {
                QrCode.encode("Z".repeat(QrCode.capacityBytes(level) + 1), level)
                fail("lvl=$level 应抛异常")
            } catch (e: QrCode.QrTooLongException) {
                assertEquals("异常带出的档位", level, e.level)
                assertNotNull(e.message)
                assertTrue("lvl=$level 的消息应含 $tag：${e.message}", e.message!!.contains(tag))
            }
        }
    }
}
