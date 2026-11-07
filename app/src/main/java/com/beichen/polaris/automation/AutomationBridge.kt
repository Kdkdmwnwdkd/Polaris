package com.beichen.polaris.automation

import android.content.Context
import android.content.SharedPreferences

/**
 * 自动化桥：连接「AccessibilityService 实例」与「UI 状态 / Agent 工具」。
 *
 * 设计（净室原创）：用一个轻量单例持有当前运行中的服务引用与总开关，
 * 工具层（UiAutomationTools）只依赖这个桥、完全不直接持有 Android 服务实例，
 * 从而保持核心层与执行层解耦（符合四层分离）。
 *
 * 注意：本类只做状态中转，不包含任何业务/动作逻辑；所有真正的屏幕操作都在
 * AgentAccessibilityService 中基于 Android 标准 Accessibility API 完成。
 */
object AutomationBridge {

    /** 当前已连接的无障碍服务实例；由服务的 onServiceConnected / onDestroy 维护。 */
    var service: AgentAccessibilityService? = null
        private set

    /** 总开关：用户在设置页开启后才允许 Agent 操作手机。 */
    var enabled: Boolean = false

    /** 连接状态变化回调，供 ViewModel 同步 UI 状态流。 */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    fun attach(service: AgentAccessibilityService) {
        this.service = service
        onConnectionChanged?.invoke(true)
    }

    fun detach(service: AgentAccessibilityService) {
        if (this.service === service) {
            this.service = null
            onConnectionChanged?.invoke(false)
        }
    }

    /** 服务已连接且总开关已开，才允许执行自动化动作。 */
    fun isReady(): Boolean = service != null && enabled

    /**
     * 清空运行时状态（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness 使用）。
     *
     * 只清**内存镜像**：`enabled` 落盘在 [AutomationPrefs]，用户的开关键值不受影响，
     * 下次服务连上时按 prefs 重新置位。刻意不做 `detach()`——那会回调
     * `onConnectionChanged`，把「测试清场」表现成一次真实的断连事件。
     */
    fun reset() {
        service = null
        enabled = false
        onConnectionChanged = null
    }
}

/** 总开关持久化（SharedPreferences）。 */
class AutomationPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("agentkit_automation", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
}
