package com.beichen.polaris.ui

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.ConversationSummary
import com.beichen.polaris.core.CrashLogger
import java.util.Calendar

/**
 * 一级目的地全量路由表（UX v1 §1.7）：15 个值全部保留，页面都还能到。
 *
 * 用户反馈侧边栏拥挤（塞了 15 个入口），参照 RikkaHub 极简风只留 4 个常用入口在
 * 抽屉里，其余（容器 / 编辑器 / MCP / 创作工坊 / 构建 / 权限 / 技能 / 日志 /
 * 记忆 / 关于）整体收进设置页——入口搬家，路由一个不动。
 *
 * @param route 路由键（UX v1 §1.7 路由表直译，用于 rememberSaveable 持久化）。
 * @param label 菜单项文案（单行中文，§6.5.2）。
 * @param title 顶栏标题（§6.5.4；「模型」项的页面标题是「模型配置」）。
 * @param icon 24dp 线性图标（1.5dp stroke 语汇，禁 emoji）。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val title: String,
    val icon: ImageVector
) {
    CHAT("chat", "对话", "对话", Icons.Outlined.Chat),
    MODEL("model", "模型", "模型配置", Icons.Outlined.ViewInAr),
    CONSOLE("console", "控制台", "控制台", Icons.Outlined.Dashboard),
    TERMINAL("terminal", "终端", "终端", Icons.Outlined.Terminal),
    CONTAINER("container", "容器", "容器", Icons.Outlined.ViewInAr),
    EDITOR("editor", "编辑器", "编辑器", Icons.Outlined.Edit),
    MCP("mcp", "MCP", "MCP", Icons.Outlined.Cloud),
    MEDIA("media", "创作", "创作工坊", Icons.Outlined.Palette),
    BUILD("build", "构建", "构建", Icons.Outlined.Build),
    PERMISSIONS("permissions", "权限中心", "权限中心", Icons.Outlined.Shield),
    /** P0（2026-10-21）：权限引导页——第一次上手时逐项走一遍，与权限中心（排查页）分工不同。 */
    PERMISSION_GUIDE("permission-guide", "权限引导", "权限引导", Icons.Outlined.Shield),
    SKILLS("skills", "技能中心", "技能中心", Icons.Outlined.Extension),
    /** W-48：插件市场（远端可装清单；与技能中心分工见 SkillMarketScreen 注释）。 */
    MARKET("market", "插件市场", "插件市场", Icons.Outlined.Public),
    WORKFLOW("workflow", "定时任务", "定时任务", Icons.Outlined.Schedule),
    LOGS("logs", "运行日志", "运行日志", Icons.Outlined.Description),
    MEMORY("memory", "记忆库", "记忆库", Icons.Outlined.Storage),
    PROMPTS("prompts", "提示词库", "提示词库", Icons.Outlined.AutoAwesome),
    BOOKMARKS("bookmarks", "收藏", "收藏", Icons.Outlined.Bookmark),
    /** W-69：全部对话——抽屉只留最近 8 条，旧的按标题找不到了，补一个完整列表页。 */
    CONVERSATIONS("conversations", "全部对话", "全部对话", Icons.Outlined.History),
    SETTINGS("settings", "设置", "设置", Icons.Outlined.Settings),
    /** W-67：外观子页——壁纸与颜色原来在设置页占掉一整屏，拆成独立页。 */
    APPEARANCE("appearance", "壁纸与颜色", "壁纸与颜色", Icons.Outlined.Palette),
    ABOUT("about", "关于", "关于", Icons.Outlined.Info);

    companion object {
        fun fromRoute(route: String): AppDest = values().firstOrNull { it.route == route } ?: CHAT
    }
}

/**
 * 抽屉「主要」组：天天用（§6.5.2）。
 *
 * 用户反馈侧边栏拥挤，参照 RikkaHub 极简风瘦身为 4 个：对话 / 模型 / 控制台 / 终端。
 * 其余入口见设置页（[AppDest] 路由一个不少）。
 */
private val PrimaryGroup = listOf(
    AppDest.CHAT, AppDest.MODEL, AppDest.CONSOLE, AppDest.TERMINAL, AppDest.WORKFLOW
)

/**
 * 抽屉底部位：只放设置（§6.5.2）。
 *
 * 「关于」同批搬进设置页（设置页本来就有它的入口）——用户反馈侧边栏拥挤，
 * 底部位留一个语义自知的齿轮就够了，不配分组标题。
 */
private val BottomGroup = listOf(AppDest.SETTINGS)

/**
 * 抽屉底部固定栏的高度：分隔线 1dp + 抽屉项 52dp + 末尾留白 12dp。
 *
 * 滚动内容末尾要按它让位（底栏是叠在滚动内容上层的），所以两边必须同源；
 * 分散写两个魔数，早晚会出现「改了一边，最后一项被压住半个」。
 */
private val DrawerBottomBarHeight = 65.dp

/**
 * 功能一：返回栈上限（录屏反馈「别人按返回的返回到上一个地方，你这个是直接退出」）。
 * 超过上限丢最旧——最近的导航历史才有参考价值，也防无限增长。
 */
private const val BACK_STACK_MAX = 10

/** 功能一：根页连按两次返回退出 App 的间隔窗口（2s，主流 App 通行值）。 */
private const val BACK_EXIT_WINDOW_MS = 2_000L

/** 功能三：抽屉「最近对话」最多展示条数（录屏反馈「人家都有聊天记录对话记录」）。 */
private const val MAX_RECENT_CONVERSATIONS = 8

/**
 * 待授权项数：无障碍服务 / 通知访问 / 使用情况访问 三项中未就绪的数量。
 *
 * 抽屉就绪文字、控制台三宫格、权限中心页三处共用同一函数——同一状态源必须同一计数，
 * 否则用户会看到「抽屉说 3 项、页面说 2 项」的信任裂缝（§6.5.2「与权限中心红点同源」）。
 */
internal fun pendingGrantCount(
    serviceConnected: Boolean,
    notificationGranted: Boolean,
    usageGranted: Boolean
): Int = listOf(serviceConnected, notificationGranted, usageGranted).count { !it }

/**
 * 日志行是否为 ERROR 级。日志格式 `HH:mm:ss.SSS LEVEL [tag] message`（core/AgentLogger.format），
 * 级别固定位于第二段，按段匹配避免消息正文里出现 "ERROR" 造成误判。
 */
internal fun String.isErrorLogLine(): Boolean = split(' ').getOrNull(1) == "ERROR"

/**
 * App 级导航宿主（视觉规范 §6.5）：抽屉是唯一 App 级导航，承载 4 个常用一级目的地
 * （其余入口收在设置页——用户反馈侧边栏拥挤，参照 RikkaHub 极简风）。
 *
 * 职责边界：本文件只做「路由 + 抽屉 + 顶栏装配 + 进入页面时的数据刷新」，
 * 页面视觉由各屏幕文件自持（共享基件见 Components.kt，页面用 [PageTopBar] 画顶栏）。
 *
 * 双模态（UX v1 §4.2 断点 840dp）：
 * - 宽屏：抽屉常驻 280dp 无 scrim 无滑入，内容区 = min(屏宽 − 280dp, 680dp) 居中；
 * - 窄屏：overlay 抽屉 clamp(280dp, 82vw, 320dp)，250ms motionBase 滑入 / 200ms motionDialog 滑出，
 *   scrim 取 LocalAgentKitColors.current.scrim，点击 scrim / 选中一项 / 返回键关闭，
 *   左缘 24dp 热区支持跟手右滑（超过 1/3 吸附，150ms motionFast）。
 *
 * @param themeMode 明暗模式（设置页三选，由 MainActivity 用 rememberSaveable 持有）。
 * @param dynamicColor 功能五（P1-10）：Material You 动态取色开关（进程内有效）。
 * @param onDynamicColorChange 动态取色切换回调（写回宿主持有的状态）。
 */
@Composable
fun AppScaffold(
    // 组装根：全 App 唯一持有 ChatViewModel 具体类型并按窄接口分发；守卫只允许本文件出现该类型。
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {}
) {
    // 路由态：15 个一级目的地的唯一真相源（抽屉见 4+1，其余经设置页/控制台）；切换无转场动画（v3 无此 token，§6.5.2）。
    var route by rememberSaveable { mutableStateOf(AppDest.CHAT.route) }
    val dest = AppDest.fromRoute(route)

    // 功能一：导航返回栈（录屏反馈「按返回直接退出」）。
    // rememberSaveable 存不了 SnapshotStateList（AcceptableClasses 只认 Serializable /
    // Parcelable / String），所以是 MutableState<List<AppDest>> + 显式 Saver；
    // 为什么存 route 而不是 enum，见 AppBackStackSaver 的说明（删枚举值会白屏）。
    var backStack by rememberSaveable(stateSaver = BackStackSaver) {
        mutableStateOf(listOf<AppDest>())
    }
    // 功能一：根页「再按一次返回退出」的上一次按压时刻（0 = 从未按过）
    var backPressedAt by rememberSaveable { mutableStateOf(0L) }

    // —— 就绪文字 / 未读 ERROR 数据源（与权限中心页、控制台同源）——
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    // 功能三：会话摘要与当前会话 id（抽屉「最近对话」组消费）
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeConversationId by vm.activeConversationId.collectAsStateWithLifecycle()
    // hotfix30：正在生成的会话 id（抽屉显示「生成中」标识，可点到在途会话续看）
    val generatingConvId by vm.generatingConvId.collectAsStateWithLifecycle()
    val pendingGrants = pendingGrantCount(
        serviceConnected, notificationGranted, usageGranted
    )
    // 功能五（P1-4a）：会话导出走系统分享面板，需要 Context 发起 Intent
    val ctx = LocalContext.current

    // P0（2026-10-21）：模型配好后的首次进入，自动带一次权限引导。
    // 「跳过 / 完成」会落 guide_seen，所以只可能打扰一次；之后入口留在权限中心，不再自动弹。
    // W-70：就绪判定必须走 VM 的单一口径，否则本地模式（Base URL/Key 本就为空）
    // 会被当成「没配模型」，权限引导与聚光灯引导在这类用户身上永不触发。
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    var autoGuideChecked by remember { mutableStateOf(false) }
    LaunchedEffect(route, modelReady) {
        if (autoGuideChecked) return@LaunchedEffect
        if (!modelReady || route != AppDest.CHAT.route || guideSeen(ctx)) return@LaunchedEffect
        autoGuideChecked = true
        // 走正常的压栈路径：引导页按返回能回到对话，不会把用户困住
        if (backStack.size >= BACK_STACK_MAX) backStack = backStack.drop(1)
        backStack = backStack + dest
        route = AppDest.PERMISSION_GUIDE.route
    }

    // 未读 ERROR：进入日志页即标记已读（读后归零即隐藏，§6.5.2）
    var logsSeenCount by rememberSaveable { mutableStateOf(0) }
    val unreadErrors = remember(logs, logsSeenCount) {
        logs.take((logs.size - logsSeenCount).coerceAtLeast(0)).count { it.isErrorLogLine() }
    }
    // 未读崩溃：和上面那条不是同一个数据源——崩溃时进程直接死，内存里的日志一行都没落盘，
    // 所以 logs 里找不到那条 ERROR。不单独查一次，控制台入口就不亮，用户也就不会去日志页。
    //
    // 跟着 logsSeenCount 重算：日志页在「查看/关闭」崩溃横幅时会 markAllSeen，回到控制台
    // 时红点应当已经消失。读盘失败一律当 false（CrashLogger 内部 runCatching），
    // 宁可不提示，也不要在没有崩溃时吓人。
    val hasUnreadCrash = remember(logs, logsSeenCount, route) {
        runCatching { CrashLogger.hasUnseen(ctx) }.getOrDefault(false)
    }

    // 进入对应页面时刷新数据：权限/日志/记忆都是「打开就要看到最新」的排查型页面
    LaunchedEffect(dest) {
        when (dest) {
            AppDest.CONSOLE, AppDest.PERMISSIONS, AppDest.PERMISSION_GUIDE -> vm.refreshGrants()
            AppDest.LOGS -> {
                vm.refreshLogs()
                logsSeenCount = vm.logs.value.size
            }

            AppDest.MEMORY -> vm.refreshMemories()
            AppDest.WORKFLOW -> vm.refreshWorkflows()
            else -> Unit
        }
    }

    // —— 抽屉开合进度（0f..1f）——
    // 拖拽中 = 手势进度（snap 跟手）；松手/点击 = token 动画（openTarget 决定去向）
    var openTarget by remember { mutableStateOf(false) }
    var gestureProgress by remember { mutableStateOf<Float?>(null) }
    var settleSpec by remember { mutableStateOf<AnimationSpec<Float>>(AkMotionBase) }
    val progress by animateFloatAsState(
        targetValue = gestureProgress ?: if (openTarget) 1f else 0f,
        animationSpec = if (gestureProgress != null) snap() else settleSpec,
        label = "drawerProgress"
    )

    val ak = LocalAkColors
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val hamburgerFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }

    val openDrawer: () -> Unit = {
        openTarget = true
        settleSpec = AkMotionBase // 汉堡开启：250ms motionBase 滑入
    }
    val closeDrawer: () -> Unit = {
        openTarget = false
        settleSpec = AkMotionDialog // 关闭：200ms motionDialog 滑出
    }

    // 功能一：弹出返回栈顶（回上一页）。返回 false = 栈已空（根页）
    val popBack: () -> Boolean = {
        val last = backStack.lastOrNull()
        if (last != null) {
            backStack = backStack.dropLast(1)
            route = last.route
            true
        } else {
            false
        }
    }

    // 功能一：根页返回 = Toast 提示，2s 窗口内再按才 finish（防误触，主流 App 通行值）
    val onBackAtRoot: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - backPressedAt < BACK_EXIT_WINDOW_MS) {
            (context as? Activity)?.finish()
        } else {
            backPressedAt = now
            Toast.makeText(context, "再按一次返回退出", Toast.LENGTH_SHORT).show()
        }
    }

    // 抽屉打开：焦点移入第一项 + announce「已打开」；关闭：焦点返回汉堡 + announce「已关闭」（§6.5.5）
    var drawerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openTarget) {
        if (openTarget) {
            // 等一帧让抽屉真正组合出来（progress 越过 0 阈值），否则 FocusRequester 尚未初始化
            withFrameNanos { }
            runCatching { firstItemFocus.requestFocus() }
            view.announceForAccessibility("导航抽屉已打开")
        } else if (drawerWasOpen) {
            runCatching { hamburgerFocus.requestFocus() }
            view.announceForAccessibility("导航抽屉已关闭")
        }
        drawerWasOpen = openTarget
    }

    // hotfix33：全局背景图开关——有图时根底色透明（照片从最底层透出，各页卡片浮在其上），
    // 无图时保持原不透明底色。真机反馈「不只是要聊天的地方变，其他地方也要变」。
    val appBgUri by vm.chatBackgroundUri.collectAsStateWithLifecycle()
    val hasAppBackground = !appBgUri.isNullOrBlank()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                if (hasAppBackground) Color.Transparent
                else MaterialTheme.colorScheme.background
            )
    ) {
        // 全局背景层（照片 + surface 色 25% scrim）；无图时不渲染任何内容
        AppBackgroundLayer(vm)
        val wide = maxWidth >= 840.dp
        // clamp(280dp, 82vw, 320dp)：280 保触控下限，320 保「抽屉感」上限（§6.5.2）
        val drawerWidth = minOf(320.dp, maxOf(280.dp, maxWidth * 0.82f))
        val drawerWidthPx = with(density) { drawerWidth.toPx() }

        // 功能一：统一导航入口（抽屉点选 / 「＋ 新对话」共用）——记返回栈 + 切路由 + 窄屏关抽屉
        val navigateTo: (AppDest) -> Unit = { target ->
            // 点当前所在页项 = 仅关闭抽屉（不重复导航，§6.5.2）
            if (target != dest) {
                if (backStack.size >= BACK_STACK_MAX) backStack = backStack.drop(1)
                backStack = backStack + dest
                route = target.route
            }
            if (!wide) closeDrawer()
        }
        val onSelect: (AppDest) -> Unit = navigateTo
        val onNewConversation: () -> Unit = {
            vm.newConversation()
            navigateTo(AppDest.CHAT)
        }
        val onSelectConversation: (String) -> Unit = { id ->
            // QA P3：在设置页等非对话页点最近对话，会话切了人还留在原页——先导航回对话页
            // （navigateTo 内部带记栈 + 窄屏关抽屉，与抽屉点选行为一致）
            if (dest != AppDest.CHAT) navigateTo(AppDest.CHAT)
            vm.switchConversation(id)
            if (!wide) closeDrawer()
        }
        // 功能一：页面内「返回」统一语义——先试返回栈，空则回对话页（ MODEL 保存/取消用）
        val onPageBack: () -> Unit = { if (!popBack()) route = AppDest.CHAT.route }

        // 抽屉打开器注入页面：页面只消费本地值，不感知导航宿主细节（宽屏常驻态为空操作）
        CompositionLocalProvider(
            LocalDrawerOpener provides { if (!wide && !openTarget) openDrawer() },
            LocalPermanentDrawer provides wide,
            LocalHamburgerFocus provides if (wide) null else hamburgerFocus
        ) {
            if (wide) {
                // —— 宽屏：常驻抽屉 280dp（无 scrim 无滑入）+ 内容限宽 680dp 居中 ——
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        color = ak.surface1,
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                    ) {
                        DrawerContent(
                            dest = dest,
                            serviceConnected = serviceConnected,
                            pendingGrants = pendingGrants,
                            conversations = conversations,
                            activeConversationId = activeConversationId,
                            onSelect = onSelect,
                            onNewConversation = onNewConversation,
                            onOpenAllConversations = { navigateTo(AppDest.CONVERSATIONS) },
                            onSelectConversation = onSelectConversation,
                            onRenameConversation = { id, title -> vm.renameConversation(id, title) },
                            onDeleteConversation = { id -> vm.deleteConversation(id) },
                            onSetPinned = { id, pinned -> vm.setConversationPinned(id, pinned) },
                            onBindWorkspace = { id, dir -> vm.setConversationWorkspace(id, dir) },
                            workspaceDirs = { vm.workspaceDirs() },
onExportConversation = { id ->
    // hotfix29：导出从纯文本升级为 .md 真文件（FileProvider content://）；
    // 文件名取会话标题（净化后），无匹配标题回退「会话」
    val title = conversations.firstOrNull { it.id == id }?.title ?: "会话"
    shareMarkdownFile(ctx, title, vm.conversationMarkdown(id), "导出会话")
},
                            firstItemFocus = null,
                            generatingConvId = generatingConvId
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 680.dp)
                                .fillMaxSize()
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // 崩溃横幅（真机第四轮 B）：有未查看崩溃时置顶提示，点击跳日志页；
                                // 日志页自身已有「最近崩溃」区块，故该页不重复显示。
                                if (dest != AppDest.LOGS) {
                                    AkCrashBanner(onOpenLogs = { navigateTo(AppDest.LOGS) })
                                }
                                Box(Modifier.weight(1f)) {
                                    ScreenHost(
                                        dest, vm, themeMode, onThemeModeChange,
                                        dynamicColor, onDynamicColorChange,
                                        onSelect, onPageBack, unreadErrors, hasUnreadCrash
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // —— 窄屏：overlay 抽屉 + scrim + 左缘 24dp 右滑热区 ——
                Box(Modifier.fillMaxSize()) {
                    // 内容层：状态栏 inset 由宿主统一处理，页面只画 56dp 透明顶栏 + 内容
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        // 崩溃横幅（真机第四轮 B）：置顶提示未查看崩溃，点击跳日志页；
                        // 日志页自身已有「最近崩溃」区块，故该页不重复显示。
                        if (dest != AppDest.LOGS) {
                            AkCrashBanner(onOpenLogs = { navigateTo(AppDest.LOGS) })
                        }
                        Box(Modifier.weight(1f)) {
                            ScreenHost(
                                dest, vm, themeMode, onThemeModeChange,
                                dynamicColor, onDynamicColorChange,
                                onSelect, onPageBack, unreadErrors, hasUnreadCrash
                            )
                        }
                    }

                    // 左缘 24dp 热区：跟手拖拽（抽屉位移与 scrim 透明度随手势进度）
                    if (progress < 1f) {
                        val dragState = rememberDraggableState { delta ->
                            gestureProgress =
                                ((gestureProgress ?: 0f) + delta / drawerWidthPx).coerceIn(0f, 1f)
                        }
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(24.dp)
                                .fillMaxHeight()
                                .draggable(
                                    state = dragState,
                                    orientation = Orientation.Horizontal,
                                    onDragStopped = {
                                        // 超过抽屉宽 1/3 吸附开启，否则收回；吸附过渡 150ms = motionFast（§6.5.2）
                                        val shouldOpen = (gestureProgress ?: 0f) > 1f / 3f
                                        gestureProgress = null
                                        openTarget = shouldOpen
                                        settleSpec = AkMotionFast
                                    }
                                )
                        )
                    }

                    // scrim：点击关闭；背后页面内容不位移（省算力，§6.5.2）
                    if (progress > 0.02f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .alpha(progress)
                                .background(ak.scrim)
                                .clickable { closeDrawer() }
                        )
                    }

                    // 抽屉本体：滑入 250ms + fade；右缘 1dp outline 装饰线
                    if (progress > 0.001f) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(drawerWidth)
                                .offset(x = drawerWidth * (progress - 1f))
                                .alpha(progress)
                                .background(ak.surface1)
                        ) {
                            DrawerContent(
                                dest = dest,
                                serviceConnected = serviceConnected,
                                pendingGrants = pendingGrants,
                                conversations = conversations,
                                activeConversationId = activeConversationId,
                                onSelect = onSelect,
                                onNewConversation = onNewConversation,
                                onOpenAllConversations = { navigateTo(AppDest.CONVERSATIONS) },
                                onSelectConversation = onSelectConversation,
                                onRenameConversation = { id, title ->
                                    vm.renameConversation(id, title)
                                },
                                onDeleteConversation = { id -> vm.deleteConversation(id) },
                                onSetPinned = { id, pinned ->
                                    vm.setConversationPinned(id, pinned)
                                },
                                onBindWorkspace = { id, dir -> vm.setConversationWorkspace(id, dir) },
                                workspaceDirs = { vm.workspaceDirs() },
                                onExportConversation = { id ->
                                    // hotfix29：导出从纯文本升级为 .md 真文件（FileProvider content://）；
                                    // 文件名取会话标题（净化后），无匹配标题回退「会话」
                                    val title = conversations.firstOrNull { it.id == id }?.title ?: "会话"
                                    shareMarkdownFile(ctx, title, vm.conversationMarkdown(id), "导出会话")
                                },
                                    firstItemFocus = firstItemFocus,
                                    generatingConvId = generatingConvId
                                )
                            // 右缘 1dp outline 装饰线（全 App 唯一抽屉描边，规范明文；其余场景 0 描边）
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                        }
                    }
                }
            }
        }

        // 功能一：系统返回键三级优先（录屏反馈「别人按返回的返回到上一个地方」）：
        // ① 抽屉开着 → 关抽屉；② 有导航历史 → 回上一页；③ 根页 → Toast 提示，2s 内再按退出。
        //  Dialog（审批 / 二次确认）是独立窗口，系统返回先被 Dialog 窗口消费，不会走到这里——不冲突。
        // 从窄屏分支移出常驻：宽屏同样有页面间前进/后退语义（模型页保存后应回对话页）。
        BackHandler {
            when {
                openTarget -> closeDrawer()
                popBack() -> Unit
                else -> onBackAtRoot()
            }
        }
    }
}

/**
 * 路由分发：全部一级目的地 → 各屏幕文件。
 * 页面切换无转场动画（v3 无此 token）；顶栏由各页用 [PageTopBar] 自渲染（§6.5.1 骨架统一）。
 *
 * @param onBack 页面内「返回」动作（功能一）：模型页保存/取消后回上一页，无历史时回对话页。
 */
@Composable
private fun ScreenHost(
    dest: AppDest,
    vm: ChatViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    onNavigate: (AppDest) -> Unit,
    onBack: () -> Unit,
    unreadErrors: Int,
    /** 崩溃目录里是否还有没看过的记录（与 [unreadErrors] 不同源，理由见控制台入口）。 */
    hasUnreadCrash: Boolean
) {
    // P2（2026-10-21）：聚光灯引导——锚点由页面组件上报，覆盖层画在页面之上。
    // 触发条件三连：模型已就绪（能对话）→ 权限引导已走过（不是第一次打开）→ 自己没被跳过一次。
    val ctx = LocalContext.current
    val anchors = remember { GuideAnchorRegistry() }
    var showSpotlight by remember { mutableStateOf(false) }
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    LaunchedEffect(dest) {
        // 离开对话页必须收起：覆盖层是页面内的，不跟着路由走。只在进对话页时重新判断。
        if (dest != AppDest.CHAT) {
            showSpotlight = false
            return@LaunchedEffect
        }
        if (modelReady && guideSeen(ctx) && !spotlightSeen(ctx)) showSpotlight = true
    }

    // W-66：按路由保存页面状态。本 App 是手写 when 切页（不是 NavHost），页面一离开组合
    // 就销毁，同槽位的 rememberSaveable 还会被下一个页面顶掉——返回时滚动位置、列表位置
    // 全部归零。SaveableStateHolder 按 route 各自存一份，返回时原样恢复。
    val pageStates = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalGuideAnchors provides anchors) {
        Box(Modifier.fillMaxSize()) {
            pageStates.SaveableStateProvider(dest.route) {
                when (dest) {
                    AppDest.CHAT -> ChatScreen(
                        vm = vm, permission = vm,
                        onOpenModel = { onNavigate(AppDest.MODEL) },
                        onOpenGuide = { onNavigate(AppDest.PERMISSION_GUIDE) },
                        onOpenContainer = { onNavigate(AppDest.CONTAINER) },
                        onManagePrompts = { onNavigate(AppDest.PROMPTS) }
                    )
                    // 功能一：模型页不再硬跳对话页——从哪来往哪回（抽屉来的回抽屉关闭态，空态直达的回对话页）
                    AppDest.MODEL -> ModelConfigScreen(vm) { onBack() }
                    AppDest.CONSOLE -> ConsoleScreen(vm, unreadErrors, hasUnreadCrash, onNavigate)
                    AppDest.TERMINAL -> TerminalScreen()
                    AppDest.CONTAINER -> ContainerScreen()
                    AppDest.EDITOR -> EditorScreen()
                    AppDest.MCP -> McpScreen(vm::refreshMcpTools) // R-07：停用即撤工具
                    AppDest.MEDIA -> MediaScreen()
                    AppDest.BUILD -> BuildScreen()
                    AppDest.PERMISSIONS -> PermissionsScreen(
                        vm,
                        onStartGuide = { onNavigate(AppDest.PERMISSION_GUIDE) }
                    )
                    AppDest.PERMISSION_GUIDE -> PermissionGuideScreen(
                        vm,
                        onDone = {
                            // 引导走完回对话：用户手上真正要做的事是「说一句话试试」，不是看权限表
                            onNavigate(AppDest.CHAT)
                        }
                    )
                    AppDest.SKILLS -> SkillsScreen(vm, onOpenMarket = { onNavigate(AppDest.MARKET) })
                    AppDest.MARKET -> SkillMarketScreen(vm)
                    AppDest.WORKFLOW -> WorkflowScreen(vm)
                    AppDest.LOGS -> LogsScreen(vm)
                    AppDest.MEMORY -> MemoryScreen(vm)
                    AppDest.PROMPTS -> PromptLibraryScreen(vm)
                    AppDest.BOOKMARKS -> BookmarkScreen(vm)
                    // W-69：列表状态从 vm 自己收（ScreenHost 拿不到抽屉那份局部状态），
                    // 打开会话与新建都要顺手导航回对话页——留在列表页会看着像「没反应」
                    AppDest.CONVERSATIONS -> ConversationsScreen(
                        vm = vm,
                        onOpen = { id ->
                            vm.switchConversation(id)
                            onNavigate(AppDest.CHAT)
                        },
                        onNew = {
                            vm.newConversation()
                            onNavigate(AppDest.CHAT)
                        }
                    )
                    AppDest.SETTINGS -> SettingsScreen(
                        vm = vm,
                        themeMode = themeMode,
                        dynamicColor = dynamicColor,
                        onNavigate = onNavigate
                    )
                    AppDest.APPEARANCE -> AppearanceScreen(
                        vm,
                        themeMode,
                        onThemeModeChange,
                        dynamicColor,
                        onDynamicColorChange
                    )
                    AppDest.ABOUT -> AboutScreen(
                        onOpenPermissionGuide = { onNavigate(AppDest.PERMISSION_GUIDE) }
                    )
                }
            }
            if (showSpotlight) {
                SpotlightGuide(
                    registry = anchors,
                    onFinish = {
                        markSpotlightSeen(ctx)
                        showSpotlight = false
                    }
                )
            }
        }
    }
}

/**
 * 页面顶栏便捷包装：自动接上抽屉打开器（LocalDrawerOpener），
 * 页面只声明「标题 + 右上 0–1 动作」，骨架细节（56dp / 透明 / 48dp 触控）全部收口在 AkTopBar。
 */
@Composable
internal fun PageTopBar(
    title: String,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    /**
     * v5：默认由顶栏自己画 30sp 大标题。
     *
     * 顶栏标题在 v5 降成 12.5sp 位置条之后，没有大标题的页面就「没有页名」了——
     * 一行灰色小字撑不起一屏的起点。把大标题收进这个函数，18 个页面的调用点
     * 一行都不用改，全部自动获得页面标题。
     *
     * 已经自己画了大标题的页面（控制台 / 设置 / 模型）传 false，避免两个标题。
     */
    largeTitle: Boolean = true
) {
    AkTopBar(
        title = title,
        onOpenDrawer = LocalDrawerOpener.current,
        actionIcon = actionIcon,
        actionDescription = actionDescription,
        actionEnabled = actionEnabled,
        onAction = onAction,
        // v5：顶栏不再显示任何标题。每个页面都已有 30sp 大标题（自动或自绘），
        // 顶栏再放一行小字就是同一个名字出现两次。
        showTitle = false
    )
    if (largeTitle) {
        Text(
            text = title,
            style = displayTitleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space5,
                top = AgentSpacing.space1,
                bottom = AgentSpacing.space5
            )
        )
    }
}

/**
 * 抽屉内容：头部（应用标记 + 就绪文字三态）+ 主要组 + 最近对话组 + 底部位（§6.5.2）。
 *
 * 用户反馈侧边栏拥挤，参照 RikkaHub 极简风：抽屉从「9+4+2」瘦身为「4+1」，
 * 管理组与「关于」整体搬进设置页，只服务抽屉项的红点逻辑随之成为死代码一并删除
 * （权限 / 日志角标在控制台快捷入口、权限中心页仍可见，同源不受影响）。
 *
 * 功能三（聊天记录）：主要组与底部位之间插入「最近对话」组——点选切换、长按管理。
 *
 * @param firstItemFocus 焦点请求器：抽屉打开时移入第一项（§6.5.5）；宽屏常驻态传 null。
 */
@Composable
private fun DrawerContent(
    dest: AppDest,
    serviceConnected: Boolean,
    pendingGrants: Int,
    conversations: List<ConversationSummary>,
    activeConversationId: String?,
    onSelect: (AppDest) -> Unit,
    onNewConversation: () -> Unit,
    /** W-69：打开「全部对话」页（抽屉只放最近几条，完整列表在独立页）。 */
    onOpenAllConversations: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    /** W-28：绑定会话的项目目录（convId, 目录相对路径；空 = 解绑）。 */
    onBindWorkspace: (String, String) -> Unit,
    /** W-28：工作区内可选目录列表（供绑定对话框）。 */
    workspaceDirs: () -> List<String>,
    /** 功能五（P1-4a）：导出会话为 Markdown（入参 = 会话 id；空会话由 VM 返空串，UI 不弹面板）。 */
    onExportConversation: (String) -> Unit,
    /** hotfix30：正在生成的会话 id——对应抽屉项显示「生成中」标识（null = 无在途任务）。 */
    generatingConvId: String? = null,
    firstItemFocus: FocusRequester?
) {
    // 长按菜单 / 重命名 / 删除三个模态的目标会话 id（AkModalHost 三态机承载退场动画）
    var menuConvId by remember { mutableStateOf<String?>(null) }
    // W-28：绑定项目目录的会话 id（null = 对话框关闭）
    var bindConvId by remember { mutableStateOf<String?>(null) }
    var renameConvId by remember { mutableStateOf<String?>(null) }
    var deleteConvId by remember { mutableStateOf<String?>(null) }
    // hotfix30：删除在途会话的 Toast 反馈（VM 静默拒绝，UI 补一句人话）
    val context = LocalContext.current

    // 设置入口固定在底部，不随「最近对话」条数下移。
    //
    // 之前整列一起 verticalScroll，于是设置排在会话列表之后跟着走：对话一多，
    // 最后一项被顶到导航栏底下（小屏 720x1280 上 bounds 直接是 0，点不到）。
    // 改成「底层：整块滚动内容 + 顶层：贴底固定栏」，滚动区仍是 fillMaxSize + verticalScroll，
    // 与改造前的测量路径一致。
    //
    // 不走「Column 分两段 + weight(1f)」：最初那么写过，结果是滚动区整块空白、
    // 只剩底栏一项挂在半空。真正的原因不在 weight，而在 DrawerItem 里的 Row 用了
    // fillMaxSize()——底栏一旦有了确定高度，它就被撑到满屏（见 DrawerItem 处的注释）。
    // 换成叠加后，底栏高度只由自己的内容决定，不再受兄弟节点影响。
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // 导航栏高度先扣掉，滚动区才不会伸到导航栏底下：
                // 底栏自己也有 navigationBarsPadding，两者相加才是整块高度
                .navigationBarsPadding()
                // 内容高 <740dp 的老旧小屏允许抽屉内部滚动：不加折叠、不加「查看更多」（§6.5.2）
                .verticalScroll(rememberPersistentScrollState())
        ) {
            DrawerHeader(
                serviceConnected = serviceConnected,
                pendingGrants = pendingGrants,
                onReadyClick = { onSelect(AppDest.PERMISSIONS) }
            )
            // 20dp → 16dp：720x1280 上抽屉可视高度只有 903px，头部 + 五个导航项
            // 就吃掉 650px，会话列表一条都露不出来（只剩标题行下面的空白）。
            // 三处 space5 各收 4dp，刚好能露出第一条会话。
            Spacer(Modifier.height(AgentSpacing.space4))

            DrawerGroupTitle("主要")
            PrimaryGroup.forEach { item ->
                DrawerItem(
                    dest = item,
                    isSelected = dest == item,
                    talkBack = item.label,
                    onClick = { onSelect(item) },
                    modifier = if (item == PrimaryGroup.first() && firstItemFocus != null)
                        Modifier.focusRequester(firstItemFocus) else Modifier
                )
            }

            // 功能三：最近对话组（录屏反馈「人家都有聊天记录对话记录，你这都没有」）。
            //
            // 这里**故意不放搜索框**（W-68 删掉了功能五留下的那个 AkTextField）：
            // 抽屉一有输入框，点一下键盘就弹起来，抽屉被 IME 压到只剩 400 多 px，
            // 固定在底部的「设置」就贴在键盘上沿——用户原话「感觉怪怪的」。
            // 他想找的是**内容**不是标题，而内容检索在记忆库（那边自带搜索）；
            // 标题筛选的收益撑不起这个副作用，于是整块拿掉，抽屉永远不会弹键盘。
            if (conversations.isNotEmpty()) {
                Spacer(Modifier.height(AgentSpacing.space4))
                DrawerGroupTitle("最近对话") {
                    // W-69：入口放在标题行右侧，跟着列表一起滚，但永远在「最近对话」
                    // 的第一屏里——不依赖滚到底。
                    Text(
                        text = "全部 ${conversations.size}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenAllConversations)
                            // 触控目标撑到 48dp 高（可见文字仍是一行 13sp）
                            .padding(horizontal = AgentSpacing.space2, vertical = 10.dp)
                            .semantics { contentDescription = "查看全部对话，共 ${conversations.size} 个" }
                    )
                }
                conversations.take(MAX_RECENT_CONVERSATIONS).forEach { conv ->
                    DrawerConversationRow(
                        conversation = conv,
                        isActive = conv.id == activeConversationId,
                        isGenerating = conv.id == generatingConvId,
                        onClick = { onSelectConversation(conv.id) },
                        onLongClick = { menuConvId = conv.id }
                    )
                }
                AkListItem(
                    icon = Icons.Outlined.Add,
                    title = "新对话",
                    onClick = onNewConversation
                )
            }

            // 滚动区末尾的呼吸间距（原先这段在「设置」上方，设置已移出，留给自己）
            Spacer(Modifier.height(AgentSpacing.space4))
            // 再让出固定底栏的高度：底栏盖在滚动内容之上，不让位的话
            // 最后一项（「新对话」）永远压在底栏底下，滚不出来。
            Spacer(Modifier.height(DrawerBottomBarHeight))
        }

        // 固定底栏：设置常驻此处，不随对话数量下移。
        //
        // navigationBarsPadding 从外层挪到这里，而不是继续留在最外层：
        // 留在外层会让整块（含滚动区）一起被抬起，滚动区白白少一截；
        // 只给底栏加，等于把导航栏高度当作底栏的内边距吃掉，滚动区可以一直用到分界线。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 必须不透明：滚动内容是叠在它下面的，透明底会把「新对话」透出来
                .background(LocalAkColors.surface1)
                .navigationBarsPadding()
        ) {
            // 分隔线是必需的：滚动内容在它上面被裁切，没有这条线，
            // 用户会把「被截断的半个对话项」当成渲染错误。
            AkDivider(Modifier.padding(horizontal = AgentSpacing.space4))
            BottomGroup.forEach { item ->
                DrawerItem(
                    dest = item,
                    isSelected = dest == item,
                    talkBack = item.label,
                    onClick = { onSelect(item) }
                )
            }
            Spacer(Modifier.height(AgentSpacing.space3))
        }
    }

    // —— 功能三：长按菜单 / 重命名 / 删除确认（Dialog 独立窗口，浮于 scrim 之上）——
    AkModalHost(target = menuConvId) { id, visible ->
        val conv = conversations.firstOrNull { it.id == id }
        ConversationMenuDialog(
            title = conv?.title ?: "对话",
            visible = visible,
            pinned = conv?.pinned == true,
            onTogglePin = {
                menuConvId = null
                onSetPinned(id, conv?.pinned != true)
            },
            workspaceDir = conv?.workspaceDir.orEmpty(),
            onBindWorkspace = { menuConvId = null; bindConvId = id },
            onRename = { menuConvId = null; renameConvId = id },
            onExport = { menuConvId = null; onExportConversation(id) },
            onDelete = { menuConvId = null; deleteConvId = id },
            onDismiss = { menuConvId = null }
        )
    }
    AkModalHost(target = renameConvId) { id, visible ->
        var text by remember(id) {
            mutableStateOf(conversations.firstOrNull { it.id == id }?.title ?: "")
        }
        RenameConversationDialog(
            name = text,
            onNameChange = { text = it },
            visible = visible,
            onConfirm = { onRenameConversation(id, text); renameConvId = null },
            onDismiss = { renameConvId = null }
        )
    }
    // W-28：绑定项目目录（列出工作区里的目录 + 「不绑定」）
    AkModalHost(target = bindConvId) { id, visible ->
        WorkspaceBindDialog(
            dirs = remember(id) { workspaceDirs() },
            current = conversations.firstOrNull { it.id == id }?.workspaceDir.orEmpty(),
            visible = visible,
            onConfirm = { dir -> onBindWorkspace(id, dir); bindConvId = null },
            onDismiss = { bindConvId = null }
        )
    }
    AkModalHost(target = deleteConvId) { id, visible ->
        val title = conversations.firstOrNull { it.id == id }?.title ?: "该对话"
        AkConfirmDialog(
            title = "删除对话",
            message = "确定删除「$title」吗？消息记录将从本机移除，删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            visible = visible,
            onConfirm = {
                // hotfix30：在途会话 VM 会静默拒绝（防删掉的会话被在途写盘复活）——
                // 这里给用户一句人话，避免「点了删除没反应」
                if (id == generatingConvId) {
                    Toast.makeText(
                        context,
                        "该对话正在生成，请先停止或等待完成",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onDeleteConversation(id)
                }
                deleteConvId = null
            },
            onDismiss = { deleteConvId = null }
        )
    }
}

/**
 * 功能三：抽屉「最近对话」行。Chat 前导图标 + 标题（单行省略）+ 相对时间与条数。
 * 视觉对齐 [DrawerItem]（52dp / radius 14dp / 选中三件套），按压 surface-2 + scale 0.97；
 * 长按出管理菜单。
 *
 * combinedClickable 在 foundation 1.7.0 仍挂 ExperimentalFoundationApi（反编译不看注解会误判），
 * 但它是官方唯一的「单击 + 长按」合一实现、行为早已稳定，本行只用 onClick/onLongClick 两参数。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationRow(
    conversation: ConversationSummary,
    isActive: Boolean,
    isGenerating: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space2)
            .heightIn(min = AgentDimens.drawerItemHeight)
    ) {
        // v5：与 [DrawerItem] 同一套选中语言——没有竖条、没有色块，只有颜色。
        // 抽屉里最不该出现的就是一块浅色圆角矩形：它会把「目录」读成「卡片墙」。
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val bg by animateColorAsState(
            targetValue = if (pressed) ak.surface2 else Color.Transparent,
            animationSpec = AkMotionFastColor
        )
        Row(
            // matchParentSize：外层 Box 只给了 heightIn(min = 52dp)，高度是**有限**的；
            // 用 fillMaxSize 会去争父容器的最大高度，在某些父里直接把行撑到整屏
            // （W-68 抽屉底栏那个 bug 就是同一行代码引发的，这里一并改掉）。
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                // 合并为单节点：一次聚焦读全，selected 态读作「已选中」
                .semantics(mergeDescendants = true) {
                    // hotfix30：生成中态读作「正在生成」——无障碍用户不依赖视觉转圈
                    contentDescription =
                        if (isGenerating) "${conversation.title}，正在生成" else conversation.title
                    selected = isActive
                },            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Chat,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 功能五（P1-3）：置顶标记——标题前的实心书签，一眼看出「这条被钉住了」
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(AgentSpacing.space1))
                    }
                    // hotfix30：生成中标记——标题前的小号转圈，一眼看出「这条正在出字」，
                    // 点击可切过去续看（在途任务按 owner 后台续跑，见 ChatViewModel.runOwnerConvId）
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(AgentSpacing.space1))
                    }
                    Text(
                        text = conversation.title,
                        // v5：14sp——比导航项（15sp）低半档，会话是「内容」不是「栏目」
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${relativeTime(conversation.updatedAt)} · " +
                        // hotfix30：生成中会话副标题前置「生成中」，与标题前转圈双通道提示
                        (if (isGenerating) "生成中 · " else "") +
                        "${conversation.messageCount} 条消息" +
                        // 功能五（P1-5）：有用量数据才缀 tokens（旧会话/本地模型会话不显示）
                        (if (conversation.promptTokens + conversation.completionTokens > 0)
                            " · ${formatTokens(conversation.promptTokens + conversation.completionTokens)} tok"
                        else ""),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 功能三：会话长按菜单（置顶 / 重命名 / 导出 / 删除）。与 [AkConfirmDialog] 同语言：
 * 344dp 白卡 + scale/fade 出入场；删除是危险动作，但红色只留给确认弹窗，菜单项保持中性（§3.1）。
 */
@Composable
internal fun ConversationMenuDialog(
    title: String,
    visible: Boolean,
    pinned: Boolean,
    workspaceDir: String = "",
    onBindWorkspace: () -> Unit = {},
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space6)
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    // 功能五（P1-3）：置顶项置首位（高频且无副作用）；图标用 Bookmark
                    // 的实心/线框表达状态，文案随当前状态反转，避免用户猜「点了会怎样」
                    AkListItem(
                        icon = if (pinned) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                        title = if (pinned) "取消置顶" else "置顶对话",
                        description = if (pinned) "已置顶，取消后回到按时间排序" else "置顶后恒排在列表最前",
                        onClick = onTogglePin
                    )
                    AkListItem(
                        icon = Icons.Outlined.FolderOpen,
                        title = "绑定项目目录",
                        description = if (workspaceDir.isBlank()) {
                            "未绑定；绑定后会把该目录的 AGENTS.md/README.md 作为项目规则"
                        } else {
                            "已绑定：$workspaceDir（点此更换或解绑）"
                        },
                        onClick = onBindWorkspace
                    )
                    AkListItem(
                        icon = Icons.Outlined.Edit,
                        title = "重命名",
                        onClick = onRename
                    )
                    // 功能五（P1-4a）：导出 Markdown（走系统分享面板，零权限）
                    AkListItem(
                        icon = Icons.Outlined.Description,
                        title = "导出 Markdown",
                        description = "生成本对话的 Markdown 文本，分享到其它应用",
                        onClick = onExport
                    )
                    AkListItem(
                        icon = Icons.Outlined.Delete,
                        title = "删除对话",
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

/**
 * 功能三：会话重命名对话框（[AkModalHost] 承载退场动画）。
 * 提交空名由 VM 忽略（renameConversation 对 blank 直接 return），此处按钮同步禁用。
 */
@Composable
internal fun RenameConversationDialog(
    name: String,
    onNameChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "重命名对话",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "对话名称",
                        placeholder = "例如：查天气"
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "确定",
                            onClick = onConfirm,
                            enabled = name.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 功能三：抽屉「最近对话」的相对时间——按日历日比较（P3：原 48h 窗口会把前天标成
 * 「昨天」）：同日 HH:mm / 昨天 HH:mm / 今年 MM-dd / 跨年 yyyy-MM-dd（去年 11-07 与
 * 今年 11-07 否则长得一样）。ts 晚于 now 按「刚刚」处理。直接读 Calendar 字段而非
 * new 三个 SimpleDateFormat：每项每次重组都会调一次，且 SDF 非线程安全。
 */
private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    if (ts > now) return "刚刚"
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val time = "${pad2(c.get(Calendar.HOUR_OF_DAY))}:${pad2(c.get(Calendar.MINUTE))}"
    val yesterday = (n.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val md = "${pad2(c.get(Calendar.MONTH) + 1)}-${pad2(c.get(Calendar.DAY_OF_MONTH))}"
    return when {
        dayKey(c) == dayKey(n) -> time
        dayKey(c) == dayKey(yesterday) -> "昨天 $time"
        c.get(Calendar.YEAR) != n.get(Calendar.YEAR) -> "${c.get(Calendar.YEAR)}-$md"
        else -> md
    }
}

/** 日历日主键：年 + 年内第几天，同年内唯一。 */
private fun dayKey(c: Calendar): Int = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)

/** 两位补零（不引 String.format：那要传 Locale，否则阿拉伯语区会给出本地数字）。 */
private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

/** 抽屉头部：40dp 圆角应用标记 +「AgentKit」+ 就绪文字三态（§6.5.2）。 */
@Composable
private fun DrawerHeader(
    serviceConnected: Boolean,
    pendingGrants: Int,
    onReadyClick: () -> Unit
) {
    // v5：去掉 40dp 的 primaryContainer 圆角方块底座。
    // 抽屉最上面那个「App 图标 + 名字」是模板里最常见的一块，而这里需要的只是
    // 一个刊头（masthead）：应用名独占一行，状态跟在下面。没有底座、没有图标，
    // 反而更像一本册子的扉页。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 竖直内边距 20dp → 12dp：小屏上抽屉要先把会话列表露出来
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space3)
    ) {
        Text(
            text = "北辰",
            style = displayTitleStyle.copy(fontSize = 23.sp, lineHeight = 31.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(AgentSpacing.space1))
        // 就绪文字三态与权限中心页共用实现（§6.5.2）：同一状态源、同一措辞
        AkReadyTextRow(
            serviceConnected = serviceConnected,
            pendingGrants = pendingGrants,
            onClick = onReadyClick
        )
    }
}

/**
 * 抽屉分组标题：单行中文 13sp w500 on-surface-var，无英文微标签（§6.5.2）。
 *
 * 右侧可挂一个行内动作（目前只有「最近对话」的「全部 N」入口）。挂在标题行而不是
 * 列表尾部，是因为 720x1280 上抽屉可视高度只有 ~451dp：五个主导航项就吃掉 260dp，
 * 会话列表里最后那一行永远压在固定底栏底下——想进「全部对话」得先盲滚一段。
 */
@Composable
private fun DrawerGroupTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = AgentSpacing.space5, end = AgentSpacing.space4, bottom = AgentSpacing.space1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            // v5：与页面里的分组标签同一个样式（11sp + 1.2sp 字距）——
            // 抽屉是「目录」，目录的小标题和正文页的小标题不该长得不一样。
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * 抽屉菜单项：高 52dp · 左右边距 8dp · 无 chevron（§6.5.2）。
 *
 * v5 改选中态：原来是「primary@12% 底 + 图标文字 primary + 左缘 3dp 竖条」三件套，
 * 一块圆角色块横在抽屉里，正是模板感最重的地方。现在只留**颜色与字重**——
 * 选中项的图标和文字转 primary、字重加一档。目录里标出当前章节，靠的是排字，不是底色。
 *
 * 按压仍给 surface-2，但圆角收到 4dp：按下去是「这一行被划过」，不是弹出一个按钮。
 * 整行为触控目标，TalkBack 合并为单节点。
 */
@Composable
private fun DrawerItem(
    dest: AppDest,
    isSelected: Boolean,
    talkBack: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space2)
            .heightIn(min = AgentDimens.drawerItemHeight)
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val bg by animateColorAsState(
            targetValue = if (pressed) ak.surface2 else Color.Transparent,
            animationSpec = AkMotionFastColor
        )
        Row(
            modifier = Modifier
                // 原为 fillMaxSize()。本意「铺满这一行」，但在**有限高**的父（如抽屉底部固定栏）里
                // 它会填满父给的最大高度；而本 Box 只靠 heightIn(min = 52dp) 兜底、没有确定高度内容，
                // 于是一个菜单项就能把整条底栏顶成全屏高（实测 590x1039，与整块抽屉等高）。
                // 在 verticalScroll（无限高）里看不出来：无限约束下 fillMaxSize 退回最小高度，恰好 52dp。
                // matchParentSize 取 Box 的最终尺寸，两种父约束下都是 52dp。
                .matchParentSize()
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
                // 合并为单节点：一次聚焦读全（图标/文字 decorative），selected 态读作「已选中」
                .semantics(mergeDescendants = true) {
                    contentDescription = talkBack
                    selected = isSelected
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = dest.icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = dest.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                ),
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * W-28：项目目录绑定对话框。
 *
 * 只列**工作区内的目录**（不是任意磁盘路径）——绑定后 Agent 仍只能读写工作区，
 * 不会因为绑定就把越界访问打开。首项「不绑定」用于解绑。
 */
@Composable
private fun WorkspaceBindDialog(
    dirs: List<String>,
    current: String,
    visible: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
        ) {
            Column(Modifier.padding(vertical = AgentSpacing.space3)) {
                Text(
                    "绑定项目目录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = AgentSpacing.space5)
                )
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    "绑定后，该目录下的 AGENTS.md / README.md 会作为项目规则注入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AgentSpacing.space5)
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberPersistentScrollState())
                ) {
                    AkListItem(
                        icon = Icons.Outlined.Folder,
                        title = "不绑定（工作区根）",
                        selected = current.isBlank(),
                        onClick = { onConfirm("") }
                    )
                    dirs.forEach { dir ->
                        AkListItem(
                            icon = Icons.Outlined.FolderOpen,
                            title = dir,
                            selected = current == dir,
                            onClick = { onConfirm(dir) }
                        )
                    }
                }
                Spacer(Modifier.height(AgentSpacing.space2))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AkTextButton(text = "取消", onClick = onDismiss)
                }
            }
        }
    }
}
