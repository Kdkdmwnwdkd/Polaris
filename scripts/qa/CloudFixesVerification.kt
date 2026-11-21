#!/usr/bin/env kotlin
/**
 * 云机实测三修的行为判据（2026-11-22）。
 *
 * 三件事的来由（前两条有云机数据，第三条有实测复现）：
 *
 *  1. **无障碍「启用中但没连上」说不出**：`A11yState` 加第三态。注意本条**不是**
 *     因为「uiautomator 会顶掉服务」——那是初判，后来复现证伪了（连跑三次 dump，
 *     `Bound services` 一直在）。真正的事实是：`adb install` 覆盖安装会清空
 *     `enabled_accessibility_services`，以及服务可能被系统回收/被其他客户端占住。
 *  2. **诊断包被能力链日志挤满**：234 行里 171 行是 `[CapabilityChain]`，同一批判定
 *     相隔 10 秒逐字重复两遍。崩溃前那几行因此被顶出「最近 300 行」的窗口。
 *  3. **本地构建版本号伪装成正式版**：`versionName = "1.$versionCode"`，回退值 14
 *     算出「1.14」——一个看起来完全正常的版本号。手册让用户先确认版本，本地包永远对不上。
 *     实测确认修复后 `dumpsys package` 读到的是 `1.14-local`。
 *
 * 分两层：
 *  A. 行为（JVM 直跑）：三态映射、指纹语义（等价判定到底等价在哪）；
 *  B. 接线（源码约束）：版本号后缀、UI 文案、探测 API、refresh 真的在比指纹 ——
 *     这些不编译报错，只会静默不生效。
 *
 * ## 为什么不直接测 refresh()
 *
 * `CapabilityReadiness` 在 app 模块，编它要拖进 AgentLogger → LogRedaction → ToolCatalog
 * 整条链。把指纹算法下沉到 core（[capabilityFingerprint]）之后，行为断言吃纯函数就够；
 * 「refresh 到底有没有用它」由 C 组源码断言钉住（`capabilityFingerprint(` 必须出现）。
 */

import com.beichen.polaris.core.capability.A11yState
import com.beichen.polaris.core.capability.BackendAvailability
import com.beichen.polaris.core.capability.Capability
import com.beichen.polaris.core.capability.CapabilityRequest
import com.beichen.polaris.core.capability.CapabilityResolution
import com.beichen.polaris.core.capability.a11yStateOf
import com.beichen.polaris.core.capability.capabilityFingerprint
import com.beichen.polaris.core.capability.resolveCapability
import java.io.File

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 <$expected>，实际 <$actual>")
}

private fun read(path: String): String {
    val f = File(path)
    if (!f.exists()) throw AssertionError("文件不存在：$path")
    return f.readText()
}

// ───────────────────────── A. 无障碍三态 ─────────────────────────

fun testA11yStates() {
    println("\n[A] 无障碍三态映射")

    check("A1 已连上 → Connected（设置读失败也不该降级）") {
        assertEquals("连上但设置读不到", A11yState.Connected, a11yStateOf(true, false))
    }
    check("A2 已连上且已启用 → Connected") {
        assertEquals("正常态", A11yState.Connected, a11yStateOf(true, true))
    }
    check("A3 启用中但没连上 → EnabledNotConnected（被顶掉）") {
        assertEquals("被别的无障碍应用占用", A11yState.EnabledNotConnected, a11yStateOf(false, true))
    }
    check("A4 没启用 → Off") {
        assertEquals("用户没开", A11yState.Off, a11yStateOf(false, false))
    }
    check("A5 三态互异（枚举值重复会让 UI 分支退化）") {
        assertEquals("枚举值个数", 3, A11yState.entries.toSet().size)
    }
}

// ──────────────────── B. 裁决指纹（去重的判定基础） ────────────────────

private fun resolve(
    capability: Capability,
    vararg candidates: BackendAvailability
): CapabilityResolution = resolveCapability(CapabilityRequest(capability, candidates.toList()))

private fun ok(id: String, label: String = "标签-$id") = BackendAvailability(id, label, true)
private fun bad(id: String, reason: String = "不可用-$id", label: String = "标签-$id") =
    BackendAvailability(id, label, false, reason)

fun testFingerprint() {
    println("\n[B] 裁决指纹")

    check("B1 同一份输入算两次，指纹相同（这是去重的前提）") {
        val a = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("user"))))
        val b = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("user"))))
        assertEquals("同输入同指纹", a, b)
    }
    check("B2 可用性变化 → 指纹变化（状态变了必须重打）") {
        val a = capabilityFingerprint(listOf(resolve(Capability.CONTROL_SCREEN, bad("acc"))))
        val b = capabilityFingerprint(listOf(resolve(Capability.CONTROL_SCREEN, ok("acc"))))
        assertTrue("可用/不可用必须区分", a != b)
    }
    check("B3 换选中后端 → 指纹变化") {
        val a = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("user"), ok("shizuku"))))
        val b = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, bad("user"), ok("shizuku"))))
        assertTrue("选中的 backendId 必须进指纹", a != b)
    }
    check("B4 只改展示名 → 指纹不变（文案不该触发落盘）") {
        val a = capabilityFingerprint(
            listOf(resolve(Capability.CONTROL_SCREEN, ok("acc", label = "无障碍服务")))
        )
        val b = capabilityFingerprint(
            listOf(resolve(Capability.CONTROL_SCREEN, ok("acc", label = "无障碍服务（改了个名）")))
        )
        assertEquals("label 不该进指纹", a, b)
    }
    check("B5 不可用原因变化 → 指纹变化（原因变了说明探测结果变了）") {
        val a = capabilityFingerprint(listOf(resolve(Capability.CONTROL_SCREEN, bad("acc", "未连接"))))
        val b = capabilityFingerprint(listOf(resolve(Capability.CONTROL_SCREEN, bad("acc", "被占用"))))
        assertTrue("reason 必须进指纹", a != b)
    }
    check("B6 多能力拼接：两条与一条不同（不能漏掉某个能力）") {
        val one = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("user"))))
        val two = capabilityFingerprint(
            listOf(resolve(Capability.RUN_SHELL, ok("user")), resolve(Capability.CONTROL_SCREEN, ok("acc")))
        )
        assertTrue("漏掉一条能力也应导致指纹不同", one != two)
    }
    check("B7 顺序敏感：同两条换个顺序，指纹不同（避免歧义拼接）") {
        val x = resolve(Capability.RUN_SHELL, ok("user"))
        val y = resolve(Capability.CONTROL_SCREEN, ok("acc"))
        assertTrue("顺序必须可区分", capabilityFingerprint(listOf(x, y)) != capabilityFingerprint(listOf(y, x)))
    }
    check("B8 空候选链：不同能力不能撞成同一条指纹（前缀必须带能力名）") {
        val a = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL)))
        val b = capabilityFingerprint(listOf(resolve(Capability.CONTROL_SCREEN)))
        // 空链时 trace 都是空，只有前缀能区分
        assertTrue("run_shell / control_screen 的空链指纹撞了", a != b)
    }
    check("B9 同能力、同可用性、只换后端 → 指纹变化（走的是哪条路必须可区分）") {
        val a = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("user"))))
        val b = capabilityFingerprint(listOf(resolve(Capability.RUN_SHELL, ok("shizuku"))))
        assertTrue("backendId 必须进指纹", a != b)
    }
}

// ──────────────────── C. 接线（源码约束） ────────────────────

fun testWiring() {
    println("\n[C] 接线")

    check("C1 版本号在本地构建时带 -local 后缀") {
        val g = read("app/build.gradle.kts")
        assertTrue("没找到 versionInjected 判定", "versionInjected" in g)
        assertTrue("versionName 没拼 -local", "\"-local\"" in g)
        assertTrue("判定没看 GITHUB_RUN_NUMBER", "GITHUB_RUN_NUMBER" in g)
    }
    check("C2 refresh 真的在比指纹（不是算完就扔）") {
        val s = read("app/src/main/java/com/beichen/polaris/ui/CapabilityReadiness.kt")
        assertTrue("没调用 capabilityFingerprint", "capabilityFingerprint(next)" in s)
        assertTrue("没有 lastLoggedFingerprint", "lastLoggedFingerprint" in s)
        assertTrue("没有比较", "!= lastLoggedFingerprint" in s)
        assertTrue("没有写回", "lastLoggedFingerprint = fingerprint" in s)
        assertTrue("日志没走注入点（harness 就数不了条数了）", "next.forEach(log)" in s)
    }
    check("C3 三态判定放在 core（JVM 可测），不埋在 UI 里") {
        val s = read("core/src/main/java/com/beichen/polaris/core/capability/A11yStatus.kt")
        assertTrue("没有 a11yStateOf", "fun a11yStateOf" in s)
        assertTrue("没有 Connected 分支", "serviceConnected -> A11yState.Connected" in s)
    }
    check("C4 UI 真的用三态，而不是只看 serviceConnected") {
        val s = read("app/src/main/java/com/beichen/polaris/ui/Components.kt")
        assertTrue("AkReadyTextRow 没算三态", "a11yStateOf(serviceConnected, enabledInSettings)" in s)
        assertTrue("没有「被占用」文案", "无障碍被占用" in s)
        assertTrue("没有 enabledInSettings 参数", "enabledInSettings: Boolean" in s)
    }
    check("C5 探测走系统启用清单，不猜") {
        val s = read("app/src/main/java/com/beichen/polaris/ui/AccessibilityProbe.kt")
        assertTrue("没调 getEnabledAccessibilityServiceList", "getEnabledAccessibilityServiceList" in s)
        assertTrue("探测失败没兜底", "catch (_: Exception)" in s)
    }
    check("C6 就绪清单把「被占用」和「没开」分开说") {
        val s = read("app/src/main/java/com/beichen/polaris/ui/ReadinessCard.kt")
        assertTrue("没区分 EnabledNotConnected", "A11yState.EnabledNotConnected" in s)
        assertTrue("没有「可能被其他无障碍应用占用」", "可能被其他无障碍应用占用" in s)
        assertTrue("动作词没随状态变", "重开" in s)
    }
}

fun main() {
    println("=== 云机三修判据（2026-11-22）===")
    testA11yStates()
    testFingerprint()
    testWiring()
    println("\n────────────────────────────")
    println("通过 $passed，失败 ${failures.size}")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
