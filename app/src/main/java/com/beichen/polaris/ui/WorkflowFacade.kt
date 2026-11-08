package com.beichen.polaris.ui

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.workflow.Workflow
import com.beichen.polaris.workflow.WorkflowStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定时任务域（W-17 / 3.1 第二批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：增删改查 + 立即运行只依赖 [WorkflowStore] 与两个 VM 动作——
 * 「当前是否在跑」（并发守卫）与「把 prompt 送进对话」（前台执行）。这两个动作
 * 通过构造回调注入，接触面一眼可见；facade 不知道会话状态机与生成链的存在。
 *
 * `workflowStore` 本体仍由 ChatViewModel 持有——四个 Workflow*Tool 注册进工具表
 * 要直接用它；这里接收同一实例，两侧看到同一份数据。
 *
 * 字段与方法体逐行照搬；`runWorkflowNow` 里对 isRunning 的判断改为读注入的
 * [isRunning] 回调，语义不变（运行中忽略立即运行请求）。
 */
internal class WorkflowFacade(
    private val store: WorkflowStore,
    private val isRunning: () -> Boolean,
    private val runPrompt: (String) -> Unit,
) {

    /** 定时任务列表状态（列表顺序即存储顺序）。 */
    private val _workflows = MutableStateFlow<List<Workflow>>(emptyList())

    val workflows: StateFlow<List<Workflow>> = _workflows.asStateFlow()

    fun refreshWorkflows() {
        _workflows.value = store.list()
    }

    fun setWorkflowEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        refreshWorkflows()
    }

    fun deleteWorkflow(id: String) {
        store.delete(id)
        refreshWorkflows()
        AgentLogger.i("Workflow", "已删除定时任务 $id")
    }

    /**
     * 立即运行一个定时任务：把它的 prompt 作为一次对话输入发送。
     *
     * 为什么走前台对话而不是后台执行：Agent 需要无障碍/截图/前台应用，后台跑不可信；
     * 前台执行用户还能看到每一步、随时停止（见 WorkflowNotifier 的说明）。
     */
    fun runWorkflowNow(id: String) {
        val wf = store.get(id) ?: return
        if (isRunning()) {
            AgentLogger.w("Workflow", "当前有任务在跑，忽略立即运行请求")
            return
        }
        store.markRun(id, System.currentTimeMillis(), "已在前台执行")
        refreshWorkflows()
        runPrompt(wf.prompt)
    }

    /**
     * 处理「点通知进入 App」携带的任务 id（MainActivity 经 VM 转发调用）。
     *
     * 只在 App 启动/收到新 Intent 时触发一次；执行后清除，避免旋转屏幕重复跑。
     */
    fun consumeWorkflowIntent(id: String?) {
        if (id.isNullOrBlank()) return
        AgentLogger.i("Workflow", "从通知进入，准备执行 $id")
        runWorkflowNow(id)
    }
}
