package com.beichen.polaris.mcp

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.core.mcp.server.McpToolSource

/**
 * [ToolRegistry] → [McpToolSource] 的适配器。
 *
 * 四行转发，没有任何逻辑——这正是目的：MCP 层与具体工具表之间只有这层薄壳，
 * P1 把工具表的所有者换成 `AgentRuntime` 时，改这一个文件就够。
 */
class RegistryToolSource(private val registry: ToolRegistry) : McpToolSource {
    override fun list(): List<AgentTool> = registry.list()
    override fun get(name: String): AgentTool? = registry.get(name)
    override suspend fun execute(call: ToolCall): ToolMessage = registry.execute(call)
    override fun descriptorOf(name: String): ToolDescriptor? = registry.descriptorOf(name)
}
