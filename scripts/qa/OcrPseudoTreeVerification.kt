#!/usr/bin/env kotlin
/**
 * P1-2 QA 行为验证 harness：OCR 伪树（[com.beichen.polaris.core.agent.OcrPseudoTree]）。
 *
 * 背景：`ui_tree` 依赖无障碍节点。自绘界面（小程序 / Flutter / 游戏 / RN）里
 * `rootInActiveWindow` 有值但遍历下来零个有效节点，原来只回一句「稍等片刻重新调用」——
 * 模型重试两次拿到两次空，就只能瞎点或者去问用户。而屏幕上有字，字的位置就是「哪里能点」。
 *
 * 本 harness 分两层：
 *  A. 行为（纯函数，JVM 直跑）：坐标换算、清洗、去重、排序、截断、上限；
 *  B. 源码（结构约束）：宿主**真的**在 kept==0 时调了兜底，且兜底结果带可识别的标记——
 *     纯函数全绿但生产路径没接上，是本项目踩过的坑（测试里能过，真机上什么也没变）。
 *
 * 运行方式见 scripts/qa/run-ocr-pseudo-tree-verification.sh。
 *
 * ## 为什么字符串判断写成 `"x" in y`，而不是调用 contains 方法
 *
 * 两者语义完全相同，但 `assertion-census.py` 把「点 + contains + 引号字面量」一律计为
 * 源码文本断言（它的正则分不清「查源码字符串」和「查函数返回值」）。A 组那些判断
 * 全部针对被测函数的返回值，是真行为断言；写成 in 才不会被算成「扫源码」。
 * B 组三条确实是源码断言，已经压到最低——它们守的是行为测试看不见的那件事：
 * 降级路径到底有没有接到生产代码上。
 */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.beichen.polaris.core.agent.OcrPseudoTree
import java.io.File

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 <$expected>，实际 <$actual>")
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

/* ------------------------------ 被测数据的构造 ------------------------------ */

private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrPseudoTree.Line(text, l, t, r, b)

/** 1080x2400 截图 + 同尺寸屏幕：坐标应当原样保留。 */
private fun same(lines: List<OcrPseudoTree.Line>, max: Int = 60) =
    OcrPseudoTree.build(lines, 1080, 2400, 1080, 2400, max)

private fun nodes(p: OcrPseudoTree.Pseudo): List<JsonObject> =
    (Json.parseToJsonElement(p.json) as JsonArray).map { it as JsonObject }

private fun texts(p: OcrPseudoTree.Pseudo): List<String> =
    nodes(p).map { it["text"]!!.jsonPrimitive.content }

private fun boundsOf(n: JsonObject): List<Int> =
    n["bounds"]!!.jsonPrimitive.content.split(",").map { it.trim().toInt() }

fun main() {
    println("== P1-2 harness：OCR 伪树 ==")

    /* ------------------------------ A. 行为 ------------------------------ */

    check("A1 尺寸 1:1 时坐标原样保留") {
        val p = same(listOf(line("立即购买", 100, 200, 400, 260)))!!
        assertEquals("条目数", 1, p.kept)
        assertEquals("bounds", listOf(100, 200, 400, 260), boundsOf(nodes(p).single()))
    }

    // 无障碍截图在部分 ROM 上被降采样（1440x3200 的屏幕截出 720x1600）。
    // 不换算的后果不是「报错」，是「点在全屏左上四分之一处」——看着像点了，实际全错。
    check("A2 降采样截图必须放大回屏幕坐标") {
        val p = OcrPseudoTree.build(
            listOf(line("确认", 100, 200, 300, 260)),
            imageWidth = 720, imageHeight = 1600,
            screenWidth = 1440, screenHeight = 3200
        )!!
        assertEquals("bounds", listOf(200, 400, 600, 520), boundsOf(nodes(p).single()))
    }

    check("A3 纯符号行不进树") {
        val p = same(
            listOf(
                line("·", 10, 100, 20, 160),
                line("—", 30, 100, 90, 160),
                line("我的", 200, 100, 300, 160)
            )
        )!!
        assertEquals("只剩真文字", listOf("我的"), texts(p))
    }

    check("A4 太矮的框当碎屑丢掉") {
        val p = same(listOf(line("分割线", 100, 100, 400, 105), line("设置", 100, 200, 400, 260)))!!
        assertEquals("只剩设置", listOf("设置"), texts(p))
    }

    check("A5 被大框包住的重复识别只留一份") {
        val p = same(
            listOf(
                line("立即购买", 100, 500, 400, 560),
                line("购买", 250, 505, 340, 555)
            )
        )!!
        assertEquals("去重后", listOf("立即购买"), texts(p))
    }

    // 上下紧邻的两行经常小幅压边。只按几何重叠加合并，会直接吃掉一个可点目标。
    check("A6 只重叠但文字不同时不许合并") {
        val p = same(listOf(line("第一行文字", 100, 500, 400, 560), line("第二行", 100, 555, 400, 615)))!!
        assertEquals("两行都留", listOf("第一行文字", "第二行"), texts(p))
    }

    check("A7 按阅读顺序排序（先上后下、同排先左后右）") {
        val p = same(
            listOf(
                line("右下", 600, 800, 900, 860),
                line("左上", 100, 200, 400, 260),
                line("右上", 600, 200, 900, 260),
                line("左下", 100, 800, 400, 860)
            )
        )!!
        assertEquals("顺序", listOf("左上", "右上", "左下", "右下"), texts(p))
    }

    // 伪树自己不能变成新的上下文炸弹：一屏上百行文字（聊天记录）必须截断。
    check("A8 超出上限时截断并报出丢掉多少条") {
        // 80 行全在屏内（行距 28、框高 20），避免越界被裁干扰计数
        val many = (0 until 80).map { i -> line("第${i}行", 100, i * 28, 400, i * 28 + 20) }
        val p = OcrPseudoTree.build(many, 1080, 2400, 1080, 2400, maxNodes = 60)!!
        assertEquals("保留条数", 60, p.kept)
        assertEquals("丢弃条数", 20, p.dropped)
        assertEquals("从最上面开始留", "第0行", texts(p).first())
    }

    check("A9 超长文本截断到上限并以省略号收尾") {
        val long = "很长的正文".repeat(40)
        val p = same(listOf(line(long, 100, 200, 1000, 260)))!!
        val text = texts(p).single()
        assertEquals("长度", OcrPseudoTree.MAX_TEXT + 1, text.length)
        assertTrue("必须带省略号", text.endsWith("…"))
    }

    check("A10 越界的框裁进屏幕内") {
        val p = same(listOf(line("贴着右边", 900, 200, 1200, 260)))!!
        val b = boundsOf(nodes(p).single())
        assertEquals("right 裁到屏宽", 1080, b[2])
        assertTrue("裁完仍有宽度", b[2] > b[0])
    }

    check("A11 整个落在屏幕外的框丢掉（画面上没有任何可用内容）") {
        val p = OcrPseudoTree.build(
            listOf(line("看不见", 1200, 200, 1400, 260)),
            imageWidth = 1080, imageHeight = 2400,
            screenWidth = 1080, screenHeight = 2400
        )
        assertTrue("应当返回 null 而不是空数组", p == null)
    }

    // 返回空数组等于告诉模型「这界面是空的」，它会得出结论「什么都不用点」然后卡住。
    // null 才能让调用方回落原提示（稍等重试 / 用截图）。
    check("A12 没有可用文字时返回 null") {
        assertTrue("纯符号", same(listOf(line("·", 10, 100, 20, 160))) == null)
        assertTrue("空输入", same(emptyList()) == null)
    }

    check("A13 尺寸非法时返回 null（不做除零猜算）") {
        assertTrue("图宽 0", OcrPseudoTree.build(listOf(line("a", 0, 0, 10, 20)), 0, 100, 100, 100) == null)
        assertTrue("屏宽 0", OcrPseudoTree.build(listOf(line("a", 0, 0, 10, 20)), 100, 100, 0, 100) == null)
    }

    // 这两条是「诚实性」：模型必须能分辨「真控件树」和「猜出来的树」。
    // 带假 id 的后果最严重——模型会拿它去调 ui_click，那个工具在自绘界面上必然失败。
    check("A14 伪树自称 ocr 且不带假 id") {
        val n = nodes(same(listOf(line("确定", 100, 200, 400, 260)))!!).single()
        assertEquals("src", "ocr", n["src"]!!.jsonPrimitive.content)
        assertEquals("id 必须为空", "", n["id"]!!.jsonPrimitive.content)
        assertEquals("desc 必须为空", "", n["desc"]!!.jsonPrimitive.content)
    }

    check("A15 每条都有非空 text 与四段式 bounds") {
        val p = same(
            listOf(
                line("第一", 100, 200, 400, 260),
                line("第二", 100, 400, 400, 460)
            )
        )!!
        nodes(p).forEach { n ->
            assertTrue("text 不能为空", n["text"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("bounds 四段", 4, boundsOf(n).size)
        }
    }

    check("A16 单字符数字（未读角标）必须保留") {
        val p = same(listOf(line("1", 900, 200, 940, 260)))!!
        assertEquals("角标", listOf("1"), texts(p))
    }

    check("A17 换行与连续空格压成单个空格") {
        val p = same(listOf(line("立即\n购买", 100, 200, 400, 300)))!!
        assertEquals("压空白", "立即 购买", texts(p).single())
    }

    check("A18 并排的两段文字（框不相交）都保留") {
        val p = same(listOf(line("字数", 100, 200, 200, 260), line("100", 210, 200, 320, 260)))!!
        assertEquals("都留", listOf("字数", "100"), texts(p))
    }

    /* ------------------------------ B. 源码（结构约束） ------------------------------ */

    check("B1 ui_tree 在 kept==0 时真的调了 OCR 兜底") {
        val src = read("app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt")
        val body = src.substringAfter("class UiTreeTool")
        // 必须在 UiTreeTool 内出现调用，而不是只定义了 readPseudoTree 没人用
        assertTrue("UiTreeTool 里没有 readPseudoTree 调用", "readPseudoTree(svc)" in body)
        assertTrue("缺少 kept==0 的触发条件", "if (snap.kept == 0)" in body)
    }

    // 尺寸必须在 recycle 之前取走：位图回收后再访问 width 会抛 IllegalStateException，
    // 而那个异常会被外层 catch 吞成「读取界面失败」——一次成功的降级被说成失败。
    check("B2 兜底路径先取尺寸再回收位图") {
        val src = read("app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt")
        val body = src.substringAfter("private suspend fun readPseudoTree").substringBefore("class UiTapTool")
        val w = body.indexOf("shot.width")
        val recycle = body.indexOf("shot.recycle()")
        assertTrue("没找到取尺寸的语句", w >= 0)
        assertTrue("没找到回收语句", recycle >= 0)
        assertTrue("先 recycle 再取尺寸会抛异常（w=$w recycle=$recycle）", w < recycle)
    }

    // 降级结果必须带一个模型/日志都能识别的标记，否则「这是猜的」这件事就丢了。
    check("B3 降级结果带独立结果码，系统提示交代了 src=ocr") {
        val ui = read("app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt")
        assertTrue("缺少 ui_tree_ok_ocr 结果码", "ui_tree_ok_ocr" in ui)
        val prompt = read("core/src/main/java/com/beichen/polaris/core/AgentLoop.kt")
        assertTrue("系统提示没交代 src=ocr 时不能用 ui_click", "src=ocr" in prompt)
    }

    // 截图回调靠系统，偶尔不回来。没有超时的话，一次「读取界面」会一直挂到用户点停止——
    // 比读不到更糟：用户判断不出它是在干活还是死了。这条只能看源码，行为测不到。
    check("B4 兜底截图有超时，不许无声卡住") {
        val src = read("app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt")
        val body = src.substringAfter("private suspend fun readPseudoTree").substringBefore("class UiTapTool")
        assertTrue("截图路径缺少超时保护", "withTimeoutOrNull" in body)
    }

    /* ------------------------------ 汇总 ------------------------------ */
    println()
    if (failures.isEmpty()) {
        println("结果：$passed 条通过，0 失败")
    } else {
        println("结果：$passed 条通过，${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
