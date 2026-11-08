package com.beichen.polaris.core.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP（Model Context Protocol）最小数据模型——本质是 JSON-RPC 2.0。
 *
 * 只用 HTTP（Streamable）传输：POST 一个 JSON-RPC 请求，取回 JSON 结果。
 * 覆盖核心三件套：initialize 握手 / tools/list / tools/call。
 */

/** JSON-RPC 请求信封。 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject? = null
)

/** JSON-RPC 错误对象。 */
@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** JSON-RPC 响应信封（result 与 error 二选一）。 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

/** MCP 工具描述（来自 tools/list）。 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement? = null
)

/** MCP 服务器信息（来自 initialize）。 */
data class McpServerInfo(val name: String, val version: String)

/**
 * 一台已保存的 MCP 服务器。
 *
 * 放在这里而不是 [McpServerStore]：它只是数据，`McpServerStore` 才需要 Android 的
 * `SharedPreferences`。分开之后同步逻辑（[planMcpSync]）能在纯 JVM 里跑断言。
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)

/** MCP 客户端异常：带 JSON-RPC 错误码。 */
class McpException(val code: Int, message: String) : Exception(message)

/** 构造 initialize 握手参数（protocolVersion 取 MCP 稳定版之一）。 */
internal fun initializeParams(): JsonObject = buildJsonObject {
    put("protocolVersion", "2024-11-05")
    put("capabilities", buildJsonObject { })
    put("clientInfo", buildJsonObject {
        put("name", "Polaris")
        put("version", "1.0")
    })
}

/** 构造 tools/call 参数。 */
internal fun callToolParams(name: String, arguments: JsonObject): JsonObject = buildJsonObject {
    put("name", name)
    put("arguments", arguments)
}
