package com.beichen.polaris.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.CrashLogger
import kotlinx.coroutines.delay

/**
 * 共享基件（视觉规范 v3.1 §6.0 / §6.1 / §6.5 / §6.9）。
 *
 * 全 App 的按钮 / 列表项 / 徽章 / 输入框 / 开关 / 空态都在这一处定义，理由：
 * ① 「0 阴影 0 描边、三层底色分层」只需要一种卡片实现，散落多处必然漂移；
 * ② 按压语言（scale 0.97 + 100ms + surface-2 底，§7-M3）统一收口，页面零成本合规；
 * ③ 触控 ≥48dp / 容器 heightIn(min) 的 fontScale 保护只写一遍（UX v1 §5.2）。
 *
 * 颜色一律走 MaterialTheme.colorScheme 与 LocalAgentKitColors.current，本文件禁止硬编码色值。
 */

/**
 * 系统「减弱动态效果」状态（UX v1 §5.4）：开发者选项 / 省电模式把动画时长缩放置 0 时返回 true。
 * 装饰性动效降级为静态，信息性动效（进度/倒计时）保留。读取一次缓存——该开关变更后用户通常重启 App。
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        scale == 0f
    }
}

/** 语义色速取：避免每个文件都重复写 LocalAgentKitColors.current。 */
internal val LocalAkColors: AgentKitColors
    @Composable get() = LocalAgentKitColors.current

/**
 * 抽屉打开动作（AppScaffold 注入）：宽屏常驻态下为空操作。
 * 页面不感知导航宿主细节，只消费这个本地值——屏幕层与抽屉层解耦。
 */
internal val LocalDrawerOpener = staticCompositionLocalOf<() -> Unit> { {} }

/** 当前是否为宽屏常驻抽屉（≥840dp）：为 true 时顶栏汉堡隐藏，避免死按钮（UX v1 §4.2）。 */
internal val LocalPermanentDrawer = staticCompositionLocalOf { false }

/**
 * 顶栏汉堡的焦点请求器（AppScaffold 注入）：抽屉关闭后焦点返回汉堡（§6.5.5）。
 * 宽屏常驻态下无汉堡，AppScaffold 传 null，本文件默认值同为 null。
 */
internal val LocalHamburgerFocus = staticCompositionLocalOf<FocusRequester?> { null }

// ———————————————————— 顶栏（§6.5.1：56dp 透明骨架） ————————————————————

/**
 * 每页同一骨架的透明顶栏：左圆形汉堡 + 居中标题 + 右 0–1 个动作。
 *
 * 常驻透明无底色无分割线（与页面同色 --ak-bg），滚动不变实色——内容区从顶栏下方起排，
 * 由外层 Column 天然占位，零监听零状态。右侧无动作时补 48dp 占位保持标题真居中。
 */
@Composable
fun AkTopBar(
    title: String,
    onOpenDrawer: () -> Unit,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    /** false = 标题由页面自己画（大标题），顶栏中间留空。 */
    showTitle: Boolean = true
) {
    // 宽屏常驻抽屉时隐藏汉堡（无抽屉可开，避免死按钮，UX v1 §4.2）；仍补 48dp 占位保持标题居中
    val permanent = LocalPermanentDrawer.current
    // 抽屉关闭后焦点返回汉堡（§6.5.5）；宽屏无汉堡时为 null，不挂焦点请求器
    val hamburgerFocus = LocalHamburgerFocus.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AgentDimens.topBarHeight)
            .padding(horizontal = AgentSpacing.space1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (permanent) {
            Spacer(Modifier.size(AgentDimens.touchTarget))
        } else {
            AkCircleIconButton(
                icon = Icons.Outlined.Menu,
                description = "打开导航抽屉",
                onClick = onOpenDrawer,
                // P2：聚光灯引导的「抽屉」锚点（无 Provider 时是空操作，不影响其他页面）
                modifier = (if (hamburgerFocus != null) Modifier.focusRequester(hamburgerFocus) else Modifier)
                    .guideAnchor(GuideAnchor.DRAWER)
            )
        }
        // v5：顶栏标题降为 12.5sp 位置条。
        //
        // v4 这里是 20sp 标题，页内再放任何大标题都会和它争——于是每页只能有一个
        // 20sp 的横排标题，内容全部压在它下面，整屏就只剩「标题 + 一堆同样重的块」。
        // 现在顶栏只负责回答「我在哪」，真正的标题交给页面内的衬线大字（AkDisplayTitle）：
        // 层级从「一条 20sp 标题」变成「一条 12.5sp 位置 + 一个 28sp 页名」，页面才有第一眼。
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.8.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (actionIcon != null) {
            AkCircleIconButton(
                icon = actionIcon,
                description = actionDescription,
                enabled = actionEnabled,
                onClick = onAction
            )
        } else {
            // 无动作页：右侧补等宽占位，标题才能保持视觉居中（§6.5.1）
            Spacer(Modifier.size(AgentDimens.touchTarget))
        }
    }
}

/**
 * 顶栏/列表用圆形图标按钮：触控 48dp、视觉 40dp（§5.4）。
 * 按压 = surface-2 底 + scale 0.97 + 100ms（§6.5.1 / §7-M3）；不用涟漪，暖纸里色块反馈已足够。
 */
@Composable
fun AkCircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    visualSize: Dp = AgentDimens.topBarButtonVisual,
    iconSize: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = modifier
            .size(AgentDimens.touchTarget)
            .clip(CircleShape)
            .background(
                if (pressed && enabled) LocalAkColors.surface2 else Color.Transparent
            )
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize)
        )
    }
}

// ———————————————————— 按压容器（全局按压语言 §7-M3） ————————————————————

/**
 * 统一可点容器：按压时 bg surface-2 + scale 0.97（100ms），松手回弹。
 *
 * 列表项 / 抽屉项 / 建议卡 / 工具卡头部全部走这里——「我收到你的触摸了」只一种手感。
 */
@Composable
fun AkPressSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(14.dp),
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) LocalAkColors.surface2 else Color.Transparent,
        animationSpec = AkMotionFastColor
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = contentAlignment
    ) { content() }
}

// ———————————————————— 按钮（§6.0 三形态 + Danger，高 48dp radius 14dp） ————————————————————

/** Filled 主按钮：primary 底 + on-primary 14sp w600；禁用整体 38% alpha。 */
@Composable
fun AkPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    AkButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
        border = null
    )
}

/** Outlined 次按钮：1.5dp outline-strong 描边 + on-surface 文字，透明底；按压 surface-2。 */
@Composable
fun AkOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    AkButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        container = Color.Transparent,
        content = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, LocalAkColors.outlineStrong)
    )
}

/** Danger Filled（仅审批「允许执行」）：Light 复用 danger、Dark 走 dangerFill（§3.4 注 1）。 */
@Composable
fun AkDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    AkButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = null,
        container = LocalAkColors.dangerFill,
        content = LocalAkColors.onDangerFill,
        border = null
    )
}

/** Text 文字按钮：无底无描边，按压 surface-1；[tint] 传 primary 或 danger。 */
@Composable
fun AkTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    AkButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        container = Color.Transparent,
        content = tint,
        border = null
    )
}

/**
 * 按钮公共实现：高 48dp（heightIn(min) 防 fontScale 裁切，UX v1 §5.2）、
 * 文字 labelLarge 14sp w600、按压 scale 0.97。按钮行一律 flex 1 等高，不制造视觉偏袒（§6.0）。
 *
 * v5：圆角 14 → 4。14dp 是「Material 卡片」的圆角，它让每个按钮看起来像一块
 * 可拖动的卡片；4dp 只把尖角磨掉一点，按钮仍然是「一条」。同一页里所有容器都被
 * 拿掉之后，圆角按钮会成为整屏唯一带圆的东西，反而更扎眼。
 */
@Composable
private fun AkButtonImpl(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector?,
    container: Color,
    content: Color,
    border: BorderStroke?
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val shape = RoundedCornerShape(4.dp)
    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> container.copy(alpha = 0.38f)
            pressed && container == Color.Transparent -> LocalAkColors.surface1
            else -> container
        },
        animationSpec = AkMotionFastColor
    )
    val fg = content.copy(alpha = if (enabled) 1f else 0.38f)
    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .heightIn(min = AgentDimens.touchTarget)
            .padding(horizontal = AgentSpacing.space6),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space2))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 2
        )
    }
}

// ———————————————————— 卡片与列表（v5：无卡片，靠细线与留白分组） ————————————————————

/**
 * 分组容器向行注入的排版参数。
 *
 * v5 之前，行的 16dp 内缩进写死在 [AkListItem] 里——那是「卡片内边距」的镜像：
 * 卡片自己有 20dp 圆角底，行必须再缩一层才不会贴到圆角上。
 *
 * 卡片拿掉之后，这层缩进就变成纯粹的错位来源：分组标签在 20dp（页面边距），
 * 行文字在 36dp，整组往右漂。所以缩进改由容器决定：
 *
 * - 在 [AkGroup] / [AkSectionCard] 里 → 0dp（与分组标签同一条竖线），行自带底部分隔线；
 * - 独立使用 [AkListItem]（抽屉、对话页的卡片）→ 仍是 16dp，不画线（外面已有容器语义）。
 */
internal data class AkRowScope(val inset: Dp, val divider: Boolean)

internal val LocalAkRowScope = staticCompositionLocalOf {
    AkRowScope(inset = AgentSpacing.space4, divider = false)
}

/**
 * 白色分组卡。
 *
 * v5：Surface 去掉，只剩一根 1dp 顶线。它的调用点都是「一整块内容」，
 * 而一块内容与下一块内容之间，一条线比一个 20dp 圆角的容器更轻——
 * 圆角容器会把内容向外推，线不会。
 */
@Composable
fun AkGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        AkHairline()
        Spacer(Modifier.height(AgentSpacing.space2))
        CompositionLocalProvider(LocalAkRowScope provides AkRowScope(0.dp, true)) {
            Column(content = content)
        }
    }
}

/** 装饰分隔线：1dp outline（<2:1，仅分隔不提示，§3.2 双轨制）。 */
@Composable
fun AkDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * 分区（v5 编辑式）：11sp 字距标签 + 可选说明 + 内容槽。
 *
 * 旧版是「图标底座 + 16sp 标题 + 说明 + 内容」的卡片。问题不在样式，在结构：
 * 一张卡就是一个视觉单位，页面上一旦有 4~5 张卡，读者看到的是「5 个同等重要的块」，
 * 而不是「1 个主状态 + 4 个次级入口」。层级不该由容器的边界来表达，那太贵。
 *
 * 现在只留一行小字标签。标签的字距（1.2sp）本身就把它和正文区分开了，
 * 不需要底色、不需要图标、也不需要圆角。
 *
 * @param icon 保留但不再渲染：32 处调用点都传了它，逐个删参数只会制造冲突，
 *   而图标在 v5 的结构里没有位置——分组标签右侧再挂一个淡图标，等于把刚删掉的
 *   「每组一个图标」又请回来。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AkSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            // 说明文字用 13sp 而不是 11.5sp：控制台「可用能力」那一组整段都是说明，
            // 太小就变成不可读的灰条，用户会直接跳过——而那段恰恰是唯一告诉他
            // 「这东西能做什么」的地方。
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 21.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalAkRowScope provides AkRowScope(0.dp, true)) {
            Column(content = content)
        }
    }
}

/**
 * 列表项（§6.1 RikkaHub 两行结构）：40dp 圆角图标底座 + 主文案 16sp/24 w500 +
 * 描述 13sp/20（1 行 ellipsis）+ 右侧控件。两行 ≥72dp、单行 ≥56dp，按压 surface-2 + scale 0.97。
 */
/**
 * 设置分组（v5 编辑式）。
 *
 * 与 [AkSectionCard] 的差别只剩内容约定：这里默认放 [AkListItem]（自带行间距与分隔线），
 * 版本演进记录：
 * - v4：标题移到卡外，12sp 灰字，卡片仍有 20dp 圆角底；
 * - v5：卡片也拿掉，标题降到 11sp + 1.2sp 字距。分组之间只靠 26dp 留白，
 *   不再有一条横贯全屏的分组标题把页面切成格子。
 *
 * @param paddedContent `true` 表示内容是表单控件（输入框 / 滑块 / 按钮），
 *   它们没有自己的水平边距，需要容器兜一层；`false`（默认）表示内容是
 *   [AkListItem]，缩进由 [LocalAkRowScope] 统一下发，容器不能再加。
 */
@Composable
fun AkGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    paddedContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.height(if (description.isNullOrBlank()) 8.dp else 10.dp))
        CompositionLocalProvider(LocalAkRowScope provides AkRowScope(0.dp, true)) {
            Column(if (paddedContent) Modifier.padding(top = 2.dp) else Modifier) {
                content()
            }
        }
    }
}

@Composable
fun AkListItem(
    /**
     * 行首图标；传 **null 表示这一行不需要图标**。
     *
     * 保持「无默认值」是有意的：现有 200+ 调用点全部显式传图标，加默认值会让
     * `AkListItem(Icons.X, "标题")` 这类位置参数调用直接编译失败。
     */
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    // v5：缩进与分隔线由容器下发（见 LocalAkRowScope）。
    // 这样同一个 AkListItem 放进 AkGroup 时贴页边距、放进抽屉时保留 16dp，
    // 不再需要为两种场景写两套行。
    val scope = LocalAkRowScope.current
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (description != null) 58.dp else 50.dp)
                .padding(horizontal = scope.inset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 排版 v5：34dp 圆角图标底座整体删除，只留 17dp 线图标。
            //
            // v4 的底座是「给每行一个色块锚点」的思路，取自 iOS 分组列表——
            // 但 iOS 只在**设置**里这么干，且底座是彩色语义块（蓝电话、绿微信），
            // 这里几十个底座全是同一个灰，就退化成一列无意义的方块。
            // 编辑式排版里这一列方块正是「生成感」的来源：内容本身够清楚时，
            // 图标只是噪音。
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(AgentSpacing.space3))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(AgentSpacing.space2))
                trailing()
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (onClick != null) {
            AkPressSurface(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                // 4dp 圆角：按压时是一道很浅的横条，不再是「浮起一张小卡」。
                shape = RoundedCornerShape(4.dp)
            ) { body() }
        } else {
            body()
        }
        if (scope.divider) AkHairline()
    }
}

/** 40dp 圆角图标底座（radius 12dp）：surface-1 底 + 22dp 线图标；选中升 primary-container。 */
@Composable
fun AkIconBase(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else LocalAkColors.surface1
    val fg = tint
        ?: if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = bg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ———————————————————— 徽章 / 状态点（§3.5 / §3.6：淡 tint + on-*-container 文字） ————————————————————

/**
 * 淡 tint 徽章：胶囊 radius full、padding h8 v3、11sp w500。
 * Light 套文字一律 on-*-container 深色墨迹（淡 tint 会把语义色文字压到 4.5:1 以下，§3.5）。
 */
@Composable
fun AkBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    dotColor: Color? = null
) {
    Surface(
        modifier = modifier.heightIn(min = 20.dp),
        shape = CircleShape,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AgentSpacing.space2, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(AgentSpacing.space1))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = content
            )
        }
    }
}

/** 8dp 状态点：decorative，语义由同色文字承载（色盲双编码铁律，UX v1 §5.3）。 */
@Composable
fun AkStatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 就绪文字三态（§6.5.2 精确文案；抽屉头部与权限中心页共用同一实现，杜绝两处措辞漂移）：
 * ① success 点 +「无障碍已连接」（0 待授权）；
 * ② warning 点 +「无障碍已连接 · N 项待授权」（N 用正文数字，与权限中心红点同源）；
 * ③ danger 点 +「无障碍未连接」。
 *
 * 状态词着色、其余 on-surface-var；整行可点（抽屉里跳权限中心，权限中心里就地刷新）。
 */
@Composable
fun AkReadyTextRow(
    serviceConnected: Boolean,
    pendingGrants: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    val dotColor = when {
        !serviceConnected -> MaterialTheme.colorScheme.error
        pendingGrants > 0 -> ak.warning
        else -> ak.success
    }
    val head = if (serviceConnected) "无障碍已连接" else "无障碍未连接"
    val tail = if (serviceConnected && pendingGrants > 0) " · ${pendingGrants} 项待授权" else ""
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val talkBack = if (serviceConnected) {
        if (pendingGrants > 0) "$head，${pendingGrants} 项待授权，双击进入权限中心" else head
    } else {
        "无障碍未连接，双击进入权限中心"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = talkBack }
            .padding(vertical = AgentSpacing.space1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AkStatusDot(dotColor, 6.dp)
        Spacer(Modifier.width(AgentSpacing.space1))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = dotColor)) { append(head) }
                if (tail.isNotEmpty()) {
                    withStyle(SpanStyle(color = onVar, fontWeight = FontWeight.Normal)) { append(tail) }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/**
 * 信任徽章（§3.6 技能专用三色）：● 内置（success）/ ● 导入（warning）/ ● 沙箱（中性暖灰）。
 * 徽章永不变色——来源不可变，授权状态在 footer 另起一行（UX v1 F3 反误解设计）。
 */
@Composable
fun TrustBadge(trust: String, modifier: Modifier = Modifier) {
    // success / warning 族不在 Material3 ColorScheme 里，走语义色数据类（§3.6）
    val ak = LocalAkColors
    // v5：不再用色底药丸。来源是一个词，不是一枚标签——一个 5dp 的点和一行小字就够，
    // 底色会把「内置」渲染成一个需要被注意的控件。色点仍保留双编码（色盲可读）。
    val label: String
    val dot: androidx.compose.ui.graphics.Color
    val ink: androidx.compose.ui.graphics.Color
    when (trust.lowercase()) {
        "packaged" -> {
            label = "内置"; dot = ak.success; ink = ak.onSuccessContainer
        }
        "imported" -> {
            label = "导入"; dot = ak.warning; ink = ak.onWarningContainer
        }
        else -> {
            // sandbox 与未知来源（如历史数据里的 remote）一律走中性暖灰：
            // 沙箱是「隔离观察」不是「危险告警」，红色只留给审批与 ERROR（§3.1）
            label = "沙箱"; dot = ak.outlineStrong; ink = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        AkStatusDot(dot, 5.dp)
        androidx.compose.foundation.layout.Spacer(
            androidx.compose.ui.Modifier.width(5.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = ink
        )
    }
}

// ———————————————————— 勾选 / 开关（§6.0：视觉收窄 + 触控 48dp） ————————————————————

/**
 * 自定义 checkbox：视觉 40dp 方框 radius 8dp，触控容器 48×48dp（§5.4，UX v1 §7.4-6）。
 * 未选 1.5dp outline-strong 描边；选中 primary 底 + 20dp on-primary 对勾。
 */
@Composable
fun AkCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(AgentDimens.touchTarget)
            .clip(CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(8.dp)
        val borderColor by animateColorAsState(
            targetValue = if (checked) MaterialTheme.colorScheme.primary
            else LocalAkColors.outlineStrong,
            animationSpec = AkMotionFastColor
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(
                    if (checked) MaterialTheme.colorScheme.primary else Color.Transparent
                )
                .border(1.5.dp, borderColor, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 自定义开关（§6.0）：track 46×28dp radius full；thumb 选中 22dp on-primary / 未选 16dp
 * outline-strong；选中 track primary / 未选 surface-2；thumb 位移与颜色 150ms（motionFast）。
 */
@Composable
fun AkSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(AgentDimens.touchTarget)
            .clip(CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        val thumbOffset by animateDpAsState(
            targetValue = if (checked) 21.dp else 3.dp,
            animationSpec = AkMotionFastDp
        )
        val trackColor by animateColorAsState(
            targetValue = if (checked) MaterialTheme.colorScheme.primary else LocalAkColors.surface2,
            animationSpec = AkMotionFastColor
        )
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(if (checked) 22.dp else 16.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.onPrimary
                        else LocalAkColors.outlineStrong
                    )
                    .align(Alignment.CenterStart)
            )
        }
    }
}

// ———————————————————— 筛选 chip（v5：文字 + 选中下划线，无胶囊底） ————————————————————

/**
 * 筛选 chip（v5）：文字 + 选中时下方一条 1.5dp 线，没有底色、没有胶囊。
 *
 * 旧版是「surface-1 灰底胶囊」。一页里 chip 一多（供应商 12 个、模型名 6 个），
 * 屏幕下半部就变成一片灰色药丸，和输入框的灰块、卡片的灰块叠在一起，
 * 整页没有一处是「干净的文字」。
 *
 * 改成下划线之后，它与 Tab、与分组标签用的是同一套语汇：文字就是文字，
 * 状态用一根线表示。间距由调用方 Row 的 spacedBy 控制。
 */
@Composable
fun AkChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 下划线画在文字自己的 drawBehind 里，而不是另起一个 fillMaxWidth 的 Box。
    // 之前那个写法在 Row 里会把芯片拉到整行宽（记忆库的「图谱」就被拉成了通栏），
    // 因为在 Column 的测量阶段，fillMaxWidth 拿到的是父 Row 给的整行约束。
    // 画在文字下面，宽度就永远是文字宽度，跟父容器无关。
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (pressed) Modifier.background(LocalAkColors.surface2)
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (selected) ink
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            maxLines = 1,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .drawBehind {
                    if (selected) {
                        val stroke = 1.5.dp.toPx()
                        val y = size.height - stroke / 2
                        drawLine(ink, Offset(0f, y), Offset(size.width, y), stroke)
                    }
                }
        )
    }
}

// ———————————————————— 输入框（v5：下划线，无填充块） ————————————————————

/**
 * 输入框（v5）：标签在上（10.5sp 字距）、值在下、底部一条线。
 *
 * 旧版是「surfaceVariant 填充块 + 圆角 + 聚焦描边」——每个输入框都是一块灰底，
 * 一页六七个字段就是六七块灰，页面被切成碎片，而且这些灰块和卡片的视觉重量几乎一样，
 * 读者分不清「哪里是表单、哪里是内容」。
 *
 * 下划线把输入框还原成「填空题」：只有一条线，线越淡越像纸上的横格。聚焦时线变
 * primary 并加粗到 1.5dp——那是全页唯一会动的元素，焦点在哪一目了然。
 *
 * 机器内容（URL / 模型名 / Key）传 [machine] = true 走 machineText 13sp。
 */
@Composable
fun AkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    machine: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 40.dp else 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = (if (machine) machineText else MaterialTheme.typography.bodyLarge)
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = singleLine,
                maxLines = maxLines,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = keyboardOptions,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    }
                }
            )
            if (trailing != null) trailing()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) 1.5.dp else 1.dp)
                .background(
                    if (focused) MaterialTheme.colorScheme.primary
                    else LocalAkColors.outlineStrong.copy(alpha = 0.42f)
                )
        )
    }
}

// ———————————————————— 崩溃可见性横幅（真机第四轮 B：用户看不到「最近崩溃」= 白做） ————————————————————

/**
 * 崩溃横幅（真机第四轮 B）。
 *
 * **为什么必须有**：进程被杀时内存日志来不及落盘，崩溃报告 [CrashLogger] 是唯一证据；
 * 但上一轮把证据写进了 `filesDir/crash/`，用户却**根本不知道去哪儿看**——等于白做。
 * 本横幅在 App 启动/主界面出现时检测**未查看**的崩溃文件，顶部报「检测到上次崩溃，点击查看」：
 * ① 点「查看」→ 标记已读（[CrashLogger.markAllSeen]）并 [onOpenLogs] 跳日志页；
 * ② 点关闭 → 同样标记已读，不再打扰。
 *
 * **降级**：崩溃目录不存在 / 读取失败一律静默（[CrashLogger] 内部全程 runCatching），
 * 返回空清单 → 横幅不渲染，**绝不影响启动**。
 *
 * @param onOpenLogs 点击「查看」时跳转日志页的动作（由宿主注入导航）。
 */
@Composable
internal fun AkCrashBanner(
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    // 会话级只扫描一次：崩溃是低频事件，横幅提示一次足矣（避免每次重组都读盘）
    val unseen = remember {
        runCatching { CrashLogger.unseenCrashRecords(ctx) }.getOrDefault(emptyList())
    }
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed || unseen.isEmpty()) return

    val ak = LocalAkColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space1),
        shape = RoundedCornerShape(14.dp),
        color = ak.warningContainer
    ) {
        Row(
            modifier = Modifier.padding(
                start = AgentSpacing.space4,
                end = AgentSpacing.space1,
                top = AgentSpacing.space2,
                bottom = AgentSpacing.space2
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Error,
                contentDescription = null,
                tint = ak.onWarningContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(AgentSpacing.space2))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "检测到上次崩溃",
                    style = MaterialTheme.typography.titleSmall,
                    color = ak.onWarningContainer
                )
                Text(
                    text = "共 ${unseen.size} 次，点击「最近崩溃」查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = ak.onWarningContainer
                )
            }
            AkTextButton(
                text = "查看",
                tint = ak.onWarningContainer,
                onClick = {
                    runCatching { CrashLogger.markAllSeen(ctx) }
                    dismissed = true
                    onOpenLogs()
                }
            )
            AkCircleIconButton(
                icon = Icons.Outlined.Close,
                description = "关闭崩溃横幅",
                tint = ak.onWarningContainer,
                onClick = {
                    runCatching { CrashLogger.markAllSeen(ctx) }
                    dismissed = true
                }
            )
        }
    }
}

// ———————————————————— 模态宿主（弹窗退场动画的公共承载） ————————————————————

/**
 * 模态宿主：把「目标非空 → 入场；目标置空 → 退场 150ms 后才卸载」的三态机收在一处。
 *
 * 没有它，每个二次确认弹窗都要在自己的页面里写一遍「visible + delay + 卸载」，
 * 三处各写一遍必然有一处忘记延时，弹窗就会闪断（技能授权 / 卸载、清空记忆都走这里）。
 *
 * @param target 目标对象：非空即打开，置空即关闭；退场期间内容仍保持上一次的目标不空跑。
 * @param content 弹窗内容；第二参数 visible 供入场 / 退场取不同动画 spec。
 */
@Composable
fun <T> AkModalHost(
    target: T?,
    content: @Composable (item: T, visible: Boolean) -> Unit
) {
    var current by remember { mutableStateOf<T?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target != null) {
            current = target
            visible = true
        } else if (visible) {
            visible = false
            delay(150) // 与 AkMotionExit 等长：退场动画播完再卸载，避免闪断
            current = null
        }
    }
    val item = current
    if (item != null) content(item, visible)
}

// ———————————————————— 二次确认弹窗（不可逆操作共用拦截） ————————————————————

/**
 * 二次确认弹窗：卸载技能 / 清空记忆等不可逆操作的统一拦截层。
 *
 * 视觉与卡片同语言：radius 20dp 白卡 + 0 阴影 + 24dp 内边距；
 * 入场 200ms scale 0.96→1 + fade（[AkMotionDialog]），退场 150ms（[AkMotionExit]）后由宿主卸载。
 * [danger] = true 时确认按钮走 dangerFill——红色只出现在审批与不可逆确认两处（§3.1）。
 * 按钮行 flex 1 等高，不制造视觉偏袒（§6.0）。
 *
 * @param visible 退场时宿主先置 false，延迟 150ms 后再卸载本组件（退场动画由宿主延时承载）。
 */
@Composable
fun AkConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean = true,
    danger: Boolean = false,
    dismissText: String = "取消",
    icon: ImageVector? = null,
    /**
     * 正文与按钮之间的结构化补充（例：安装确认里的域名清单与警告）。
     *
     * 单向文字 `message` 只该放「一句话结论」；把清单拼进 message 会变成一坨换行文本，
     * 既不能逐行强调，也没法给危险项单独上色。
     */
    details: (@Composable () -> Unit)? = null
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 宽 = min(344dp, 100vw − 48dp)：外层 padding 24dp 给出后者，widthIn 封顶 344dp
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
                    if (icon != null) {
                        // 图标底座 36dp：危险用 danger-container，其余用 primary-container
                        val iconBg = if (danger) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                        val iconTint = if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(iconBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.height(AgentSpacing.space4))
                    }
                    // 标题陈述事实：不加大不加粗不变红（§6.4 同款措辞纪律）
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (details != null) {
                        Spacer(Modifier.height(AgentSpacing.space3))
                        details()
                    }
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = dismissText,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        if (danger) {
                            AkDangerButton(
                                text = confirmText,
                                onClick = onConfirm,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            AkPrimaryButton(
                                text = confirmText,
                                onClick = onConfirm,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ———————————————————— 空态（§6.9：居中排版，hero 卡废止） ————————————————————

/**
 * 统一空态：上下 padding 32dp，56dp 圆图标底（surface-1 + 28dp 语义色线图标）+
 * 标题 titleLarge 18sp w600 + 描述 bodySmall 居中 maxW 280dp + 可选主按钮（48dp 自动宽）。
 */
@Composable
fun AkEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // v5：去掉 56dp 的圆形浅灰底座。空态本来就是「这一屏没有内容」，
        // 再给它一个圆形容器，等于用一块图形去填补空白——那是模板的做法。
        // 图标降到 30dp 当一枚记号，标题用 17sp：整块读起来是一句话，不是一张卡片。
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint.copy(alpha = 0.9f),
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(AgentSpacing.space4))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 24.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                lineHeight = 21.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(300.dp)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AgentSpacing.space6))
            AkPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * W-66：能记住位置的滚动状态。
 *
 * `rememberScrollState()` 只是 `remember`——页面一离开组合（这个 App 是手写 `when (dest)`
 * 切页，不是 NavHost），状态就没了，返回时停在顶部。用户的原话是「我点关于与介绍，
 * 一退出就自动跳到最前面……每次都得重新翻」。
 *
 * 换成 `rememberSaveable` + `ScrollState.Saver`，配合 AppScaffold 里的
 * `rememberSaveableStateHolder()`（按 route 隔离），返回时能落回原来的位置。
 * 弹窗内部的小滚动也用它，无害——只是多存一个 Int。
 */
@Composable
internal fun rememberPersistentScrollState(): ScrollState =
    rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
