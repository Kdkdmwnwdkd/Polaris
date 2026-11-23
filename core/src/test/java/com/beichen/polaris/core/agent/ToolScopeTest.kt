package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.ExposureMode
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolScope] 的判据。
 *
 * 这五条守的都是「不报错但行为错」的路径：视图没收紧（被裁工具照常执行）、
 * 视图收紧过头（元工具消失，模型再也开不了组）。两者在真机上都只表现为
 * 「模型有点怪」，日志里没有任何一行是错误。
 */
class ToolScopeTest {

    private fun tool(n: String) = object : AgentTool {
        override val name: String = n
        override val description: String = "$n：执行 $n 相关操作。"
        override val parameters: JsonSchema = JsonSchema()
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ok")
    }

    private fun registryOf(vararg names: String) =
        ToolRegistry().apply { names.forEach { register(tool(it)) } }

    private fun plan(
        visible: List<String>,
        hiddenCount: Int,
        mode: ExposureMode = ExposureMode.GROUPED
    ) = KernelExposure.Plan(
        mode = mode,
        visible = visible.map { tool(it) },
        hiddenCount = hiddenCount,
        groups = emptyList(),
        activated = emptySet()
    )

    @Test
    fun `S1 未裁剪返回同一实例，不是等价副本`() {
        // 全量模式下 visible 就是全部工具。照抄一份 58 条的新表虽然结果相同，
        // 却切断了「视图 === 全量」这个可比较的事实，也白花每轮一次全量拷贝。
        val full = registryOf("read_file", "write_file")
        val view = ToolScope.viewOf(full, plan(listOf("read_file", "write_file"), hiddenCount = 0, mode = ExposureMode.FULL))
        assertSame("未裁剪必须是同一个对象", full, view)
    }

    @Test
    fun `S2 裁剪后名字与顺序严格等于 plan_visible`() {
        // 顺序不是小事：工具表的字节序决定端侧 KV 前缀能否复用，
        // 也决定模型看到的工具排列是否逐轮稳定。
        val full = registryOf("read_file", "write_file", "ui_swipe", "ui_tap")
        val visible = listOf("read_file", "ui_tap", "__find_tools")
        val view = ToolScope.viewOf(full, plan(visible, hiddenCount = 2))
        assertEquals("视图名字与顺序必须与 visible 一致", visible, view.names())
    }

    @Test
    fun `S3 裁剪后元工具仍在，否则模型再也开不了组`() {
        // __use_tools 的 description 里塞着全部分组清单，几百字符，
        // 是最容易被"优化"掉的一项。滤掉它 = 模型失去开组能力，
        // 而它得到的唯一反馈是「未知工具」——于是开始猜工具名。
        val full = registryOf("read_file", "ui_swipe")
        val visible = listOf("read_file", KernelExposure.FIND_TOOLS, KernelExposure.USE_TOOLS)
        val view = ToolScope.viewOf(full, plan(visible, hiddenCount = 1))
        assertNotNull("__find_tools 必须在视图里", view.get(KernelExposure.FIND_TOOLS))
        assertNotNull("__use_tools 必须在视图里", view.get(KernelExposure.USE_TOOLS))
    }

    @Test
    fun `S4 被裁工具在视图里查无此工具，执行器才会走未启用路径`() {
        // 执行器的判据是：fullRegistry 里有它、本回合视图里没有 → 「存在但未启用」。
        // 视图若等于全量，这条分支永不命中，被裁工具会被直接执行。
        val full = registryOf("read_file", "ui_swipe")
        val view = ToolScope.viewOf(full, plan(listOf("read_file"), hiddenCount = 1))
        assertNull("被裁工具在视图里必须查不到", view.get("ui_swipe"))
        assertNull("descriptorOf 也要为 null", view.descriptorOf("ui_swipe"))
        assertNotNull("全量里它还在，执行器据此区分两种情形", full.get("ui_swipe"))
    }

    @Test
    fun `S5 视图是快照，不反向污染全局注册表`() {
        // 视图只读不写。若哪天为了省事给 full 做 removeAll（"就地收紧"），
        // 全局工具表会缺项：Skill 桥、MCP 回卷、下一回合 own 都会看见残缺的表。
        val full = registryOf("read_file", "ui_swipe", "shell_run")
        ToolScope.viewOf(full, plan(listOf("read_file"), hiddenCount = 2))
        assertEquals("全局注册表不该被视图改动", 3, full.names().size)
        assertTrue("全局里 ui_swipe 仍在", full.names().contains("ui_swipe"))
    }

    @Test
    fun `S6 是否裁剪只看 plan_trimmed，不从 visible 数量反推`() {
        // 单一真相源：裁剪与否由 plan 判定（hiddenCount > 0）说了算。
        // 若实现改成 `visible.size >= full.size`，遇到「hiddenCount=0 但 visible 偏少」
        // 的畸形 plan 就会分叉——那种 plan 由 plan() 不会产出，但手工构造或未来字段
        // 变动可能产出，实现不该自己再算一遍。
        val full = registryOf("read_file", "write_file")
        val weird = plan(listOf("read_file"), hiddenCount = 0, mode = ExposureMode.FULL)
        assertSame("plan 说没裁，就返回同一实例", full, ToolScope.viewOf(full, weird))
    }
}
