package com.beichen.polaris.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 伪树（P1-2）。
 *
 * ## 为什么这里必须钉死
 *
 * 伪树错了是**静默的**：模型会照着一堆坐标去点，每次都能「点成功」（手势注入从不失败），
 * 只是点错了地方。用户看到的是「它乱点」，日志里一切正常。所以几个最容易算错的地方
 * 必须用纯函数断言锁住：
 *
 *  1. 图片坐标 → 屏幕坐标的换算（降采样截图的坑）；
 *  2. 纯符号行、碎屑框必须被丢掉（否则条目数被垃圾占满，真文字反而被截掉）；
 *  3. 重复识别的同一段字只留一份；
 *  4. 条目数上限（否则伪树自己变成新的上下文炸弹）。
 */
class OcrPseudoTreeTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrPseudoTree.Line(text, l, t, r, b)

    /** 1080x2400 截图、1080x2400 屏幕：1:1，坐标应当原样保留。 */
    private fun sameSize(lines: List<OcrPseudoTree.Line>, max: Int = 60) =
        OcrPseudoTree.build(lines, 1080, 2400, 1080, 2400, max)

    private fun nodes(p: OcrPseudoTree.Pseudo): List<JsonObject> =
        (Json.parseToJsonElement(p.json) as JsonArray).map { it as JsonObject }

    @Test
    fun `坐标 1 比 1 时原样保留`() {
        val p = sameSize(listOf(line("立即购买", 100, 200, 400, 260)))!!
        val n = nodes(p).single()
        assertEquals("立即购买", n["text"]!!.jsonPrimitive.content)
        assertEquals("100,200,400,260", n["bounds"]!!.jsonPrimitive.content)
    }

    /**
     * 截图被降采样时（1440x3200 屏幕截出 720x1600），坐标必须**放大**。
     *
     * 不换算的后果：模型点在屏幕左上四分之一处，看着像「点了但没反应」，
     * 于是重试、换策略、最后归结为「这个 App 点不动」。
     */
    @Test
    fun `降采样截图必须换回屏幕坐标`() {
        val p = OcrPseudoTree.build(
            listOf(line("确认", 100, 200, 300, 260)),
            imageWidth = 720, imageHeight = 1600,
            screenWidth = 1440, screenHeight = 3200
        )!!
        val n = nodes(p).single()
        assertEquals("200,400,600,520", n["bounds"]!!.jsonPrimitive.content)
    }

    @Test
    fun `纯符号行不进树`() {
        val p = sameSize(
            listOf(
                line("·", 10, 100, 20, 160),
                line("—", 30, 100, 90, 160),
                line("我的", 200, 100, 300, 160)
            )
        )!!
        val texts = nodes(p).map { it["text"]!!.jsonPrimitive.content }
        assertEquals(listOf("我的"), texts)
    }

    @Test
    fun `太矮的框当碎屑丢掉`() {
        // 5px 高：图片里连一行字都放不下，只可能是分割线或笔画碎屑
        val p = sameSize(
            listOf(
                line("分割线", 100, 100, 400, 105),
                line("设置", 100, 200, 400, 260)
            )
        )!!
        assertEquals(listOf("设置"), nodes(p).map { it["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun `被大框包住的重复识别只留一份`() {
        val p = sameSize(
            listOf(
                line("立即购买", 100, 500, 400, 560),
                line("购买", 250, 505, 340, 555)   // ML Kit 额外给出的词级框
            )
        )!!
        val texts = nodes(p).map { it["text"]!!.jsonPrimitive.content }
        assertEquals(listOf("立即购买"), texts)
    }

    @Test
    fun `只重叠但文字不同时不合并`() {
        // 上下紧邻的两行，框小幅交叠——合并会直接吃掉一个可点目标
        val p = sameSize(
            listOf(
                line("第一行文字", 100, 500, 400, 560),
                line("第二行", 100, 555, 400, 615)
            )
        )!!
        assertEquals(2, nodes(p).size)
    }

    @Test
    fun `按从上到下从左到右排序`() {
        val p = sameSize(
            listOf(
                line("右下", 600, 800, 900, 860),
                line("左上", 100, 200, 400, 260),
                line("右上", 600, 200, 900, 260),
                line("左下", 100, 800, 400, 860)
            )
        )!!
        val texts = nodes(p).map { it["text"]!!.jsonPrimitive.content }
        assertEquals(listOf("左上", "右上", "左下", "右下"), texts)
    }

    @Test
    fun `超出上限时截断并报出丢掉多少条`() {
        // 80 行全在 2400 高以内：行距 28、框高 20，不会因为越界被裁掉干扰计数
        val many = (0 until 80).map { i -> line("第${i}行", 100, i * 28, 400, i * 28 + 20) }
        val p = OcrPseudoTree.build(many, 1080, 2400, 1080, 2400, maxNodes = 60)!!
        assertEquals(60, p.kept)
        assertEquals(20, p.dropped)
    }

    @Test
    fun `超长文本被截断`() {
        val long = "很长的正文".repeat(40)   // 200 字
        val p = sameSize(listOf(line(long, 100, 200, 1000, 260)))!!
        val text = nodes(p).single()["text"]!!.jsonPrimitive.content
        assertEquals(OcrPseudoTree.MAX_TEXT + 1, text.length)   // +1 是省略号
        assertTrue("必须以省略号收尾", text.endsWith("…"))
    }

    @Test
    fun `越界的框裁进屏幕内`() {
        val p = OcrPseudoTree.build(
            listOf(line("贴着右边", 900, 200, 1200, 260)),
            imageWidth = 1080, imageHeight = 2400,
            screenWidth = 1080, screenHeight = 2400
        )!!
        val bounds = nodes(p).single()["bounds"]!!.jsonPrimitive.content.split(",").map { it.toInt() }
        assertEquals(1080, bounds[2])   // right 被裁到屏宽
        assertTrue(bounds[2] > bounds[0])
    }

    @Test
    fun `完全在屏幕外的框丢掉`() {
        // 框整个落在右边之外：裁完宽度为负，点不到任何真实内容
        val p = OcrPseudoTree.build(
            listOf(line("看不见", 1200, 200, 1400, 260)),
            imageWidth = 1080, imageHeight = 2400,
            screenWidth = 1080, screenHeight = 2400
        )
        assertNull(p)
    }

    @Test
    fun `没有任何可用文字时返回 null 而不是空数组`() {
        assertNull(sameSize(listOf(line("·", 10, 100, 20, 160))))
        assertNull(sameSize(emptyList()))
    }

    @Test
    fun `尺寸非法时返回 null`() {
        assertNull(OcrPseudoTree.build(listOf(line("a", 0, 0, 10, 20)), 0, 100, 100, 100))
        assertNull(OcrPseudoTree.build(listOf(line("a", 0, 0, 10, 20)), 100, 100, 0, 100))
    }

    @Test
    fun `伪树必须自称是 ocr 且不带假 id`() {
        val p = sameSize(listOf(line("确定", 100, 200, 400, 260)))!!
        val n = nodes(p).single()
        assertEquals("ocr", n["src"]!!.jsonPrimitive.content)
        assertEquals("", n["id"]!!.jsonPrimitive.content)
        // clickable 是推测值：有字就假定可点。诚实性靠 src 字段 + 文案交代，不靠这个字段。
        assertTrue(n["clickable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `单字符数字仍然保留`() {
        // 「1」是未读数角标，是最常见的可点目标之一，不能因为短就丢
        val p = sameSize(listOf(line("1", 900, 200, 940, 260)))!!
        assertEquals(listOf("1"), nodes(p).map { it["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun `换行与连续空格被压成单个空格`() {
        val p = sameSize(listOf(line("立即\n购买", 100, 200, 400, 300)))!!
        assertEquals("立即 购买", nodes(p).single()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `同一行两条不相交的文字都保留`() {
        // 「字数 100」+「字节 2.1M」这类并排标签，框不相交，不该被当成重复
        val p = sameSize(
            listOf(
                line("字数", 100, 200, 200, 260),
                line("100", 210, 200, 320, 260)
            )
        )!!
        assertEquals(listOf("字数", "100"), nodes(p).map { it["text"]!!.jsonPrimitive.content })
    }
}
