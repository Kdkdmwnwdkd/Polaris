package com.beichen.polaris.core.qr

/**
 * 独立实现的二维码**解码器**（仅测试用，不进生产代码）。
 *
 * ## 为什么必须有它
 *
 * `QrCode` 是纯算法：编错了不会抛异常，只会生成一张扫不出来的图。而「扫得出来」
 * 这件事没有任何本地断言能证明——除非真的解一遍。
 *
 * 自己写解码器的风险是「用同一套逻辑验证自己」：如果解码器照抄编码器的表与位序，
 * 编码器把 A 放错到 B、解码器也从 B 读，两边一起错，测试照样全绿。所以这里刻意做到：
 *
 * | 依赖 | 来源 | 与 `QrCode` 的关系 |
 * |---|---|---|
 * | 对齐坐标 / 纠错块结构表 | python-qrcode 5.x 导出 | **独立副本**，两边不一致会立刻红 |
 * | 格式信息位序 | ISO 18004 §8.9 图 25，从「位编号 → 坐标」重写 | 不同写法 |
 * | 数据位序 / 掩码 | 从标准重写（之字形、8 个掩码函数） | 不同写法 |
 * | GF(256) | 本原多项式 0x11D（数学常数） | 无法避免相同，但只是常数 |
 * | **RS 校验** | 本文件独有（编码器只算不清） | 解码器多一道独立检查 |
 *
 * 锚点链条：`QrFixtures`（python-qrcode 生成）→ 解得出原文 ⇒ 解码器可信；
 * 解码器再解 `QrCode.encode()` 的输出 ⇒ 编码器可信。两头都不是自证。
 *
 * ## 它检查什么
 *
 * - 版本（由尺寸推得）与格式信息（两份分别做 BCH 纠错，并比较原始值是否逐位相同）
 * - 每个纠错块的 RS 校验子是否全零（码字本身正确的**必要**条件）
 * - 字节模式的模式位、字符计数位宽（v10+ 是 16 位）与内容
 */
internal object QrDecoder {

    class Decoded(
        val text: String,
        val version: Int,
        /** 0 = L，1 = M。 */
        val level: Int,
        val mask: Int,
        /** 两份格式信息的**原始**值是否逐位相同（ISO 要求相同）。 */
        val formatRawEqual: Boolean,
        /** 两份各自纠错后得到的值是否一致。 */
        val formatCorrectedEqual: Boolean,
        /** 与最近合法格式码的汉明距离（≤3 才是「纠错成功」，>3 说明靠猜）。 */
        val formatDistance: Int,
        /** 所有纠错块的 RS 校验子是否全零。 */
        val syndromesClean: Boolean
    )

    class QrDecodeException(message: String) : Exception(message)

    // —— 独立副本：对齐坐标（python-qrcode util.PATTERN_POSITION_TABLE）——
    private val ALIGN: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(),
        2 to intArrayOf(6, 18),
        3 to intArrayOf(6, 22),
        4 to intArrayOf(6, 26),
        5 to intArrayOf(6, 30),
        6 to intArrayOf(6, 34),
        7 to intArrayOf(6, 22, 38),
        8 to intArrayOf(6, 24, 42),
        9 to intArrayOf(6, 26, 46),
        10 to intArrayOf(6, 28, 50),
        11 to intArrayOf(6, 30, 54),
        12 to intArrayOf(6, 32, 58),
        13 to intArrayOf(6, 34, 62)
    )

    private class Spec(val ecPerBlock: Int, val groups: List<Pair<Int, Int>>) {
        val dataCodewords: Int = groups.sumOf { it.first * it.second }
        val ecCodewords: Int = groups.sumOf { it.first * ecPerBlock }
    }

    // —— 独立副本：纠错块结构（python-qrcode base.RS_BLOCK_TABLE）——
    private val SPEC: Map<Pair<Int, Int>, Spec> = mapOf(
        (1 to 0) to Spec(7, listOf(1 to 19)),
        (1 to 1) to Spec(10, listOf(1 to 16)),
        (2 to 0) to Spec(10, listOf(1 to 34)),
        (2 to 1) to Spec(16, listOf(1 to 28)),
        (3 to 0) to Spec(15, listOf(1 to 55)),
        (3 to 1) to Spec(26, listOf(1 to 44)),
        (4 to 0) to Spec(20, listOf(1 to 80)),
        (4 to 1) to Spec(18, listOf(2 to 32)),
        (5 to 0) to Spec(26, listOf(1 to 108)),
        (5 to 1) to Spec(24, listOf(2 to 43)),
        (6 to 0) to Spec(18, listOf(2 to 68)),
        (6 to 1) to Spec(16, listOf(4 to 27)),
        (7 to 0) to Spec(20, listOf(2 to 78)),
        (7 to 1) to Spec(18, listOf(4 to 31)),
        (8 to 0) to Spec(24, listOf(2 to 97)),
        (8 to 1) to Spec(22, listOf(2 to 38, 2 to 39)),
        (9 to 0) to Spec(30, listOf(2 to 116)),
        (9 to 1) to Spec(22, listOf(3 to 36, 2 to 37)),
        (10 to 0) to Spec(18, listOf(2 to 68, 2 to 69)),
        (10 to 1) to Spec(26, listOf(4 to 43, 1 to 44)),
        (11 to 0) to Spec(20, listOf(4 to 81)),
        (11 to 1) to Spec(30, listOf(1 to 50, 4 to 51)),
        (12 to 0) to Spec(24, listOf(2 to 92, 2 to 93)),
        (12 to 1) to Spec(22, listOf(6 to 36, 2 to 37)),
        (13 to 0) to Spec(26, listOf(4 to 107)),
        (13 to 1) to Spec(22, listOf(8 to 37, 1 to 38))
    )

    // —— GF(256) ——
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

    private fun mul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** 格式信息的 32 个合法值（level 2 位 × mask 3 位，BCH(15,5) + 掩码 0x5412）。 */
    private val FORMAT_CODES: IntArray = IntArray(32) { i ->
        val levelBits = i shr 3
        val mask = i and 7
        var rem = (levelBits shl 3) or mask
        repeat(10) {
            rem = rem shl 1
            if (rem and 0x400 != 0) rem = rem xor 0x537
        }
        (((levelBits shl 3) or mask) shl 10 or (rem and 0x3FF)) xor 0x5412
    }

    private fun popcount(v: Int): Int {
        var x = v
        var n = 0
        while (x != 0) { n += x and 1; x = x shr 1 }
        return n
    }

    /** 最近合法格式码的距离与修正值。距离 > 3 表示 BCH 纠不动（只能猜）。 */
    private fun correctFormat(raw: Int): Pair<Int, Int> {
        var best = Int.MAX_VALUE
        var bestCode = raw
        for (code in FORMAT_CODES) {
            val d = popcount(code xor raw)
            if (d < best) { best = d; bestCode = code }
        }
        return best to bestCode
    }

    fun decode(m: Array<BooleanArray>): Decoded {
        val size = m.size
        if (size < 21 || size > 177 || (size - 17) % 4 != 0) {
            throw QrDecodeException("尺寸不是合法二维码：$size")
        }
        val version = (size - 17) / 4
        val align = ALIGN[version] ?: throw QrDecodeException("不支持的版本：$version")

        // —— 格式信息：两份分别读，分别纠错 ——
        // 位编号 i 的坐标（ISO 18004 §8.9 图 25），用 (row, col) 记：
        //   第一份 0..5 → (i, 8)；6 → (7,8)；7 → (8,8)；8 → (8,7)；9..14 → (8, 14-i)
        //   第二份 0..7 → (8, size-1-i)；8..14 → (size-7+(i-8), 8)
        var raw1 = 0
        var raw2 = 0
        for (i in 0..14) {
            val (r1, c1) = when {
                i <= 5 -> i to 8
                i == 6 -> 7 to 8
                i == 7 -> 8 to 8
                i == 8 -> 8 to 7
                else -> 8 to (14 - i)
            }
            if (m[r1][c1]) raw1 = raw1 or (1 shl i)
            val (r2, c2) = if (i <= 7) 8 to (size - 1 - i) else (size - 7 + (i - 8)) to 8
            if (m[r2][c2]) raw2 = raw2 or (1 shl i)
        }
        val (d1, code1) = correctFormat(raw1)
        val (d2, code2) = correctFormat(raw2)
        // 两份都合法时优先距离小的；相等优先第一份。两份都不合法则取第一份（继续解，由调用方判定）。
        val code = if (d2 < d1) code2 else code1
        val distance = minOf(d1, d2)
        val unmasked = code xor 0x5412
        val levelBits = (unmasked shr 13) and 3
        val mask = (unmasked shr 10) and 7
        val level = when (levelBits) {
            1 -> 0   // L
            0 -> 1   // M
            else -> throw QrDecodeException("不支持的纠错级别：$levelBits")
        }

        val spec = SPEC[version to level]
            ?: throw QrDecodeException("缺少块结构：v$version level=$level")

        // —— 功能位图（不参与数据读取的位置）——
        val fn = functionModules(size, version, align)

        // —— 之字形读位，去掩码 ——
        val codewordCount = spec.dataCodewords + spec.ecCodewords
        val cw = IntArray(codewordCount)
        var bitIdx = 0
        var col = size - 1
        var upward = true
        while (col > 0 && bitIdx < codewordCount * 8) {
            if (col == 6) col = 5
            val rows = if (upward) (size - 1 downTo 0) else (0 until size)
            for (r in rows) {
                for (c in intArrayOf(col, col - 1)) {
                    if (fn[r][c]) continue
                    if (bitIdx >= codewordCount * 8) continue
                    var bit = m[r][c]
                    if (maskBit(mask, r, c)) bit = !bit
                    if (bit) cw[bitIdx shr 3] = cw[bitIdx shr 3] or (1 shl (7 - (bitIdx and 7)))
                    bitIdx++
                }
            }
            upward = !upward
            col -= 2
        }
        if (bitIdx < codewordCount * 8) {
            throw QrDecodeException("数据位不足：读到 $bitIdx 位，需 ${codewordCount * 8}")
        }

        // —— 反交错 ——
        val blockSizes = ArrayList<Int>()
        spec.groups.forEach { (count, dataCw) -> repeat(count) { blockSizes.add(dataCw) } }
        val dataBlocks = Array(blockSizes.size) { IntArray(blockSizes[it]) }
        var p = 0
        val maxData = spec.groups.maxOf { it.second }
        for (i in 0 until maxData) {
            for (b in blockSizes.indices) {
                if (i < blockSizes[b]) dataBlocks[b][i] = cw[p++]
            }
        }
        val ecBlocks = Array(blockSizes.size) { IntArray(spec.ecPerBlock) }
        for (i in 0 until spec.ecPerBlock) {
            for (b in blockSizes.indices) ecBlocks[b][i] = cw[p++]
        }

        // —— RS 校验子 ——
        var clean = true
        for (b in blockSizes.indices) {
            val full = IntArray(blockSizes[b] + spec.ecPerBlock)
            dataBlocks[b].copyInto(full, 0)
            ecBlocks[b].copyInto(full, blockSizes[b])
            if (!syndromesClean(full, spec.ecPerBlock)) clean = false
        }

        // —— 按块顺序拼回原始数据流，解析字节模式 ——
        val stream = IntArray(spec.dataCodewords)
        var q = 0
        for (b in blockSizes.indices) for (v in dataBlocks[b]) stream[q++] = v

        val reader = BitStream(stream)
        val mode = reader.read(4)
        if (mode != 0b0100) throw QrDecodeException("不是字节模式：mode=$mode")
        val countBits = if (version <= 9) 8 else 16
        val len = reader.read(countBits)
        if (len < 0 || reader.remaining < len * 8) {
            throw QrDecodeException("字符计数越界：len=$len 剩余=${reader.remaining}")
        }
        val bytes = ByteArray(len) { reader.read(8).toByte() }

        return Decoded(
            text = String(bytes, Charsets.UTF_8),
            version = version,
            level = level,
            mask = mask,
            formatRawEqual = raw1 == raw2,
            formatCorrectedEqual = code1 == code2,
            formatDistance = distance,
            syndromesClean = clean
        )
    }

    /** 功能图形位置：定位 + 分隔 + 时序 + 对齐 + 格式带 + 版本带。 */
    private fun functionModules(size: Int, version: Int, align: IntArray): Array<BooleanArray> {
        val f = Array(size) { BooleanArray(size) }
        for ((r0, c0) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            for (r in -1..7) for (c in -1..7) {
                val rr = r0 + r
                val cc = c0 + c
                if (rr in 0 until size && cc in 0 until size) f[rr][cc] = true
            }
        }
        for (i in 0 until size) { f[6][i] = true; f[i][6] = true }
        for (r in align) for (c in align) {
            if ((r == 6 && c == 6) || (r == 6 && c == align.last()) ||
                (r == align.last() && c == 6)
            ) continue
            for (dr in -2..2) for (dc in -2..2) {
                if (r + dr in 0 until size && c + dc in 0 until size) f[r + dr][c + dc] = true
            }
        }
        for (i in 0..8) { f[8][i] = true; f[i][8] = true }
        for (i in 0..7) { f[8][size - 1 - i] = true; f[size - 1 - i][8] = true }
        if (version >= 7) {
            for (r in size - 11 until size - 8) for (c in 0..5) f[r][c] = true
            for (r in 0..5) for (c in size - 11 until size - 8) f[r][c] = true
        }
        return f
    }

    /** ISO 18004 八种掩码：返回 true 表示该位需要取反。 */
    private fun maskBit(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    /** 校验子 S_i = r(α^i)，全零 ⇔ 码字无错（含纠错码字本身）。 */
    private fun syndromesClean(block: IntArray, ecLen: Int): Boolean {
        for (i in 0 until ecLen) {
            var s = 0
            for (v in block) s = mul(s, EXP[i]) xor v
            if (s != 0) return false
        }
        return true
    }

    private class BitStream(private val bytes: IntArray) {
        private var pos = 0
        val remaining: Int get() = bytes.size * 8 - pos
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                if (pos < bytes.size * 8) {
                    v = (v shl 1) or ((bytes[pos shr 3] shr (7 - (pos and 7))) and 1)
                    pos++
                } else {
                    v = v shl 1
                }
            }
            return v
        }
    }
}
