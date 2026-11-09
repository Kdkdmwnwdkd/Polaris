/**
 * 端点明文策略验证（EndpointPolicy）。
 *
 * 验的是「带凭据的请求会不会被放行到公网明文端点」——这是审计 F6 里唯一能靠纯函数
 * 判定的部分，也是唯一一处「放行」等于「把 Key 明文广播出去」的判定。
 *
 * 三组：
 *   A 形态分类：https / 私网明文 / 公网明文 / 畸形，各自的边界；
 *   B 私网判定：IPv4 四类私网段 + CGNAT + 链路本地、IPv6 回环/ULA/链路本地/映射、
 *     名字后缀、单标签主机名、以及**不许误判成私网**的公网地址；
 *   C 拒绝语义：有凭据才拒、无凭据放行、https 一律放行、畸形不重复报错、文案可读。
 */

var pass = 0
var fail = 0

fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

fun assertTrue(what: String, cond: Boolean) {
    if (!cond) throw AssertionError(what)
}

fun assertEquals(what: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$what：期望 $expected，实际 $actual")
}

private val P = com.beichen.polaris.core.net.EndpointPolicy
private typealias Kind = com.beichen.polaris.core.net.EndpointPolicy.Kind

/* ------------------------------ A 形态分类 ------------------------------ */

fun testKind() {
    println("[A] 形态分类")

    check("A1 https 一律 HTTPS") {
        assertEquals("官方端点", Kind.HTTPS, P.kindOf("https://api.openai.com/v1"))
        assertEquals("大写 scheme", Kind.HTTPS, P.kindOf("HTTPS://API.OPENAI.COM/v1"))
        assertEquals("带端口", Kind.HTTPS, P.kindOf("https://api.example.com:8443/v1"))
        assertEquals("私网 https 也是 https", Kind.HTTPS, P.kindOf("https://192.168.1.5:8443/v1"))
    }

    check("A2 http + 私网 → CLEARTEXT_PRIVATE") {
        assertEquals("回环", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://127.0.0.1:11434/v1"))
        assertEquals("localhost", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://localhost:8080"))
        assertEquals("192.168", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://192.168.1.5:11434/v1"))
        assertEquals("10.x", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://10.0.0.7:8000/v1"))
        assertEquals("单标签", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://nas:8080/v1"))
    }

    check("A3 http + 公网 → CLEARTEXT_PUBLIC") {
        assertEquals("公网域名", Kind.CLEARTEXT_PUBLIC, P.kindOf("http://api.deepseek.com/v1"))
        assertEquals("公网 IP", Kind.CLEARTEXT_PUBLIC, P.kindOf("http://8.8.8.8/v1"))
        assertEquals("中转站", Kind.CLEARTEXT_PUBLIC, P.kindOf("http://one-api.example.com:3000/v1"))
        assertEquals("IPv6 公网", Kind.CLEARTEXT_PUBLIC, P.kindOf("http://[2001:db8::1]:8080/v1"))
    }

    check("A4 畸形一律 MALFORMED（不在这里重复报错）") {
        assertEquals("空串", Kind.MALFORMED, P.kindOf(""))
        assertEquals("全空白", Kind.MALFORMED, P.kindOf("   "))
        assertEquals("无 scheme", Kind.MALFORMED, P.kindOf("192.168.1.5:8080"))
        assertEquals("file:", Kind.MALFORMED, P.kindOf("file:///etc/passwd"))
        assertEquals("ftp:", Kind.MALFORMED, P.kindOf("ftp://example.com/a"))
        assertEquals("content:", Kind.MALFORMED, P.kindOf("content://media/1"))
        assertEquals("无主机", Kind.MALFORMED, P.kindOf("http:///v1"))
        assertEquals("只有 scheme", Kind.MALFORMED, P.kindOf("https://"))
    }

    check("A5 脏输入不影响判定") {
        assertEquals("首尾空白", Kind.CLEARTEXT_PRIVATE, P.kindOf("  http://192.168.1.5:8080/v1  "))
        assertEquals("userinfo", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://u:p@192.168.1.5:8080/v1"))
        assertEquals("问号后不参与", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://10.1.2.3:80/?x=api.openai.com"))
        assertEquals("hash 后不参与", Kind.CLEARTEXT_PRIVATE, P.kindOf("http://10.1.2.3/#api.openai.com"))
    }
}

/* ------------------------------ B 私网判定 ------------------------------ */

fun testPrivateHost() {
    println("[B] 私网判定")

    check("B1 IPv4 四类私网段 + 边界") {
        assertTrue("10.0.0.1", P.isPrivateHost("10.0.0.1"))
        assertTrue("10.255.255.255", P.isPrivateHost("10.255.255.255"))
        assertTrue("172.16.0.1", P.isPrivateHost("172.16.0.1"))
        assertTrue("172.31.255.255", P.isPrivateHost("172.31.255.255"))
        assertTrue("192.168.0.1", P.isPrivateHost("192.168.0.1"))
        assertTrue("127.0.0.1", P.isPrivateHost("127.0.0.1"))
        assertTrue("172.32.0.1 应为公网", !P.isPrivateHost("172.32.0.1"))
        assertTrue("172.15.255.255 应为公网", !P.isPrivateHost("172.15.255.255"))
        assertTrue("11.0.0.1 应为公网", !P.isPrivateHost("11.0.0.1"))
        assertTrue("192.169.0.1 应为公网", !P.isPrivateHost("192.169.0.1"))
    }

    check("B2 链路本地 / CGNAT / 未指定") {
        assertTrue("169.254.169.254 元数据地址", P.isPrivateHost("169.254.169.254"))
        assertTrue("100.64.0.1 CGNAT", P.isPrivateHost("100.64.0.1"))
        assertTrue("100.127.255.254 CGNAT 上界", P.isPrivateHost("100.127.255.254"))
        assertTrue("100.128.0.1 应属公网", !P.isPrivateHost("100.128.0.1"))
        assertTrue("0.0.0.0", P.isPrivateHost("0.0.0.0"))
    }

    check("B3 IPv6") {
        assertTrue("::1", P.isPrivateHost("::1"))
        assertTrue("::", P.isPrivateHost("::"))
        assertTrue("fe80::1", P.isPrivateHost("fe80::1"))
        assertTrue("fc00::1 ULA", P.isPrivateHost("fc00::1"))
        assertTrue("fd12:3456::1 ULA", P.isPrivateHost("fd12:3456::1"))
        assertTrue("2001:db8::1 应属公网", !P.isPrivateHost("2001:db8::1"))
        assertTrue("::ffff:192.168.1.5 映射", P.isPrivateHost("::ffff:192.168.1.5"))
        assertTrue("::ffff:8.8.8.8 映射公网", !P.isPrivateHost("::ffff:8.8.8.8"))
    }

    check("B4 名字形态") {
        assertTrue("localhost", P.isPrivateHost("localhost"))
        assertTrue("app.localhost", P.isPrivateHost("app.localhost"))
        assertTrue("nas.local", P.isPrivateHost("nas.local"))
        assertTrue("router.lan", P.isPrivateHost("router.lan"))
        assertTrue("svc.internal", P.isPrivateHost("svc.internal"))
        assertTrue("svc.home.arpa", P.isPrivateHost("svc.home.arpa"))
        assertTrue("单标签 nas", P.isPrivateHost("nas"))
        assertTrue("带尾点 nas.local.", P.isPrivateHost("nas.local."))
        assertTrue("大写 NAS.LOCAL", P.isPrivateHost("NAS.LOCAL"))
        assertTrue("example.com 应为公网", !P.isPrivateHost("example.com"))
        assertTrue("localhost.example.com 应为公网", !P.isPrivateHost("localhost.example.com"))
    }

    check("B5 非法 IPv4 不被当成私网（回落到名字规则）") {
        assertTrue("999.1.1.1 不是合法 IPv4", !P.isPrivateHost("999.1.1.1"))
        assertTrue("1.2.3 段数不足", !P.isPrivateHost("1.2.3"))
        assertTrue("空串", !P.isPrivateHost(""))
    }
}

/* ------------------------------ C 拒绝语义 ------------------------------ */

fun testRefusal() {
    println("[C] 拒绝语义")

    check("C1 公网明文 + 有凭据 → 拒绝，理由里点名是哪台主机") {
        val why = P.refusalFor("http://api.deepseek.com/v1", sendsCredentials = true)
        assertTrue("应拒绝", why != null)
        val text = why!!
        // 只钉「开头点名主机」这个结构，不钉具体措辞：文案是产品面，改词不该让断言变红
        assertTrue("理由应以「端点「<host>」」开头：$text", text.startsWith("端点「api.deepseek.com」"))
        assertTrue("理由不能是空壳：$text", text.length > 20)
    }

    check("C2 公网明文 + 无凭据 → 放行（拦的是凭据外泄，不是把可用配置打死）") {
        assertEquals("无 Key", null, P.refusalFor("http://api.example.com/v1", sendsCredentials = false))
    }

    check("C3 私网明文一律放行（Ollama / one-api 的日常形态）") {
        assertEquals("回环", null, P.refusalFor("http://127.0.0.1:11434/v1", true))
        assertEquals("192.168", null, P.refusalFor("http://192.168.1.5:8080/v1", true))
        assertEquals("10.x", null, P.refusalFor("http://10.0.0.7/v1", true))
        assertEquals("单标签", null, P.refusalFor("http://nas:8080/v1", true))
    }

    check("C4 https 一律放行") {
        assertEquals("官方", null, P.refusalFor("https://api.openai.com/v1", true))
        assertEquals("公网 IP + https", null, P.refusalFor("https://8.8.8.8/v1", true))
    }

    check("C5 畸形端点不在这里报错（交给「端点未配置」那条独立分支）") {
        assertEquals("空串", null, P.refusalFor("", true))
        assertEquals("无 scheme", null, P.refusalFor("192.168.1.5:8080", true))
        assertEquals("file:", null, P.refusalFor("file:///etc/passwd", true))
    }

    check("C6 请求地址（不是 Base URL）同样能判——model 列表/余额查询走的是完整 URL") {
        assertEquals(
            "完整 URL 公网明文",
            Kind.CLEARTEXT_PUBLIC,
            P.kindOf("http://api.example.com/v1/models?page=1")
        )
        assertEquals(
            "完整 URL 私网明文",
            Kind.CLEARTEXT_PRIVATE,
            P.kindOf("http://192.168.1.5:8080/v1/models")
        )
    }
}

fun main() {
    println("=== 端点明文策略验证（EndpointPolicy）===")
    testKind()
    testPrivateHost()
    testRefusal()
    println()
    println("$pass 通过 / $fail 失败")
    if (fail > 0) kotlin.system.exitProcess(1)
}
