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
 * ## 那第三态什么时候出现
 *
 * 列表里**还留着**本服务，但服务没连上。两种成因：
 *  - 服务进程被系统回收（国产 ROM 清后台、省电策略），用户没关开关但服务没了；
 *  - 系统的无障碍通道被另一个客户端占住（自动点击器、抢票插件、厂商 AI 助手），
 *    同一时刻只让一个客户端拿稳。
 *
 * 这一态**本次没有复现**：模拟器上连跑三次 `uiautomator dump`，`Bound services` 始终在。
 * 保留它是防御——真机上服务被清后台是常态，而这时如果只说「无障碍未连接」，
 * 用户会以为是自己没开，跑去设置里反复开关，问题根本不在开关上。
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
