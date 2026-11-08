package com.beichen.polaris.ui

import com.beichen.polaris.automation.AutomationBridge
import com.beichen.polaris.automation.AutomationPrefs
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置开关域（3.1 第三批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：四个开关都是「一个布尔/字符串状态 + 一次落盘」，读点在别处
 * （PlanTool 的 planModeEnabled、buildTaskClient 的任务档案、send 收尾的通知开关），
 * 但零写入逻辑——搬走的是状态与持久化，不是决策。
 *
 * 唯一的外部副作用是 [setAutomationEnabled] 写 [AutomationBridge]（跟原实现一致）；
 * 「总开关变化 → 能力就绪度重算」仍由宿主在转发层调用，facade 不反向依赖 VM。
 *
 * 字段与方法体逐行照搬：通知开关与任务模式/任务模型落 ConfigStore，
 * 自动化总开关落 AutomationPrefs。
 */
internal class SettingsFacade(
    private val configStore: ConfigStore,
    private val automationPrefs: AutomationPrefs,
) {

    /** 自动化总开关（供设置页实时反映开关与服务连接情况）。 */
    private val _automationEnabled = MutableStateFlow(automationPrefs.enabled)
    val automationEnabled: StateFlow<Boolean> = _automationEnabled.asStateFlow()

    /** 回答完成提醒开关（用户显式 opt-in，跨启动保持）。 */
    private val _completionNotifyEnabled = MutableStateFlow(configStore.isCompletionNotifyEnabled())
    val completionNotifyEnabled: StateFlow<Boolean> = _completionNotifyEnabled.asStateFlow()

    /** 任务模式：先出方案待批，再执行。 */
    private val _planMode = MutableStateFlow(configStore.isPlanModeEnabled())
    val planMode: StateFlow<Boolean> = _planMode.asStateFlow()

    /** 后台任务用哪套模型档案；空串 = 跟随当前。 */
    private val _taskProfileId = MutableStateFlow(configStore.taskProfileId())
    val taskProfileId: StateFlow<String> = _taskProfileId.asStateFlow()

    /** 切换自动化总开关（持久化 + 同步到桥）。能力重算由宿主转发层负责。 */
    fun setAutomationEnabled(v: Boolean) {
        automationPrefs.enabled = v
        AutomationBridge.enabled = v
        _automationEnabled.value = v
    }

    /** 设置页开关：写回 ConfigStore（即时生效 + 跨启动保持）。 */
    fun setCompletionNotifyEnabled(enabled: Boolean) {
        configStore.setCompletionNotifyEnabled(enabled)
        _completionNotifyEnabled.value = enabled
    }

    fun setPlanMode(enabled: Boolean) {
        _planMode.value = enabled
        configStore.setPlanModeEnabled(enabled)
        AgentLogger.i("Chat", "任务模式切换为 ${if (enabled) "先计划后执行" else "自动执行"}")
    }

    /** 设置任务模型档案；传空串 = 跟随主配置。 */
    fun setTaskProfile(id: String) {
        _taskProfileId.value = id
        configStore.setTaskProfileId(id)
        AgentLogger.i("Chat", "任务模型切换为 ${id.ifBlank { "跟随主配置" }}")
    }
}
