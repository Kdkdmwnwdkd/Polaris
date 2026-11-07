package com.beichen.polaris.ui

/**
 * 极简 Markdown 解析器（纯 Kotlin，**零 Android / 零第三方依赖**）。
 *
 * 设计目标（路线图 Phase 2 · T11）：
 * ① **解析与渲染分离**——本文件只做「文本 → 块级 / 行内 AST」的纯函数变换，不引用任何
 *    Android 或 Compose 类型，因此可被纯 JVM harness 直接断言（见 scripts/qa/MarkdownVerification.kt）。
 *    渲染层在同包的 [MarkdownText] 里。
 * ② **安全**——**不解析、不执行任何 HTML**：`<script>…</script>` 等标签只会落进 [MdSpan.Plain]，
 *    原样当成普通文本。AST 里根本不存在「HTML / 脚本」节点类型，从根上没有注入面。
 * ③ **流式健壮**——文本可能只有「半截标记」（如只有 `**` 开头），解析器对未闭合标记一律按字面
 *    输出，绝不吞掉内容、绝不抛异常。
 *
 * 支持的 9 类语法：标题（#~###）、粗体、斜体、行内代码、围栏代码块、无序 / 有序列表（含嵌套）、
 * 引用、链接、分割线。
 */

/** 行内片段 AST：一段带样式的文本。 */
sealed interface MdSpan {
    /** 普通文本（也是所有「未识别 / 未闭合标记」的落点）。 */
    data class Plain(val text: String) : MdSpan

    /** 粗体 `**x**`；子片段可再嵌套（如粗体内含斜体）。 */
    data class Bold(val children: List<MdSpan>) : MdSpan

    /** 斜体 `*x*`；子片段可再嵌套。 */
    data class Italic(val children: List<MdSpan>) : MdSpan

    /** 行内代码 `` `x` ``；内容为字面文本，不再二次解析。 */
    data class Code(val text: String) : MdSpan

    /** 链接 `[文字](url)`；children = 链接文字，url = 目标地址（原样保存，不做协议判断）。 */
    data class Link(val children: List<MdSpan>, val url: String) : MdSpan
}

/** 列表项：正文 + 可选的更深一层子列表。 */
data class MdListItem(
    val content: List<MdSpan>,
    val subList: MdBlock? = null
)

/** 表格列对齐（GFM 的 `:--` / `:-:` / `--:`）。 */
enum class MdAlign { LEFT, CENTER, RIGHT }

/**
 * 表格（GFM 风格）。
 *
 * 为什么单独建模而不是塞进 Paragraph：表格有行列结构，按文本画必然错位——
 * 真机实测模型输出的对比表会原样显示成 `| 列一 | 列二 |` 和 `|---|---|`，
 * 用户完全读不了。表格是模型最常用的输出格式之一，必须真渲染。
 *
 * @param header 表头单元格（已按 [alignments] 数量对齐/补齐）
 * @param rows 数据行；每行长度统一为 [alignments] 长度（不足补空、超出截断）
 * @param alignments 每列对齐方式
 */
data class MdTable(
    val header: List<List<MdSpan>>,
    val rows: List<List<List<MdSpan>>>,
    val alignments: List<MdAlign>
)

/** 块级 AST。 */
sealed interface MdBlock {
    /** 标题；`#` 的数量即 level（1~6）。 */
    data class Heading(val level: Int, val content: List<MdSpan>) : MdBlock

    /** 普通段落（连续非空行按 `\n` 合并）。 */
    data class Paragraph(val content: List<MdSpan>) : MdBlock

    /** 围栏代码块；[language] 可为空串（无语言标签）。 */
    data class CodeBlock(val language: String, val code: String) : MdBlock

    /** 无序列表（`-` / `*` / `+`）。 */
    data class BulletList(val items: List<MdListItem>) : MdBlock

    /** 有序列表（`1.` / `1)`）。 */
    data class OrderedList(val items: List<MdListItem>) : MdBlock

    /** 引用块。 */
    data class Quote(val content: List<MdSpan>) : MdBlock

    /** 分割线 `---` / `***` / `___`。 */
    data object Divider : MdBlock

    /** 表格（GFM）。 */
    data class Table(val table: MdTable) : MdBlock
}

/** 纯函数 Markdown 解析入口：不依赖任何 Android API，可脱离设备直接断言。 */
object MarkdownParser {

    /**
     * 刻意的 no-op（登记在 [com.beichen.polaris.AppGraph] 里，`resettable = false`）。
     *
     * 这个 object 只有两个纯函数入口，游标 `i` 属于每次 `parse` 新建的 Parser 实例。
     * 登记它的原因不是「需要清空」，而是让「它为什么可以安全地是 object」有一个
     * 写在明面上的答案——否则下一个人只能靠通读全文确认这里真的没有状态。
     */
    fun reset() {
        // 无状态可清。
    }

    /** 把整段文本切成块级 AST。空 / 全空白输入返回空列表。 */
    fun parse(text: String): List<MdBlock> = BlockParser(text).parse()

    /** 把一行（或一段）文本拆成行内 AST。 */
    fun parseInline(text: String): List<MdSpan> = InlineParser(text).parse()
}

/** 反引号字符（用 Unicode 转义书写，避免与 Kotlin 反引号标识符语法在阅读上混淆）。 */
private const val BACKTICK = '\u0060'

/** 围栏代码块标记（三反引号）。 */
private const val FENCE = "\u0060\u0060\u0060"

// ———————————————————————————————— 行内解析 ————————————————————————————————

/**
 * 行内扫描器：单趟从左到右遍历，遇到标记尝试配对；配对失败即退化为字面文本。
 * 变“吞内容”为“按字面输出”，是流式半截文本健壮性的核心。
 */
private class InlineParser(private val text: String) {
    private val n = text.length
    private var i = 0
    private val out = mutableListOf<MdSpan>()
    private val plain = StringBuilder()

    fun parse(): List<MdSpan> {
        while (i < n) {
            when (text[i]) {
                BACKTICK -> if (!tryCode()) literal(BACKTICK)
                '*' -> tryEmphasis()
                '[' -> if (!tryLink()) literal('[')
                else -> {
                    plain.append(text[i])
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** 未识别 / 未闭合标记：原样落到普通文本，保证不吞内容。 */
    private fun literal(c: Char) {
        plain.append(c)
        i++
    }

    private fun flush() {
        if (plain.isNotEmpty()) {
            out += MdSpan.Plain(plain.toString())
            plain.clear()
        }
    }

    /** 行内代码：`` `x` ``。找不到闭合反引号 → 返回 false（按字面处理）。 */
    private fun tryCode(): Boolean {
        val close = text.indexOf(BACKTICK, i + 1)
        if (close < 0) return false
        flush()
        out += MdSpan.Code(text.substring(i + 1, close))
        i = close + 1
        return true
    }

    /** 强调：`**` 粗体优先于单个 `*` 斜体；任一步找不到闭合都退化为字面。 */
    private fun tryEmphasis() {
        if (i + 1 < n && text[i + 1] == '*') {
            val close = text.indexOf("**", i + 2)
            if (close >= 0) {
                flush()
                out += MdSpan.Bold(MarkdownParser.parseInline(text.substring(i + 2, close)))
                i = close + 2
                return
            }
            plain.append("**")
            i += 2
            return
        }
        val close = findItalicClose(i + 1)
        if (close >= 0) {
            flush()
            out += MdSpan.Italic(MarkdownParser.parseInline(text.substring(i + 1, close)))
            i = close + 1
            return
        }
        plain.append('*')
        i++
    }

    /** 链接：`[文字](url)`。任一步不完整 → 返回 false（按字面处理）。 */
    private fun tryLink(): Boolean {
        val closeBracket = text.indexOf(']', i + 1)
        if (closeBracket < 0) return false
        if (closeBracket + 1 >= n || text[closeBracket + 1] != '(') return false
        val closeParen = text.indexOf(')', closeBracket + 2)
        if (closeParen < 0) return false
        flush()
        val label = text.substring(i + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen)
        out += MdSpan.Link(MarkdownParser.parseInline(label), url)
        i = closeParen + 1
        return true
    }

    /** 找下一个「单 `*`」作为斜体收尾；跳过 `**` 对。找不到返回 -1。 */
    private fun findItalicClose(from: Int): Int {
        var j = from
        while (j < n) {
            if (text[j] == '*') {
                if (j + 1 < n && text[j + 1] == '*') {
                    j += 2
                    continue
                }
                return j
            }
            j++
        }
        return -1
    }
}

// ———————————————————————————————— 块级解析 ————————————————————————————————

/**
 * 块解析器：逐行状态机。每个分支都保证 `i` 前进（至少吞一行），因此任何输入都不会死循环。
 */
private class BlockParser(raw: String) {
    private val lines: List<String> = raw.split("\n")
    private val out = mutableListOf<MdBlock>()
    private var i = 0

    fun parse(): List<MdBlock> {
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val trimmed = line.trim()

            // 围栏代码块：只有存在闭合围栏才成块；否则整行按字面输出
            if (trimmed.startsWith(FENCE)) {
                val close = findClosingFence(i + 1)
                if (close >= 0) {
                    val lang = trimmed.removePrefix(FENCE).trim()
                    val code = lines.subList(i + 1, close).joinToString("\n")
                    out += MdBlock.CodeBlock(lang, code)
                    i = close + 1
                    continue
                }
                // 未闭合围栏：按字面（不动行内解析，避免反引号被误当行内码而「吞掉」）
                out += MdBlock.Paragraph(listOf(MdSpan.Plain(trimmed)))
                i++
                continue
            }

            // 标题（#~######，须后跟空格或行尾）
            val level = headingLevel(line)
            if (level > 0) {
                val body = line.trimStart().drop(level).trimStart()
                out += MdBlock.Heading(level, MarkdownParser.parseInline(body))
                i++
                continue
            }

            // 分割线
            if (isDivider(trimmed)) {
                out += MdBlock.Divider
                i++
                continue
            }

            // 表格：当前行 + 下一行是分隔行才成立（GFM 规定）。
            // 必须排在「列表」「段落」之前：`| a | b |` 既不是列表也不是普通段落。
            if (i + 1 < lines.size && isTableRow(line) && isTableDivider(lines[i + 1])) {
                val (table, next) = parseTable(i)
                if (table != null) {
                    out += MdBlock.Table(table)
                    i = next
                    continue
                }
            }

            // 引用块：连续 `>` 行合并为一块
            if (trimmed.startsWith(">")) {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val body = lines[i].trimStart().removePrefix(">")
                    val text = if (body.startsWith(" ")) body.substring(1) else body
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                    i++
                }
                out += MdBlock.Quote(MarkdownParser.parseInline(sb.toString()))
                continue
            }

            // 列表
            if (matchListMarker(line) != null) {
                val (block, next) = parseList(i)
                out += block
                i = next
                continue
            }

            // 普通段落：吞下连续非空、且不以任一「块起始」开头的行（至少吞当前行以保证前进）
            val sb = StringBuilder()
            sb.append(lines[i].trim())
            i++
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.isBlank() || startsNewBlock(cur)) break
                sb.append('\n').append(cur.trim())
                i++
            }
            out += MdBlock.Paragraph(MarkdownParser.parseInline(sb.toString()))
        }
        return out
    }

    private fun findClosingFence(from: Int): Int {
        var j = from
        while (j < lines.size) {
            if (lines[j].trim().startsWith(FENCE)) return j
            j++
        }
        return -1
    }

    /**
     * 解析表格（起点 = 表头行，起点+1 = 分隔行）。
     *
     * 容错原则（模型输出经常不规整）：
     * - 列数以**分隔行**为准（视觉上它决定了表格宽度）；
     * - 某行列数不足 → 补空串；超出 → 截断（不因多一个 `|` 就整块不渲染）；
     * - 数据行遇到空行 / 非表格行 → 表格结束；
     * - 分隔行本身不合法（列数对不上）→ 返回 null，退化成普通段落（宁可按原文显示，不要渲染成错位的表）。
     */
    private fun parseTable(start: Int): Pair<MdTable?, Int> {
        val headerCells = splitTableRow(lines[start])
        val divCells = splitTableRow(lines[start + 1])
        if (divCells.isEmpty()) return null to start

        val cols = divCells.size
        val aligns = divCells.map { cell ->
            val t = cell.trim()
            when {
                t.startsWith(":") && t.endsWith(":") -> MdAlign.CENTER
                t.endsWith(":") -> MdAlign.RIGHT
                else -> MdAlign.LEFT
            }
        }
        // 表头列数与分隔行不一致时，以分隔行为准补齐/截断
        val header = normalizeCells(headerCells, cols)
        val rows = mutableListOf<List<List<MdSpan>>>()
        var j = start + 2
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank() || !isTableRow(line)) break
            rows += normalizeCells(splitTableRow(line), cols)
            j++
        }
        return MdTable(header, rows, aligns) to j
    }

    /** 把某行单元格数补齐/截断到 [cols]，并解析行内样式。 */
    private fun normalizeCells(cells: List<String>, cols: Int): List<List<MdSpan>> {
        val padded = when {
            cells.size == cols -> cells
            cells.size < cols -> cells + List(cols - cells.size) { "" }
            else -> cells.take(cols)
        }
        return padded.map { MarkdownParser.parseInline(it.trim()) }
    }

    /** 解析一段列表：递归支持嵌套（子列表挂在上一项的 [MdListItem.subList]）。 */
    private fun parseList(start: Int): Pair<MdBlock, Int> {
        val first = matchListMarker(lines[start])!!
        val baseIndent = first.indent
        val ordered = first.ordered
        val items = mutableListOf<MutableListItem>()
        var j = start
        while (j < lines.size) {
            val line = lines[j]
            if (line.isBlank()) break
            val m = matchListMarker(line)
            if (m == null) {
                // 缩进大于基准的普通行 → 归入上一项正文（续行）
                if (items.isNotEmpty() && indentWidth(line) > baseIndent) {
                    items.last().continuation += line.trim()
                    j++
                    continue
                }
                break
            }
            if (m.ordered != ordered) break
            if (m.indent < baseIndent) break
            if (m.indent > baseIndent) {
                if (items.isEmpty()) break
                val (child, next) = parseList(j)
                items.last().subList = child
                j = next
                continue
            }
            items += MutableListItem(MarkdownParser.parseInline(m.content))
            j++
        }
        val block = if (ordered) {
            MdBlock.OrderedList(items.map { it.build() })
        } else {
            MdBlock.BulletList(items.map { it.build() })
        }
        return block to j
    }
}

/** 列表解析过程中的可变中间态；[build] 时再拼出不可变 [MdListItem]。 */
private class MutableListItem(val content: List<MdSpan>) {
    val continuation = mutableListOf<String>()
    var subList: MdBlock? = null

    fun build(): MdListItem {
        if (continuation.isEmpty()) return MdListItem(content, subList)
        val extra = continuation.joinToString("\n")
        val merged = if (content.isEmpty()) {
            listOf(MdSpan.Plain(extra))
        } else {
            content + MdSpan.Plain("\n$extra")
        }
        return MdListItem(merged, subList)
    }
}

/** 一个列表项的标记解析结果。 */
private class ListMarker(val ordered: Boolean, val indent: Int, val content: String)

/** 行首缩进宽度（空格计 1，制表符计 4）。 */
private fun indentWidth(line: String): Int {
    var w = 0
    for (c in line) {
        when (c) {
            ' ' -> w += 1
            '\t' -> w += 4
            else -> return w
        }
    }
    return w
}

/** 去掉行首缩进（空格 / 制表符）。 */
private fun stripIndent(line: String): String {
    var k = 0
    while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
    return line.substring(k)
}

/**
 * 识别列表标记：无序（`-` / `*` / `+` 后跟空格）、有序（数字 + `.` / `)` 后跟空格）。
 * 不匹配返回 null（例如 `**粗体**`、`-x` 都不是列表项）。
 */
private fun matchListMarker(line: String): ListMarker? {
    val indent = indentWidth(line)
    val body = stripIndent(line)
    if (body.isEmpty()) return null
    val first = body[0]
    if (first == '-' || first == '*' || first == '+') {
        if (body.length == 1) return ListMarker(false, indent, "")
        if (body[1] == ' ') return ListMarker(false, indent, body.substring(2).trimStart())
        return null
    }
    var j = 0
    while (j < body.length && body[j].isDigit()) j++
    if (j > 0 && j < body.length && (body[j] == '.' || body[j] == ')')) {
        if (j + 1 >= body.length || body[j + 1] == ' ') {
            return ListMarker(true, indent, body.substring(j + 1).trimStart())
        }
    }
    return null
}

/** 标题级别：`#`~`######` 且后跟空格或行尾；否则返回 0（不是标题）。 */
private fun headingLevel(line: String): Int {
    val t = line.trimStart()
    if (!t.startsWith("#")) return 0
    var count = 0
    while (count < t.length && t[count] == '#') count++
    if (count > 6) return 0
    if (count < t.length && t[count] != ' ') return 0
    return count
}

/** 分割线：整行由 ≥3 个相同的 `-` / `*` / `_` 组成。 */
private fun isDivider(t: String): Boolean {
    if (t.length < 3) return false
    val ch = t[0]
    if (ch != '-' && ch != '*' && ch != '_') return false
    for (c in t) if (c != ch) return false
    return true
}

/** 当前行是否开启一个新的块（用于段落收集时判断边界）。 */
private fun startsNewBlock(line: String): Boolean {
    val t = line.trim()
    if (t.startsWith(FENCE)) return true
    if (headingLevel(line) > 0) return true
    if (isDivider(t)) return true
    if (t.startsWith(">")) return true
    if (matchListMarker(line) != null) return true
    // 表格行也要断段：否则「正文 + 表格」会被并成同一个 Paragraph
    if (isTableRow(line)) return true
    return false
}

/**
 * 是否是表格行：至少含一个 `|`。
 *
 * 不要求首尾都有 `|`——GFM 允许 `a | b` 这种写法，模型输出里也很常见。
 */
private fun isTableRow(line: String): Boolean = line.contains('|')

/**
 * 是否是表格的「分隔行」：`|---|---|` / `|:--|--:|:-:|`。
 *
 * 每个单元格必须只由 `-`（至少一个）、可选的冒号组成——别把 `| a | b |` 误判成分隔行。
 */
private fun isTableDivider(line: String): Boolean {
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val t = cell.trim()
        if (t.isEmpty()) return@all false
        val core = t.removePrefix(":").removeSuffix(":")
        core.isNotEmpty() && core.all { it == '-' }
    }
}

/**
 * 切分表格行 → 单元格原文。
 *
 * 处理三种模型输出的常见变体：
 * - `| a | b |`（标准）
 * - `a | b`（无首尾竖线）
 * - `| a | b`（只有首）
 *
 * 不做 `\|` 转义处理：GFM 支持，但模型输出里极少见，加进来会让切分逻辑复杂一倍、
 * 收益很低；真遇到时退化成多一列，比整块不渲染好。
 */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    if (s.isEmpty()) return emptyList()
    return s.split('|')
}
