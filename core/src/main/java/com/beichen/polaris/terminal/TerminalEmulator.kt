package com.beichen.polaris.terminal

import java.util.ArrayDeque

/**
 * 终端仿真器（净室重写）。
 *
 * 规范依据：xterm ctlseqs（<https://invisible-island.net/xterm/ctlseqs/ctlseqs.html>）
 * 与 ECMA-48；字符宽度用 Markus Kuhn 的公开域 wcwidth 算法。**未参考、未复制
 * Termux（GPLv3）或任何第三方终端仿真器的任何一行代码。**
 *
 * 职责：吃字节流（[append]），维护「屏幕缓冲区 + 光标 + 字符属性」，对外吐
 * 不可变快照（[snapshot]）供 UI 直接渲染。零 Android 依赖，可在 JVM 单测里直接跑。
 *
 * 线程契约：所有写方法（append / resize / reset）与 [snapshot] 都在同一把内部锁上，
 * 读线程（TerminalSession 的 pty 读协程）写、主线程快照，两端互见完整状态。
 *
 * 不可变性约定：屏幕行采用「写时复制」——任何单元格写入都会换行对象，
 * 这样 Compose 按行渲染时能以对象身份正确判断「这行变没变」，不会漏刷新。
 */

/** 默认前景色（xterm 近似亮灰）。UI 渲染时映射到主题 onSurface，不直接使用该值。 */
const val COLOR_DEFAULT_FG: Int = 0xFFD8D8D8.toInt()

/** 默认背景色。UI 渲染时映射为「无背景」（透出页面底色），不直接使用该值。 */
const val COLOR_DEFAULT_BG: Int = 0xFF000000.toInt()

/** 16 色 ANSI 标准值（xterm 默认调色板，ARGB）。下标 0-7 常规，8-15 亮色。 */
val ANSI_16: IntArray = intArrayOf(
    0xFF000000.toInt(),  // 0 black
    0xFFCD0000.toInt(),  // 1 red
    0xFF00CD00.toInt(),  // 2 green
    0xFFCDCD00.toInt(),  // 3 yellow
    0xFF0000EE.toInt(),  // 4 blue
    0xFFCD00CD.toInt(),  // 5 magenta
    0xFF00CDCD.toInt(),  // 6 cyan
    0xFFE5E5E5.toInt(),  // 7 white
    0xFF7F7F7F.toInt(),  // 8 bright black
    0xFFFF0000.toInt(),  // 9 bright red
    0xFF00FF00.toInt(),  // 10 bright green
    0xFFFFFF00.toInt(),  // 11 bright yellow
    0xFF5C5CFF.toInt(),  // 12 bright blue
    0xFFFF00FF.toInt(),  // 13 bright magenta
    0xFF00FFFF.toInt(),  // 14 bright cyan
    0xFFFFFFFF.toInt()   // 15 bright white
)

/** 256 色 xterm 标准表：0-15 复用 [ANSI_16]，16-231 为 6×6×6 立方，232-255 为灰阶。 */
val XTERM_256: IntArray = IntArray(256).also { table ->
    for (i in 0 until 16) table[i] = ANSI_16[i]
    // 立方色阶取值（xterm 标准）：0, 95, 135, 175, 215, 255
    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
    for (i in 16 until 232) {
        val n = i - 16
        val r = levels[(n / 36) % 6]
        val g = levels[(n / 6) % 6]
        val b = levels[n % 6]
        table[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    for (i in 232 until 256) {
        val v = 8 + (i - 232) * 10
        table[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}

/**
 * 单个字符单元格：字符 + 全套 SGR 属性。
 *
 * @param char 字符文本。BMP 单字符；星彩（emoji 等 astral 码点）存 UTF-16 代理对，
 *   占 2 列仍只算一格——列对齐由 [wcwidth] 保证，渲染层按格上色。
 * @param combining 叠加在 [char] 上的组合字符（不占列，如拉丁变音符号）；null 为无。
 * @param fg / bg ARGB 颜色；等于 [COLOR_DEFAULT_FG] / [COLOR_DEFAULT_BG] 时表示「默认」，
 *   UI 映射到主题色，仿真器不感知明暗主题。
 */
data class CharCell(
    val char: String = " ",
    val combining: String? = null,
    val fg: Int = COLOR_DEFAULT_FG,
    val bg: Int = COLOR_DEFAULT_BG,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false
)

/**
 * 一行屏幕内容（写时复制：单元格变化时由仿真器换行对象，UI 靠对象身份判断刷新）。
 *
 * @param cells 长度恒等于终端列数的单元格数组，右侧允许是被裁掉的空白。
 * @param continued 本行是否因**自动换行而写到半截**（内容续到下一行）。语义与 xterm 的
 *   LINEWRAPPED 一致：标记打在「被截断的那一行」上，承接内容的那行不带标记。
 *   注意：目前没有消费方（UI 按固定列宽渲染，不需要这个标记）；留着是因为
 *   「复制时把软换行还原成一行」迟早要用，而到那时再从字节流反推是不可能的。
 */
class TerminalRow(val cells: Array<CharCell>, val continued: Boolean = false) {

    /** 整行文本（含尾部空白；组合字符跟在主字符后）。 */
    fun text(): String = buildString(cells.size * 2) {
        for (c in cells) {
            append(c.char)
            c.combining?.let { append(it) }
        }
    }

    companion object {
        /** 构造一列宽的全默认空白行。 */
        fun blank(columns: Int): TerminalRow =
            TerminalRow(Array(columns.coerceAtLeast(1)) { CharCell() })

        /**
         * 行内最后一个「有视觉内容」的单元格下标 +1（裁剪尾部默认背景空格）。
         * 裁剪只砍「空格 + 默认背景 + 非反显」，绝不砍颜色块与反显块。
         */
        fun visibleLength(cells: Array<CharCell>): Int {
            var end = cells.size
            while (end > 0) {
                val c = cells[end - 1]
                val plain = c.char == " " && c.combining == null &&
                    c.bg == COLOR_DEFAULT_BG && !c.inverse
                if (!plain) break
                end--
            }
            return end
        }
    }
}

/**
 * 屏幕快照（UI 直接消费，无需了解仿真器内部结构）。
 *
 * @param lines 滚动历史（旧→新）+ 当前屏幕，共 scrollback + rows 行；行对象与仿真器
 *   共享，但因行是写时复制的，快照里的行内容不会再变（终端活视图语义由快照频率承载）。
 * @param cursorRow / cursorCol 光标位置（0 基；UI 不画光标，仅供调试与测试断言）。
 */
data class TerminalSnapshot(
    val lines: List<TerminalRow>,
    val cursorRow: Int,
    val cursorCol: Int
) {
    companion object {
        val EMPTY = TerminalSnapshot(emptyList(), 0, 0)
    }
}

/** 待用字符属性（SGR 累积态；写入单元格时快照进去）。 */
private data class CellStyle(
    var fg: Int = COLOR_DEFAULT_FG,
    var bg: Int = COLOR_DEFAULT_BG,
    var bold: Boolean = false,
    var dim: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false
) {
    fun reset() {
        fg = COLOR_DEFAULT_FG
        bg = COLOR_DEFAULT_BG
        bold = false
        dim = false
        italic = false
        underline = false
        inverse = false
    }

    fun toCell(text: String, combining: String? = null): CharCell =
        CharCell(text, combining, fg, bg, bold, dim, italic, underline, inverse)
}

/** 解析器状态（xterm ctlseqs 状态机子集，覆盖 shell / vim / top 常用序列）。 */
private enum class ParserState {
    GROUND,
    ESCAPE,
    ESCAPE_INTERMEDIATE,
    CSI_ENTRY,
    CSI_PARAM,
    CSI_IGNORE,
    OSC_STRING,
    STRING_IGNORE  // DCS / SOS / PM / APC：统一按「吃到 ST 为止」忽略
}

/**
 * VT100/xterm 终端仿真器。
 *
 * @param columns 初始列数（≥1）
 * @param rows 初始行数（≥1）
 */
class TerminalEmulator(
    columns: Int = 80,
    rows: Int = 24
) {
    private val lock = Any()

    /** 当前列数（[resize] 后更新）。 */
    var columns: Int = columns.coerceAtLeast(1)
        private set

    /** 当前行数（[resize] 后更新）。 */
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var screen: MutableList<TerminalRow> = ArrayList<TerminalRow>(this.rows).also { fillBlank(it, this.rows, this.columns) }
    private var scrollback: ArrayDeque<TerminalRow> = ArrayDeque()

    /** 备用屏幕备份（DECSET 1049 切走时暂存主屏）；null = 当前就在主屏。 */
    private var altScreen: MutableList<TerminalRow>? = null

    private var curRow: Int = 0
    private var curCol: Int = 0
    private var wrapPending: Boolean = false  // 光标停在右缘、下一个可打印字符才换行
    private val style = CellStyle()
    private var savedRow: Int = 0
    private var savedCol: Int = 0
    private val savedStyle = CellStyle()
    private var scrollTop: Int = 0
    private var scrollBottom: Int = this.rows - 1
    private var tabStops: BooleanArray = BooleanArray(this.columns) { it % 8 == 0 }
    private var lastPrinted: String = " "  // REP(b) 的重复对象

    // —— 解析器状态机（xterm ctlseqs）——
    private var state: ParserState = ParserState.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate: Char = ' '
    private var stringEscape: Boolean = false  // OSC/DCS 串内见到过 ESC

    // —— UTF-8 增量解码（跨 read 边界拼接多字节字符）——
    private var utf8Pending: Int = 0
    private var utf8Value: Int = 0

    // —————————————————————————— 对外 API ——————————————————————————

    /**
     * 喂入一段字节流（通常是一次 pty read 的 chunk）。
     *
     * 多字节 UTF-8 字符跨 chunk 边界时由内部 pending 状态拼接，调用方无需关心。
     *
     * @param len 有效长度（默认整个数组）
     */
    fun append(bytes: ByteArray, len: Int = bytes.size) {
        val n = len.coerceAtMost(bytes.size)
        if (n <= 0) return
        synchronized(lock) {
            for (i in 0 until n) appendByte(bytes[i].toInt() and 0xFF)
        }
    }

    /** 喂入单字节（测试与逐字节流式输入用）。 */
    fun append(byte: Int) {
        synchronized(lock) { appendByte(byte and 0xFF) }
    }

    /**
     * 屏幕快照：滚动历史（旧→新）+ 当前屏幕。UI 按行渲染即用。
     * 主线程可随时调用（与读线程的写入互斥）。
     */
    fun snapshot(): TerminalSnapshot = synchronized(lock) {
        val lines = ArrayList<TerminalRow>(scrollback.size + screen.size)
        lines.addAll(scrollback)
        lines.addAll(screen)
        TerminalSnapshot(lines, curRow, curCol.coerceAtMost(columns - 1))
    }

    /** 仅当前屏幕的纯文本（测试 / 断言用；行间以 \n 连接，保留尾部空白）。 */
    fun getScreenText(): String = synchronized(lock) {
        buildString {
            for (r in 0 until rows) {
                if (r > 0) append('\n')
                append(screen[r].text())
            }
        }
    }

    /** 滚动历史行数（上限 [MAX_SCROLLBACK] 行，超过丢弃最旧）。 */
    val scrollbackLines: Int
        get() = synchronized(lock) { scrollback.size }

    /** 光标行（0 基）。 */
    val cursorRow: Int
        get() = synchronized(lock) { this.curRow }

    /** 光标列（0 基，已按右边距收敛）。 */
    val cursorCol: Int
        get() = synchronized(lock) { this.curCol.coerceAtMost(columns - 1) }

    /**
     * 改变终端尺寸并尽量保留内容：滚动历史 + 屏幕拼成总行，取最新 rows 行，
     * 逐行截断 / 补足到新列数；滚动区域重置为全区，光标收敛到新范围内。
     */
    fun resize(newCols: Int, newRows: Int) {
        synchronized(lock) {
            val cols = newCols.coerceAtLeast(1)
            val rws = newRows.coerceAtLeast(1)
            if (cols == columns && rws == rows) return
            screen = refit(screen, cols, rws)
            altScreen?.let { altScreen = refit(it, cols, rws) }
            columns = cols
            rows = rws
            tabStops = BooleanArray(cols) { it % 8 == 0 }
            scrollTop = 0
            scrollBottom = rws - 1
            curRow = curRow.coerceIn(0, rws - 1)
            curCol = curCol.coerceIn(0, cols - 1)
            wrapPending = false
        }
    }

    /** 全量复位（ESC c / RIS 语义）：清屏、清历史、光标归位、属性与状态机复位。 */
    fun reset() {
        synchronized(lock) {
            state = ParserState.GROUND
            csiParams.setLength(0)
            csiPrivate = ' '
            stringEscape = false
            utf8Pending = 0
            utf8Value = 0
            style.reset()
            savedStyle.reset()
            savedRow = 0
            savedCol = 0
            wrapPending = false
            lastPrinted = " "
            altScreen = null
            scrollback.clear()
            screen = ArrayList<TerminalRow>(rows).also { fillBlank(it, rows, columns) }
            curRow = 0
            curCol = 0
            scrollTop = 0
            scrollBottom = rows - 1
            tabStops = BooleanArray(columns) { it % 8 == 0 }
        }
    }

    // —————————————————————————— 字节入口与 UTF-8 解码 ——————————————————————————

    private fun appendByte(b: Int) {
        if (b < 0x80) {
            // ASCII：中途出现即作废未完成的多字节序列（畸形流保护，防状态卡死）
            if (utf8Pending > 0) {
                utf8Pending = 0
                utf8Value = 0
            }
            processAscii(b)
            return
        }
        if (utf8Pending == 0) {
            when (b) {
                in 0xC2..0xDF -> { utf8Pending = 1; utf8Value = b and 0x1F }
                in 0xE0..0xEF -> { utf8Pending = 2; utf8Value = b and 0x0F }
                in 0xF0..0xF7 -> { utf8Pending = 3; utf8Value = b and 0x07 }
                else -> Unit  // 0x80..0xBF 孤立续字节 / 0xF8+ 非法：丢弃
            }
            return
        }
        if (b !in 0x80..0xBF) {
            // 多字节序列中途被非续字节打断：作废当前序列，按新序列头重新开始
            utf8Pending = 0
            utf8Value = 0
            appendByte(b)
            return
        }
        utf8Value = (utf8Value shl 6) or (b and 0x3F)
        utf8Pending--
        if (utf8Pending == 0) {
            val cp = utf8Value
            utf8Value = 0
            if (cp in 1..0x10FFFF) printCodepoint(cp)
        }
    }

    // —————————————————————————— 状态机分派 ——————————————————————————

    private fun processAscii(b: Int) {
        when (state) {
            ParserState.GROUND -> when {
                b == 0x1B -> state = ParserState.ESCAPE
                b < 0x20 -> executeControl(b)
                b == 0x7F -> Unit  // DEL：忽略
                else -> printChar(b.toChar())
            }

            ParserState.ESCAPE -> escapeDispatch(b)

            ParserState.ESCAPE_INTERMEDIATE -> when {
                b in 0x20..0x2F -> Unit  // 继续收中间字节
                b in 0x30..0x7E -> state = ParserState.GROUND  // 终结字节：整条忽略（字符集指定等）
                b < 0x20 -> executeControl(b)
                else -> state = ParserState.GROUND
            }

            ParserState.CSI_ENTRY -> csiEntry(b)
            ParserState.CSI_PARAM -> csiParam(b)

            ParserState.CSI_IGNORE -> if (b in 0x40..0x7E) state = ParserState.GROUND

            ParserState.OSC_STRING, ParserState.STRING_IGNORE -> stringStateByte(b)
        }
    }

    private fun escapeDispatch(b: Int) {
        when {
            b < 0x20 -> executeControl(b)  // 转义序列中嵌入控制字符（规范允许）
            b == '['.code -> {
                state = ParserState.CSI_ENTRY
                csiParams.setLength(0)
                csiPrivate = ' '
            }

            b == ']'.code -> {
                state = ParserState.OSC_STRING
                stringEscape = false
            }

            b == 'P'.code || b == 'X'.code || b == '^'.code || b == '_'.code -> {
                // DCS / SOS / PM / APC：字符串负载一律忽略（终端 Tab 不需要）
                state = ParserState.STRING_IGNORE
                stringEscape = false
            }

            b in 0x20..0x2F -> state = ParserState.ESCAPE_INTERMEDIATE

            b == '7'.code -> { saveCursor(); state = ParserState.GROUND }
            b == '8'.code -> { restoreCursor(); state = ParserState.GROUND }
            b == 'M'.code -> { reverseIndex(); state = ParserState.GROUND }
            b == 'D'.code -> { lineFeed(); state = ParserState.GROUND }
            b == 'E'.code -> { curCol = 0; lineFeed(); state = ParserState.GROUND }
            b == 'c'.code -> { reset(); state = ParserState.GROUND }
            b == 'H'.code -> { tabStops[curCol.coerceIn(tabStops.indices)] = true; state = ParserState.GROUND }
            else -> state = ParserState.GROUND  // 其余单字符转义（含字符集指定）一律忽略
        }
    }

    private fun csiEntry(b: Int) {
        when {
            b in 0x30..0x39 || b == ';'.code || b == ':'.code -> {
                csiParams.append(b.toChar())
                state = ParserState.CSI_PARAM
            }

            b in 0x3C..0x3F -> {  // '<' '=' '>' '?' 私有前缀
                csiPrivate = b.toChar()
                state = ParserState.CSI_PARAM
            }

            b in 0x20..0x2F -> state = ParserState.CSI_IGNORE  // 中间字节：整条忽略
            b in 0x40..0x7E -> {  // 无参数直接终结（如 CSI H）
                // 先回到 GROUND 再派发：REP(b) 要走真实打印路径，而 printCodepoint 在
                // 非 GROUND 态会把字符丢掉（见 csiDispatch 里 'b' 分支）。
                state = ParserState.GROUND
                csiDispatch(b.toChar())
            }

            b < 0x20 -> executeControl(b)
            else -> state = ParserState.GROUND
        }
    }

    private fun csiParam(b: Int) {
        when {
            b in 0x30..0x3B -> {  // 数字 / ':' / ';'
                if (csiParams.length < MAX_CSI_PARAMS) csiParams.append(b.toChar())
            }

            b in 0x20..0x2F -> state = ParserState.CSI_IGNORE
            b in 0x40..0x7E -> {
                state = ParserState.GROUND   // 同上：先复位再派发，REP 才打得出来
                csiDispatch(b.toChar())
            }

            b < 0x20 -> executeControl(b)
            else -> state = ParserState.GROUND
        }
    }

    /** OSC / DCS 字符串态：BEL 或 ST（ESC \）结束，其余字节是字符串负载，忽略。 */
    private fun stringStateByte(b: Int) {
        if (stringEscape) {
            stringEscape = false
            when (b) {
                0x5C -> state = ParserState.GROUND  // ST = ESC '\'
                0x1B -> stringEscape = true         // 连续 ESC：继续等 ST
                else -> {                            // 畸形串里的 ESC：按新转义处理
                    state = ParserState.ESCAPE
                    escapeDispatch(b)
                }
            }
            return
        }
        when (b) {
            0x07 -> state = ParserState.GROUND  // BEL 结束
            0x1B -> stringEscape = true
            else -> Unit                        // 字符串内容：忽略
        }
    }

    // —————————————————————————— 控制字符 ——————————————————————————

    private fun executeControl(b: Int) {
        when (b) {
            0x07 -> Unit  // BEL：不响铃
            0x08 -> if (curCol > 0) { curCol--; wrapPending = false }  // BS
            0x09 -> { curCol = nextTabStop(curCol); wrapPending = false }  // TAB
            0x0A, 0x0B, 0x0C -> lineFeed()  // LF / VT / FF
            0x0D -> { curCol = 0; wrapPending = false }  // CR
            0x0E, 0x0F -> Unit  // SO / SI 字符集切换：不实现，忽略
            else -> Unit        // DLE 等其余控制字符：忽略
        }
    }

    // —————————————————————————— 打印与光标 ——————————————————————————

    private fun printChar(c: Char) = printCodepoint(c.code)

    private fun printCodepoint(cp: Int) {
        if (state != ParserState.GROUND) return  // 非 GROUND 态的可打印字符（CSI 参数等）丢弃
        val w = wcwidth(cp)
        if (w == 0) {
            // 组合字符：叠加到左边一格（不占列、不动光标）
            if (curCol > 0) {
                val r = curRow
                val c = curCol - 1
                val prev = screen[r].cells[c]
                putCell(r, c, prev.copy(combining = (prev.combining ?: "") + cpToString(cp)))
            }
            return
        }
        if (w < 0) return  // 控制码点不应到这，防御

        if (wrapPending) {
            // 停在新行（打上续行标记，便于 UI 裁尾部空白）
            wrapPending = false
            curCol = 0
            screen[curRow] = TerminalRow(screen[curRow].cells, continued = true)
            lineFeed()
        } else if (curCol + w > columns) {
            // 宽字符恰好放不进最后一列：先换行再落笔
            curCol = 0
            lineFeed()
        }

        val r = curRow
        putCell(r, curCol, style.toCell(cpToString(cp)))
        if (w == 2 && curCol + 1 < columns) {
            // 宽字符右半格占位（空文本，保证 text() 不重复出字）
            putCell(r, curCol + 1, style.toCell(""))
        }
        lastPrinted = cpToString(cp)
        curCol += w
        wrapPending = curCol >= columns
    }

    private fun lineFeed() {
        when {
            curRow == scrollBottom -> scrollUp(1)
            curRow < rows - 1 -> curRow++
            else -> Unit  // 已在屏幕底且不在滚动区：钉住
        }
    }

    private fun reverseIndex() {
        when {
            curRow == scrollTop -> scrollDown(1)
            curRow > 0 -> curRow--
            else -> Unit
        }
    }

    private fun carriageReturn() {
        curCol = 0
        wrapPending = false
    }

    // —————————————————————————— CSI 命令 ——————————————————————————

    private fun csiDispatch(final: Char) {
        val params = parseParams(csiParams.toString())
        val n = params.p(0, 1)
        when (final) {
            '@' -> insertChars(n)                       // ICH
            'A' -> { cursorUp(n); wrapPending = false }  // CUU
            'B' -> { cursorDown(n); wrapPending = false }// CUD
            'C' -> { curCol = (curCol + n).coerceAtMost(columns - 1); wrapPending = false }  // CUF
            'D' -> { curCol = (curCol - n).coerceAtLeast(0); wrapPending = false }            // CUB
            'E' -> { cursorDown(n); carriageReturn() }  // CNL
            'F' -> { cursorUp(n); carriageReturn() }    // CPL
            'G', '`' -> { curCol = (n - 1).coerceIn(0, columns - 1); wrapPending = false }       // HPA
            'H', 'f' -> {                                // CUP
                val row = (params.p(0, 1) - 1).coerceIn(0, rows - 1)
                val col = (params.p(1, 1) - 1).coerceIn(0, columns - 1)
                curRow = row
                curCol = col
                wrapPending = false
            }

            'I' -> repeat(n.coerceAtMost(columns)) { curCol = nextTabStop(curCol) }  // CHT
            'J' -> eraseInDisplay(params.p(0, 0))                    // ED
            'K' -> eraseInLine(params.p(0, 0))                       // EL
            'L' -> insertLines(n)                                    // IL
            'M' -> deleteLines(n)                                    // DL
            'P' -> deleteChars(n)                                    // DCH
            'S' -> scrollUp(n)                                       // SU
            'T' -> scrollDown(n)                                     // SD
            'X' -> eraseChars(n)                                     // ECH
            'Z' -> repeat(n.coerceAtMost(columns)) { curCol = prevTabStop(curCol) }  // CBT
            'b' -> repeat(n.coerceAtMost(MAX_REPEAT)) { printCodepointString(lastPrinted) }  // REP
            'd' -> { curRow = (n - 1).coerceIn(0, rows - 1); wrapPending = false }  // VPA
            'g' -> clearTabStop(params.p(0, 0))                      // TBC
            'h' -> if (csiPrivate == '?') setModes(params, true)     // DECSET
            'l' -> if (csiPrivate == '?') setModes(params, false)    // DECRST
            'm' -> sgr(params)                                       // SGR
            'r' -> setScrollRegion(params.p(0, 1), params.p(1, 1))   // DECSTBM
            's' -> saveCursor()                                      // 保存光标
            'u' -> restoreCursor()                                   // 恢复光标
            else -> Unit  // 未实现的终结字节：忽略（规范允许的安全行为）
        }
    }

    /** CSI 参数字符串 → 整数列表：空段按 0（与 xterm 一致；'38:5:n' 子参数同样支持）。 */
    private fun parseParams(s: String): List<Int> {
        if (s.isEmpty()) return emptyList()
        return s.split(';', ':').map { it.trim().toIntOrNull() ?: 0 }
    }

    /** 取第 [i] 个参数；0 或缺省时回落 [default]（CUU/CUP 等「0 = 默认」语义）。 */
    private fun List<Int>.p(i: Int, default: Int): Int {
        val v = getOrNull(i) ?: 0
        return if (v == 0) default else v
    }

    private fun cursorUp(n: Int) {
        curRow = (curRow - n).coerceAtLeast(scrollTop)
    }

    private fun cursorDown(n: Int) {
        curRow = (curRow + n).coerceAtMost(scrollBottom)
    }

    private fun insertChars(n: Int) {
        val count = n.coerceIn(0, columns - curCol)
        if (count <= 0) return
        val cells = screen[curRow].cells.copyOf()
        for (c in columns - 1 downTo curCol + count) cells[c] = cells[c - count]
        for (c in curCol until curCol + count) cells[c] = style.toCell(" ")
        replaceRow(curRow, cells)
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val count = n.coerceIn(0, columns - curCol)
        if (count <= 0) return
        val cells = screen[curRow].cells.copyOf()
        for (c in curCol until columns - count) cells[c] = cells[c + count]
        for (c in columns - count until columns) cells[c] = style.toCell(" ")
        replaceRow(curRow, cells)
        wrapPending = false
    }

    private fun eraseChars(n: Int) {
        val end = (curCol + n).coerceIn(curCol, columns)
        val cells = screen[curRow].cells.copyOf()
        for (c in curCol until end) cells[c] = style.toCell(" ")
        replaceRow(curRow, cells)
    }

    private fun insertLines(n: Int) {
        if (curRow !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - curRow + 1)
        repeat(count) {
            screen.removeAt(scrollBottom)
            screen.add(curRow, TerminalRow.blank(columns))
        }
        carriageReturn()
    }

    private fun deleteLines(n: Int) {
        if (curRow !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - curRow + 1)
        repeat(count) {
            screen.removeAt(curRow)
            screen.add(scrollBottom, TerminalRow.blank(columns))
        }
        carriageReturn()
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLineRight(curRow)
                for (r in curRow + 1 until rows) replaceRow(r, blankCells())
            }

            1 -> {
                for (r in 0 until curRow) replaceRow(r, blankCells())
                eraseLineLeft(curRow)
            }

            2 -> for (r in 0 until rows) replaceRow(r, blankCells())
            3 -> {
                for (r in 0 until rows) replaceRow(r, blankCells())
                scrollback.clear()  // ED 3 连滚动历史一起清
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> eraseLineRight(curRow)
            1 -> eraseLineLeft(curRow)
            2 -> replaceRow(curRow, blankCells())
        }
    }

    private fun eraseLineRight(r: Int) {
        val cells = screen[r].cells.copyOf()
        for (c in curCol until columns) cells[c] = style.toCell(" ")
        replaceRow(r, cells)
    }

    private fun eraseLineLeft(r: Int) {
        val end = (curCol + 1).coerceIn(1, columns)
        val cells = screen[r].cells.copyOf()
        for (c in 0 until end) cells[c] = style.toCell(" ")
        replaceRow(r, cells)
    }

    /**
     * 滚动区整体上移 [n] 行（LF 触底 / SU）。
     * 仅当滚动区顶 = 屏幕顶且不在备用屏时，移出的行才进滚动历史——
     * 这正是「上下滑看历史」的内容来源。
     */
    private fun scrollUp(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            val removed = screen.removeAt(scrollTop)
            if (scrollTop == 0 && altScreen == null) {
                if (scrollback.size >= MAX_SCROLLBACK) scrollback.removeFirst()
                scrollback.addLast(removed)
            }
            screen.add(scrollBottom, TerminalRow.blank(columns))
        }
    }

    /** 滚动区整体下移 [n] 行（ESC M 顶行 / SD）。 */
    private fun scrollDown(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            screen.removeAt(scrollBottom)
            screen.add(scrollTop, TerminalRow.blank(columns))
        }
    }

    private fun setScrollRegion(p1: Int, p2: Int) {
        val top = p1 - 1
        val bottom = p2 - 1
        if (top >= bottom || bottom >= rows) {
            // 参数非法（含 bottom 越界）：按全区重置，而不是把屏幕搞死
            scrollTop = 0
            scrollBottom = rows - 1
        } else {
            scrollTop = top
            scrollBottom = bottom
        }
        curRow = 0
        curCol = 0
        wrapPending = false
    }

    private fun saveCursor() {
        savedRow = curRow
        savedCol = curCol
        savedStyle.fg = style.fg
        savedStyle.bg = style.bg
        savedStyle.bold = style.bold
        savedStyle.dim = style.dim
        savedStyle.italic = style.italic
        savedStyle.underline = style.underline
        savedStyle.inverse = style.inverse
    }

    private fun restoreCursor() {
        curRow = savedRow.coerceIn(0, rows - 1)
        curCol = savedCol.coerceIn(0, columns - 1)
        style.fg = savedStyle.fg
        style.bg = savedStyle.bg
        style.bold = savedStyle.bold
        style.dim = savedStyle.dim
        style.italic = savedStyle.italic
        style.underline = savedStyle.underline
        style.inverse = savedStyle.inverse
        wrapPending = false
    }

    // —————————————————————————— SGR 与私有模式 ——————————————————————————

    private fun sgr(params: List<Int>) {
        if (params.isEmpty()) {
            style.reset()
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when (p) {
                0 -> style.reset()
                1 -> style.bold = true
                2 -> style.dim = true
                3 -> style.italic = true
                4 -> style.underline = true
                5, 6 -> Unit  // 闪烁：忽略
                7 -> style.inverse = true
                8, 9 -> Unit   // 隐藏 / 删除线：不实现
                21 -> style.underline = true  // 双下划线按单下划线处理
                22 -> { style.bold = false; style.dim = false }
                23 -> style.italic = false
                24 -> style.underline = false
                25 -> Unit
                27 -> style.inverse = false
                28, 29 -> Unit
                in 30..37 -> style.fg = XTERM_256[p - 30]
                38 -> i = sgrExtendedColor(params, i, fg = true)
                39 -> style.fg = COLOR_DEFAULT_FG
                in 40..47 -> style.bg = XTERM_256[p - 40]
                48 -> i = sgrExtendedColor(params, i, fg = false)
                49 -> style.bg = COLOR_DEFAULT_BG
                in 90..97 -> style.fg = XTERM_256[p - 90 + 8]
                in 100..107 -> style.bg = XTERM_256[p - 100 + 8]
                else -> Unit
            }
            i++
        }
    }

    /**
     * SGR 38/48 扩展色：`5;n` = 256 色索引，`2;r;g;b` = 真彩色。
     *
     * @return 消费到的参数下标（循环在此基础上 +1 继续）。
     */
    private fun sgrExtendedColor(params: List<Int>, i: Int, fg: Boolean): Int {
        when (params.getOrNull(i + 1)) {
            5 -> {
                val idx = params.getOrNull(i + 2) ?: 0
                val color = if (idx in XTERM_256.indices) XTERM_256[idx] else COLOR_DEFAULT_FG
                if (fg) style.fg = color else style.bg = color
                return i + 2
            }

            2 -> {
                val r = (params.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                val g = (params.getOrNull(i + 3) ?: 0).coerceIn(0, 255)
                val b = (params.getOrNull(i + 4) ?: 0).coerceIn(0, 255)
                val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                if (fg) style.fg = color else style.bg = color
                return i + 4
            }

            else -> return i  // 畸形扩展色：跳过，不吃掉后续参数
        }
    }

    /**
     * DECSET / DECRST 私有模式（CSI ? Pm h / l）。
     * 只实现 1049（备用屏幕，vim/htop 靠它）；其余（光标可见性 25、鼠标追踪 1000 系列等）
     * 一律忽略——终端 Tab 场景用不到，忽略是规范允许的安全行为。
     */
    private fun setModes(params: List<Int>, on: Boolean) {
        for (p in params) {
            if (p != 1049) continue
            if (on) enterAltScreen() else leaveAltScreen()
        }
    }

    private fun enterAltScreen() {
        if (altScreen != null) return
        saveCursor()
        altScreen = screen
        screen = ArrayList<TerminalRow>(rows).also { fillBlank(it, rows, columns) }
        curRow = 0
        curCol = 0
        wrapPending = false
    }

    private fun leaveAltScreen() {
        val saved = altScreen ?: return
        screen = saved
        altScreen = null
        restoreCursor()
    }

    // —————————————————————————— 制表位 ——————————————————————————

    private fun nextTabStop(col: Int): Int {
        for (c in col + 1 until columns) if (tabStops[c]) return c
        return columns - 1
    }

    private fun prevTabStop(col: Int): Int {
        for (c in col - 1 downTo 0) if (tabStops[c]) return c
        return 0
    }

    /** TBC：0 = 清光标处制表位，3 = 全清；其余值忽略。 */
    private fun clearTabStop(mode: Int) {
        when (mode) {
            0 -> tabStops[curCol.coerceIn(tabStops.indices)] = false
            3 -> java.util.Arrays.fill(tabStops, false)
        }
    }

    // —————————————————————————— 屏幕行操作（写时复制） ——————————————————————————

    private fun putCell(r: Int, c: Int, cell: CharCell) {
        val cells = screen[r].cells.copyOf()
        cells[c] = cell
        replaceRow(r, cells)
    }

    private fun replaceRow(r: Int, cells: Array<CharCell>) {
        val old = screen[r]
        screen[r] = TerminalRow(cells, old.continued)
    }

    private fun blankCells(): Array<CharCell> = Array(columns) { style.toCell(" ") }

    private fun fillBlank(target: MutableList<TerminalRow>, rws: Int, cols: Int) {
        target.clear()
        repeat(rws) { target.add(TerminalRow.blank(cols)) }
    }

    /** 尺寸重整：保留最新 rws 行、逐行截断 / 补足到 cols（滚动历史随之让位）。 */
    private fun refit(rowsList: MutableList<TerminalRow>, cols: Int, rws: Int): MutableList<TerminalRow> {
        val out = ArrayList<TerminalRow>(rws)
        val from = (rowsList.size - rws).coerceAtLeast(0)
        for (i in from until rowsList.size) {
            val row = rowsList[i]
            val cells = if (row.cells.size == cols) row.cells
            else Array(cols) { if (it < row.cells.size) row.cells[it] else CharCell() }
            out.add(TerminalRow(cells, row.continued))
        }
        while (out.size < rws) out.add(TerminalRow.blank(cols))
        return out
    }

    // —————————————————————————— 码点转字符串 ——————————————————————————

    private fun cpToString(cp: Int): String =
        if (cp < 0x10000) String(charArrayOf(cp.toChar()))
        else String(Character.toChars(cp))

    private fun printCodepointString(s: String) {
        if (s.isEmpty()) return
        // REP 走完整打印路径：宽度、换行、属性都与正常打印一致
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            printCodepoint(cp)
            i += Character.charCount(cp)
        }
    }

    companion object {
        /** 滚动历史上限：2000 行（约几屏命令的量，内存约数百 KB 封顶）。 */
        const val MAX_SCROLLBACK = 2000

        /** CSI 参数字符串长度上限（防恶意超长序列撑爆内存）。 */
        private const val MAX_CSI_PARAMS = 64

        /**
         * REP(b) 单次重复上限：防 `ESC[999999b` 类畸形序列把读线程拖住
         * （正常用法是重复几个到几十个字符，1024 远超所需）。
         */
        private const val MAX_REPEAT = 1024

        // ———————————————————— wcwidth（Markus Kuhn 公开域算法） ————————————————————

        /** 组合字符区间表（Kuhn 的 combining 表，节选 shell/中文场景高频段）。 */
        private val COMBINING: Array<IntRange> = arrayOf(
            0x0300..0x036F, 0x0483..0x0489, 0x0591..0x05BD, 0x05BF..0x05C7,
            0x0610..0x061A, 0x064B..0x065F, 0x0670..0x0670, 0x06D6..0x06DC,
            0x06DE..0x06E4, 0x06E7..0x06ED, 0x0711..0x0711, 0x0730..0x074A,
            0x07A6..0x07B0, 0x0901..0x0903, 0x093A..0x094F, 0x0951..0x0957,
            0x0962..0x0963, 0x0981..0x0983, 0x09BC..0x09CD, 0x09D7..0x09D7,
            0x09E2..0x09E3, 0x0A01..0x0A03, 0x0A3C..0x0A51, 0x0A70..0x0A71,
            0x0A81..0x0A83, 0x0ABC..0x0ACD, 0x0AE2..0x0AE3, 0x0B01..0x0B03,
            0x0B3C..0x0B57, 0x0B82..0x0B82, 0x0BBE..0x0BCD, 0x0BD7..0x0BD7,
            0x0C00..0x0C04, 0x0C3E..0x0C56, 0x0C62..0x0C63, 0x0C81..0x0C83,
            0x0CBC..0x0CD6, 0x0CE2..0x0CE3, 0x0D00..0x0D03, 0x0D3B..0x0D57,
            0x0D82..0x0D83, 0x0DCA..0x0DDF, 0x0DF2..0x0DF3, 0x0E31..0x0E3A,
            0x0E47..0x0E4E, 0x0EB1..0x0EBC, 0x0EC8..0x0ECD, 0x0F18..0x0F19,
            0x0F35..0x0F39, 0x0F71..0x0F84, 0x0F86..0x0F87, 0x0F90..0x0FBC,
            0x0FC6..0x0FC6, 0x102C..0x103F, 0x1056..0x105F, 0x1071..0x1074,
            0x1082..0x108D, 0x109D..0x109D, 0x135D..0x135F, 0x1712..0x1714,
            0x1732..0x1734, 0x1752..0x1753, 0x1772..0x1773, 0x17B4..0x17D3,
            0x17DD..0x17DD, 0x180B..0x180D, 0x18A9..0x18A9, 0x1920..0x193B,
            0x1946..0x196D, 0x19B0..0x19C0, 0x19C8..0x19C9, 0x1A17..0x1A1B,
            0x1B00..0x1B04, 0x1B34..0x1B44, 0x1B6B..0x1B73, 0x1BAB..0x1BAD,
            0x1BE6..0x1BE6, 0x1C2C..0x1C33, 0x1CD0..0x1CD2, 0x1CD4..0x1CE0,
            0x1CE2..0x1CE8, 0x1DC0..0x1DE6, 0x1DFE..0x1DFF, 0x20D0..0x20F0,
            0x2CEF..0x2CF1, 0x2D7F..0x2D7F, 0x2DE0..0x2DFF, 0xA66F..0xA672,
            0xA674..0xA67D, 0xA69F..0xA69F, 0xA6F0..0xA6F1, 0xA802..0xA802,
            0xA806..0xA806, 0xA80B..0xA80B, 0xA825..0xA826, 0xA8C4..0xA8C5,
            0xA8E0..0xA8F1, 0xA926..0xA92D, 0xA947..0xA953, 0xA980..0xA983,
            0xA9B3..0xA9C0, 0xAA29..0xAA36, 0xAA43..0xAA43, 0xAA4C..0xAA4D,
            0xAAB0..0xAAB0, 0xAAB2..0xAAB4, 0xAAB7..0xAAB8, 0xAABE..0xAABF,
            0xAAC1..0xAAC1, 0xABE3..0xABEA, 0xABEC..0xABED, 0xFB1E..0xFB1E,
            0xFE00..0xFE0F, 0xFE20..0xFE26, 0xFEFF..0xFEFF, 0xFFF9..0xFFFB,
            0x101FD..0x101FD, 0x10A01..0x10A0F, 0x10A38..0x10A3F, 0x110B9..0x110BA,
            0x1D165..0x1D169, 0x1D16D..0x1D182, 0x1D185..0x1D18B, 0x1D1AA..0x1D1AD,
            0x1D242..0x1D244, 0xE0100..0xE01EF
        )

        /**
         * 字符显示宽度（Markus Kuhn 公开域 wcwidth 的 Kotlin 重写）。
         * 组合字符 0 列，CJK / 全角 2 列，其余 1 列；控制字符返回 -1（调用方已提前分流）。
         */
        fun wcwidth(cp: Int): Int {
            if (cp == 0) return 0
            if (cp < 32 || cp in 0x7F..0x9F) return -1
            if (isCombining(cp)) return 0
            return if (isWide(cp)) 2 else 1
        }

        private fun isCombining(cp: Int): Boolean {
            // COMBINING 按起点有序，线性扫描足够（区间 <120 个，打印路径热点可控）
            for (r in COMBINING) if (cp in r) return true
            return false
        }

        /** 宽字符区间（Kuhn 的 wide 表）：CJK 表意文字 / 假名 / 全角形式 / emoji 区。 */
        private fun isWide(cp: Int): Boolean = when {
            cp in 0x1100..0x115F -> true   // Hangul Jamo 初声
            cp == 0x2329 || cp == 0x232A -> true  // 左右尖括号
            cp in 0x2E80..0xA4CF && cp != 0x303F -> true  // CJK … Yi 扩展（排除 0x303F）
            cp in 0xAC00..0xD7A3 -> true   // Hangul 音节
            cp in 0xF900..0xFAFF -> true   // CJK 兼容表意文字
            cp in 0xFE10..0xFE19 -> true   // 竖排形式
            cp in 0xFE30..0xFE6F -> true   // CJK 兼容形式
            cp in 0xFF00..0xFF60 -> true   // 全角形式
            cp in 0xFFE0..0xFFE6 -> true   // 全角符号
            cp in 0x1F300..0x1F64F -> true // emoji 表情符号
            cp in 0x1F900..0x1F9FF -> true // emoji 补充符号
            cp in 0x20000..0x2FFFD -> true // CJK 扩展 B+
            cp in 0x30000..0x3FFFD -> true // CJK 扩展 G+
            else -> false
        }
    }
}
