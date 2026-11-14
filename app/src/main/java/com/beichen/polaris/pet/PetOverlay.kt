package com.beichen.polaris.pet

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.beichen.polaris.core.voice.SpeechRemedy
import com.beichen.polaris.ui.AgentKitTheme
import com.beichen.polaris.ui.AppearanceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /**
     * 语音状态。
     *
     * 用 `mutableStateOf` 而不是 `StateFlow`：所有写入都在主线程（[PetVoiceInput] 的回调
     * 由 `SpeechRecognizer` 保证在主线程），Compose 直接读就能观察，少一层 collect 和
     * 一次 `collectAsState` 的生命周期绑定。
     */
    var voiceState: PetVoiceState by mutableStateOf(PetVoiceState.Idle)
        private set

    /** 一次性提示（没权限 / 没听清 / 麦克风被占）。[NOTICE_MS] 后自动清空。 */
    var notice: String? by mutableStateOf(null)
        private set

    /**
     * 提示附带的可点击动作（如「去授权」）。null = 提示只是文字。
     *
     * 为什么需要它：缺麦克风权限时，光提示「去系统设置给『北辰』打开麦克风」是不够的——
     * 用户得自己去设置里找到这个 App、找到权限项、点开。而**桌宠是常驻悬浮窗，
     * 本身没有 Activity**，没法自己弹权限申请框（Compose 的权限 launcher 需要 Activity）。
     *
     * 所以这里给一条出路：点提示直接跳到本应用的系统权限页，一步到位。
     */
    var noticeAction: (() -> Unit)? by mutableStateOf(null)
        private set

    /**
     * 展开后要不要把焦点塞进输入框。
     *
     * 默认 true（点一下形象展开，用户多半是想打字）。两类情况必须为 false：
     * - **语音唤起**：用户张着嘴准备说话，屏幕上却弹起半屏键盘，遮挡的正是他自己的
     *   视线；声音一进来还可能被键盘的提示音盖住。
     * - **瞬时提示**：只为了让用户看见一句话而展开，抢焦点纯属打扰。
     *
     * 注意这不是「能不能打字」的开关——窗口照样可获焦，用户想打字点输入框即可。
     */
    var autoFocusInput: Boolean by mutableStateOf(true)
        private set

    /**
     * 语音识别出的待发送文本。
     *
     * 为什么要绕这一道：`send` 是 [PetPanel] 的内部闭包（它要改 messages / sending 这些
     * 局部状态），宿主拿不到。所以识别结果先落到这里，由 PetPanel 用 `LaunchedEffect`
     * 消费并回调 [consumeVoicePrompt] 清空——一次性事件的常见写法。
     */
    var voicePrompt: String? by mutableStateOf(null)
        private set

    private var voice: PetVoiceInput? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val clearNotice = Runnable {
        notice = null
        noticeAction = null
    }

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
                        voiceState = voiceState,
                        notice = notice,
                        noticeAction = noticeAction,
                        voicePrompt = voicePrompt,
                        autoFocusInput = autoFocusInput,
                        onVoicePromptConsumed = ::consumeVoicePrompt,
                        onExpandedChange = { applyExpanded(it) },
                        onReply = onReply,
                        onClose = {
                            onClose()
                        },
                        onOpenApp = onOpenApp,
                        onDrag = ::moveBy,
                        onDragEnd = ::snapToEdge,
                        onVoiceStart = { voice?.start() },
                        onVoiceStop = { voice?.stop() }
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

        // 语音引擎与窗口同生同灭。放在 addView 之后：加窗失败就没必要建它。
        // 构造时不创建 SpeechRecognizer（只在 start() 里建），所以这一步是零成本的。
        voice = PetVoiceInput(
            context = context,
            onState = { voiceState = it },
            onText = { text ->
                // 不在这里直接发：PetPanel 的 send 还管着消息列表、气泡滚动、朗读，
                // 绕过去就会变成「语音发的消息不进历史」。
                voicePrompt = text
            },
            onProblem = { message -> showNotice(message) },
            onProblemWithCode = { message, remedy ->
                // 缺权限是唯一「当场就能解决」的一种：给一条直达系统权限页的出路。
                // 其他原因（没服务 / 被占用 / 没听清）不给按钮——给了也只是让人多点一次。
                //
                // 注意这里必须写成单行 if 表达式：写成带花括号的分支时，
                // Kotlin 编译器会在生成可调用引用上崩（ICE）——已复现。
                showNotice(
                    message,
                    when (remedy) {
                        // 缺权限：直达本应用的权限页。
                        SpeechRemedy.GrantMicPermission -> ({ openAppPermissionSettings() })
                        // 换引擎：直达**本应用**的语音识别页。
                        // 系统「语音识别」设置项在部分机型上根本不存在
                        // （2026-11-14 真机截图：默认应用里只有「文字转语音输出」），
                        // 所以出路必须是我们自己的页面。
                        SpeechRemedy.SwitchEngine -> ({ openVoiceEngineSettings() })
                        // 系统没选定默认引擎：那一项确实在系统设置里，跳系统页。
                        SpeechRemedy.SelectDefaultEngine -> ({ openVoiceInputSettings() })
                        else -> null
                    },
                )
            }
        )

        // 起来就问一遍「哪个引擎认中文」，把能用的那个接上。
        // 真机上最常见的失败不是没装引擎，而是装了好几个、默认那个不认中文
        // （Google TTS 自带的识别服务要单独下中文离线包）。探测到就不去麻烦用户。
        voice?.refreshEngine()

        compose.post { snapToEdge() }
    }

    fun hide() {
        mainHandler.removeCallbacks(clearNotice)
        // 先停语音再拆窗口：录音中拆窗会让 SpeechRecognizer 的回调打到已销毁的 composition，
        // 而麦克风要等 20 秒硬超时才释放——用户看到的是「桌宠没了，但麦克风还占着」。
        voice?.destroy()
        voice = null
        voiceState = PetVoiceState.Idle
        voicePrompt = null
        notice = null
        scope.cancel()
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null
        params = null
        expanded = false
        // 下次展开默认回到「点开就能打字」的行为，别把这一次的语音意图留在状态里
        autoFocusInput = true
        owner.detach()
    }

    /** PetPanel 消费完一条语音结果后调用。 */
    fun consumeVoicePrompt() {
        voicePrompt = null
    }

    /**
     * 从外部叫它听一句（磁贴 / 通知按钮 / 以后的任何入口）。
     *
     * 语义是「按一下麦克风」而不是「开始一段新录音」：
     * - 空闲时 → 展开气泡并开始听；
     * - 已经在听 → 当作「我说完了」，让引擎尽快出结果（与气泡里那个麦克风按钮同一行为）。
     *
     * 展开时**不抢输入焦点**（[autoFocusInput] = false）：用户张着嘴，屏幕上弹半屏键盘
     * 既挡视线又盖住声音反馈。窗口本身仍可获焦，想打字点一下输入框就行。
     */
    fun beginVoice() {
        if (view == null) {
            AgentLogger.w(TAG, "悬浮窗未就绪，忽略聆听请求")
            return
        }
        val v = voice ?: run {
            showNotice("语音还没准备好，稍等一下再说")
            return
        }
        if (voiceState !is PetVoiceState.Idle) {
            // 正在听（或正在出结果）：stop 是「说完了」，Transcribing 下它是空操作
            v.stop()
            return
        }
        applyExpanded(true, focusInput = false)
        if (!v.start()) {
            // start() 失败时已经通过 onProblem → showNotice 给了可执行的原因，这里不重复
            AgentLogger.i(TAG, "聆听请求未被接受")
        }
    }

    /**
     * 显示一条瞬时提示，并把气泡展开——收起态下气泡不渲染，用户什么都看不到。
     *
     * 重复调用时重置计时：连续两次「没听清」，第二次的提示应该完整停留 5 秒，
     * 而不是被第一次的定时器提前清掉（清之前比对内容的那套在这里不需要，
     * 因为 removeCallbacks 已经保证只有一个 pending 的清除任务）。
     */
    /**
     * 跳到本应用的系统权限页。
     *
     * 为什么不是「申请权限」：桌宠是常驻悬浮窗，没有 Activity 上下文，
     * 而 Compose 的 `RequestPermission` launcher 必须挂在 Activity 上。
     * 从 Service 里直接拉起系统的权限设置页是可行的——系统会显示这个 App 的
     * 权限列表，用户点一下麦克风开关即可。
     *
     * `ACTION_APPLICATION_DETAILS_SETTINGS` 而不是 `ACTION_MANAGE_APP_ALL_PERMISSIONS`：
     * 后者在部分 ROM 上不存在，前者从 Android 9 起一直都在。
     */
    private fun openAppPermissionSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                AgentLogger.w(TAG, "打开应用权限页失败：${it.message}")
                showNotice("打不开系统权限页，请手动到系统设置里给「北辰」打开麦克风")
            }
    }

    /**
     * 直达本应用的「语音识别」页。
     *
     * 这是「引擎不认中文」的首选出路，也是 2026-11-14 真机截图之后的改法：
     * 原来这里跳的是系统语音设置，而用户的机器上**根本没有那一项**
     * （「默认应用」里只有「文字转语音输出」）。指路一个不存在的页面，
     * 等于让用户白跑一趟再回来。
     *
     * 我们自己的页面列的是真实引擎清单，点一下就能指定——
     * 这条出路不依赖任何 ROM 是否把语音识别做成设置项。
     *
     * 悬浮窗拉起 Activity 必须带 `NEW_TASK`；带 `SINGLE_TOP` 是为了复用它，
     * 而不是在任务栈里堆第二个 MainActivity。
     */
    private fun openVoiceEngineSettings() {
        val intent = Intent(context, com.beichen.polaris.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(
                com.beichen.polaris.MainActivity.EXTRA_DEST,
                com.beichen.polaris.ui.AppDest.VOICE.route,
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                AgentLogger.w(TAG, "打开本应用语音页失败：${it.message}")
                // 兜底：至少把人送到一个能改东西的地方，而不是什么都不发生。
                openVoiceInputSettings()
            }
    }

    /**
     * 跳到系统的「语音输入」设置。
     *
     * 只在 [openVoiceEngineSettings] 失败时兜底用。保留它是因为
     * 有些 ROM **确实**有这一项，而我们的页面在极端情况下（Activity 被禁用）也可能拉不起来。
     *
     * 两个候选按顺序试：
     * 1. `ACTION_VOICE_INPUT_SETTINGS`——就是语音输入设置，但**不是所有 ROM 都有**
     *    （AOSP 有，部分定制 ROM 删掉了）。
     * 2. 本应用的详情页——兜底。至少用户能在附近找到权限项，
     *    比点了没反应强。
     */
    private fun openVoiceInputSettings() {
        val candidates = listOf(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        AgentLogger.w(TAG, "打不开语音输入设置页")
        showNotice("打不开系统设置，请手动到 系统设置 → 默认应用 → 语音识别 里换一个服务")
    }

    private fun showNotice(text: String, action: (() -> Unit)? = null) {
        notice = text
        noticeAction = action
        // 只为让人看一眼文字而展开，不该顺手弹出键盘
        applyExpanded(true, focusInput = false)
        mainHandler.removeCallbacks(clearNotice)
        mainHandler.postDelayed(clearNotice, NOTICE_MS)
    }

    /**
     * 展开 / 收起：窗口宽度与焦点标志一起改，缺一样都会出问题。
     *
     * @param focusInput 展开后是否让 [PetPanel] 把焦点塞进输入框。点形象展开用 true；
     *        语音唤起与瞬时提示用 false（理由见 [autoFocusInput]）。
     *
     * 名字故意不是 `setExpanded`：`expanded` 是 `var ... private set`，Kotlin 会为它
     * 生成 `setExpanded(Z)V`，同名函数在 JVM 层直接签名冲突（CI 第一次就是这么挂的：
     * `Platform declaration clash: setExpanded(Z)V`）。本地没有 Android SDK，
     * 这类只在字节码层暴露的冲突靠肉眼看不出来。
     */
    private fun applyExpanded(value: Boolean, focusInput: Boolean = true) {
        if (expanded == value) {
            // 已经展开：只更新焦点意图，宽度不用再动
            if (value) autoFocusInput = focusInput
            return
        }
        expanded = value
        if (value) autoFocusInput = focusInput
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

    /**
     * 朗读。失败不影响气泡——文字已经在屏幕上了。
     *
     * **必须走仲裁器，不能直接 `speaker.speak`。** 直接调引擎看起来只是少一层，
     * 实际会丢掉两件事：① 播报不会登记成 `AudioHolder.Speak`，仲裁器不知道有声音在响；
     * ② 用户此时长按说话，`beginCapture` 拿得到麦克风，但 `revoked` 不会触发，
     * 于是「朗读停不下来 + 录音把自己念的也录进去」同时发生。
     *
     * 第二个坑是**线程**：本类的 `scope` 是 `AppScope.io`，而 `TextSpeaker` 的约定是
     * 「speak / stop 都在主线程」（它的音频焦点字段和 `mainHandler` 都是按这个假设写的）。
     * 所以这里显式切回 Main——`arbiter.speak` 内部会碰 AudioManager 与 TTS 引擎，
     * 在 IO 线程调不报错，但焦点请求与 `onDone` 回调的时序会变得不可预测。
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.Main) {
            AudioGraph.arbiter(context).speak(text)
                .onFailure { AgentLogger.w(TAG, "桌宠朗读失败：${it.message}") }
        }
    }

    private companion object {
        const val TAG = "PetOverlay"
        const val PET_SIZE_DP = 84
        const val BUBBLE_WIDTH_DP = 296

        /**
         * 提示停留时长。
         *
         * 5 秒：足够读完「没听清，再说一次试试」（11 个字），又不至于让上一次的错误
         * 挂在用户已经开始的新一轮上面。与 `ChatViewModel.INTERRUPT_NOTICE_MS` 取同一个值，
         * 两处提示在用户眼里的节奏应该一致。
         */
        const val NOTICE_MS = 5_000L
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
