package com.beichen.polaris.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.automation.DeviceContext
import com.beichen.polaris.ui.contract.ConsoleContract

/**
 * 控制台页（UX v1 §1.3 控制台结构直译，替代旧「自动化」大杂页）。
 *
 * 结构自上而下：状态总览双栏（无障碍服务 / 自动化总开关）→ 总开关 +
 * 「前往系统设置开启无障碍服务」→ 设备感知实时上下文 → 快捷入口 2×2 →
 * 可用能力说明。旧页里的技能 / 日志 / 记忆三块已拆去各自的一级页面，这里只留跳转入口。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「控制台」+ 右上「刷新」），抽屉开关由宿主注入。
 * 待授权项数与抽屉红点、权限中心页共用 [pendingGrantCount]，三处永远报同一个数（§6.5.2）。
 *
 * @param unreadErrors 未读 ERROR 条数（由宿主持有并读后归零，与本页日志入口角标同源）。
 * @param onNavigate 跳转到抽屉里的其他一级目的地。
 */
@Composable
fun ConsoleScreen(
    vm: ConsoleContract,
    unreadErrors: Int,
    /**
     * 崩溃目录里是否还有没看过的记录。
     *
     * 和 [unreadErrors] 是两回事：进程被杀时内存日志来不及落盘，那条 ERROR 在
     * `vm.logs` 里根本不存在，于是 unreadErrors 是 0、入口不亮、用户想不到要去
     * 日志页——而这恰恰是最该看的一次崩溃。所以这里单独问一次崩溃目录。
     */
    hasUnreadCrash: Boolean = false,
    onNavigate: (AppDest) -> Unit
) {
    val enabled by vm.automationEnabled.collectAsStateWithLifecycle()
    val connected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()
    val deviceContext by vm.deviceContext.collectAsStateWithLifecycle()
    val skillInfos by vm.skillInfos.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val ak = LocalAkColors

    val pendingGrants = pendingGrantCount(connected, notificationGranted, usageGranted)

    // 从系统设置页返回时刷新授予状态：用户在设置里开了无障碍 / 通知 / 使用情况，回到本页要立刻反映
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshGrants()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.CONSOLE.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshGrants() }
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 状态总览：三宫格（§1.3）——
            AkSectionCard(
                title = "状态总览",
                icon = Icons.Outlined.Dashboard,
                description = "无障碍服务与自动化总开关就绪后，Agent 才能完整操作并感知手机。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    ConsoleStatusTile(label = "无障碍服务", ok = connected, modifier = Modifier.weight(1f))
                    ConsoleStatusTile(label = "自动化总开关", ok = enabled, modifier = Modifier.weight(1f))
                }
            }

            // —— 总开关 + 系统设置入口 ——
            AkSectionCard(
                title = "自动化总开关",
                icon = Icons.Outlined.Security,
                description = "关闭后所有手机操作类工具立即失效，Agent 只能对话。"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Security,
                    title = "自动化总开关",
                    description = if (enabled) "已开启，Agent 可操作手机" else "已关闭，Agent 只能对话",
                    trailing = {
                        AkSwitch(
                            checked = enabled,
                            onCheckedChange = { vm.setAutomationEnabled(it) }
                        )
                    }
                )
                // 无障碍服务只能在系统设置里开：未连接时常驻入口，已连接后收起（§1.3）
                if (!connected) {
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkPrimaryButton(
                        text = "前往系统设置开启无障碍服务",
                        icon = Icons.Outlined.Accessibility,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.openAccessibilitySettings(ctx) }
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "开启后返回本页，状态会自动刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // —— 设备感知：实时上下文 ——
            AkSectionCard(
                title = "设备感知",
                icon = Icons.Outlined.Visibility,
                description = "通知访问与使用情况访问就绪后，每次发消息时当前前台应用与最近通知会自动注入给模型。"
            ) {
                DeviceContextBlock(deviceContext = deviceContext)
            }

            // —— 快捷入口 2×2（角标规则同抽屉：红点 = 待办 / 计数 = 存量 / 零不显示）——
            AkSectionCard(
                title = "快捷入口",
                icon = Icons.Outlined.Apps,
                description = "管理类页面从这里直接进入，角标与抽屉里看到的一致。"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        ConsoleEntry(
                            icon = AppDest.PERMISSIONS.icon,
                            label = AppDest.PERMISSIONS.label,
                            badge = if (pendingGrants > 0) {
                                { AkStatusDot(MaterialTheme.colorScheme.error, 8.dp) }
                            } else null,
                            onClick = { onNavigate(AppDest.PERMISSIONS) },
                            modifier = Modifier.weight(1f)
                        )
                        ConsoleEntry(
                            icon = AppDest.SKILLS.icon,
                            label = AppDest.SKILLS.label,
                            badge = if (skillInfos.isNotEmpty()) {
                                { ConsoleCountBadge("${skillInfos.size}") }
                            } else null,
                            onClick = { onNavigate(AppDest.SKILLS) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        // 数字优先：有未读 ERROR 时报条数（用户据此判断「要不要现在去看」）；
                        // 没有 ERROR 但有未看过的崩溃时退化成红点——崩溃是低频事件，一个点
                        // 足够提示「这里有事」，写成条数反而像在报错次数。
                        val logsBadge: (@Composable () -> Unit)? = if (unreadErrors > 0) {
                            { ConsoleCountBadge("$unreadErrors") }
                        } else if (hasUnreadCrash) {
                            { AkStatusDot(MaterialTheme.colorScheme.error, 8.dp) }
                        } else {
                            null
                        }
                        ConsoleEntry(
                            icon = AppDest.LOGS.icon,
                            label = AppDest.LOGS.label,
                            badge = logsBadge,
                            onClick = { onNavigate(AppDest.LOGS) },
                            modifier = Modifier.weight(1f)
                        )
                        ConsoleEntry(
                            icon = AppDest.MEMORY.icon,
                            label = AppDest.MEMORY.label,
                            badge = if (memories.isNotEmpty()) {
                                { ConsoleCountBadge("${memories.size}") }
                            } else null,
                            onClick = { onNavigate(AppDest.MEMORY) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // —— 可用能力说明：内容即 description，无需额外控件 ——
            AkSectionCard(
                title = "可用能力",
                icon = Icons.Outlined.Apps,
                description = "可用工具：ui_tree（读取界面）、ui_tap / ui_long_press / ui_swipe（手势）、" +
                    "ui_input（输入文本）、ui_key（home / back / recent）、ui_click（按文本或 ID 点击）、" +
                    "ui_screenshot（截取屏幕给视觉模型看）、device_notifications（读取最近通知）、" +
                    "device_foreground_app（读取当前前台应用）。\n" +
                    "在对话里直接说“打开设置并点击某某”“截个屏看看现在是什么界面”" +
                    "“刚才有没有新消息”，即可让 Agent 操作并感知手机。"
            )
        }
    }
}

/**
 * 三宫格状态块：8dp 语义点 + 标签 + 状态词（点色与文字同色双编码，色盲可读，UX v1 §5.3）。
 *
 * 就绪 = successContainer 淡 tint 底 + on-success-container 墨迹；
 * 未就绪 = surface-1 中性底 + on-surface-var——「未授权 / 未开启」不是错误，不用红色（§3.1）。
 */
@Composable
private fun ConsoleStatusTile(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    val ak = LocalAkColors
    val ink = if (ok) ak.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (ok) ak.successContainer else ak.surface1,
        modifier = modifier.heightIn(min = 76.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AgentSpacing.space2,
                vertical = AgentSpacing.space3
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AkStatusDot(if (ok) ak.success else ak.outlineStrong, 8.dp)
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(AgentSpacing.space1))
            Text(
                text = if (ok) "已就绪" else "未开启",
                style = MaterialTheme.typography.labelMedium,
                color = ink
            )
        }
    }
}

/**
 * 快捷入口（2×2 单项）：40dp 图标底座 + 标签 + 角标，整块 64dp 起可点。
 * 角标由调用方传入（红点 = 待办 / 计数 = 存量 / 零不显示，§6.5.2）。
 */
@Composable
private fun ConsoleEntry(
    icon: ImageVector,
    label: String,
    badge: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AkPressSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AkIconBase(icon = icon)
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (badge != null) badge()
        }
    }
}

/** 存量计数角标：中性墨迹数字，无底无点（计数不是告警，§6.5.2）。 */
@Composable
private fun ConsoleCountBadge(count: String) {
    Text(
        text = count,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 设备感知实时上下文：前台应用（标签 + 包名）+ 最近通知（最多 3 条，其余折叠为「等 N 条」）。
 * 空态给一句 actionable 说明，不甩白屏——用户要知道「去开什么才能看到数据」。
 */
@Composable
private fun DeviceContextBlock(deviceContext: DeviceContext) {
    if (deviceContext.isEmpty) {
        Text(
            text = "暂无感知数据。开启无障碍服务后切换 App、或收到新通知，这里会实时显示。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    if (deviceContext.foregroundPackage.isNotBlank()) {
        val fg = if (deviceContext.foregroundLabel.isNotBlank() &&
            deviceContext.foregroundLabel != deviceContext.foregroundPackage
        ) {
            "${deviceContext.foregroundLabel} · ${deviceContext.foregroundPackage}"
        } else {
            deviceContext.foregroundPackage
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkIconBase(icon = Icons.Outlined.Apps)
            Spacer(Modifier.width(AgentSpacing.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "前台应用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = fg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    val notifications = deviceContext.recentNotifications
    if (notifications.isNotEmpty()) {
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = "最近通知",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space1))
        Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
            notifications.take(3).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    // 装饰性中性小点：条目已由文字承载语义，不做状态双编码
                    AkStatusDot(MaterialTheme.colorScheme.onSurfaceVariant, 6.dp)
                    Spacer(Modifier.width(AgentSpacing.space2))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val rest = notifications.size - 3
            if (rest > 0) {
                Text(
                    text = "等 $rest 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
