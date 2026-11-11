package com.beichen.polaris.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.ui.contract.ChatScreenContract
import kotlinx.coroutines.delay

/**
 * 审批弹窗的**全局挂载点**（审计 F16）。
 *
 * ## 为什么从 ChatScreen 搬到这里
 *
 * 修前 [ApprovalDialog] 的挂载写在 `ChatScreen` 的组件树里，只有「对话」这一页
 * 被组合时才会渲染。而审批请求是**异步到达**的：Agent 正在跑，用户可能停在
 * 控制台、设置、模型页，或者（第一次用定时任务时最常见）被自动带到了「权限引导」页。
 *
 * 真机复现（模拟器 API 30，`device_notifications` 带 `packages`）：
 *
 * ```
 * 07:29:28 tool.start tool=device_notifications args={"packages":["com.android.shell"]}
 * 07:29:31 WARN [Workflow] 当前有任务在跑，忽略立即运行请求      ← 用户以为点了没反应
 * 07:29:58 WARN [Approval] device_notifications 拒绝
 * 07:29:58 tool.end code=approval_denied dur=30079ms             ← 正好 30 秒超时
 * ```
 *
 * 同一时刻 `uiautomator dump` 里只有权限引导页的文字，**没有任何弹窗**。用户看到的是
 * 「任务跑了一下，然后什么都没发生」；从日志看是「审批超时自动拒绝」。两个视角都对，
 * 合起来才是完整的缺陷：**闸门在，但看不见的闸门等于被自动关上**。
 *
 * 审批是全局模态事件，不是某个页面的附属 UI。挂到 `AgentKitApp` 的 Scaffold 之外，
 * 路由怎么切都不影响它出现——这条不变量由 `run-approval-host-verification.sh` 守着。
 *
 * 退场动画：request 消失后本地多撑 150ms 承载 fade-out（Dialog 没有内建退场钩子）。
 */
@Composable
internal fun ApprovalHost(vm: ChatScreenContract) {
    val request by vm.pendingApproval.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<ApprovalRequest?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        if (request != null) {
            shown = request
            visible = true
        } else if (visible) {
            visible = false
            delay(150)
            shown = null
        }
    }

    val current = shown ?: return
    ApprovalDialog(request = current, visible = visible, vm = vm)
}
