package com.beichen.polaris.core.mcp.server

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.agent.ToolDescriptor

/**
 * MCP 宿主看到的「工具表」抽象。
 *
 * ## 为什么不直接吃 [com.beichen.polaris.core.ToolRegistry]
 *
 * 两个具体理由：
 *  1. **编译面**：`ToolRegistry` 牵着 `ToolLoadProfiler`、`JsonSchema.serializer()`
 *     （后者要序列化编译器插件）。MCP 层只是「列工具、调工具」，不该为了这两件事
 *     把整个内核拖进 harness——拖进去的后果是行为测试在没装插件的机器上编不过，
 *     于是被 SKIP，安全断言就静默消失了。
 *  2. **P1 的方向**：`AgentRuntime` 落地后，工具表的所有者会变。MCP 只依赖这 4 个方法，
 *     换所有者时不用改协议层。
 */
interface McpToolSource {

    /** 全部已注册工具（顺序稳定，供 `tools/list` 与档位过滤）。 */
    fun list(): List<AgentTool>

    fun get(name: String): AgentTool?

    /** 执行一次调用。实现方负责异常兜底——协议层只认 [ToolMessage]。 */
    suspend fun execute(call: ToolCall): ToolMessage

    /** 守卫用的描述快照；未知工具返回 null。 */
    fun descriptorOf(name: String): ToolDescriptor?
}
