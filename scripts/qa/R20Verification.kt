/**
 * R-20 验证：工具 schema 里暴露了**实际不可用**的后端。
 *
 * ## 缺陷
 *
 * `ShellRunTool` 的 `identity` 参数手写了 `enum = listOf("user","su","shizuku","ssh")`，
 * 而工厂当时对非 `User` 一律返回空候选（R-12 后改成按权限档筛，su / ssh 仍无后端）——`su` / `ssh`
 * 首期根本没有执行路径。模型照着 enum 选，必然失败一次；失败信息还只是「没有可用后端」，
 * 读不出「这个值本来就不存在」，于是通常原样重试，再失败一次。
 *
 * 同一份事实（哪些身份能用）被写在四个地方：枚举注释、schema 的 enum 字面量、
 * 参数说明的字面量、错误提示的字面量。改一处忘三处，就是这个缺陷的来源。
 *
 * ## 判据
 *
 * schema 必须由枚举的 [TerminalIdentity.usable] 生成。这里测的不是「当前有哪些值」，
 * 而是**双向一致**：usable 的必须出现在 schema 里，schema 里的必须 usable。
 * 以后加新身份忘了标注，A/C 组会红。
 *
 * ## 真跑范围
 *
 * 只编译 `TerminalIdentity.kt` + `ShellRoute.kt`（零 Android 依赖）。
 * `ShellRunTool.execute` 里那段「合法但未实现 → 明确报错」的分支拖 Android 编不动，
 * 由 runner 的 [G] 段做静态接线断言——这一点不含糊，别把静态检查当成行为证据。
 */

import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import com.beichen.polaris.core.terminal.backend.shouldUsePrivilegedShell

private var gPass = 0
private var gFail = 0

fun check(name: String, body: () -> Unit) {
    try {
        body()
        gPass++
        println("  PASS  $name")
    } catch (e: AssertionError) {
        gFail++
        println("  FAIL  $name -> ${e.message}")
    }
}

/** 断言用 lambda 传消息：只有失败时才拼字符串（这些用例里有循环，拼了也是白拼）。 */
inline fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 $expected，实际 $actual")
}

/* ------------------------------ A. schema 取值 ------------------------------ */

fun testSchemaValues() {
    println("[A] schema 暴露给模型的取值")

    val values = TerminalIdentity.schemaValues()

    check("A1 恰好是 user / shizuku / ssh（顺序稳定，便于 diff）") {
        // W-RT5 起 ssh 有了真实执行路径（远端持久 shell），按 R-20 的规矩它**必须**进 schema：
        // 能跑却不出现，等于让模型永远想不起自己还能连远端。
        eq(values, listOf("user", "shizuku", "ssh"), "schemaValues()")
    }

    check("A2 不含 su —— 首期没有执行路径") {
        expect("su" !in values) { "su 又回到 schema 里了：模型会照着选，然后必然失败" }
    }

    check("A3 ssh 在 schema 里（W-RT5：它有后端了，反过来必须可见）") {
        expect("ssh" in values) { "ssh 有后端却没进 schema——模型用不到这条通道" }
    }

    check("A4 文案默认用「 / 」分隔") {
        eq(TerminalIdentity.schemaValuesText(), "user / shizuku / ssh", "schemaValuesText()")
    }

    check("A5 中文语境可用顿号") {
        eq(TerminalIdentity.schemaValuesText("、"), "user、shizuku、ssh", "schemaValuesText(\"、\")")
    }

    check("A6 取值全部是小写线格式（schema 与 fromWire 认的是同一个字符串）") {
        values.forEach { v ->
            eq(v, v.lowercase(), "取值 $v 应已是小写")
            expect(TerminalIdentity.fromWire(v) != null) { "$v 能进 schema 却解析不回来" }
        }
    }
}

/* ------------------------- B. 哪些身份真的能用 ------------------------- */

fun testUsability() {
    println("[B] 枚举的可用性标注")

    check("B1 user 可用（proot 容器与本机 shell 都走它）") {
        expect(TerminalIdentity.User.usable) { "user 被判成不可用" }
    }

    check("B2 shizuku 可用（作为工具参数走一次性通道）") {
        expect(TerminalIdentity.Shizuku.usable) {
            "shizuku 被判成不可用——它虽然没有池后端，但有一次性提权通道"
        }
    }

    check("B3 su 不可用") { expect(!TerminalIdentity.Su.usable) { "su 被标成可用" } }

    check("B4 ssh 可用（W-RT5：后端已实现，走显式路由）") {
        expect(TerminalIdentity.Ssh.usable) {
            "ssh 被判成不可用——它有后端了（远端持久 shell），只是不进降级链"
        }
    }
}

/* ---------------------- C. 不变式：枚举与 schema 双向一致 ---------------------- */

fun testInvariants() {
    println("[C] 双向一致（加新身份时忘标注，这里会红）")

    val values = TerminalIdentity.schemaValues().toSet()

    check("C1 usable 的都在 schema 里") {
        TerminalIdentity.entries.filter { it.usable }.forEach { id ->
            expect(id.wire in values) { "${id.wire} 标了 usable 却没进 schema，模型用不到它" }
        }
    }

    check("C2 schema 里的都标了 usable") {
        values.forEach { wire ->
            val id = TerminalIdentity.fromWire(wire)
            expect(id != null) { "$wire 在 schema 里但解析不成枚举" }
            expect(id!!.usable) { "$wire 进了 schema 却标着不可用——这正是 R-20 的原样" }
        }
    }

    check("C3 未实现的值一个都不许出现在 schema 里") {
        TerminalIdentity.entries.filter { !it.usable }.forEach { id ->
            expect(id.wire !in values) { "${id.wire} 未实现却出现在 schema 里" }
        }
    }

    check("C4 fromWire 对每个 wire 都能往返") {
        TerminalIdentity.entries.forEach { id ->
            eq(TerminalIdentity.fromWire(id.wire), id, "${id.wire} 往返")
            eq(TerminalIdentity.fromWire("  ${id.wire.uppercase()}  "), id, "${id.wire} 大写+空白往返")
        }
    }
}

/* --------------------------- D. 路由真值表（R-20 的缝） --------------------------- */

fun testRouting() {
    println("[D] 一次性通道的路由判定")

    check("D1 未给 identity → 进池") {
        expect(!shouldUsePrivilegedShell(null, hasSession = false, privilegedAvailable = true)) {
            "未给 identity 却走了通道"
        }
    }

    check("D2 shizuku → 走通道") {
        expect(shouldUsePrivilegedShell("shizuku", false, true)) { "显式 shizuku 没走通道" }
    }

    check("D3 Shizuku（大写）→ 同样走通道（修前不走，掉进池里被拒）") {
        expect(shouldUsePrivilegedShell("Shizuku", false, true)) {
            "大写能通过 fromWire 校验，却走了错的分支——这就是 R-20 的那条缝"
        }
    }

    check("D4 SHIZUKU 全大写 → 走通道") {
        expect(shouldUsePrivilegedShell("SHIZUKU", false, true)) { "全大写没走通道" }
    }

    check("D5 带空白 → 走通道（fromWire 会 trim）") {
        expect(shouldUsePrivilegedShell("  shizuku  ", false, true)) { "带空白没走通道" }
    }

    check("D6 su / ssh → 不走通道") {
        expect(!shouldUsePrivilegedShell("su", false, true)) { "su 走了提权通道" }
        expect(!shouldUsePrivilegedShell("ssh", false, true)) { "ssh 走了提权通道" }
    }

    check("D7 带 session → 不走（会话已绑定后端）") {
        expect(!shouldUsePrivilegedShell("shizuku", true, true)) { "带 session 还换通道" }
    }

    check("D8 通道不可用 → 不走（交给池如实报权限不足）") {
        expect(!shouldUsePrivilegedShell("shizuku", false, false)) { "通道不可用却仍走" }
    }

    check("D9 空串按「未给」处理") {
        expect(!shouldUsePrivilegedShell("", false, true)) { "空串走了通道" }
    }

    check("D10 未知取值 → 不走（不静默猜）") {
        expect(!shouldUsePrivilegedShell("root", false, true)) { "未知取值被当成 shizuku" }
    }
}

fun main() {
    println("=== R-20：identity schema 只暴露真的能用的值 ===")
    testSchemaValues()
    testUsability()
    testInvariants()
    testRouting()
    println()
    println("=== 结果：$gPass 通过 / $gFail 失败 ===")
    // 不在这里 exitProcess：退出码交给 runner 按总失败数决定，
    // 否则「java 提前退出」和「断言失败」在日志里长得一样。
}
