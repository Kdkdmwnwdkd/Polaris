/**
 * MCP 宿主的协议与安全边界验证。
 *
 * ## 为什么是行为 harness，不是源码扫描
 *
 * 这套东西的价值全在「不该执行的时候真的没执行」：
 *  - 档位外的工具，`tools/call` 必须被拒，**而且工具实现一次都没被调用**；
 *  - 需要审批的工具，在没有审批界面时必须拒绝，不能「反正没人看见就放行」；
 *  - 错 token 必须 401，而不是「先执行再说」。
 *
 * 扫描源码只能证明有 `if`，证明不了分支顺序对、证明不了 `execute` 没被提前调用。
 * 所以这里真起 Socket、真发 HTTP、真数执行次数。
 *
 * ## 不测什么
 *
 * - Android 侧的前台服务与 UI（`McpHostService` / `McpHostCard`）：它们依赖
 *   Context 与 Compose，本 harness 跑在纯 JVM 上。那部分的验收在真机矩阵。
 * - `McpToolset` 装的是哪 7 个工具：那是装配事实，由 run-mcp-host-verification.sh
 *   的 A 段做源码断言（数量与名单）。
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.mcp.server.McpToolSource
import com.beichen.polaris.core.agent.ToolGuardChain
import com.beichen.polaris.core.mcp.server.McpAudit
import com.beichen.polaris.core.mcp.server.McpHostConfig
import com.beichen.polaris.core.mcp.server.McpPairing
import com.beichen.polaris.core.mcp.server.McpServerHost
import com.beichen.polaris.core.mcp.server.McpTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private var passed = 0
private var failed = 0

private fun ok(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        println("  PASS  $name")
        passed++
    } else {
        println("  FAIL  $name${if (detail.isBlank()) "" else " —— $detail"}")
        failed++
    }
}

/** 假工具：名字必须与 ToolCatalog 里的一致，档位判定才走真实元数据。 */
private class FakeTool(
    override val name: String,
    private val counter: AtomicInteger,
    override val needsApproval: Boolean = false,
) : AgentTool {
    override val description = "harness 假工具 $name"
    override val parameters = JsonSchema.objectSchema()
    val calls: Int get() = counter.get()

    override suspend fun execute(args: JsonObject): ToolResult {
        counter.incrementAndGet()
        return ToolResult.ok(message = "ran:$name")
    }
}

/**
 * 解析响应体里的 `result.protocolVersion`。
 *
 * 一律走 [objOrNull] / [primOrNull] 软转换：这是**被测方真实会收到的输入**，
 * 硬转在 `null` 字段上抛异常，用它会掩盖「协议层把 null 当对象读」的缺陷。
 */
private fun protocolVersion(body: String): String? = rpcResult(body)
    ?.get("protocolVersion").primOrNull()?.content

private fun rpcResult(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body).objOrNull()?.get("result").objOrNull() }.getOrNull()

/** `result.tools[].name` 的集合——档位过滤的可见结果。 */
private fun toolNames(body: String): Set<String> = rpcResult(body)
    ?.get("tools").arrOrNull()
    ?.mapNotNull { it.objOrNull()?.get("name").primOrNull()?.content }
    ?.toSet()
    .orEmpty()

/** JSON-RPC 错误码；成功响应返回 null。 */
private fun errorCode(body: String): Int? = runCatching {
    Json.parseToJsonElement(body).objOrNull()
        ?.get("error").objOrNull()
        ?.get("code").primOrNull()
        ?.content?.toIntOrNull()
}.getOrNull()

/** `tools/call` 成功时的文本内容（M1 只回 text，不涉图片/资源）。 */
private fun toolText(body: String): String =
    rpcResult(body)?.get("content").arrOrNull()
        ?.mapNotNull { it.objOrNull()?.get("text").primOrNull()?.content }
        ?.joinToString("\n")
        .orEmpty()

private fun post(port: Int, body: String, token: String?): Pair<Int, String> {
    val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 3000
    conn.readTimeout = 8000
    conn.setRequestProperty("Content-Type", "application/json")
    token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    return code to text
}

fun main() {
    val dir = File("/tmp/qa-mcp-host-${System.nanoTime()}").apply { mkdirs() }
    val pairing = McpPairing(File(dir, "clients.json"))
    val audit = McpAudit(File(dir, "audit"))
    val calls = AtomicInteger()

    val tools = listOf(
        FakeTool("read_file", calls),
        FakeTool("calculator", calls),
        FakeTool("app_launch", calls),
        FakeTool("ui_tap", calls, needsApproval = true),
        // PRIVILEGED + 需审批：标准档可见、但必须弹卡。没有审批界面时就得拒。
        FakeTool("write_file", calls, needsApproval = true),
        // 硬禁用名单里的名字：即便档位是 FULL 也不该出现。
        FakeTool("app_uninstall", calls, needsApproval = true),
    )

    // 真实 ToolRegistry 不在本 harness 的编译面里（它牵序列化编译器插件）。
    // 这里用 4 行转发模拟它的行为：未知工具返回错误、其余直接执行。
    val source = object : McpToolSource {
        override fun list(): List<AgentTool> = tools
        override fun get(name: String): AgentTool? = tools.firstOrNull { it.name == name }
        override fun descriptorOf(name: String): ToolDescriptor? =
            get(name)?.let { ToolDescriptor(it.name, it.name, "{}") }

        override suspend fun execute(call: ToolCall): ToolMessage {
            val tool = get(call.name)
                ?: return ToolMessage(call.id, call.name, call.arguments, "unknown_tool", isError = true)
            val result = tool.execute(call.arguments)
            return ToolMessage(
                id = call.id,
                name = tool.name,
                arguments = call.arguments,
                content = result.message,
                isError = !result.ok,
            )
        }
    }

    val host = McpServerHost(
        tools = source,
        pairing = pairing,
        audit = audit,
        config = McpHostConfig(),
        guard = ToolGuardChain.NONE,
        approver = null,
    )
    val port = host.start().getOrElse {
        println("  FAIL  B0 监听启动失败：${it.message}")
        println("0 通过 / 1 失败")
        return
    }

    val readToken = pairing.pair("只读端", McpTier.READONLY).token
    val stdToken = pairing.pair("标准端", McpTier.STANDARD).token

    val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"harness","version":"1"}}}"""

    // ── 鉴权 ──────────────────────────────────────────────────────────
    val noToken = post(port, init, null)
    ok("B1 无 token → 401", noToken.first == 401, "实际 ${noToken.first}")

    val badToken = post(port, init, "not-a-real-token")
    ok("B2 错 token → 401", badToken.first == 401, "实际 ${badToken.first}")

    val good = post(port, init, readToken)
    ok(
        "B3 正确 token → 握手成功且协议版本回显",
        good.first == 200 && protocolVersion(good.second) == "2024-11-05",
        "状态 ${good.first}，体 ${good.second.take(120)}",
    )

    // ── 档位可见性 ────────────────────────────────────────────────────
    val readNames = toolNames(post(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", readToken).second)
    ok("B4 只读档只暴露 SAFE 工具", readNames == setOf("read_file", "calculator"), "实际 $readNames")
    ok("B5 只读档看不到 app_launch（PRIVILEGED）", "app_launch" !in readNames)
    ok("B6 只读档看不到 ui_tap（DANGEROUS）", "ui_tap" !in readNames)

    val stdNames = toolNames(post(port, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", stdToken).second)
    ok("B7 标准档看到 app_launch", "app_launch" in stdNames, "实际 $stdNames")
    ok("B8 标准档仍看不到 ui_tap", "ui_tap" !in stdNames)
    ok("B9 任何档位都看不到 app_uninstall", "app_uninstall" !in stdNames)

    // ── 越档调用：必须拒绝且不执行 ────────────────────────────────────
    val before = calls.get()
    val crossCall = post(
        port,
        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"app_launch","arguments":{}}}""",
        readToken,
    )
    ok(
        "B10 只读档调 app_launch → -32003",
        errorCode(crossCall.second) == -32003,
        "体 ${crossCall.second.take(140)}",
    )
    ok("B11 被拒的调用一次都没执行", calls.get() == before, "执行数 ${calls.get() - before}")

    // ── 需要审批但无审批界面：拒绝，不静默放行 ────────────────────────
    val before2 = calls.get()
    val approvalCall = post(
        port,
        """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"write_file","arguments":{"path":"a.txt","content":"x"}}}""",
        stdToken,
    )
    ok(
        "B12 需审批的工具在无审批界面时 → -32004（不静默执行）",
        errorCode(approvalCall.second) == -32004,
        "体 ${approvalCall.second.take(140)}",
    )
    ok("B13 需要审批的调用一次都没执行", calls.get() == before2)

    // ── 正常调用 ──────────────────────────────────────────────────────
    val okCall = post(
        port,
        """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"a.txt"}}}""",
        readToken,
    )
    ok(
        "B14 档位内调用成功且结果回传",
        okCall.first == 200 && toolText(okCall.second).indexOf("ran:read_file") >= 0,
        "体 ${okCall.second.take(160)}",
    )
    ok("B15 成功的调用确实执行了一次", calls.get() == before2 + 1, "执行数 ${calls.get() - before2}")

    // ── 通知与未知方法 ────────────────────────────────────────────────
    val notify = post(port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", readToken)
    ok("B16 通知返回 202 且无 body", notify.first == 202, "实际 ${notify.first}")

    val unknown = post(port, """{"jsonrpc":"2.0","id":7,"method":"nope/nope"}""", readToken)
    ok("B17 未知方法 → -32601", errorCode(unknown.second) == -32601, "实际 ${errorCode(unknown.second)}")

    // ── 审计 ──────────────────────────────────────────────────────────
    val auditFiles = File(dir, "audit").listFiles()?.filter { it.name.endsWith(".jsonl") }.orEmpty()
    val auditText = auditFiles.joinToString("\n") { it.readText() }
    ok("B18 审计落盘", auditFiles.isNotEmpty())
    val decisions = auditFiles.flatMap { f -> f.readLines() }.mapNotNull { line ->
        runCatching {
            Json.parseToJsonElement(line).objOrNull()?.let { o ->
                (o["decision"].primOrNull()?.content ?: "?") to (o["tool"].primOrNull()?.content ?: "?")
            }
        }.getOrNull()
    }
    ok(
        "B19 审计记了越档拒绝（deny_tier / app_launch）",
        decisions.any { it == "deny_tier" to "app_launch" },
        "实际 $decisions",
    )
    ok(
        "B20 审计记了审批拒绝（deny_approval / write_file）",
        decisions.any { it == "deny_approval" to "write_file" },
        "实际 $decisions",
    )
    val fields = auditFiles.flatMap { f -> f.readLines() }.mapNotNull { line ->
        runCatching { Json.parseToJsonElement(line).objOrNull() }.getOrNull()
    }
    ok(
        "B21 审计参数只存摘要与预览，不存全文",
        fields.isNotEmpty() && fields.all { it["argsHash"].primOrNull() != null && it["argsPreview"].primOrNull() != null },
    )

    // ── 锁屏：人不在场，只放行 SAFE ──────────────────────────────────
    val lockedHost = McpServerHost(
        tools = source,
        pairing = pairing,
        audit = audit,
        config = McpHostConfig(),
        guard = ToolGuardChain.NONE,
        approver = null,
        screenOn = { false },
    )
    val lockedPort = lockedHost.start().getOrElse {
        println("  FAIL  B23 锁屏实例启动失败：${it.message}")
        println("$passed 通过 / ${failed + 1} 失败")
        return
    }
    val lockedCall = post(
        lockedPort,
        """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"app_launch","arguments":{"target":"com.x"}}}""",
        stdToken,
    )
    ok(
        "B23 锁屏时 app_launch 被拒（-32006）",
        errorCode(lockedCall.second) == -32006,
        "体 ${lockedCall.second.take(140)}",
    )
    val lockedRead = post(
        lockedPort,
        """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"calculator","arguments":{"expression":"1+1"}}}""",
        readToken,
    )
    ok(
        "B24 锁屏时 SAFE 工具仍可用",
        toolText(lockedRead.second).indexOf("ran:calculator") >= 0,
        "体 ${lockedRead.second.take(140)}",
    )
    lockedHost.stop()

    // ── 停服 ──────────────────────────────────────────────────────────
    host.stop()
    val afterStop = runCatching { post(port, init, readToken) }.isFailure
    ok("B25 stop 之后端口不再接受连接", afterStop)

    println("")
    println("$passed 通过 / $failed 失败")
}
