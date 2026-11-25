package com.beichen.polaris.core

import java.security.MessageDigest

/**
 * 诊断包正文的内容指纹（2026-11-25）。
 *
 * ## 为什么需要
 *
 * 诊断包是**离开应用**的文件：先经系统分享面板，再过接收方 App，最后上传到聊天窗口。
 * 这几跳里任何一跳出错，现场看到的就是「一份坏文件」，而坏法骗人：
 *
 * 2026-11-25 的一份真机诊断包——
 *  - 文件头自报 `26739 字节`，手机里的文件却是 `28672`（`7 × 4096`）；
 *  - 末 1933 字节与「往前 4096 字节处」的内容**逐字节相同**；
 *  - `turns` / `events` 两个导出文件同样：大小都是 4096 的整数倍，
 *    尾部都是前一块的重放；
 *  - 同一次会话里用「复制日志文本」存出来的文件 `54226` 字节，不整块、内容完好。
 *
 * 也就是：写入端是对的（`file.length()` 在写完那一刻就是 26739），
 * 是传输端把一个 4KB 缓冲重放了一次。但**当时能把这句话说死的唯一依据，
 * 是留在手机上的那行日志**——分析现场的人只拿到坏文件，于是先去查截断、
 * 再去查 UTF-8 边界、最后怀疑 `takeLast` 的行数，全是白查。
 *
 * 指纹的作用就是把这条判据随文件一起带出去：
 * 正文末尾写「正文字节数 + 指纹」，日志里再写一份同样的值。
 *
 * | 现象 | 结论 |
 * |---|---|
 * | 把文件截到「正文字节数」后算出的指纹 = 文件里写的指纹 | 正文完好，多出来的是尾巴 |
 * | 算出来对不上 | 正文本身被覆盖了 |
 * | 文件里根本没有「自校验」这一段 | 末尾被盖掉或文件被截断 |
 *
 * 三行日志就能替代一轮排查，所以它值得一个独立文件——**判据和接线分开**，
 * 判据在 JVM 上可测（[DiagnosticsExporter] import 了 `Context`，编不过 JVM）。
 *
 * ## 为什么取前 8 字节
 *
 * 这不是防篡改，是把「两份字节流是不是同一份」这件事变成一眼可比。
 * 8 字节（16 个十六进制字符）碰撞概率远低于这类误判的真实概率，
 * 且短到能塞进一行日志而不挤掉其它字段。
 */
internal object DiagnosticsFingerprint {

    /** 参与比较的十六进制位数。改了它就是改了判据，`DiagnosticsFingerprintTest` 会红。 */
    const val HEX_CHARS: Int = 16

    private const val HEX = "0123456789abcdef"

    /** 算法不可用时的占位。Android 与 JVM 都必带 SHA-256，走不到这里。 */
    const val UNAVAILABLE: String = "-"

    /**
     * 正文指纹：`SHA-256(bytes)` 的前 [HEX_CHARS] 个十六进制字符（小写）。
     *
     * 刻意**不抛出**：这个函数在「用户点导出」的路径上，为了一个日志字段
     * 让整份诊断包生成失败是本末倒置——`export()` 外面那层 `runCatching`
     * 会把异常变成 `null`，用户看到的是「导出失败」，而真正的原因只是
     * 某个 ROM 裁了 provider。
     */
    fun of(bytes: ByteArray): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(HEX_CHARS)
        var i = 0
        while (sb.length < HEX_CHARS) {
            val v = digest[i++].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        sb.toString()
    }.getOrDefault(UNAVAILABLE)

    /**
     * 自校验段的完整文本（含它与正文之间的那个空行）。
     *
     * ## 为什么要多写一行「完整文件应为」
     *
     * 只写「正文字节数」的话，判断文件好坏要靠人做减法：`文件大小 - 正文字节数`
     * 应该等于本段长度。2026-11-25 实测这个差是 81，写成文档后照样有人算错——
     * 因为 81 会随正文位数的变化而变（正文从 5 位涨到 6 位时是 82），
     * 任何「记住 81」的说法都是错的。
     *
     * 所以直接把**期望的文件总字节数**印出来：看一眼大小对不对，不用算。
     *
     * ## 自指怎么处理
     *
     * 「完整文件应为：N」里的 N 包含它自己的位数：N 的位数决定本段长度，
     * 本段长度又决定 N。这里用迭代收敛——初值给一个偏大的数，每次用真实长度
     * 反算，最多三轮必稳定（位数最多 7 位，只会跳一次）。
     * 收敛后 [sealOf] 的长度与 `total - bodyBytes.size` 严格相等，
     * `DiagnosticsFingerprintTest` 里有断言守着这条，改坏了会红。
     *
     * @param bodyEndsWithNewline 正文是否以换行结尾；不是则先补一个，保证空行成立。
     */
    fun sealOf(bodyBytes: ByteArray, bodyEndsWithNewline: Boolean): String {
        val n = bodyBytes.size
        val finger = of(bodyBytes)
        fun render(total: Int): String = buildString {
            if (!bodyEndsWithNewline) append('\n')
            append('\n')
            append("—— 自校验 ——\n")
            append("正文字节数：").append(n).append('\n')
            append("完整文件应为：").append(total).append(" 字节\n")
            append("正文指纹：").append(finger).append('\n')
        }
        var total = n + 160
        repeat(4) {
            val len = render(total).toByteArray(Charsets.UTF_8).size
            if (n + len == total) return render(total)
            total = n + len
        }
        return render(total)
    }
}
