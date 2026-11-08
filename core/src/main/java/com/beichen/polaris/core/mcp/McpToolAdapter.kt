package com.beichen.polaris.core.mcp

import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 单次 MCP 调用上限（W-19）：挂死的服务器不应拖住整轮任务。 */
private const val MCP_CALL_TIMEOUT_MS = 60_000L

/**
 * MCP 工具 → AgentKit 工具（W-14）。
 *
 * 这是「MCP 只做了一半」的补口：此前 [McpClient] 只在 MCP 页面里用于测试连接，
 * 对话里的模型根本调不到 MCP 工具。本文件把 `tools/list` 的结果包成 [AgentTool]，
 * 注册进 [com.beichen.polaris.core.ToolRegistry]，模型即可像调用内置工具一样调用。
 *
 * 命名策略：`mcp_<server>_<tool>`（全部小写、非字母数字转下划线、各段截断）。
 * 前缀化是为了避免与内置工具重名，也让日志/审批界面一眼看出「这是外部工具」。
 */
class McpToolAdapter(
    serverName: String,
    private val originalName: String,
    private val client: McpClient,
    private val tool: McpTool
) : AgentTool {

    override val name: String = mcpWireName(serverName, originalName)

    override val description: String = buildString {
        append(tool.description.ifBlank { "MCP 工具 $originalName" })
        append("（外部 MCP 服务器「").append(serverName).append("」提供）")
    }

    override val parameters: JsonSchema = jsonSchemaFromMcp(tool.inputSchema)

    /**
     * MCP 工具的真实副作用未知：**保守按写操作**处理（走串行批次 + 审批策略），
     * 宁可慢一点，也不并行调用外部服务。
     */
    override val readOnly: Boolean = false

    /**
     * R4 审批收口：外部 MCP 工具**必须**走用户确认。
     *
     * 修前这里是默认的 `false`——`readOnly = false` 只影响串行调度，
     * 审批判据看的是 `needsApproval`，于是「外部服务器提供的工具」可以不经确认
     * 直接执行，这是三条旁路里最宽的一条（服务器可能做任何事，参数里可能带用户数据）。
     *
     * 代价是每次调用都弹窗。这个代价是故意的：MCP 服务器由用户手动配置，
     * 但配置之后 Agent 会自己决定何时调用——「我信任这个服务器」和
     * 「我同意这一次把这段内容发出去」是两件事。
     */
    override val needsApproval: Boolean = true

    override val systemPrompt: String =
        "这是外部 MCP 工具，参数必须严格符合其 JSON Schema；调用失败时先读错误信息修正参数，" +
            "不要原样重复调用。"

    override suspend fun execute(args: JsonObject): ToolResult {
        // W-19：MCP 服务器可能挂死（网络卡住/服务端不返回），60 秒没结果就明确失败，
        // 不能让整轮任务无限等待。
        val result = withTimeoutOrNull(MCP_CALL_TIMEOUT_MS) {
            client.callTool(originalName, args.toString())
        } ?: return ToolResult.fail(
            code = "mcp_timeout",
            message = "MCP 工具「$originalName」执行超时（60 秒）。",
            hint = "服务器可能不可用；换用其他工具或稍后重试，不要原样连续重试。"
        )
        return result.fold(
            onSuccess = { toolResultFromMcp(it) },
            onFailure = { e ->
                ToolResult.fail(
                    code = "mcp_call_failed",
                    message = "MCP 工具「$originalName」调用失败：${e.message}",
                    hint = "检查服务器是否在线、参数是否符合 Schema；连续失败请换用其他工具或告知用户。"
                )
            }
        )
    }
}

/* ------------------------------------------------------------------ */
/* 纯函数（JVM harness 可断言）                                        */
/* ------------------------------------------------------------------ */

/** 合法工具名字符集：`[a-z0-9_]`；用于把 MCP 名称规范化成 function-calling 可接受的形式。 */
internal fun sanitizeToolSegment(raw: String, maxLen: Int = 24): String {
    val cleaned = raw.lowercase()
        .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
        .joinToString("")
        .trim('_')
        .replace(Regex("_+"), "_")
    return cleaned.take(maxLen).ifEmpty { "tool" }
}

/** 带前缀的工具名：`mcp_<server>_<tool>`。纯函数，同入参同结果。 */
fun mcpWireName(serverName: String, toolName: String): String =
    "mcp_${sanitizeToolSegment(serverName, 12)}_${sanitizeToolSegment(toolName)}"

/**
 * MCP `inputSchema` → [JsonSchema]。
 *
 * [JsonSchema] 只支持扁平字段（首期契约），所以这里：
 *  - 顶层 `properties` 的标量类型原样映射；
 *  - `object` / `array` 保留类型名（模型知道要传结构，具体形状由服务器校验）；
 *  - 枚举只保留纯字符串枚举（数字枚举转字符串会误导）；
 *  - 描述截断到 200 字，避免一个巨型 schema 撑爆提示词。
 */
fun jsonSchemaFromMcp(input: JsonElement?): JsonSchema {
    val obj = input as? JsonObject ?: return JsonSchema()
    val required = (obj["required"] as? JsonArray)
        ?.mapNotNull { it.primOrNull()?.contentOrNull }
        ?: emptyList()
    val propsObj = obj["properties"] as? JsonObject ?: return JsonSchema(required = required)

    val props = LinkedHashMap<String, JsonSchema.Property>()
    propsObj.forEach { (key, value) ->
        val p = value as? JsonObject ?: return@forEach
        val rawType = p["type"]?.primOrNull()?.contentOrNull ?: "string"
        val desc = p["description"]?.primOrNull()?.contentOrNull.orEmpty().take(200)
        props[key] = JsonSchema.Property(
            type = normalizeSchemaType(rawType),
            description = desc,
            enum = stringEnumOf(p["enum"])
        )
    }
    // required 里可能含 properties 中不存在的键（服务器写错），过滤掉以免 schema 不自洽
    return JsonSchema(properties = props, required = required.filter { it in props })
}

/** 只接受 function-calling 认识的类型；其余（null/未知）退化为 string。 */
internal fun normalizeSchemaType(raw: String): String = when (raw) {
    "string", "number", "integer", "boolean", "array", "object" -> raw
    else -> "string"
}

/** 纯字符串枚举才保留；混合类型或数字枚举返回 null（避免误导模型）。 */
internal fun stringEnumOf(el: JsonElement?): List<String>? {
    val arr = el as? JsonArray ?: return null
    val values = arr.mapNotNull { it.primOrNull()?.contentOrNull }
    if (values.size != arr.size) return null
    return values.takeIf { it.isNotEmpty() }
}

/**
 * MCP `tools/call` 结果 → [ToolResult]。
 *
 * MCP 标准返回形如 `{ content: [{type:"text", text:"..."}], isError?: true }`；
 * 兼容 image/resource 片段（非文本只留占位，图片 URL 不强行渲染，避免 base64 撑爆历史）。
 */
fun toolResultFromMcp(el: JsonElement): ToolResult {
    val obj = el as? JsonObject
    val isError = obj?.get("isError")?.primOrNull()?.booleanOrNull ?: false
    val contentArray = obj?.get("content") as? JsonArray

    val text = if (contentArray != null) {
        contentArray.mapNotNull { part ->
            val o = part as? JsonObject ?: return@mapNotNull null
            when (o["type"]?.primOrNull()?.contentOrNull) {
                "text" -> o["text"]?.primOrNull()?.contentOrNull
                "image" -> "[图片内容已省略]"
                "resource" -> o["resource"]?.objOrNull()?.get("text")?.primOrNull()?.contentOrNull
                    ?: "[资源内容已省略]"
                else -> null
            }
        }.joinToString("\n")
    } else {
        el.toString()
    }.ifBlank { "(工具无输出)" }

    return if (isError) {
        ToolResult.fail(
            code = "mcp_tool_error",
            message = "MCP 工具返回错误",
            hint = "按错误信息修正参数后重试；同一参数不要重复提交。",
            data = text.take(2_000)
        )
    } else {
        ToolResult.ok(code = "mcp_ok", message = "MCP 工具执行完成", data = text.take(8_000))
    }
}
