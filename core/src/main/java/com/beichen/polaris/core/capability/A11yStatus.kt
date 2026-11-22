package com.beichen.polaris.core.capability

/**
 * 无障碍的**三种**状态，而不是「连上 / 没连上」两种。
 *
 * ## 事实基础（2026-11-22 云机实测，可复现）
 *
 * 1. `adb install` 覆盖安装会清空 `enabled_accessibility_services`——**用户每次升级，
 *    无障碍授权都会丢**。这时列表是空的，属于 [Off]，提示「去开启」是对的。
 * 2. 绑定本身是即时的：`settings put` 之后 `dumpsys accessibility` 在 t=0 就已经
 *    `Bound services: 北辰 · 自动化`，不存在几十秒的空窗。
 *
 * ## 那第三态什么时候出现（2026-11-22 云机已复现）
 *
 * 复现路径：服务正常绑定 → `am force-stop` 杀掉进程 → 系统把本组件记进
 * `Crashed services` 并清空 `enabled_accessibility_services` → 用户（或任何写入者）
 * 把设置项写回去。此时 `dumpsys accessibility` 同时显示：
 *
 * ```
 * Bound services:{}                                  ← 没连上
 * Enabled services:{{com.beichen.polaris/....AgentAccessibilityService}}  ← 开关是开的
 * Crashed services:{{...AgentAccessibilityService}}  ← 崩过，AMS 拒绝重绑
 * ```
 *
 * 关键事实：**AMS 一旦把服务记进 crashed 表，就不会再自动重绑**，除非用户把开关关掉
 * 再打开（实测：`settings put` 置 null 后重写，立刻回到 `Bound services: 北辰 · 自动化`）。
 * 所以用户看到的现象正是「开关明明是开的，但就是用不了」——这是真机上
 * 「升级/清理后台后无障碍失效」的主因之一，不是我们凭空防的一个态。
 *
 * 另一种成因（未复现但同类）：系统的无障碍通道被另一个客户端占住。
 *
 * ## 为什么不放在 ui
 *
 * 这里是纯判定，不碰 Context；Android 侧的探测在
 * `com.beichen.polaris.ui.isEnabledInSettings`。分开才能让 JVM harness 直接测。
 */
enum class A11yState {
    /** 服务在线，可以操作屏幕。 */
    Connected,

    /** 系统设置里**仍处于启用**，但服务没连上——被回收、正在重绑，或被别的无障碍客户端占住。 */
    EnabledNotConnected,

    /** 系统启用清单里没有本服务（没开过，或升级后授权被清）。 */
    Off,
}

/** 纯函数：由两个探测结果推出三态。 */
fun a11yStateOf(serviceConnected: Boolean, enabledInSettings: Boolean): A11yState = when {
    serviceConnected -> A11yState.Connected
    enabledInSettings -> A11yState.EnabledNotConnected
    else -> A11yState.Off
}

/**
 * 纯函数：系统设置项 `enabled_accessibility_services` 的原始值里，是否列着这个组件。
 *
 * ## 为什么不直接用 `getEnabledAccessibilityServiceList()`
 *
 * 因为它会**自我过滤**：AMS 把崩过的服务挪进 `Crashed services`，同时把它从启用清单里
 * 摘掉。云机实测（2026-11-22）：`settings get` 明确返回本组件、`dumpsys accessibility`
 * 也写着 `Enabled services:{...}`，可 UI 仍然显示「无障碍未连接」——判定走的正是那个
 * 被过滤过的接口。第三态于是成了永远走不到的死分支。
 *
 * 读原始设置项则与用户在系统设置里看到的那颗开关同源，不会自我过滤。
 *
 * ## 值的格式
 *
 * 冒号分隔的 `ComponentName.flattenToString()` 列表，同一组件可能有三种写法：
 * `pkg/full.ClassName`、`pkg/pkg.ClassName`、`pkg/.ClassName`。都认。
 * 未设置时读出来是 `null`；个别 ROM 会存字面量 `"null"`——不需要特判，
 * 它不可能等于任何真实组件名，比较时自然落空（写特判会多出一条永远走不到的分支：
 * 变异测试里把特判删掉，判据一条都不会红，说明它本来就没在起作用）。
 */
fun isA11yServiceListed(enabledServices: String?, packageName: String, className: String): Boolean {
    val raw = enabledServices?.trim().orEmpty()
    if (raw.isEmpty()) return false
    val simple = className.removePrefix("$packageName.")
    val forms = setOf(
        "$packageName/$className",
        "$packageName/$simple",
        "$packageName/.$simple",
    )
    return raw.split(':').any { entry ->
        val e = entry.trim()
        e.isNotEmpty() && forms.any { it.equals(e, ignoreCase = true) }
    }
}
