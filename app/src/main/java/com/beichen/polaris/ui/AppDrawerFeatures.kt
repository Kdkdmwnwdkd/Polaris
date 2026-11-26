package com.beichen.polaris.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 抽屉「功能」折叠组：低频但完整的页面（2026-11-26）。
 *
 * 起因是一条结构问题：抽屉曾因「拥挤」从 8 项砍到 4 项，砍掉的入口全部下沉进设置页，
 * 于是设置页长出了「工具」组——模型配置 / 容器 / 编辑器 / 提示词库 / 收藏 / MCP /
 * 创作工坊 / 构建，**没有一项是设置**，全是功能页。设置页于是在替导航打工，
 * 25 个页面里 20 个只能从它索引过去。
 *
 * 这里把功能页收回抽屉，但不回到拥挤：默认收起，只占一行分组标题；
 * 展开才铺开 7 项。模型配置不在其中——它就是 [PrimaryGroup] 里的「模型」，
 * 一个页面挂两个名字是纯冗余。
 */
private val FeatureGroup = listOf(
    AppDest.CONTAINER, AppDest.EDITOR, AppDest.PROMPTS, AppDest.BOOKMARKS,
    AppDest.MCP, AppDest.MEDIA, AppDest.BUILD
)

/**
 * 抽屉里「功能」这一整块：一行可展开的标题 + 展开后的 7 个条目。
 *
 * 展开状态故意用 `remember` 留在本 composable 内，而不是提到 [AppDrawer] 上层：
 * 抽屉关闭时这块离开组合，状态随之丢弃，下次拉开必然是收起态。
 * 换成 rememberSaveable 或提到上层，用户展开过一次之后每次拉开抽屉都要先看到
 * 7 行功能页，最近对话被顶到第三屏——那正是当初「拥挤」的形态。
 *
 * 单独成文件而不是留在 AppScaffold.kt：那个文件有冻结行数上限（只减不增），
 * 而抽屉条目、分组标题与这一块的耦合只靠 [DrawerItem]，搬出来不需要改调用方。
 */
@Composable
internal fun DrawerFeatureSection(
    dest: AppDest,
    onSelect: (AppDest) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(AgentSpacing.space2))
    DrawerSectionToggle(
        text = "功能",
        count = FeatureGroup.size,
        expanded = expanded,
        onClick = { expanded = !expanded }
    )
    if (expanded) {
        FeatureGroup.forEach { item ->
            DrawerItem(
                dest = item,
                isSelected = dest == item,
                talkBack = item.label,
                onClick = { onSelect(item) }
            )
        }
    }
}

/**
 * 抽屉里可展开的分组标题（当前只有「功能」用它）。
 *
 * 字与 [DrawerGroupTitle] 同一套（11sp + 1.2sp 字距），所以它读起来仍是目录小标题；
 * 但整行可点，右侧多给「7 ›」——数字说明里面有几项，箭头说明能展开。
 * 两件事都得有：只写「功能」的话，收起态看起来就是一行没有下文的标题，
 * 用户不知道这里藏了东西。
 *
 * 触控高度按 48dp 兜底，与 [DrawerItem] 的 52dp 不同高——它是标题不是条目，
 * 但手指落点不能比条目小太多。
 */
@Composable
private fun DrawerSectionToggle(
    text: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space4,
                top = AgentSpacing.space1,
                bottom = AgentSpacing.space1
            )
            .semantics {
                contentDescription = if (expanded) "$text，$count 项，已展开" else "$text，$count 项，已收起"
            },
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
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}
