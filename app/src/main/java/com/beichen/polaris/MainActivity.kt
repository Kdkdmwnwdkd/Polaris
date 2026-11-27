package com.beichen.polaris

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.beichen.polaris.core.CrashLogger
import com.beichen.polaris.browser.BrowserHost
import com.beichen.polaris.pet.PetState
import com.beichen.polaris.startup.StartupGuard
import com.beichen.polaris.startup.StartupQuarantine
import com.beichen.polaris.ui.AgentKitTheme
import com.beichen.polaris.ui.AppearanceState
import com.beichen.polaris.ui.AppScaffold
import com.beichen.polaris.ui.ApprovalHost
import com.beichen.polaris.ui.ChatViewModel
import com.beichen.polaris.ui.StartupRescueScreen
import com.beichen.polaris.ui.ThemeMode
import com.beichen.polaris.workflow.WorkflowNotifier

/**
 * ChatViewModel 的显式工厂（2026-11-27 真机）。
 *
 * 换掉默认工厂的理由有两个，都是被真机逼出来的：
 *
 * ① **让失败留下原因**。默认工厂抛的是 `RuntimeException: Cannot create an instance of
 *    class ChatViewModel`，真正的原因在 `cause` 链里；崩溃处理器记录的就是这层包装，
 *    用户发过来的报告通篇一句「建不出来」——等于没证据。
 * ② **别让一次脏状态判死刑**。真机上十次崩溃全是同一个错，而中间有两次启动是成功的
 *    （00:29:05、09:31:36 都正常进到了对话页）——说明它不是「数据坏了就必崩」的确定性
 *    故障，而是构造路径上某一步在特定状态下会抛。这种前提下直接崩掉整个应用是最差的选择。
 *
 * 所以这里做一次**无损重试**：第一次失败后只清「上次跑到哪一步」这类可再生状态
 * （运行态快照 / 回合日志 / 任务队列 / 草稿），会话正文一个字不动，然后再建一次。
 * 仍失败才上抛——那时交给救援页，由用户决定要不要动业务状态。
 */
private val chatVmFactory = viewModelFactory {
    initializer {
        val app = this[APPLICATION_KEY] as Application
        buildChatViewModel(app)
    }
}

/** 两次尝试：原样 → 清运行态。两次都失败才抛，异常带上两次的 cause 链。 */
private fun buildChatViewModel(app: Application): ChatViewModel {
    val first = runCatching { ChatViewModel(app) }
    first.getOrNull()?.let { return it }
    val firstError = first.exceptionOrNull() ?: IllegalStateException("ChatViewModel 构造失败且无异常")
    runCatching { CrashLogger.recordCaught(app, "ChatViewModel 构造失败（第 1 次·原样）", firstError) }

    // 只清可再生状态：这几项丢了最多让上次的活儿重跑一遍，不会少一条会话。
    clearVolatileState(app)
    val second = runCatching { ChatViewModel(app) }
    second.getOrNull()?.let { return it }
    val secondError = second.exceptionOrNull() ?: IllegalStateException("ChatViewModel 构造失败且无异常")
    runCatching { CrashLogger.recordCaught(app, "ChatViewModel 构造失败（第 2 次·清运行态后）", secondError) }

    // 第 2 次才是「当前状态」的真实原因；第 1 次多半是被同一份状态连累的。
    // 把第 1 次挂到 suppressed 上，两段堆栈都在，读的人不用猜哪次更能代表问题。
    if (secondError !== firstError) runCatching { secondError.addSuppressed(firstError) }
    throw secondError
}

/**
 * 清掉「下次进来还得重新算、但不含用户内容」的状态。
 *
 * 白名单而不是黑名单：这里每加一项都是一次潜在的数据损失，
 * 所以只允许「丢了最多让上次的活儿重跑一遍」的东西进来。
 * 会话（conversations.json / conversations.d）与记忆、提示词**不在**此列。
 */
private fun clearVolatileState(app: Application) {
    val files = app.filesDir ?: return
    listOf("agent_run_state.json", "drafts.json").forEach { name ->
        runCatching { java.io.File(files, name).delete() }
    }
    listOf("run-journal", "jobs").forEach { name ->
        runCatching { java.io.File(files, name).deleteRecursively() }
    }
}

class MainActivity : ComponentActivity() {

    /**
     * 通知点击带来的待执行任务 id（W-17）。
     *
     * 用 Compose State 而不是「读一次就丢」：App 已在前台时点通知会走 [onNewIntent]，
     * 这时 Activity 不会重建，必须靠状态变化驱动 Compose 重新消费。
     */
    private val pendingWorkflowId = mutableStateOf<String?>(null)

    /**
     * 外部要求直达的页面路由（2026-11-14）。
     *
     * 用途：桌宠悬浮窗的「去换引擎」按钮。悬浮窗不是 Activity，没法自己导航，
     * 只能拉起本 Activity 并带上目标页。用 Compose State 的理由同上——
     * App 在前台时点这个按钮走 [onNewIntent]，Activity 不重建。
     */
    private val pendingDest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWorkflowId.value = intent?.getStringExtra(WorkflowNotifier.EXTRA_WORKFLOW_ID)
        pendingDest.value = intent?.getStringExtra(EXTRA_DEST)
        // ViewModel 在这里建，不在 `AgentKitApp` 里用 `viewModel()`。
        //
        // 差别只有一处，但这一处决定了「失败之后还有没有路」：`viewModel()` 是 composable，
        // 构造抛异常发生在**组合期**，只能一路冒到 `setContent` 之外——也就是崩溃、白屏、
        // 回桌面。挪到组合之前，异常就落在一个能接住的 `Result` 里，界面还能画出来。
        // 真机上用户要的正是这个：不是「永远不崩」，而是崩了能看见原因、能复制、能自救。
        val vmAttempt = runCatching {
            ViewModelProvider(this, chatVmFactory)[ChatViewModel::class.java]
        }
        setContent {
            val vm = vmAttempt.getOrNull()
            if (vm != null) {
                AgentKitApp(
                    vm = vm,
                    pendingWorkflowId = pendingWorkflowId.value,
                    onWorkflowConsumed = { pendingWorkflowId.value = null },
                    pendingDest = pendingDest.value,
                    onDestConsumed = { pendingDest.value = null },
                )
            } else {
                RescueApp(
                    cause = vmAttempt.exceptionOrNull(),
                    onRetry = {
                        clearVolatileState(application)
                        recreate()
                    },
                    onResetBusinessState = {
                        // 重手段：只搬不删，目录里有 README 说怎么搬回来。
                        // 日志与崩溃报告不在搬移清单里（StartupQuarantine.MOVABLE 有断言钉着）。
                        runCatching { StartupQuarantine.move(filesDir, System.currentTimeMillis()) }
                        recreate()
                    },
                )
            }
        }
        // W-15 的后台 WebView：**首帧画完之后**再建，别挡在首帧前面。
        //
        // 修前在 `setContent` 之前 attach，于是「创建 WebView」这件事被 `main.post`
        // 排进主线程队列，排在 Compose 首帧之前。而 WebView 的构造必须在主线程，
        // 冷启动时它可能要 20~30 秒（首次要拉起 chromium provider）。主线程被占满这
        // 30 秒，界面一帧都出不来——系统等不到 focused window，5 秒后判 ANR：
        //   E ActivityManager: Completed ANR of com.beichen.polaris in 30580ms
        //   W ActivityTaskManager: Force finishing activity com.beichen.polaris/.MainActivity
        // 现象是「点开 App 直接被弹回桌面」，用户只会当成 App 闪退。
        //
        // 改成在首帧的 onDraw 回调里排队：首帧已经画完、窗口有了焦点，后面主线程再
        // 忙也只是「界面可见、暂时不响应」，不会变成「打不开」。代价是 web_* 工具在
        // 首帧之后才可用——它本来就只在用户主动调用浏览器工具时才需要。
        window.decorView.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    // ⚠️ 不能在 onDraw 里**直接** removeOnDrawListener：ViewTreeObserver
                    // 正在派发时会抛 IllegalStateException("Cannot call removeOnDrawListener
                    // inside of onDraw")，首帧一画就崩。这个坑是本机真机验证抓到的
                    // （崩溃栈：MainActivity$onCreate$2.onDraw），不是猜的。
                    // 正确做法：派发结束后再摘——post 一次即可。
                    window.decorView.post {
                        window.decorView.viewTreeObserver.removeOnDrawListener(this)
                        BrowserHost.attach(this@MainActivity)
                    }
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 前台时点通知：不重建 Activity，靠这个状态触发执行
        pendingWorkflowId.value = intent.getStringExtra(WorkflowNotifier.EXTRA_WORKFLOW_ID)
        // 前台时从悬浮窗点「去换引擎」：同样不重建，靠状态触发导航
        pendingDest.value = intent.getStringExtra(EXTRA_DEST)
    }

    companion object {
        /** 拉起本 Activity 并直达某页的路由键（值必须是 [com.beichen.polaris.ui.AppDest.route]）。 */
        const val EXTRA_DEST = "polaris.dest"
    }

    /**
     * 桌面助手的「补启动」时机。
     *
     * 用户开了开关、但服务在上次进程里被系统杀了（省电策略 / 一键清理），
     * 回到 App 就是唯一无争议的补救窗口——此时 `startForegroundService` 不会撞上
     * Android 12+ 的后台启动限制。放在 `onResume` 而不是 `onCreate`：从悬浮窗
     * 点「打开主应用」回来时 Activity 不会重建，onCreate 不会跑。
     */
    override fun onResume() {
        super.onResume()
        runCatching { PetState.ensureRunning(this) }
    }

    override fun onDestroy() {
        // 释放 WebView，避免持有 Activity 造成泄漏
        BrowserHost.release()
        super.onDestroy()
    }
}

/**
 * 救援页主题壳。
 *
 * 复用 [AgentKitTheme] 是为了「崩了」这件事看起来不像另一个 App——用户的判断依据
 * 往往就是「还是不是那个界面」。但外观偏好的读取要单独兜住：救援页本身绝不能再崩。
 */
@Composable
private fun RescueApp(
    cause: Throwable?,
    onRetry: () -> Unit,
    onResetBusinessState: () -> Unit,
) {
    val ctx = LocalContext.current
    remember { runCatching { AppearanceState.load(ctx.applicationContext) }; true }
    AgentKitTheme(
        themeMode = AppearanceState.themeMode,
        dynamicColor = AppearanceState.dynamicColor,
        accent = AppearanceState.accent,
    ) {
        StartupRescueScreen(
            cause = cause,
            onRetry = onRetry,
            onResetBusinessState = onResetBusinessState,
        )
    }
}

/**
 * 应用根：品牌主题 + 抽屉导航宿主 [AppScaffold]。
 *
 * 主题模式用 rememberSaveable 持有在** Activity 作用域**（而非各页内），
 * 设置页改选项、全 App 立刻跟着变；旋转屏幕 / 进程被系统回收后重建时不丢选择。
 * 偏好不落盘——日用助手天天对话，主题不值得一次磁盘写（见 [ThemeMode] 注释）。
 *
 * 功能五（P1-10）：动态取色开关与 [themeMode] 同纪律（进程内有效不落盘），
 * 默认关——「暖纸」品牌色是 deliberate 设计，壁纸取色是 opt-in 覆盖。
 */
@Composable
private fun AgentKitApp(
    vm: ChatViewModel,
    pendingWorkflowId: String? = null,
    onWorkflowConsumed: () -> Unit = {},
    pendingDest: String? = null,
    onDestConsumed: () -> Unit = {},
) {
    // W-64：外观偏好落盘后由 AppearanceState 统一持有。
    // 这里只在首次组合时读一次盘；之后改值走 setXxx（内部会写盘并触发重组）。
    val ctx = LocalContext.current
    remember { AppearanceState.load(ctx.applicationContext); true }
    val themeMode = AppearanceState.themeMode
    val dynamicColor = AppearanceState.dynamicColor
    val accent = AppearanceState.accent
    // 启动自救的第二个事实：**根组合成功**。LaunchedEffect 在整棵树组合并应用变更后
    // 才启动，所以子组件（ChatScreen）抛异常时它不会跑——正是「这次没起来」的语义。
    // 少了这一步，StartupGuard 只会看到「每次都开始了、永远没成功」，把正常使用也隔离掉。
    LaunchedEffect(Unit) { StartupGuard.markBooted(ctx.applicationContext) }
    // W-17：点通知进入 → 自动把该任务的 prompt 作为一次对话发送
    LaunchedEffect(pendingWorkflowId) {
        if (pendingWorkflowId != null) {
            vm.consumeWorkflowIntent(pendingWorkflowId)
            onWorkflowConsumed()
        }
    }
    AgentKitTheme(themeMode = themeMode, dynamicColor = dynamicColor, accent = accent) {
        AppScaffold(
            vm = vm,
            themeMode = themeMode,
            onThemeModeChange = { AppearanceState.setThemeMode(ctx, it) },
            dynamicColor = dynamicColor,
            onDynamicColorChange = { AppearanceState.setDynamicColor(ctx, it) },
            initialRoute = pendingDest,
            onRouteConsumed = onDestConsumed,
        )
        // 审计 F16：审批是全局模态，挂在 Scaffold 之外——路由停在权限引导/控制台/设置页
        // 时它照样出现。修前挂在 ChatScreen 里，非对话页触发的审批只能等 30 秒超时被拒。
        ApprovalHost(vm = vm)
    }
}
