package com.beichen.polaris.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 审批请求的**进程级中转站**。
 *
 * ## 为什么需要它
 *
 * 审批原本只在 `ChatViewModel` 里：`_pendingApproval` 是 ViewModel 的私有状态，
 * 由 `ApprovalHost` 用一个 Compose `Dialog` 渲染。那条路径只在**北辰自己的窗口**
 * 上有效——自动化跳到 QQ / 微信之后，北辰退到后台，`Dialog` 就跟着不可见了。
 *
 * 真机现象（用户 2026-11-21 反馈）：让 Agent 去 QQ 发消息，界面已经跳到 QQ，
 * 审批卡却在北辰里。用户必须切回北辰点「允许」，再切回 QQ；而一回到 QQ，
 * Agent 又可能已经因为看不到界面而重新规划，界面再次跳走。
 *
 * 于是审批从「一次点击」变成「反复切应用」，30 秒根本不够，任务频繁被自动拒绝。
 *
 * ## 分工
 *
 * - `ChatViewModel` 是唯一的**决策方**：它决定这次要不要问、问什么、记住什么。
 *   它只管把「有一张卡要显示」和「卡撤了」两件事告诉这里（[publish] / [withdraw]）。
 * - [ApprovalOverlay] 是**显示方**：无障碍服务在线时，用 `TYPE_ACCESSIBILITY_OVERLAY`
 *   把卡片画在所有应用之上，用户就地就能点。
 * - 用户的点击回到这里，再经 [Sink] 交给 ViewModel 的 `approveOnce` / `approveAlways`
 *   / `denyApproval`——判定逻辑仍然只有一份，浮层和对话框不会各自演化。
 *
 * ## 为什么不做成 Compose 状态
 *
 * 无障碍服务活着的时长和 Activity 无关，甚至可能没有 Activity（用户在别的应用里）。
 * 浮层要订阅的东西必须是进程级的，不能挂在任何 ViewModel 的生命周期上。
 */
object ApprovalBridge {

    /**
     * 浮层要显示的全部内容。
     *
     * 刻意不直接引用 `ui.ApprovalRequest`：那是 UI 层的类型，`automation` 反向依赖
     * `ui` 会让架构守卫报循环。这里只保留「画一张卡需要知道的四件事」。
     */
    data class Pending(
        /** 工具名（`ui_click`、`app_launch`…）。模型与用户共用的词汇，不翻译。 */
        val toolName: String,
        /** 一句话说明要做什么（已脱敏的参数预览，单行）。 */
        val summary: String,
        /** 「本次会话不再询问<label>」的完整文案，由 `ApprovalGrant.memoLabel` 提供。 */
        val memoLabel: String,
        /** 这一次能不能被记住。false 时浮层不显示「不再问」按钮，改为一句说明。 */
        val memorizable: Boolean
    )

    /** 用户决策的落点。由 `ChatViewModel` 在初始化时绑定。 */
    interface Sink {
        fun allowOnce()

        fun allowAlways()

        fun deny()
    }
    private val _pending = MutableStateFlow<Pending?>(null)

    /** 非空 = 有一张卡在等用户。浮层订阅它。 */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _overlayReady = MutableStateFlow(false)

    /**
     * 浮层通道是否就绪（无障碍服务已挂上 [ApprovalOverlay]）。
     *
     * 应用内的 [com.beichen.polaris.ui.ApprovalHost] 读它：为 true 时不再渲染 `Dialog`，
     * 否则同一个请求会同时出现在浮层和对话框里，用户点一个、另一个还挂着。
     *
     * **它标记的是「通道」，不是「某一帧的窗口」**：等 addView 成功再置位会让第一次
     * 审批出现双卡（`publish` 与 Compose 重组没有先后保证）。所以无障碍一连上就置 true，
     * `addView` 万一失败再翻回 false——对话框晚一帧补上，但不会双卡。
     */
    val overlayReady: StateFlow<Boolean> = _overlayReady.asStateFlow()

    @Volatile
    private var sink: Sink? = null

    fun bind(s: Sink?) {
        sink = s
    }

    /**
     * 便捷绑定：三个决策就是 ViewModel 上已有的三个方法。
     *
     * 做成函数引用而不是让调用方写匿名对象，是为了让接入点只有一行——
     * `ChatViewModel` 已经在行数上限上打架，每多三行匿名类就要再动一次基线。
     */
    fun bind(onAllowOnce: () -> Unit, onAllowAlways: () -> Unit, onDeny: () -> Unit) {
        sink = object : Sink {
            override fun allowOnce() = onAllowOnce()
            override fun allowAlways() = onAllowAlways()
            override fun deny() = onDeny()
        }
    }

    /**
     * 发布一张待确认卡。
     *
     * 参数全部来自 `ApprovalRequest` 的既有字段，不在这里做任何判定——
     * 「要不要问」刚在上游 `ApprovalTier` / `ApprovalScope` 判完，这里只负责转手。
     */
    fun publish(toolName: String, summary: String, memoLabel: String, memorizable: Boolean) {
        _pending.value = Pending(toolName, summary, memoLabel, memorizable)
    }

    /** 撤卡。ViewModel 的 `finally` 与三个决策入口都会调，必须幂等。 */
    fun withdraw() {
        _pending.value = null
    }

    fun markOverlayReady(ready: Boolean) {
        _overlayReady.value = ready
    }

    /**
     * 三个决策入口。
     *
     * 先撤卡再转交：用户手指落下那一刻卡片就应该消失，而不是等 Agent 循环
     * 走完 `finally` 再回写状态——那中间有几十毫秒，用户会以为没点上，容易连点。
     *
     * 返回 false = 没有人在等（超时收场后浮层还在的竞态），调用方无需处理。
     */
    fun allowOnce(): Boolean = deliver { it.allowOnce() }

    fun allowAlways(): Boolean = deliver { it.allowAlways() }

    fun deny(): Boolean = deliver { it.deny() }

    private inline fun deliver(action: (Sink) -> Unit): Boolean {
        val s = sink ?: return false
        _pending.value = null
        action(s)
        return true
    }

    /**
     * 进程级状态复位。由 AppGraph 的测试清单调用，harness 也用它做用例隔离——
     * 上一个用例留下的 `pending` 会让下一个用例一开局就看见一张别人的卡。
     */
    fun reset() {
        _pending.value = null
        _overlayReady.value = false
        sink = null
    }
}
