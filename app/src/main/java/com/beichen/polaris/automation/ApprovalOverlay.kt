package com.beichen.polaris.automation

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 审批卡的**跨应用浮层**。
 *
 * ## 解决的具体问题
 *
 * 审批原本是 Compose `Dialog`，只在北辰自己的窗口里可见。自动化一旦跳到目标应用
 * （真机案例：让它去 QQ 发消息），用户眼前是 QQ，审批卡留在后台的北辰里：
 *
 * 1. 必须在 QQ 与北辰之间来回切，切一次就丢一次上下文；
 * 2. 切回北辰点完再切回 QQ，Agent 可能已经把界面又跳走了；
 * 3. 整个过程要压在 30 秒超时之内，否则自动拒绝，任务白跑。
 *
 * 现在卡片画在所有应用之上，用户停在 QQ 里就能点——不用切应用，也不用抢时间。
 *
 * ## 为什么用 `TYPE_ACCESSIBILITY_OVERLAY`
 *
 * 这是 `AccessibilityService` 专属的窗口类型，**不需要 `SYSTEM_ALERT_WINDOW`**。
 * 而要做自动化，无障碍服务必定已经连上（否则连界面都读不到），所以这条路
 * 在所有能触发审批的场景里都成立，不会多要一个用户得自己去设置里开的权限。
 *
 * 走 Shizuku / 无线调试通道（无障碍没开）时浮层不存在，`overlayReady` 保持 false，
 * 应用内 `ApprovalHost` 照旧渲染 `Dialog`——功能不退化。
 *
 * ## 三个不能省的细节
 *
 * 1. **不抢焦点**（`FLAG_NOT_FOCUSABLE`）：抢了会把输入法顶掉，而自动化此时可能
 *    正在往输入框里打字。
 * 2. **仍然可触摸**：所以不能加 `FLAG_NOT_TOUCHABLE`。窗外触摸靠
 *    `FLAG_NOT_TOUCH_MODAL` 穿透。
 * 3. **不进入无障碍树**（`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`）：
 *    否则 `ui_tree` 会把「允许 / 拒绝」当成界面上的可点节点读给模型，
 *    模型完全可能自己点「允许」——审批就变成了自己批自己。
 *
 * ## 遮挡会不会导致点错
 *
 * 不会。浮层只在「有一张卡在等」时出现，而那一刻 Agent 正卡在审批挂起里，
 * 不会派发任何手势；用户点完卡片立即消失，Agent 才继续。时序上两者不重叠。
 */
object ApprovalOverlay {

    private const val TAG = "ApprovalOverlay"

    /** 卡片离屏幕顶端的距离（占屏高比例）。避开状态栏与常见应用的标题栏。 */
    private const val TOP_FRACTION = 0.16f

    private var service: AgentAccessibilityService? = null
    private var scope: CoroutineScope? = null
    private var root: View? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var memoView: TextView? = null
    private var memoRow: View? = null

    /** 无障碍服务连接时调用。重复调用安全（会先拆掉旧窗口）。 */
    fun attach(service: AgentAccessibilityService) {
        detach()
        this.service = service
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            ApprovalBridge.pending.collectLatest { pending ->
                if (pending == null) hide() else show(pending)
            }
        }
        // 这一行是接线证据：真机上无障碍连上后，日志里应该出现它，
        // 但不应该出现窗口——只有真的有审批请求时才会 addView。
        AgentLogger.i(TAG, "审批浮层通道已就绪（等审批请求，未建窗口）")
        // 通道就绪即让应用内对话框让位。
        //
        // 不能等「第一次 addView 成功」再置位：那一刻 `publish` 与 Compose 重组谁先谁后
        // 没有保证，结果是第一次审批时浮层和对话框同时出现一帧，用户看到两张卡。
        // 万一 addView 真失败，[show] 会立刻把它翻回 false，对话框随即补上——
        // 损失的是一帧，换来的是「不会双卡」。
        ApprovalBridge.markOverlayReady(true)
    }

    /** 服务销毁时调用：拆窗口并让应用内对话框重新接管。 */
    fun detach() {
        hide()
        scope?.cancel()
        scope = null
        service = null
        ApprovalBridge.markOverlayReady(false)
    }

    private fun show(pending: ApprovalBridge.Pending) {
        val svc = service ?: return
        val wm = svc.getSystemService(WindowManager::class.java) ?: return
        val existing = root
        if (existing != null) {
            bind(pending)
            return
        }
        val card = buildCard(svc, pending) ?: return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            val metrics = svc.resources.displayMetrics
            y = (metrics.heightPixels * TOP_FRACTION).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        val added = runCatching { wm.addView(card, lp) }.isSuccess
        if (!added) {
            // 厂商 ROM 可能有额外限制。这里必须诚实降级：overlayReady=false 会让
            // 应用内对话框照常显示，用户至少还有一条路能批。
            AgentLogger.w(TAG, "审批浮层挂载失败，退回应用内对话框")
            ApprovalBridge.markOverlayReady(false)
            return
        }
        root = card
        ApprovalBridge.markOverlayReady(true)
        AgentLogger.i(TAG, "审批浮层已显示：${pending.toolName}")
    }

    private fun hide() {
        val v = root ?: return
        val svc = service
        root = null
        titleView = null
        bodyView = null
        memoView = null
        memoRow = null
        runCatching {
            svc?.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        }
        ApprovalBridge.markOverlayReady(false)
    }

    /** 同一张卡复用时只换文字，不重建窗口（避免每步审批闪一下）。 */
    private fun bind(pending: ApprovalBridge.Pending) {
        titleView?.text = pending.toolName
        bodyView?.text = pending.summary.ifBlank { "（无参数）" }
        memoRow?.visibility = if (pending.memorizable) View.VISIBLE else View.GONE
        memoView?.text = pending.memoLabel
    }

    private fun buildCard(
        svc: AgentAccessibilityService,
        pending: ApprovalBridge.Pending
    ): View? {
        val d = svc.resources.displayMetrics.density
        // 写成 lambda 而不是局部函数：局部函数不能被当作值传给 textButton。
        val dp: (Int) -> Int = { (it * d).toInt() }

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21C1C1E"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            // 不进入无障碍树：否则 ui_tree 会把按钮读给模型，模型可以自己批自己。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val head = TextView(svc).apply {
            text = "确认执行危险操作"
            setTextColor(Color.parseColor("#FFB4A0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        card.addView(head, matchWidth())

        val title = TextView(svc).apply {
            text = pending.toolName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        card.addView(title, matchWidth())
        titleView = title

        val body = TextView(svc).apply {
            text = pending.summary.ifBlank { "（无参数）" }
            setTextColor(Color.parseColor("#D0D0D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(12))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(body, matchWidth())
        bodyView = body

        // 按钮行：拒绝（描边）/ 允许（实心红）
        val buttons = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val deny = textButton(svc, "拒绝", "#00000000", "#FFFFFF", dp(1), "#4DFFFFFF", dp)
        deny.setOnClickListener { ApprovalBridge.deny() }
        val allow = textButton(svc, "允许本次", "#D93025", "#FFFFFF", 0, "#00000000", dp)
        allow.setOnClickListener { ApprovalBridge.allowOnce() }
        buttons.addView(deny, rowWeight())
        buttons.addView(allow, rowWeight())
        card.addView(buttons, matchWidth().apply { topMargin = dp(2) })

        // 「本次会话不再询问…」独占一行。
        //
        // 真机上这一段原来是小字链接，测试用户根本没看见，于是每一步都回来点「允许本次」，
        // 一个发消息任务被拦了四五次。它不是次要选项——它是「这条链路要不要打扰我」的开关，
        // 必须和「允许」一样显眼。不可记忆时（rm、提权 shell）改成一句说明，不给按钮。
        val memo = TextView(svc).apply {
            text = pending.memoLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (pending.memorizable) {
            memo.setTextColor(Color.parseColor("#8AB4F8"))
            memo.background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A8AB4F8"))
                cornerRadius = dp(12).toFloat()
            }
            memo.isClickable = true
            memo.setOnClickListener { ApprovalBridge.allowAlways() }
        } else {
            memo.setTextColor(Color.parseColor("#9A9A9A"))
        }
        card.addView(memo, matchWidth().apply { topMargin = dp(8) })
        memoView = memo
        memoRow = memo

        return card
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = 0; rightMargin = 0 }

    private fun rowWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginStart = 8 }

    private fun textButton(
        svc: AgentAccessibilityService,
        label: String,
        fill: String,
        textColor: String,
        strokeDp: Int,
        strokeColor: String,
        dp: (Int) -> Int
    ): TextView = TextView(svc).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(textColor))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(11), 0, dp(11))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(12).toFloat()
            if (strokeDp > 0) setStroke(strokeDp, Color.parseColor(strokeColor))
        }
        isClickable = true
        isFocusable = true
    }

    /** 仅测试/诊断：浮层当前是否在屏幕上。 */
    val visible: Boolean get() = root != null
}
