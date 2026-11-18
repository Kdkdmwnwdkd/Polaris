package com.beichen.polaris.core.mcp.server

import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.agent.GuardContext
import com.beichen.polaris.core.agent.GuardDecision
import com.beichen.polaris.core.agent.ToolGuard
import com.beichen.polaris.core.agent.ToolGuardChain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 宿主配置。端口 0 = 由系统分配（推荐：避免与用户其他工具撞端口）。 */
data class McpHostConfig(
    val port: Int = 0,
    val bindAddress: String = "127.0.0.1",
    val protocolVersion: String = "2024-11-05",
    val serverName: String = "polaris",
    val serverVersion: String = "1.0",
)

/**
 * Polaris 的 MCP 服务端：把 57 个工具里「当前档位可见」的那部分暴露出去。
 *
 * ## 为什么手写 HTTP，不引 NanoHTTPD / Ktor
 *
 * 我们只需要 `POST /mcp` 一个路由、一个 `Content-Length`、一个 `Connection: close`。
 * 引一个框架的代价是 APK +100 KB 到 +2 MB、多一份依赖审计、多一个 CVE 面，
 * 而收益只是省下这 300 行。安全边界不靠框架——靠「只绑 loopback + Bearer + 请求体上限
 * + 严格的头解析」，这几条反而在框架里更难收紧。
 *
 * ## 为什么不用 SSE / 长连接
 *
 * MCP 的 Streamable HTTP 允许服务端用 SSE 推流，但工具调用是**一次性**的
 * （`tools/call` 一个请求一个结果），没有服务端主动推送的需求。实现 SSE 只会多一条
 * 容易写错的连接生命周期，换不来任何能力。响应固定 `application/json`，协议合法。
 *
 * ## 线程模型
 *
 * accept 一条线程，连接处理固定 4 线程。工具执行是 `runBlocking`——它阻塞的是
 * 连接线程，不是主线程。上限 4 的意义是「最多 4 个外部调用同时在跑」，
 * 这同时是一道天然的保护：外部客户端突发 100 个请求也只会排队。
 */
class McpServerHost(
    private val tools: McpToolSource,
    private val pairing: McpPairing,
    private val audit: McpAudit,
    private val config: McpHostConfig = McpHostConfig(),
    /**
     * 守卫链。M1 装配传 [ToolGuardChain.NONE]（只读档没有需要路径守卫的写操作）。
     * M2 接入写操作时**必须**传入与本地调用同一条链——这条是硬要求，不是可选装饰。
     */
    private val guard: ToolGuard = ToolGuardChain.NONE,
    /** 审批回调。null = 本版本没有审批 UI，任何需要审批的调用一律拒绝（绝不静默放行）。 */
    private val approver: McpApprover? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(WORKER_THREADS) { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    /** 当前实际端口；未启动时为 -1。 */
    @Volatile
    var port: Int = -1
        private set

    fun isRunning(): Boolean = running.get()

    /** 启动监听，返回实际端口。失败不抛，用 [Result] 让调用方决定是提示还是重试。 */
    fun start(): Result<Int> = runCatching {
        if (running.get()) return Result.success(port)
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(config.bindAddress, config.port))
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "mcp-accept").apply {
            isDaemon = true
            start()
        }
        port
    }.onFailure {
        running.set(false)
        port = -1
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = -1
    }

    /** 供 UI 展示的「当前可见工具数」。不含审批状态，纯可见性。 */
    fun visibleToolCount(tier: McpTier): Int = tools.list().count { tier.allows(it) }

    // ------------------------------------------------------------------ accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val conn = try {
                socket.accept()
            } catch (_: Throwable) {
                // close() 会让 accept 抛 SocketException；running 已置 false，正常退出。
                if (!running.get()) break else continue
            }
            runCatching { workers.execute { handleConnection(conn) } }
                .onFailure { runCatching { conn.close() } }
        }
    }

    // ------------------------------------------------------------------ http

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                respond(socket, 400, rpcError(JsonNull, -32700, "malformed request line"))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = readHeaders(input)

            if (path != "/mcp") {
                respond(socket, 404, rpcError(JsonNull, -32601, "not found"))
                return
            }
            if (method != "POST") {
                respond(socket, 405, rpcError(JsonNull, -32600, "only POST /mcp is supported"))
                return
            }

            val client = authenticate(headers["authorization"])
            if (client == null) {
                respond(
                    socket, 401,
                    rpcError(JsonNull, -32001, "invalid or expired token"),
                    extraHeaders = mapOf("WWW-Authenticate" to "Bearer realm=\"polaris\""),
                )
                return
            }

            val body = readBody(input, headers)
            if (body == null) {
                respond(socket, 413, rpcError(JsonNull, -32600, "body missing or larger than ${MAX_BODY_BYTES} bytes"))
                return
            }

            val payload = dispatch(body, client)
            if (payload == null) {
                // notification：协议要求 202 且无 body。
                respondEmpty(socket, 202)
            } else {
                respond(socket, 200, payload)
            }
        } catch (_: Throwable) {
            runCatching { respond(socket, 500, rpcError(JsonNull, -32603, "internal error")) }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun authenticate(header: String?): McpClientRecord? {
        val token = header?.removePrefix("Bearer ")?.trim().orEmpty()
        if (token.isEmpty()) {
            noteFailure()
            return null
        }
        val hit = pairing.authenticate(token)
        if (hit == null) {
            noteFailure()
            return null
        }
        return hit
    }

    @Volatile
    private var failures = 0
    @Volatile
    private var blockedUntil = 0L

    private fun noteFailure() {
        val now = clock()
        if (now > blockedUntil) failures = 0
        failures += 1
        if (failures >= MAX_AUTH_FAILURES) {
            blockedUntil = now + AUTH_COOLDOWN_MS
            failures = 0
            pairing.noteAuthFailure()
        }
    }

    private fun isBlocked(): Boolean = clock() < blockedUntil

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = HashMap<String, String>(8)
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return headers
    }

    /** 只读 header 行；不用 BufferedReader，避免它把 body 一起吞进自己的缓冲。 */
    private fun readLine(input: InputStream, limit: Int = MAX_LINE): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) {
                if (sb.length >= limit) return null
                sb.append(b.toChar())
            }
            b = input.read()
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String? {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            // 明确不支持：解析 chunked 是另一个错误面，而主流 MCP 客户端都发 Content-Length。
            return null
        }
        val len = headers["content-length"]?.toIntOrNull() ?: return null
        if (len <= 0 || len > MAX_BODY_BYTES) return null
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        if (read <= 0) return null
        return String(buf, 0, read, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ rpc

    private fun dispatch(body: String, client: McpClientRecord): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return rpcError(JsonNull, -32700, "parse error")

        val id = root["id"] ?: JsonNull
        val method = root["method"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return rpcError(id, -32600, "missing method")

        // 通知类一律 202，不回 body。
        if (method.startsWith("notifications/")) return null

        return when (method) {
            "initialize" -> rpcResult(id, initializeResult())
            "ping" -> rpcResult(id, buildJsonObject { })
            "tools/list" -> rpcResult(id, toolsList(client))
            "tools/call" -> toolsCall(id, root, client)
            else -> rpcError(id, -32601, "method not found: $method")
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", config.protocolVersion)
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
        })
        put("serverInfo", buildJsonObject {
            put("name", config.serverName)
            put("version", config.serverVersion)
        })
    }

    private fun toolsList(client: McpClientRecord): JsonObject {
        val tier = McpTier.fromWire(client.tier)
        val exposed = tools.list()
            .filter { tier.allows(it) }
            .map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", schemaOf(tool.parameters))
                    put(
                        "annotations",
                        buildJsonObject {
                            put("readOnlyHint", tool.readOnly)
                            put("destructiveHint", tool.needsApproval && !tool.readOnly)
                        },
                    )
                }
            }
        return buildJsonObject { put("tools", JsonArray(exposed)) }
    }

    private fun toolsCall(id: JsonElement, root: JsonObject, client: McpClientRecord): String {
        if (isBlocked()) return rpcError(id, -32002, "too many auth failures, retry later")

        val tier = McpTier.fromWire(client.tier)
        val params = root["params"]?.jsonObject
        val name = params?.get("name")?.jsonPrimitive?.contentOrNullSafe()
            ?: return rpcError(id, -32602, "missing params.name")
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = tools.get(name)
            ?: return rpcError(id, -32602, "unknown tool: $name")

        if (!tier.allows(tool)) {
            audit.record(client.name, tier, name, args, "deny_tier", null, 0, "rejected")
            return rpcError(id, -32003, "tool '$name' is not exposed at tier '${tier.wire}'")
        }

        // 审批：没有审批 UI 就拒绝。绝不因为「是外部调用」而跳过 needsApprovalFor。
        if (tool.needsApprovalFor(args)) {
            val key = com.beichen.polaris.core.tool.ToolCatalog.approvalOf(name)
            // toolsCall 是同步函数（HTTP 处理线程），approve 是 suspend——这里用 runBlocking
            // 是有意的：审批本来就要等用户，阻塞的正是这条连接自己的线程，不影响 accept。
            val allowed = approver?.let { runBlocking { it.approve(client, tool, args, key) } } ?: false
            if (!allowed) {
                audit.record(client.name, tier, name, args, "deny_approval", key, 0, "rejected")
                return rpcError(
                    id, -32004,
                    "tool '$name' requires user approval; this build has no approval surface (M1)",
                )
            }
        }

        val callId = id.toString()
        val call = ToolCall(id = callId, name = name, arguments = args)

        // 守卫链：M1 传 NONE，等价弃权。M2 传真实链后，这里的 Deny 必须回喂给客户端。
        val decision = runBlocking {
            guard.check(GuardContext(call, tools.descriptorOf(name), 0, "mcp:${client.name}"))
        }
        if (decision is GuardDecision.Deny) {
            audit.record(client.name, tier, name, args, "deny_guard", null, 0, decision.code)
            return rpcError(id, -32005, decision.message)
        }

        val started = clock()
        val message = runBlocking { tools.execute(call) }
        val elapsed = clock() - started
        audit.record(
            client.name, tier, name, args,
            decision = "allow",
            approvalKey = null,
            durationMs = elapsed,
            status = if (message.isError) "error" else "ok",
        )
        return rpcResult(id, toolCallResult(message.content, message.isError, message.images))
    }


    /**
     * [JsonSchema] → MCP `inputSchema`。
     *
     * 手写而不是 `Json.encodeToJsonElement(...)`：后者要求 `@Serializable` 的编译器插件，
     * 而 core 的其它部分不依赖它——为一次序列化把插件引进内核，收益不值。
     * 顺带把 `enum`/`items`/嵌套 `properties` 这几个「漏了就会被严格客户端拒掉」的字段
     * 显式写出来，比依赖默认值更可控。
     */
    private fun schemaOf(schema: JsonSchema): JsonObject = buildJsonObject {
        put("type", schema.type)
        if (schema.properties.isNotEmpty()) {
            put("properties", buildJsonObject {
                schema.properties.forEach { (key, prop) -> put(key, propertyOf(prop)) }
            })
        }
        if (schema.required.isNotEmpty()) {
            put("required", JsonArray(schema.required.map { JsonPrimitive(it) }))
        }
    }

    private fun propertyOf(prop: JsonSchema.Property): JsonObject = buildJsonObject {
        put("type", prop.type)
        if (prop.description.isNotBlank()) put("description", prop.description)
        prop.enum?.takeIf { it.isNotEmpty() }?.let { values ->
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        }
        prop.items?.let { put("items", propertyOf(it)) }
        prop.properties?.takeIf { it.isNotEmpty() }?.let { nested ->
            put("properties", buildJsonObject {
                nested.forEach { (key, p) -> put(key, propertyOf(p)) }
            })
        }
        prop.required?.takeIf { it.isNotEmpty() }?.let { req ->
            put("required", JsonArray(req.map { JsonPrimitive(it) }))
        }
    }

    private fun toolCallResult(text: String, isError: Boolean, images: List<String>): JsonObject =
        buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                images.forEach { url ->
                    parseDataUrl(url)?.let { (mime, data) ->
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", data)
                            put("mimeType", mime)
                        })
                    }
                }
            })
            put("isError", isError)
        }

    /** `data:image/png;base64,xxx` → (mime, base64)。不是 data URL 就丢弃（MCP 只认 base64）。 */
    private fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val meta = url.substring(5, comma)
        if (!meta.contains("base64")) return null
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        if (data.length > MAX_IMAGE_CHARS) return null
        return mime to data
    }

    // ------------------------------------------------------------------ wire

    private fun rpcResult(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    private fun respond(
        socket: Socket,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        val out: OutputStream = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun respondEmpty(socket: Socket, status: Int) {
        val head = "HTTP/1.1 $status ${reason(status)}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    companion object {
        const val WORKER_THREADS = 4
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGE_CHARS = 1_500_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_LINE = 8 * 1024
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_AUTH_FAILURES = 10
        const val AUTH_COOLDOWN_MS = 5L * 60 * 1000
    }
}

/** 审批回调：返回 true 才执行。M1 无实现，M2 接手机端审批卡片。 */
fun interface McpApprover {
    suspend fun approve(client: McpClientRecord, tool: com.beichen.polaris.core.AgentTool, args: JsonObject, approvalKey: String?): Boolean
}
