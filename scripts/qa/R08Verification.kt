#!/usr/bin/env kotlin
/**
 * R-08「审批记忆粒度过粗」验证 harness。
 *
 * ## 这条缺陷的真身
 * 不是「少判断一次」，而是 `approveAlways` 记的是**工具类别**（`shell`），
 * 用户同意的是**这一次的风险**。用户为了不再被 `npm install` 打断而勾选，
 * 顺带把 `rm -rf` / `pm uninstall` / `git reset --hard` 全部放行。
 *
 * 所以断言的核心只有一条：**不可逆命令的那次授权，永远不会让下一次免于确认**。
 * 这条只有真跑能证明——grep 只能证明 `memorizable` 这个词出现过。
 *
 * ## 覆盖
 *  A. 危险命令分类（删除/格式化/提权/卸载/强推）
 *  B. 一次授权的作用域：shell 普通档可记忆，危险档不可记忆且键不同
 *  C. 不变量：危险档的键永远不可能出现在会话白名单里（即使被别处写入）
 *  D. 回归：W-36 的「同类操作」粒度没被破坏（ui_tap / ui_click 仍共用一键）
 *
 * 运行：scripts/qa/run-r08-verification.sh
 */

import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.agent.ApprovalScope
import com.beichen.polaris.core.agent.ShellCommandRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

private fun argsOf(command: String): JsonObject =
    buildJsonObject { put("command", JsonPrimitive(command)) }

private fun shellGrant(command: String) = ApprovalScope.of("shell_run", argsOf(command))

/* -------------------------------- A. 分类 -------------------------------- */

fun testClassification() {
    println("[A] 危险命令分类")

    val destructive = listOf(
        "rm -rf /sdcard/Download",
        "rm -f a.txt",
        "rmdir old",
        "dd if=/dev/zero of=/dev/block/x",
        "mkfs.ext4 /dev/block/x",
        "pm uninstall com.example.x",
        "pm clear com.example.x",
        "git reset --hard HEAD~3",
        "git clean -fdx",
        "git push --force origin main",
        "find . -name '*.tmp' -delete",
        "chmod 000 /sdcard",
        "su -c 'id'",
        "reboot",
        "kill -9 1234",
        "mv a.txt b.txt",
    )
    destructive.forEach { cmd ->
        check("A 危险：$cmd") { expect(ShellCommandRules.isDestructive(cmd)) { "应判为不可逆" } }
    }

    val benign = listOf(
        "ls -la",
        "cat a.txt",
        "echo hello > out.txt",     // 重定向是写文件，但不是不可逆
        "npm install",
        "gradle assembleDebug",
        "git status",
        "git log --oneline",
        "pm list packages",
        "python3 script.py",
        "",
    )
    benign.forEach { cmd ->
        check("A 非危险：${cmd.ifBlank { "(空命令)" }}") {
            expect(!ShellCommandRules.isDestructive(cmd)) { "不该判为不可逆" }
        }
    }

    check("A 命令串里只要有一段危险，整体就危险") {
        expect(ShellCommandRules.isDestructive("ls && rm -rf build")) { "&& 后半段漏判" }
        expect(ShellCommandRules.isDestructive("echo ok; pm uninstall com.x")) { "; 后半段漏判" }
        expect(!ShellCommandRules.isDestructive("ls | grep a | wc -l")) { "纯读管道被误判" }
    }

    check("A 绝对路径也认得出来（/system/bin/rm）") {
        expect(ShellCommandRules.isDestructive("/system/bin/rm -rf /data/local/tmp/x")) { "basename 归一化失效" }
    }
}

/* -------------------------------- B. 授权 -------------------------------- */

fun testGrant() {
    println("[B] 一次授权的作用域")

    check("B1 普通 shell 命令：可记忆，键就是 shell 组") {
        val g = shellGrant("npm install")
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "key")
        expect(g.memorizable) { "npm install 应该可以被记住" }
        eq(g.memoLabel, "本次会话不再询问shell 命令", "文案")
    }

    check("B2 删除类命令：不可记忆，键与普通 shell 不同") {
        val g = shellGrant("rm -rf /sdcard/Download")
        expect(!g.memorizable) { "rm -rf 不该能被记住——这正是 R-08" }
        expect(g.key != ApprovalGrouping.GROUP_SHELL) { "危险档与普通档共用了同一个键" }
        expect(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE)) { "键前缀不对：${g.key}" }
    }

    check("B3 不可记忆时文案不能写「不再询问」") {
        val g = shellGrant("pm uninstall com.example.x")
        // 用前缀/后缀匹配而不是「包含某子串」：census 会把后者一律记成源码文本断言，
        // 这里判的虽是函数返回值，但没必要跟那条机械规则较劲——前缀匹配本来就比包含更强。
        expect(!g.memoLabel.startsWith("本次会话不再询问")) { "文案在骗人：${g.memoLabel}" }
        expect(g.memoLabel.endsWith("每次都会单独确认")) { "应说明每次都会确认：${g.memoLabel}" }
    }

    check("B4 同一个危险命令两次授权，键稳定（便于日志聚合）") {
        eq(shellGrant("rm -rf a").key, shellGrant("rm -rf b").key, "key")
    }

    check("B5 非 shell 工具不受影响（W-36 粒度保留）") {
        val tap = ApprovalScope.of("ui_tap", JsonObject(emptyMap()))
        val click = ApprovalScope.of("ui_click", JsonObject(emptyMap()))
        expect(tap.memorizable && click.memorizable) { "界面操作应可记忆" }
        eq(tap.key, click.key, "ui_tap / ui_click 应共用一键")
        eq(tap.key, ApprovalGrouping.GROUP_UI_ACTION, "key")
    }

    check("B6 后台 shell 与前台同判定") {
        val g = ApprovalScope.of("shell_background", argsOf("rm -rf x"))
        expect(!g.memorizable) { "shell_background 漏判" }
    }

    check("B7 缺 command 参数时不崩，按普通档处理") {
        val g = ApprovalScope.of("shell_run", JsonObject(emptyMap()))
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "key")
    }
}

/* ------------------------------ C. 不变量 ------------------------------ */

fun testInvariant() {
    println("[C] 关键不变量")

    check("C1 危险档不会被任何历史白名单命中") {
        val g = shellGrant("rm -rf /")
        // 模拟「旧版本/别处」把组键或危险键写进了白名单
        val polluted = setOf(ApprovalGrouping.GROUP_SHELL, g.key, "shell_destructive:rm")
        expect(!ApprovalScope.allows(g, polluted)) { "危险档被白名单放行了——R-08 又回来了" }
    }

    check("C2 普通档在白名单里时正常放行") {
        val g = shellGrant("gradle build")
        expect(ApprovalScope.allows(g, setOf(ApprovalGrouping.GROUP_SHELL))) { "普通档被误拦" }
    }

    check("C3 普通档不在白名单时仍然要问") {
        expect(!ApprovalScope.allows(shellGrant("npm install"), emptySet())) { "没授权却放行" }
    }

    check("C4 「记住 shell」之后，危险命令依旧要问（本轮的正式诉求）") {
        // 用户为了 npm install 勾了「不再询问」
        val allowed = mutableSetOf<String>()
        val benign = shellGrant("npm install")
        if (benign.memorizable) allowed += benign.key
        expect(ApprovalScope.allows(shellGrant("npm install"), allowed)) { "普通命令应免问" }
        expect(!ApprovalScope.allows(shellGrant("rm -rf /sdcard"), allowed)) { "rm 被顺带放行" }
        expect(!ApprovalScope.allows(shellGrant("pm uninstall com.x"), allowed)) { "卸载被顺带放行" }
    }
}

/* --------------------- D. 审计回归：一层壳不能换来永久放行 --------------------- */

/**
 * 2026-11-09 外部审计 F2。
 *
 * 修前的判据是 `isDestructive(首词)`：首词不在黑名单里就落回可记忆的 `shell` 键。
 * 于是 `busybox rm -rf`、`env rm`、`$(rm -rf)`、`python -c`、`sed -i`、`cp`、`>`
 * 全部搭上「用户为 npm install 勾的那一次授权」静默通过。
 *
 * 这一组断言的就是那句用户能听懂的话：**记住一条日常命令，不等于放行这些**。
 */
fun testWrappedBypass() {
    println("[D] 审计回归：包装/替换/写文件一律不可记忆")

    val bypass = listOf(
        "busybox rm -rf /sdcard/DCIM",
        "toybox rm -rf /sdcard/DCIM",
        "env FOO=1 rm -rf /sdcard/DCIM",
        "nohup rm -rf /sdcard/DCIM",
        "timeout 5 rm -rf /sdcard/DCIM",
        "xargs rm -rf",
        "eval \"rm -rf /sdcard/DCIM\"",
        "command rm -rf /sdcard/DCIM",
        "\$(rm -rf /sdcard/DCIM)",
        "echo `rm -rf /sdcard/DCIM`",
        "cp /dev/null /sdcard/important.txt",
        "echo x > /sdcard/important.txt",
        "python3 -c \"import os; os.remove('a')\"",
        "node -e \"require('fs').unlinkSync('a')\"",
        "sed -i 's/a/b/' notes.txt",
        "awk '{print}' a.txt > b.txt",
        "tree -o out.txt",
        "sort -o out.txt in.txt",
    )
    bypass.forEach { cmd ->
        check("D 不可记忆：$cmd") {
            expect(!ShellCommandRules.isMemorizable(cmd)) { "包装后被记成了可记忆——审计 F2 的绕过路径又回来了" }
            val g = shellGrant(cmd)
            expect(!g.memorizable) { "ApprovalScope 仍判可记忆：${g.key}" }
            expect(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_UNMEMORIZABLE)) { "键前缀不对：${g.key}" }
        }
    }

    check("D 反向：日常命令仍然可记忆（不能把 R-08 修成“每次全问”）") {
        val daily = listOf(
            "npm install", "pnpm add zod", "yarn build", "pip3 install requests",
            "gradle assembleDebug", "./gradlew build", "cargo test", "go build ./...",
            "git status", "git log --oneline", "git diff HEAD",
            "ls -la", "cat a.txt", "grep -rn token src", "wc -l a.txt", "pwd",
        )
        daily.forEach { cmd ->
            expect(ShellCommandRules.isMemorizable(cmd)) { "日常命令被误判为不可记忆：$cmd" }
            expect(shellGrant(cmd).memorizable) { "ApprovalScope 误拦：$cmd" }
        }
    }

    check("D 记住 npm install 之后，D 组的绕过命令一条都不许放行") {
        val allowed = mutableSetOf<String>()
        val benign = shellGrant("npm install")
        if (benign.memorizable) allowed += benign.key
        expect(ApprovalScope.allows(shellGrant("npm install"), allowed)) { "日常命令应免问" }
        bypass.forEach { cmd ->
            expect(!ApprovalScope.allows(shellGrant(cmd), allowed)) { "被顺带放行：$cmd" }
        }
    }

    check("D 首词无害但参数写文件，仍算不可记忆（参数层判据没丢）") {
        expect(!ShellCommandRules.isMemorizable("find . -name '*.tmp' -delete")) { "find -delete" }
        expect(!ShellCommandRules.isMemorizable("git clean -fdx")) { "git clean" }
        expect(!ShellCommandRules.isMemorizable("ls && rm -rf build")) { "命令串后半段" }
        expect(!ShellCommandRules.isMemorizable("pm uninstall com.x")) { "pm uninstall" }
        expect(!ShellCommandRules.isMemorizable("ls &")) { "后台执行" }
    }
}

/* ------------------ E. 审计回归：提权与网络外发是两条独立风险 ------------------ */

/** 审计 F1：权限是风险变量，命令文本不是；网络是唯一能把内容发出去的口子。 */
fun testElevatedAndNetwork() {
    println("[E] 审计回归：提权 shell 与网络外发")

    fun shellArgs(command: String, identity: String? = null) = buildJsonObject {
        put("command", JsonPrimitive(command))
        identity?.let { put("identity", JsonPrimitive(it)) }
    }

    check("E1 identity=shizuku 的 ls 也不可记忆（同一条命令换个 uid 能看的范围不同）") {
        val g = ApprovalScope.of("shell_run", shellArgs("ls -la /data/data/com.example", "shizuku"))
        expect(!g.memorizable) { "提权 shell 被判可记忆——审计 F1" }
        expect(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_ELEVATED)) { "键前缀不对：${g.key}" }
        expect(g.key.contains("shizuku")) { "键应带身份，便于区分 su / adb：${g.key}" }
        expect(!g.memoLabel.startsWith("本次会话不再询问")) { "文案在骗人：${g.memoLabel}" }
    }

    check("E2 显式 identity=user 时按命令本身判（不能被提权档误伤）") {
        val g = ApprovalScope.of("shell_run", shellArgs("ls -la", "user"))
        expect(g.memorizable) { "应用自身 uid 的只读命令应可记忆" }
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "key")
    }

    check("E3 提权档不会被任何历史白名单命中") {
        val g = ApprovalScope.of("shell_run", shellArgs("ls -la", "shizuku"))
        val polluted = setOf(
            ApprovalGrouping.GROUP_SHELL, ApprovalGrouping.GROUP_SHELL_ELEVATED,
            g.key, "shell_elevated:ls",
        )
        expect(!ApprovalScope.allows(g, polluted)) { "提权命令被白名单放行了" }
    }

    check("E4 后台 shell 同样吃提权判定") {
        val g = ApprovalScope.of("shell_background", shellArgs("cat /data/system/x", "shizuku"))
        expect(!g.memorizable) { "shell_background 漏判提权" }
        expect(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_ELEVATED)) { "键前缀不对：${g.key}" }
    }

    fun httpArgs(url: String?) = buildJsonObject {
        url?.let { put("url", JsonPrimitive(it)) }
    }

    check("E5 http_request 纳入审批，按域名分键") {
        val a = ApprovalScope.of("http_request", httpArgs("https://api.github.com/repos/x/y"))
        val b = ApprovalScope.of("http_request", httpArgs("https://api.github.com/user"))
        val c = ApprovalScope.of("http_request", httpArgs("https://evil.example.com/collect"))
        expect(a.memorizable) { "同域重复请求应可记忆，否则查资料会被反复打断" }
        eq(a.key, b.key, "同域不同路径应共用一键")
        expect(a.key != c.key) { "跨域共用了同一个键——外泄链正是靠这一步" }
        expect(a.key.startsWith(ApprovalGrouping.GROUP_NETWORK)) { "键前缀不对：${a.key}" }
        expect(a.key.contains("api.github.com") && c.key.contains("evil.example.com")) { "键里应带域名" }
    }

    check("E6 记住 A 域之后，发往 B 域仍要问") {
        val allowed = mutableSetOf<String>()
        val gh = ApprovalScope.of("http_request", httpArgs("https://api.github.com/user"))
        if (gh.memorizable) allowed += gh.key
        expect(ApprovalScope.allows(gh, allowed)) { "同域应免问" }
        expect(!ApprovalScope.allows(ApprovalScope.of("http_request", httpArgs("https://evil.example.com/x")), allowed)) {
            "换域被顺带放行"
        }
    }

    check("E7 缺 url / 畸形 url 不崩，退化为整组记忆（仍会弹窗）") {
        val noUrl = ApprovalScope.of("http_request", httpArgs(null))
        expect(noUrl.memorizable) { "缺 url 时应退化为组键，不该崩也不该不可记忆" }
        eq(noUrl.key, ApprovalGrouping.GROUP_NETWORK, "key")
        val bad = ApprovalScope.of("http_request", httpArgs("not a url"))
        eq(bad.key, ApprovalGrouping.GROUP_NETWORK, "畸形 url 应回退组键")
    }
}

/* ------------------------ F. 审计回归：MCP 永不记忆 ------------------------ */

fun testMcpNeverMemorized() {
    println("[F] 审计回归：MCP 工具永不记忆")

    check("F1 mcp_ 前缀工具不可记忆（目录注释写的就是「每次调用都需确认」）") {
        listOf("mcp_weather__forecast", "mcp_fs__write_file", "mcp_").forEach { name ->
            val g = ApprovalScope.of(name, JsonObject(emptyMap()))
            expect(!g.memorizable) { "MCP 工具被判可记忆：$name" }
            expect(!g.memoLabel.startsWith("本次会话不再询问")) { "文案在骗人：$name" }
        }
    }

    check("F2 即便 MCP 键被别处写进白名单，也不放行") {
        val g = ApprovalScope.of("mcp_fs__write_file", JsonObject(emptyMap()))
        expect(!ApprovalScope.allows(g, setOf(g.key, "mcp", ApprovalGrouping.GROUP_FILE_WRITE))) {
            "MCP 被白名单放行了"
        }
    }

    check("F3 非 mcp 前缀的同名子串不受影响（防前缀判定过宽）") {
        // `xmcp_x` 不以 mcp_ 开头，不该被当成 MCP；它没有归组，回退成自己的名字但可记忆。
        val g = ApprovalScope.of("xmcp_x", JsonObject(emptyMap()))
        expect(g.memorizable) { "前缀判定过宽" }
    }
}

/* --------------------------------- 主流程 --------------------------------- */

fun main() {
    println("=== R-08 审批记忆粒度验证 ===")
    testClassification()
    testGrant()
    testInvariant()
    testWrappedBypass()
    testElevatedAndNetwork()
    testMcpNeverMemorized()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
