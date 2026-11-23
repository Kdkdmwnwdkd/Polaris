package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.UserMessage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RequestViewBuilder] 的编排判据（2026-11-23）。
 *
 * ## 为什么要有这一份
 *
 * `ContextBudget` 的三个纯函数各有一份 harness 判据，但**它们怎么连起来**没有。
 * 连法里藏着一条会静默丢数据的规则：
 *
 * > 先 `pruneImages`，再 `fitToBudget`。
 *
 * 反过来的后果不是「多丢一条」——是**按错误的体积判据丢**。旧截图剥掉后每张只剩
 * 约 20 token，先剥图能让 fit 多留下一整步真实结果；先压历史则把 900 token 的图
 * 算进去，白丢一步。真机上表现为「上下文明明没满，它却忘了上一步做了什么」。
 *
 * 另外两条也只有连起来才看得见：
 * - 无超限时必须**原样返回同一个 List 实例**（零分配）——有人「顺手」改成 `toList()`
 *   就是每轮多拷一份历史；
 * - `anchorIndex` 必须真的透传到 `pruneImages`（W-49 修过一次，漏传就退回「首条
 *   用户消息」，多轮会话下保护错对象，本轮输入的三张图会被整条剥掉）。
 */
class RequestViewTest {

    private fun call(id: String) = ToolCall(id, "ui_tree", JsonObject(emptyMap()))

    /** 10 万字符的假 data URL：按字符计 token 会得 2.5 万，正确口径只算 900。 */
    private fun image() = "data:image/jpeg;base64," + "A".repeat(100_000)

    /** 一「步」= assistant(tool_calls) + 工具结果 + 截图回灌，共 3 条。 */
    private fun step(id: String, chars: Int = 6): List<ChatMessage> = listOf(
        AssistantMessage("", listOf(call(id))),
        ToolMessage(id, "ui_tree", JsonObject(emptyMap()), "界".repeat(chars)),
        UserMessage("请看下面由工具返回的画面：", listOf(image()))
    )

    @Test
    fun `V1 没超预算就原样返回同一个实例`() {
        // 零分配不是「优化」，是这条链每轮都跑：多拷一份历史 = 每轮多一次 O(n) 拷贝。
        val messages: List<ChatMessage> = listOf(SystemMessage("规则"), UserMessage("随便聊聊"))
        val view = RequestViewBuilder.build(messages, 24_000, 2, 40)
        assertSame("未超限必须返回入参本身", messages, view.messages)
        assertFalse(view.dropped)
        assertFalse(view.folded)
    }

    @Test
    fun `V2 超预算丢段并标记 dropped`() {
        val messages = listOf(SystemMessage("规则")) + step("s1", 3_000) + step("s2", 3_000) +
            step("s3", 3_000) + UserMessage("本轮任务")
        val view = RequestViewBuilder.build(messages, 3_000, 0, 40)
        assertTrue("应发生丢段", view.dropped)
        assertTrue("丢完必须更短", view.messages.size < messages.size)
    }

    @Test
    fun `V3 先剥图再压历史：旧图再多也不该丢真实结果`() {
        // 构造「图多、字少」的历史：每步约 942 token，其中 900 是那张图。
        // 预算 1000：正确顺序下剥完图只剩约 192 token，一条都不丢；
        // 顺序反了则是 2832 token 对 1000 预算，必然丢掉整整一步。
        // 锚点显式指到最后一条：否则默认锚点是「首条 UserMessage」（此处是 s1 的截图回灌），
        // s1 的图会被保护、总 token 仍逼近预算，就测不出顺序了。
        val messages = listOf(SystemMessage("规则")) + step("s1") + step("s2") + step("s3") +
            UserMessage("本轮任务")
        val view = RequestViewBuilder.build(
            messages, 1_000, keepRecentImages = 0, maxMessages = 40, anchorIndex = messages.lastIndex
        )
        assertEquals("条数不该变（只剥了图）", messages.size, view.messages.size)
        assertFalse("不该被判成丢段", view.dropped)
        assertTrue(
            "所有图都应被剥掉",
            view.messages.none { it is UserMessage && it.images.isNotEmpty() }
        )
    }

    @Test
    fun `V4 anchorIndex 真的透传：本轮输入的图不许剥`() {
        // W-49 的回归。旧实现锚点取「首条 UserMessage」，多轮会话里那是历史最老的一条，
        // 本轮输入反而成了「最老的可裁项」——用户贴的图当场消失。
        val messages = listOf(
            SystemMessage("规则"),
            UserMessage("第一张旧图", listOf(image())),
            UserMessage("第二张旧图", listOf(image())),
            UserMessage("本轮输入的图", listOf(image()))
        )
        val out = RequestViewBuilder.build(
            messages, 24_000, keepRecentImages = 1, maxMessages = 40, anchorIndex = 3
        ).messages
        assertTrue("本轮输入的图必须还在", (out[3] as UserMessage).images.isNotEmpty())
        assertTrue("最早那张该被剥", (out[1] as UserMessage).images.isEmpty())
        assertTrue("keepLast=1 保留最近一张旧图", (out[2] as UserMessage).images.isNotEmpty())
    }

    @Test
    fun `V5 折叠发生时会留下可识别的摘要条`() {
        val messages = listOf(SystemMessage("规则")) + (1..6).map { step("s$it", 400) }.flatten() +
            UserMessage("本轮任务")
        val view = RequestViewBuilder.build(messages, 3_000, keepRecentImages = 0, maxMessages = 40)
        assertTrue("应丢段", view.dropped)
        if (view.folded) {
            val note = view.messages.firstOrNull { it is UserMessage && it.content.startsWith(ContextBudget.FOLD_MARK) }
            assertTrue("折叠标记与 folded 必须一致：有标记才算折叠", note != null)
        } else {
            // 空间不够写摘要时允许退化成纯丢弃——但要能解释为什么。
            assertTrue(
                "没折叠就必须一条折叠标记都没有",
                view.messages.none { it is UserMessage && it.content.startsWith(ContextBudget.FOLD_MARK) }
            )
        }
    }

    @Test
    fun `V6 条数上限单独生效`() {
        val messages = listOf(SystemMessage("规则")) + (1..5).map { UserMessage("第 $it 条") }
        val view = RequestViewBuilder.build(messages, 24_000, keepRecentImages = 2, maxMessages = 3)
        assertEquals(3, view.messages.size)
        assertTrue("任务锚点（首条用户消息）必须留", "第 1 条" in (view.messages[1] as UserMessage).content)
        assertTrue("最后一条必须留", "第 5 条" in (view.messages.last() as UserMessage).content)
    }

    @Test
    fun `V7 空历史直接返回`() {
        val view = RequestViewBuilder.build(emptyList(), 1_000, 2, 40)
        assertTrue(view.messages.isEmpty())
        assertFalse(view.dropped)
        assertFalse(view.folded)
    }
}
