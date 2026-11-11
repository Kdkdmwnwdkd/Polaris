package com.beichen.polaris.core

import java.io.File
import java.io.Reader

/**
 * 一页文件切片。
 *
 * @param text 本页文本。**保证不以孤立代理结尾**（代理对不会被劈成两页）。
 * @param nextOffset 下一页的 offset（UTF-16 码元下标）。调用方必须用它续读，
 *   不要自己用 `offset + limit` 推算——页尾为保住 emoji 可能多带 1 个码元。
 * @param hasMore 本页之后还有内容。
 */
data class TextSlice(
    val text: String,
    val nextOffset: Int,
    val hasMore: Boolean
)

/**
 * `read_file` 的分页读取（审计 F19）。
 *
 * ## 修的是什么
 *
 * 修前 `ReadFileTool` 是这样读的：
 *
 * ```kotlin
 * val full = file.readText()                       // ← 整份载入内存
 * val snippet = full.substring(offset, end)        // ← 再切出一页
 * ```
 *
 * 也就是说 `limit`（默认 65536「字符」）**只限制了返回给模型的那一段**，
 * 文件本身仍然被完整读进堆里。工具描述里写的「避免一次性把大文件灌爆上下文」
 * 只对上下文成立，对内存不成立：工作区里放一个 300MB 的日志，点一下「读一下这个文件」，
 * 进程先 OOM，`limit` 连一次生效的机会都没有。
 *
 * 同一个家族的错误本项目已经踩过两次（`BackupArchive` 的 zip 炸弹、`ShizukuShell`
 * 的 `copyToCapped`），结论一致：**上限必须在读的过程中生效，不能在读完之后的切片里生效**。
 *
 * ## 顺带修掉的第二个问题：分页会把 emoji 劈成两半
 *
 * `String.length` 数的是 UTF-16 码元，一个 emoji 占 2 个。旧的 `substring(offset, end)`
 * 落在代理对中间时，第一页以孤立高位代理结尾、第二页以孤立低位代理开头——单看每一页都
 * 是非法文本，序列化成 JSON 时变成 `?` 或被上游拒绝，而拼接回来也不再等于原文。
 * 项目里早有 [safeHeadEnd] / [safeTailStart] 处理同类问题（T06 截断），这里的分页走同一套判据。
 *
 * 纯 JVM（只用 `java.io`），harness 直接编译真文件跑。
 */
object TextFileSlice {

    /** 单页默认字符数（与工具描述的默认值同源）。 */
    const val DEFAULT_LIMIT: Int = 65_536

    /** 单页上限。**实现侧的硬顶**，工具描述里的数字只是提示，不构成约束。 */
    const val MAX_LIMIT: Int = 65_536

    /**
     * 从 [file] 的 [offset] 处读一页，最多 [limit] 个码元（代理对可多带 1 个，见 [TextSlice]）。
     *
     * 内存占用只与 [limit] 有关，与文件大小无关：边读边拼，读完即停。
     *
     * 边界语义：
     *  - `offset` 为负 → 当 0；超过文件长度 → 返回空页（`nextOffset` = 文件实际字符数）；
     *  - `limit` ≤ 0 → 当 1；超过 [MAX_LIMIT] → 截到 [MAX_LIMIT]；
     *  - `offset` 手工传到代理对中间（正常续读不会）→ 丢掉那半个码点，从下一个码点开始：
     *    宁可少一个 emoji，也不产出以孤立低位代理开头的页。
     */
    fun read(file: File, offset: Int, limit: Int = DEFAULT_LIMIT): TextSlice {
        val skipTo = offset.coerceAtLeast(0)
        val pageSize = limit.coerceIn(1, MAX_LIMIT)
        var consumed = 0

        file.reader(Charsets.UTF_8).use { raw ->
            val src = Pushback(raw)

            var skipped = 0
            var lastSkipped = -1
            while (skipped < skipTo) {
                val c = src.read()
                if (c < 0) return TextSlice("", skipped, false)
                lastSkipped = c
                skipped++
            }

            // 起点对齐：offset 落在代理对中间时，这一位是「低位代理」，它的高位在本页之前。
            if (lastSkipped >= 0 && lastSkipped.toChar().isHighSurrogate()) {
                val c = src.read()
                if (c >= 0) {
                    if (c.toChar().isLowSurrogate()) {
                        consumed++
                    } else {
                        src.unread(c)
                    }
                }
            }

            val sb = StringBuilder(if (pageSize < 4096) pageSize else 4096)
            while (sb.length < pageSize) {
                val c = src.read()
                if (c < 0) return TextSlice(sb.toString(), skipTo + consumed, false)
                sb.append(c.toChar())
                consumed++
            }

            // 页尾对齐：最后一个码元是高位代理时，探一个；是低位就并进来（本页多 1 个码元）。
            val probe = src.read()
            if (probe < 0) return TextSlice(sb.toString(), skipTo + consumed, false)
            if (sb[sb.length - 1].isHighSurrogate() && probe.toChar().isLowSurrogate()) {
                sb.append(probe.toChar())
                consumed++
                val next = src.read()
                if (next < 0) return TextSlice(sb.toString(), skipTo + consumed, false)
                src.unread(next)
                return TextSlice(sb.toString(), skipTo + consumed, true)
            }
            src.unread(probe)
            return TextSlice(sb.toString(), skipTo + consumed, true)
        }
    }
}

/**
 * 单码元回退读：只看一个字符判断「后面还有没有」，不能把它吃掉。
 *
 * 用 `Reader.read()` 而不是 `mark/reset`：后者在 `InputStreamReader` 上会按 `readAheadLimit`
 * 把底层缓冲区撑大，为了探一个字符反而多占内存——正是本次要修的那类问题。
 */
private class Pushback(private val r: Reader) {
    private var pending: Int = NONE

    fun read(): Int {
        val p = pending
        if (p != NONE) {
            pending = NONE
            return p
        }
        return r.read()
    }

    fun unread(c: Int) {
        pending = c
    }

    private companion object {
        const val NONE = -2
    }
}
