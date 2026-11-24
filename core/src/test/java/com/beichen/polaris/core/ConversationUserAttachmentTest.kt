package com.beichen.polaris.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户气泡的附件回执：图片与文件名必须能落盘、能读回、旧文件不炸。
 *
 * ## 为什么需要
 *
 * 2026-11-24 真机反馈「只发图片/只发文件发不出去」，修完发现第二个问题：
 * 发出去了，但用户自己那条气泡只显示四个字，看不到发了什么。
 *
 * 修法是把缩略图与文件名挂到 [UiItem.User] 上并落盘。这里守三件事：
 *
 * ① **往返不丢**：`UiItem.User → MsgRecord → UiItem.User` 之后，图与文件名还在。
 *    最容易犯的错是只在写入侧加了字段、读回侧的 `when` 分支忘了带——界面上表现为
 *    「切走再切回来，图没了」，而当场看是对的，极难复现。
 * ② **旧文件不炸**：没有这两个字段的历史记录，反序列化后是空列表（不是 null，
 *    也不是解析失败把整条会话读丢）。
 * ③ **序列化真的写进去**：走一遍 JSON，避免字段被漏标、或被误加了 `@Transient`
 *    而在测试里看起来一切正常、到真机上却什么都没存。
 */
class ConversationUserAttachmentTest {

    private val thumb = "data:image/jpeg;base64,AAAABBBB"

    @Test
    fun `图片与文件名能在落盘读回之间保住`() {
        val item = UiItem.User(
            text = "看一下这两张",
            timeMs = 1L,
            images = listOf(thumb, thumb + "2"),
            fileNames = listOf("a.txt", "b.pdf")
        )

        val rec = item.toMsgRecord()
        assertEquals("落盘应带图", listOf(thumb, thumb + "2"), rec.images)
        assertEquals("落盘应带文件名", listOf("a.txt", "b.pdf"), rec.fileNames)

        val back = rec.toUiItem()
        assertTrue("读回仍应是用户气泡", back is UiItem.User)
        back as UiItem.User
        assertEquals(listOf(thumb, thumb + "2"), back.images)
        assertEquals(listOf("a.txt", "b.pdf"), back.fileNames)
        assertEquals("正文不能因为加了附件字段而丢", "看一下这两张", back.text)
    }

    @Test
    fun `旧记录没有这两个字段时读回为空列表`() {        // 旧文件里这两个 key 根本不存在——靠默认值兜住，不能让整条会话解析失败
        val legacy = MsgRecord(id = "old-1", role = ROLE_USER, content = "以前的消息")
        val back = legacy.toUiItem() as UiItem.User
        assertEquals(emptyList<String>(), back.images)
        assertEquals(emptyList<String>(), back.fileNames)
        assertEquals("以前的消息", back.text)
    }

    @Test
    fun `序列化真的写入了附件字段`() {
        val rec = UiItem.User("hi", images = listOf(thumb), fileNames = listOf("a.txt")).toMsgRecord()
        val json = Json.encodeToString(MsgRecord.serializer(), rec)
        assertTrue("JSON 里应有 images", json.contains("\"images\""))
        assertTrue("JSON 里应有 fileNames", json.contains("\"fileNames\""))

        // 反向：旧 JSON（两个 key 都没有）必须能读成默认空值
        val legacyJson = """{"id":"x","role":"user","content":"hi"}"""
        val decoded = Json.decodeFromString(MsgRecord.serializer(), legacyJson)
        assertEquals(emptyList<String>(), decoded.images)
        assertEquals(emptyList<String>(), decoded.fileNames)
    }

    /**
     * 切回会话走的是**另一条**重建路径：`ConversationRecord.toUiItems()` 逐轮渲染，
     * 不经过 `MsgRecord.toUiItem()`。AT2 那种「读回忘了带字段」的错法如果只发生在这条路径上，
     * 表现是「发完当场能看到图，切走再回来图没了」——当场验证永远发现不了。
     */
    @Test
    fun `切回会话时逐轮重建也带附件`() {
        val user = UiItem.User("带图", images = listOf(thumb), fileNames = listOf("a.txt")).toMsgRecord()
        val record = ConversationRecord(
            id = "c1",
            title = "t",
            createdAt = 0,
            updatedAt = 0,
            turns = listOf(TurnRecord(user))
        )
        val bubble = record.toUiItems().filterIsInstance<UiItem.User>().single()
        assertEquals(listOf(thumb), bubble.images)
        assertEquals(listOf("a.txt"), bubble.fileNames)
    }
}
