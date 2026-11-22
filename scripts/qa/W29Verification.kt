import com.beichen.polaris.core.llm.BalanceParsing
import com.beichen.polaris.core.text.HeaderParsing

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

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
