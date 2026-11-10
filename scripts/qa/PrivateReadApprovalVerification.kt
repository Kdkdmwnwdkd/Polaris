#!/usr/bin/env kotlin
/**
 * F9「读用户私有数据的工具零审批」验证 harness。
 *
 * ## 这条缺陷的真身
 *
 * 审计 F7 修的是「shell 一条 `cat` 绕过工作区沙箱」——判据落在**路径**上。
 * 但有两类工具根本没有路径可判：
 *
 *  - `clipboard action=get`：用户刚复制的密码、验证码、卡号，一次调用整个进上下文；
 *  - `device_notifications`：此刻手机在响什么，正文里有验证码短信、聊天消息、银行推送。
 *
 * 两者 `needsApproval` 都是默认的 `false`（`AgentTool` 的默认值），`ToolCatalog` 里
 * 也没标归组键。于是模型可以静默读走它们，然后随下一次请求发出去——
 * 用户看不见任何一步。
 *
 * ## 为什么这两条要单独成档
 *
 * 「读」是 Agent 的本职，拦不住也不该拦；能拦的只有**读的那一刻让用户看见**。
 * 与 F7 同族：粒度必须对得上风险——
 *  - F7 按**目录**（同意读 `/sdcard/DCIM` 不等于同意读 `/data/data`）；
 *  - F9 按**工具**（同意读剪贴板不等于同意读通知，后者范围大到用户很难想象）。
 *
 * ## 覆盖
 *  A. 剪贴板读取：默认（缺 action）与显式 get 都要问，可记忆，键带工具名
 *  B. 剪贴板写入（set）：不问——它把内容**放到**用户手上，没有把秘密送出去
 *  C. 通知读取：要问
 *  D. 分档隔离：剪贴板与通知的键不同，同意一个不放行另一个
 *  E. 文案：勾选框要写清是「读剪贴板」还是「读通知」
 *  F. 不变量：不会被 shell / shell_read / network 的既有授权顺带放行
 *
 * 运行：scripts/qa/run-private-read-approval-verification.sh
 */

import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.agent.ApprovalScope
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

private fun args(vararg kv: Pair<String, String>): JsonObject =
    buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

/** 剪贴板一次调用的授权语义。 */
private fun clip(action: String?) =
    ApprovalScope.of("clipboard", if (action == null) buildJsonObject { } else args("action" to action))

/** 通知读取的授权语义。 */
private fun notify() = ApprovalScope.of("device_notifications", buildJsonObject { })

private val PRIVATE_KEY = "${ApprovalGrouping.GROUP_PRIVATE_READ}:"

/* --------------------------------- A 读取 --------------------------------- */

private fun testClipboardRead() {
    check("A1 缺 action 参数 = 默认 get，必须问") {
        val g = clip(null)
        eq(g.key, PRIVATE_KEY + "clipboard", "缺省按读取处理")
        eq(g.memorizable, true, "读剪贴板可以被记住")
    }

    check("A2 显式 action=get 要问") {
        eq(clip("get").key, PRIVATE_KEY + "clipboard", "键")
    }

    check("A3 action 大小写与空白不影响判定") {
        eq(clip(" GET ").key, PRIVATE_KEY + "clipboard", "「 GET 」也算读")
        eq(clip("Get").key, PRIVATE_KEY + "clipboard", "「Get」也算读")
    }

    check("A4 键里带工具名，粒度到工具而不是整组") {
        val g = clip("get")
        expect(g.key.endsWith(":clipboard")) { "键应以工具名结尾，实际 ${g.key}" }
    }
}

/* --------------------------------- B 写入 --------------------------------- */

private fun testClipboardWrite() {
    check("B1 action=set 不落隐私读档（不弹这一档的窗）") {
        val g = clip("set")
        expect(!g.key.startsWith(PRIVATE_KEY)) { "写入不该被归到读取档，实际 ${g.key}" }
    }

    check("B2 set 的大小写/空白同样识别") {
        listOf("SET", " Set ", "set").forEach { a ->
            expect(!clip(a).key.startsWith(PRIVATE_KEY)) { "「$a」应被识别为写入" }
        }
    }

    check("B3 未知 action（如 dump）按读取处理") {
        // fail-closed：不认识的动作一律当读取，而不是当写入放过。
        eq(clip("dump").key, PRIVATE_KEY + "clipboard", "未知动作按读")
    }
}

/* --------------------------------- C 通知 --------------------------------- */

private fun testNotifications() {
    check("C1 读取通知要问") {
        val g = notify()
        eq(g.key, PRIVATE_KEY + "device_notifications", "键")
        eq(g.memorizable, true, "可被记住")
    }
}

/* ------------------------------- D 分档隔离 ------------------------------- */

private fun testSeparation() {
    check("D1 剪贴板与通知是两个键，同意一个不放行另一个") {
        val c = clip("get")
        val n = notify()
        expect(c.key != n.key) { "两个工具的键必须不同" }
        expect(!ApprovalScope.allows(n, setOf(c.key))) { "同意读剪贴板后，读通知仍要问" }
        expect(!ApprovalScope.allows(c, setOf(n.key))) { "反过来同样不成立" }
    }

    check("D2 记忆命中后同一工具不再问") {
        val c = clip("get")
        expect(ApprovalScope.allows(c, setOf(c.key))) { "同一个键应命中" }
    }

    check("D3 不同剪贴板动作共用同一个读键（get 之间不必重复问）") {
        eq(clip("get").key, clip(null).key, "缺省与显式 get 是同一件事")
    }
}

/* --------------------------------- E 文案 --------------------------------- */

private fun testLabels() {
    check("E1 剪贴板读的勾选框写明「读剪贴板」") {
        val g = clip("get")
        eq(g.memoLabel, "本次会话不再询问读取剪贴板", "勾选框文案")
    }

    check("E2 通知读的勾选框写明「读通知」") {
        eq(notify().memoLabel, "本次会话不再询问读取通知", "勾选框文案")
    }

    check("E3 归组有中文名，不会把裸键 private_read 露给用户") {
        val label = ApprovalGrouping.labelOf(ApprovalGrouping.GROUP_PRIVATE_READ)
        expect(label != ApprovalGrouping.GROUP_PRIVATE_READ) { "必须有人话文案，实际：$label" }
    }
}

/* ------------------------------- F 不变量 --------------------------------- */

private fun testInvariants() {
    check("F1 shell 组的既有授权不能顺带放行读剪贴板") {
        val g = clip("get")
        expect(!ApprovalScope.allows(g, setOf(ApprovalGrouping.GROUP_SHELL))) {
            "同意过 shell ≠ 同意读剪贴板"
        }
    }

    check("F2 工作区外读取（shell_read）的授权也不能放行通知") {
        val g = notify()
        expect(!ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM"))) {
            "读相册 ≠ 读通知"
        }
    }

    check("F3 网络外发的授权也不能放行读剪贴板") {
        val g = clip("get")
        expect(!ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_NETWORK}:api.example.com"))) {
            "批准过一次外发 ≠ 同意读剪贴板"
        }
    }

    check("F4 隐私读键不等于任何 shell 键（组名不重叠）") {
        val keys = setOf(
            ApprovalGrouping.GROUP_SHELL, ApprovalGrouping.GROUP_SHELL_READ,
            ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE, ApprovalGrouping.GROUP_NETWORK
        )
        expect(ApprovalGrouping.GROUP_PRIVATE_READ !in keys) { "组名不能与既有档撞车" }
    }
}

/* --------------------------------- 入口 ----------------------------------- */

fun main() {
    println("== F9 私有数据读取审批（clipboard / device_notifications） ==")
    println("[A] 剪贴板读取")
    testClipboardRead()
    println("[B] 剪贴板写入")
    testClipboardWrite()
    println("[C] 通知读取")
    testNotifications()
    println("[D] 分档隔离")
    testSeparation()
    println("[E] 文案")
    testLabels()
    println("[F] 不变量")
    testInvariants()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
