package com.beichen.polaris.ui

import com.beichen.polaris.automation.AutomationBridge
import com.beichen.polaris.automation.AutomationPrefs
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.agent.ApprovalTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置开关域（3.1 第三批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：五个开关都是「一个布尔/字符串状态 + 一次落盘」，读点在别处
 * （PlanTool 的 planModeEnabled、buildTaskClient 的任务档案、send 收尾的通知开关、
 * requestApproval 的审批档位），但零写入逻辑——搬走的是状态与持久化，不是决策。
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

    /**
     * 自动续跑：进程被杀后，冷启动替用户发一句「继续」。
     *
     * 默认开（见 `ConfigStore.isAutoResumeEnabled`）。它每触发一次就是一次真实请求，
     * 所以必须让用户能关；关掉之后行为退回改造前——只提示，不自动发。
     */
    private val _autoResume = MutableStateFlow(configStore.isAutoResumeEnabled())
    val autoResume: StateFlow<Boolean> = _autoResume.asStateFlow()

    /**
     * W-RT3：审批档位。
     *
     * 读盘先过 [ApprovalTier.normalize]：旧版本残留、手改数据一律退回「常规」。
     * 这里必须 normalize 而不是把原始值原样揣着——它直接决定「要不要弹窗」，
     * 一个认不出的字符串在判定时落进 else 分支虽然也是常规，但那属于「碰巧对」；
     * 显式归一化让「坏配置退到保守侧」成为写下来的约定，而不是分支顺序的副作用。
     */
    private val _approvalTier = MutableStateFlow(ApprovalTier.normalize(configStore.approvalTier()))
    val approvalTier: StateFlow<String> = _approvalTier.asStateFlow()

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

    /** 设置页开关：自动续跑（即时生效 + 跨启动保持）。 */
    fun setAutoResume(enabled: Boolean) {
        _autoResume.value = enabled
        configStore.setAutoResumeEnabled(enabled)
        AgentLogger.i("Chat", "自动续跑切换为 ${if (enabled) "开" else "关"}")
    }

    /** 切换审批档位：即时生效（下一个审批点就按新档判）+ 落盘。 */
    fun setApprovalTier(mode: String) {
        val next = ApprovalTier.normalize(mode)
        _approvalTier.value = next
        configStore.setApprovalTier(next)
        AgentLogger.i("Chat", "审批档位切换为「${ApprovalTier.descriptionOf(next).substringBefore("：")}」")
    }

    /** 设置任务模型档案；传空串 = 跟随主配置。 */
    fun setTaskProfile(id: String) {
        _taskProfileId.value = id
        configStore.setTaskProfileId(id)
        AgentLogger.i("Chat", "任务模型切换为 ${id.ifBlank { "跟随主配置" }}")
    }
}
