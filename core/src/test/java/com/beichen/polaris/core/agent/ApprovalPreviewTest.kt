package com.beichen.polaris.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批处理审批摘要（2026-11-21）。
 *
 * ## 为什么这个纯函数值得有测试
 *
 * `ui_batch` 把 5 个动作合成一次调用，随之把「5 次审批」变成「1 次审批」。
 * 这次审批能不能起作用，全看卡片上写的是不是人话：
 *
 * - 写人话 → 用户看懂「点搜索框 → 输入天气 → 按回车」，一眼判断该不该批；
 * - 写 JSON → 用户看不懂，直接点「允许」，**这次审批等于没有**。
 *
 * 所以这里钉两件事：摘要必须是可读的，且**非批处理的工具一个字都不许变**。
 */
class ApprovalPreviewTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `批处理摘要把三个动作翻成人话`() {
        val args = obj(
            """{"steps":[
                 {"action":"tap","x":540,"y":320},
                 {"action":"input","text":"天气"},
                 {"action":"key","key":"enter"}
               ]}"""
        )
        val text = ApprovalPreview.of("ui_batch", args)
        assertTrue("应说明动作数量", text.contains("3 个界面动作"))
        assertTrue("点坐标要能读出来", text.contains("点 (540, 320)"))
        assertTrue("输入内容要能读出来", text.contains("输入「天气」"))
        assertTrue("按键要能读出来", text.contains("按 enter 键"))
    }

    @Test
    fun `长文本会被截短，不把卡片撑爆`() {
        val long = "很".repeat(200)
        val args = obj("""{"steps":[{"action":"input","text":"$long"}]}""")
        val text = ApprovalPreview.of("ui_batch", args)
        assertTrue("输入内容截到 60 字以内", text.length < 200)
    }

    @Test
    fun `不是 steps 结构时返回 null，回退原始 JSON`() {
        assertNull(ApprovalPreview.batchSummary(obj("""{"command":"ls"}""")))
        assertNull(ApprovalPreview.batchSummary(obj("""{"steps":[]}""")))
    }

    @Test
    fun `未知动作原样显示，不编造描述`() {
        val args = obj("""{"steps":[{"action":"teleport"}]}""")
        val text = ApprovalPreview.of("ui_batch", args)
        assertTrue(text.contains("teleport"))
    }

    @Test
    fun `非批处理工具走原路径，逐字节不变`() {
        val args = obj("""{"x":1,"y":2}""")
        // ui_tap 不是批处理工具：预览应等于参数 JSON 原文
        assertEquals(args.toString(), ApprovalPreview.of("ui_tap", args))
    }
}
