package com.beichen.polaris.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.container.RootfsManager
import com.beichen.polaris.ui.contract.PermissionReadout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「准备清单」：配好模型之后，还差哪些步骤才能让它真的替你干活。
 *
 * ## 为什么需要它
 * 首启漏斗现在是散的：引导弹窗只讲「配模型」，配完就消失；无障碍在权限引导页、
 * 通知在系统设置、容器在设置页里。用户配完模型就以为完事了，过几天发现
 * 「它不能操作手机」——再回来找入口，路径已经忘了。
 *
 * 这张卡片把「增强能力」集中到对话页顶部：缺什么、点哪里、都做完就自动消失。
 *
 * ## 刻意不做的事
 * - **不拦路**：不配也能聊天，卡片可折叠、可忽略。拦路是首启漏斗最大的杀手。
 * - **不重复讲模型**：模型由引导弹窗负责，这里只管「配完之后」。
 * - **不显示百分比进度条**：4 项里 1 项没做不代表「75% 可用」，
 *   每项是独立的增强，用「还差 N 项」比百分比诚实。
 */
@Composable
fun ReadinessCard(
    vm: PermissionReadout,
    onOpenContainer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val serviceConnected by vm.serviceConnected.collectAsStateWithLifecycle()
    val notificationGranted by vm.notificationGranted.collectAsStateWithLifecycle()
    val usageGranted by vm.usageGranted.collectAsStateWithLifecycle()

    // 容器状态要读文件系统，放 IO；null = 还没读出来（此时按「未就绪」显示，不闪）
    var containerReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        containerReady = withContext(Dispatchers.IO) {
            RootfsManager.listAvailable(ctx).any { RootfsManager.isExtracted(ctx, it.id) }
        }
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    data class Item(val title: String, val hint: String, val done: Boolean, val action: (() -> Unit)?)

    val items = listOf(
        Item("无障碍服务", "让它替你看屏幕、点按钮", serviceConnected) {
            vm.openAccessibilitySettings(ctx)
        },
        Item("通知权限", "任务跑完能提醒你", notificationGranted) {
            openAppNotificationSettings(ctx)
        },
        Item("使用情况访问", "知道你正在用哪个 App", usageGranted) {
            safeStart(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS)
        },
        Item("容器运行时", "在里面跑 node / python / java", containerReady == true) {
            onOpenContainer()
        }
    )
    val missing = items.count { !it.done }
    if (missing == 0) return

    // v5：浅色圆角块 → 一行 + 一条细线。它是一条「提示」，不是一张卡片；
    // 给它容器边界，就等于告诉用户「这里有一块新东西要处理」，而它其实只是
    // 一句「还差几项」。收起来时占一行，展开时是几行清单，两种状态都不需要边框。
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = AgentSpacing.space5)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // v5：删掉了底下那行「点开看是哪几项」。标题已经把事说完了，
                // 再解释一遍「点开能看到什么」是替用户念了一遍自己的动作。
                Text(
                    text = "还差 $missing 项，就能全自动",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.filter { !it.done }.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = item.action != null) { item.action?.invoke() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "○",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.action != null) {
                                Text(
                                    text = "去开启",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            AkHairline()
        }
    }
}

/** 直达本应用的通知设置（API 26+），失败回落到应用详情页。 */
private fun openAppNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (!safeStart(ctx, intent)) {
        safeStart(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
            }
        )
    }
}

private fun safeStart(ctx: Context, action: String) {
    safeStart(ctx, Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 打开系统页面；个别 ROM 没有对应 Activity 时返回 false，不崩。 */
private fun safeStart(ctx: Context, intent: Intent): Boolean = runCatching {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
