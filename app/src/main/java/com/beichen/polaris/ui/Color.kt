package com.beichen.polaris.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * 暖纸 Warm Paper 色板（视觉规范 v3.1 §3.2 / §3.3）。
 *
 * 设计取向：冷灰底 + 白卡 + 更浅内嵌的三层明度分层，全 App 0 阴影 0 描边；
 * 单一主色「藏蓝」落在冷底上同族，语义色严格四槽（danger / warning / success / trust-sandbox）。
 *
 * ## 2026-11-26：色温统一（真机反馈「颜色怪怪的，但说不上哪里」）
 *
 * 这套色板原本叫「暖纸」——暖粉底 + 暖棕语义色。后来底与主色整体转冷（#F4F6F9 / #22406B），
 * **四个中性槽却漏在暖棕一侧**：trustSandbox #6B5B4E、scrim #1A120D、inverseSurface #2E2620、
 * logDebug #756450。它们不是语义色（没有「必须橙」「必须红」的理由），只是中性灰，
 * 带暖就与整屏冷调对冲——人眼说不出哪一处不对，只觉得脏。
 *
 * 判据：**中性槽一律取主色的色相、零饱和度偏移**；只有 danger / warning / success 允许
 * 保留语义色相（红 / 橙 / 绿）。深色的主色是星金，金配夜蓝成立，故只改中性槽、不动 DarkPrimary。
 *
 * 同一批还修了三处注释与实现不符（surface2 / outlineStrong / scrim 的注释里还写着暖值），
 * 它们正是「漏改」这件事的化石。
 * 本文件是全 App 唯一允许出现硬编码 Color(0x..) 的地方，改色值必须先回规范 §3.4 复测对比度。
 */

// —— Light（主设计套）§3.2 ——
private val LightBg = Color(0xFFF7F8FB)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurface1 = Color(0xFFF1F3F7)
private val LightSurface2 = Color(0xFFE7EBF1)
private val LightSurfaceVar = Color(0xFFF0F3F8)
private val LightOnSurface = Color(0xFF121821)
private val LightOnSurfaceVar = Color(0xFF5C6675)
private val LightOutline = Color(0xFFE4E8EF)
private val LightOutlineStrong = Color(0xFF7A8496)
private val LightPrimary = Color(0xFF22406B)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFE2EAF6)
private val LightOnPrimaryContainer = Color(0xFF16304F)
private val LightDanger = Color(0xFFB4322A)
private val LightOnDanger = Color(0xFFFFFFFF)
private val LightDangerContainer = Color(0xFFF9E2DF)
private val LightOnDangerContainer = Color(0xFF7E1F16)
private val LightWarning = Color(0xFF8A5A12)
private val LightWarningContainer = Color(0xFFF7E8D2)
private val LightOnWarningContainer = Color(0xFF5C3A05)
private val LightSuccess = Color(0xFF177245)
private val LightSuccessContainer = Color(0xFFDCF0E3)
private val LightOnSuccessContainer = Color(0xFF0F4A2A)
private val LightTrustSandbox = Color(0xFF5E6B7A)
private val LightLogDebug = Color(0xFF6B7787)
private val LightLogInfo = Color(0xFF0E5D6B)
private val LightInverseSurface = Color(0xFF1C2430)
private val LightInverseOnSurface = Color(0xFFF2F6FB)
/** 弹窗遮罩：冷墨 #0E1420 @ 40%（不用纯黑——纯黑会把下面的层次压死）。 */
private val LightScrim = Color(0x660E1420)

// —— Dark（配套平行套，暖黑非冷黑）§3.3 ——
private val DarkBg = Color(0xFF101724)
private val DarkSurface = Color(0xFF182233)
private val DarkSurface1 = Color(0xFF1E2A3D)
private val DarkSurface2 = Color(0xFF243147)
private val DarkSurfaceVar = Color(0xFF2E3A50)
private val DarkOnSurface = Color(0xFFEEF2F8)
private val DarkOnSurfaceVar = Color(0xFFA5B1C4)
private val DarkOutline = Color(0xFF26324A)
private val DarkOutlineStrong = Color(0xFF8B97AC)
private val DarkPrimary = Color(0xFFD2A85E)
private val DarkOnPrimary = Color(0xFF1A1408)
private val DarkPrimaryContainer = Color(0xFF2A3346)
private val DarkOnPrimaryContainer = Color(0xFFE8C583)
private val DarkDanger = Color(0xFFFF8D82)
/** Dark 专用「允许执行」按钮填充：亮珊瑚只做文字/图标，不做大填充（§3.4 注 1）。 */
private val DarkDangerFill = Color(0xFFC2483A)
private val DarkOnDangerFill = Color(0xFFFFFFFF)
private val DarkDangerContainer = Color(0xFF3D1A14)
private val DarkOnDangerContainer = Color(0xFFFFC9C0)
private val DarkWarning = Color(0xFFF2B950)
private val DarkWarningContainer = Color(0xFF3A2A12)
private val DarkOnWarningContainer = Color(0xFFF5D9A0)
private val DarkSuccess = Color(0xFF62D996)
private val DarkSuccessContainer = Color(0xFF123828)
private val DarkOnSuccessContainer = Color(0xFFBFF0D4)
private val DarkTrustSandbox = Color(0xFF94A1B2)
private val DarkLogDebug = Color(0xFF94A0AF)
private val DarkLogInfo = Color(0xFF7FC8CE)
private val DarkInverseSurface = Color(0xFFEAF0F7)
private val DarkInverseOnSurface = Color(0xFF111823)
/** 弹窗遮罩：纯黑 @ 56%。 */
private val DarkScrim = Color(0x8F000000)

/**
 * Material3 ColorScheme 映射（规范 §8.1）。
 *
 * secondary / tertiary 无规范独立色值，直接复用 primary 同族（暖纸全 App 同时出现色相 ≤5 族，
 * 不引入规范外色相）；surfaceContainer* 承载 surface-1 / surface-2 阶层。
 */
internal val AgentKitLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightPrimaryContainer,
    onSecondaryContainer = LightOnPrimaryContainer,
    tertiary = LightPrimary,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightPrimaryContainer,
    onTertiaryContainer = LightOnPrimaryContainer,
    background = LightBg,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightOnSurfaceVar,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface1,
    surfaceContainer = LightSurface1,
    surfaceContainerHigh = LightSurface2,
    surfaceContainerHighest = LightSurface2,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutline,
    scrim = LightScrim,
    error = LightDanger,
    onError = LightOnDanger,
    errorContainer = LightDangerContainer,
    onErrorContainer = LightOnDangerContainer
)

internal val AgentKitDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    tertiary = DarkPrimary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkPrimaryContainer,
    onTertiaryContainer = DarkOnPrimaryContainer,
    background = DarkBg,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = DarkOnSurfaceVar,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurface1,
    surfaceContainer = DarkSurface2,
    surfaceContainerHigh = DarkSurfaceVar,
    surfaceContainerHighest = DarkSurfaceVar,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    scrim = DarkScrim,
    error = DarkDanger,
    onError = DarkOnDangerFill,
    errorContainer = DarkDangerContainer,
    onErrorContainer = DarkOnDangerContainer
)

/**
 * 规范 §3.8 中 ColorScheme 承载不下的语义 token（surface-1/2 阶层、强交互边界、
 * 语义四槽、日志级别、信任等级、scrim）。淡 tint 徽章的底由语义色 12% alpha 现场合成
 * （§3.4 注 2），文字一律取对应 on-*-container 深色墨迹（§3.5）。
 */
internal data class AgentKitColors(
    /** 内嵌块 / 图标底座 / chip 底 / 抽屉底（Light #EEF1F6 / Dark #1E2A3D）。 */
    val surface1: Color,
    /** 按压态 / 更深内嵌（Light #E7EBF1 / Dark #2A211C）。 */
    val surface2: Color,
    /** 交互边界强轨 ≥3:1（Light #7A8496 / Dark #8B97AC）。 */
    val outlineStrong: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** 「允许执行」按钮填充：Light 复用 danger，Dark 用专用暗红（§3.4 注 1）。 */
    val dangerFill: Color,
    val onDangerFill: Color,
    /** 沙箱信任等级（中性暖灰，不是语义色）。 */
    val trustSandbox: Color,
    val logDebug: Color,
    val logInfo: Color,
    val logWarn: Color,
    val logError: Color,
    /** 抽屉 / 弹窗遮罩（Light #0E1420@40% / Dark #000@56%）。 */
    val scrim: Color
)

internal val LightAgentKitColors = AgentKitColors(
    surface1 = LightSurface1,
    surface2 = LightSurface2,
    outlineStrong = LightOutlineStrong,
    success = LightSuccess,
    successContainer = LightSuccessContainer,
    onSuccessContainer = LightOnSuccessContainer,
    warning = LightWarning,
    warningContainer = LightWarningContainer,
    onWarningContainer = LightOnWarningContainer,
    dangerFill = LightDanger,
    onDangerFill = LightOnDanger,
    trustSandbox = LightTrustSandbox,
    logDebug = LightLogDebug,
    logInfo = LightLogInfo,
    logWarn = LightWarning,
    logError = LightDanger,
    scrim = LightScrim
)

internal val DarkAgentKitColors = AgentKitColors(
    surface1 = DarkSurface1,
    surface2 = DarkSurface2,
    outlineStrong = DarkOutlineStrong,
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    onSuccessContainer = DarkOnSuccessContainer,
    warning = DarkWarning,
    warningContainer = DarkWarningContainer,
    onWarningContainer = DarkOnWarningContainer,
    dangerFill = DarkDangerFill,
    onDangerFill = DarkOnDangerFill,
    trustSandbox = DarkTrustSandbox,
    logDebug = DarkLogDebug,
    logInfo = DarkLogInfo,
    logWarn = DarkWarning,
    logError = DarkDanger,
    scrim = DarkScrim
)

/** 语义色 CompositionLocal：随明暗主题切换，替代旧静态 AgentColors 对象（规范 §8.1）。 */
internal val LocalAgentKitColors = staticCompositionLocalOf { LightAgentKitColors }

// =====================================================================================
// W-64：主题强调色（预设 + 自定义）
// =====================================================================================

/**
 * 预设强调色。
 *
 * 每个色给 light/dark 两个值：同一个色相在深色底上必须提亮，否则会糊成一片
 * （`#22406B` 放在 `#17110E` 上几乎看不见）。这是 Material 的 tonal palette 规则，
 * 手写两个值比运行时算 HSL 更可控。
 */
enum class AccentPreset(val label: String, val light: Long, val dark: Long) {
    /** 默认：夜空蓝底上的星金。北辰的题面。 */
    NIGHT_GOLD("星金", 0xFFB7862B, 0xFFD2A85E),
    AZURE("天青", 0xFF22406B, 0xFF7FA8D9),
    PINE("松绿", 0xFF1E5B47, 0xFF6FC3A2),
    CLAY("赭石", 0xFF9C4A2F, 0xFFD98F6F),
    IRIS("青紫", 0xFF4B3F86, 0xFFA99BE0);

    fun color(dark: Boolean): Color = Color(if (dark) this.dark else this.light)

    companion object {
        fun ofName(name: String?): AccentPreset? = entries.firstOrNull { it.name == name }
    }
}

/** 解析 `#RRGGBB` / `RRGGBB` / `#AARRGGBB`；非法返回 null（调用方保留当前色）。 */
fun parseAccentHex(raw: String?): Color? {
    val s = raw?.trim()?.removePrefix("#") ?: return null
    if (s.length != 6 && s.length != 8) return null
    val v = s.toLongOrNull(16) ?: return null
    return if (s.length == 6) Color(0xFF000000L or v) else Color(v)
}

/** 便于 UI 回显：`#RRGGBB`。 */
fun Color.toHexString(): String {
    val argb = (this.value shr 32).toInt()
    return "#%02X%02X%02X".format((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

internal fun Color.blendTo(target: Color, amount: Float): Color = Color(
    red = red + (target.red - red) * amount,
    green = green + (target.green - green) * amount,
    blue = blue + (target.blue - blue) * amount,
    alpha = 1f
)

/**
 * W-65：我方气泡的「淡色玻璃」四件套。
 *
 * 旧实现是 `primary` 实色块 + `onPrimary` 白字：一整屏翻下来，自己的话像四五个深色
 * 补丁压在正文里，比 AI 的长段回复还重。用户的说法是「淡一点，或者渐变，或者玻璃那种
 * 透一点，但是要能看出是气泡」——所以这里取 [ColorScheme.surface] 当底，按 9%→28%
 * （深色 13%→34%）叠主题色：低端几乎融进底色，高端才透出一点点色相，渐变本身就构成边界；
 * 再补 0.5dp 的 [stroke]（32%/46% 主题色）当玻璃边缘，浅底上不会糊成一片。
 */
internal data class UserBubbleColors(
    /** 渐变起点（靠左下，最淡）。 */
    val start: Color,
    /** 渐变终点（靠右上，稍深）。 */
    val end: Color,
    /** 正文色：不是纯黑，掺 30% 主题色，压在淡色底上仍 ≥7:1。 */
    val text: Color,
    /** 0.5dp 描边：模拟玻璃边缘的那道亮线。 */
    val stroke: Color
)

/**
 * 按当前主题算我方气泡配色。[ColorScheme.surface] 作底而不是 background：
 * 浅色下聊天页底色比卡片灰，用 surface 才亮得出来；深色下同理，气泡要浮在页面底之上。
 */
internal fun userBubbleColors(scheme: ColorScheme): UserBubbleColors {
    val dark = scheme.surface.luminance() < 0.35f
    val base = scheme.surface
    return UserBubbleColors(
        start = base.blendTo(scheme.primary, if (dark) 0.13f else 0.09f),
        end = base.blendTo(scheme.primary, if (dark) 0.34f else 0.28f),
        // 浅色：正文黑掺主题色 → 偏蓝的深字；深色：直接用 onSurface，掺色会压低对比度
        text = if (dark) scheme.onSurface else scheme.onSurface.blendTo(scheme.primary, 0.30f),
        stroke = base.blendTo(scheme.primary, if (dark) 0.46f else 0.32f)
    )
}

/**
 * 把当前 [ColorScheme] 的强调色换成 [accent]，其余角色（背景/表面/描边/语义色）原样保留。
 *
 * 为什么只替换 primary 族：全 App 的品牌感由 primary + primaryContainer 承载，
 * 而可读性由 background/surface/onSurface 决定。只动前者，任何自定义色都不会
 * 把文字对比度搞坏——用户选了个荧光黄也不会变成不可读的白底白字。
 */
internal fun accentColorScheme(base: ColorScheme, accent: Color, dark: Boolean): ColorScheme {
    // 亮色 accent 上必须压深色字，否则 #D2A85E 上的白字对比度只有 1.9:1
    val onAccent = if (accent.luminance() > 0.55f) Color(0xFF1A1408) else Color.White
    val container = accent.copy(alpha = if (dark) 0.26f else 0.14f).compositeOver(base.surface)
    val onContainer = if (dark) accent.blendTo(Color.White, 0.25f) else accent.blendTo(Color.Black, 0.28f)
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer
    )
}
