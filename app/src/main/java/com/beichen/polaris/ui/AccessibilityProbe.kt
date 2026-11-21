package com.beichen.polaris.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.automation.AgentAccessibilityService
import com.beichen.polaris.core.capability.A11yState

/**
 * Android 侧的无障碍探测。判定逻辑（三态）在 core 的 [A11yState]，这里只负责
 * 「去系统里读一眼」+ 给 Composable 提供记忆化的结果。
 *
 * 三态的意义：把「授权被系统清掉了」和「授权还在但服务没上线」分开——
 * 前者要用户重新授权（`adb install` 覆盖安装就会清掉，实测），后者只需要等服务或被占用方退出。
 * 详见 [A11yState] 的类注释。
 */

/**
 * 本应用的无障碍服务是否**仍列在系统的启用清单里**（不代表已连上）。
 *
 * 只读设置，不触发绑定；失败一律按「没启用」处理——把异常抛到 UI 会让整页崩掉，
 * 而这条信息只用于把提示说得更准，不值得为它中断渲染。
 */
fun isEnabledInSettings(context: Context, serviceClass: Class<*>): Boolean = try {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val target = ComponentName(context, serviceClass).flattenToString()
    am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.any { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(si.packageName, si.name).flattenToString().equals(target, ignoreCase = true)
        } ?: false
} catch (_: Exception) {
    false
}

/**
 * 给 UI 用的探测：只在 [serviceConnected] 变化时重读一次系统设置。
 *
 * 不用 `LaunchedEffect(Unit)` 只读一次——抽屉是常驻的，服务掉线不会重建整个页面；
 * 而 `serviceConnected` 变 false 的那一刻正是需要重新判断「是没开还是被顶掉」的时刻。
 */
@Composable
fun rememberA11yEnabledInSettings(serviceConnected: Boolean): Boolean {
    val context = LocalContext.current
    return remember(serviceConnected) {
        isEnabledInSettings(context, AgentAccessibilityService::class.java)
    }
}
