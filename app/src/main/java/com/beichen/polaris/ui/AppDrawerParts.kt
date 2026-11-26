package com.beichen.polaris.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 抽屉的原子件：分组标题与菜单项。
 *
 * 从 AppScaffold.kt 搬出来（2026-11-26）：那个文件有冻结行数上限（只减不增），
 * 而「功能」折叠组需要新的条目形态，原地加不进去。搬的只是位置——
 * 三个函数的实现逐字未改，只把 `private` 放宽成 `internal` 供同包使用。
 *
 * 与 [DrawerFeatureSection] 的关系：那边管「一组低频页面收起来」，
 * 这边管「一行长什么样」。条目怎么排、点哪个，全在那边。
 */

/**
 * 抽屉分组标题：单行中文 13sp w500 on-surface-var，无英文微标签（§6.5.2）。
 *
 * 右侧可挂一个行内动作（目前只有「最近对话」的「全部 N」入口）。挂在标题行而不是
 * 列表尾部，是因为 720x1280 上抽屉可视高度只有 ~451dp：五个主导航项就吃掉 260dp，
 * 会话列表里最后那一行永远压在固定底栏底下——想进「全部对话」得先盲滚一段。
 */
@Composable
internal fun DrawerGroupTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
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
internal fun DrawerItem(
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
