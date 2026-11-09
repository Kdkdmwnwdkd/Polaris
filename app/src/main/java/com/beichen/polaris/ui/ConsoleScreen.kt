package com.beichen.polaris.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.automation.DeviceContext
import com.beichen.polaris.ui.contract.ConsoleContract

/**
 * 控制台页（v5 编辑式结构）。
 *
 * 旧结构是 5 张卡片：状态总览（双栏色块）、自动化总开关、设备感知、快捷入口（2×2 色块）、
 * 可用能力。五张卡在屏幕上重量完全相同，于是「服务断了」和「记忆里有 12 条」看起来一样急。
 *
 * 现在改成一条竖排的清单：
 *
 * ```
 * 控制台              ← 28sp 衬线
 * 已连接 · 2 项待授权  ← 12.5sp
 *
 * 状态
 * 无障碍服务      已连接
 * 自动化总开关     已开启
 * 设备感知        运行中
 *
 * 自动化
 * 自动化总开关                      [开关]
 *
 * 此刻
 * 前台应用        微信
 * 最近通知
 * · 菜鸟驿站：您的包裹已到…
 *
 * 管理
 * 权限中心        2  ›
 * 技能           8  ›
 * 运行日志        ·  ›
 * 记忆          12  ›
 *
 * 能做的事
 * ui_tree（读取界面）、ui_tap…
 * ```
 *
 * 信息一点没少，但读者是按「从上到下的状态」读，而不是按「五张卡片」扫。
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

    // 副标题只讲「现在什么状态」，不再解释这一页是干什么的：
    // 用户点进来时已经知道自己在控制台，第一行文字应该给出他此刻最需要判断的事实。
    val statusLine = when {
        !connected -> "无障碍未连接 · Agent 暂时只能对话"
        pendingGrants > 0 -> "已连接 · $pendingGrants 项待授权"
        enabled -> "已连接 · 自动化运行中"
        else -> "已连接 · 自动化已关闭"
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.CONSOLE.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = { vm.refreshGrants() },
            // 本页自己画大标题（要带状态副标题），顶栏不再重复
            largeTitle = false
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space1,
                    bottom = AgentSpacing.space8
                )
        ) {
            AkDisplayTitle(title = AppDest.CONSOLE.title, subtitle = statusLine)

            // —— 状态：三行事实，不用色块 ——
            AkSectionLabel(text = "状态", gap = 20)
            AkLine(
                title = "无障碍服务",
                dense = true,
                trailing = {
                    StatusWord(
                        ok = connected,
                        okText = "已连接",
                        noText = "未连接"
                    )
                }
            )
            AkLine(
                title = "设备感知",
                dense = true,
                trailing = {
                    StatusWord(
                        ok = !deviceContext.isEmpty,
                        okText = "运行中",
                        noText = "暂无数据"
                    )
                }
            )

            // —— 自动化：一个开关 + 未连接时的唯一出路 ——
            AkSectionLabel(text = "自动化")
            AkLine(
                title = "自动化总开关",
                meta = if (enabled) "Agent 可操作并感知手机" else "Agent 只能对话",
                trailing = {
                    AkSwitch(
                        checked = enabled,
                        onCheckedChange = { vm.setAutomationEnabled(it) }
                    )
                }
            )
            // 无障碍服务只能在系统设置里开：未连接时常驻入口，已连接后收起（§1.3）
            if (!connected) {
                Spacer(Modifier.height(AgentSpacing.space4))
                AkActionLine(
                    text = "前往系统设置开启无障碍服务",
                    note = "开启后返回本页，状态会自动刷新。",
                    onClick = { vm.openAccessibilitySettings(ctx) }
                )
            }

            // —— 此刻：设备感知的实时上下文 ——
            AkSectionLabel(text = "此刻")
            DeviceContextBlock(deviceContext = deviceContext)

            // —— 管理：四个入口压成四行（原来是 2×2 的四个色块）——
            AkSectionLabel(text = "管理")
            AkLine(
                title = AppDest.PERMISSIONS.label,
                value = if (pendingGrants > 0) "$pendingGrants 项待办" else null,
                onClick = { onNavigate(AppDest.PERMISSIONS) }
            )
            AkLine(
                title = AppDest.SKILLS.label,
                value = if (skillInfos.isNotEmpty()) "${skillInfos.size} 个" else null,
                onClick = { onNavigate(AppDest.SKILLS) }
            )
            AkLine(
                title = AppDest.LOGS.label,
                // 数字优先：有未读 ERROR 时报条数（用户据此判断「要不要现在去看」）；
                // 没有 ERROR 但有未看过的崩溃时退化成红点——崩溃是低频事件，一个点
                // 足够提示「这里有事」，写成条数反而像在报错次数。
                value = if (unreadErrors > 0) "$unreadErrors 条" else null,
                onClick = { onNavigate(AppDest.LOGS) },
                trailing = if (unreadErrors == 0 && hasUnreadCrash) {
                    { AkStatusDot(MaterialTheme.colorScheme.error, 7.dp) }
                } else {
                    null
                }
            )
            AkLine(
                title = AppDest.MEMORY.label,
                value = if (memories.isNotEmpty()) "${memories.size} 条" else null,
                onClick = { onNavigate(AppDest.MEMORY) }
            )

            // —— 能做的事：整段说明就是内容，不需要容器 ——
            AkSectionLabel(text = "能做的事", gap = 30)
            Text(
                text = "可用工具：ui_tree（读取界面）、ui_tap / ui_long_press / ui_swipe（手势）、" +
                    "ui_input（输入文本）、ui_key（home / back / recent）、ui_click（按文本或 ID 点击）、" +
                    "ui_screenshot（截取屏幕给视觉模型看）、device_notifications（读取最近通知）、" +
                    "device_foreground_app（读取当前前台应用）。\n\n" +
                    "在对话里直接说“打开设置并点击某某”“截个屏看看现在是什么界面”" +
                    "“刚才有没有新消息”，即可让 Agent 操作并感知手机。",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = AgentSpacing.space2)
            )
        }
    }
}

/**
 * 状态词：小色点 + 文字（点色与文字同色双编码，色盲可读，UX v1 §5.3）。
 *
 * 就绪用 success 墨迹；未就绪用中性墨——「未授权 / 未开启」不是错误，不上红。
 */
@Composable
private fun StatusWord(ok: Boolean, okText: String, noText: String) {
    val ak = LocalAkColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        AkStatusDot(if (ok) ak.success else ak.outlineStrong, 6.dp)
        Spacer(Modifier.width(AgentSpacing.space2))
        Text(
            text = if (ok) okText else noText,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = if (ok) ak.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 21.sp),
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
        AkLine(title = "前台应用", meta = fg, dense = true)
    }
    val notifications = deviceContext.recentNotifications
    if (notifications.isNotEmpty()) {
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = "最近通知",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
            notifications.take(3).forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    // 装饰性中性小点：条目已由文字承载语义，不做状态双编码
                    AkStatusDot(MaterialTheme.colorScheme.onSurfaceVariant, 5.dp)
                    Spacer(Modifier.width(AgentSpacing.space2))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        ),
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
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
