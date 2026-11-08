package com.beichen.polaris.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.PromptDeleteResult
import com.beichen.polaris.core.PromptFillResult
import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.core.PromptUpsertResult
import com.beichen.polaris.core.fillPromptVariables
import com.beichen.polaris.ui.contract.PromptLibraryContract
import java.util.UUID

/**
 * 功能五（P1-7）：提示词库管理页（一级页面，设置页「提示词库」进入）。
 *
 * 分内置 / 自定义两组：内置只读（可另存为），自定义可增删改。
 * 页面结构：顶栏（标题 + 右上「＋ 新建」）→ 内置组（只读，行尾「另存为」）
 * → 自定义组（行尾编辑/删除）→ 编辑对话框 → 删除二次确认。
 */
@Composable
fun PromptLibraryScreen(vm: PromptLibraryContract) {
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<PromptItem?>(null) }
    var isNew by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by rememberSaveable { mutableStateOf("") }

    val builtins = prompts.filter { it.builtin }
    val customs = prompts.filterNot { it.builtin }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = "提示词库",
            actionIcon = Icons.Outlined.Add,
            actionDescription = "新建提示词",
            onAction = {
                editing = PromptItem(
                    id = "p-${System.currentTimeMillis()}-${UUID.randomUUID().hashCode().toString(16).padStart(6, '0')}",
                    title = "",
                    content = ""
                )
                isNew = true
            }
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
            AkSectionCard(
                title = "内置提示词",
                icon = Icons.Outlined.AutoAwesome,
                description = "随 App 内置，不可修改；点「另存为」复制成自定义后可自由编辑。"
            ) {
                if (builtins.isEmpty()) {
                    Text(
                        "内置提示词缺失（数据被清过？重启 App 会自动补种）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AgentSpacing.space4)
                    )
                }
                builtins.forEachIndexed { index, item ->
                    if (index > 0) AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    PromptRow(
                        item = item,
                        onDuplicate = { vm.duplicatePromptAsCustom(item.id) },
                        showDuplicate = true
                    )
                }
            }

            AkSectionCard(
                title = "我的提示词",
                icon = Icons.Outlined.Edit,
                description = "输入框旁的闪电按钮点选即用；共 ${customs.size} 条。"
            ) {
                if (customs.isEmpty()) {
                    Text(
                        "还没有自定义提示词。点右上「＋」新建，或在内置项上「另存为」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AgentSpacing.space4)
                    )
                }
                customs.forEachIndexed { index, item ->
                    if (index > 0) AkDivider(Modifier.padding(start = AgentSpacing.space4))
                    PromptRow(
                        item = item,
                        onEdit = {
                            editing = item
                            isNew = false
                        },
                        onDelete = { confirmDeleteId = item.id }
                    )
                }
            }
        }
    }

    // 编辑/新建对话框
    editing?.let { draft ->
        PromptEditorDialog(
            initial = draft,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { title, content, tags ->
                val result = vm.savePrompt(
                    draft.copy(title = title, content = content, tags = tags)
                )
                if (result is PromptUpsertResult.Ok) {
                    editing = null
                    toast = if (isNew) "已创建「$title」" else "已保存「$title」"
                } else {
                    toast = (result as? PromptUpsertResult.Rejected)?.reason ?: "保存失败"
                }
            }
        )
    }

    // 删除二次确认（danger：不可逆）
    confirmDeleteId?.let { id ->
        val item = prompts.firstOrNull { it.id == id }
        AkConfirmDialog(
            title = "删除提示词",
            message = "确定删除「${item?.title ?: "该提示词"}」吗？删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmDeleteId = null
                when (val r = vm.deletePrompt(id)) {
                    is PromptDeleteResult.Ok -> toast = "已删除"
                    is PromptDeleteResult.Protected -> toast = "内置提示词不可删除"
                    is PromptDeleteResult.NotFound -> Unit
                }
            },
            onDismiss = { confirmDeleteId = null }
        )
    }

    // 轻量反馈：本项目无 Snackbar 基建，用 MemoryScreen 同款行内提示（2 秒自消由调用方不管，
    // 这里简单展示在删除确认关闭后即可——持久化操作结果已体现在列表变化上）
    if (toast.isNotBlank()) {
        PromptToast(text = toast, onDismiss = { toast = "" })
    }
}

/**
 * 输入栏「闪电按钮」唤起的提示词选择器（P1-7 的核心交互）。
 *
 * 与 [PromptLibraryScreen] 的分工：选择器只做「点选 → 填入输入框」一件事，
 * 不做管理（管理走设置页全功能页）——输入栏是高频区，弹窗要轻。
 * 内置在前带「内置」小标；自定义在后。多变体/空态都有兜底文案。
 */
@Composable
fun PromptPickerDialog(
    prompts: List<PromptItem>,
    onDismiss: () -> Unit,
    onPick: (PromptItem) -> Unit,
    onManage: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgentSpacing.space5),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(AgentSpacing.space4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "快捷指令",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    AkTextButton(text = "管理", onClick = onManage)
                }
                Spacer(Modifier.height(AgentSpacing.space2))
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberPersistentScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
                ) {
                    if (prompts.isEmpty()) {
                        Text(
                            "提示词库是空的，点「管理」新建一条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = AgentSpacing.space4)
                        )
                    }
                    prompts.forEach { item ->
                        PromptRow(
                            item = item,
                            onClick = { onPick(item) },
                            showBuiltinBadge = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * W-RT4：提示词填槽弹窗——选中带 `{{变量}}` 的提示词时，先按名收集变量、
 * 逐个填值，确认后把成品交给 [onConfirm]（插入由调用方决定）。
 *
 * 交互规则（与 [fillPromptVariables] 的纯函数语义对齐）：
 *  - 任一变量为空白时确认键禁用——不做半填，半成品不进输入框；
 *  - 变量多于 4 个时弹窗内可滚动（模板作者的自由度不设上限）。
 *
 * 变量列表由调用方传进来而不是在这里现扫：ChatScreen 那边要用它判断
 * 「有没有变量、要不要弹这个框」，同一份判定不该在两个地方各算一次。
 */
@Composable
fun PromptFillDialog(
    item: PromptItem,
    variables: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var values by remember(item.id) { mutableStateOf(variables.associateWith { "" }) }
    val allFilled = variables.isNotEmpty() && variables.all { values[it]?.isNotBlank() == true }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgentSpacing.space5),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(AgentSpacing.space4)) {
                Text(
                    text = "填写变量：${item.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    variables.forEach { name ->
                        AkFieldLabel(name)
                        AkTextField(
                            value = values[name].orEmpty(),
                            onValueChange = { v -> values = values + (name to v) },
                            placeholder = "{{ $name }}",
                            singleLine = true
                        )
                        Spacer(Modifier.height(AgentSpacing.space2))
                    }
                }
                Spacer(Modifier.height(AgentSpacing.space2))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AkTextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(AgentSpacing.space2))
                    AkPrimaryButton(
                        text = "填入",
                        enabled = allFilled,
                        onClick = {
                            val result = fillPromptVariables(item.content, values)
                            if (result is PromptFillResult.Ok) onConfirm(result.content)
                        }
                    )
                }
            }
        }
    }
}

/** 提示词行（管理页与选择器共用）：标题 + 内容预览 + 标签 + 行尾动作。 */
@Composable
private fun PromptRow(
    item: PromptItem,
    onClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    showBuiltinBadge: Boolean = false,
    showDuplicate: Boolean = false
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.medium) else Modifier)
    Column(
        rowModifier.padding(
            horizontal = AgentSpacing.space3,
            vertical = AgentSpacing.space2
        )
    ) {
        if (onClick != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
            ) { PromptRowBody(item, showBuiltinBadge) }
        } else {
            PromptRowBody(item, showBuiltinBadge)
        }
        if (onEdit != null || onDelete != null || onDuplicate != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (onDuplicate != null) {
                    AkTextButton(
                        text = "另存为",
                        icon = Icons.Outlined.ContentCopy,
                        onClick = onDuplicate
                    )
                }
                if (onEdit != null) {
                    AkTextButton(text = "编辑", icon = Icons.Outlined.Edit, onClick = onEdit)
                }
                if (onDelete != null) {
                    Spacer(Modifier.width(AgentSpacing.space1))
                    AkTextButton(
                        text = "删除",
                        icon = Icons.Outlined.Delete,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptRowBody(item: PromptItem, showBuiltinBadge: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (showBuiltinBadge && item.builtin) {
                Spacer(Modifier.width(AgentSpacing.space2))
                AkChip(text = "内置", selected = false, onClick = {})
            }
        }
        Text(
            item.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.tags.isNotEmpty()) {
            Text(
                item.tags.joinToString(" · ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 新建/编辑对话框：名称 + 内容（多行）+ 标签（逗号分隔）。
 * 与 [PromptLibraryScreen] 的保存回调返回三元组，UI 层只负责收集文本。
 */
@Composable
private fun PromptEditorDialog(
    initial: PromptItem,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, tags: List<String>) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var tags by remember { mutableStateOf(initial.tags.joinToString("，")) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AgentSpacing.space5),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.padding(AgentSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
            ) {
                Text(
                    if (isNew) "新建提示词" else "编辑提示词",
                    style = MaterialTheme.typography.titleMedium
                )
                AkFieldLabel("名称")
                AkTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "如：审查改动",
                    singleLine = true
                )
                AkFieldLabel("内容")
                AkTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = "提示词正文，点选后填入输入框",
                    singleLine = false,
                    minLines = 3
                )
                AkFieldLabel("标签（可选，逗号分隔）")
                AkTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    placeholder = "如：开发，理解",
                    singleLine = true
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AkTextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(AgentSpacing.space2))
                    AkPrimaryButton(
                        text = "保存",
                        onClick = {
                            onSave(
                                title,
                                content,
                                tags.split('，', ',').map { it.trim() }.filter { it.isNotBlank() }
                            )
                        }
                    )
                }
            }
        }
    }
}

/** 一次性提示（结果反馈）：3 行文案 + 关闭钮，不做自动计时器（避免协程泄漏进 UI 层）。 */
@Composable
private fun PromptToast(text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                Modifier.padding(
                    horizontal = AgentSpacing.space4,
                    vertical = AgentSpacing.space3
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(AgentSpacing.space3))
                AkTextButton(text = "好", onClick = onDismiss)
            }
        }
    }
}

/* ------------------------------ 小组件 ------------------------------ */

@Composable
private fun AkFieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 项目内输入框（BasicTextField + surface-var 底，形态学 ChatInputBar 的输入框）。 */
@Composable
private fun AkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    minLines: Int = 1
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .padding(AgentSpacing.space1)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AgentSpacing.space3,
                        vertical = AgentSpacing.space2
                    ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}
