package com.beichen.polaris.core.qr

/**
 * W-RT8：零依赖二维码生成器（纯 Kotlin）——配置导入导出的编码侧。
 *
 * 为什么自写：工程纪律零新增第三方依赖，而二维码编码是纯算法（伽罗瓦域
 * 纠错 + 摆点 + 掩码评分），沙箱可完整验证——对拍 Oracle 用现成解码库，
 * 编码结果逐一解码回读比对。
 *
 * 范围：字节模式，版本 1–13，纠错档 L / M。配置负载几百字节，够用；
 * 超出容量抛 [QrCode.QrTooLongException]，调用方引导用户精简配置，
 * 绝不静默截断——截断出的码扫出来是残缺配置，比扫不出更坑。
 *
 * 实现依据：ISO/IEC 18004；纠错块结构表与对齐坐标对照权威开源实现
 * （ZXing core 3.5.3 Version.java）逐项核对。
 */
object QrCode {

    /** 生成的二维码：size×size 模块方阵，true = 深色。 */
    class Matrix(val size: Int) {
        private val dark = BooleanArray(size * size)
        operator fun get(row: Int, col: Int): Boolean = dark[row * size + col]
        fun set(row: Int, col: Int, value: Boolean) { dark[row * size + col] = value }
    }

    /** 负载超过全部支持版本的容量时抛出（不静默截断）。 */
    class QrTooLongException(val bytes: Int) :
        Exception("payload $bytes bytes exceeds max capacity (v13/L)")

    // —— GF(256)，本原多项式 0x11D（QR 标准）——
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gmul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** 纠错码字：对数据码字多项式按生成多项式做综合除法取余（RS 系）。 */
    private fun rsEncode(data: ByteArray, ecLen: Int): ByteArray {
        // 生成多项式系数（首项恒 1）：(x-α^0)(x-α^1)...(x-α^(ecLen-1)) 展开
        var gen = intArrayOf(1)
        for (i in 0 until ecLen) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor gmul(gen[j], EXP[i])
            }
            gen = next
        }
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            for (j in 1 until ecLen) rem[j - 1] = rem[j]
            rem[ecLen - 1] = 0
            for (j in 0 until ecLen) rem[j] = rem[j] xor gmul(factor, gen[j + 1])
        }
        return ByteArray(ecLen) { rem[it].toByte() }
    }

    // —— 版本表：对齐坐标 + 纠错块结构（档 0 = L，档 1 = M；对照 ZXing Version.java）——

    private class BlockSpec(val ecPerBlock: Int, val groups: List<Pair<Int, Int>>) {
        val dataCodewords: Int = groups.sumOf { it.first * it.second }
        val ecCodewords: Int = groups.sumOf { it.first * ecPerBlock }
    }

    private class Ver(
        val version: Int,
        val alignment: IntArray,
        val low: BlockSpec,
        val mid: BlockSpec
    ) {
        val size: Int = 17 + 4 * version
        val totalCodewords: Int = low.dataCodewords + low.ecCodewords
        fun spec(level: Int): BlockSpec = if (level == 0) low else mid
    }

    private val VERSIONS = listOf(
        Ver(1, intArrayOf(), BlockSpec(7, listOf(1 to 19)), BlockSpec(10, listOf(1 to 16))),
        Ver(2, intArrayOf(6, 18), BlockSpec(10, listOf(1 to 34)), BlockSpec(16, listOf(1 to 28))),
        Ver(3, intArrayOf(6, 22), BlockSpec(15, listOf(1 to 55)), BlockSpec(26, listOf(1 to 44))),
        Ver(4, intArrayOf(6, 26), BlockSpec(20, listOf(1 to 80)), BlockSpec(18, listOf(2 to 32))),
        Ver(5, intArrayOf(6, 30), BlockSpec(26, listOf(1 to 108)), BlockSpec(24, listOf(2 to 43))),
        Ver(6, intArrayOf(6, 34), BlockSpec(18, listOf(2 to 68)), BlockSpec(16, listOf(4 to 27))),
        Ver(7, intArrayOf(6, 22, 38), BlockSpec(20, listOf(2 to 78)), BlockSpec(18, listOf(4 to 31))),
        Ver(8, intArrayOf(6, 24, 42), BlockSpec(24, listOf(2 to 97)),
            BlockSpec(22, listOf(2 to 38, 2 to 39))),
        Ver(9, intArrayOf(6, 26, 46), BlockSpec(30, listOf(2 to 116)),
            BlockSpec(22, listOf(3 to 36, 2 to 37))),
        Ver(10, intArrayOf(6, 28, 50), BlockSpec(18, listOf(2 to 68, 2 to 69)),
            BlockSpec(26, listOf(4 to 43, 1 to 44))),
        Ver(11, intArrayOf(6, 30, 54), BlockSpec(20, listOf(4 to 81)),
            BlockSpec(30, listOf(1 to 50, 4 to 51))),
        Ver(12, intArrayOf(6, 32, 58), BlockSpec(24, listOf(2 to 92, 2 to 93)),
            BlockSpec(22, listOf(6 to 36, 2 to 37))),
        Ver(13, intArrayOf(6, 34, 62), BlockSpec(26, listOf(4 to 107)),
            BlockSpec(22, listOf(8 to 37, 1 to 38))),
    )

    /** 某档位能装的最大字节数（末版数据码字 − 模式与计数头保守 2 码字）。 */
    fun capacityBytes(level: Int): Int = VERSIONS.last().spec(level).dataCodewords - 2

    /** 主入口：文本 → 二维码矩阵。选能装下的最小版本。 */
    fun encode(text: String, level: Int = 1): Matrix {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > capacityBytes(level)) throw QrTooLongException(data.size)
        val ver = VERSIONS.firstOrNull { v ->
            dataCodewordNeed(data.size, v.version) <= v.spec(level).dataCodewords
        } ?: throw QrTooLongException(data.size)

        val spec = ver.spec(level)
        val totalData = spec.dataCodewords

        // 比特流：模式 0100（字节模式）+ 计数 + 数据 + 终止符 + 补齐
        val bits = BitWriter()
        bits.write(0b0100, 4)
        if (ver.version <= 9) bits.write(data.size, 8) else bits.write(data.size, 16)
        data.forEach { bits.write(it.toInt() and 0xFF, 8) }
        repeat(minOf(4, totalData * 8 - bits.length).coerceAtLeast(0)) { bits.write(0, 1) }
        while (bits.length % 8 != 0) bits.write(0, 1)
        var padIdx = 0
        while (bits.length < totalData * 8) { bits.write(if (padIdx % 2 == 0) 0xEC else 0x11, 8); padIdx++ }
        val dataBytes = bits.toBytes()

        // 分块纠错 + 交错
        val blocks = mutableListOf<ByteArray>()
        var offset = 0
        spec.groups.forEach { (count, dataCw) ->
            repeat(count) {
                blocks.add(dataBytes.copyOfRange(offset, offset + dataCw))
                offset += dataCw
            }
        }
        val ecBytes = blocks.map { rsEncode(it, spec.ecPerBlock) }
        val interleaved = ByteArray(spec.dataCodewords + spec.ecCodewords)
        var p = 0
        val maxDataCw = spec.groups.maxOf { it.second }
        for (i in 0 until maxDataCw) {
            blocks.forEach { blk -> if (i < blk.size) interleaved[p++] = blk[i] }
        }
        for (i in 0 until spec.ecPerBlock) {
            ecBytes.forEach { ec -> interleaved[p++] = ec[i] }
        }
        return place(ver, interleaved, level)
    }

    /** 数据码字需求：4 比特模式 + 计数 + 数据，向上取整到码字。 */
    private fun dataCodewordNeed(dataLen: Int, version: Int): Int {
        val bits = 4 + (if (version <= 9) 8 else 16) + dataLen * 8
        return (bits + 7) / 8
    }

    // —— 摆点 ——
    private fun place(ver: Ver, codewords: ByteArray, level: Int): Matrix {
        val size = ver.size

        fun newBase(): Matrix {
            val m = Matrix(size)
            // 定位图形 + 分隔
            listOf(0 to 0, 0 to size - 7, size - 7 to 0).forEach { (r0, c0) ->
                for (r in -1..7) for (c in -1..7) {
                    val rr = r0 + r; val cc = c0 + c
                    if (rr in 0 until size && cc in 0 until size) {
                        m.set(rr, cc, rr in r0..r0 + 6 && cc in c0..c0 + 6 && (
                            rr == r0 || rr == r0 + 6 || cc == c0 || cc == c0 + 6 ||
                                (rr in r0 + 2..r0 + 4 && cc in c0 + 2..c0 + 4)))
                    }
                }
            }
            // 时序图形
            for (i in 8 until size - 8) {
                m.set(6, i, i % 2 == 0)
                m.set(i, 6, i % 2 == 0)
            }
            // 校正图形（跳过与定位图形重叠的三个角）
            val centers = ver.alignment
            for (r in centers) for (c in centers) {
                if ((r == 6 && c == 6) || (r == 6 && c == centers.last()) ||
                    (r == centers.last() && c == 6)) continue
                for (dr in -2..2) for (dc in -2..2) {
                    m.set(r + dr, c + dc,
                        dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0))
                }
            }
            // 版本信息（v7+）：左下 3×6 + 右上 6×3
            if (ver.version >= 7) {
                // BCH(18,6)，生成多项式 0x1F25（度 12）：逐位左移、逢第 12 位越界即消
                var rem = ver.version
                repeat(12) {
                    rem = rem shl 1
                    if (rem and 0x1000 != 0) rem = rem xor 0x1F25
                }
                val bits18 = (ver.version shl 12) or (rem and 0xFFF)
                for (i in 0 until 18) {
                    val bit = (bits18 shr i) and 1 == 1
                    m.set(size - 11 + i % 3, i / 3, bit)   // 左下
                    m.set(i / 3, size - 11 + i % 3, bit)   // 右上
                }
            }
            // 暗模块
            m.set(size - 8, 8, true)
            return m
        }

        val reserved = Array(size) { BooleanArray(size) }
        markReserved(reserved, size, ver.version)

        // 八种掩码各摆一遍，评分取最优
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        var bestMatrix: Matrix? = null
        for (mask in 0 until 8) {
            val trial = newBase()
            val dataBits = BitReader(codewords)
            var bitCount = 0
            placeData(trial, reserved) { row, col ->
                var bit = if (bitCount < codewords.size * 8) dataBits.next() else false
                bitCount++
                if (maskFn(mask, row, col)) bit = !bit
                bit
            }
            drawFormat(trial, size, level, mask)
            val score = penalty(trial, size)
            if (score < bestScore) { bestScore = score; bestMask = mask; bestMatrix = trial }
        }
        return bestMatrix!!
    }

    private fun markReserved(res: Array<BooleanArray>, size: Int, version: Int) {
        fun mark(r: Int, c: Int) { if (r in 0 until size && c in 0 until size) res[r][c] = true }
        listOf(0 to 0, 0 to size - 7, size - 7 to 0).forEach { (r0, c0) ->
            for (r in -1..7) for (c in -1..7) mark(r0 + r, c0 + c)
        }
        for (i in 0 until size) { mark(6, i); mark(i, 6) }
        val centers = alignmentCentersForSize(size)
        for (r in centers) for (c in centers) {
            if ((r == 6 && c == 6) || (r == 6 && c == centers.last()) ||
                (r == centers.last() && c == 6)) continue
            for (dr in -2..2) for (dc in -2..2) mark(r + dr, c + dc)
        }
        // 格式信息带（两份）
        for (i in 0..8) { mark(8, i); mark(i, 8) }
        for (i in 0..7) { mark(8, size - 1 - i); mark(size - 1 - i, 8) }
        // 版本信息带（v7+）：左下 3 行 × 6 列 + 右上 6 行 × 3 列
        if (version >= 7) {
            for (r in size - 11 until size - 8) for (c in 0..5) mark(r, c)
            for (r in 0..5) for (c in size - 11 until size - 8) mark(r, c)
        }
        mark(size - 8, 8) // 暗模块
    }

    private fun alignmentCentersForSize(size: Int): IntArray =
        VERSIONS.first { it.size == size }.alignment

    /** 数据摆点：之字形路径（右列先、左列后；跳过第 6 列竖时序带）。 */
    private inline fun placeData(m: Matrix, res: Array<BooleanArray>, bitFn: (Int, Int) -> Boolean) {
        val size = m.size
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col = 5
            val rowRange = if (upward) (size - 1 downTo 0) else (0 until size)
            for (r in rowRange) {
                for (c in intArrayOf(col, col - 1)) {
                    if (!res[r][c]) m.set(r, c, bitFn(r, c))
                }
            }
            upward = !upward
            col -= 2
        }
    }

    // —— 掩码函数（ISO 18004 八种）——
    private fun maskFn(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    // —— 格式信息：5 数据位 + BCH(10) 生成 0x537 + 掩码 0x5412 ——
    private fun drawFormat(m: Matrix, size: Int, level: Int, mask: Int) {
        val levelBits = when (level) { 0 -> 0b01; 1 -> 0b00; else -> 0b11 } // L M（Q/H 未开放）
        var rem = levelBits shl 3 or mask
        repeat(10) {
            rem = rem shl 1
            if (rem and 0x400 != 0) rem = rem xor 0x537
        }
        val fmt = ((levelBits shl 3 or mask) shl 10 or (rem and 0x3FF)) xor 0x5412
        val bits = IntArray(15) { fmt shr (14 - it) and 1 }
        // 第一份：绕左上
        for (i in 0..5) m.set(8, i, bits[i] == 1)
        m.set(8, 7, bits[6] == 1)
        m.set(8, 8, bits[7] == 1)
        m.set(7, 8, bits[8] == 1)
        for (i in 9..14) m.set(14 - i, 8, bits[i] == 1)
        // 第二份：右上横排 + 左下竖排
        for (i in 0..7) m.set(size - 1 - i, 8, bits[i] == 1)
        for (i in 8..14) m.set(8, size - 15 + i, bits[i] == 1)
        m.set(size - 8, 8, true) // 暗模块恒深
    }

    // —— 掩码评分（启发式：游程 / 同色块 / 仿标记 / 深色占比）——
    private fun penalty(m: Matrix, size: Int): Int {
        var score = 0
        for (axis in 0..1) {
            for (i in 0 until size) {
                var run = 1
                for (j in 1 until size) {
                    val cur = if (axis == 0) m[i, j] else m[j, i]
                    val prev = if (axis == 0) m[i, j - 1] else m[j - 1, i]
                    if (cur == prev) run++ else { if (run >= 5) score += run - 2; run = 1 }
                }
                if (run >= 5) score += run - 2
            }
        }
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val v = m[r, c]
            if (m[r, c + 1] == v && m[r + 1, c] == v && m[r + 1, c + 1] == v) score += 3
        }
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        for (r in 0 until size) for (c in 0 until size - 6) {
            var ok = true
            for (k in 0..6) if (m[r, c + k] != pattern[k]) { ok = false; break }
            if (ok) score += 40
        }
        for (c in 0 until size) for (r in 0 until size - 6) {
            var ok = true
            for (k in 0..6) if (m[r + k, c] != pattern[k]) { ok = false; break }
            if (ok) score += 40
        }
        var dark = 0
        for (r in 0 until size) for (c in 0 until size) if (m[r, c]) dark++
        score += kotlin.math.abs(dark * 100 / (size * size) - 50) / 5 * 10
        return score
    }
}

/** 比特写出器。 */
class BitWriter {
    private val bits = ArrayList<Int>(512)
    val length: Int get() = bits.size
    fun write(value: Int, count: Int) {
        for (i in count - 1 downTo 0) bits.add(value shr i and 1)
    }
    fun toBytes(): ByteArray {
        val out = ByteArray(bits.size / 8)
        for (i in out.indices) {
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or bits[i * 8 + j]
            out[i] = b.toByte()
        }
        return out
    }
}

/** 比特读出器（支持重置重读——八种掩码各摆同一份数据）。 */
class BitReader(private val data: ByteArray) {
    private var pos = 0
    fun reset() { pos = 0 }
    fun next(): Boolean {
        if (pos >= data.size * 8) return false
        val bit = (data[pos / 8].toInt() shr (7 - pos % 8)) and 1
        pos++
        return bit == 1
    }
}
