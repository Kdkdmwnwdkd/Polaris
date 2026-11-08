package com.beichen.polaris.core.capability

import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.TerminalIdentity

/*
 * Phase 2 · DoD⑤：把已有骨架的可用性判断**接入统一能力链**（不重复造轮子）。
 *
 *  - 「执行 shell 命令」的候选来自 T07 的 [TerminalBackendFactory]：复用其 `providersFor`
 *    给出的 proot → user 顺序，以及带缓存 + `clearCache` 的 `isAvailable` 探测，
 *    **不重新实现** proot/rootfs 的判定；
 *  - 「操作手机界面」的候选来自无障碍状态（[accessibilityCapabilitySnapshot]，纯函数入参 =
 *    「服务是否连接」+「自动化总开关是否开启」），Android 侧读取 `AutomationBridge` 的适配
 *    见 automation/CapabilityAndroidBridge.kt。
 *
 * 本文件只做「骨架状态 → [BackendAvailability] 快照」的**纯映射**，零 Android 依赖，
 * 可在 JVM harness 直接断言。
 */

/** 无障碍能力候选的后端键（与能力链其它候选区分）。 */
const val BACKEND_ACCESSIBILITY = "accessibility"

/**
 * 无线调试（adb over TCP）候选的后端键。
 *
 * 与 `TerminalBackend.KEY_SHIZUKU` 同层：两者都是 uid 2000，做同一批事，只是通道不同。
 * 这里不复用 shell 的 `KEY_SSH`——那是「连到别的机器」，语义不同。
 */
const val BACKEND_ADB = "adb-wireless"

/* ------------------------------------------------------------------ */
/* shell 能力：调试通道(Shizuku) → proot 容器 → 本机 user shell        */
/* ------------------------------------------------------------------ */

/**
 * shell 后端键 → 展示名（纯函数）。
 */
fun shellBackendLabel(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "调试通道（Shizuku）"
    TerminalBackend.KEY_PROOT -> "proot 容器"
    TerminalBackend.KEY_USER -> "本机 user shell"
    else -> backendKey
}

/**
 * shell 后端键 → 不可用原因（纯函数，面向诊断）。
 */
fun shellBackendReason(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "调试通道未就绪（Shizuku 未运行或未授权本应用）"
    TerminalBackend.KEY_PROOT -> "proot 容器不可用（未安装 rootfs 或缺少 proot 组件）"
    TerminalBackend.KEY_USER -> "本机 shell（/system/bin/sh）不可执行"
    else -> "后端「$backendKey」不可用"
}

/**
 * shell 后端键 → 不可用时的**可操作提示**（纯函数，具体到下一步）。
 */
fun shellBackendHint(backendKey: String): String = when (backendKey) {
    TerminalBackend.KEY_SHIZUKU -> "到「权限中心 → 调试通道」按引导完成一次授权：装好 Shizuku 并在其中「通过无线调试启动」，再回来点「去授权」。"
    TerminalBackend.KEY_PROOT -> "可在「容器」页一键安装并解压 rootfs 后重试；若只想跑普通命令，本机 shell 已足够。"
    TerminalBackend.KEY_USER -> "确认设备可执行本机 shell（一般无需处理），可重启应用后重试。"
    else -> "确认「$backendKey」所需的依赖已就绪后重试。"
}

/**
 * 一条 shell 候选的可用性快照（纯函数）：可用 → 仅带展示名；不可用 → 附原因 + 可操作提示。
 */
fun shellBackendAvailability(backendKey: String, available: Boolean): BackendAvailability =
    if (available) {
        BackendAvailability(backendKey, shellBackendLabel(backendKey), available = true)
    } else {
        BackendAvailability(
            backendId = backendKey,
            label = shellBackendLabel(backendKey),
            available = false,
            reason = shellBackendReason(backendKey),
            hint = shellBackendHint(backendKey)
        )
    }

/**
 * 从 T07 工厂取「执行 shell 命令」的候选可用性快照（proot → user，按优先级）。
 *
 * **复用既有判断**：候选顺序来自 [TerminalBackendFactory.providersFor]，可用性来自带缓存的
 * [TerminalBackendFactory.isAvailable]（proot 探测要碰文件系统，缓存判定；权限/可用性变化后
 * 由调用方 `clearCache()` 让其重新探测）。本函数**不重复实现**任何探测。
 */
fun TerminalBackendFactory.shellCapabilitySnapshot(
    shizukuAvailable: Boolean = false
): List<BackendAvailability> = buildList {
    // W-13：调试通道（uid 2000）能做的活最多（改系统设置/停应用/读文件），排在最前；
    // 不可用时仅作为「诊断条目」呈现，不影响后续 proot/user 的降级链。
    add(shellBackendAvailability(TerminalBackend.KEY_SHIZUKU, shizukuAvailable))
    addAll(
        providersFor(TerminalIdentity.User, preferProot = true)
            // W-56：Shizuku 已是池里的后端，但上面那行用「实时状态」把它放在首位——
            // 这里跳过，避免同一档出现两次（展示顺序与执行顺序不一致会更难解释）。
            .filter { it.backendKey != TerminalBackend.KEY_SHIZUKU }
            .map { provider ->
                shellBackendAvailability(provider.backendKey, isAvailable(provider))
            }
    )
}

/* ------------------------------------------------------------------ */
/* 无障碍能力：操作手机界面                                             */
/* ------------------------------------------------------------------ */

/**
 * 无障碍候选的可用性快照（纯函数）。
 *
 * 入参拆成两个子状态（**不与 `AutomationBridge.isReady()` 的单布尔混淆**），因为两种缺失的
 * 用户动作**不同**（一个去系统设置开无障碍、一个去 App 设置开总开关），文案必须分别给出：
 *  1. 服务未连接 → 引导「到系统设置开启无障碍服务」；
 *  2. 总开关关闭 → 引导「在设置页打开自动化总开关」；
 *  3. 两者都缺 → 合并两条动作，按先系统后 App 的顺序。
 *
 * @param serviceConnected 无障碍服务是否已连接（`AutomationBridge.service != null`）
 * @param masterEnabled 自动化总开关是否开启（`AutomationBridge.enabled`）
 */
fun accessibilityBackendAvailability(
    serviceConnected: Boolean,
    masterEnabled: Boolean
): BackendAvailability {
    if (serviceConnected && masterEnabled) {
        return BackendAvailability(BACKEND_ACCESSIBILITY, "无障碍服务", available = true)
    }
    val missing = buildList {
        if (!serviceConnected) add("无障碍服务未连接")
        if (!masterEnabled) add("自动化总开关未开启")
    }.joinToString("，且")
    val actions = buildList {
        if (!serviceConnected) add("到系统「设置 → 无障碍」开启「北辰 · 自动化」服务")
        if (!masterEnabled) add("到「北辰」的「设置 → 自动化」打开总开关")
    }.joinToString("，再")
    return BackendAvailability(
        backendId = BACKEND_ACCESSIBILITY,
        label = "无障碍服务",
        available = false,
        reason = missing,
        hint = "$actions，然后重试。"
    )
}

/**
 * 无线调试候选的可用性快照（纯函数）。
 *
 * @param configured 是否已配对过（有密钥 + 端点）。**不看当前连接是否活着**——端点在、
 *   连接可重建，真正的失败（端口变了/配对失效）会在执行时如实报错，比在探测阶段
 *   干等一次 TCP 超时更诚实。
 */
fun adbBackendAvailability(configured: Boolean): BackendAvailability =
    if (configured) {
        BackendAvailability(BACKEND_ADB, "无线调试（ADB）", available = true)
    } else {
        BackendAvailability(
            backendId = BACKEND_ADB,
            label = "无线调试（ADB）",
            available = false,
            reason = "无线调试未配对（没有可用的 adb 凭据）",
            hint = "到「设置 → 调试通道」用配对码配对一次：先在开发者选项打开「无线调试」，" +
                "点「使用配对码配对设备」拿到配对端口与 6 位码，再填主页显示的连接端口。"
        )
    }

/**
 * 「操作手机界面」的候选可用性快照（纯函数）：**无障碍服务 → 调试通道（Shizuku）→ 无线调试**。
 *
 * W-50 之前这里只有无障碍一条候选，注释还写着「无更低一级可用路径」。但那条判断不成立：
 * shell（uid 2000）能做 `input tap/swipe/text/keyevent`、`uiautomator dump`、`screencap`，
 * 足以覆盖界面操作的大头。于是「只开无线调试、没开无障碍」的用户被整条链路挡住，
 * 明明有可用通道却被告知「全链不可用」。
 *
 * @param serviceConnected 无障碍服务是否已连接
 * @param masterEnabled 自动化总开关是否开启
 * @param shizukuAvailable 调试通道是否就绪（Shizuku 已运行且已授权本应用）
 * @param adbAvailable 无线调试是否已配对（W-51 新增的第三级候选）
 */
fun controlScreenSnapshot(
    serviceConnected: Boolean,
    masterEnabled: Boolean,
    shizukuAvailable: Boolean,
    adbAvailable: Boolean = false
): List<BackendAvailability> = listOf(
    // 首选：实时、无需 dump、能拿界面变化事件
    accessibilityBackendAvailability(serviceConnected, masterEnabled),
    // 第二级：Shizuku（binder，无连接维持成本，探测微秒级）
    shellBackendAvailability(TerminalBackend.KEY_SHIZUKU, shizukuAvailable),
    // 第三级：无线调试直连（不依赖第三方 App，端口变了需重新配对）
    adbBackendAvailability(adbAvailable)
)

/**
 * 旧的单候选快照（仅无障碍）。
 *
 * @Deprecated 保留是为了不破坏既有 harness 断言；新调用点请用 [controlScreenSnapshot]。
 */
@Deprecated("改用 controlScreenSnapshot(...)，它包含调试通道兜底")
fun accessibilityCapabilitySnapshot(serviceConnected: Boolean, masterEnabled: Boolean): List<BackendAvailability> =
    listOf(accessibilityBackendAvailability(serviceConnected, masterEnabled))
