package com.beichen.polaris.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 编辑式排版原语（v5 骨架）。
 *
 * 之前每一页都是「卡片套卡片」：设置页 9 张卡、模型页 12 张卡、每张卡顶着一个
 * 图标底座 + 18sp 标题 + 一句解释。那是**后台管理界面**的语言——把每块内容都装进
 * 容器，用容器的边界代替层级。容器一多，页面就只剩「块」，读不出主次。
 *
 * 这一版把容器整个拿掉，秩序改由三样东西建立：
 *
 * 1. **字号对比**——衬线大标题（28sp）与正文（15sp）之间隔着一个量级，
 *    眼睛先落在标题上，不需要卡片边框告诉它「从这里开始读」；
 * 2. **细线**——行与行之间一条 1dp 线，是分栏而不是分块；
 * 3. **留白**——分组之间 26dp，组内 0dp。间距本身就是分组信号。
 *
 * 关于标题字体：最初想用衬线（书刊语汇，不参与 Material 体系）。在模拟器上验证后
 * 放弃——Android 的 `FontFamily.Serif` 只映射到 `NotoSerif-Regular.ttf`，那个文件
 * **不含 CJK**，中文会静默回退到黑体，等于什么都没做。国产 ROM 里更不会有中文衬线。
 *
 * 改用「大字号 + 细字重 + 负字距」：30sp / 400 / -0.4sp。中文黑体在 30sp 下笔画
 * 之间的空隙足够，细字重会让它看起来像排出来的字而不是加粗的 UI 文字；负字距把
 * 默认的字间空白收紧，这是所有现代大标题的共同做法，且不依赖任何字体文件。
 *
 * 所有颜色仍走现有语义 token（onSurface / onSurfaceVariant / outline），
 * 没有新增色值——这一版只改结构。
 */

private val titlePlatformStyle = PlatformTextStyle(includeFontPadding = true)

/**
 * 页面大标题：30sp / 400 / -0.4sp。
 *
 * 全 App 每页只有一处，独占一条视觉线——它存在的意义不是「把页名说一遍」，
 * 而是给这一屏一个明确的起点。字号必须比正文（15sp）大一个量级，
 * 否则它就只是「一个稍大的字」。
 */
internal val displayTitleStyle: TextStyle = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 30.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.4).sp,
    platformStyle = titlePlatformStyle
)

/** 对话页空态的主标题再大一档：那一屏除了标题没有别的内容，可以放开。 */
internal val displayHeroStyle: TextStyle = displayTitleStyle.copy(
    fontSize = 34.sp,
    lineHeight = 46.sp
)

/**
 * 页面标题块：衬线大标题 + 一行说明。
 *
 * @param subtitle 副标题——写「现在的状态」，不写「这个页面是干什么的」。
 *   用户点进来已经知道自己在哪一页，再解释一遍是浪费第一屏。
 */
@Composable
fun AkDisplayTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: TextStyle = displayTitleStyle
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    letterSpacing = 0.2.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 分组标签：11sp 小字 + 1.2sp 字距。
 *
 * 字距是关键——中文正文从不加字距，加了就立刻从「内容」变成「标签」，
 * 读者不需要任何底色也知道这一行是路标而不是正文。
 *
 * @param gap 与上一组之间的间距。默认 26dp：分组靠留白划分，不靠线。
 */
@Composable
fun AkSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    gap: Int = 26,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = gap.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 行底细线：1dp outline。比卡片阴影轻，只做分栏。 */
@Composable
fun AkHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * 行：标题（+ 副文案）占左，值或控件占右，行底一条细线。
 *
 * 这是 v5 的主结构件，替代 [AkListItem] 在「浏览型列表」里的大多数用法。
 * 和 AkListItem 的区别：没有图标底座、没有圆角按压块、行高 50dp 而不是 56。
 * 编辑式排版里一列重复的圆角色块会形成「视觉噪音列」，而细线不会。
 *
 * @param value 右侧只读值（12.5sp，弱化）。有 [trailing] 时通常不传。
 * @param dense 单行 44dp；默认 50dp 留出两行空间。
 */
@Composable
fun AkLine(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    dense: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val rowModifier = modifier.fillMaxWidth()
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (dense) 44.dp else 50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!meta.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            letterSpacing = 0.1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (value != null) {
                Spacer(Modifier.width(AgentSpacing.space3))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(AgentSpacing.space3))
                trailing()
            }
        }
    }
    Column(rowModifier) {
        if (onClick != null) {
            AkPressSurface(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                // 行是通栏的：按压高亮也通栏，圆角给 4dp 让它看起来像「被划了一下」，
                // 而不是弹出一个色块。14dp 的圆角在通栏宽度上会像一张卡。
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) { body() }
        } else {
            body()
        }
        if (showDivider) AkHairline()
    }
}

/**
 * 行动行：整页唯一的「主要行动」。
 *
 * 为什么不放实心按钮：一屏里出现一块深色填充的矩形，眼睛会先被它吸走，
 * 而这里真正的主角是「现在是什么状态」。行动是要在读完状态之后才做的第二件事，
 * 所以给它 primary 墨色 + 右箭头 + 一条同色短线——足够亮，但不抢版面。
 *
 * 关于那条线：第一版画的是通栏细线，结果它和分组分隔线长得一模一样——读者会以为
 * 「这里分节了」而不是「这里可以点」。改成 28dp 的短横线（编辑式里的 rule），
 * 它标记的是一句话，不是一条边界。
 *
 * @param note 行动下方的一句说明（12sp 中性灰），可为空。
 */
@Composable
fun AkActionLine(
    text: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    onClick: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        AkPressSurface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        color = ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(AgentSpacing.space2))
                    Icon(
                        imageVector = Icons.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .width(28.dp)
                        .height(1.5.dp)
                        .background(ink.copy(alpha = 0.75f))
                )
            }
        }
        if (!note.isNullOrBlank()) {
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 字段行：标签在上（11sp 标签体）、值在下（15sp 或等宽）、下划线。
 *
 * 用于表单——模型名、Base URL、超时。刻意不做输入框外观：一个描边方框会立刻
 * 把页面拉回「设置表单」的观感，而下划线更像填空题。
 *
 * @param mono 值是机器文本（URL / Key / 模型名）时传 true，走等宽字体。
 */
@Composable
fun AkFieldLine(
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.2.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) { content() }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LocalAkColors.outlineStrong.copy(alpha = 0.45f))
        )
    }
}

/**
 * 引线块：左侧 2dp 竖线 + 缩进内容。
 *
 * 用于工具调用、引用、代码片段这类「附属材料」。方案里它是唯一还带边框的东西，
 * 因为它确实需要和正文分开——而卡片不需要。
 */
@Composable
fun AkRuleBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = AgentSpacing.space2)
    ) {
        Box(
            Modifier
                .width(2.dp)
                .heightIn(min = 24.dp)
                .align(Alignment.Top)
                .background(LocalAkColors.outlineStrong.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(AgentSpacing.space3))
        Box(Modifier.weight(1f)) { content() }
    }
}
