import com.beichen.polaris.core.llm.BalanceFailure
import com.beichen.polaris.core.llm.BalanceParsing
import com.beichen.polaris.core.text.HeaderParsing
import java.io.File

/** 冒充 Android 的同名异常：真实类在 android.jar 里，harness 不加载它。 */
private class NetworkOnMainThreadException : RuntimeException()

/** 读仓库内的源文件（CWD 是仓库根，由 run-*.sh 保证）。 */
private fun readRepoFile(rel: String): String {
    val f = File(rel)
    if (!f.isFile) throw AssertionError("找不到 $rel（CWD=${File(".").absolutePath}）")
    return f.readText()
}

/**
 * W-29 harness：自定义请求头/请求体解析 + 余额查询解析（纯 JVM）。
 *
 * 重点：
 *  - 请求头解析：注释/空行跳过、值里的冒号保留、头名只留可打印 ASCII、同名后者覆盖；
 *  - 请求体校验：空通过、非对象/坏 JSON 拒绝；
 *  - 余额解析：覆盖 DeepSeek / Kimi / 硅基流动 / OpenRouter 的真实响应形状，
 *    以及"字段缺失 → null"（不编造数字）。
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

fun main() {
    println("=== W-29 自定义参数 + 余额解析 ===")

    // ---------- 请求头 ----------
    check("解析：基本 Key: Value，空格可有可无") {
        val h = HeaderParsing.parseHeaderLines("HTTP-Referer: https://a.com\nX-Title:AgentKit")
        assertTrue("2 条", h.size == 2)
        assertTrue("值含冒号保留", h["HTTP-Referer"] == "https://a.com")
        assertTrue("无空格也行", h["X-Title"] == "AgentKit")
    }

    check("解析：注释与空行跳过") {
        val h = HeaderParsing.parseHeaderLines("# 注释\n\n  \nA: 1")
        assertTrue("只剩 1 条", h.size == 1)
        assertTrue("值", h["A"] == "1")
    }

    check("解析：无冒号的行跳过") {
        assertTrue("跳过", HeaderParsing.parseHeaderLines("just-text-without-colon").isEmpty())
        assertTrue("冒号在开头也跳过", HeaderParsing.parseHeaderLines(": value").isEmpty())
    }

    check("解析：同名后者覆盖") {
        val h = HeaderParsing.parseHeaderLines("A: 1\nA: 2")
        assertTrue("覆盖", h["A"] == "2" && h.size == 1)
    }

    check("解析：头名清洗成 ASCII（中文/空格被剔除）") {
        val h = HeaderParsing.parseHeaderLines("X 测试 头: v")
        assertTrue("键非空且无中文", h.keys.single().all { it.code in 0x21..0x7E })
    }

    check("render 回显与 parse 往返一致") {
        val text = "A: 1\nB: two"
        assertTrue("往返", HeaderParsing.renderHeaderLines(HeaderParsing.parseHeaderLines(text)) == text)
    }

    // ---------- 请求体 ----------
    check("请求体：空通过") {
        assertTrue("空", HeaderParsing.validateCustomBody("") == null)
        assertTrue("空白", HeaderParsing.validateCustomBody("  \n ") == null)
    }

    check("请求体：JSON 对象通过") {
        assertTrue("简单", HeaderParsing.validateCustomBody("""{"top_k": 40}""") == null)
        assertTrue("嵌套", HeaderParsing.validateCustomBody("""{"a":{"b":1}}""") == null)
    }

    check("请求体：数组/标量/坏 JSON 拒绝") {
        assertTrue("数组", HeaderParsing.validateCustomBody("[1,2]") != null)
        assertTrue("字符串", HeaderParsing.validateCustomBody("\"x\"") != null)
        assertTrue("坏 JSON", HeaderParsing.validateCustomBody("{a:1}") != null)
        assertTrue("截断", HeaderParsing.validateCustomBody("""{"a":""") != null)
    }

    // ---------- 余额 ----------
    check("端点映射：四家支持，其余 null") {
        assertTrue("deepseek", BalanceParsing.endpointOf("deepseek")?.path == "/user/balance")
        assertTrue("kimi", BalanceParsing.endpointOf("moonshot")?.path == "/users/me/balance")
        assertTrue("硅基流动", BalanceParsing.endpointOf("siliconflow")?.path == "/user/info")
        assertTrue("openrouter", BalanceParsing.endpointOf("openrouter")?.path == "/credits")
        assertTrue("openai 不支持", BalanceParsing.endpointOf("openai") == null)
        assertTrue("未知不支持", BalanceParsing.endpointOf("whatever") == null)
    }

    check("DeepSeek：balance_infos 提取金额与币种") {
        val json = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}
            ]}
        """.trimIndent()
        val out = BalanceParsing.parse("deepseek", json)
        assertTrue("含金额", out != null && out.contains("110.00"))
        assertTrue("含货币", out!!.contains("元"))
    }

    check("DeepSeek：字段缺失 → null") {
        assertTrue("空对象", BalanceParsing.parse("deepseek", "{}") == null)
        assertTrue("缺 total", BalanceParsing.parse("deepseek", """{"balance_infos":[{"currency":"CNY"}]}""") == null)
    }

    check("Kimi：available_balance（数字与字符串两种）") {
        assertTrue(
            "数字",
            BalanceParsing.parse("moonshot", """{"data":{"available_balance":12.34}}""")?.contains("12.34") == true
        )
        assertTrue(
            "字符串",
            BalanceParsing.parse("kimi", """{"data":{"available_balance":"9.99"}}""")?.contains("9.99") == true
        )
    }

    check("硅基流动：balance 字段") {
        val out = BalanceParsing.parse("siliconflow", """{"code":20000,"data":{"balance":"12.34"}}""")
        assertTrue("含金额", out?.contains("12.34") == true)
    }

    check("OpenRouter：额度 - 已用 = 余额") {
        val out = BalanceParsing.parse(
            "openrouter",
            """{"data":{"total_credits":10.0,"total_usage":3.5}}"""
        )
        assertTrue("余额 6.50", out?.contains("6.50") == true)
        assertTrue("含额度", out!!.contains("10.0"))
    }

    check("未知供应商 → null（不编数字）") {
        assertTrue("null", BalanceParsing.parse("openai", """{"balance":"1"}""") == null)
    }

    // ---------- 余额接口地址（2026-11-22 真机「查不了」） ----------
    check("地址：baseUrl 带 /v1 时，先原样拼、再补根路径候选") {
        val urls = BalanceParsing.candidateUrls("https://api.deepseek.com/v1", "/user/balance")
        assertTrue("应有 2 个候选：$urls", urls.size == 2)
        assertTrue("第一个必须是用户填的地址：$urls", urls[0] == "https://api.deepseek.com/v1/user/balance")
        assertTrue("第二个要剥掉 /v1：$urls", urls[1] == "https://api.deepseek.com/user/balance")
    }

    check("地址：baseUrl 已是根路径时只有一个候选，不做无意义重试") {
        val urls = BalanceParsing.candidateUrls("https://api.deepseek.com", "/user/balance")
        assertTrue("应只有 1 个：$urls", urls.size == 1)
        assertTrue(urls[0], urls[0] == "https://api.deepseek.com/user/balance")
    }

    check("地址：末尾斜杠不会拼出双斜杠") {
        val urls = BalanceParsing.candidateUrls("https://api.deepseek.com/v1/", "/user/balance")
        assertTrue("出现了 //：$urls", urls.none { "com//" in it })
        assertTrue("仍然 2 个候选", urls.size == 2)
    }

    check("地址：自建中转也剥它自己的 /v1，不写死官方域名") {
        val urls = BalanceParsing.candidateUrls("https://proxy.io/deepseek/v1", "/user/balance")
        assertTrue("$urls", urls.size == 2)
        assertTrue("剥的是代理的 /v1：$urls", urls[1] == "https://proxy.io/deepseek/user/balance")
    }

    check("地址：/v1beta 或 /v1/xxx 不许被误剥") {
        val beta = BalanceParsing.candidateUrls("https://x.io/v1beta", "/credits")
        assertTrue("/v1beta 被剥了：$beta", beta.size == 1 && beta[0] == "https://x.io/v1beta/credits")
        val deeper = BalanceParsing.candidateUrls("https://x.io/v1/openai", "/credits")
        assertTrue("中间的 /v1 被剥了：$deeper", deeper.size == 1)
    }

    check("地址：path 是绝对 URL 时原样返回，不拼 baseUrl") {
        val urls = BalanceParsing.candidateUrls("https://x.io/v1", "https://other.io/balance")
        assertTrue("$urls", urls == listOf("https://other.io/balance"))
    }

    check("地址：baseUrl 为空返回空列表（调用方要当作失败处理，不能拼出相对地址）") {
        assertTrue("空串", BalanceParsing.candidateUrls("", "/user/balance").isEmpty())
        assertTrue("纯空白", BalanceParsing.candidateUrls("   ", "/user/balance").isEmpty())
        assertTrue("只有斜杠", BalanceParsing.candidateUrls("/", "/user/balance").isEmpty())
    }

    check("地址：候选之间不重复（剥完相同就不再加）") {
        listOf(
            "https://api.deepseek.com",
            "https://api.deepseek.com/v1",
            "https://api.deepseek.com/v1/",
            "http://192.168.1.9:8080"
        ).forEach { base ->
            val urls = BalanceParsing.candidateUrls(base, "/user/balance")
            assertTrue("$base 出现重复候选：$urls", urls.size == urls.toSet().size)
        }
    }

    // ---------- F 组：失败文案（「查询失败：null」回归）----------
    //
    // 真机截图：点「查余额」显示「查询失败：null」。根因是 query() 没切 IO 线程，
    // Android 抛 NetworkOnMainThreadException（无 message），UI 又拼了 it.message。
    // 下面每条都对着「用户屏幕上会出现什么」断言，而不是对着实现细节。

    check("文案：message 为 null 时不得出现 null 字面量") {
        val text = BalanceFailure.describe(java.io.IOException())
        assertTrue("出现了 null：$text", "null" !in text)
        assertTrue("不该是空白：$text", text.isNotBlank())
    }

    check("文案：message 就是字面量 \"null\" 也当作没有") {
        val text = BalanceFailure.describe(java.io.IOException("null"))
        assertTrue("把 null 当成了原因：$text", "null" !in text)
    }

    check("文案：主线程网络异常要说「与网络无关」，别让用户去换 WiFi") {
        val text = BalanceFailure.describe(NetworkOnMainThreadException())
        assertTrue("没说主线程：$text", "主线程" in text)
        assertTrue("应点明与网络无关：$text", "与网络无关" in text)
    }

    check("文案：协程取消不等于失败") {
        val text = BalanceFailure.describe(java.util.concurrent.CancellationException())
        assertTrue("$text", "取消" in text)
        assertTrue("$text", "失败" !in text)
        assertTrue("$text", "null" !in text)
    }

    check("文案：域名解析失败要指向地址/网络，不指向余额接口") {
        val text = BalanceFailure.describe(java.net.UnknownHostException("api.deepseek.com"))
        assertTrue("$text", "域名解析" in text)
        assertTrue("应带上主机名：$text", "api.deepseek.com" in text)
    }

    check("文案：超时只说一次「超时」，不许拼成「超时：timeout」") {
        val text = BalanceFailure.describe(java.net.SocketTimeoutException("timeout"))
        assertTrue("$text", "超时" in text)
        assertTrue("重复拼了：$text", "超时：timeout" !in text)
    }

    check("文案：HTTP 状态码原样保留（401/404 是排查的关键信息）") {
        val t = IllegalStateException("HTTP 401 @ https://api.deepseek.com/user/balance：{\"error\":\"invalid key\"}")
        val text = BalanceFailure.describe(t)
        assertTrue("$text", "HTTP 401" in text)
    }

    check("文案：自己抛的中文原因不被改写") {
        val text = BalanceFailure.describe(IllegalStateException("响应里没有可识别的余额字段"))
        assertTrue("$text", "响应里没有可识别的余额字段" in text)
    }

    check("文案：认不出的异常至少要说清是哪个类抛的") {
        val text = BalanceFailure.describe(RuntimeException())
        assertTrue("没有类名：$text", "RuntimeException" in text)
        assertTrue("$text", "null" !in text)
    }

    check("文案：超长 message 截断，不把整段 JSON 顶到屏幕上") {
        val text = BalanceFailure.describe(IllegalStateException("x".repeat(500)))
        assertTrue("没截断：${text.length}", text.length < 200)
        assertTrue("$text", text.endsWith("…"))
    }

    check("结构：BalanceService 的网络调用必须在 Dispatchers.IO 里") {
        val src = readRepoFile("core/src/main/java/com/beichen/polaris/core/llm/BalanceService.kt")
        val io = src.indexOf("withContext(Dispatchers.IO)")
        // 2026-11-26 起网络调用走 runCancellable（可取消），不再是裸 execute()。
        // 「必须在 IO 线程上」这条约束不变，判据跟着改；另外补一条「必须可取消」。
        val call = src.indexOf("runCancellable(")
        assertTrue("没有 withContext(Dispatchers.IO)：主线程会抛 NetworkOnMainThreadException", io >= 0)
        assertTrue("没走 runCancellable：点停止会等它自己读到超时", call >= 0)
        assertTrue("网络调用在切线程之前：仍然阻塞主线程", io < call)
        assertTrue("不该再用 runCatching（会吞 CancellationException）", "runCatching {" !in src)
    }

    check("结构：界面不许直接拼异常的 message") {
        val ui = readRepoFile("app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt")
        assertTrue("还在拼 it.message：" + "「查询失败：\${it.message}」", "查询失败：\${it.message}" !in ui)
        assertTrue("失败文案应走 queryText（内部走 BalanceFailure）", "BalanceService.queryText" in ui)
    }

    check("结构：query 在取消时 rethrow，不把取消写成失败") {
        val src = readRepoFile("core/src/main/java/com/beichen/polaris/core/llm/BalanceService.kt")
        assertTrue("没有 catch CancellationException", "catch (cancel: CancellationException)" in src)
        assertTrue("catch 分支里没有 rethrow", Regex("catch \\(cancel: CancellationException\\) \\{\\s*throw cancel").containsMatchIn(src))
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
