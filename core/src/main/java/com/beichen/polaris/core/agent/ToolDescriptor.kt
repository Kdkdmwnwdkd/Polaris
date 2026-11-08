package com.beichen.polaris.core.agent

/**
 * 工具描述符（T01）：loop 侧对「一次工具调用该长什么样」的纯数据视图。
 *
 * 与 [com.beichen.polaris.core.AgentTool] 的分工：AgentTool 是**执行体**
 * （带 suspend execute），ToolDescriptor 是**契约快照**——loop 只凭它做
 * 调用解析与日志，不碰执行：
 *
 *  - [name]：工具名（= AgentTool.name，模型 function calling 用的名字）。
 *  - [wireName]：协议线名。默认 = [name]（本地工具直通）；预留 MCP 前缀化
 *    （如 `mcp__server__tool`）——届时 wireName 与 name 分离，loop 的回喂
 *    映射仍认 name，协议层认 wireName。
 *  - [inputSchemaJson]：入参 JSON Schema 的序列化字符串（由 ToolRegistry
 *    从 AgentTool.parameters 纯映射产出，见 ToolRegistry.descriptors()）。
 *    字符串形态而非对象形态：MCP / 未来的远端工具描述本来就是 JSON 文本，
 *    早统一早省事。
 *
 * 纯数据类：无行为、无外部依赖，harness 可直接断言字段正确性。
 */
data class ToolDescriptor(
    val name: String,
    val wireName: String,
    val inputSchemaJson: String
)
