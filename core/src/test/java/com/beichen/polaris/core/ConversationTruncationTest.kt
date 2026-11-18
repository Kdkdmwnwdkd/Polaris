package com.beichen.polaris.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 落盘截断标记的正确性（2026-11-18）。
 *
 * ## 为什么需要这组断言
 *
 * 正文有 4000 字符的落盘上限，截断本身是刻意取舍（会话文件不能无限长）。
 * 危险的是**静默**截断：会话内 UI 看到全文，重启后读到残文，用户以为「模型话说一半」，
 * 冷启动续跑还会基于断掉的上下文继续推理。
 *
 * 这一版加了 `truncatedChars` 来标记，但第一版写错过一次——读回重写时用了 `=` 而不是 `+=`，
 * 于是「123 字符被截断」的历史消息再存一次就变成了「完整」，标记被自己抹掉。
 * 那次没有断言发现，靠人眼 review 看到的。这组测试就是为了让它下次自己红。
 *
 * 4000 这个数字在这里是硬编码的：它等于 `ConversationStore.MAX_FIELD_CHARS`。
 * 如果哪天上限被调大，这里会红——那正是想要的提醒（标记逻辑要跟着一起看）。
 */
class ConversationTruncationTest {

    private val limit = 4000

    private fun longText(extra: Int) = "x".repeat(limit + extra)

    @Test
    fun `超长用户正文落盘时记录被丢弃的字符数`() {
        val rec = UiItem.User(longText(123)).toMsgRecord()
        assertEquals("落盘正文应被截到上限", limit, rec.content.length)
        assertEquals("应记录丢弃了 123 个字符", 123, rec.truncatedChars)
    }

    @Test
    fun `读回重写不清零截断计数`() {
        // 这就是那个写错成 `=` 的路径：MsgRecord → UiItem → MsgRecord。
        val back = UiItem.User(longText(123)).toMsgRecord().toUiItem()
        assertEquals("读回后 UI 应知道自己被截断", 123, (back as UiItem.User).truncatedChars)

        val again = back.toMsgRecord()
        assertEquals("再次落盘必须累加而不是清零", 123, again.truncatedChars)
    }

    @Test
    fun `短正文不记截断`() {
        val rec = UiItem.User("短消息").toMsgRecord()
        assertEquals(0, rec.truncatedChars)
        assertEquals("短消息", rec.content)
    }

    @Test
    fun `恰好等于上限不算截断`() {
        val rec = UiItem.User("x".repeat(limit)).toMsgRecord()
        assertEquals(0, rec.truncatedChars)
        assertEquals(limit, rec.content.length)
    }

    @Test
    fun `助手正文同样记录截断`() {
        val rec = UiItem.Assistant(longText(50)).toMsgRecord()
        assertEquals(50, rec.truncatedChars)
        assertEquals(limit, rec.content.length)
    }

    @Test
    fun `工具结果同样记录截断`() {
        val call = ToolCall("call-1", "shell_run", JsonObject(emptyMap()))
        val rec = UiItem.Tool(call, longText(7), isError = false).toMsgRecord()
        assertEquals(7, rec.truncatedChars)
        assertEquals(limit, rec.content.length)
    }

    @Test
    fun `多个被截断字段的计数相加`() {
        // 正文和推理过程各自 take(4000)；两者都被截断时，标记应是两者丢弃量之和。
        val rec = UiItem.Assistant(
            text = longText(100),
            reasoning = longText(30)
        ).toMsgRecord()
        assertEquals(130, rec.truncatedChars)
    }
}
