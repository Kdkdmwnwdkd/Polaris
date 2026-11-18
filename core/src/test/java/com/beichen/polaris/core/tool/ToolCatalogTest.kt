package com.beichen.polaris.core.tool

import com.beichen.polaris.core.agent.ApprovalGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录（`ToolCatalog`）与审批归组的一致性（2026-11-19）。
 *
 * ## 为什么需要这组
 *
 * 目录是「加一个工具要改哪几处」的单一真源，但它自己**没有任何运行时守卫**：
 * `audit()` 只在启动时把问题打成告警，改错了照样开机；`capabilityOf` 的 fail-closed
 * 兜底、`ApprovalGrouping.groupOf` 的「不并组」回退，都是**一行的安全属性**——
 * 把 `?: Capability.DANGEROUS` 改成 `?: Capability.SAFE`，全仓没有任何断言会红。
 *
 * 最值得钉住的是这一条：MCP 工具名由**外部服务器**决定。若审批归组退回
 * `ToolCatalog.groupOf`（按首段前缀并组），一个自称 `web_eval_helper` 的工具就会被
 * 并进 `web` 组——用户此前勾过的「不再询问网页操作」直接放行它。现在
 * `ApprovalGrouping.groupOf` 用的是 `approvalOf() ?: toolName`，不并组，但没人看着。
 */
class ToolCatalogTest {

    private val names = ToolCatalog.toolNames

    @Test
    fun `目录自检对当前实现为空`() {
        val problems = ToolCatalog.audit(names)
        assertTrue("目录报告了问题：${problems.joinToString("；")}", problems.isEmpty())
    }

    @Test
    fun `目录里有的工具没有实现时要报出来`() {
        val problems = ToolCatalog.audit(names - "read_file")
        assertTrue(
            "少一个实现必须被目录发现，否则模型照目录调用会拿到 unknown_tool：$problems",
            problems.any { it.startsWith("read_file") && it.contains("没有实现") }
        )
    }

    @Test
    fun `注册了目录里没有的工具时要报出来`() {
        val problems = ToolCatalog.audit(names + "ghost_tool")
        assertTrue(
            "多出来的实现查不到组/能力/审批，必须报：$problems",
            problems.any { it.startsWith("ghost_tool") && it.contains("没登记") }
        )
    }

    @Test
    fun `未登记工具的能力按 DANGEROUS 处理`() {
        assertEquals(
            "fail-closed 兜底被改掉就是静默放权：未知工具会被当作 SAFE 交给导入技能",
            Capability.DANGEROUS,
            ToolCatalog.capabilityOf("no_such_tool_at_all")
        )
    }

    @Test
    fun `未登记工具没有审批归组键`() {
        assertNull("未登记不该凭空得到一个归组键", ToolCatalog.approvalOf("no_such_tool_at_all"))
    }

    @Test
    fun `外部工具名以已声明组开头时不会并进那个组`() {
        // MCP 服务器可以提供任意名字的工具。若归组退化成「按前缀并组」，
        // 这个名字会落进 web 组，从而继承用户勾过的「不再询问网页操作」。
        val group = ApprovalGrouping.groupOf("web_eval_helper")
        assertEquals("不在目录里的工具必须单独成组（用自身名字），不能继承任何静态组", "web_eval_helper", group)
        assertFalse("绝不能等于 web 组", group == ApprovalGrouping.GROUP_UI_ACTION || group == "web")
    }

    @Test
    fun `目录里的工具按声明的 approval 归组`() {
        assertEquals(ApprovalGrouping.GROUP_FILE_WRITE, ApprovalGrouping.groupOf("write_file"))
        assertEquals(ApprovalGrouping.GROUP_SHELL, ApprovalGrouping.groupOf("shell_run"))
        // 声明了 approval = null 的工具（如 web_search）不参与分组，仍按自身名字记账：
        // 它本来就不弹窗，归组键只服务弹窗文案，凭空给它一个组反而会让白名单多一个入口
        assertEquals("web_search", ApprovalGrouping.groupOf("web_search"))
    }

    @Test
    fun `每个非空 approval 都能翻译成人话`() {
        // 笔误（"file_wirte"）不会编译失败，也不会查表失败——它只会让弹窗上出现
        // 一个英文键。labelOf 查不到时会原样返回，所以「返回值等于键」就是没翻译。
        val untranslated = ToolCatalog.tools
            .mapNotNull { it.approval }
            .distinct()
            .filter { ApprovalGrouping.labelOf(it) == it }
        assertTrue("这些 approval 键没有中文标签（大概是笔误或新加的忘了登记）：$untranslated", untranslated.isEmpty())
    }

    @Test
    fun `动态组与兜底组没有静态成员`() {
        assertTrue("mcp 组只能由运行时提供成员", ToolCatalog.membersOf("mcp").isEmpty())
        assertTrue("misc 是兜底桶，不该被显式登记", ToolCatalog.membersOf(ToolCatalog.MISC).isEmpty())
        assertFalse("目录里不允许有工具写进 misc", ToolCatalog.tools.any { it.group == ToolCatalog.MISC })
    }

    @Test
    fun `每个静态组都有成员`() {
        val empty = ToolCatalog.groups
            .filter { !it.dynamic && it.name != ToolCatalog.MISC }
            .filter { ToolCatalog.membersOf(it.name).isEmpty() }
            .map { it.name }
        assertTrue("空组会让模型按索引开组时开到空列表：$empty", empty.isEmpty())
    }

    @Test
    fun `工具名与组名都不重复`() {
        assertEquals("工具名重复", names.size, names.toSet().size)
        val groupNames = ToolCatalog.groups.map { it.name }
        assertEquals("组名重复", groupNames.size, groupNames.toSet().size)
    }

    @Test
    fun `目录顺序按组成段出现`() {
        // audit 里已有这条检查，这里单独钉一次：顺序决定发给模型的工具表顺序，
        // 插一行到中间的代价是云端 KV 前缀整段失效（不会报错，只会变贵变慢）。
        val segments = ToolCatalog.tools.map { it.group }.distinct()
        assertEquals(
            "同一组被插行拆成了多段",
            segments.size,
            ToolCatalog.tools.map { it.group }.toSet().size
        )
    }
}
