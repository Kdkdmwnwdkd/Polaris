package com.beichen.polaris.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.automation.AgentAccessibilityService
import com.beichen.polaris.core.capability.A11yState
import com.beichen.polaris.core.capability.isA11yServiceListed

/**
 * Android 侧的无障碍探测。判定逻辑（三态、字符串解析）在 core 的 [A11yState] /
 * `isA11yServiceListed`，这里只负责「去系统里读一眼」+ 给 Composable 提供记忆化的结果。
 *
 * 三态的意义：把「授权被系统清掉了」和「授权还在但服务没上线」分开——
 * 前者要用户重新授权（`adb install` 覆盖安装就会清掉，实测），后者要关掉再打开开关。
 * 详见 [A11yState] 的类注释。
 */

/**
 * 本应用的无障碍服务是否**仍列在系统的启用清单里**（不代表已连上）。
 *
 * 读的是设置项的原始字符串，不是 `AccessibilityManager.getEnabledAccessibilityServiceList()`：
 * 后者会把崩过的服务过滤掉，导致「开关是开的但服务崩了」这个真实状态永远探测不到
 * （云机实测，详见 `isA11yServiceListed` 的注释）。
 *
 * 总开关也一并看：`ACCESSIBILITY_ENABLED` 为 0 时清单可能还留着旧条目，但功能整体是关的，
 * 这时提示「去开启」才是对的。
 *
 * 只读设置，不触发绑定；失败一律按「没启用」处理——把异常抛到 UI 会让整页崩掉，
 * 而这条信息只用于把提示说得更准，不值得为它中断渲染。
 */
fun isEnabledInSettings(context: Context, serviceClass: Class<*>): Boolean = try {
    val cr = context.contentResolver
    val masterOn = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    val raw = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    masterOn && isA11yServiceListed(raw, context.packageName, serviceClass.name)
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
