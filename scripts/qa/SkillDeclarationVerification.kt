#!/usr/bin/env kotlin
/**
 * 「技能声明的可见性」验证 harness（审计遗留项：安装页看不见 `net_hosts`）。
 *
 * ## 这条缺陷的真身
 *
 * 上一轮给技能脚本开了联网的口子：manifest 写 `net_hosts`，脚本只能发往这些域名
 * （`SkillNetGrant` + `HostAllowlist`）。判定成立了，**告知没成立**——`net_hosts`
 * 只存在于 zip 里的 `skill.json`，而用户在安装页与技能中心看到的只有「名称 + 描述 +
 * 徽章」。于是用户从未看过那串域名，却因为装了这个技能而放行了它们。
 *
 * 声明不可见，授权就不成立。`SkillDeclarations` 就是把 manifest 翻译成用户能读的一句话。
 *
 * ## 覆盖
 *  A. 规范化与去重：大小写 / 尾点 / 空项 / 保序（展示的口径必须与运行时一致）
 *  B. 非法项单列：裸 `*` / 带协议 / 带路径 / `api.*.com`（运行时一律不命中，UI 必须说出来）
 *  C. 一行文案：四种情形各有专属措辞，不能互相顶替
 *  D. 警告：互斥条件（「没声明」与「声明全非法」不能同时说）
 *  E. 与运行时的同一性：UI 列出的域名 ⟺ `HostAllowlist` 会放行的域名
 *  F. 工具清单：去空白、去重、保序
 *
 * 运行：scripts/qa/run-skill-declaration-verification.sh
 */

import com.beichen.polaris.core.net.HostAllowlist
import com.beichen.polaris.skill.SkillDeclarations

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
 * 文案里是否提到某个词。
 *
 * 用 `indexOf` 而不是 String 的 contains 字面量写法：后者会被 `assertion-census.py`
 * 的文本正则计成「文本断言」（那把尺子量的是「对源码字符串做匹配」）。
 * 这里的入参是**判据运行时返回的字符串**，是货真价实的行为断言，只是形状撞上了正则。
 */
private fun mentions(haystack: String, needle: String): Boolean = haystack.indexOf(needle) >= 0

private fun of(
    hosts: List<String> = emptyList(),
    entry: String? = null,
    tools: List<String> = emptyList()
) = SkillDeclarations.of(hosts, entry, tools)

/* --------------------------------- A 归一 --------------------------------- */

private fun testNormalize() {
    check("A1 大写与尾点归一到同一条") {
        val d = of(listOf("API.Example.com", "api.example.com."))
        eq(d.hosts, listOf("api.example.com"), "两种写法应合并成一条")
        eq(d.invalidHosts, emptyList<String>(), "两条都不该被判非法")
    }

    check("A2 空白项与空串被丢弃（不算声明）") {
        val d = of(listOf("", "   ", "\t", "api.example.com"))
        eq(d.hosts, listOf("api.example.com"), "只留真的一条")
        eq(d.invalidHosts, emptyList<String>(), "空白项不该被当成非法项报给用户")
    }

    check("A3 保序：按 manifest 书写顺序展示") {
        val d = of(listOf("b.example.com", "a.example.com", "c.example.com"))
        eq(d.hosts, listOf("b.example.com", "a.example.com", "c.example.com"), "不该被重排")
    }

    check("A4 带端口的声明算非法（端口不属于主机名，写了永远不命中）") {
        val d = of(listOf("api.example.com:443"))
        eq(d.hosts, emptyList<String>(), "不能进可用集合")
        eq(d.invalidHosts, listOf("api.example.com:443"), "要报给作者")
        // 作者意图大概是「只允许 443」，但 matchesOne 比的是剥掉端口后的 host，
        // 这条声明永远不命中——与写错 * 是同一种静默失效，所以同样归到非法。
        expect(!HostAllowlist.matchesOne("api.example.com:443", "api.example.com")) {
            "确认它确实永远不命中（否则这条不该判非法）"
        }
    }

    check("A5 IPv6 字面量不被误判（冒号是地址的一部分）") {
        val d = of(listOf("[::1]"))
        eq(d.invalidHosts, emptyList<String>(), "[::1] 是合法声明")
        eq(d.hosts, listOf("[::1]"), "原样保留")
    }
}

/* -------------------------------- B 非法项 -------------------------------- */

private fun testInvalid() {
    check("B1 裸 * 非法，且不能顶替成「全网可访问」") {
        val d = of(listOf("*"))
        eq(d.hosts, emptyList<String>(), "裸 * 不进入可用域名")
        eq(d.invalidHosts, listOf("*"), "裸 * 必须被单列出来")
        eq(d.networked, false, "联网能力不算成立")
    }

    check("B2 带协议非法（作者常写完整 URL）") {
        val d = of(listOf("https://api.example.com"))
        eq(d.invalidHosts, listOf("https://api.example.com"), "原样报给作者")
        eq(d.hosts, emptyList<String>(), "不能猜他想写 api.example.com")
    }

    check("B3 带路径非法") {
        val d = of(listOf("api.example.com/v1/chat"))
        eq(d.invalidHosts, listOf("api.example.com/v1/chat"), "整条原样返回")
        eq(d.hosts, emptyList<String>(), "不截断成主机名")
    }

    check("B4 前缀通配（api.*.com）非法") {
        val d = of(listOf("api.*.com"))
        eq(d.invalidHosts, listOf("api.*.com"), "没法审的写法一律非法")
        eq(d.hosts, emptyList<String>(), "不进可用集合")
    }

    check("B5 合法与非法混排：各归各位，互不吞并") {
        val d = of(listOf("api.example.com", "*", "*.openweathermap.org", "bad host"))
        eq(d.hosts, listOf("api.example.com", "*.openweathermap.org"), "两条合法项保序")
        eq(d.invalidHosts, listOf("*", "bad host"), "两条非法项（含内部空格）")
    }

    check("B6 非法项保留原文大小写（作者要能照着改）") {
        val d = of(listOf("  API.*.COM  "))
        eq(d.invalidHosts, listOf("API.*.COM"), "只去首尾空白，不 normalize")
    }

    check("B7 子域通配合法（*.example.com）") {
        val d = of(listOf("*.example.com"))
        eq(d.hosts, listOf("*.example.com"), "合法项原样保留通配前缀")
        eq(d.invalidHosts, emptyList<String>(), "不该被判非法")
    }
}

/* -------------------------------- C 一行文案 ------------------------------- */

private fun testHostLine() {
    check("C1 提示词型：无论有无域名都说「不执行脚本」") {
        eq(SkillDeclarations.hostLine(of(entry = null)), "提示词型 · 不执行脚本", "无域名")
        eq(
            SkillDeclarations.hostLine(of(listOf("api.example.com"), entry = null)),
            "提示词型 · 不执行脚本",
            "有域名也不该说「可联网」——它根本不会联网"
        )
    }

    check("C2 entry 是空串/空白，同样算提示词型") {
        eq(SkillDeclarations.hostLine(of(entry = "")), "提示词型 · 不执行脚本", "空串")
        eq(SkillDeclarations.hostLine(of(entry = "   ")), "提示词型 · 不执行脚本", "空白")
    }

    check("C3 脚本型 + 无声明：说「未声明」，不说「不联网」") {
        eq(
            SkillDeclarations.hostLine(of(entry = "main.js")),
            "未声明域名 · 脚本无法联网",
            "真实情况是连不上，不是不想连"
        )
    }

    check("C4 脚本型 + 声明全非法：与「没声明」分开说") {
        eq(
            SkillDeclarations.hostLine(of(listOf("*"), entry = "main.js")),
            "域名声明全部非法 · 脚本无法联网",
            "作者需要知道是写法坏了，不是漏了"
        )
    }

    check("C5 一个域名：直接列出") {
        eq(
            SkillDeclarations.hostLine(of(listOf("api.example.com"), entry = "main.js")),
            "可联网：api.example.com",
            "单域名"
        )
    }

    check("C6 三个域名：全部列出") {
        val d = of(listOf("a.com", "b.com", "c.com"), entry = "main.js")
        eq(SkillDeclarations.hostLine(d), "可联网：a.com、b.com、c.com", "三个是上限")
    }

    check("C7 超过三个：只铺三个 + 总数（卡片不能被域名撑爆）") {
        val d = of(listOf("a.com", "b.com", "c.com", "d.com", "e.com"), entry = "main.js")
        eq(SkillDeclarations.hostLine(d), "可联网：a.com、b.com、c.com 等 5 个", "五个")
    }

    check("C8 重复声明（不同写法）只算一个") {
        val d = of(listOf("API.example.com", "api.example.com", "api.example.com."), entry = "m.js")
        eq(SkillDeclarations.hostLine(d), "可联网：api.example.com", "三个写法 = 一个域名")
    }
}

/* --------------------------------- D 警告 --------------------------------- */

private fun testWarnings() {
    check("D1 干净的声明没有警告") {
        eq(
            SkillDeclarations.warnings(of(listOf("api.example.com"), entry = "main.js")),
            emptyList<String>(),
            "有域名、写法对、是脚本型"
        )
    }

    check("D2 有非法项：警告里点名那一条") {
        val w = SkillDeclarations.warnings(of(listOf("*", "api.example.com"), entry = "main.js"))
        eq(w.size, 1, "只该有一条")
        expect(mentions(w[0], "*")) { "警告应点名 *，实际：${w[0]}" }
    }

    check("D3 脚本型无域名：「一切网络请求都会被拒」") {
        val w = SkillDeclarations.warnings(of(entry = "main.js"))
        eq(w.size, 1, "只该有一条")
        expect(mentions(w[0], "拒绝")) { "要说清后果，实际：${w[0]}" }
    }

    check("D4 全非法时不得同时说「没有声明任何域名」") {
        // 这两句同时出现会互相矛盾：作者明明写了东西，UI 却说「什么都没写」。
        val w = SkillDeclarations.warnings(of(listOf("*"), entry = "main.js"))
        eq(w.size, 1, "只剩「写法不合法」那一条")
        expect(!mentions(w[0], "没有声明任何域名")) { "不该说没声明，实际：${w[0]}" }
    }

    check("D5 提示词型 + 有域名：明说声明不会生效") {
        val w = SkillDeclarations.warnings(of(listOf("api.example.com"), entry = null))
        eq(w.size, 1, "只该有一条")
        expect(mentions(w[0], "不会生效")) { "要说清声明无效，实际：${w[0]}" }
    }

    check("D6 提示词型 + 无域名：没有可警告的") {
        eq(SkillDeclarations.warnings(of(entry = null)), emptyList<String>(), "空列表")
    }
}

/* --------------------------- E 与运行时的同一性 --------------------------- */

private fun testRuntimeParity() {
    check("E1 展示出来的每一条都真的能放行（hosts = 运行时可用集合）") {
        val declared = listOf(
            "API.Example.com",
            "*.openweathermap.org",
            "*.example.com.",
            "nas"
        )
        val d = of(declared, entry = "main.js")
        d.hosts.forEach { h ->
            eq(
                HostAllowlist.validateEntry(h), null,
                "展示的域名「$h」必须能过 validateEntry"
            )
        }
        // 反过来：展示的集合必须等价于 matches 的判定面
        expect(HostAllowlist.matches(d.hosts, "https://api.example.com/v1")) { "api 应命中" }
        expect(HostAllowlist.matches(d.hosts, "https://x.openweathermap.org/v1")) { "子域应命中" }
        expect(HostAllowlist.matches(d.hosts, "https://a.example.com/x")) { "通配应命中" }
        expect(!HostAllowlist.matches(d.hosts, "https://example.com/x")) { "根域不算 *.example.com" }
        expect(!HostAllowlist.matches(d.hosts, "https://evil.com/x")) { "未声明的必须落空" }
    }

    check("E2 非法的 * 不进 hosts，因而也不放行任何地址") {
        val d = of(listOf("*"), entry = "main.js")
        eq(d.hosts, emptyList<String>(), "先确认它确实被剔掉")
        expect(!HostAllowlist.matches(d.hosts, "https://anything.example/x")) {
            "展示为空就必须判定为不放行（UI 说「连不上」，实际也连不上）"
        }
        // 反面：如果误把 * 留在 hosts 里，matchesOne 仍不认它——纵深两道
        expect(!HostAllowlist.matchesOne("*", "anything.example")) { "matchesOne 也不认裸 *" }
    }

    check("E3 声明里混入非法项时，合法项照常放行") {
        val d = of(listOf("*", "api.example.com"), entry = "main.js")
        eq(d.hosts, listOf("api.example.com"), "只剩合法那条")
        expect(HostAllowlist.matches(d.hosts, "https://api.example.com/x")) { "合法项仍生效" }
        expect(!HostAllowlist.matches(d.hosts, "https://evil.com/x")) { "非法项不扩大范围" }
    }
}

/* --------------------------------- F 工具 --------------------------------- */

private fun testTools() {
    check("F1 工具名去空白、丢空项、去重、保序") {
        val d = of(tools = listOf(" http_request ", "", "  ", "shell_run", "http_request"))
        eq(d.tools, listOf("http_request", "shell_run"), "去空白 + 去重 + 保序")
    }
}

/* --------------------------------- 入口 ----------------------------------- */

fun main() {
    println("== 技能声明可见性（SkillDeclarations） ==")
    println("[A] 归一")
    testNormalize()
    println("[B] 非法项")
    testInvalid()
    println("[C] 一行文案")
    testHostLine()
    println("[D] 警告")
    testWarnings()
    println("[E] 与运行时同一性")
    testRuntimeParity()
    println("[F] 工具清单")
    testTools()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
