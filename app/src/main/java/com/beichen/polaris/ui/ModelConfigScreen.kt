package com.beichen.polaris.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.beichen.polaris.ui.contract.ModelConfigContract
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import com.beichen.polaris.core.llm.LlmProvider
import com.beichen.polaris.core.llm.ProviderCatalog
import com.beichen.polaris.core.llm.RecoveryAction
import com.beichen.polaris.core.llm.ProviderGroup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.ConfigSanitizer
import com.beichen.polaris.core.DEFAULT_SYSTEM_PROMPT
import com.beichen.polaris.core.ModelProfile

/**
 * 模型配置页（视觉规范 §6.8 + §6.5.3 页面内 Tab）。
 *
 * 一级页面：顶栏「模型配置」无右上动作（保存有毁损性，不进顶栏防误触，§6.5.4）；
 * 内容区顶部为页面内 Tab「模型 / 提示词」——内容分区，非 App 导航。
 *
 * 功能二（多模型配置，录屏反馈「别人的模型可以搞好几个」）：顶栏下方是配置档案切换条，
 * 页面语义变为「编辑当前选中的配置」；保存 / 测连通 / 提示词全部作用于当前档案。
 *
 * @param onBack 「取消 / 保存」后回对话页（抽屉路由由宿主持有）。
 */
@Composable
fun ModelConfigScreen(vm: ModelConfigContract, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    // 以已落盘配置为基准：config 变化（保存成功 / 切换档案）即重置编辑态，避免二次进入残留旧值
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var providerId by remember(config) { mutableStateOf(config.providerId) }
    // W-29：高级参数（自定义请求头/请求体）——默认收起，避免普通用户被吓到
    var headersText by remember(config) {
        mutableStateOf(
            config.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }
    var bodyText by remember(config) { mutableStateOf(config.customBody) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    // A 批：只要输入变了，上一次「测试连接成功」就失效——不能让用户保存一个没测过的配置。
    LaunchedEffect(baseUrl, apiKey, model, providerId) {
        vm.clearTestResult()
    }
    // W-29：余额查询状态
    val scope = rememberCoroutineScope()
    var balanceLoading by remember { mutableStateOf(false) }
    var balanceText by remember(config) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    // 功能二：新建档案先弹命名框（旧行为静默建副本、新 chip 被挤出屏外，录屏反馈「点＋没反应」）。
    var showAdd by rememberSaveable { mutableStateOf(false) }
    // W-RT8：二维码导入导出（配置分享；负载无 Key，导入只回填非敏感字段，保存仍走用户确认）
    var showQrExport by rememberSaveable { mutableStateOf(false) }
    var showQrImport by rememberSaveable { mutableStateOf(false) }

    val dirty = baseUrl != config.baseUrl || apiKey != config.apiKey ||
        model != config.model || providerId != config.providerId ||
        ConfigSanitizer.parseHeaderLines(headersText) != config.customHeaders ||
        bodyText.trim() != config.customBody

    /** 把编辑区写回当前档案并回对话页（底部「保存」与顶部未保存提示条共用）。 */
    val saveNow: () -> Unit = {
        // 保存时统一过 ConfigSanitizer：剔除不可见字符、规范化 URL，避免带着 \u00A0 落盘
        vm.updateConfig(
            ConfigSanitizer.sanitized(baseUrl, apiKey, model, providerId).copy(
                customHeaders = ConfigSanitizer.parseHeaderLines(headersText),
                customBody = bodyText.trim()
            )
        )
        onBack()
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.MODEL.title)
        // 切换档案会静默丢弃未保存编辑（编辑器随 config 重置）——档案是顶层上下文，
        // 切走即换编辑对象，与「保存后才生效」的语义一致，不另做挽留弹窗
        ProfileSwitcher(
            profiles = profiles,
            activeId = activeProfileId,
            onSelect = { vm.switchProfile(it) },
            onAdd = { showAdd = true }
        )
        LocalTabRefreshEffect(vm = vm, tab = tab)
        ModelPageTabs(
            selected = tab,
            onSelect = { tab = it }
        )
        // W-70：供应商、Key、模型名都只存在编辑区，而「保存」按钮只在「模型」页签底部。
        // 真机（2026-10-28 录屏）就这样：切到「本地模型」页签选好模型文件、测试连接也通过了，
        // 人以为配好就退出——config 里还是 agnes，消息照旧发到云端。未保存时在任意页签顶部
        // 留一条提示，不必回到「模型」页签才看得见入口。
        if (dirty) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space2)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(start = AgentSpacing.space3, end = AgentSpacing.space2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "改动还没保存，对话仍用旧配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AkTextButton(text = "保存", onClick = saveNow)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .navigationBarsPadding()
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            when (tab) {
                0 -> {
                    // v4（真机反馈「两套触碰系统，上面也有下面也是，留一套就行」）：
                    // 这里原本还有一份 ProviderPickerSection——它和顶部 ProfileSwitcher 的
                    // chips 列的是同一批档案（同样的名字、同样带对勾），只是形态不同。
                    // 保留顶部 chips（更紧凑、随时可见、带「＋」），删掉这份列表。
                    // 切换配置的入口因此只有一个，不会再出现「两处数量还不同步」的怪状。
                    ModelTab(
                        vm = vm,
                        baseUrl = baseUrl,
                        onBaseUrl = { baseUrl = it },
                        apiKey = apiKey,
                        onApiKey = { apiKey = it },
                        model = model,
                        onModel = { model = it },
                        providerId = providerId,
                        onOpenLocalTab = { tab = 1 },
                        headersText = headersText,
                        onHeadersText = { headersText = it },
                        bodyText = bodyText,
                        onBodyText = { bodyText = it },
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { showAdvanced = !showAdvanced },
                        balanceText = balanceText,
                        balanceLoading = balanceLoading,
                        onQueryBalance = {
                            scope.launch {
                                balanceLoading = true
                                balanceText = com.beichen.polaris.core.llm.BalanceService
                                    .query(providerId, baseUrl, apiKey)
                                    .fold(
                                        onSuccess = { it },
                                        onFailure = { "查询失败：${it.message}" }
                                    )
                                balanceLoading = false
                            }
                        },
                        onProviderChange = { id ->
                            if (id != providerId) {
                                // 智能预填：仅当当前值是旧供应商默认值或空时才填新默认值，
                                // 用户手工改过的一律不覆盖（可预期 > 聪明）
                                if (baseUrl == ProviderDefaults.defaultUrl(providerId) || baseUrl.isBlank()) {
                                    baseUrl = ProviderDefaults.defaultUrl(id)
                                }
                                if (model == ProviderDefaults.defaultModel(providerId) || model.isBlank()) {
                                    model = ProviderDefaults.defaultModel(id)
                                }
                                // W-70：切到内置模型时**只清端点、保留 Key**。W-23 当年连 Key 一起清，
                                // 结果用户从本地切回云端发现要重填 Key（48 位的 Secret 谁记得住）。
                                // 端点清空是为了不让「本地还带着 Agnes 地址」；Key 留着只是躺在配置里，
                                // 本地客户端根本不读，回云端时还能直接用。
                                if (id == ProviderCatalog.STYLE_LOCAL_LLAMA) {
                                    baseUrl = ""
                                }
                                providerId = id
                            }
                        },
                        dirty = dirty,
                        onSave = saveNow,
                        onCancel = onBack
                    )

                    // W-RT8：配置二维码（换机迁移）。入口与两个弹窗都在 QrConfigSection.kt，
                    // 这里只挂一行——这个文件已经 2400 多行，二维码绘制不该再往里堆。
                    QrConfigSection(
                        onExport = { showQrExport = true },
                        onImport = { showQrImport = true }
                    )

                    // —— W-RT7：费用参考（当前会话）——
                    //
                    // 放在模型页签而不是提示词页签：用户是「想知道这套配置花了多少」才来的，
                    // 提示词页签跟钱无关。取的是当前会话的用量，切会话后自然变。
                    //
                    // remember(config)：进页面算一次，切档案重算。发消息发生在对话页，
                    // 那时这个页面已经被组合销毁，不存在「数字停在旧值」的观感问题。
                    val drill = remember(config) { vm.costDrillDown() }
                    if (drill.isNotEmpty()) {
                        AkGroup(
                            title = "费用参考（当前会话）",
                            description = "按各模型官网牌价估算，单位美元；价格调整可能滞后，量级为准",
                            paddedContent = true
                        ) {
                            drill.forEach { mc ->
                                val costText = mc.costUsd?.let { " ≈ $%.4f".format(it) }
                                    ?: "（价格未知，未计入合计）"
                                Text(
                                    text = mc.modelId +
                                        "：入 ${mc.promptTokens} / 出 ${mc.completionTokens} tokens" +
                                        costText,
                                    style = machineSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val total = com.beichen.polaris.core.llm.ModelPricing.totalUsd(drill)
                            if (total != null) {
                                Spacer(Modifier.height(AgentSpacing.space2))
                                Text(
                                    text = "合计 ≈ $%.4f".format(total),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                1 -> LocalModelsTab(vm = vm)

                else -> PromptTab(config = config)
            }
        }
    }

    // 功能二：新建档案对话框（用户录屏反馈「新增无 visible 反馈」+ 参照 App：先弹命名框再创建）。
    // 预填自动名「配置 N」，用户可直接点创建；清空则按 null 交 VM 走自动命名，绝不建空白名档案。
    // AkModalHost 用法与 ModelTab 的重命名对话框一致：退场播完再卸载，content 离开组合后
    // 内部 remember 的输入状态随之重置，下次打开重新预填。
    AkModalHost(target = if (showAdd) true else null) { _, visible ->
        var name by remember { mutableStateOf("配置 ${profiles.size + 1}") }
        AddProfileDialog(
            name = name,
            onNameChange = { name = it },
            visible = visible,
            onConfirm = { vm.addProfile(name.ifBlank { null }); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }

    // W-RT8：两个二维码弹窗（内容与渲染都在 QrConfigSection.kt）
    if (showQrExport) {
        QrExportDialog(
            providerId = providerId,
            baseUrl = baseUrl,
            model = model,
            profileName = profiles.firstOrNull { it.id == activeProfileId }?.name.orEmpty(),
            onDismiss = { showQrExport = false }
        )
    }
    if (showQrImport) {
        QrImportDialog(
            onDismiss = { showQrImport = false },
            onApply = { parsed ->
                baseUrl = parsed.baseUrl
                model = parsed.model
                providerId = parsed.providerId
            }
        )
    }
}

/** 「本地模型」页签打开时刷新清单与加载状态（下载完成后回到前台也刷新）。 */
@Composable
private fun LocalTabRefreshEffect(vm: ModelConfigContract, tab: Int) {
    LaunchedEffect(tab) { if (tab == 1) vm.refreshLocalModels() }
}

/** 三家供应商的默认 Base URL / 模型名（切换供应商时预填用）。 */
/**
 * hotfix34：供应商默认值改为委托 [ProviderCatalog]（30+ 家全量目录）。
 * 目录未收录的 id（用户自定义端点）走 OpenAI 兼容兜底——不因「不在目录」被卡住。
 */
private object ProviderDefaults {
    fun defaultUrl(id: String): String = ProviderCatalog.defaultUrl(id).ifBlank {
        if (id == ProviderCatalog.STYLE_LOCAL_LLAMA) "" else "https://api.openai.com/v1"
    }

    fun defaultModel(id: String): String = ProviderCatalog.defaultModel(id).ifBlank {
        // 本地模式的模型由「本地模型」页签的 active 文件决定，这里只给个占位
        if (ProviderCatalog.isLocal(id)) "" else "gpt-4o-mini"
    }
}

/**
 * 页面内 Tab（§6.5.3，全 App 仅此一处）：文字 labelLarge，未选 on-surface-var w500、
 * 选中 primary w600 + 底部 2dp primary 下划线（宽随文字）；仅点选，变色 150ms，无药丸无背景。
 */
@Composable
private fun ModelPageTabs(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("模型", "本地模型", "提示词")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space5),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            val lineColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = AkMotionFastColor
            )
            Column(
                Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .clickable { onSelect(index) }
                    .padding(
                        horizontal = AgentSpacing.space4,
                        vertical = AgentSpacing.space3
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // 下划线直接画在文字自己的宽度上。
                    //
                    // 旧写法是「Column 里放一个 fillMaxWidth 的 Box」：Row 测量时把剩余宽度
                    // 全给了第一个 Tab 的 Box，第一个 Tab 撑满整行，后两个被挤出屏幕——
                    // 真机（720x1280 @320dpi）实测只剩「模型」，本地模型/提示词两个 Tab 完全不可见。
                    // 用 drawWithContent 按 Text 自身 size 画线，宽度天然等于文字宽，也不会因
                    // IntrinsicSize 把中文压成竖排（2026-10-13 踩过的坑）。
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            color = lineColor,
                            topLeft = Offset(0f, size.height + 6.dp.toPx()),
                            size = Size(size.width, 2.dp.toPx())
                        )
                    }
                )
            }
            if (index < tabs.lastIndex) Spacer(Modifier.width(AgentSpacing.space2))
        }
    }
}

/**
 * 功能二：配置档案切换条（录屏反馈「别人的模型可以搞好几个，你就只能搞一个」）。
 *
 * Row + horizontalScroll：档案多了一行放不下时横向滚动，不换行、不挤压编辑器；
 * 每档案一枚 [AkChip]（选中态 = 当前生效配置），条尾「＋」chip 视觉与 AkChip 对齐。
 * 切换是即时生效的（VM 同步 _config 与旧四键镜像），未经保存的编辑器改动随切换丢弃。
 */
@Composable
private fun ProfileSwitcher(
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    val scrollState = rememberPersistentScrollState()
    // 用户录屏反馈「点＋新增后根本没有增加，他还是在最开始那个页面，根本就没变」：
    // 新档案 chip 追加在条尾，一屏放不下时被横向滚动条挤到屏幕外，点了跟没点一样。
    // 档案数量变化即平滑滚到最右，让最新档案和「＋」按钮进入可见区（数量没变不滚，
    // 避免切 tab 等无关重组把用户已经手动滚开的位置强行拽回）。
    LaunchedEffect(profiles.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEach { profile ->
            AkChip(
                text = profile.name,
                selected = profile.id == activeId,
                onClick = { onSelect(profile.id) }
            )
            Spacer(Modifier.width(AgentSpacing.space2))
        }
        AddProfileChip(onClick = onAdd)
    }
}

/** 「＋」chip：按 [AkChip] 视觉自绘（32dp 高 / radius full / surface-1 底 / 按压 surface-2 + scale 0.97）。 */
@Composable
private fun AddProfileChip(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) LocalAkColors.surface2 else LocalAkColors.surface1,
        animationSpec = AkMotionFastColor
    )
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "新增",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * hotfix34：供应商选择器（全量适配）。
 *
 * 真机反馈：「你问我哪家的模型，为什么一定要哪家的？你不能说全部都弄吗？
 * 现在大市面上比较出名的都弄一下，或者说全部适配一下」。
 *
 * 交互：常用厂商横滑 chip 常驻（OpenAI 兼容 / DeepSeek / Kimi / 智谱 / 通义 /
 * Claude / Gemini）＋ 末尾「全部供应商…」打开分组全量列表（国内 / 海外 /
 * 聚合与推理平台 / 本地部署 / 原生协议，共 30+ 家，每家带域名与说明）。
 * 选中即回填该家默认端点与模型（调用方 onSelect 里做）。
 */
@Composable
private fun ProviderSelector(selected: String, onSelect: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    val common = ProviderCatalog.all.filter { it.group == ProviderGroup.COMMON }
    // 非「常用」厂商被选中时，chip 行要能显示它（否则用户看不到当前选的是哪家）
    val current = ProviderCatalog.byId(selected)
    val extra = if (current != null && current.group != ProviderGroup.COMMON) listOf(current) else emptyList()

    Column(verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
            items(extra + common) { p ->
                AkChip(
                    text = p.label,
                    selected = p.id == selected,
                    onClick = { onSelect(p.id) }
                )
            }
            item {
                AkChip(text = "全部供应商…", selected = false, onClick = { showAll = true })
            }
        }
        // 当前供应商说明（域名 + 一句话）——选中后一眼确认「我配的是哪家」
        current?.let { p ->
            Text(
                text = buildString {
                    if (p.defaultBaseUrl.isNotBlank()) append(p.defaultBaseUrl)
                    if (p.note.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(p.note)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showAll) {
        AkModalHost(target = Unit) { _, dialogVisible ->
            ProviderAllDialog(
                selected = selected,
                visible = dialogVisible,
                onPick = { id ->
                    onSelect(id)
                    showAll = false
                },
                onDismiss = { showAll = false }
            )
        }
    }
}

/**
 * 全量供应商列表（分组 + 选中对勾）。344dp 宽卡片、封顶 560dp 高、内滚动——
 * 与 AkConfirmDialog 同族语汇；点选即切换并回填默认端点。
 */
@Composable
private fun ProviderAllDialog(
    selected: String,
    visible: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = "选择供应商",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space5)
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    LazyColumn(
                        Modifier.heightIn(max = 480.dp),
                        contentPadding = PaddingValues(horizontal = AgentSpacing.space3)
                    ) {
                        ProviderGroup.entries.forEach { group ->
                            val list = ProviderCatalog.all.filter { it.group == group }
                            if (list.isEmpty()) return@forEach
                            item(key = "hdr_${group.name}") {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = AgentSpacing.space2,
                                        top = AgentSpacing.space3,
                                        bottom = AgentSpacing.space1
                                    )
                                )
                            }
                            items(list, key = { it.id }) { p ->
                                ProviderOptionRow(
                                    provider = p,
                                    isSelected = p.id == selected,
                                    onClick = { onPick(p.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单条供应商：label + 域名/说明两行 + 选中对勾。 */
@Composable
private fun ProviderOptionRow(
    provider: LlmProvider,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = provider.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                if (provider.defaultBaseUrl.isNotBlank()) append(provider.defaultBaseUrl)
                if (provider.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(provider.note)
                }
                if (isEmpty()) append(provider.defaultModel)
            }
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.width(AgentSpacing.space2))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "当前选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 「模型」Tab：接入字段 + Key 自检 + 测试连接四态 + 结果卡 + 保存/取消（§6.8）。 */
@Composable
private fun ModelTab(
    vm: ModelConfigContract,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
    providerId: String,
    onProviderChange: (String) -> Unit,
    dirty: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onOpenLocalTab: () -> Unit = {},
    // W-29：高级参数（由父组件持有状态，本组件只渲染）
    headersText: String = "",
    onHeadersText: (String) -> Unit = {},
    bodyText: String = "",
    onBodyText: (String) -> Unit = {},
    showAdvanced: Boolean = false,
    onToggleAdvanced: () -> Unit = {},
    // W-29：余额查询（同样由父组件持有状态与协程）
    balanceText: String? = null,
    balanceLoading: Boolean = false,
    onQueryBalance: () -> Unit = {}
) {
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var keyRevealed by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // 「选择模型」弹窗：从当前端点拉取可用模型列表搜索选择（用户反馈「给了路径我就能搜到有什么模型」）
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    // 本地模式没有端点/Key：测试按钮放行，字段区换成状态卡
    // hotfix34：区分两种「本地」——isLocal 仅指 App 内置 llama.cpp（无端点字段）；
    // Ollama/LM Studio/vLLM 是外部本地服务，仍要填 baseUrl，只是不需要 Key
    val isLocal = providerId == ProviderCatalog.STYLE_LOCAL_LLAMA
    val needsKey = ProviderCatalog.requiresApiKey(providerId)
    val canTest = isLocal || (baseUrl.isNotBlank() && (!needsKey || apiKey.isNotBlank()))

    AkGroup(
        title = "模型接入",
        description = "内置 30+ 家供应商预设（DeepSeek / Kimi / 智谱 / 通义 / 豆包 / Claude / Gemini / " +
            "OpenRouter / Ollama…）。点「全部供应商」按组选；自建端点选「OpenAI 兼容」直接填。",
        paddedContent = true
    ) {
        Text(
            text = "供应商",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        ProviderSelector(selected = providerId, onSelect = onProviderChange)
        Spacer(Modifier.height(AgentSpacing.space3))

        if (isLocal) {
            // 本地模式：端点/Key/模型名三个字段全部不适用，换成当前本地模型状态卡
            LocalModeSummaryCard(vm = vm, onOpenLocalTab = onOpenLocalTab)
        } else {
            AkTextField(
                value = baseUrl,
                onValueChange = onBaseUrl,
                label = "Base URL",
                placeholder = ProviderDefaults.defaultUrl(providerId),
                machine = true
            )
            Spacer(Modifier.height(AgentSpacing.space3))
            // W-23 修：本地部署（Ollama / LM Studio / vLLM）不需要 Key——
            // 以前无条件渲染 Key 输入框，用户会以为「本地也要 API」，这是错的。
            if (needsKey) {
                // API Key：默认 masked，眼睛 IconButton（48dp 触控）切换明文
                AkTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = "API Key",
                    placeholder = "sk-…",
                    machine = true,
                    visualTransformation = if (keyRevealed) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailing = {
                        AkCircleIconButton(
                            icon = if (keyRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            description = if (keyRevealed) "隐藏 Key" else "显示 Key",
                            onClick = { keyRevealed = !keyRevealed },
                            iconSize = 20.dp
                        )
                    }
                )
                KeySelfCheck(apiKey)
                // P1（2026-10-21）：Key 为空时给一条直达入口。真机上最常卡的一步
                // 不是「填错」，而是「不知道去哪弄」——把控制台地址做成一次点击。
                // 未收录的自定义端点 / 本地部署没有控制台，consoleUrlOf 返回空串，不渲染。
                if (apiKey.isBlank()) {
                    val consoleUrl = ProviderCatalog.consoleUrlOf(providerId)
                    if (consoleUrl.isNotBlank()) {
                        KeyConsoleLink(providerId = providerId, url = consoleUrl)
                    }
                }
            } else {
                Text(
                    text = "本地服务不需要 API Key（Ollama / LM Studio / vLLM 直接连本机端口）。" +
                        "填了也会被忽略。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(AgentSpacing.space3))
            // 模型名： trailing 放大镜 IconButton（48dp 触控）打开「选择模型」弹窗，
            // 从端点拉取可用模型搜索选择，不必盲手填（用法同上方 API Key 字段的眼睛按钮）
            AkTextField(
                value = model,
                onValueChange = onModel,
                label = "模型名",
                placeholder = ProviderDefaults.defaultModel(providerId),
                machine = true,
                trailing = {
                    AkCircleIconButton(
                        icon = Icons.Outlined.Search,
                        description = "获取模型列表",
                        onClick = { showModelPicker = true },
                        iconSize = 20.dp
                    )
                }
            )

            // W-23：给「想用内置模型但选错供应商」的用户一条明路——
            // 不用再翻「全部供应商」弹窗找「本地模型（内置）」。
            Spacer(Modifier.height(AgentSpacing.space2))
            AkTextButton(
                text = "改用 App 内置模型（无需 API / 不联网）",
                icon = Icons.Outlined.Memory,
                onClick = { onProviderChange(ProviderCatalog.STYLE_LOCAL_LLAMA) }
            )

            // W-29：高级参数（自定义请求头 / 请求体）——折叠，默认收起
            Spacer(Modifier.height(AgentSpacing.space2))
            AkTextButton(
                text = if (showAdvanced) "收起高级参数" else "高级：自定义请求头 / 请求体",
                icon = if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                onClick = onToggleAdvanced
            )
            if (showAdvanced) {
                Spacer(Modifier.height(AgentSpacing.space2))
                AkTextField(
                    value = headersText,
                    onValueChange = onHeadersText,
                    label = "自定义请求头（每行一条 Key: Value，# 开头为注释）",
                    placeholder = "HTTP-Referer: https://example.com\nX-Title: Polaris",
                    singleLine = false,
                    maxLines = 5,
                    machine = true
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                AkTextField(
                    value = bodyText,
                    onValueChange = onBodyText,
                    label = "自定义请求体（JSON 对象，可留空）",
                    placeholder = "{\"top_k\": 40}",
                    singleLine = false,
                    maxLines = 5,
                    machine = true
                )
                val bodyError = ConfigSanitizer.validateCustomBody(bodyText)
                if (bodyError != null) {
                    Spacer(Modifier.height(AgentSpacing.space1))
                    Text(
                        bodyError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(AgentSpacing.space1))
                Text(
                    "请求头会追加到默认头之后，同名覆盖默认值；请求体会合并进请求 JSON，" +
                        "同名会覆盖内置默认值（例如 temperature）。仅对 OpenAI 兼容端点生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    TestConnectionButton(
        testing = testing,
        result = testResult,
        enabled = canTest && !testing,
        onClick = { vm.testConnection(baseUrl, apiKey, model, providerId) }
    )
    if (!canTest) {
        Text(
            text = "填写 Base URL 与 API Key 后即可测试连接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    testResult?.let { result ->
        // 自定义端点（未收录 id）没有控制台：不渲染「去拿 Key」按钮，
        // 否则用户点下去什么都不会发生——宁可少一个按钮，也不给一个空转的按钮。
        val consoleUrl = ProviderCatalog.consoleUrlOf(providerId)
        TestResultCard(
            result = result,
            onCopy = { copyToClipboard(ctx, result) },
            // P1：失败不再只给一句建议，按钮直接指向下一步
            onGetKey = if (consoleUrl.isNotBlank()) {
                { openExternal(ctx, consoleUrl) }
            } else null,
            onPickModel = { showModelPicker = true },
            onRetry = { vm.testConnection(baseUrl, apiKey, model, providerId) },
            // P1.3：测试通过就地收尾——保存（改过才需要）并回对话，不用自己找返回路
            onUseAndReturn = { if (dirty) onSave() else onCancel() }
        )
    }

    // W-29：余额查询（只有部分服务商提供接口：DeepSeek / Kimi / 硅基流动 / OpenRouter）
    if (!isLocal) {
        Spacer(Modifier.height(AgentSpacing.space2))
        AkOutlinedButton(
            text = if (balanceLoading) "查询中…" else "查余额",
            enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && !balanceLoading,
            onClick = onQueryBalance
        )
        balanceText?.let {
            Spacer(Modifier.height(AgentSpacing.space1))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // 保存 / 取消：flex 1 等高（§6.8）；无改动时保存禁用（38% alpha）
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
    ) {
        AkOutlinedButton(
            text = "取消",
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        )
        AkPrimaryButton(
            text = "保存",
            onClick = onSave,
            enabled = dirty,
            modifier = Modifier.weight(1f)
        )
    }

    // 「选择模型」弹窗：AkModalHost 承载退场动画（用法同重命名/删除对话框）。
    // 打开即拉取；local-llama 模式字段区换成 LocalModeSummaryCard，天然没有该入口。
    AkModalHost(target = if (showModelPicker) true else null) { _, visible ->
        ModelPickerSheet(
            vm = vm,
            baseUrl = baseUrl,
            apiKey = apiKey,
            providerId = providerId,
            onPick = { onModel(it); showModelPicker = false },
            onDismiss = { vm.clearModelList(); showModelPicker = false },
            visible = visible
        )
    }

    // 功能二：当前档案管理。删除是不可逆操作，AkConfirmDialog danger 二次确认；
    // 只剩一个档案时删除项置灰（VM 侧 deleteProfile 同样拒绝，双保险）
    AkGroupCard {
        AkListItem(
            icon = Icons.Outlined.Edit,
            title = "重命名当前配置",
            description = activeProfile?.name ?: "未命名配置",
            onClick = { showRename = true }
        )
        AkDivider()
        AkListItem(
            icon = Icons.Outlined.Delete,
            title = "删除当前配置",
            description = if (profiles.size > 1) "删除后不可恢复，将切换到其余配置"
            else "至少保留一个配置",
            enabled = profiles.size > 1,
            onClick = { showDelete = true }
        )
    }

    // 重命名对话框：AkModalHost 承载退场动画（直接随 flag 卸载会闪断）
    AkModalHost(target = if (showRename) activeProfileId else null) { id, visible ->
        val profile = profiles.firstOrNull { it.id == id }
        var text by remember(id) { mutableStateOf(profile?.name ?: "") }
        RenameProfileDialog(
            name = text,
            onNameChange = { text = it },
            visible = visible,
            onConfirm = { vm.renameProfile(id, text); showRename = false },
            onDismiss = { showRename = false }
        )
    }

    // 删除确认：danger 红色只出现在审批与不可逆确认两处（§3.1）
    AkModalHost(target = if (showDelete) activeProfileId else null) { id, visible ->
        val profile = profiles.firstOrNull { it.id == id }
        AkConfirmDialog(
            title = "删除当前配置",
            message = "确定删除「${profile?.name ?: "该配置"}」吗？" +
                "其 Base URL 与 API Key 将一并移除，删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            visible = visible,
            onConfirm = { vm.deleteProfile(id); showDelete = false },
            onDismiss = { showDelete = false }
        )
    }
}

/**
 * 功能二：档案重命名对话框（[AkModalHost] 承载退场动画）。
 *
 * 与 [AkConfirmDialog] 同语言：344dp 白卡 + scale/fade 出入场；提交空名由 VM 忽略
 * （renameProfile 对 blank 直接 return），此处按钮同步禁用给即时反馈。
 */
@Composable
private fun RenameProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
                    Text(
                        text = "重命名配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "配置名称",
                        placeholder = "例如：家里的 OpenAI"
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "确定",
                            onClick = onConfirm,
                            enabled = name.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 功能二：「新建模型配置」对话框（用户录屏反馈「点＋新增没有任何 visible 反馈，
 * 还是在最开始那个页面，根本就没变」+ 参照 App：点「＋新建」→ 命名框 → 创建后才可见）。
 *
 * 与 [RenameProfileDialog] 同语言同机制（AkModalHost 承载退场 + 344dp 白卡 + scale/fade）：
 * 标题 + 一句描述 + 名称输入 + 取消/创建。创建按钮永不禁用——名称清空时由 VM 回退
 * 自动命名（addProfile 对空白名走「配置 N」），绝不创建空白名档案。
 */
@Composable
private fun AddProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)
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
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "新建模型配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "创建新的模型配置，自定义 API 和参数设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "配置名称",
                        placeholder = "例如：GPT-4 配置、Claude 配置..."
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "创建",
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「选择模型」弹窗（用户反馈「给了路径我就能搜索到他有什么模型，我不是说模型我自己搞」）：
 * 从当前端点拉取可用模型，点击选择；也可以返回手动输入。
 *
 * 视觉语言对齐 [AddProfileDialog] / [RenameProfileDialog]：AkModalHost 承载退场 +
 * 344dp 圆角白卡 + scale/fade 出入场。四态渲染（消费 VM 的 modelListLoading /
 * modelListError / modelList 三个 StateFlow）：
 *  ① loading：圆环 +「正在获取模型列表…」；
 *  ② error：脱敏后的错误文案 +「重试」；
 *  ③ 空列表且非 loading：「未获取到模型」+「重试」+「该端点可能不支持 /models，可返回手动输入」；
 *  ④ 有数据：搜索框 +「共 N 个模型」+ 封顶 360dp 的 LazyColumn，点击行即选定；
 *     当前已选中的行（与已落盘的 config.model 比对）文字升 primary 并附对勾。
 */
@Composable
private fun ModelPickerSheet(
    vm: ModelConfigContract,
    baseUrl: String,
    apiKey: String,
    providerId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean
) {
    val loading by vm.modelListLoading.collectAsStateWithLifecycle()
    val error by vm.modelListError.collectAsStateWithLifecycle()
    val models by vm.modelList.collectAsStateWithLifecycle()
    // 当前已选中的模型：以已落盘配置为准（不是编辑器里未保存的值）
    val config by vm.config.collectAsStateWithLifecycle()
    // 功能五（P1-6）：收藏模型（收藏区恒在最前）
    val favorites by vm.favoriteModels.collectAsStateWithLifecycle()
    // 搜索关键词：纯本地状态，无需上提到 VM
    var keyword by remember { mutableStateOf("") }

    // 打开即拉（弹窗随退场动画卸载后，再次打开重新组合会重新触发）
    LaunchedEffect(Unit) { vm.fetchModelList(baseUrl, apiKey, providerId) }

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
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "从当前端点拉取可用模型，点击选择；也可以返回手动输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    when {
                        // ① loading
                        loading -> Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = AgentSpacing.space6),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = "正在获取模型列表…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // ② error：文案 + 重试
                        error != null -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = error ?: "未知错误",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            AkTextButton(
                                text = "重试",
                                onClick = { vm.fetchModelList(baseUrl, apiKey, providerId) }
                            )
                        }

                        // ③ 空列表（非 loading）：未获取到 + 重试 + 端点不支持提示
                        models.isEmpty() -> Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "未获取到模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            AkTextButton(
                                text = "重试",
                                onClick = { vm.fetchModelList(baseUrl, apiKey, providerId) }
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            Text(
                                text = "该端点可能不支持 /models，可返回手动输入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        // ④ 有数据：搜索框 + 计数 + 封顶列表
                        else -> {
                            AkTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                label = "搜索模型",
                                placeholder = "搜索模型",
                                machine = true
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            // 功能五（P1-6）：收藏优先排序（对标 RikkaHub ModelListSheet 收藏区）
                            // sortedByDescending 是稳定排序：收藏组内保持端点原序，不跳来跳去
                            val visible = remember(models, keyword, favorites) {
                                val base = if (keyword.isBlank()) models
                                else models.filter { it.contains(keyword, ignoreCase = true) }
                                base.sortedByDescending { it in favorites }
                            }
                            val listState = rememberLazyListState()
                            // 功能五（P1-6）：打开即定位当前模型（列表拉取完成后滚过去，
                            // 不用用户在几十项里自己找——RikkaHub `selectedModelPosition` 同目的）
                            LaunchedEffect(models, keyword) {
                                if (keyword.isBlank() && models.isNotEmpty()) {
                                    val idx = visible.indexOf(config.model)
                                    if (idx > 0) listState.scrollToItem(idx)
                                }
                            }
                            Text(
                                text = "共 ${models.size} 个模型 · 收藏 ${favorites.count { it in models }} 个",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                            ) {
                                items(visible, key = { it }) { id ->
                                    ModelPickerRow(
                                        id = id,
                                        isCurrent = id == config.model,
                                        favorite = id in favorites,
                                        onPick = { onPick(id) },
                                        onToggleFavorite = { vm.toggleFavoriteModel(id) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space6))
                    // 底部取消：fillMaxWidth，与顶部动作行的视觉重量一致
                    AkOutlinedButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 功能五（P1-6）：模型列表行——模型名 + 能力标签 + 星标 + 当前对勾。
 *
 * 星标与整行点击是**两个独立热区**：点星只切换收藏、不选中模型
 * （点星会顺带选模型是最容易误操作的实现）。
 *
 * 能力标签是**规则推断**（见 [modelTags]）而非白名单表——新模型只要命名规律
 * 一致就自动命中，不用发版维护（思路来自 RikkaHub ModelRegistry 的匹配引擎）。
 */
@Composable
private fun ModelPickerRow(
    id: String,
    isCurrent: Boolean,
    favorite: Boolean,
    onPick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPick)
            .padding(vertical = AgentSpacing.space3, horizontal = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = id,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = modelTags(id)
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(AgentSpacing.space1))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
                    tags.forEach { tag ->
                        AkBadge(
                            text = tag,
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (favorite) "取消收藏" else "收藏",
            tint = if (favorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite)
                .padding(AgentSpacing.space2)
                .size(18.dp)
        )
        if (isCurrent) {
            Spacer(Modifier.width(AgentSpacing.space1))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "当前模型",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 功能五（P1-6）：按模型名推断能力标签（规则匹配，不是白名单）。
 *
 * 只标两类用户真正用得上的：「视觉」（决定能不能发图）与「推理」（决定该不该
 * 期待它慢慢想）。宁可漏标不可错标——错标会让用户发图给纯文本模型然后困惑。
 */
private fun modelTags(id: String): List<String> {
    val lower = id.lowercase()
    val tags = mutableListOf<String>()
    val vision = listOf(
        "gpt-4o", "gpt-4.1", "gpt-5", "vision", "-vl", "vl-", "llava",
        "claude-3", "claude-4", "claude-sonnet", "gemini", "glm-4v", "qwen2-vl", "qwen3-vl"
    )
    if (vision.any { lower.contains(it) }) tags += "视觉"
    val reasoning = listOf("o1", "o3", "o4", "reason", "thinking", "-r1", "deepseek-r")
    if (reasoning.any { lower.contains(it) }) tags += "推理"
    return tags
}

/**
 * 「本地模型」页签（九步第 3 步）：本地 llama.cpp 推理的完整控制台。
 *
 * 四个分区：运行状态 → 已下载模型（选用/删除）→ 内置目录（下载）→ 推理参数。
 * 所有状态都由 ChatViewModel 的 StateFlow 驱动，Composable 无副作用（除刷新 effect）。
 */
@Composable
private fun LocalModelsTab(vm: ModelConfigContract) {
    val models by vm.localModels.collectAsStateWithLifecycle()
    val active by vm.localActive.collectAsStateWithLifecycle()
    val loaded by vm.localLoaded.collectAsStateWithLifecycle()
    val downloading by vm.localDownloading.collectAsStateWithLifecycle()
    val nCtx by vm.localNCtx.collectAsStateWithLifecycle()
    val threads by vm.localThreads.collectAsStateWithLifecycle()
    val maxTokens by vm.localMaxTokens.collectAsStateWithLifecycle()
    val agentMode by vm.localAgentMode.collectAsStateWithLifecycle()
    val gpuMode by vm.localGpuMode.collectAsStateWithLifecycle()
    val gpuSummary by vm.localGpuSummary.collectAsStateWithLifecycle()
    val gpuCrashNote by vm.localGpuCrashNote.collectAsStateWithLifecycle()
    val safeMode by vm.localSafeMode.collectAsStateWithLifecycle()
    val resourceTier by vm.localResourceTierLock.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val catalog = remember(ctx) { com.beichen.polaris.core.llm.local.GgufModelStore(ctx).catalog() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // W-45：用户手机里已有的 .gguf 直接导入，不必再从内置目录下一遍
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importLocalModel(uri) }
    val config by vm.config.collectAsStateWithLifecycle()
    val onLocalProvider =
        config.providerId == com.beichen.polaris.core.llm.ProviderCatalog.STYLE_LOCAL_LLAMA

    // W-44：下载 ≠ 使用。真机反馈「下好了但不知道咋用」——选了本地模型文件，
    // 但「当前模型」仍是云端供应商，对话照样走云。这里把状态和下一步摆在最上面。
    if (!onLocalProvider) {
        AkGroup(
            title = "本地模型还没生效",
            description = "只有把「当前模型」切到「本地模型（内置）」，对话才会走本地推理",
            paddedContent = true
        ) {
            Text(
                text = "当前供应商：" + config.providerId.ifBlank { "未配置" },
                style = machineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AgentSpacing.space3))
            if (models.isNotEmpty()) {
                AkOutlinedButton(
                    text = "切换为本地模型",
                    onClick = {
                        // 只切 provider，不清 baseUrl/apiKey：清空后用户切回云端得重填 Key。
                        // 本地客户端不读这两个字段，留着不影响推理，且切换可逆。
                        vm.updateConfig(
                            config.copy(
                                providerId = com.beichen.polaris.core.llm.ProviderCatalog.STYLE_LOCAL_LLAMA
                            )
                        )
                    }
                )
            } else {
                Text(
                    text = "先在下面「模型目录」下载一个（配置低先从 0.5B 试，要工具调用稳就 1.5B 起）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // —— 运行状态 ————————————————————————————————————————————
    val nativeOk = remember { com.beichen.polaris.core.llm.local.LlamaJni.ensureLoaded() }
    AkGroup(
        title = "运行状态",
        description = "由 llama.cpp（b4600，MIT）驱动，模型常驻内存随用随聊；仅支持 arm64 真机",
        paddedContent = true
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(
                if (nativeOk) LocalAkColors.success else MaterialTheme.colorScheme.error,
                6.dp
            )
            Spacer(Modifier.width(7.dp))
            Text(
                // 不可用时优先说清「为什么」：ABI 不符与「CPU 缺指令集」是两种完全不同的
                // 处置方式（换设备 vs 等双 .so），一句「ABI 不支持」会把用户引到错方向。
                text = if (nativeOk) {
                    "推理引擎就绪（arm64）"
                } else {
                    com.beichen.polaris.core.llm.local.LlamaJni.unavailableReason
                        ?: "推理引擎不可用（当前设备 ABI 不支持）"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (nativeOk) LocalAkColors.success else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            text = "当前模型：${active.ifBlank { "未选择" }}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "内存状态：${if (loaded) "已加载（可立即对话）" else "未加载（首次对话时加载，约需数秒）"}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loaded) {
            Spacer(Modifier.height(AgentSpacing.space3))
            AkOutlinedButton(
                text = "卸载模型（释放内存）",
                onClick = { vm.unloadLocalModel() },
                icon = Icons.Outlined.Delete
            )
        }
    }

    // —— 已下载模型 ——————————————————————————————————————————
    AkGroup(
        title = "已下载模型",
        description = "点选即设为当前模型，下次对话生效；删除会同时清理文件",
        paddedContent = true
    ) {
        // 已有模型文件的用户不必再走「模型目录」重下一遍（几 GB 的流量）
        AkOutlinedButton(
            text = "从本地文件导入 .gguf",
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            icon = Icons.Outlined.Add
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        if (models.isEmpty()) {
            Text(
                text = "还没有模型：可以直接导入手机里已有的 .gguf，或从下方「模型目录」下载一个（低配先试 0.5B，常用 1.5B）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            models.forEach { m ->
                val isActive = m.fileName == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setActiveLocalModel(m.fileName) }
                        .padding(vertical = AgentSpacing.space3, horizontal = AgentSpacing.space2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AkStatusDot(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        8.dp
                    )
                    Spacer(Modifier.width(AgentSpacing.space3))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = m.fileName,
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "${m.sizeBytes / 1024 / 1024} MB" +
                                if (isActive) " · 当前使用" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AkCircleIconButton(
                        icon = Icons.Outlined.Delete,
                        description = "删除 ${m.fileName}",
                        onClick = { pendingDelete = m.fileName },
                        iconSize = 20.dp
                    )
                }
            }
        }
    }

    // —— 模型目录 ————————————————————————————————————————————
    AkGroup(
        title = "模型目录",
        description = "Q4_K_M 量化，均来自 HuggingFace 公开仓库；下载支持断点续传",
        paddedContent = true
    ) {
        catalog.forEach { entry ->
            val isDownloaded = models.any { it.fileName == entry.fileName }
            val isDownloading = downloading?.first == entry.fileName
            Column(Modifier.padding(vertical = AgentSpacing.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${entry.displayName} · ${entry.paramSize}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${entry.description} · 约 ${entry.approxBytes / 1024 / 1024} MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        isDownloading -> Text(
                            text = "下载中…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        isDownloaded -> Text(
                            text = "已下载",
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalAkColors.success
                        )
                        else -> AkOutlinedButton(
                            text = "下载",
                            onClick = { vm.downloadLocalModel(entry) },
                            icon = Icons.Outlined.Download,
                            enabled = downloading == null
                        )
                    }
                }
                if (isDownloading) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    val progress = downloading?.second
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
            if (entry != catalog.last()) AkDivider()
        }
    }

    // —— 对话模式 ————————————————————————————————————————————
    //
    // 这一项直接决定「首字要等多久」，比线程数、上下文长度重要一个数量级：
    // Agent 模式会把 7 个工具的说明一起塞进 prompt（约 1086 token），轻聊档只有几句话
    // （约 40 token）。手机 CPU 上这就是「180 秒算不完」和「5 秒出字」的差别。
    AkGroup(
        title = "本地对话模式",
        description = "轻聊档只发你这句话，出字最快；Agent 模式会附带工具清单与完整行为规则，prompt 长约 20 倍，首次回复会明显变慢",
        paddedContent = true
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Agent 模式（能调用工具）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (agentMode) "已开启：能读写文件、点屏幕，但首字会慢很多"
                    else "已关闭：纯聊天，只发你这句话，首字最快",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = agentMode, onCheckedChange = { vm.setLocalAgentMode(it) })
        }
    }

    // —— GPU 加速 ————————————————————————————————————————————
    //
    // 放在「推理参数」之前，因为它是这一批机型（天玑 / 8 Gen 1 以下）唯一可能
    // 提速一个数量级的开关，比调线程数重要得多。
    //
    // 三档而不是开关：Mali 的 Vulkan 驱动能在短任务上跑得不错，长任务会
    // VK_ERROR_DEVICE_LOST。「自动」由设备规则判，「强制」是用户看过提示之后的
    // 明确选择，「关闭」给那些更在意耗电与发热的人。
    AkGroup(
        title = "GPU 加速（Vulkan）",
        description = "把模型层卸载到显卡。Adreno 自动开启；Mali 驱动在长时间推理后可能中断，默认不自动开。改动会在下一条消息发出时自动重载生效",
        paddedContent = true
    ) {
        Text(
            text = gpuSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 上次开 GPU 崩过就把原因摆出来。不给提示的话，用户只会看到「自动」突然不生效了，
        // 以为是设置坏了——而真相是上一条消息把进程打死了，这次被自动退回 CPU。
        if (gpuCrashNote.isNotBlank()) {
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = "⚠ $gpuCrashNote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        com.beichen.polaris.core.llm.local.GpuMode.values().forEach { mode ->
            val hint = when (mode) {
                com.beichen.polaris.core.llm.local.GpuMode.AUTO -> "由设备判定，只在稳妥的机型上开"
                com.beichen.polaris.core.llm.local.GpuMode.FORCE -> "明知有风险也开，适合想自己试的机型"
                com.beichen.polaris.core.llm.local.GpuMode.OFF -> "完全不用显卡，最省电、最稳"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.setLocalGpuMode(mode) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = gpuMode == mode,
                    onClick = { vm.setLocalGpuMode(mode) }
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // —— 推理参数 ————————————————————————————————————————————
    AkGroup(
        title = "推理参数",
        description = "上下文越大越吃内存（4K 约额外 0.5GB）；GPU 开启时上限 4096（显存要和权重共用）；线程数超过大核数收益递减，保存后下次加载生效",
        paddedContent = true
    ) {
        // —— 资源档位 ——————————————————————————————
        //
        // 和安全模式一样是「会覆盖下面填的值」的开关（AUTO 只减不增），所以也放在
        // 被覆盖的参数之前。默认自动：按机型画像选档；锁档后完全按档位表来。
        //
        // 这张表是唯一的「档位 → 参数」来源，规则在 core 的 ResourcePlanRules；
        // 这里只负责显示与选择，不在这里再判一次机型。
        val tierOptions: List<Pair<com.beichen.polaris.core.llm.local.ResourceTier?, String>> = listOf(
            null to "自动（跟机型走）：只减不增，你手动调高的值不会被覆盖",
            com.beichen.polaris.core.llm.local.ResourceTier.ECO to
                "省电：入门 / 中低端档，压线程、上下文封顶 4096",
            com.beichen.polaris.core.llm.local.ResourceTier.BALANCED to
                "均衡：现行默认口径，只对激进设置封顶",
            com.beichen.polaris.core.llm.local.ResourceTier.PERFORMANCE to
                "性能：不设额外限制，设置给多少用多少",
        )
        val tierLock = com.beichen.polaris.core.llm.local.ResourceTier.parse(resourceTier)
        tierOptions.forEachIndexed { index, (tier, hint) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.setLocalResourceTierLock(tier?.name ?: "AUTO") },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = tierLock == tier,
                    onClick = { vm.setLocalResourceTierLock(tier?.name ?: "AUTO") }
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (tier == null) "资源档位：自动" else "资源档位：${tier.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index != tierOptions.lastIndex) Spacer(Modifier.height(AgentSpacing.space2))
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        // —— 安全模式 ————————————————————————————————
        //
        // 排在最前，因为它会**覆盖**下面两个值：开着的时候，用户把上下文写到 8192
        // 也只按 2048 生效。把「为什么你的设置没起作用」放在被覆盖的参数之上，
        // 比放在组尾更不容易让人以为设置坏了。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "安全模式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (safeMode) {
                        "已开启：上下文按 2048、批按 64 运行（下一次加载模型生效）"
                    } else {
                        "已关闭：按下面填的值运行。若在本机反复闪退，可打开它换稳定"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (safeMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = safeMode, onCheckedChange = { vm.setLocalSafeMode(it) })
        }
        Spacer(Modifier.height(AgentSpacing.space3))
        LocalParamField(
            label = "上下文长度（token）",
            value = nCtx.toString(),
            hint = "512 ~ 16384，常用 2048 / 4096",
            onValue = { vm.setLocalNCtx(it.toIntOrNull() ?: return@LocalParamField) }
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        LocalParamField(
            label = "推理线程数",
            value = threads.toString(),
            hint = "1 ~ 8，默认取大核簇核心数（多数机型 4~6）",
            onValue = { vm.setLocalThreads(it.toIntOrNull() ?: return@LocalParamField) }
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        LocalParamField(
            label = "单次生成上限（token）",
            value = maxTokens.toString(),
            hint = "64 ~ 8192，一次回复的最大长度",
            onValue = { vm.setLocalMaxTokens(it.toIntOrNull() ?: return@LocalParamField) }
        )
    }

    pendingDelete?.let { name ->
        val sizeMb = models.firstOrNull { it.fileName == name }?.sizeBytes?.div(1024 * 1024)
        AkConfirmDialog(
            title = "删除模型文件",
            message = "确定删除 $name 吗？文件将从本机移除" +
                (sizeMb?.let { "（$it MB），" } ?: "，") + "需要时需重新下载。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            onConfirm = {
                vm.deleteLocalModel(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** 「模型」页签里本地模式的状态摘要（点「本地模型」页签可管理）。 */
@Composable
private fun LocalModeSummaryCard(vm: ModelConfigContract, onOpenLocalTab: () -> Unit = {}) {
    val active by vm.localActive.collectAsStateWithLifecycle()
    val loaded by vm.localLoaded.collectAsStateWithLifecycle()
    val models by vm.localModels.collectAsStateWithLifecycle()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(
                if (active.isNotBlank()) LocalAkColors.success
                else MaterialTheme.colorScheme.error,
                6.dp
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (active.isBlank()) "未选择本地模型" else "已选用 $active",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        Text(
            text = buildString {
                append("本地模式不使用 Base URL 与 API Key。")
                if (active.isBlank()) {
                    append("请切到「本地模型」页签下载并选用一个 GGUF 模型（约 1GB 起）。")
                } else {
                    append("模型文件共 ${models.size} 个，推理参数见「本地模型」页签。")
                    if (loaded) append("当前已在内存中，可直接对话。")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AgentSpacing.space2))
        // W-23：给一条直接入口——用户反馈「切到本地模式后下面啥都没有」，
        // 其实就是没找到顶部的「本地模型」页签。
        AkTextButton(
            text = if (active.isBlank()) "打开「本地模型」页签，下载一个模型" else "打开「本地模型」页签管理模型",
            icon = Icons.Outlined.Memory,
            onClick = onOpenLocalTab
        )
    }
}

/**
 * 本地参数数字输入行。
 *
 * **编辑期必须用本地文本，不能拿 [value] 直接受控。**
 *
 * 设置层的 setter 一律 `coerceIn`（上下文 512~16384、线程 1~8、一次上限 64~8192）。
 * 受控写法 + coerce 会在**每次按键**后把半截数字送进 setter，再拿 coerce 后的值
 * 覆盖输入框：想输 `2048`，打「2」被抬成 `512`、显示 `512`，再打「0」变 `5120`……
 * 结果是怎么删都删不掉、打什么都跳回原值。
 *
 * 真机录屏（2026-10-29）就是这个现象：上下文卡在 `16384` 改不动，三个参数全废。
 *
 * 现在的分工：
 *  - 输入过程中只更新本地 [text]，同时把合法数字**实时**落盘（不点别处也不丢）；
 *  - 编辑中绝不把 [value] 灌回来，光标不会跳；
 *  - 失焦后用 coerce 后的真实值校正显示（输入 `300` 会被改成 `512`，用户看得见）。
 *
 * 另外：label 只由 [AkTextField] 画一次。原来这里自己又画了一遍 + 传 label 进去，
 * 真机上同一个标题上下出现两次（录屏里可见）。
 */
@Composable
private fun LocalParamField(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    // 非编辑态下跟随外部值：既覆盖「首次组合」，也覆盖「失焦后由 coerce 纠正」。
    // 编辑中（focused）刻意不同步 —— 那正是把输入打断的元凶。
    LaunchedEffect(value, focused) {
        if (!focused && text != value) text = value
    }

    AkTextField(
        value = text,
        onValueChange = { raw ->
            // 只留数字：纯整数字段，粘贴进来带空格或单位不该整段作废。
            // 截 5 位够到 16384；不截的话误触会打出天文数字，提交时再被拽回来。
            val digits = raw.filter { it.isDigit() }.take(5)
            text = digits
            // 空串是「清空重打」的中间态，不是用户意图，不落盘。
            if (digits.isNotEmpty()) onValue(digits)
        },
        // AkTextField 把 modifier 挂在最外层 Column 上，而这个 Column 自己永远不是
        // 焦点目标（焦点在里面的 BasicTextField），所以它的 `isFocused` 恒为 false。
        // 必须看 `hasFocus`（= 子树里有焦点），才能在离开字段时拿到那一次 true→false。
        modifier = Modifier.onFocusChanged { state ->
            focused = state.hasFocus
        },
        label = label,
        placeholder = hint,
        machine = true
    )
}

/**
 * 「去某某获取 API Key」入口（P1，2026-10-21）。
 *
 * 只做一件事：用系统浏览器打开 [url]。外链失败（没有浏览器 / ROM 限制）时静默——
 * 用户仍可自己搜索，弹一个他解决不了的错误框只会更糟。文案用供应商显示名，
 * 与上方选择器完全一致，避免「DeepSeek 控制台」和「深度求索」两套叫法。
 */
@Composable
private fun KeyConsoleLink(providerId: String, url: String) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { openExternal(ctx, url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // 「OpenAI 兼容」是通用风格名，拼成「去 OpenAI 兼容 获取 API Key」读不通；
            // 这个 id 的默认端点就是 OpenAI 官方，所以对这种情况说「OpenAI 官方」。
            text = "去 ${consoleLabel(providerId)} 获取 API Key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 控制台入口里的供应方称呼：通用风格名换成具体平台名。 */
private fun consoleLabel(providerId: String): String =
    if (providerId == ProviderCatalog.STYLE_OPENAI) "OpenAI 官方"
    else ProviderCatalog.labelOf(providerId)

/** 打开外部链接：失败静默（没有浏览器 / ROM 限制时用户自己搜也一样）。 */
private fun openExternal(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * API Key 实时自检行（§6.8）：每次输入实时刷新。
 *
 * 正常：6dp success 点 +「长度 56 · sk-a…7F3A · 校验通过」12sp（指纹 machineSmall）；
 * 异常：danger 点 + 「检测到 3 个不可见字符，保存时自动清除」danger——
 * 在保存前就把「粘贴不全 / 夹带不可见字符」暴露给用户。
 */
@Composable
private fun KeySelfCheck(rawKey: String) {
    if (rawKey.isBlank()) return
    val insp = remember(rawKey) { ConfigSanitizer.inspectKey(rawKey) }
    val hasIssue = insp.hasIllegalChars || insp.length < 8
    val dot = if (hasIssue) MaterialTheme.colorScheme.error else LocalAkColors.success
    Column(Modifier.padding(top = AgentSpacing.space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AkStatusDot(dot, 6.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append("长度 ${insp.length} · ")
                    }
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append("${insp.head}…${insp.tail}")
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(if (hasIssue) " · 存在异常" else " · 校验通过")
                    }
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (insp.hasIllegalChars) {
            Text(
                text = "检测到 ${insp.removedCount} 个不可见字符，保存时自动清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * 「测试连接」按钮四态（§6.0 / §6.8）：
 * idle = Filled 主按钮全宽；testing = 左 18dp 圆环 + 「测试中…」；
 * ok = success-container 底 + 对勾 + 「连接正常」；fail = danger-container 底 + 状态码。
 */
@Composable
private fun TestConnectionButton(
    testing: Boolean,
    result: TestResult?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val ak = LocalAkColors
    val container = when {
        result == null || testing -> MaterialTheme.colorScheme.primary
        result.ok -> ak.successContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when {
        result == null || testing -> MaterialTheme.colorScheme.onPrimary
        result.ok -> ak.onSuccessContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AgentDimens.touchTarget)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled || testing) container else container.copy(alpha = 0.38f)
            )
            .clickable(enabled = enabled || testing, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled || testing) content else content.copy(alpha = 0.38f)
        when {
            testing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = fg
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text("测试中…", style = MaterialTheme.typography.labelLarge, color = fg)
            }

            result?.ok == true -> {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                Text("连接正常", style = MaterialTheme.typography.labelLarge, color = fg)
            }

            result != null -> {
                Text(
                    text = if (result.code > 0) "${result.code} · 连接失败" else "网络异常 · 连接失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg
                )
            }

            else -> {
                Text("测试连接", style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

/** 「测试连接」结果卡（§6.8）：端点 / 指纹 / 响应预览 machineSmall，右下「复制」。 */
@Composable
private fun TestResultCard(
    result: TestResult,
    onCopy: () -> Unit,
    onGetKey: (() -> Unit)? = null,
    onPickModel: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** 成功时的收尾动作（保存并回对话）；null = 不显示该按钮。 */
    onUseAndReturn: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkStatusDot(
                    if (result.ok) LocalAkColors.success else MaterialTheme.colorScheme.error,
                    6.dp
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (result.ok) "连接正常" else "连接失败 · ${result.code}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.ok) LocalAkColors.success else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.weight(1f))
                AkTextButton(
                    text = "复制",
                    onClick = onCopy,
                    icon = Icons.Outlined.ContentCopy,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "端点：${result.endpoint}",
                        style = machineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Key 指纹：${result.fingerprint}",
                        style = machineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (result.bodyPreview.isNotBlank()) {
                        Text(
                            text = "响应：${result.bodyPreview}",
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.suggestion.isNotBlank()) {
                        Text(
                            text = result.suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            // —— P1：把「建议」升级成「下一步」 ——
            // 诊断只给一句话，用户仍要自己找路；这里按归因直接给唯一正确的动作，
            // 避免 429 被当成 Key 错、404 被当成网络问题这类反复试错。
            val action = result.action
            if (!result.ok) {
                when (action) {
                    RecoveryAction.GET_KEY -> if (onGetKey != null) {
                        AkTextButton(
                            text = "去获取 / 更换 Key",
                            onClick = onGetKey
                        )
                    }
                    RecoveryAction.PICK_MODEL -> AkTextButton(
                        text = "选择可用模型",
                        onClick = onPickModel
                    )
                    RecoveryAction.RETRY -> AkTextButton(
                        text = "重试",
                        onClick = onRetry
                    )
                    RecoveryAction.NONE -> Unit
                }
            } else if (onUseAndReturn != null) {
                AkTextButton(text = "保存并回到对话", onClick = onUseAndReturn)
            }
        }
    }
}

/** 复制诊断结果到剪贴板（导出排查用，不动日志文件）。 */
private fun copyToClipboard(ctx: Context, result: TestResult) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = buildString {
        appendLine("结果：${if (result.ok) "连接正常" else "失败 ${result.code}"}")
        appendLine("端点：${result.endpoint}")
        appendLine("Key 指纹：${result.fingerprint}")
        if (result.bodyPreview.isNotBlank()) appendLine("响应：${result.bodyPreview}")
        if (result.suggestion.isNotBlank()) appendLine("建议：${result.suggestion}")
    }
    cm.setPrimaryClip(ClipData.newPlainText("北辰 连接诊断", text))
}

/**
 * 「提示词」Tab：透明展示每轮发往模型的系统提示拼装顺序（只读）。
 *
 * 提示词由内核按「基础规则 → 工具自带提示 → 记忆摘要 → 记忆使用引导」拼装
 * （core/AgentLoop.buildSystemPrompt），当前版本不提供手动编辑——改措辞属于内核行为。
 */
@Composable
private fun PromptTab(config: com.beichen.polaris.skill.ModelConfig) {
    AkGroup(
        title = "系统提示词",
        description = "每轮对话发往模型前按下面的顺序拼装，随对话实时生效",
        paddedContent = true
    ) {
        PromptBlock(
            index = "1",
            title = "基础规则",
            body = DEFAULT_SYSTEM_PROMPT
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        PromptBlock(
            index = "2",
            title = "工具提示",
            body = "已注册工具自带的说明片段（如 use_skill 的技能调用指引），随技能增删动态变化。"
        )
        Spacer(Modifier.height(AgentSpacing.space3))
        PromptBlock(
            index = "3",
            title = "记忆摘要",
            body = "最近 12 条长期记忆、上限 1200 字，每轮注入；写满后由新记忆顶替最早的一条。"
        )
    }
    AkGroup(
        title = "当前生效配置",
        description = "以上提示词由下面的端点与模型执行",
        paddedContent = true
    ) {
        Text(
            text = "端点：${config.baseUrl}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "模型：${config.model}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Key：${if (config.apiKey.isBlank()) "未配置" else "已配置（${config.apiKey.length} 字符）"}",
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 提示词拼装块：序号徽标 + 标题 + mono 正文（surface-1 内嵌块，maxHeight 140dp 可滚动）。 */
@Composable
private fun PromptBlock(index: String, title: String, body: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = index,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(AgentSpacing.space2))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(LocalAkColors.surface1)
                .verticalScroll(rememberPersistentScrollState())
                .padding(AgentSpacing.space3)
        ) {
            SelectionContainer {
                Text(
                    text = body,
                    style = machineText,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
