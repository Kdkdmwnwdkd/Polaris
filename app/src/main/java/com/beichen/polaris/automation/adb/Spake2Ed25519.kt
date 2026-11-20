package com.beichen.polaris.automation.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

// 注意：这里一律写 `BigInteger.valueOf(2)`，**不要**写 `BigInteger.TWO`。
//
// `TWO` 是 Java 9 加的静态字段，Android 平台的 `java.math.BigInteger` 直到
// **API 33** 才有它；而本模块 minSdk = 26。项目没开 core library desugaring，
// 所以 `GETSTATIC BigInteger.TWO` 在 Android 8.0–12L 上会抛 NoSuchFieldError。
//
// 更要命的是它出现在 object 的**顶层 val 初始化式**里（P / L 等），也就是类一被
// 加载就执行——不是「用到才崩」，是「进 ADB 配对就崩」，且崩在类初始化，连
// try/catch 都接不住。JVM 单测（JDK 21）全绿，看不出任何异常。
//
// 2026-11-20 由云端 `:app:lintDebug` 首次发现（CI 此前不跑 lint）。

/**
 * AOSP / boringssl 版 SPAKE2（Edwards25519 群）——adb 无线配对的密码学核心。
 *
 * ## 为什么自己实现
 *
 * AOSP 的 `adb pair` 走 TLS + SPAKE2，SPAKE2 用的是 boringssl 的
 * `crypto/curve25519/spake25519.cc`，**不是** RFC 9382 的标准版：M/N 两个掩码点由
 * 固定 seed 哈希生成（带 8-torsion，阶为 8L），并且有一处「口令标量小修补」——
 * 详见下方 [passwordScalar]。任何细节走偏（常数、字节序、transcript 顺序、
 * 每段是否带 8 字节长度前缀）都会导致双方密钥不同，表现为配对时
 * 「响应解密失败」。
 *
 * ## 算法（严格对齐 spake25519.cc 与 adb/pairing_connection.cpp）
 *
 * ```
 * private = sc_reduce(rand64) * 8            // ×8 清 cofactor
 * P       = private · B
 * pw_hash = SHA512(password)                 // 64B，参与 transcript
 * ps      = sc_reduce(pw_hash)  + 小修补      // 使低 3 位为 0
 * mask    = ps · (alice ? M : N)
 * msg     = encode(P + mask)
 * ---- 收对端 Q* ----
 * Q  = Q* − ps · (alice ? N : M)
 * dh = encode(private · Q)
 * key = SHA512( len‖name_a ‖ len‖name_b ‖ len‖msg_a ‖ len‖msg_b ‖ len‖dh ‖ len‖pw_hash )[:32]
 * ```
 *
 * 名字是 **`"adb pair client\0"` / `"adb pair server\0"`（含结尾 NUL，16 字节）**——
 * AOSP 侧用 `sizeof(kClientName)` 传长度，漏掉那个 NUL 会让密钥对不上。本端恒为
 * client（alice），设备是 server（bob）。
 *
 * ## 正确性
 *
 * 与一份独立实现（Python，同一份 spake25519.cc 流程 + 同一组 M/N 编码）对拍，
 * 固定私钥与口令下 `message` 与最终密钥逐字节一致；用例见
 * `scripts/qa/Spake2Verification.kt`。
 */
internal object Ed25519 {

    /** 2^255 − 19 */
    val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** 素数阶子群的阶：2^252 + 27742317777372353535851937790883648493 */
    val L: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** 曲线常数 d = −121665 / 121666 mod p */
    val D: BigInteger = BigInteger(
        "37095705934669439343138083508754565189542113879843219016388785533085940283555"
    )

    /** 基点 B 的压缩编码（标准 Ed25519 基点：y = 4/5，x 为正）。 */
    private val BASE_ENCODED = hex("5866666666666666666666666666666666666666666666666666666666666666")

    /** 基点（惰性解码，避免类初始化顺序问题）。 */
    val B: Point by lazy { decode(BASE_ENCODED) ?: error("基点解码失败") }

    /** 单位元 (0, 1)。 */
    val IDENTITY: Point = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** 扩展扭曲爱德华坐标：x = X/Z，y = Y/Z，x·y = T/Z。 */
    data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    /** 小端 32 字节 → 无符号大整数。 */
    fun leToInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    /** 无符号大整数 → 小端 [size] 字节（不足补零；超出取低 [size] 字节）。 */
    fun intToLe(v: BigInteger, size: Int = 32): ByteArray {
        val raw = v.toByteArray()          // 大端，正数可能带一个 0x00 符号位前缀
        val be = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
        val out = ByteArray(size)
        // be[be.size-1] 是最低字节 → out[0]
        for (i in 0 until be.size) out[i] = be[be.size - 1 - i]
        return out
    }

    /**
     * 压缩点解码（RFC 8032 §5.1.3）：y = 低 255 位，最高位 = x 的奇偶。
     *
     * @return 点；编码不在曲线上 / y ≥ p 时返回 null（调用方必须按失败处理，
     *   否则会把非法点当合法输入，破坏协议安全性）。
     */
    fun decode(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val v = leToInt(encoded)
        val sign = v.testBit(255)
        val y = v.clearBit(255)
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val denom = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (denom.signum() == 0) return null
        val x2 = u.multiply(denom.modInverse(P)).mod(P)
        var x = sqrtRatio(x2) ?: return null
        if (x.testBit(0) != sign) x = P.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /**
     * p ≡ 5 (mod 8) 时的开平方：x = a^((p+3)/8)，失败则乘 sqrt(−1) 再试一次。
     * 返回值平方不等于 a 时返回 null（a 不是二次剩余）。
     */
    private fun sqrtRatio(a: BigInteger): BigInteger? {
        val exp = P.add(BigInteger.valueOf(3)).shiftRight(3)
        var x = a.modPow(exp, P)
        if (x.multiply(x).mod(P) == a) return x
        val sqrtMinusOne = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        x = x.multiply(sqrtMinusOne).mod(P)
        return if (x.multiply(x).mod(P) == a) x else null
    }

    /** 压缩：y 小端 32 字节，最高位放 x 的奇偶。 */
    fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        var v = y
        if (x.testBit(0)) v = v.setBit(255)
        return intToLe(v)
    }

    /** 点加（add-2008-hwcd-3，a = −1 的扩展坐标公式，统一加法）。 */
    fun add(p1: Point, p2: Point): Point {
        val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
        val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
        val c = p1.t.multiply(TWO_D).multiply(p2.t).mod(P)
        val d = p1.z.multiply(BigInteger.valueOf(2)).multiply(p2.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    /** 倍点（dbl-2008-hwcd，a = −1）。 */
    fun double(p: Point): Point {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = p.z.multiply(p.z).multiply(BigInteger.valueOf(2)).mod(P)
        val d = P.subtract(a)
        val xy = p.x.add(p.y).mod(P)
        val e = xy.multiply(xy).subtract(a).subtract(b).mod(P)
        val g = d.add(b).mod(P)
        val f = g.subtract(c).mod(P)
        val h = d.subtract(b).mod(P)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    /** 取反：−(x, y) = (−x, y)。 */
    fun negate(p: Point): Point =
        Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

    /**
     * 标量乘（从左到右 double-and-add）。
     *
     * 不做常数时间处理：这里只跑一次配对、输入是本端生成的随机标量或对端掩码，
     * 不存在可被远程测量的时序侧信道场景；相比之下 BigInteger 非恒定时间的
     * 实现复杂度不值得。若将来复用到大批量签名场景，需改用固定窗口 + 盲化。
     */
    fun mul(p: Point, k: BigInteger): Point {
        if (k.signum() == 0) return IDENTITY
        var result: Point? = null
        val bits = k.toString(2)
        for (ch in bits) {
            if (result != null) result = double(result)
            if (ch == '1') result = if (result == null) p else add(result, p)
        }
        return result ?: IDENTITY
    }

    private val TWO_D: BigInteger by lazy { D.multiply(BigInteger.valueOf(2)).mod(P) }

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
}

/**
 * adb 配对专用的 SPAKE2 实现，角色固定：**本端 = client（alice）**，设备 = server（bob）。
 *
 * @param password 口令 = 6 位配对码 + TLS 导出的 64 字节材料（见 [AdbPairingClient]）
 * @param privateKeySeed 仅测试注入用（64 字节）；生产传 null 走 [SecureRandom]
 */
internal class Spake2Ed25519(
    password: ByteArray,
    private val isClient: Boolean = true,
    privateKeySeed: ByteArray? = null
) : Spake2 {

    private val privateScalar: BigInteger
    private val passwordScalar: BigInteger
    private val passwordHash: ByteArray

    override val message: ByteArray

    init {
        val seed = privateKeySeed ?: ByteArray(64).also { SecureRandom().nextBytes(it) }
        require(seed.size == 64) { "SPAKE2 私钥种子必须是 64 字节（got ${seed.size}）" }

        // 1) private = sc_reduce(seed) * 8：×8 后与 8-torsion 的掩码点相乘时可将低阶分量抵消
        val reduced = Ed25519.leToInt(seed).mod(Ed25519.L)
        privateScalar = reduced.multiply(BigInteger.valueOf(8)).and(MASK256)

        val p = Ed25519.mul(Ed25519.B, privateScalar)

        // 2) 口令哈希：64 字节原值进 transcript，归约值用于掩码标量
        passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
        passwordScalar = passwordScalarOf(passwordHash)

        // 3) 掩码点：alice 用 M，bob 用 N
        val maskBase = if (isClient) M_POINT else N_POINT
        val mask = Ed25519.mul(maskBase, passwordScalar)

        message = Ed25519.encode(Ed25519.add(p, mask))
    }

    override fun process(peerMessage: ByteArray): ByteArray {
        if (peerMessage.size != 32) throw IllegalArgumentException("对端 SPAKE2 消息长度应为 32（got ${peerMessage.size}）")
        val qStar = Ed25519.decode(peerMessage)
            ?: throw IllegalArgumentException("对端 SPAKE2 点不在曲线上（配对码错误或协议不匹配）")

        val peerMaskBase = if (isClient) N_POINT else M_POINT
        val peerMask = Ed25519.mul(peerMaskBase, passwordScalar)
        val q = Ed25519.add(qStar, Ed25519.negate(peerMask))
        val dh = Ed25519.encode(Ed25519.mul(q, privateScalar))

        val sha = MessageDigest.getInstance("SHA-512")
        val myName = if (isClient) CLIENT_NAME else SERVER_NAME
        val theirName = if (isClient) SERVER_NAME else CLIENT_NAME
        // 顺序按角色固定：alice 先自己；bob 先对端 —— 两端算出的字节序因此完全相同
        if (isClient) {
            updateWithLength(sha, myName)
            updateWithLength(sha, theirName)
            updateWithLength(sha, message)
            updateWithLength(sha, peerMessage)
        } else {
            updateWithLength(sha, theirName)
            updateWithLength(sha, myName)
            updateWithLength(sha, peerMessage)
            updateWithLength(sha, message)
        }
        updateWithLength(sha, dh)
        updateWithLength(sha, passwordHash)

        // AOSP 侧用 AES-256-GCM，只取 SHA512 结果的前 32 字节
        return sha.digest().copyOf(32)
    }

    companion object {
        /** 8 字节长度前缀（小端）+ 数据。boringssl 的 `update_with_length_prefix`。 */
        private fun updateWithLength(sha: MessageDigest, data: ByteArray) {
            val len = ByteArray(8)
            var l = data.size.toLong()
            for (i in 0 until 8) {
                len[i] = (l and 0xff).toByte()
                l = l shr 8
            }
            sha.update(len)
            sha.update(data)
        }

        /**
         * 口令标量：`sc_reduce(SHA512(password))` 后按低位补 L / 2L / 4L，使低 3 位为 0。
         *
         * 这是 boringssl 为修一个历史 bug（漏乘 8 导致口令哈希低 3 位泄露）做的**单边兼容**
         * 处理：等价于给掩码点加上低阶点，而这些低阶分量会被 ×8 的临时私钥抵消。
         * 少了这一步，算出的掩码点会与对端不同，密钥必然不一致。
         */
        private fun passwordScalarOf(hash: ByteArray): BigInteger {
            var s = Ed25519.leToInt(hash).mod(Ed25519.L)
            // L 为奇数 ⇒ 加 L 翻转 bit0、加 2L 翻转 bit1、加 4L 翻转 bit2（顺序判断更新后的值）
            if (s.testBit(0)) s = s.add(Ed25519.L)
            if (s.testBit(1)) s = s.add(Ed25519.L.shiftLeft(1))
            if (s.testBit(2)) s = s.add(Ed25519.L.shiftLeft(2))
            return s
        }

        private val MASK256: BigInteger = BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)

        /** 含结尾 NUL（AOSP 用 `sizeof()` 传长度，16 字节）。 */
        val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

        /**
         * M / N 的压缩点编码，取自 boringssl `crypto/curve25519/spake25519.cc` 的注释
         * （由 seed 哈希生成，阶为 8L）。
         */
        private val M_ENCODED = Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_ENCODED = Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

        private val M_POINT: Ed25519.Point by lazy {
            Ed25519.decode(M_ENCODED) ?: error("SPAKE2 M 点解码失败")
        }
        private val N_POINT: Ed25519.Point by lazy {
            Ed25519.decode(N_ENCODED) ?: error("SPAKE2 N 点解码失败")
        }
    }
}
