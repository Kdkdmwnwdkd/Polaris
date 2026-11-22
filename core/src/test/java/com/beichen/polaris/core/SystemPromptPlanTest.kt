package com.beichen.polaris.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemPromptPlan] 的分级判据（2026-11-23）。
 *
 * ## 为什么要有这一份
 *
 * 三段稳定性分级此前只写在注释里，没有任何断言。这类错**不会报错，只会变慢**：
 * 把记忆段标成 FROZEN，每轮记忆一变整段前缀作废，端侧推理从头 prefill，用户只
 * 感觉「这轮特别慢」，日志里也只有一条前缀变化的 INFO。同理「没记忆还拼
 * memory_usage」只多花几十 token，谁都不会注意到——直到小模型的注意力被这条用不上
 * 的指令分走。
 *
 * 所以这里断言的不是「拼出来长什么样」，而是三条**改了就要有人喊**的规则：
 * 1. 记忆变化不得改变稳定前缀指纹（[P7]）——否则 KV 复用被截断；
 * 2. 身份/工具面变化**必须**改变指纹（[P8]/[P9]）——否则缓存会喂出旧规则；
 * 3. 没记忆时不许留 memory_usage（[P5]）。
 */
class SystemPromptPlanTest {

    private val usage = "<memory_usage>记忆引导</memory_usage>"

    private fun plan(
        identity: String = "你是北辰。",
        hints: List<String> = emptyList(),
        memory: String? = null
    ) = SystemPromptPlan.sections(identity, hints, memory, usage)

    @Test
    fun `P1 身份段是 FROZEN 且排第一`() {
        val s = plan()
        assertEquals(1, s.size)
        assertEquals(SystemPromptPlan.KEY_IDENTITY, s[0].key)
        assertEquals(Stability.FROZEN, s[0].stability)
        assertEquals("你是北辰。", s[0].text)
    }

    @Test
    fun `P2 工具段是 SESSION，按输入顺序拼接`() {
        val s = plan(hints = listOf("先 ui_tree", "再 ui_tap"))
        assertEquals(2, s.size)
        assertEquals(SystemPromptPlan.KEY_TOOLS, s[1].key)
        // SESSION 而不是 TURN：工具面只在开新组时变，若标成 TURN 就永远进不了指纹，
        // 换了工具面缓存还当没换。
        assertEquals(Stability.SESSION, s[1].stability)
        assertEquals("先 ui_tree\n\n再 ui_tap", s[1].text)
    }

    @Test
    fun `P3 空白工具提示不产生空段`() {
        // 空段拼进去就是两个换行：多耗 token，而且不同轮次的空白不一致会让前缀失效。
        val s = plan(hints = listOf("", "  ", "ui_tree 先看结构", "\n\t "))
        assertEquals(2, s.size)
        assertEquals("ui_tree 先看结构", s[1].text)
    }

    @Test
    fun `P4 空白身份不产生身份段`() {
        val s = plan(identity = "   \n ")
        assertTrue("身份为空白时不该有 identity 段", s.none { it.key == SystemPromptPlan.KEY_IDENTITY })
        assertEquals("", SystemPromptPlan.assemble("   \n ", emptyList(), null, usage).text)
    }

    @Test
    fun `P5 没有记忆就不拼 memory_usage`() {
        // 四种「看起来没记忆」的写法都要认：null、空串、纯空格、纯空白字符。
        for (m in listOf(null, "", "   ", "\n\t ")) {
            val s = plan(memory = m)
            assertTrue(
                "记忆=${m?.replace("\n", "\\n")} 时不该出现记忆段或引导段",
                s.none { it.key == SystemPromptPlan.KEY_MEMORY || it.key == SystemPromptPlan.KEY_MEMORY_USAGE }
            )
        }
    }

    @Test
    fun `P6 记忆两段都是 TURN 且压在最后`() {
        val s = plan(hints = listOf("工具用法"), memory = "  用户喜欢简洁回答  ")
        assertEquals(4, s.size)
        assertEquals(Stability.TURN, s[2].stability)
        assertEquals(Stability.TURN, s[3].stability)
        assertEquals(SystemPromptPlan.KEY_MEMORY, s[2].key)
        assertEquals("用户喜欢简洁回答", s[2].text) // 摘要要去掉首尾空白
        assertEquals(SystemPromptPlan.KEY_MEMORY_USAGE, s[3].key)
        assertEquals(usage, s[3].text)
    }

    @Test
    fun `P7 只改记忆，稳定前缀指纹不变`() {
        // 这条是整套分段的**唯一目的**：记忆一天变十次，缓存也该一次都不失效。
        val a = SystemPromptPlan.assemble("你是北辰。", listOf("工具用法"), "记忆 A", usage)
        val b = SystemPromptPlan.assemble("你是北辰。", listOf("工具用法"), "完全不同的记忆 B", usage)
        assertNotEquals("正文本来就该不同", a.text, b.text)
        assertEquals("记忆变化不得改变稳定前缀指纹", a.signature, b.signature)
    }

    @Test
    fun `P8 改身份，稳定前缀指纹必须变`() {
        // 反过来：身份段在 FROZEN 里，改了却指纹不变＝缓存会继续喂旧规则。
        val a = SystemPromptPlan.assemble("你是北辰。", emptyList(), null, usage)
        val b = SystemPromptPlan.assemble("你是别的助手。", emptyList(), null, usage)
        assertNotEquals(a.signature, b.signature)
    }

    @Test
    fun `P9 改工具提示，稳定前缀指纹必须变`() {
        val a = SystemPromptPlan.assemble("你是北辰。", listOf("旧用法"), null, usage)
        val b = SystemPromptPlan.assemble("你是北辰。", listOf("新用法"), null, usage)
        assertNotEquals(a.signature, b.signature)
    }

    @Test
    fun `P10 同输入两次组装逐字节一致`() {
        // 段内顺序若来自 Map 遍历或哈希序，两次组装就会不同——缓存照样失效。
        val hints = listOf("甲", "乙", "丙")
        val a = SystemPromptPlan.assemble("你是北辰。", hints, "记忆", usage)
        val b = SystemPromptPlan.assemble("你是北辰。", hints, "记忆", usage)
        assertEquals(a.text, b.text)
        assertEquals(a.signature, b.signature)
        assertEquals(a.frozenChars, b.frozenChars)
    }
}
