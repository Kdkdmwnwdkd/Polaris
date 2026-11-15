package com.beichen.polaris.audio.tts

import java.util.Locale

/**
 * 微软 Edge 朗读接口的门票：`Sec-MS-GEC` v2 令牌。
 *
 * ## 为什么要在客户端算
 *
 * 那 6 个神经音色（晓晓/晓伊/云希/云健/云扬/晓北）走的是 Edge 浏览器朗读那套
 * WebSocket。它不要 API key、不要账号，只认一个随请求带上的令牌：
 *
 *     Sec-MS-GEC = <16 位 hex>-<64 位 hex>
 *
 * 令牌 = 哈希(「当前时间按 5 分钟取整的 Windows 文件时间」+ 固定客户端标识)。
 * 服务端只验这个哈希，所以算得出来就能用——**不需要用户填任何 key**。
 *
 * ## 为什么这段代码看着不像 SHA-256
 *
 * 它确实是 SHA-256，但上游为了增加「照抄算法」的成本，把实现改写了：
 * 常数表被替换、初值预先折叠了前 14 轮、若干 sigma 函数带着修正项。
 * 这里**逐行照抄上游**（edge-tts 7.3.1 的 `drm.py`），不做「简化成标准 SHA-256」
 * 的重写——那样看着干净，但结果对不上，而错了的表现是 403，看不出哪里错。
 *
 * 唯一的简化：`(x * 0x100000001) >> n` 这个 ROTR 技巧换成直接循环右移。
 * 两者数学等价（上游注释也这么写），换掉只是因为 Kotlin 的 Long 会在乘 2^32 时
 * 溢出，而右移前溢出恰恰会丢掉要用到的位。
 *
 * ## 校验方式
 *
 * `SecMsGecTest` 里钉了 8 组「时间戳 → 令牌」，直接取自上游 Python 实现的输出。
 * 算法一改，那 8 条会立刻红。
 */
internal object SecMsGec {

    /** 32 位无符号掩码。全程用 Long 存 0..2^32-1，避免 Int 的符号位污染异或。 */
    private const val MASK = 0xFFFFFFFFL

    private const val WIN_EPOCH = 11644473600L

    /** 上游写死的客户端标识；服务端认的就是它。 */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** 与令牌配套的版本号。令牌格式换代会连这个一起改。 */
    const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"

    private val INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )

    /** 前 14 轮已被 [sha256Init] 折叠进这里，所以后续轮次从 14 开始。 */
    private val STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )

    private val SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )

    private val ROUND = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )

    /** 第 14 轮用的是被改过的常数——上游折叠前 14 轮时留下的记号。 */
    private const val ADJUSTMENT = 0xC9DC06A7L

    private val BLOCK: LongArray = LongArray(64) {
        if (it == 14) ADJUSTMENT else ROUND[it]
    }

    private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0L

    /** CS64 的系数表（14 组，各带一个偏移量）。 */
    private val CS64_COEFFICIENTS = arrayOf(
        0xE82E91B1L to 0xB32700D0L,
        0x44DE24C6L to 0x88DFF51DL,
        0x3BEB65BCL to 0x29514038L,
        0xC89C5933L to 0xB328CBDFL,
        0x8D1EC211L to 0xD405B4D5L,
        0x314EF5D5L to 0x26E55F3CL,
        0xE92491B1L to 0x607801C6L,
        0xF0A5B9D5L to 0x82B2C1FBL,
        0x06064CF3L to 0xFA51A3DAL,
        0xDA79FA60L to 0x207CC464L,
        0xBED20560L to 0x3C3C74F3L,
        0x89F9C9F8L to 0xD2D14B61L,
        0x35FA38C6L to 0xBB04A2C1L,
        0xCB49E3C6L to 0xC5626167L,
    )

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    /**
     * 生成令牌。
     *
     * @param unixSeconds 取当前时间；测试传入固定值以复现上游输出
     */
    fun token(unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        // 时间按 5 分钟取整：令牌每 5 分钟才变一次，缓存它不会有精度问题。
        var ticks = unixSeconds.toDouble() + WIN_EPOCH
        ticks -= ticks % 300
        // 转成 100 纳秒单位——Windows 文件时间就是这么记的，少乘这一下服务端只回 403。
        val str = "${(ticks * 10_000_000).toLong()}${TRUSTED_CLIENT_TOKEN}"
        return hash(str.toByteArray(Charsets.US_ASCII))
    }

    private fun hash(data: ByteArray): String {
        val digest = sha256Digest(data)
        val (cs64a, cs64b) = cs64Digest(data, digest)
        val cs64 = hex(reverse32(cs64a) + reverse32(cs64b)).uppercase(Locale.ROOT)
        val sha = hex(digest).uppercase(Locale.ROOT)
        return "$cs64-$sha"
    }

    /** u32 → 4 字节小端。上游用 `struct.pack("<II", ...)`。 */
    private fun reverse32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun rol16(value: Long): Long {
        val v = value and MASK
        return ((v shl 16) or (v shr 16)) and MASK
    }

    private fun rotr(value: Long, bits: Int): Long {
        val v = value and MASK
        return ((v shr bits) or (v shl (32 - bits))) and MASK
    }

    private fun sigma0(value: Long): Long {
        val v = value and MASK
        return ((v shr 7) xor (v shl 25) xor (v shr 18) xor (v shl 14) xor (v shr 3)) and MASK
    }

    private fun sigma1(value: Long): Long {
        val v = value and MASK
        return ((v shr 17) xor (v shl 15) xor (v shr 19) xor (v shl 13) xor (v shr 10)) and MASK
    }

    private fun bigSigma0(value: Long): Long = rotr(value, 2) xor rotr(value, 13) xor rotr(value, 22)

    private fun bigSigma1(value: Long): Long = rotr(value, 6) xor rotr(value, 11) xor rotr(value, 25)

    /** 上游写的是 `g ^ (e & (f ^ g))`，与标准 ch 等价；照抄，不要改写成 `(e&f)^(~e&g)`。 */
    private fun ch(x: Long, y: Long, z: Long): Long = z xor (x and (y xor z))

    private fun maj(x: Long, y: Long, z: Long): Long = (x and y) or (z and (x or y))

    private fun be24(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 16) or
            ((data[at + 1].toLong() and 0xFF) shl 8) or
            (data[at + 2].toLong() and 0xFF)

    private fun be32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or
            ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or
            (data[at + 3].toLong() and 0xFF)

    private fun le32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF)) or
            ((data[at + 1].toLong() and 0xFF) shl 8) or
            ((data[at + 2].toLong() and 0xFF) shl 16) or
            ((data[at + 3].toLong() and 0xFF) shl 24)

    /** 小端 32 位成对读取；长度不是 4 的倍数时补零，且保证偶数个 word。 */
    private fun wordsLe4Even(data: ByteArray): LongArray {
        val padded = ByteArray(data.size + (4 - data.size % 4) % 4)
        data.copyInto(padded)
        var count = padded.size / 4
        if (count % 2 == 1) count += 1
        val out = LongArray(count)
        for (i in 0 until padded.size / 4) out[i] = le32(padded, i * 4)
        return out
    }

    private fun sha256Init(data: ByteArray): LongArray {
        require(data.size >= 7) { "令牌盐至少 7 字节" }
        val words = LongArray(64)
        words[14] = be24(data, 0)
        words[15] = be32(data, 3)
        for (index in 16..29) {
            var value = SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words[index] = value and MASK
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            ADJUSTMENT - ROUND[14]) and MASK
        expandSchedule(words, 31)
        val working = rounds(STATE, words, 14)
        return LongArray(8) { (INITIAL[it] + working[it]) and MASK }
    }

    private fun expandSchedule(words: LongArray, from: Int) {
        for (index in from..63) {
            val x = words[index - 2]
            val y = words[index - 15]
            // 上游把两次循环右移合并成一次移位组合，这里照抄（不取模，最后统一取）。
            val s1 = (x shr 17) xor (x shl 15) xor (x shr 19) xor (x shl 13) xor (x shr 10)
            val s0 = (y shr 7) xor (y shl 25) xor (y shr 18) xor (y shl 14) xor (y shr 3)
            words[index] = (s1 + words[index - 7] + s0 + words[index - 16]) and MASK
        }
    }

    private fun rounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) BLOCK else ROUND
        for (index in firstRound until 64) {
            val t1 = h + bigSigma1(e) + ch(e, f, g) + constants[index] + words[index]
            val t2 = bigSigma0(a) + maj(a, b, c)
            h = g
            g = f
            f = e
            e = (d + t1) and MASK
            d = c
            c = b
            b = a
            a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    internal fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "令牌盐至少 7 字节" }
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size)
        val padLen = ((55 - length) % 64 + 64) % 64
        val padded = ByteArray(tail.size + 1 + padLen + 8)
        tail.copyInto(padded)
        padded[tail.size] = 0x80.toByte()
        var bits = length.toLong() * 8
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = ((bits shr (8 * i)) and 0xFF).toByte()
        }
        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = LongArray(64)
            for (i in 0 until 16) words[i] = be32(padded, offset + i * 4)
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            out[i * 4] = ((v shr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v shr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v shr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        var keyA = le32(digest, 0) or 1L
        var keyB = le32(digest, 4) or 1L
        keyA = keyA and MASK
        keyB = keyB and MASK
        val factors = longArrayOf(
            (keyA * CS64_FACTOR) and MASK,
            (keyB * CS64_FACTOR) and MASK,
        )
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        CS64_COEFFICIENTS.forEachIndexed { index, (coefficient, offset) ->
            terms.add((coefficient * factors[index % 2]) and MASK)
            offsets.add(offset)
        }
        val words = wordsLe4Even(byteArrayOf(0) + data)
        words.forEachIndexed { index, word ->
            val key = if (index % 2 == 0) keyA else keyB
            var term = (word * key) and MASK
            var offset = (rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)) and MASK
            if (index == 0) {
                term = (term + CS64_ADJUSTMENT0 * factors[0]) and MASK
                offset = (offset + CS64_ADJUSTMENT1) and MASK
            }
            terms.add(term)
            offsets.add(offset)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16((offsets[index] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and MASK)) and MASK
            t = rol16((offsets[index + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc = (acc + state) and MASK

            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            index += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }
}
