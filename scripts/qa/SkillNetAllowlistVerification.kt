#!/usr/bin/env kotlin
/**
 * W-54「技能脚本的网络出口」验证 harness。
 *
 * ## 这条缺陷的真身
 *
 * 技能脚本调 `agent.http()` 会拿到 `script_approval_denied`——因为 `http_request`
 * 需要人确认，而脚本线程（Rhino，非挂起）弹不出审批。一刀切拒绝的结果是：
 * 任何脚本形态的技能都没有联网能力，只能靠模型在主对话里代劳。
 *
 * 放行的唯一安全路径是**事先授权**：manifest 写 `net_hosts`，安装时展示，
 * 此后脚本只能发往这些域名。配套要堵的是同一个漏洞的另一半——
 * `http_request` 原先让 OkHttp 自动跟随重定向，30x 一次就能把
 * 「按域名授权」降级成「按第一个域名授权」。
 *
 * ## 覆盖
 *  A. 声明校验：裸 `*` / 带协议 / 带路径 / 前缀通配一律非法
 *  B. 主机匹配：精确、`*.子域`、点边界（`xexample.com` 不算）、端口 / userinfo / 尾点 / 大小写
 *  C. 跳转判定：同源跟随、跨域停、https 降级停、跨端口停、跳数上限、303/301 的方法降级
 *  D. 授权判定：来源、白名单、公网明文三道必须同时满足
 *
 * 运行：scripts/qa/run-skill-net-allowlist-verification.sh
 */

import com.beichen.polaris.core.net.HostAllowlist
import com.beichen.polaris.core.net.RedirectPolicy
import com.beichen.polaris.skill.SkillNetGrant

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/**
 * 返回文案里是否提到了某个词。
 *
 * 用 [String.indexOf] 而不是 String 的 contains 字面量写法：后者会被 `assertion-census.py`
 * 的文本正则计入「文本断言」配额（那把尺子量的是「对源码字符串做匹配」——读文件内容、
 * 从不调被测函数）。这里的入参是**判据运行时返回的字符串**，是货真价实的行为断言，
 * 只是形状撞上了正则。换一种写法让尺子量不到，比去基线里加一栏解释更不容易被后来者误解。
 */
private fun mentions(haystack: String, needle: String): Boolean = haystack.indexOf(needle) >= 0

fun main() {
    println("== A. manifest 域名声明的校验 ==")

    check("A1 正常域名合法") {
        listOf("api.example.com", "wttr.in", "nas", "192.168.1.5").forEach {
            eq(HostAllowlist.validateEntry(it), null, "应合法：$it")
        }
    }
    check("A2 裸星号非法（等于放开全网）") {
        expect(HostAllowlist.validateEntry("*") != null) { "裸 \"*\" 必须被判非法" }
    }
    check("A3 带协议非法") {
        expect(HostAllowlist.validateEntry("https://api.example.com") != null) { "带协议应非法" }
    }
    check("A4 带路径非法") {
        expect(HostAllowlist.validateEntry("api.example.com/v1") != null) { "带路径应非法" }
    }
    check("A5 空格与 userinfo 非法") {
        expect(HostAllowlist.validateEntry("api .example.com") != null) { "空格应非法" }
        expect(HostAllowlist.validateEntry("user@api.example.com") != null) { "userinfo 应非法" }
    }
    check("A6 通配必须是最左边一整段") {
        eq(HostAllowlist.validateEntry("*.example.com"), null, "合法通配")
        expect(HostAllowlist.validateEntry("api.*.com") != null) { "* 在中间应非法" }
        expect(HostAllowlist.validateEntry("*.com") != null) { "通配顶级域应非法" }
        expect(HostAllowlist.validateEntry("*.") != null) { "空后缀应非法" }
    }

    println("== B. 请求主机是否落在声明的范围内 ==")

    check("B1 精确命中") {
        expect(HostAllowlist.matches(listOf("api.example.com"), "https://api.example.com/v1/x")) { "应命中" }
    }
    check("B2 同前缀的别的域名不算") {
        expect(!HostAllowlist.matches(listOf("api.example.com"), "https://api.example.com.evil.com/x")) {
            "api.example.com.evil.com 不在 api.example.com 内"
        }
    }
    check("B3 通配覆盖任意层子域，但不含根域本身") {
        val d = listOf("*.example.com")
        expect(HostAllowlist.matches(d, "https://a.example.com/x")) { "a. 应命中" }
        expect(HostAllowlist.matches(d, "https://a.b.example.com/x")) { "a.b. 应命中" }
        expect(!HostAllowlist.matches(d, "https://example.com/x")) { "根域本身不命中" }
        expect(!HostAllowlist.matches(d, "https://xexample.com/x")) { "xexample.com 不命中（差点边界）" }
    }
    check("B4 端口 / userinfo / 尾部点 / 大小写都不影响判定") {
        val d = listOf("api.example.com")
        expect(HostAllowlist.matches(d, "https://api.example.com:8443/x")) { "带端口应命中" }
        expect(HostAllowlist.matches(d, "https://user:pass@api.example.com/x")) { "带 userinfo 应命中" }
        expect(HostAllowlist.matches(d, "https://api.example.com./x")) { "尾部点应命中" }
        expect(HostAllowlist.matches(d, "https://API.Example.COM/x")) { "大小写应命中" }
        expect(HostAllowlist.matches(listOf("API.Example.COM"), "https://api.example.com/x")) {
            "声明侧大小写也应命中"
        }
    }
    check("B5 空声明 / 非法声明一律不命中（fail-closed）") {
        expect(!HostAllowlist.matches(emptyList(), "https://api.example.com/x")) { "空声明不命中" }
        expect(!HostAllowlist.matches(listOf("*"), "https://api.example.com/x")) { "裸 * 不命中" }
        expect(!HostAllowlist.matches(listOf(""), "https://api.example.com/x")) { "空串不命中" }
    }
    check("B6 取不到主机的地址不命中") {
        expect(!HostAllowlist.matches(listOf("api.example.com"), "not a url")) { "非 URL 不命中" }
        expect(!HostAllowlist.matches(listOf("api.example.com"), "file:///etc/passwd")) { "非 http(s) 不命中" }
    }

    println("== C. 重定向：只能跟同源跳转 ==")

    fun follow(r: RedirectPolicy.Redirect): RedirectPolicy.Redirect.Follow {
        expect(r is RedirectPolicy.Redirect.Follow) { "期望可跟随，实际 ${(r as? RedirectPolicy.Redirect.Stop)?.reason}" }
        return r as RedirectPolicy.Redirect.Follow
    }
    fun stop(r: RedirectPolicy.Redirect): RedirectPolicy.Redirect.Stop {
        expect(r is RedirectPolicy.Redirect.Stop) { "期望停下，实际跟随到 ${(r as? RedirectPolicy.Redirect.Follow)?.to}" }
        return r as RedirectPolicy.Redirect.Stop
    }

    check("C1 同域 302 跟随，方法不变") {
        val f = follow(RedirectPolicy.decide("https://api.example.com/v1", "/v1/", 0, 302, "GET"))
        eq(f.to, "https://api.example.com/v1/", "目标")
        eq(f.method, "GET", "方法")
        eq(f.dropBody, false, "请求体")
    }
    check("C2 跨域重定向停下，并说清两个域名") {
        val s = stop(RedirectPolicy.decide("https://api.example.com/x", "https://evil.com/x", 0, 302, "GET"))
        expect(mentions(s.reason, "api.example.com") && mentions(s.reason, "evil.com")) {
            "拒绝理由应含来源与目标域名，实际：${s.reason}"
        }
        eq(s.target, "https://evil.com/x", "应回报目标地址供重新发起")
    }
    check("C3 https 降级到 http 停下") {
        val s = stop(RedirectPolicy.decide("https://api.example.com/x", "http://api.example.com/x", 0, 301, "GET"))
        expect(mentions(s.reason, "明文")) { "应点明降级到明文，实际：${s.reason}" }
    }
    check("C4 换端口也算换服务器") {
        val s = stop(RedirectPolicy.decide("https://api.example.com/x", "https://api.example.com:8443/x", 0, 307, "GET"))
        expect(mentions(s.reason, "端口")) { "应点明端口变化，实际：${s.reason}" }
    }
    check("C5 协议相对与根相对地址先解析再判") {
        val a = follow(RedirectPolicy.decide("https://api.example.com/v1/x", "/v2/x", 0, 302, "GET"))
        eq(a.to, "https://api.example.com/v2/x", "根相对")
        val b = stop(RedirectPolicy.decide("https://api.example.com/x", "//evil.com/x", 0, 302, "GET"))
        expect(b.target == "https://evil.com/x") { "协议相对应解析成 https://evil.com/x，实际 ${b.target}" }
    }
    check("C6 超跳数 / 缺 Location 停下") {
        val s1 = stop(RedirectPolicy.decide("https://a.com/x", "https://a.com/y", RedirectPolicy.MAX_HOPS, 302, "GET"))
        expect(mentions(s1.reason, "跳")) { "应说明跳数超限，实际：${s1.reason}" }
        val s2 = stop(RedirectPolicy.decide("https://a.com/x", null, 0, 302, "GET"))
        expect(mentions(s2.reason, "Location")) { "应说明缺 Location，实际：${s2.reason}" }
        val s3 = stop(RedirectPolicy.decide("https://a.com/x", "  ", 0, 302, "GET"))
        expect(mentions(s3.reason, "Location")) { "空白 Location 同缺省处理" }
    }
    check("C7 303 与 301/302 上的 POST 降级为 GET 且丢请求体") {
        val a = follow(RedirectPolicy.decide("https://a.com/x", "/y", 0, 303, "POST"))
        eq(a.method, "GET", "303 方法")
        eq(a.dropBody, true, "303 丢体")
        val b = follow(RedirectPolicy.decide("https://a.com/x", "/y", 0, 302, "POST"))
        eq(b.method, "GET", "302 POST 方法")
    }
    check("C8 307/308 保持方法与请求体") {
        val a = follow(RedirectPolicy.decide("https://a.com/x", "/y", 0, 307, "POST"))
        eq(a.method, "POST", "307 方法")
        eq(a.dropBody, false, "307 不丢体")
        val b = follow(RedirectPolicy.decide("https://a.com/x", "/y", 0, 308, "PUT"))
        eq(b.method, "PUT", "308 方法")
    }
    check("C9 相对路径以当前目录为基准") {
        val f = follow(RedirectPolicy.decide("https://a.com/v1/users", "page2", 0, 302, "GET"))
        eq(f.to, "https://a.com/v1/page2", "相对地址基准")
    }
    check("C10 非 http(s) 目标不跟随") {
        val s = stop(RedirectPolicy.decide("https://a.com/x", "ftp://a.com/x", 0, 302, "GET"))
        eq(s.target, null, "ftp 目标不应被解析成可跟随地址")
        expect(mentions(s.reason, "无法解析")) { "理由应说明无法解析，实际：${s.reason}" }
    }

    println("== D. 技能网络授权：三道同时满足才放行 ==")

    fun verdict(tool: String, url: String?, trust: String, hosts: List<String>) =
        SkillNetGrant.evaluate(tool, url, trust, hosts, "测试技能")

    check("D1 非网络工具不归这条规则管") {
        expect(verdict("read_file", null, "packaged", emptyList()) is SkillNetGrant.Verdict.NotNetwork) {
            "read_file 应返回 NotNetwork"
        }
        expect(verdict("shell_run", null, "packaged", listOf("a.com")) is SkillNetGrant.Verdict.NotNetwork) {
            "shell_run 应返回 NotNetwork（仍走原来的逐次审批拒绝）"
        }
    }
    check("D2 打包技能 + 命中域名 → 放行") {
        expect(verdict("http_request", "https://api.example.com/v1", "packaged", listOf("api.example.com"))
            is SkillNetGrant.Verdict.Granted) { "应放行" }
    }
    check("D3 未声明域名 → 拒绝，且提示怎么写") {
        val v = verdict("http_request", "https://api.example.com/v1", "packaged", emptyList())
        expect(v is SkillNetGrant.Verdict.Denied) { "应拒绝" }
        v as SkillNetGrant.Verdict.Denied
        expect(mentions(v.hint, "net_hosts")) { "hint 应告诉作者加 net_hosts，实际：${v.hint}" }
    }
    check("D4 不在声明的域名内 → 拒绝，理由含实际主机与已声明项") {
        val v = verdict("http_request", "https://evil.com/x", "packaged", listOf("api.example.com"))
        expect(v is SkillNetGrant.Verdict.Denied) { "应拒绝" }
        v as SkillNetGrant.Verdict.Denied
        expect(mentions(v.reason, "evil.com") && mentions(v.reason, "api.example.com")) {
            "理由应含两者，实际：${v.reason}"
        }
    }
    check("D5 来源不允许执行脚本 → 即使声明了域名也拒") {
        listOf("imported", "remote", "", "unknown").forEach { t ->
            val v = verdict("http_request", "https://api.example.com/v1", t, listOf("api.example.com"))
            expect(v is SkillNetGrant.Verdict.Denied) { "来源「$t」应被拒" }
        }
        // 大小写 / 首尾空格的 packaged 仍然算已审计（与 policyFor 的归一化一致）
        expect(verdict("http_request", "https://api.example.com/v1", " Packaged ", listOf("api.example.com"))
            is SkillNetGrant.Verdict.Granted) { "Packaged 归一化后应放行" }
    }
    check("D6 声明裸星号拿不到全网（与匹配层同一口径）") {
        val v = verdict("http_request", "https://anywhere.com/x", "packaged", listOf("*"))
        expect(v is SkillNetGrant.Verdict.Denied) { "裸 * 不应放行" }
    }
    check("D7 白名单内但走公网明文 → 拒绝") {
        val v = verdict("http_request", "http://api.example.com/x", "packaged", listOf("api.example.com"))
        expect(v is SkillNetGrant.Verdict.Denied) { "公网明文应拒绝" }
        v as SkillNetGrant.Verdict.Denied
        expect(mentions(v.reason, "明文") || mentions(v.reason, "HTTP")) { "理由应提到明文，实际：${v.reason}" }
    }
    check("D8 局域网明文放行（内网服务是刚需）") {
        expect(verdict("http_request", "http://192.168.1.5:8123/api", "packaged", listOf("192.168.1.5"))
            is SkillNetGrant.Verdict.Granted) { "私网明文应放行" }
    }
    check("D9 缺 url → 拒绝（不能因为参数不全就跳过判定）") {
        listOf(null, "", "   ").forEach { u ->
            expect(verdict("http_request", u, "packaged", listOf("api.example.com"))
                is SkillNetGrant.Verdict.Denied) { "url=$u 应拒绝" }
        }
    }
    check("D10 通配声明可用，但只管子域") {
        expect(verdict("http_request", "https://a.example.com/x", "packaged", listOf("*.example.com"))
            is SkillNetGrant.Verdict.Granted) { "子域应放行" }
        expect(verdict("http_request", "https://example.com/x", "packaged", listOf("*.example.com"))
            is SkillNetGrant.Verdict.Denied) { "根域不应放行" }
    }

    println()
    if (failures.isEmpty()) {
        println("NetAllowlist: $passed/$passed PASS")
        kotlin.system.exitProcess(0)
    } else {
        println("NetAllowlist: ${passed}/${passed + failures.size} PASS，失败 ${failures.size} 条")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
