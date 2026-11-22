import com.beichen.polaris.core.llm.BalanceFailure
import com.beichen.polaris.core.llm.BalanceService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Collections

/**
 * 「查余额」端到端 harness（真 HTTP、真 OkHttp、真协程）。
 *
 * ## 为什么需要它
 *
 * W-29 只验了**纯函数**（路径怎么拼、JSON 怎么解），但真机上坏掉的从来不是纯函数：
 *  - 界面显示「查询失败：null」——网络调用跑在主线程；
 *  - 拼出来的路径 404——baseUrl 带 `/v1` 而接口在根路径。
 *
 * 这两条都必须用「真的发一次请求」才能证明。所以这里起一个本地 HTTP 服务，
 * 让它按真实供应商的行为回：`/v1/user/balance` → 404，`/user/balance` → 200 + 真实 JSON。
 *
 * 断言对象是**用户看到的结果**：成功时显示金额、失败时说人话、401 不反复重试。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private const val DEEPSEEK_JSON =
    """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",""" +
        """"granted_balance":"10.00","topped_up_balance":"100.00"}]}"""

/** 记下每一次被请求的路径，用来断言「试了几次、按什么顺序」。 */
private val hits = Collections.synchronizedList(mutableListOf<String>())

/**
 * @param rootStatus 根路径（`/user/balance`）返回的状态码。
 * @param delayMs handler 里的延迟，用于构造「来不及返回就被取消」。
 */
private fun startServer(rootStatus: Int = 200, delayMs: Long = 0): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        val path = ex.requestURI.path
        hits += path
        if (delayMs > 0) Thread.sleep(delayMs)
        // 模拟官方行为：OpenAI 兼容前缀下没有余额接口。
        val (code, body) = when {
            path.endsWith("/v1/user/balance") -> 404 to """{"error":"not found"}"""
            path.endsWith("/user/balance") -> rootStatus to
                if (rootStatus == 200) DEEPSEEK_JSON else """{"error":"invalid api key"}"""
            else -> 404 to ""
        }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) {
            ex.sendResponseHeaders(code, -1)
        } else {
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
    server.start()
    return server
}

private fun portOf(server: HttpServer): Int = server.address.port

fun main() {
    println("=== 查余额：端到端（真 HTTP + 真 OkHttp）===")

    check("成功：baseUrl 带 /v1 时 404 后自动换根路径，拿到真实余额") {
        hits.clear()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${portOf(server)}/v1"
            val result = runBlocking { BalanceService.query("deepseek", base, "sk-test") }
            val text = result.getOrNull()
                ?: throw AssertionError("本该成功，却失败：${BalanceFailure.describe(result.exceptionOrNull()!!)}")
            assertTrue("没读到金额：$text", "110" in text)
            assertTrue("顺序不对：$hits", hits == listOf("/v1/user/balance", "/user/balance"))
        } finally {
            server.stop(0)
        }
    }

    check("成功：baseUrl 已是根路径时只打一次，不做多余请求") {
        hits.clear()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${portOf(server)}"
            val result = runBlocking { BalanceService.query("deepseek", base, "sk-test") }
            assertTrue("本该成功", result.isSuccess)
            assertTrue("打了 ${hits.size} 次：$hits", hits == listOf("/user/balance"))
        } finally {
            server.stop(0)
        }
    }

    check("401：不换地址重试（换地址也是同样的 401），且文案带状态码") {
        hits.clear()
        val server = startServer(rootStatus = 401)
        try {
            val base = "http://127.0.0.1:${portOf(server)}/v1"
            val result = runBlocking { BalanceService.query("deepseek", base, "sk-bad") }
            assertTrue("401 居然成功了", result.isFailure)
            val text = BalanceFailure.describe(result.exceptionOrNull()!!)
            assertTrue("没带状态码：$text", "401" in text)
            assertTrue("不该出现 null：$text", "null" !in text)
            // /v1 路径先 404（换候选），到根路径 401 就停：共 2 次，不能是 3 次以上。
            assertTrue("重试次数不对：$hits", hits.size == 2)
        } finally {
            server.stop(0)
        }
    }

    check("取消：协程超时中断时抛取消，不伪装成「查询失败」") {
        hits.clear()
        val server = startServer(delayMs = 1500)
        try {
            val base = "http://127.0.0.1:${portOf(server)}/v1"
            var cancelled = false
            try {
                runBlocking { withTimeout(300) { BalanceService.query("deepseek", base, "sk-test") } }
            } catch (c: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue("超时被吞成了普通结果（会显示成「查询失败」）", cancelled)
        } finally {
            server.stop(0)
        }
    }

    check("未提供接口的供应商：直说，不去打网络") {
        hits.clear()
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${portOf(server)}"
            val result = runBlocking { BalanceService.query("openai", base, "sk-test") }
            assertTrue("本该失败", result.isFailure)
            assertTrue("打网络了：$hits", hits.isEmpty())
            val text = BalanceFailure.describe(result.exceptionOrNull()!!)
            assertTrue("没说清是服务商不支持：$text", "未提供余额接口" in text)
        } finally {
            server.stop(0)
        }
    }

    check("失败文案：整条链路上任何一句都不含 null 字面量") {
        // 端口 1 上没有人监听：ConnectException，且 message 在不同 JDK 上形态不一。
        val result = runBlocking { BalanceService.query("deepseek", "http://127.0.0.1:1", "sk-test") }
        assertTrue("本该失败", result.isFailure)
        val text = BalanceFailure.describe(result.exceptionOrNull()!!)
        assertTrue("出现了 null：$text", "null" !in text)
        assertTrue("空文案：$text", text.isNotBlank())
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
