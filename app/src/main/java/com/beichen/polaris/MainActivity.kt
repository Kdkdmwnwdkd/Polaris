package com.beichen.polaris

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beichen.polaris.browser.BrowserHost
import com.beichen.polaris.pet.PetState
import com.beichen.polaris.ui.AgentKitTheme
import com.beichen.polaris.ui.AppearanceState
import com.beichen.polaris.ui.AppScaffold
import com.beichen.polaris.ui.ApprovalHost
import com.beichen.polaris.ui.ChatViewModel
import com.beichen.polaris.ui.ThemeMode
import com.beichen.polaris.workflow.WorkflowNotifier

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
        setContent {
            AgentKitApp(
                pendingWorkflowId = pendingWorkflowId.value,
                onWorkflowConsumed = { pendingWorkflowId.value = null },
                pendingDest = pendingDest.value,
                onDestConsumed = { pendingDest.value = null },
            )
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
    val vm = viewModel<ChatViewModel>()
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
