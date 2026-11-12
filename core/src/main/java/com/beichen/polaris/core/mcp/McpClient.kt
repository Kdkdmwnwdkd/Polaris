package com.beichen.polaris.core.mcp

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.net.BoundedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 最小 MCP 客户端（HTTP / JSON-RPC 2.0）。
 *
 * 复用应用已有 OkHttp + kotlinx.serialization，不引入 kotlin-sdk 重依赖，保证离线构建。
 * 覆盖 MCP 核心：握手（initialize）→ 列举工具（tools/list）→ 调用工具（tools/call）。
 *
 * 传输约定：对 [baseUrl] POST 一个 JSON-RPC 请求体，解析返回的 JSON（若服务端以 SSE
 * 回包则取其中的 data: JSON 行）。result 缺失或含 error 时抛 [McpException]。
 */
class McpClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultHttp()
) {
    /**
     * JSON-RPC 请求 id（审计 F28）。
     *
     * 原来是 `private var nextId = 1` + `nextId++`：`rpc` 跑在 IO 线程池上，
     * 并发两次 callTool（模型一回合可并行发多个工具）会读到同一个值，
     * 两个请求拿到同一个 id——服务端回包就分不清是谁的，结果错配。
     * 原子递增的代价可以忽略，错配的代价是「工具返回了别人的结果」。
     */
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** initialize 握手。成功返回服务器信息。 */
    suspend fun initialize(): Result<McpServerInfo> = withContext(Dispatchers.IO) {
        runCatching {
            // W-47：服务器返回非对象（null/数组）时按协议错误处理，不抛硬转换异常
            val result = rpc("initialize", initializeParams()).objOrNull()
                ?: error("MCP initialize 响应不是 JSON 对象")
            val info = result["serverInfo"]?.objOrNull()
            McpServerInfo(
                name = info?.get("name")?.primOrNull()?.contentOrNull ?: "unknown",
                version = info?.get("version")?.primOrNull()?.contentOrNull ?: "?"
            )
        }
    }

    /** tools/list：列举服务器暴露的工具。 */
    suspend fun listTools(): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = rpc("tools/list", buildJsonObject { }).objOrNull()
                ?: error("MCP tools/list 响应不是 JSON 对象")
            val tools = result["tools"]?.arrOrNull() ?: buildJsonArray { }
            tools.mapNotNull { el ->
                // W-47：tools 数组里的 null 元素直接跳过（旧代码会抛 JsonNull 异常）
                val o = el.objOrNull() ?: return@mapNotNull null
                McpTool(
                    name = o["name"]?.primOrNull()?.contentOrNull ?: "",
                    description = o["description"]?.primOrNull()?.contentOrNull ?: "",
                    inputSchema = o["inputSchema"]
                )
            }
        }
    }

    /**
     * tools/call：调用工具。
     * @param argumentsJson 参数 JSON 对象字符串（非法 JSON 时按空对象处理）。
     * @return 原始 result（通常含 content 文本数组与 isError 标志），交由 UI 渲染。
     */
    suspend fun callTool(name: String, argumentsJson: String): Result<JsonElement> =
        withContext(Dispatchers.IO) {
            runCatching {
                val args = parseArgs(argumentsJson)
                rpc("tools/call", callToolParams(name, args))
            }
        }

    private fun parseArgs(s: String): JsonObject = runCatching {
        // W-47：模型给的参数不是对象（null/数组/非法）时退化为空对象，让工具自己报缺参
        json.parseToJsonElement(s.trim().ifEmpty { "{}" }).objOrNull() ?: buildJsonObject { }
    }.getOrElse { buildJsonObject { } }

    /** 发送单个 JSON-RPC 请求，返回 result 或抛 [McpException]。 */
    private fun rpc(method: String, params: JsonObject): JsonElement {
        val id = nextId.getAndIncrement()
        val payload = json.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = id, method = method, params = params)
        )
        val request = Request.Builder()
            .url(baseUrl)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json, text/event-stream")
            .build()
        http.newCall(request).execute().use { resp ->
            // 审计 F29：`body?.string()` 是整份进堆，修前对响应体大小毫无限制。
            // MCP 服务器由用户自己填，返回一个超长 JSON（或流式无限输出）会把进程读挂。
            // 与 F19/F21/F23 同一条规则：边读边计数，到上限即停。
            val text = resp.body?.byteStream()
                ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES).text }
                .orEmpty()
            if (!resp.isSuccessful) {
                throw McpException(resp.code, "HTTP ${resp.code}: ${text.take(300)}")
            }
            val envelope = json.decodeFromString(
                JsonRpcResponse.serializer(), extractJson(text)
            )
            envelope.error?.let { throw McpException(it.code, it.message) }
                ?: return envelope.result ?: JsonObject(emptyMap())
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = LlmHttp.llm

        /**
         * 从响应体提取 JSON 负载：直接 JSON 则原样；SSE（`data: {...}` 行）则拼接/取首个 data。
         */
        internal fun extractJson(text: String): String {
            val t = text.trim()
            if (t.startsWith("{")) return t
            // SSE：可能多行 data:，取最后一个含 JSON 的 data 行
            val dataLines = t.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.startsWith("{") }
                .toList()
            return dataLines.lastOrNull() ?: t
        }

        private const val TAG = "McpClient"

        init {
            AgentLogger.d(TAG, "McpClient 就绪（HTTP JSON-RPC 传输）")
        }
    }
}
