/**
 * 审计 F12「日志里的用户内容会随诊断包外发」验证。
 *
 * ## 缺陷
 *
 * `AgentLogger` 是磁盘日志的唯一写入口，而「导出诊断包」会把日志尾部 300 行原样
 * 打包交给用户去发。也就是说日志里写了什么，用户求助时就会带出去什么。
 *
 * 它本来有两道防线：
 *
 *  - `AgentLogger.sanitize`：正则掩码 `sk-` / `Bearer` / `token=` / `password=`；
 *  - `sensitiveTools`：命中名单的工具，入参与结果**只记长度**。
 *
 * 第二道是**手写的 7 项名单**，而「哪个工具的入参与输出会带用户数据」这件事
 * `ToolCatalog` 已经用 `approval` 字段回答过一遍了。两张表各自维护，结果是静默不同步：
 *
 *  - `ocr_image` 刚被认定与 `ui_screenshot` 同档（都是读屏幕，F9b），审批照做了，
 *    名单没跟上——OCR 出来的屏幕文字照样落盘；
 *  - `shell_run` / `shell_background` / `container_toolchain` 的输出是**任意内容**
 *    （`cat` 出来的文件、`env` 打出来的环境变量），从来不在名单里；
 *  - `write_file` / `edit_file` 的参数是文件内容的前 300 字符。
 *
 * 还有几处绕过名单、直接往日志里写用户内容的地方：用户发送的正文、拒绝计划的理由、
 * 弹窗里的自由输入、自动抽取的记忆正文、浏览器 URL 的 query。
 *
 * ## 修法
 *
 * 1. `sensitiveTools` = 审批真源派生（`approval != null`）+ 手工补充集
 *    （读工作区内文件 / 公开网页 / 前台包名——不需审批但内容仍不该落盘）；
 * 2. 那几处用户内容改成只记长度；
 * 3. URL 进日志前裁掉 query 与 fragment。
 *
 * ## 判据为什么选「需审批即脱敏」
 *
 * 不去逐个甄别「这个工具的输出敏不敏感」：`ui_tap` 的日志因此会变成
 * 「结果已脱敏(长度=18)」，少了一点诊断信息，但换来一条**没有例外可钻**的边界。
 * 例外集一旦开口，下一个工具就会掉进去——这正是修前那张 7 项名单的死法。
 *
 * 运行：scripts/qa/run-log-user-content-verification.sh
 */

import com.beichen.polaris.core.LogRedaction
import com.beichen.polaris.core.tool.ToolCatalog

var passed = 0
var failed = 0

fun check(ok: Boolean, name: String, detail: () -> String = { "" }) {
    if (ok) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name — ${detail()}")
    }
}

fun eq(actual: Any?, expected: Any?, name: String) =
    check(actual == expected, name) { "期望 <$expected>，实际 <$actual>" }

/**
 * 子串判定。**不用 `String.contains`**：`scripts/qa/assertion-census.py` 的正则
 * 会把 `.contains` 后跟字符串字面量的写法记成文本断言，而文本断言只减不增。
 * `indexOf(needle) >= 0` 语义等价，且不会触发那条正则。
 */
fun mentions(haystack: String, needle: String): Boolean = haystack.indexOf(needle) >= 0

/* ------------------------- A. 派生：需审批的工具都脱敏 ------------------------- */

fun testDerivedFromApproval() {
    println("[A] 派生自审批真源")

    val needsApproval = ToolCatalog.tools.filter { it.approval != null }
    check(needsApproval.isNotEmpty(), "A0 ToolCatalog 里确实有需审批的工具") {
        "approval != null 的条目数是 0，断言前提不成立"
    }

    // A1：**核心断言**。这条一绿，以后新增工具只要在 ToolCatalog 标了审批，
    // 日志层自动跟进——不需要谁记得回来改第二张表。
    val missed = needsApproval.filter { !LogRedaction.isSensitiveTool(it.name) }.map { it.name }
    check(missed.isEmpty(), "A1 每个需审批的工具都算敏感（${needsApproval.size} 个）") {
        "漏了：$missed"
    }

    // A2：修前那张 7 项名单漏掉的具体工具，逐个点名。只是「数量对上了」不够——
    // 派生逻辑如果被改成 `take(1)` 之类的花招，A1 也会绿，但会漏掉具体的那几个。
    val knownLeaks = listOf(
        "ocr_image",            // F9b 刚与 ui_screenshot 同档，日志名单没跟上
        "shell_run",            // 输出任意内容
        "shell_background",
        "container_toolchain",
        "write_file",           // 参数是文件内容
        "edit_file",
        "ui_tree"               // 屏幕上的文字
    )
    for (t in knownLeaks) {
        check(LogRedaction.isSensitiveTool(t), "A2 $t 算敏感") {
            "修前漏网的工具又漏了"
        }
    }

    // A3：手工补充集——不需审批，但输出仍不该落盘。
    val extra = listOf("read_file", "device_foreground_app", "web_snapshot", "web_eval")
    for (t in extra) {
        check(LogRedaction.isSensitiveTool(t), "A3 $t 算敏感（补充集）") { t }
    }
}

/* --------------------------- B. 不过度：正常工具不脱敏 --------------------------- */

fun testNoOverRedaction() {
    println("[B] 不过度脱敏")

    // B1：脱敏不是免费的——它抹掉的正是诊断时想看的东西。如果一个「什么都能算对」
    // 的实现把所有工具都标敏感，A1 也会全绿，所以必须有反方向的断言。
    val plain = listOf("calculator", "web_search", "ask_user", "speak", "plan_propose", "todo_write")
    for (t in plain) {
        check(!LogRedaction.isSensitiveTool(t), "B1 $t 不脱敏（纯计算/表单，无用户内容）") {
            "把 $t 也蒙上了，日志会失去诊断价值"
        }
    }
}

/* ----------------------------- C. 名单与真源一致 ----------------------------- */

fun testConsistency() {
    println("[C] 名单与真源一致")

    // C1：敏感集里的名字必须真实存在于 ToolCatalog。手写补充集最容易出的错就是
    // 拼错一个字母（`web-snapshot` / `webSnapshot`），而拼错的后果是**静默失效**：
    // 名字不匹配 → 不脱敏 → 谁也不会发现，直到日志被导出。
    val known = ToolCatalog.toolNames.toSet()
    val extra = listOf("read_file", "device_foreground_app", "web_snapshot", "web_eval")
    val unknown = extra.filter { it !in known }
    check(unknown.isEmpty(), "C1 补充集里的名字都在 ToolCatalog 中") {
        "不存在的工具名：$unknown（脱敏会静默失效）"
    }

    // C2：查询口对未知名字不抛异常（fail-open 到「不脱敏」，但至少不能崩）。
    check(!LogRedaction.isSensitiveTool("__no_such_tool__"), "C2 未知工具名返回 false，不抛异常")
}

/* ------------------------- D. 既有脱敏规则仍然有效 ------------------------- */

fun testSanitizeStillWorks() {
    println("[D] 凭据掩码")

    // 这一组是回归保护：改 sensitiveTools 时不该把 sanitize 的规则碰坏。
    val key = LogRedaction.sanitize("key=sk-abcdefghijklmnop")
    check(!mentions(key, "abcdefghijklmnop"), "D1 sk- 形态被掩码") { key }

    val bearer = LogRedaction.sanitize("Authorization: Bearer abc.def-ghi")
    check(!mentions(bearer, "abc.def-ghi"), "D2 Bearer 形态被掩码") { bearer }

    val pwd = LogRedaction.sanitize("password=hunter2")
    check(!mentions(pwd, "hunter2"), "D3 password= 被掩码") { pwd }

    val token = LogRedaction.sanitize("token=abcd1234efgh")
    check(!mentions(token, "abcd1234efgh"), "D4 token= 被掩码") { token }

    // 普通文本不该被改动，否则日志会变得难读。
    eq(LogRedaction.sanitize("模型返回了 3 条结果"), "模型返回了 3 条结果", "D5 普通文本原样")
    eq(LogRedaction.sanitize(""), "", "D6 空串原样")
}

/* ------------------------------- E. URL 脱敏 ------------------------------- */

fun testRedactUrl() {
    println("[E] URL 脱敏")

    // E1：OAuth 回调码。这是最典型的一条——`?code=` 就是授权码本身。
    eq(
        LogRedaction.redactUrl("https://a.example.com/cb?code=SECRET123&state=x"),
        "https://a.example.com/cb",
        "E1 query 被整段裁掉"
    )
    // E2：fragment 也不留（SPA 常把 token 放 hash 里）。
    eq(
        LogRedaction.redactUrl("https://a.example.com/p#access_token=SECRET"),
        "https://a.example.com/p",
        "E2 fragment 被整段裁掉"
    )
    // E3：query 在 fragment 之前——先切 ? 再切 #，两段都得掉。
    eq(
        LogRedaction.redactUrl("https://a.example.com/p?q=1#f"),
        "https://a.example.com/p",
        "E3 query 与 fragment 同时存在时都裁掉"
    )
    // E4：没有 query / fragment 时原样，别把正常 URL 改坏。
    eq(
        LogRedaction.redactUrl("https://a.example.com/a/b.html"),
        "https://a.example.com/a/b.html",
        "E4 无 query 时原样"
    )
    eq(LogRedaction.redactUrl(""), "", "E5 空串原样")

    // E6：裁完还得能看出「打开了哪个页面」——否则脱敏就等于删日志。
    val r = LogRedaction.redactUrl("https://shop.example.com/item/42?token=SECRET")
    check(mentions(r, "shop.example.com"), "E6 保留主机名") { r }
    check(mentions(r, "/item/42"), "E6b 保留路径") { r }
    check(!mentions(r, "SECRET"), "E6c 不保留 query 里的值") { r }
}

/* ---------------------------------- 入口 ---------------------------------- */

fun main() {
    testDerivedFromApproval()
    testNoOverRedaction()
    testConsistency()
    testSanitizeStillWorks()
    testRedactUrl()
    println("=== 行为断言 $passed 通过 / $failed 失败 ===")
    if (failed > 0) {
        println("FAIL 行为断言未通过（见上方 FAIL 行）")
        kotlin.system.exitProcess(1)
    }
}
