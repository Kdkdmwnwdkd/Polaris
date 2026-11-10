package com.beichen.polaris.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.skill.SkillDeclaration
import com.beichen.polaris.skill.SkillDeclarations
import com.beichen.polaris.ui.contract.SkillLibraryContract

/**
 * 技能中心页（视觉规范 §3.6 信任色板 + UX v1 §1.5）。
 *
 * 从旧自动化页拆出：技能是「装了什么、允许多大权」的资产管理页面，
 * 与导入对话框、授权 / 卸载二次确认弹窗自持一页。
 *
 * 信任徽章只表达**来源**（内置 / 导入 / 沙箱）且永不变色；脚本授权状态另起一行展示——
 * 这是 §3.6 的反误解设计：授权不会把「导入」洗成「内置」。
 * 来源判据用 [SkillUiInfo.isImported]（是否落在导入目录），不用运行时 trust——
 * 运行时 trust 在授权后会翻成 packaged，直接拿来上徽章会骗人。
 *
 * @param vm 对话 ViewModel（技能快照 / 导入状态 / 导入卸载授权动作全部走它的公开接口）。
 */
@Composable
fun SkillsScreen(
    vm: SkillLibraryContract,
    /** W-48：跳到插件市场；默认空实现，方便 harness/预览单独渲染技能页。 */
    onOpenMarket: () -> Unit = {}
) {
    val skillInfos by vm.skillInfos.collectAsStateWithLifecycle()
    val importStatus by vm.importStatus.collectAsStateWithLifecycle()

    // 三个模态的目标态：非空即打开，置空即关闭（退场动画由 AkModalHost 承载）
    var importTarget by remember { mutableStateOf<Unit?>(null) }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var authTarget by remember { mutableStateOf<SkillUiInfo?>(null) }
    var uninstallTarget by remember { mutableStateOf<SkillUiInfo?>(null) }

    // 从本机选择 .zip（SAF）：只把 Uri 交给 ViewModel，由其切 IO 线程读字节
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.importFromUri(it) } }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.SKILLS.title,
            actionIcon = Icons.Outlined.Add,
            actionDescription = "导入技能",
            onAction = { importTarget = Unit }
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
            // —— W-48：插件市场入口。v5 彻底去掉那张白卡：入口是「去别处」，
            // 一张带阴影的白卡会把它读成「本页的第一块内容」。一行 + 箭头才是入口。
            AkLine(
                title = "插件市场",
                meta = "浏览可安装的插件，支持版本更新；安装链路与本页一致",
                onClick = onOpenMarket,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            // —— 安全策略：先讲规则再列技能，用户才知道徽章与按钮在说什么 ——
            AkSectionCard(
                title = "安全策略",
                icon = Icons.Outlined.Security,
                description = "导入的技能一律降权：默认禁止执行脚本，能力上限收紧到「特权」，" +
                    "只能调用白名单内的低危工具。授权脚本执行后会升到与内置技能同级，" +
                    "但随时可以撤销；卸载则会一并撤销授权。"
            )

            // —— 导入 / 授权 / 卸载的操作回执（去掉 ViewModel 文案里的表情前缀，改用状态点）——
            val status = importStatus
            if (status != null) {
                ImportStatusStrip(status)
            }

            // —— 技能列表 / 空态 ——
            if (skillInfos.isEmpty()) {
                AkEmptyState(
                    icon = Icons.Outlined.Extension,
                    title = "暂无已安装技能",
                    description = "导入一个 zip 技能，或先载入内置示例看看效果。导入来源默认禁止脚本执行。",
                    actionLabel = "载入示例技能",
                    onAction = { vm.importDemo() }
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)) {
                    skillInfos.forEach { skill ->
                        SkillCard(
                            skill = skill,
                            onAuthorize = { authTarget = skill },
                            onRevoke = { vm.setScriptAuthorization(skill.id, false) },
                            onUninstall = { uninstallTarget = skill }
                        )
                    }
                }
            }
        }
    }

    // —— 导入对话框：URL / 本机文件 / 内置示例三条路径 ——
    AkModalHost(target = importTarget) { _, dialogVisible ->
        ImportSkillDialog(
            url = importUrl,
            visible = dialogVisible,
            onUrlChange = { importUrl = it },
            onDismiss = { importTarget = null },
            onImportUrl = {
                vm.importFromUrl(importUrl)
                importTarget = null
            },
            onPickFile = {
                importLauncher.launch("application/zip")
                importTarget = null
            },
            onImportDemo = {
                vm.importDemo()
                importTarget = null
            }
        )
    }

    // —— 授权脚本执行：不可逆风险，二次确认 ——
    // 确认框里必须列出联网域名：授权脚本正是 `net_hosts` 生效的那一刻，
    // 在此之前那些域名对用户只是 skill.json 里的一串字符。
    AkModalHost(target = authTarget) { skill, dialogVisible ->
        AkConfirmDialog(
            title = "授权脚本执行",
            message = "「${skill.name}」来自导入来源，执行脚本可能调用高危工具操控你的手机。" +
                "确认授权后它将获得与内置技能同等的脚本执行权限，之后仍可随时撤销。",
            confirmText = "确认授权",
            icon = Icons.Outlined.Security,
            danger = false,
            visible = dialogVisible,
            onConfirm = {
                vm.setScriptAuthorization(skill.id, true)
                authTarget = null
            },
            onDismiss = { authTarget = null },
            details = { SkillDeclarationDetails(skill.declaration) }
        )
    }

    // —— 卸载：删目录且一并撤销授权，二次确认用 danger ——
    AkModalHost(target = uninstallTarget) { skill, dialogVisible ->
        AkConfirmDialog(
            title = "卸载技能",
            message = "确认卸载「${skill.name}」？该技能的目录会被删除，脚本授权一并撤销，且无法恢复。",
            confirmText = "确认卸载",
            icon = Icons.Outlined.Delete,
            danger = true,
            visible = dialogVisible,
            onConfirm = {
                vm.uninstallSkill(skill.id)
                uninstallTarget = null
            },
            onDismiss = { uninstallTarget = null }
        )
    }
}

/**
 * 单个技能卡（§3.6）：名称 + 信任徽章（来源，永不变色）+ 摘要徽章 + 工具放行清单 + 授权 / 卸载行。
 *
 * 工具清单用「状态点 + 文案」双编码：放行 = success 点，拦截 = 中性点（拦截是策略事实不是错误，
 * 红色只留给审批与 ERROR，§3.1）。
 */
@Composable
private fun SkillCard(
    skill: SkillUiInfo,
    onAuthorize: () -> Unit,
    onRevoke: () -> Unit,
    onUninstall: () -> Unit
) {
    val ak = LocalAkColors
    AkGroupCard {
        Column(
            Modifier.padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
        ) {
            // 头部：名称 + 来源徽章（导入来源授权后也不会变成「内置」，§3.6 反误解设计）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                TrustBadge(trust = if (skill.isImported) "imported" else "packaged")
            }
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // v5：三行摘要（两个灰药丸 + 一行「能力上限」）压成一行。
            // 它们本来就是同一件事的三个属性，拆成三个控件只是让页面显得"有很多信息"。
            Text(
                text = buildString {
                    append(if (skill.isScript) "脚本" else "提示词")
                    append("  ·  ")
                    append(if (skill.allowScript) "允许执行脚本" else "禁止执行脚本")
                    append("  ·  上限 ")
                    append(capabilityLabel(skill.maxCapability))
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 声明行：这个技能**说**自己要联网到哪里。
            // 与下面的工具清单配对读——那里是「实际能做成什么」（能力门放行结果），
            // 这里是「它想做什么」（manifest 原话）。只有一处，两边会各自误导。
            Text(
                text = SkillDeclarations.hostLine(skill.declaration),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 0.1.sp
                ),
                color = if (skill.declaration.invalidHosts.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            // 工具清单：白名单 + 全局能力门的最终放行结果
            if (skill.tools.isNotEmpty()) {
                AkDivider()
                Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
                    skill.tools.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AkStatusDot(if (t.allowed) ak.success else ak.outlineStrong, 6.dp)
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = "${t.name} · ${capabilityLabel(t.capability)}",
                                style = machineText,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = if (t.allowed) "放行" else "拦截",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 授权 / 卸载：只对导入来源展示（内置技能无需授权，也没有卸载入口）
            if (skill.isImported) {
                AkDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (skill.allowScript) "已授权脚本执行" else "脚本执行已禁止",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (skill.allowScript) {
                        AkTextButton(text = "撤销授权", onClick = onRevoke)
                    } else {
                        AkTextButton(text = "授权脚本", onClick = onAuthorize)
                    }
                    AkTextButton(
                        text = "卸载",
                        onClick = onUninstall,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 授权 / 安装确认里的「声明」区：域名逐条列出 + 会失效的写法 + 声明工具。
 *
 * 为什么逐条换行而不是逗号连成一句：域名是用户要拿来核对的（「这个技能凭什么要连
 * 我的对象存储？」），连成一句就没法逐条比对，只能整体扫一眼然后点确认。
 */
@Composable
private fun SkillDeclarationDetails(d: SkillDeclaration) {
    Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
        Text(
            text = SkillDeclarations.hostLine(d),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (d.hosts.isNotEmpty()) {
            Text(
                text = d.hosts.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (d.tools.isNotEmpty()) {
            Text(
                text = "声明工具：" + d.tools.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SkillDeclarations.warnings(d).forEach { w ->
            Text(
                text = w,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (d.needsScript && d.hosts.isNotEmpty()) {
            Text(
                text = "授权后脚本只能访问上面列出的域名，其它地址一律被拒；主对话里的联网仍会逐次询问。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 导入技能对话框：URL 输入 + 三条导入路径（URL / 本机文件 / 内置示例）。
 * 入场 200ms scale + fade（[AkMotionDialog]），退场由 [AkModalHost] 延时卸载。
 */
@Composable
private fun ImportSkillDialog(
    url: String,
    visible: Boolean,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImportUrl: () -> Unit,
    onPickFile: () -> Unit,
    onImportDemo: () -> Unit
) {
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = AkMotionDialog)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = AkMotionDialog)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                ) {
                    Text(
                        text = "导入技能",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "支持从 URL 下载 zip、从本机选择 .zip 文件，或载入内置示例。" +
                            "导入来源一律降权：默认禁止脚本执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AkTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = "Zip 下载地址",
                        placeholder = "https://…",
                        machine = true
                    )
                    AkPrimaryButton(
                        text = "从 URL 导入",
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = url.isNotBlank(),
                        onClick = onImportUrl
                    )
                    AkOutlinedButton(
                        text = "从本机选择文件",
                        icon = Icons.Outlined.Folder,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onPickFile
                    )
                    AkOutlinedButton(
                        text = "载入示例技能",
                        icon = Icons.Outlined.Extension,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onImportDemo
                    )
                    AkTextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

/**
 * 导入状态条：把 ViewModel 回填文案里的表情前缀（✅ / ⚠️）剥掉，改用状态点双编码。
 * 纯中文无 emoji 是 UI 层铁律；core 文案不动，只在外壳上做翻译。
 */
@Composable
private fun ImportStatusStrip(status: String) {
    val ak = LocalAkColors
    val (dotColor, text) = when {
        status.startsWith("✅") -> ak.success to status.trimStart('✅', ' ')
        status.startsWith("⚠") -> ak.warning to status.trimStart('⚠', '\uFE0F', ' ')
        else -> MaterialTheme.colorScheme.onSurfaceVariant to status
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ak.surface1)
            .padding(
                horizontal = AgentSpacing.space4,
                vertical = AgentSpacing.space3
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AkStatusDot(dotColor, 6.dp)
        Spacer(Modifier.width(AgentSpacing.space2))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 能力上限英文枚举名 → 中文展示名（机器 token 的人话翻译，规范 §4.1 纯中文）。 */
private fun capabilityLabel(capability: String): String = when (capability.uppercase()) {
    "SAFE" -> "只读"
    "PRIVILEGED" -> "特权"
    "DANGEROUS" -> "高危"
    else -> capability
}
