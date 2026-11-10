#!/usr/bin/env kotlin
/**
 * F7「工作区沙箱对 shell 是句空话」验证 harness。
 *
 * ## 这条缺陷的真身
 * `read_file` / `write_file` 被 PathGuard 锁在工作区里，但 `shell_run` 的 `cat` 整条绕过：
 * proot 把 `/sdcard` 直接 bind 进容器（`-b /sdcard`），无线调试 / Shizuku 以 uid 2000 跑，
 * 本机 shell 也照样能读。于是「工作区沙箱」只对文件工具成立。
 *
 * 一条提示注入就能让 Agent 读相册——而「读」本身是 Agent 的本职，拦不住也不该拦；
 * 能拦的只有**读的那一刻让用户看见**。所以判据落在审批层：默认「不问」的只读命令，
 * 一旦读的是受保护目录，就要过一次人，且授权粒度是**目录**而不是整组 `shell`。
 *
 * ## 覆盖
 *  A. 受保护目录识别：共享存储 / 应用数据 / 前缀边界 / 相对路径 / 系统只读位置
 *  B. 规范化：`..` 穿越不改变「用户同意的是哪个目录」
 *  C. 授权键：按目录分档，`shell` 组的授权**不能**顺带放行受保护读
 *  D. 不变量：写命令、重定向、包装器仍落在「每次都问」，不会被读授权洗白
 *  E. 回归：普通只读 / 构建命令的判定与授权一字未变（别为了安全把日常拖成弹窗地狱）
 *
 * 运行：scripts/qa/run-shell-read-boundary-verification.sh
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

/** 断言这条命令命中的同意粒度。 */
private fun expectScope(command: String, expected: String?) {
    eq(ShellCommandRules.sensitiveReadRoot(command), expected, "「$command」的同意粒度")
}

/* -------------------------- A. 受保护目录识别 --------------------------- */

fun testDetection() {
    println("[A] 受保护目录识别")

    check("A1 相册里的单个文件 → 相册目录") {
        expectScope("cat /sdcard/DCIM/Camera/IMG_0001.jpg", "/sdcard/DCIM")
    }

    check("A2 只 ls 根 → 粒度就是根本身") {
        expectScope("ls /sdcard", "/sdcard")
    }

    check("A3 外置存储路径（/storage/emulated/0）") {
        expectScope("head -n 20 /storage/emulated/0/Download/a.csv", "/storage/emulated")
    }

    check("A4 其它应用的私有目录") {
        expectScope("ls /data/data/com.tencent.mm/files", "/data/data/com.tencent.mm")
    }

    check("A5 相对路径不触发（落在 cwd 里，不构成绕过）") {
        expectScope("cat notes.txt", null)
        expectScope("grep -r todo src", null)
    }

    check("A6 系统只读位置不触发（看一眼 CPU 型号不该弹窗）") {
        expectScope("cat /proc/cpuinfo", null)
        expectScope("cat /system/build.prop", null)
        expectScope("ls /data/local/tmp", null)
    }

    check("A7 前缀陷阱：/sdcard0 与 /sdcardevil 不在 /sdcard 里") {
        expectScope("cat /sdcardevil/x.jpg", null)
        expectScope("cat /data/database/x", null)
    }

    check("A8 管道与 && 串里任意一段命中即触发") {
        expectScope("ls | wc -l", null)
        expectScope("ls /sdcard/DCIM | wc -l", "/sdcard/DCIM")
        expectScope("cat a.txt && cat /sdcard/b.txt", "/sdcard/b.txt")
    }

    check("A9 绝对路径的命令名（/system/bin/cat）不影响判定") {
        expectScope("/system/bin/cat /sdcard/DCIM/x", "/sdcard/DCIM")
    }

    check("A10 引号与长选项里的路径也认") {
        expectScope("grep -F needle \"/sdcard/My Files/a.txt\"", "/sdcard/My")
        expectScope("grep --file=/sdcard/patterns.txt a.txt", "/sdcard/patterns.txt")
    }

    check("A11 find 的目标目录") {
        expectScope("find /sdcard/DCIM -name \"*.jpg\"", "/sdcard/DCIM")
    }
}

/* ----------------------------- B. 规范化 ------------------------------- */

fun testNormalization() {
    println("[B] 路径规范化")

    check("B1 .. 穿越后按真实目录算") {
        expectScope("cat /sdcard/../data/data/x/y.db", "/data/data/x")
        expectScope("cat /sdcard/DCIM/../Download/a.csv", "/sdcard/Download")
    }

    check("B2 重复分隔符与 . 被折叠") {
        expectScope("cat //sdcard///DCIM/./x.jpg", "/sdcard/DCIM")
    }

    check("B3 根括号闭合后仍命中") {
        expectScope("cat /sdcard/DCIM/x.jpg,", "/sdcard/DCIM")
    }
}

/* --------------------------- C. 授权键与作用域 --------------------------- */

fun testGrant() {
    println("[C] 授权键与作用域")

    check("C1 受保护读：键带目录，且可记忆（同目录不再问）") {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        eq(g.key, "${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM", "授权键")
        eq(g.memorizable, true, "可记忆")
        eq(g.label, "读取 /sdcard/DCIM", "弹窗类别名")
        eq(g.memoLabel, "本次会话不再询问读取 /sdcard/DCIM", "勾选框文案")
    }

    check("C2 换目录必须重问") {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        expect(!ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/Download"))) {
            "同意过下载目录，不该放行相册"
        }
        expect(ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM"))) {
            "同意过相册，同一目录应放行"
        }
    }

    check("C3 关键回归：同意过 shell 组，不等于同意读相册") {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        expect(!ApprovalScope.allows(g, setOf(ApprovalGrouping.GROUP_SHELL))) {
            "`npm install` 的『不再询问』被顺带用来放行读相册——这条就是 F7 本身"
        }
    }

    check("C4 普通只读命令仍是纯 shell 组（不新建弹窗）") {
        val g = shellGrant("cat notes.txt")
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "授权键")
        eq(g.memorizable, true, "可记忆")
        eq(ShellCommandRules.sensitiveReadRoot("cat notes.txt"), null, "不该命中受保护目录")
    }
}

/* ---------------------------- D. 不变量 -------------------------------- */

fun testInvariants() {
    println("[D] 不变量")

    check("D1 带重定向的读仍落「每次都问」，不被读授权洗白") {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg > /sdcard/DCIM/y.jpg")
        eq(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_UNMEMORIZABLE), true, "应落不可记忆档（实际 ${g.key}）")
        eq(g.memorizable, false, "可记忆")
        expect(!ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM"))) {
            "读相册的授权被用来放行『往相册写文件』"
        }
    }

    check("D2 写命令不归读档") {
        eq(shellGrant("cp /sdcard/DCIM/x.jpg /sdcard/Download/").memorizable, false, "cp 可记忆？")
        eq(shellGrant("rm -rf /sdcard/DCIM").memorizable, false, "rm 可记忆？")
        eq(
            shellGrant("rm -rf /sdcard/DCIM").key.startsWith(ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE),
            true,
            "rm 应落不可逆档"
        )
    }

    check("D3 包装器里的读仍不可记忆") {
        val g = shellGrant("busybox cat /sdcard/DCIM/x.jpg")
        eq(g.memorizable, false, "wrapper 可记忆？")
    }

    check("D4 提权身份优先于读档") {
        val args = buildJsonObject {
            put("command", JsonPrimitive("cat /sdcard/DCIM/x.jpg"))
            put("identity", JsonPrimitive("shizuku"))
        }
        val g = ApprovalScope.of("shell_run", args)
        eq(g.key.startsWith(ApprovalGrouping.GROUP_SHELL_ELEVATED), true, "提权应先命中（实际 ${g.key}）")
        eq(g.memorizable, false, "提权可记忆？")
    }

    check("D5 读档只对 shell 工具有效，别的工具不受影响") {
        eq(ApprovalScope.of("http_request", argsOf("x")).key.startsWith(ApprovalGrouping.GROUP_SHELL_READ), false, "http")
        eq(ApprovalScope.of("read_file", argsOf("x")).key, "read_file", "read_file 不参与归组")
    }
}

/* ------------------------------ E. 回归 -------------------------------- */

fun testRegression() {
    println("[E] 回归（别为了安全把日常命令拖成弹窗地狱）")

    check("E1 只读命令仍然免审批（needsApproval 不看路径）") {
        eq(ShellCommandRules.needsApproval("cat /sdcard/DCIM/x.jpg"), false, "只读判定")
        eq(ShellCommandRules.needsApproval("ls /sdcard"), false, "只读判定")
        eq(ShellCommandRules.needsApproval("npm install"), true, "写命令仍需审批")
    }

    check("E2 构建类命令的授权一字未变") {
        val g = shellGrant("npm install")
        eq(g.key, ApprovalGrouping.GROUP_SHELL, "授权键")
        eq(g.memorizable, true, "可记忆")
    }

    check("E3 系统信息类命令不触发读档（避免误弹）") {
        eq(ShellCommandRules.sensitiveReadRoot("getprop ro.product.model"), null, "getprop")
        eq(ShellCommandRules.sensitiveReadRoot("ps -A"), null, "ps")
        eq(ShellCommandRules.sensitiveReadRoot("df -h"), null, "df")
    }

    check("E4 空命令不炸") {
        eq(ShellCommandRules.sensitiveReadRoot(""), null, "空命令")
        eq(ShellCommandRules.sensitiveReadRoot("   "), null, "空白命令")
    }
}

/* --------------------------------- 主流程 -------------------------------- */

fun main() {
    println("=== F7 工作区外读取边界验证 ===")
    testDetection()
    testNormalization()
    testGrant()
    testInvariants()
    testRegression()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
