package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨回合历史压缩（P0-1）。
 *
 * ## 为什么这里必须钉死
 *
 * 长会话失忆是「静默」的：第 21 轮的问题照常发出去，模型照常回答，只是答得牛头不对马嘴。
 * 没有异常、没有日志、用户只会觉得「这 AI 变笨了」。所以压缩逻辑要么完全正确，
 * 要么就会在没人注意的地方悄悄吃掉一段对话——这类缺陷只能在纯函数层拦住。
 *
 * 断言分三组：
 *  1. 不超窗口时**逐字不变**（不能为了新功能改动老行为）；
 *  2. 超窗口时摘要必须存在，且必须交代「最初的任务」；
 *  3. 规模涨到几百轮时仍要守住字符上限（否则摘要自己变成新的上下文炸弹）。
 */
class HistoryCompactorTest {

    private fun turn(q: String, a: String = "", tools: List<String> = emptyList()) =
        HistoryCompactor.Turn(q, a, tools)

    private fun userTexts(msgs: List<ChatMessage>) =
        msgs.filterIsInstance<UserMessage>().map { it.content }

    @Test
    fun `窗口内逐轮问答顺序不变且没有摘要`() {
        val turns = listOf(turn("第一个问题", "第一个回答"), turn("第二个问题", "第二个回答"))
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)

        assertEquals(4, msgs.size)
        // 用 as? 而不是 `is UserMessage && msgs[0].content`：下标表达式不是稳定表达式，
        // smart cast 不成立（K2 编译器直接报 unresolved reference）。
        assertEquals("第一个问题", (msgs[0] as? UserMessage)?.content)
        assertEquals("第一个回答", (msgs[1] as? AssistantMessage)?.content)
        assertEquals("第二个问题", (msgs[2] as? UserMessage)?.content)
        assertTrue(
            "没超窗口不该出现折叠条",
            userTexts(msgs).none { it.startsWith(HistoryCompactor.DIGEST_MARK) }
        )
    }

    @Test
    fun `超窗口时最早的任务仍在摘要里`() {
        val turns = (1..25).map { turn("第 $it 个问题", "第 $it 个回答") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)

        val digest = userTexts(msgs).firstOrNull()
            ?: throw AssertionError("超出窗口却没有折叠条——这段对话被静默丢弃了")
        assertTrue("折叠条必须可识别", digest.startsWith(HistoryCompactor.DIGEST_MARK))
        // 任务锚点：被挤出的第一轮是「我们到底在干什么」，丢了模型只会瞎猜
        assertTrue("摘要丢了最初的任务", digest.contains("第 1 个问题"))
        assertTrue("摘要要说明折了多少轮", digest.contains("5 轮"))
        // 最近 20 轮必须完整保留（问题 + 答案），不能因为加了摘要就被挤掉
        assertTrue(msgs.any { it is UserMessage && it.content == "第 25 个问题" })
        assertTrue(msgs.any { it is AssistantMessage && it.content == "第 25 个回答" })
    }

    @Test
    fun `折叠条只占一条消息且排在最前`() {
        val turns = (1..30).map { turn("问题 $it", "回答 $it") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 10)

        val digests = userTexts(msgs).takeWhile { it.startsWith(HistoryCompactor.DIGEST_MARK) }
        assertEquals("折叠条不该重复出现", 1, digests.size)
        assertTrue("折叠条必须排最前（任务的锚点）", msgs.first() is UserMessage)
    }

    @Test
    fun `十倍窗口也不超字符上限`() {
        val turns = (1..300).map {
            turn("这是第 $it 个很长很长的问题，".repeat(3), "这是第 $it 个很长很长的回答。".repeat(5))
        }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        val digest = userTexts(msgs).first()

        assertTrue(
            "摘要 ${digest.length} 字，超过上限 ${HistoryCompactor.DIGEST_BUDGET_CHARS}",
            digest.length <= HistoryCompactor.DIGEST_BUDGET_CHARS
        )
        assertTrue("再长也得交代最初的任务", digest.contains("最初的任务"))
        assertTrue("再长也得说明省略了多少", digest.contains("已省略"))
    }

    @Test
    fun `空问题的轮次不占窗口名额`() {
        // 一次「问了没答」的空轮（异常数据）不该把最近一轮真对话挤出窗口
        val turns = listOf(turn(""), turn("真问题", "真回答"))
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 1)

        assertEquals(2, msgs.size)
        assertEquals("真问题", (msgs[0] as UserMessage).content)
        assertFalse(userTexts(msgs).any { it.startsWith(HistoryCompactor.DIGEST_MARK) })
    }

    @Test
    fun `工具名进摘要但不进正文`() {
        val turns = (1..25).map { i ->
            turn("问题 $i", "回答 $i", if (i == 1) listOf("ui_batch", "take_screenshot") else emptyList())
        }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        val digest = userTexts(msgs).first()

        assertTrue("摘要应带工具名（防止把做过的事再做一遍）", digest.contains("ui_batch"))
        // 正文里不能冒出工具名——历史 tool 消息与 tool_calls 必须严格配对，混进来会 400
        assertTrue(
            "工具名不该写进最近轮次的问答正文",
            msgs.drop(1).none { it is UserMessage && it.content.contains("ui_batch") }
        )
    }

    @Test
    fun `keepRecent 为零时全部折成一条`() {
        val turns = (1..5).map { turn("问题 $it", "回答 $it") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 0)

        assertEquals(1, msgs.size)
        assertTrue((msgs[0] as UserMessage).content.startsWith(HistoryCompactor.DIGEST_MARK))
    }

    @Test
    fun `空历史返回空列表`() {
        assertTrue(HistoryCompactor.buildPriorMessages(emptyList()).isEmpty())
        assertTrue(HistoryCompactor.buildPriorMessages(listOf(turn("  "))).isEmpty())
    }

    @Test
    fun `同样输入必得同样字节`() {
        // 确定性是这套方案的前提：视图每轮都在变的话，前缀缓存全废、问题也难复现
        val turns = (1..40).map { turn("问题 $it", "回答 $it", listOf("read_file")) }
        val a = HistoryCompactor.buildPriorMessages(turns, keepRecent = 10)
        val b = HistoryCompactor.buildPriorMessages(turns, keepRecent = 10)
        assertEquals(userTexts(a), userTexts(b))
    }
}
