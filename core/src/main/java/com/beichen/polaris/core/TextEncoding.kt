package com.beichen.polaris.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 严格 UTF-8 判定（审计 F20）。
 *
 * ## 修的是什么
 *
 * `File.readText()` 与 `File.reader(Charsets.UTF_8)` 底层都是 `InputStreamReader`，
 * 而它的解码器默认 `onMalformedInput(REPLACE)`：**非法字节被静默换成 U+FFFD**，不抛异常。
 * 对一个 GBK 编码的 txt（中文用户从 Windows 拷过来的文件基本都是），这意味着一整篇中文
 * 变成一片 `�`。
 *
 * 单看 `read_file`，这不过是「显示难看」。但和 `edit_file` 连起来就是**不可逆的数据损坏**：
 *
 *   1. 模型 `read_file` 看到的是 `����`；
 *   2. 模型拿其中一段 `����` 当 old_string 去 `edit_file`——能匹配上，因为两边是同一套乱码；
 *   3. `atomicWriteText` 按 UTF-8 写回，原文件里那些 GBK 字节**永久消失**。
 *
 * 备份钩子能救一次，但用户看到的是「文件被改坏了」，而且备份会过期。所以判据要放在入口：
 * 不是 UTF-8 就不让编辑；读的时候也明确告诉模型「这是解码占位符，不是你看到的内容」——
 * 否则它会自信地基于乱码继续推理，那比报错难查得多。
 *
 * ## 为什么不自动转码
 *
 * 猜编码不可靠：GBK / GB18030 / Big5 / Shift-JIS 在纯 ASCII 段落上无法区分。更要紧的是
 * `read_file` 的 offset 按 **UTF-16 码元**算（见 [TextFileSlice]），一旦读取路径偷偷转码，
 * 用户手里记着的 offset 全部失效。转码该是用户显式做的一次操作（`iconv`），不该是读取的副作用。
 *
 * 纯 JVM（只用 `java.io` / `java.nio`），harness 直接编译真文件跑。
 */
object TextEncoding {

    /**
     * 探测窗口。8KB 足够覆盖一个中文文件的开头，对任何设备都是零成本。
     *
     * 只探头部是取舍：全量扫一张 300MB 的日志只为了回答「是不是 UTF-8」不值得，
     * 而真出问题的文件（别人发来的 txt、导出表）几乎都在头部几行就露出非 ASCII 字节。
     */
    const val PROBE_BYTES: Int = 8 * 1024

    /**
     * `edit_file` 允许整份载入的上限。
     *
     * `edit_file` 必须看到全文才能做唯一性校验，所以它没法像 [TextFileSlice] 那样分页。
     * 能做的只有「超过就直接说不」——`readText()` 一个 300MB 的文件，进程先 OOM，
     * 后面的唯一性校验一次都不执行。要改大文件请用 `write_file` 整份重写或 `shell_run`。
     */
    const val EDIT_MAX_BYTES: Long = 8L * 1024 * 1024

    /**
     * 文件头能否按严格 UTF-8 解码。
     *
     * 读不到（IO 异常）时返回 `true`：这里的判定是用来**阻止**破坏性操作的，
     * 因为一次读失败就把用户的文件判成「不可编辑」不划算——让后续真正的读操作去报错。
     */
    fun looksUtf8(file: File, probeBytes: Int = PROBE_BYTES): Boolean {
        if (probeBytes <= 0) return true
        // 多读 3 字节：UTF-8 最长 4 字节，探测窗口可能正切在一个字符中间。
        val want = probeBytes + 3
        val head = readHead(file, want) ?: return true
        // UTF-16 的 BOM（FF FE / FE FF）不是合法 UTF-8，但「纯 ASCII 内容的 UTF-16 文件」
        // 逐字节都是合法 UTF-8（每个字母后面跟一个 0x00），不显式拦就会被放行，
        // 然后 edit_file 按 UTF-8 写回——文件坏了，而且坏得很安静。
        if (head.size >= 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
        }
        val truncated = head.size == want
        // 二进制文件（图片、压缩包、APK）里的 NUL 会被当成合法字符读进来，结果同样是一片乱码。
        // 真正的文本里几乎不出现 0x00，占比超过 10% 就当二进制——`read_file` 该提示，
        // `edit_file` 该拒绝（原来那条路会把 APK 按 UTF-8 重写，装都装不回去）。
        var nul = 0
        for (b in head) if (b.toInt() == 0) nul++
        if (nul * 10 > head.size) return false
        val len = if (truncated) probeBytes else head.size
        // 读满 = 后面还有字节，按「输入未完」判（endOfInput=false 时尾部残缺序列不算错）。
        return decodes(head, len, endOfInput = !truncated)
    }

    /**
     * 严格解码全文；含非法字节时返回 `null`（不抛，省得每个调用方都写一层 catch）。
     *
     * 调用方须先自行限制文件大小（[EDIT_MAX_BYTES]），本方法会整份载入。
     */
    fun readStrictUtf8(file: File): String? = try {
        val bytes = file.readBytes()
        strictDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: IOException) {
        null
    }

    /**
     * 严格解码器：遇到非法/不可映射字节**报错**，而不是替换成 U+FFFD。
     *
     * 这两行就是整个修复的核心——`InputStreamReader` 用的正是同一个 `CharsetDecoder`，
     * 只是默认行为是 REPLACE。
     */
    private fun strictDecoder() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun decodes(buf: ByteArray, len: Int, endOfInput: Boolean): Boolean = try {
        // 输出容量取字节数 + 1 就够：UTF-8 里字符数恒不超过字节数。
        val out = CharBuffer.allocate(len + 1)
        val result = strictDecoder().decode(ByteBuffer.wrap(buf, 0, len), out, endOfInput)
        !result.isError
    } catch (_: CharacterCodingException) {
        false
    }

    /** 读最多 [max] 字节；不足 [max] 说明已到文件尾。读不了返回 null。 */
    private fun readHead(file: File, max: Int): ByteArray? = try {
        file.inputStream().use { input ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            if (n == max) buf else buf.copyOf(n)
        }
    } catch (_: IOException) {
        null
    }
}
