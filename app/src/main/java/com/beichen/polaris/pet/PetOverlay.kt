package com.beichen.polaris.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AppScope
import com.beichen.polaris.ui.AgentKitTheme
import com.beichen.polaris.ui.AppearanceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗宿主：`WindowManager` + `ComposeView` + 那一堆必须手工挂的 owner。
 *
 * ## 为什么样板这么长
 *
 * Compose 在 Activity 里能跑，是因为 `ComponentActivity` 已经替你实现了
 * `LifecycleOwner` / `SavedStateRegistryOwner` / `ViewModelStoreOwner` 并挂到了
 * 视图树上。`WindowManager.addView` 的视图没有这棵树，`ComposeView` 一 attach
 * 就会因为找不到 `ViewTreeLifecycleOwner` 直接抛异常。所以 [OverlayOwner]
 * 这三个接口一个都不能少，且必须在 `addView` **之前**设置到 view 上。
 *
 * ## 窗口尺寸为什么要跟着变
 *
 * 收起时窗口只有形象那么大（[PET_SIZE_DP]）：`TYPE_APPLICATION_OVERLAY` 的窗口
 * 在**整个矩形范围内**拦截触摸，哪怕那块像素是透明的。如果一直用气泡的宽度，
 * 桌宠收着的时候也会挡住它旁边一大片区域的点击——用户会以为「屏幕上有个洞」。
 * 展开时才把窗口撑到 [BUBBLE_WIDTH_DP]。
 *
 * ## 焦点与键盘
 *
 * 收起态带 `FLAG_NOT_FOCUSABLE`：不抢焦点，不打断用户正在用的应用。
 * 展开态必须去掉这一位，否则输入框点不出软键盘。去掉后补 `FLAG_NOT_TOUCH_MODAL`，
 * 让窗口外的触摸照常穿透到下面的应用。
 *
 * 这是 spec §2.2 说的那个坑的一半：**能弹出来**。另一半（各家 ROM 的键盘高度、
 * 焦点抢占）只能真机试，一期接受「能用但可能不完美」。
 */
class PetOverlayHost(
    private val context: Context,
    /** 问一句。由 [PetOverlayService] 注入，测试可换成假实现。 */
    private val ask: suspend (String) -> PetReply,
    /** 一轮回答出结果后的副作用（朗读）。失败与空输入不朗读，由调用方判断。 */
    private val onReply: (PetReply) -> Unit,
    private val onClose: () -> Unit,
    private val onOpenApp: () -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val owner = OverlayOwner()

    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * 贴左还是贴右；展开时按这个方向对齐，避免气泡把形象挤出屏幕。
     *
     * 用 Compose 可观察状态而不是普通字段：气泡展开会让窗口从 84dp 变成 296dp，
     * 如果形象仍居中，它会从贴边的位置往屏幕中间「跳」一下。让内容跟着贴边方向
     * 排，形象就原地不动。
     */
    var snappedLeft: Boolean by mutableStateOf(false)
        private set

    /** Compose 读这个值决定渲染气泡还是只渲染形象。改动只走 [applyExpanded]。 */
    var expanded: Boolean by mutableStateOf(false)
        private set

    fun show() {
        if (view != null) return
        // 主题是全局单例，Service 里没人替它读过盘。不 load 会拿到默认配色，
        // 用户改了强调色、桌宠却还是旧的。
        runCatching { AppearanceState.load(context) }
        owner.attach()

        val sizePx = px(PET_SIZE_DP)
        val lp = WindowManager.LayoutParams(
            sizePx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // 收起态：不抢焦点 + 不拦截外部触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screen = screenSize()
            x = (screen.x - sizePx).coerceAtLeast(0)
            y = (screen.y * 0.62f).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }

        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                AgentKitTheme(
                    themeMode = AppearanceState.themeMode,
                    dynamicColor = AppearanceState.dynamicColor,
                    accent = AppearanceState.accent
                ) {
                    PetPanel(
                        expanded = expanded,
                        petSize = PET_SIZE_DP,
                        bubbleWidth = BUBBLE_WIDTH_DP,
                        alignEnd = !snappedLeft,
                        ask = ask,
                        onExpandedChange = ::applyExpanded,
                        onReply = onReply,
                        onClose = {
                            onClose()
                        },
                        onOpenApp = onOpenApp,
                        onDrag = ::moveBy,
                        onDragEnd = ::snapToEdge
                    )
                }
            }
        }

        val added = runCatching { windowManager.addView(compose, lp) }
        if (added.isFailure) {
            AgentLogger.w(TAG, "悬浮窗添加失败：${added.exceptionOrNull()?.message}")
            owner.detach()
            return
        }
        view = compose
        params = lp
        compose.post { snapToEdge() }
    }

    fun hide() {
        scope.cancel()
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
        params = null
        expanded = false
        owner.detach()
    }

    /**
     * 展开 / 收起：窗口宽度与焦点标志一起改，缺一样都会出问题。
     *
     * 名字故意不是 `setExpanded`：`expanded` 是 `var ... private set`，Kotlin 会为它
     * 生成 `setExpanded(Z)V`，同名函数在 JVM 层直接签名冲突（CI 第一次就是这么挂的：
     * `Platform declaration clash: setExpanded(Z)V`）。本地没有 Android SDK，
     * 这类只在字节码层暴露的冲突靠肉眼看不出来。
     */
    private fun applyExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val lp = params ?: return
        val screen = screenSize()
        lp.width = px(if (value) BUBBLE_WIDTH_DP else PET_SIZE_DP)
        lp.flags = if (value) {
            // 能获焦（否则软键盘不弹），但仍不拦截窗口外的触摸
            (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        lp.softInputMode = if (value) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        clampX(screen, lp.width)
        update(lp)
        // 高度是 WRAP_CONTENT，要等这一帧量完才知道会不会顶出屏幕底边
        view?.post {
            val v = view ?: return@post
            val h = v.height
            if (h > 0) {
                val maxY = (screen.y - h).coerceAtLeast(0)
                if (lp.y > maxY) {
                    lp.y = maxY
                    update(lp)
                }
            }
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val screen = screenSize()
        val w = view?.width?.takeIf { it > 0 } ?: lp.width
        val h = view?.height?.takeIf { it > 0 } ?: px(PET_SIZE_DP)
        lp.x = (lp.x + dx.toInt()).coerceIn(0, (screen.x - w).coerceAtLeast(0))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, (screen.y - h).coerceAtLeast(0))
        update(lp)
    }

    /** 松手吸附到最近的左右边缘（spec 一期：拖动自由移动，松手贴边）。 */
    private fun snapToEdge() {
        val lp = params ?: return
        val screen = screenSize()
        val w = view?.width?.takeIf { it > 0 } ?: lp.width
        snappedLeft = lp.x + w / 2 < screen.x / 2
        lp.x = if (snappedLeft) 0 else (screen.x - w).coerceAtLeast(0)
        update(lp)
    }

    private fun clampX(screen: Point, width: Int) {
        val lp = params ?: return
        lp.x = if (snappedLeft) 0 else (screen.x - width).coerceAtLeast(0)
    }

    private fun update(lp: WindowManager.LayoutParams) {
        val v = view ?: return
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenSize(): Point = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getSize(p)
            p
        }
    }.getOrDefault(Point(1080, 1920))

    private fun px(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    /** 朗读。失败不影响气泡——文字已经在屏幕上了。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch {
            runCatching { AudioGraph.speaker(context).speak(text) }
                .onFailure { AgentLogger.w(TAG, "桌宠朗读失败：${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PetOverlay"
        const val PET_SIZE_DP = 84
        const val BUBBLE_WIDTH_DP = 296
    }
}

/**
 * `addView` 的视图不在任何 Activity 的视图树里，三个 owner 都得自己实现。
 *
 * 合并成一个类而不是三个匿名对象：它们共享同一个生命周期，拆开只会让
 * [attach] / [detach] 的调用顺序更难维护。
 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedController = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    fun attach() {
        // 顺序不能反：performRestore 要求 registry 尚未越过 CREATED，
        // 而 performAttach 必须在 performRestore 之前（ComponentActivity 同款）。
        savedController.performAttach()
        savedController.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun detach() {
        runCatching {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        runCatching { viewModelStore.clear() }
    }
}
