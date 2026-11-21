package com.beichen.polaris.core.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P1-2：界面结构读不到时，用「画面上的文字 + 坐标」拼一棵够用的伪树。
 *
 * ## 要解决的是什么
 *
 * `ui_tree` 依赖无障碍节点。自绘界面（Flutter / 游戏 / 小程序 WebView / 部分 ROM 的
 * 隐私页面）里 `rootInActiveWindow` 是有值的，但遍历下来**一个有效节点都没有**，
 * 于是 `kept == 0`。原来这里只回一句「稍等片刻重新调用」——模型重试两次、
 * 拿到两次空，就只能瞎点或者去问用户。
 *
 * 而屏幕上有字。文字的位置本身就能告诉模型「哪里能点」。
 *
 * ## 为什么必须是「同构的树」而不是一段纯文本
 *
 * `ocr_image` 早就存在，但它返回的是 `result.text`——**没有坐标**。对「读出验证码」
 * 够用，对「点那个『立即购买』」完全没用：模型知道有这四个字，不知道在哪。
 * 所以这里的产出刻意与真树字段完全一致（text/desc/id/cls/bounds/clickable/...），
 * 模型不需要学第二种格式，`ui_tap` 也不需要第二套坐标语义。
 *
 * ## 三个刻意的取舍
 *
 * 1. **`clickable` 恒为 true**（真树里是实测值）。OCR 只能证明「这里有字」，
 *    证不出「这里收点击」。但把这个字段诚实填成 false 的后果是模型看见满屏
 *    `clickable: false` 而不敢动手——卡住比点错一次更贵：点错有动作回执
 *    （`*_no_effect`）会把「界面没变」告诉它，它自然会换目标。
 * 2. **带 `src: "ocr"` 字段**。多一个字段不会让模型崩，但能让它在后续推理里
 *    区分「id 为空是因为真的是自绘」和「id 为空是因为这是猜的」。真树永远没有这个字段。
 * 3. **不合并跨行文字**。按钮文字被折成两行（"立即\n购买"）时，本可以合并成一个框更好点，
 *    但合并规则一旦写错就会把两行聊天记录误并成一条，代价比多一次点击大得多。
 *
 * ## 纯函数
 *
 * 不碰 Android、不碰 ML Kit、不碰文件：OCR 引擎给 [Line]，这里给 JSON 字符串。
 * 坐标换算、去重、排序、截断全部可在 JVM 上单测——这几件事恰好是最容易算错、
 * 又最不容易在真机上被发现的（偏几十像素照样「点了」，只是点错地方）。
 */
object OcrPseudoTree {

    /** OCR 一行的识别结果，坐标是**图片像素**（缩放前）。 */
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /** 产出：[json] 与真树同构的数组；[kept] 实际条目；[dropped] 因超限被截掉的条数。 */
    data class Pseudo(val json: String, val kept: Int, val dropped: Int)

    /** 最多给多少条。手机一屏正常 15~30 行文字，60 条覆盖得住，又不至于把上下文吃穿。 */
    const val MAX_NODES = 60

    /** 单条文字上限。按钮文字很短，长的是聊天/正文——截断后仍足以判断点哪里。 */
    const val MAX_TEXT = 80

    /**
     * 高度小于此值的框一律丢弃（**图片像素**，不是屏幕像素）。
     *
     * 依据：正常文字行高在 1080p 截图上 ≥ 20px，缩放到 720p 也有 13px；
     * 6px 以下多半是图标碎屑、分割线、或笔画被拆出来的单点。
     */
    private const val MIN_BOX_HEIGHT = 6

    /** 同一行内水平重叠超过这个比例，且文本互相包含时，认为是重复识别。 */
    private const val OVERLAP_RATIO = 0.7

    /**
     * @param lines OCR 原始行（图片坐标系）
     * @param imageWidth / [imageHeight] 截图位图尺寸
     * @param screenWidth / [screenHeight] 屏幕坐标尺寸（`ui_tap` 用的那套）
     *
     * 图片与屏幕尺寸**必须分开传**：无障碍截图的位图在部分 ROM 上被降采样
     * （例如 1440x3200 的屏幕截出 720x1600）。直接拿图片坐标去点，会精准地
     * 点在全屏正中间偏上的位置——看起来「点了」，实际全错。
     *
     * @return null 表示「画面里没有可用文字」，调用方应回落原来的提示
     */
    fun build(
        lines: List<Line>,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        maxNodes: Int = MAX_NODES
    ): Pseudo? {
        if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        if (lines.isEmpty()) return null

        val mapped = lines.asSequence()
            .mapNotNull { it.toRow(imageWidth, imageHeight, screenWidth, screenHeight) }
            .toList()
        if (mapped.isEmpty()) return null

        val deduped = dropContained(mapped)
        // 按阅读顺序（先上后下、同一行先左后右）。ML Kit 的 line 级框同一行 top 基本一致，
        // 所以不需要额外的行容差归并；这里若再加容差，反而会把上下两行粘在一起。
        val sorted = deduped.sortedWith(compareBy({ it.top }, { it.left }))
        val limit = maxNodes.coerceAtLeast(1)
        val kept = if (sorted.size > limit) sorted.subList(0, limit) else sorted

        val json = buildJsonArray {
            kept.forEach { row ->
                add(
                    buildJsonObject {
                        put("text", row.text)
                        // desc/id 留空是对的：编不出真值。给假 id 会诱导模型拿它去调 ui_click。
                        put("desc", "")
                        put("id", "")
                        put("cls", "Text")
                        put("bounds", "${row.left},${row.top},${row.right},${row.bottom}")
                        put("clickable", true)
                        put("scrollable", false)
                        put("editable", false)
                        put("checked", false)
                        put("src", "ocr")
                    }
                )
            }
        }.toString()

        return Pseudo(json = json, kept = kept.size, dropped = sorted.size - kept.size)
    }

    /** 一行 → 屏幕坐标的行；不合格（纯符号、太矮、越界）返回 null。 */
    private fun Line.toRow(imgW: Int, imgH: Int, scrW: Int, scrH: Int): Row? {
        if (bottom - top < MIN_BOX_HEIGHT) return null
        val text = cleanText(this.text) ?: return null
        // Long 乘法：屏宽与坐标都是四位数时 Int 也够，但换一块 4K 平板就未必。
        val l = (left.toLong() * scrW / imgW).toInt()
        val t = (top.toLong() * scrH / imgH).toInt()
        val r = (right.toLong() * scrW / imgW).toInt()
        val b = (bottom.toLong() * scrH / imgH).toInt()
        // 越界裁到屏幕内；裁完已经没有面积就丢——那种框点不到任何真实内容。
        val cl = l.coerceIn(0, scrW)
        val ct = t.coerceIn(0, scrH)
        val cr = r.coerceIn(0, scrW)
        val cb = b.coerceIn(0, scrH)
        if (cr - cl < 2 || cb - ct < 2) return null
        return Row(text, cl, ct, cr, cb)
    }

    /**
     * 文本清洗：压空白、丢纯符号、截断。
     *
     * 「纯符号」要丢：OCR 对图标、分隔线、圆角边框经常吐出 `·`、`|`、`—`、`◡`，
     * 这些进了树只会占条目数，还可能被模型当成可点目标。
     * 判定用 `isLetterOrDigit`（中文汉字属于 Letter），所以「1」「确定」「¥」？
     * ——「¥」不算，会丢；但只会以「¥ 9.9」这种形式出现，整行仍留得下。
     */
    private fun cleanText(raw: String): String? {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t.none { it.isLetterOrDigit() }) return null
        return if (t.length > MAX_TEXT) t.take(MAX_TEXT) + "…" else t
    }

    /**
     * 丢掉「被别的框包住、且文字更短」的行。
     *
     * 典型来源：ML Kit 对同一段文字既给出整行、又给出其中的一个词；或者图标边框
     * 与文字框高度重叠。保留大框、丢小框，模型才不会看到两遍同样的字。
     *
     * 只在「重叠 ≥ [OVERLAP_RATIO] 且文本互相包含」时才合并——只靠几何重叠判定太激进：
     * 列表项里两行相邻文字经常上下压边，误合并会直接吃掉一个可点目标。
     */
    private fun dropContained(rows: List<Row>): List<Row> {
        val out = ArrayList<Row>(rows.size)
        for (row in rows) {
            if (out.any { it.swallows(row) }) continue
            out.removeAll { row.swallows(it) }
            out.add(row)
        }
        return out
    }

    private data class Row(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        /** [other] 大部分落在自己框内，且自己的文字包含对方的文字。 */
        fun swallows(other: Row): Boolean {
            if (text.length < other.text.length || !text.contains(other.text)) return false
            val ow = (other.right - other.left).coerceAtLeast(1).toLong()
            val oh = (other.bottom - other.top).coerceAtLeast(1).toLong()
            val iw = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0).toLong()
            val ih = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0).toLong()
            val overlap = iw * ih
            val area = ow * oh
            return overlap * 100 >= area * (OVERLAP_RATIO * 100).toLong()
        }
    }
}
