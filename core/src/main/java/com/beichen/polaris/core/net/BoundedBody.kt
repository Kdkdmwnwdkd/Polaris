package com.beichen.polaris.core.net

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * 一次响应体读取的结果。
 *
 * @param text 解出来的文本（最多 [BoundedBody] 的上限；截断处的半个字符会被丢弃，
 *   不会变成 U+FFFD）。
 * @param truncated 是否因为撞到上限而没读完。**这个字段必须存在**：调用方需要能区分
 *   「响应就这么长」与「我只看了前面一段」，否则截断会被当成完整内容继续推理。
 * @param bytesRead 实际读了多少字节（诊断用）。
 */
data class BodySlice(
    val text: String,
    val truncated: Boolean,
    val bytesRead: Long
) {
    companion object {
        /** 空体（204 / HEAD / body 为 null）。 */
        val EMPTY = BodySlice("", false, 0)
    }
}

/**
 * 有上限的响应体读取（审计 F21）。
 *
 * ## 修的是什么
 *
 * 修前两处都是 `resp.body?.string()`：
 *
 * ```kotlin
 * val text = resp.body?.string().orEmpty()                  // ← 整份读进堆
 * val out = if (text.length > 8000) text.take(8000) + "…"   // ← 截断发生在读完以后
 * ```
 *
 * 这正是 F19 在 `read_file` 上修过的同一个错误：**上限只作用在返回值上，不作用在读的过程**。
 * 一个 500MB 的响应（模型被诱导去下一个大文件、或对方返回一个 tar）会直接把进程打挂，
 * `take(8000)` 一次生效的机会都没有。
 *
 * 同一家族的错误本项目已踩过三次（`BackupArchive` 的 zip 炸弹、`ShizukuShell` 的
 * `copyToCapped`、F19 的 `read_file`），结论一致：**边读边计数，到上限即停**。
 *
 * ## 为什么收 InputStream 而不是 okio 的 BufferedSource
 *
 * 两个调用点的响应体都能给 `byteStream()`，而 `java.io` 让本类零依赖——
 * harness 可以直接拿 `ByteArrayInputStream` 跑真行为，不用把 okhttp/okio 拖进
 * classpath，也就不用再维护一条「找不到 jar 就 SKIP」的脆弱路径。
 *
 * ## 为什么截断处要丢半个字符
 *
 * 上限按**字节**算，而 UTF-8 一个字符占 1~4 字节，截断点可能落在字符中间。
 * 直接 `String(bytes)` 会把那半个字符变成 U+FFFD——模型看到 `�` 会以为响应里真有这玩意。
 * 这里用 `endOfInput=false` 的解码器，让不完整的尾部序列留在 buffer 里被丢弃：
 * 宁可少一个字，不产出假字符。
 */
object BoundedBody {

    /** 通用默认上限。搜索页 HTML 通常 100~500KB，512KB 足够解析出前几条结果。 */
    const val DEFAULT_MAX_BYTES: Long = 512L * 1024

    /**
     * 任何调用都不得超过的硬顶。
     *
     * `maxBytes` 是调用方传的，将来有人传 `Long.MAX_VALUE` 就等于把这条保护删了。
     * 8MB 与 `edit_file` 的上限同值——同一个「一次操作允许占多少内存」的约定。
     */
    const val MAX_READ_BYTES: Long = 8L * 1024 * 1024

    /**
     * 读最多 [maxBytes] 字节；[input] **不会被关闭**（由调用方 `use` 管理，
     * 因为 OkHttp 还要靠它释放连接）。
     *
     * 读失败（IO 异常）返回已读到的部分 + `truncated=true`，不抛：
     * 一个读到一半断掉的响应，把前半段交给调用方比整条失败有用。
     */
    fun read(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): BodySlice {
        val cap = maxBytes.coerceIn(1L, MAX_READ_BYTES).toInt()
        val buf = ByteArray(cap)
        var total = 0
        try {
            while (total < cap) {
                val n = input.read(buf, total, cap - total)
                if (n < 0) break
                total += n
            }
            // 读满上限时探 1 字节：读到说明后面还有，这不是「正好读完」。
            val truncated = total == cap && input.read() >= 0
            return BodySlice(decode(buf, total), truncated, total.toLong())
        } catch (_: IOException) {
            return BodySlice(decode(buf, total), true, total.toLong())
        }
    }

    /**
     * UTF-8 解码，**尾部不完整序列丢弃而不是替换成 U+FFFD**。
     *
     * 用 `endOfInput=false`：解码器遇到「后面可能还有字节」的残缺序列时保留在内部缓冲，
     * 不报错也不产出替换字符。非法字节（真乱码）仍然被替换——那是另一回事，
     * 调用方（`http_request`）本来就把响应当文本看。
     */
    private fun decode(bytes: ByteArray, len: Int): String {
        if (len <= 0) return ""
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        // 字符数恒不超过字节数，多留 1 位给可能的尾部
        val out = CharBuffer.allocate(len + 1)
        decoder.decode(ByteBuffer.wrap(bytes, 0, len), out, false)
        out.flip()
        return out.toString()
    }
}
