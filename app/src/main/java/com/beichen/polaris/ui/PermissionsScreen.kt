package com.beichen.polaris.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import com.beichen.polaris.automation.shizuku.ShizukuShell
import com.beichen.polaris.ui.contract.PermissionStatusContract
import rikka.shizuku.Shizuku
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beichen.polaris.core.capability.CapabilityResolution
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 权限中心页（UX v1 §1.4）：三项系统能力的就绪度总览 + 缺失置顶 + 一键跳转。
 *
 * 从旧自动化页拆出：权限是「排查型」信息，和「操作型」的控制台分开，
 * 用户出问题时先进权限中心，而不是在一堆开关里找哪一项没开。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「权限中心」+ 右上「刷新」）。
 * 待授权项数与抽屉红点、控制台三宫格共用 [pendingGrantCount]——同一状态源必须同一计数。
 *
 * P0（2026-10-21）：顶部新增「权限引导」入口。本页是**排查页**（我现在有什么），
 * 引导页是**上手页**（还差什么、点了能多做什么）。两条路径不重复：出问题来本页，
 * 第一次配置走引导。
 *
 * @param onStartGuide 进入权限引导页
 */
@Composable
fun PermissionsScreen(vm: PermissionStatusContract, onStartGuide: () -> Unit = {}) {
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val shizukuUiState by vm.shizukuUiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val pendingGrants = pendingGrantCount(connected, notificationGranted, usageGranted)

    // 从系统设置页返回时刷新：用户刚在设置里开了某项，回到本页要立刻反映
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshGrants()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 调试通道授权回调：用户在 Shizuku 弹窗点「允许」后立刻刷新状态（不等下次进页面）
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                vm.refreshShizukuStatus()
            }
        }
        val registered = runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
        }.isSuccess
        onDispose {
            if (registered) runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        }
    }

    // Shizuku 服务启动/退出：自动刷新状态（用户在 Shizuku 里点「启动」后回到本页即变「去授权」）
    DisposableEffect(Unit) {
        val onReceived = Shizuku.OnBinderReceivedListener {
            vm.refreshShizukuStatus()
        }
        val onDead = Shizuku.OnBinderDeadListener {
            vm.refreshShizukuStatus()
        }
        val ok = runCatching {
            Shizuku.addBinderReceivedListener(onReceived)
            Shizuku.addBinderDeadListener(onDead)
            true
        }.getOrDefault(false)
        onDispose {
            if (ok) runCatching {
                Shizuku.removeBinderReceivedListener(onReceived)
                Shizuku.removeBinderDeadListener(onDead)
            }
        }
    }

    val grants = listOf(
        GrantItem(
            title = "无障碍服务",
            granted = connected,
            icon = Icons.Outlined.Accessibility,
            grantedHint = "已连接，Agent 可读取界面并执行点击、滑动、输入",
            missingHint = "未连接，Agent 无法操作手机；安装新版本后系统会断开该权限，需回系统设置重新开启",
            onOpen = { vm.openAccessibilitySettings(ctx) }
        ),
        GrantItem(
            title = "通知访问",
            granted = notificationGranted,
            icon = Icons.Outlined.Notifications,
            grantedHint = "已授权，Agent 可读取最近通知",
            missingHint = "未授权，Agent 不知道有没有新消息",
            onOpen = {
                ctx.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),
        GrantItem(
            title = "调试通道（Shizuku）",
            granted = shizukuUiState == ShizukuShell.UiState.READY,
            icon = Icons.Outlined.Terminal,
            grantedHint = "已就绪：Agent 可在后台执行文件清理、停应用、改系统设置（shell uid 2000），不跳屏",
            missingHint = when (shizukuUiState) {
                ShizukuShell.UiState.NOT_INSTALLED ->
                    "未安装：点右侧一键安装（应用内自带官方 Shizuku 安装包，不跳商店）；装好回来即自动识别"
                ShizukuShell.UiState.INSTALLED_NOT_RUNNING ->
                    "已安装未启动：点右侧直接打开 Shizuku，在其中点「通过无线调试启动」"
                ShizukuShell.UiState.NO_PERMISSION ->
                    "未授权：点右侧，在 Shizuku 弹窗里选「允许」"
                ShizukuShell.UiState.READY -> ""
            },
            actionText = when (shizukuUiState) {
                ShizukuShell.UiState.NOT_INSTALLED -> "一键安装"
                ShizukuShell.UiState.INSTALLED_NOT_RUNNING -> "打开 Shizuku"
                ShizukuShell.UiState.NO_PERMISSION -> "去授权"
                ShizukuShell.UiState.READY -> "去开启"
            },
            onOpen = { vm.handleShizukuAction(ctx) }
        ),
        GrantItem(
            title = "使用情况访问",
            granted = usageGranted,
            icon = Icons.Outlined.Apps,
            grantedHint = "已授权，Agent 可读取当前前台应用",
            missingHint = "未授权，Agent 不知道你此刻在用哪个 App",
            onOpen = {
                ctx.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    )

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.PERMISSIONS.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshGrants() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            // v5：间距交给各块自己的顶部留白（26dp），不再额外叠一层 spacedBy
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // —— 引导入口（P0，2026-10-21）：本页是排查页，第一次上手走引导页 ——
            // 不因为「全部已授权」就隐藏：换机、重装、系统升级掉权限后还要能从这里再走一遍。
            //
            // v5：原来这块是「小标题 + 三行说明 + 描边按钮」，占掉首屏 110dp，把真正要看的
            // 权限清单挤到折叠线以下。它的全部内容是「去另一页」这一件事——一行就够。
            AkSectionLabel(text = "权限引导", gap = 26)
            AkLine(
                title = "逐项走一遍五项能力",
                meta = "每项都说清「开启后能多做什么」，点开跳系统设置，回来自动复检",
                dense = true,
                showDivider = false,
                onClick = onStartGuide,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            // —— 就绪度总览：与抽屉头部同一实现、同一措辞（§6.5.2）——
            AkSectionCard(title = "权限就绪度", icon = Icons.Outlined.Shield) {
                AkReadyTextRow(
                    serviceConnected = connected,
                    pendingGrants = pendingGrants,
                    onClick = { vm.refreshGrants() }
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = "基础能力三项：无障碍服务、通知访问、使用情况访问；另有一项可选增强：" +
                        "调试通道（Shizuku，提供后台 shell 权限）。" +
                        "缺项已置顶排列，点「去开启」会跳转对应的设置页，授予后回到本页即自动刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— 权限列表：未授权置顶（待办优先被处理，§1.4）——
            AkGroupCard {
                // sortedBy 稳定排序：false（未授权）在前，同组内保持上面的固定顺序
                grants.sortedBy { it.granted }.forEachIndexed { index, item ->
                    if (index > 0) {
                        // 分隔线缩进 16dp（与左侧图标底座右缘对齐，§6.1）
                        AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    }
                    GrantRow(item)
                }
            }

            // —— 调试通道设置步骤（三步向导 + 厂商坑位）——
            if (shizukuUiState != ShizukuShell.UiState.READY) {
                AkSectionCard(
                    title = "调试通道怎么开",
                    icon = Icons.Outlined.Terminal,
                    description = "Shizuku 是开源工具（作者 Rikka），它借系统自己的调试接口拿到 shell 权限，" +
                        "不 root、不联网上传数据。只需配置一次；重启手机后要重新「启动」一次（系统限制）。"
                ) {
                    Text(
                        text = "① 安装 Shizuku（已装跳过）\n" +
                            "② 打开 Shizuku →「通过无线调试启动」→ 用「分屏」：Shizuku 占一半、" +
                            "开发者选项里的「无线调试」占另一半。在无线调试页点「使用配对码配对设备」生成 6 位码，" +
                            "从通知栏把它填进 Shizuku（中途切走页面，配对会被系统终止）\n" +
                            "③ 看到 Shizuku 显示「已启动」→ 回本页点「去授权」→ 允许",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    Text(
                        text = "魅族 / Flyme 必须额外开（否则会「一开就关」或随机停止）：\n" +
                            "· 开发者选项 → 关闭「Flyme 支付保护」\n" +
                            "· 开发者选项 → 默认 USB 配置 →「不进行数据传输」\n" +
                            "· 开发者选项 → 打开「停用 adb 授权超时功能」（不开的话配对会被系统回收）\n" +
                            "· 系统设置 → 应用 → Shizuku → 允许后台运行、电池不限制\n" +
                            "· 配对与使用期间不要关闭「USB 调试」和「开发者选项」；切换 Wi-Fi 后无线调试会自动关闭，需重新打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // —— W-68：无线调试（ADB）——
            // Shizuku 与无线调试是同一件事的两条路（都要 uid 2000），原来 Shizuku 在本页、
            // 无线调试在设置页，用户得在两个页面各看一半再自己拼。本页收齐两种方式：
            // Shizuku 在权限列表里带授权动作，无线调试在这里配对。
            AdbChannelSection()

            // —— W-RT5：SSH 远程机器（显式路由，不进 shell 自动降级链）——
            SshChannelSection(vm)

            // —— 能力链一次走完（DoD⑤）：预先告诉用户「需要做什么」，而非逐条工具试错 ——
            val plans by vm.capabilityPlans.collectAsStateWithLifecycle()
            if (plans.isNotEmpty()) {
                AkSectionCard(
                    title = "能力就绪度",
                    icon = Icons.Outlined.Info,
                    description = "Agent 执行某类动作时会自动选择可用路径（如「跑 shell 命令」：调试通道 → " +
                        "proot 容器 → 本机 user shell）。这里一次说清当前会走哪条路、缺什么、你需要做什么，不用逐条工具试。"
                ) {
                    plans.forEachIndexed { index, plan ->
                        if (index > 0) {
                            AkDivider(Modifier.padding(start = AgentSpacing.space4))
                        }
                        CapabilityPlanRow(plan)
                    }
                }
            }

            // —— 说明：每项权限的用途与数据去向 ——
            AkSectionCard(
                title = "这些权限用来做什么",
                icon = Icons.Outlined.Info,
                description = "无障碍服务：读取当前界面，在你发出指令后执行点击、滑动、输入，" +
                    "是 Agent 操作手机的基础，其余能力都建立在它之上；截图也走无障碍（Android 11+），\n" +
                    "不需要额外的屏幕录制授权。\n" +
                    "通知访问：读取最近通知，回答“刚才有没有新消息”。\n" +
                    "使用情况访问：读取当前前台应用，让 Agent 知道你在用哪个 App。\n" +
                    "调试通道（Shizuku）：借 shell（uid 2000）在后台清理文件、停应用、改系统设置，" +
                    "不需要跳转界面；不配置时 Agent 仍可用无障碍完成操作。\n" +
                    "前台应用名与通知内容会随对话一起发送到你配置的模型接口，" +
                    "除此之外不会外传到任何地方。"
            )
        }
    }
}

/** 权限中心的一行：静态描述 + 当前授予态 + 跳转动作（不可点整行，只点「去开启」，防误跳）。 */
private class GrantItem(
    val title: String,
    val granted: Boolean,
    val icon: ImageVector,
    val grantedHint: String,
    val missingHint: String,
    val onOpen: () -> Unit,
    /** 按钮文案：按缺什么给什么（安装/打开/授权），避免一律「去开启」把人带错路。 */
    val actionText: String = "去开启"
)

@Composable
private fun GrantRow(item: GrantItem) {
    val ak = LocalAkColors
    // v5：去掉 40dp 图标底座和「已授权」绿色药丸。
    // 一列五个圆角方块会把页面读成「图标列表」，而这五行的差别只在授权状态；
    // 状态交给一个字＋一个点，图标降到 18dp 当行首记号。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(
                horizontal = AgentSpacing.space4,
                vertical = AgentSpacing.space3
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = if (item.granted) ak.success
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(AgentSpacing.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (item.granted) item.grantedHint else item.missingHint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        if (item.granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkStatusDot(ak.success, 5.dp)
                Spacer(Modifier.width(AgentSpacing.space1))
                Text(
                    text = "已授权",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = ak.onSuccessContainer
                )
            }
        } else {
            AkTextButton(text = item.actionText, onClick = item.onOpen)
        }
    }
}

/**
 * 能力就绪度一行（DoD⑤）：逐条能力展示「选中的执行路径」或「缺什么 + 需要你做什么」。
 *
 * 与 [GrantRow] 的区别：授权行是「静态能力清单」，本行是**运行时的降级裁决**——告诉用户
 * Agent 此刻实际会走哪条路，以及若要启用缺失路径，下一步该点哪里（文案来自能力链的 hint）。
 */
@Composable
private fun CapabilityPlanRow(plan: CapabilityResolution) {
    val ak = LocalAkColors
    val ready = plan is CapabilityResolution.Selected
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AgentSpacing.space3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(if (ready) ak.success else ak.warning)
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = plan.capability.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (ready) "就绪" else "需处理",
                style = MaterialTheme.typography.labelMedium,
                color = if (ready) ak.success else ak.warning
            )
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        when (plan) {
            is CapabilityResolution.Selected -> Text(
                text = "将走：${plan.backend.label}",
                style = MaterialTheme.typography.bodySmall,
                color = onVar
            )
            is CapabilityResolution.Unavailable -> {
                Text(
                    text = plan.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = onVar
                )
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    text = "需要你：${plan.hint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ak.warning
                )
            }
        }
    }
}
