package com.beichen.polaris.ui

import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.extractPromptVariables
import com.beichen.polaris.core.uiKey
import com.beichen.polaris.core.uiKeySeq
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import com.beichen.polaris.core.text.ChatTime
import com.beichen.polaris.core.text.SpeechLogic
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.asr.AsrDirectory
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.beichen.polaris.core.agent.AskUserBroker
import com.beichen.polaris.core.agent.APPROVAL_TIMEOUT_MS
import com.beichen.polaris.core.agent.PlanBroker
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.ModelProfile
import com.beichen.polaris.core.TokenUsage
import com.beichen.polaris.core.llm.ProviderCatalog
import com.beichen.polaris.core.time.Monotonic
import com.beichen.polaris.ui.contract.*
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * W-30：消息操作弹窗的目标。
 *
 * 为什么不用 Triple：四个字段（正文/轮次/角色/时间）+ 消息下标，位置参数读到第三项就开始猜了。
 * @param index 该消息在 `messages` 里的下标（翻译结果按它缓存）
 */
private data class MenuTarget(
    val text: String,
    val turnIndex: Int,
    val role: String,
    val timeMs: Long,
    val index: Int
)

/**
 * 对话页（视觉规范 §6.2 气泡 / §6.3 工具卡 / §6.4 审批 / §6.9 空态 / §6.10 输入栏）。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题 = 当前会话名 + 右上「新对话」），抽屉开关由宿主注入。
 * 消息列与输入栏贴底排布；审批弹窗是对话流中的模态事件（UX v1 D4：审批不是导航项）。
 * 功能三：顶栏标题实时反映当前会话名（首条消息自动命名 / 抽屉长按可重命名），「＋」新建。
 *
 * @param onOpenModel 空态「去配置模型」→ 模型页。
 * @param onOpenGuide 首启引导里的「去开启」→ 权限引导页（P0，2026-10-21）。
 */
@Composable
fun ChatScreen(
    vm: ChatScreenContract,
    // 只读权限三态：只转给 ReadinessCard，不并进 vm（对话页拿不到刷新/跳设置的权力）。
    permission: PermissionReadout,
    onOpenModel: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenContainer: () -> Unit,
    onManagePrompts: () -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streamDraft by vm.streamDraft.collectAsStateWithLifecycle()
    // 功能五（P1-9）：流式推理过程草稿（与 streamDraft 同生命周期的「正在思考」内容）
    val reasoningDraft by vm.reasoningDraft.collectAsStateWithLifecycle()
    val retryState by vm.retryState.collectAsStateWithLifecycle()
    val truncationNotice by vm.truncationNotice.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeConversationId by vm.activeConversationId.collectAsStateWithLifecycle()
    // hotfix30：正在生成的会话 id——非本会话在生成时输入区给说明行
    val generatingConvId by vm.generatingConvId.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val running by vm.isRunning.collectAsStateWithLifecycle()
    // 功能五（P1-7）：提示词库（快捷指令）——输入栏闪电按钮唤起的选择器数据源
    val prompts by vm.prompts.collectAsStateWithLifecycle()
    var showPromptPicker by remember { mutableStateOf(false) }
    // W-RT4：待填变量的提示词（非 null 时弹填槽；null = 无流程）
    var fillPromptItem by remember { mutableStateOf<PromptItem?>(null) }
    val config by vm.config.collectAsStateWithLifecycle()
    // 功能四（录屏反馈「存了多个模型却换不了、不知道在用哪个」）：常驻模型指示条 + 一键切换，不再深埋三层且切换有反馈。
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    // W-30：翻译结果（按消息下标）与正在翻译的下标
    val translations by vm.translations.collectAsStateWithLifecycle()
    val translatingIndex by vm.translatingIndex.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    // 功能五（P1-5）：当前会话累计 token 用量（模型指示条下常驻小条）
    val sessionUsage by vm.sessionUsage.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // W-70：本地推理不填 Base URL / Key，「三要素齐全」的判定会把本地模式误判成未配置，
    // 于是顶栏显示「模型未配置」、点一下还把人送去配置页——真机反馈「切了本地像没用」。
    // 就绪判定统一由 ChatViewModel.modelReady 给出（本地看模型文件、云端看三要素）。
    val isLocalProvider = config.providerId == ProviderCatalog.STYLE_LOCAL_LLAMA
    val localModelName by vm.localActive.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()

    // v6：引导首次启动只弹一次（关掉即落盘）；modelReady 为真时也不再弹。
    val onboardingCtx = LocalContext.current
    // 首次 getSharedPreferences 要同步解析 XML，LaunchedEffect 跑在主线程上，会顶掉一帧。
    fun onboardingPrefs() = onboardingCtx.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(modelReady) {
        if (modelReady) return@LaunchedEffect
        val dismissed = withContext(Dispatchers.IO) { onboardingPrefs().getBoolean(ONBOARDING_KEY, false) }
        if (!dismissed) showOnboarding = true
    }
    val dismissOnboarding: () -> Unit = {
        showOnboarding = false
        onboardingPrefs().edit().putBoolean(ONBOARDING_KEY, true).apply()
    }

    // 贴底跟随规则（上翻即停、回底恢复、流式长高也跟）都在 ChatAutoScroll.kt，那段修过三轮。
    val followTail = remember { FollowTail() }
    FollowTailEffect(listState, messages.size, streamDraft, followTail)

    // 「从中间轮重新发问」待编辑的用户气泡（长按/点击「重新发问」入口置位，确认后截断+重问）
    var reaskTarget by remember { mutableStateOf<UiItem.User?>(null) }
    // 功能五（P1-1）：消息「更多」菜单 / 从此处删除确认的目标
    // （存「正文 → 轮次」二元组而非 UiItem：UiItem 是 sealed 接口，
    //   弹窗体里取不到 text/turnIndex，还要再 when 一遍）
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 功能四：模型切换弹窗开关（指示条点击打开）
    var showModelSwitch by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // 功能五（P1-2）：TTS 朗读（离开对话页即 shutdown，防系统绑定泄漏）
    val tts = remember { TtsController(ctx) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // 正在朗读的消息下标（null = 没在朗读）；再点同一按钮即停止
    var speakingIndex by remember { mutableStateOf<Int?>(null) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    // 功能四（P0-2）/ 功能五（P1-4）：待发附件（图片 + 文件）
    val pendingImages by vm.pendingImages.collectAsStateWithLifecycle()
    val pendingFiles by vm.pendingFiles.collectAsStateWithLifecycle()
    // 附件类型选择菜单（「＋」不再直通相册——用户要的是「发东西」，不只是照片）
    var showAttachMenu by remember { mutableStateOf(false) }
    var localVoice by remember { mutableStateOf(false) }
    val inputScope = rememberCoroutineScope()
    // 语音入口的权限闸门（模型 / 授权 / 永久拒绝三条出路）：实现与理由见 VoiceInputGate.kt
    val startVoiceInput = rememberVoiceInputGate { localVoice = true }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            inputScope.launch {
                val ok = vm.attachImageFromUri(it)
                Toast.makeText(
                    ctx,
                    if (ok) "已添加图片" else "图片读取失败，请重选",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    // 文件选择：OpenDocument + */* —— 具体类型由 VM 按扩展名分派（文本 / PDF / DOCX / 拒绝）
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            inputScope.launch {
                val msg = when (val r = vm.attachFileFromUri(it)) {
                    is FileAttachResult.Text -> "已添加「${r.name}」（${r.chars} 字）"
                    is FileAttachResult.Images -> "已添加 PDF「${r.name}」（${r.pages} 页转图片）"
                    is FileAttachResult.Failed -> r.reason
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // hotfix33：背景图已提升到 App 根层（AppBackgroundLayer）——本页不再自绘，
    // 全局统一（真机反馈「不只是要聊天的地方变，其他地方也要变」）
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        // v6：首次启动引导（Dialog 不占布局空间，放这里只是为了拿到上文的 state）
        OnboardingDialog(
            visible = showOnboarding,
            onGoConfig = {
                dismissOnboarding()
                onOpenModel()
            },
            onOpenGuide = {
                // 不要落「已看过」：用户去看权限时模型可能还没配。这里只跳转，
                // 回到对话页时 ChatScreen 重新组合、标记仍为 false → 引导会再出现，
                // 继续把「先配模型」这件事说完。落标记反而会让人落在空白对话页。
                onOpenGuide()
            },
            onDismiss = dismissOnboarding
        )
        PageTopBar(
            // 功能三：标题 = 当前会话名（找不到摘要时回落「对话」——冷启动首帧也可能为空）
            title = conversations.firstOrNull { it.id == activeConversationId }?.title
                ?: AppDest.CHAT.title,
            actionIcon = Icons.Outlined.Add,
            actionDescription = "新对话",
            // 会话名留在顶栏：30sp 的会话名会和下方的模型条、消息流抢版面，
            // 而这一页的内容本身就是标题——不需要再给它一个标题。
            largeTitle = false,
            // 当前会话已为空时禁用（§6.5.4）：再建也是空会话，无信息增量
            actionEnabled = messages.isNotEmpty(),
            onAction = { vm.newConversation() }
        )
        // 功能四：当前模型指示条——一眼看到「在用哪个模型」，点击即弹切换列表。
        // 未配置时整条变引导态（点此去配置），与空态「去配置模型」同语义。
        ModelIndicatorBar(
            profileName = activeProfile?.name,
            modelName = if (isLocalProvider) {
                localModelName.ifBlank { "未选本地模型" }
            } else {
                config.model
            },
            host = if (isLocalProvider) "设备内推理" else hostOf(config.baseUrl),
            ready = modelReady,
            onClick = {
                if (modelReady) showModelSwitch = true else onOpenModel()
            }
        )
        // 功能五（P1-5）：本会话 token 用量小条（无数据时不占位——供应商没给 usage
        // 或还没聊过，都不显示而非摆一个 0）
        if (sessionUsage.totalTokens > 0) {
            UsageSummaryBar(usage = sessionUsage)
        }
        // 准备清单：只列「还差什么」，全部就绪自动消失。
        // 放在分支之外——空会话和非空会话都该看到，而不是只有第一次才有。
        // 折叠态只占一行，点开才展开；不拦路、不弹窗。
        ReadinessCard(
            vm = permission,
            onOpenContainer = onOpenContainer,
            // v5：横向边距交给 ReadinessCard 自己（20dp，与模型条同一条竖线）；
            // 这里再给 16dp 会让两行的左边缘差 16dp，整块看起来是歪的。
            modifier = Modifier.padding(vertical = AgentSpacing.space1)
        )
        if (messages.isEmpty() && streamDraft == null) {
            EmptyGuide(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            // W-33 顺手：包一层 Box，给「回到底部」按钮留悬浮位。
            // 审计 F17：key 的唯一性不能依赖外部 id（供应商会复用 tool_call id），整表算一遍。
            val messageKeys = remember(messages) { messages.uiKeySeq() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    // W-96：左右 16 → 20dp（16dp 时头像与气泡的圆角几乎贴住屏幕边，像被塞满）。
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    // W-96：底部只留 8dp 时，最后一条气泡的圆角几乎贴着输入栏上沿；
                    // 12dp 让「消息区到此为止」这件事看得出来。
                    top = AgentSpacing.space2,
                    bottom = AgentSpacing.space3
                )
            ) {
                // 同发送者 8dp、换手 24dp（W-96 从 16dp 提起）：3:1 的间距差就是分组的全部线索。
                // R-05：key 用数据主键（工具卡 call.id、气泡轮次下标），免得插一张卡就整表重建。
                // F17 兜底：messageKeys 由 remember(messages) 算，key lambda 却在测量阶段调用，
                // 此时 messages 可能已是下一帧的（刚插入工具卡）——直接索引会越界崩页（模拟器实测）。
                itemsIndexed(messages, key = { index, _ -> messageKeys.getOrNull(index) ?: "fallback-$index" }) { index, item ->
                    // 2026-11-26：工具调用收成一行「过程轨迹」。这里算两件事——
                    // ① 本条的轨迹组（只有组首拿得到，组内其余返回 null）；
                    // ② 本条是否已被别人接管（组内非首条 / 被工具组吸收的纯思考）。
                    // 接管的只跳渲染，不删数据：落盘与模型上下文一个字都没动，
                    // 所以历史会话、导出、续跑全都与改前一致。
                    val trace = if (item is UiItem.Tool) traceRunAt(messages, index) else null
                    if ((item is UiItem.Tool && trace == null) ||
                        (item is UiItem.Assistant && thinkingAbsorbedBy(messages, index))
                    ) {
                        return@itemsIndexed
                    }
                    val prev = messages.getOrNull(index - 1)
                    val gap = when {
                        prev == null -> 0.dp
                        sameSender(prev, item) -> AgentSpacing.space2
                        else -> TURN_GAP
                    }
                    Column(Modifier.padding(top = gap)) {
                        MessageRow(
                            item,
                            // 流式光标只在「末尾且无草稿气泡」时显示；有草稿时草稿自己带光标
                            streaming = running && index == messages.lastIndex && streamDraft == null,
                            trace = trace
                        )
                        // W-30：翻译结果（按消息下标缓存；只显示、不写入会话内容）
                        translations[index]?.let { tr ->
                            TranslationBlock(
                                ui = tr,
                                isUser = item is UiItem.User,
                                onClose = { vm.clearTranslation(index) }
                            )
                        }
                        // W-30：消息时间——旧数据（0）不渲染，避免出现 1970 年
                        val ts = when (item) {
                            is UiItem.User -> item.timeMs
                            is UiItem.Assistant -> item.timeMs
                            else -> 0L
                        }
                        val timeLabel = ChatTime.format(ts, System.currentTimeMillis())
                        // v4：时间戳按**间隔**显示——距上一条不足 5 分钟就不画。
                        // 原来每条消息都带一个「16:11」，一段 20 条的对话就有 20 个时间，
                        // 读起来像运行日志而不是对话；时间只在「隔了一会儿再说」时才有信息量。
                        // 首条必显示（prevTs 为 0），保证每屏开头都有时间锚点。
                        val prevTs = when (prev) {
                            is UiItem.User -> prev.timeMs
                            is UiItem.Assistant -> prev.timeMs
                            else -> 0L
                        }
                        val showTime = timeLabel.isNotEmpty() &&
                            (prevTs <= 0L || ts - prevTs >= TIME_GAP_MS)
                        if (showTime) {
                            Text(
                                text = timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = if (item is UiItem.User) TextAlign.End else TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 2.dp
                                    )
                            )
                        }
                        // Phase 2 DoD②：助手气泡操作区（变体切换器 + 重新生成 + 复制）
                        if (item is UiItem.Assistant) {
                            val showSwitch = item.variantCount > 1
                            val showRegen = !running && index == messages.lastIndex && item.turnIndex >= 0
                            val showCopy = !running && item.text.isNotBlank()
                            // 功能五（P1-1）：落库消息才给「更多」（分享/从此处删除）——
                            // 草稿与失败气泡没有轮次可操作
                            val showMore = !running && item.turnIndex >= 0
                            val showSpeak = !running && item.text.isNotBlank()
                            if (showSwitch || showRegen || showCopy || showMore || showSpeak) {
                                AssistantActions(
                                    variantIndex = item.variantIndex,
                                    variantCount = item.variantCount,
                                    showSwitch = showSwitch,
                                    showRegen = showRegen,
                                    showCopy = showCopy,
                                    showMore = showMore,
                                    showSpeak = showSpeak,
                                    // 错误气泡上把「重新生成」叫「重试」——
                                    // 功能完全一样（都是重发上一轮），但用户看到 401 提示
                                    // 「去改 Key」、改完回来找「重新生成」时对不上号。
                                    // 「重试」是他此刻心理模型里的词。
                                    regenLabel = if (isErrorBubble(item.text)) "重试" else "重新生成",
                                    speaking = speakingIndex == index,
                                    onSwitch = { delta -> vm.switchVariant(item.turnIndex, delta) },
                                    onRegenerate = { vm.regenerate() },
                                    onCopy = { copyMessage(ctx, item.text) },
                                    onMore = {
                                        menuTarget = MenuTarget(
                                            text = item.text,
                                            turnIndex = item.turnIndex,
                                            role = "assistant",
                                            timeMs = item.timeMs,
                                            index = index
                                        )
                                    },
                                    onSpeak = {
                                        if (speakingIndex == index) {
                                            tts.stop()
                                            speakingIndex = null
                                        } else if (tts.speak(item.text)) {
                                            speakingIndex = index
                                        } else {
                                            Toast.makeText(ctx, "朗读器未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        // 功能四（P0-1）：用户气泡操作行——复制 + 从中间轮重新发问
                        // （运行中隐藏；turnIndex < 0 的草稿态气泡只给复制）
                        if (item is UiItem.User && !running) {
                            UserMessageActions(
                                showReask = item.turnIndex >= 0,
                                showMore = item.turnIndex >= 0,
                                onCopy = { copyMessage(ctx, item.text) },
                                onMore = {
                                    menuTarget = MenuTarget(
                                        text = item.text,
                                        turnIndex = item.turnIndex,
                                        role = "user",
                                        timeMs = item.timeMs,
                                        index = index
                                    )
                                },
                                onReask = { reaskTarget = item }
                            )
                        }
                    }
                }
                // 流式草稿气泡（重新生成/发送时 T01 增量实时填入，尚未落库）
                if (streamDraft != null) {
                    item(key = "stream-draft") {
                        // W-96：草稿这一条原来是**零间距**——它不走上面的 itemsIndexed，
                        // 自然也没拿到 gap，于是「正在生成的回答」直接压在提问的下边沿上。
                        // 落库后又按 20dp 排，气泡会往下跳一格。两头都难看，所以这里
                        // 用与落库完全同口径的 gap：生成时什么样，定稿就是什么样。
                        val prev = messages.lastOrNull()
                        val draftGap = when {
                            prev == null -> 0.dp
                            prev is UiItem.Assistant -> AgentSpacing.space2
                            else -> TURN_GAP
                        }
                        Column(Modifier.padding(top = draftGap)) {
                            MessageRow(
                                UiItem.Assistant(streamDraft.orEmpty(), reasoning = reasoningDraft),
                                streaming = true
                            )
                        }
                    }
                }
            }
                // W-33 顺手：不在底部时给一个回到底部的按钮（长对话上翻后一键回最新）
                val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
                if (!atBottom) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = AgentSpacing.space4, bottom = AgentSpacing.space2)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                followTail.forceFollow() // 明确要求回底部 → 恢复自动跟随
                                inputScope.launch {
                                    if (messages.isNotEmpty()) {
                                        // 传 Int.MAX_VALUE 才是「滚到内容末尾」；
                                        // 只传 index 等于把最后一条的**顶部**对齐视口顶端，
                                        // 长消息（几百行思考过程/工具结果）看起来就像"没滚到底"。
                                        // 与上面 W-43 的贴底逻辑保持同一写法。
                                        listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = "回到底部",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        // Phase 2 DoD③：断网/失败自动恢复横幅——重试中显示「正在恢复（n/N）· 原因」，
        // 恢复成功 / 最终失败 / 取消时置 null 即消失（最终失败仍走既有错误气泡，不重复提示）
        retryState?.let { RetryBanner(it) }
        // 输出被长度上限截断：不提示的话「说到一半」与「说完了」在界面上完全一样。
        truncationNotice?.let { InterruptedBanner(it) { vm.dismissTruncationNotice() } }
        // B 批：上次 Agent 长任务被系统中断时的恢复提示。
        // 用户切后台/被回收后回来，能看到「上次做到哪、可以直接说继续」，而不是一片空白。
        vm.interruptedNotice.collectAsStateWithLifecycle().value?.let { notice ->
            InterruptedBanner(notice) { vm.dismissInterruptedNotice() }
        }
        // W-14：模型用 ask_user 提问时，输入栏上方出现选择卡片（回答后自动消失）
        vm.askUserPending.collectAsStateWithLifecycle().value?.let { q ->
            AskUserCard(question = q, onAnswer = { vm.answerAskUser(q.id, it) })
        }
        // W-28：输入框打 @ 时，上方出现工作区文件建议（选中即替换为 @路径）
        val mentionQuery = com.beichen.polaris.core.text.MentionLogic.activeQueryAtEnd(input)
        if (mentionQuery != null) {
            val wsFiles = remember(mentionQuery != null) { vm.workspacePaths() }
            val suggestions = remember(mentionQuery.keyword, wsFiles) {
                com.beichen.polaris.core.text.MentionLogic.match(wsFiles, mentionQuery.keyword, 8)
            }
            if (suggestions.isNotEmpty()) {
                MentionSuggestionBar(
                    files = suggestions,
                    onPick = { path ->
                        // 读改写交给 viewModel 原子完成：自己先读 value 再写回去，
                        // 中间用户若还在打字，后写会覆盖先写（W-31 的老毛病）。
                        vm.editInput { com.beichen.polaris.core.text.MentionLogic.replaceTailWith(it, path) }
                    }
                )
            }
        }
        // W-25：先计划后执行——模型提交计划时，输入栏上方出现计划卡片（批准/拒绝后消失）
        vm.planPending.collectAsStateWithLifecycle().value?.let { plan ->
            PlanCard(
                plan = plan,
                onApprove = { vm.approvePlan(plan.id) },
                onReject = { reason -> vm.rejectPlan(plan.id, reason) }
            )
        }
        ChatInputBar(
            text = input,
            running = running,
            images = pendingImages,
            files = pendingFiles,
            onTextChange = { vm.setInput(it) },
            onSend = {
                // v6：模型没配就把请求打到空地址上，用户只会看到一句「调用失败」。
                // 这里直接说明原因并送他到配置页——比让他自己猜快得多。
                if (modelReady) {
                    // 用户主动发了一条消息 → 他必然想看到结果，强制恢复贴底跟随。
                    // 不加这句的话，点输入框时软键盘弹出会把视口压小、LazyColumn 为保持
                    // 可见项微调滚动位置，那个位移会被上翻判据当成「用户在翻历史」，
                    // 跟随被关掉 —— 消息发出去之后界面一动不动，用户以为卡死了
                    // （真机实测 8 次发送 8 次复现）。
                    followTail.forceFollow()
                    vm.send()
                } else {
                    Toast.makeText(ctx, "先配一个模型才能对话", Toast.LENGTH_SHORT).show()
                    onOpenModel()
                }
            },
            onStop = { vm.stop() },
            onPickAttach = { showAttachMenu = true },
            onVoiceInput = startVoiceInput,
            onRemoveImage = { vm.removePendingImage(it) },
            onRemoveFile = { vm.removePendingFile(it) },
            // hotfix30：后台有其他对话在生成（generatingConvId != 本会话）时，
            // 输入区禁用需有说明——用户切到空闲会话发现发不了消息会以为输入坏了
            runningNotice = if (generatingConvId != null && generatingConvId != activeConversationId)
                "有其他对话正在生成，本对话需等待或停止后才能发送" else null
        )
        }
    }

    // 功能五（P1-7）：快捷指令选择器（点选填入输入框；「管理」进提示词库全功能页）
    // W-RT4：带 {{变量}} 的提示词先弹填槽，填好再进输入框；无变量的保持原来直插路径。
    if (localVoice) {
        LocalVoiceSheet(
            onClose = { localVoice = false },
            onSubmit = { said ->
                vm.editInput { SpeechLogic.appendTo(it, said) }
                localVoice = false
            },
        )
    }

    if (showPromptPicker) {
        PromptPickerDialog(
            prompts = prompts,
            onDismiss = { showPromptPicker = false },
            onPick = { item ->
                showPromptPicker = false
                if (extractPromptVariables(item.content).isEmpty()) {
                    vm.insertPromptIntoInput(item.content)
                } else {
                    fillPromptItem = item
                }
            },
            onManage = {
                showPromptPicker = false
                onManagePrompts()
            }
        )
    }

    // W-RT4：填槽完成才插入输入框——填到一半的模板不进输入框（见 PromptFillDialog 的注释）
    fillPromptItem?.let { fillItem ->
        PromptFillDialog(
            item = fillItem,
            variables = extractPromptVariables(fillItem.content),
            onDismiss = { fillPromptItem = null },
            onConfirm = { filled ->
                vm.insertPromptIntoInput(filled)
                fillPromptItem = null
            }
        )
    }

    // —— Phase 2 DoD②：从中间轮重新发问的编辑对话框 ——
    val reask = reaskTarget
    if (reask != null) {
        ReaskDialog(
            initial = reask.text,
            onDismiss = { reaskTarget = null },
            onConfirm = { newText ->
                val turn = reask.turnIndex
                reaskTarget = null
                vm.reaskFromTurn(turn, newText)
            }
        )
    }

    // —— L10 审批弹窗：挂载点在 AgentKitApp（ApprovalHost），不在这里 ——
    // 审计 F16：它原来挂在本页，只有「对话」被组合时才渲染。模型发起的审批是异步到达的，
    // 用户那时可能停在控制台/设置/权限引导页 → 弹窗不出现 → 30 秒超时被自动拒绝
    // （真机日志：tool.end code=approval_denied dur=30079ms）。搬到全局后与本页无关。

    // —— 功能五（P1-4）：附件类型选择（图片 / 文件） ——
    AkModalHost(target = if (showAttachMenu) true else null) { _, visible ->
        AttachPickerDialog(
            visible = visible,
            onPickImage = { showAttachMenu = false; pickImage.launch("image/*") },
            onPickFile = { showAttachMenu = false; pickFile.launch(arrayOf("*/*")) },
            onOpenPrompts = { showAttachMenu = false; showPromptPicker = true },
            onDismiss = { showAttachMenu = false }
        )
    }

    // —— 功能五（P1-1）：消息更多菜单（分享 / 从此处删除） ——
    AkModalHost(target = menuTarget) { target, visible ->
        MessageActionsDialog(
            visible = visible,
            showDelete = target.turnIndex >= 0,
            bookmarked = vm.isBookmarked(target.role, target.text),
            translating = translatingIndex == target.index,
            onShare = { shareMessage(ctx, target.text); menuTarget = null },
            onToggleBookmark = {
                val added = vm.toggleBookmark(target.role, target.text, target.timeMs)
                Toast.makeText(ctx, if (added) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                menuTarget = null
            },
            onTranslate = {
                vm.translateMessage(target.index, target.text)
                menuTarget = null
            },
            onDelete = { menuTarget = null; deleteTarget = target.text to target.turnIndex },
            onDismiss = { menuTarget = null }
        )
    }
    AkModalHost(target = deleteTarget) { target, visible ->
        AkConfirmDialog(
            title = "从此处删除",
            message = "将删除从这条消息起的全部内容（含本条），删除后不可恢复。",
            confirmText = "删除",
            danger = true,
            icon = Icons.Outlined.Delete,
            visible = visible,
            onConfirm = {
                vm.deleteFromTurn(target.second)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // —— 功能四：模型切换弹窗（录屏反馈「保存了好多个模型，要换的时候不给换」） ——
    // AkModalHost 承载退场动画；点选即 vm.switchProfile 即时生效（VM 同步 _config，
    // 下一轮请求读新配置，见 ChatViewModel.switchProfile），Toast 给可见反馈。
    AkModalHost(target = if (showModelSwitch) true else null) { _, visible ->
        ModelSwitchDialog(
            visible = visible,
            profiles = profiles,
            activeId = activeProfileId,
            onSelect = { id ->
                vm.switchProfile(id)
                val name = profiles.firstOrNull { it.id == id }?.name ?: "该配置"
                Toast.makeText(ctx, "已切换到「$name」", Toast.LENGTH_SHORT).show()
                showModelSwitch = false
            },
            onManage = {
                showModelSwitch = false
                onOpenModel()
            },
            onDismiss = { showModelSwitch = false }
        )
    }
}

/**
 * 功能四：对话页「当前模型」指示条（录屏反馈「都不知道我现在用哪个模型」）。
 *
 * 常驻顶栏下方：图标底座 + 配置名（titleMedium w600）+ 模型名 · 域名（bodySmall），
 * 右侧 ExpandMore 暗示「可展开切换」。整条 52dp 触控（§6.0 48dp 下限 + 图标内边距），
 * 按压 surface-2 + scale 0.97（与 AkListItem 同语言）。
 *
 * 未配置态（[ready] = false）：文案变「模型未配置 · 点此前往配置」，图标底座不升色，
 * 点击走 onOpenModel 引导——与空态「去配置模型」按钮同语义，此处只是多一个常驻入口。
 */
@Composable
private fun ModelIndicatorBar(
    profileName: String?,
    modelName: String,
    host: String,
    ready: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    val bg by animateColorAsState(
        targetValue = if (pressed) LocalAkColors.surface2 else Color.Transparent,
        animationSpec = AkMotionFastColor
    )
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // v5：40dp 图标底座拿掉。这一条是页眉，不是入口卡片——
        // 一个色块会把「你现在用哪个模型」变成「一个可点的小组件」，
        // 而它真正的作用是让用户随时能确认这句话发给了谁。
        if (ready) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = profileName ?: "默认配置",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    ),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (modelName.isBlank()) "未设置模型名" else "$modelName · $host",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = "切换模型",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        } else {
            // v5：未配置时不再写成上下两行（「模型未配置」+「点此前往配置模型」），
            // 那读起来像一个说明段落。一句话说清状态和动作，后面跟一个箭头。
            Text(
                text = "模型未配置",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "去配置",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * 功能五（P1-5）：本会话 token 用量条（模型指示条下常驻，一行小字）。
 *
 * 为什么放这里而不是设置页：用量是「天天要看」的日常数据（这个月跟模型聊了多少、
 * 快到额度了吗），埋在设置里等于没有。
 * 纯展示不可点——交互（清零/导出）留给后续备份功能，不做半成品入口。
 */
@Composable
private fun UsageSummaryBar(usage: TokenUsage) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space5, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缓存命中单独列：它是唯一能证明「省钱这件事真的生效了」的字段。
        // 服务端不报（本地模型 / 部分中转）时不显示，不占位也不显示 0。
        val cached = usage.cachedPromptTokens
        val cacheHint = if (cached > 0) " · 缓存 ${formatTokens(cached)}" else ""
        Text(
            text = "本会话 tokens · ↑ ${formatTokens(usage.promptTokens)} · " +
                "↓ ${formatTokens(usage.completionTokens)} · 共 ${formatTokens(usage.totalTokens)}" +
                cacheHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 加了缓存段后单行放不下：宁可换行，也不要 Ellipsis 把「共多少」截掉
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * token 数格式化（纯函数）：1234 → "1.2k"、1234567 → "1.2M"。
 * 小于 1000 原样展示——用量小的时候 "856" 比 "0.9k" 更精确。
 * 小数分隔符固定 Locale.US——个别 locale 会输出逗号小数点，"1,2k" 不像个数。
 */
internal fun formatTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
}

/**
 * 功能四：「切换模型配置」弹窗（对齐 [AddProfileDialog] 视觉语言：344dp 白卡 +
 * scale/fade 出入场，AkModalHost 承载退场）。
 *
 * 列出全部档案（[AkListItem] 两行结构：配置名 + 模型名 · 域名），当前生效项
 * selected 高亮（primary 图标底座 + primary 标题 + 右侧对勾）。点选即时生效，
 * 无需先保存——[ChatViewModel.switchProfile] 同步落盘，重启不丢失。
 *
 * 底部「管理模型配置」文字按钮进模型配置页（新建/重命名/删除/编辑字段在那里），
 * 本弹窗只承担「快速切换」单一职责，不与配置页职责重叠。
 */
@Composable
private fun ModelSwitchDialog(
    visible: Boolean,
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
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
                // 上下各留 24dp：小屏（360x640dp）上内容比屏幕高，必须给滚动留边界
                .padding(horizontal = 24.dp, vertical = 24.dp),
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
                        text = "切换模型配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "共 ${profiles.size} 个配置 · 点选即时生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            val isActive = profile.id == activeId
                            val host = hostOf(profile.config.baseUrl)
                            val desc = when {
                                profile.config.model.isBlank() && host.isBlank() -> "未填写"
                                profile.config.model.isBlank() -> host
                                else -> "${profile.config.model} · $host"
                            }
                            AkListItem(
                                icon = Icons.Outlined.AutoAwesome,
                                title = profile.name,
                                description = desc,
                                selected = isActive,
                                onClick = { onSelect(profile.id) },
                                trailing = if (isActive) {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "当前生效",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    AkTextButton(
                        text = "管理模型配置",
                        onClick = onManage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 功能四（P0-1）：复制消息正文到剪贴板并给 Toast 反馈。
 *
 * 复制的是**原文**（助手消息为 Markdown 源文本，用户消息为其输入原文）——
 * 与 RikkaHub「copy all」一致；想只拷一段仍可用系统长按选字。
 * 复制到剪贴板是系统行为，用户看不到变化，因此**必须有 Toast**，
 * 否则点了没反应（这类「静默成功」正是之前开关状态回写缺失的同类问题）。
 */
private fun copyMessage(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null || text.isBlank()) return
    manager.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/**
 * 功能五（P1-2）：TTS 朗读控制器（系统 TextToSpeech，零新增依赖）。
 *
 * 生命周期纪律：TTS 必须显式 shutdown，否则会泄漏系统绑定——由调用方在
 * [DisposableEffect] 里挂 [shutdown]（离开对话页即释放）。
 *
 * 初始化是异步的（OnInitListener 回调），因此 [speak] 用 [ready] 守卫并返回
 * 布尔：未就绪时上层提示「朗读器未就绪」，而不是静默失败。
 */
internal class TtsController(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 走进程级仲裁器，而不是自己 new 一个 `TextToSpeech`。
     *
     * 修前这里是独立的引擎实例：主界面朗读不登记 `AudioHolder.Speak`，仲裁器
     * 不知道有声音在响。于是「主界面在念，桌宠同时开麦」两条通道互不可见——
     * 麦克风把播报声录进去，识别结果是一团乱码。同一个进程里有两份 TTS，
     * 就等于有两份「谁在说话」的真相，这在音频上必然出错。
     *
     * `AudioGraph.arbiter` 内部保证单例，且 [AudioGraph.speaker] 是同一个引擎。
     */
    private val arbiter = AudioGraph.arbiter(appContext)

    /** 播报协程的作用域。`Main.immediate`：TTS 与 AudioManager 都要求带 Looper 的线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 提交一句朗读。
     *
     * 返回值只表示「已提交」——引擎就绪与合成结果都是异步的（内置音色要先加载模型）。
     * 失败在协程里用 Toast 说明，不再用「未就绪」把用户挡在门外：
     * 旧实现只看系统引擎的 OnInit，内置音色根本没有那个回调。
     */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        scope.launch {
            arbiter.speak(text).onFailure {
                Toast.makeText(
                    appContext,
                    "朗读失败：${it.message ?: "语音引擎未就绪"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return true
    }

    /** 停止当前朗读（再点一次同一按钮走这里）。走仲裁器，顺带释放播报租约。 */
    fun stop() {
        arbiter.stopSpeech()
    }

    /**
     * 离开对话页：停播报 + 取消本页的协程。
     *
     * 停的是**共享通道**（桌宠若正好在念也会停）。这与改造前的语义一致——
     * 「离开对话页，声音不该留」——而同一个进程里同时有两处朗读本来就不该发生：
     * 仲裁器只会让后一个说话的人等前一个说完。
     */
    fun shutdown() {
        runCatching { arbiter.stopSpeech() }
        scope.cancel()
    }
}

/**
 * 功能五（P1-1）：分享消息正文——走系统分享面板（对标 RikkaHub MessageSheetAction Share）。
 * 机制与 hotfix22 会话导出同源，抽到 [shareText] 共用（ACTION_SEND + text/plain，
 * 零权限；面板无接收方时系统显示空态，不崩）。
 */
private fun shareMessage(context: Context, text: String) = shareText(context, text, "分享消息")

/** 功能四：从 Base URL 提取域名（去 scheme、去路径）；空 URL 返回空串。 */
internal fun hostOf(baseUrl: String): String =
    baseUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .substringBefore("?")

/**
 * 「正在恢复」横幅（Phase 2 DoD③）：供应商瞬时失败自动重试时显示
 * 「正在恢复（n/N）· 原因」；恢复成功 / 最终失败 / 取消时由 VM 将 [ChatViewModel.retryState]
 * 置 null，横幅自动消失。轻量单行（secondaryContainer 底色 + 小号转圈），不抢气泡与输入栏焦点。
 */
/**
 * W-30：译文块——贴在原气泡下方，窄一号、左侧缩进，视觉上明确是「附注」而不是新消息。
 *
 * 用 [MarkdownText] 渲染：译文里常带列表/代码，纯 Text 会把格式压平。
 * 失败时用 error 色写原因，不假装「翻译成功但没有内容」。
 */
@Composable
private fun TranslationBlock(ui: TranslationUi, isUser: Boolean, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 4.dp,
                start = if (isUser) 48.dp else 36.dp,
                end = if (isUser) 0.dp else 48.dp
            )
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAkColors.surface1)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "译文",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "收起",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        if (ui.error != null) {
            Text(
                text = "翻译失败：${ui.error}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            MarkdownText(
                text = ui.text.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RetryBanner(state: RetryState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(AgentSpacing.space2))
        Text(
            text = "正在恢复（${state.attempt}/${state.max}）· ${state.reason}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * B 批：上次长任务被中断的提示横幅。
 *
 * 用 tertiaryContainer 而非 error——这不是错误，是「上次的活没干完，你可以继续」。
 * 右侧「知道了」用于用户明确表示不需要提醒时的关闭。
 */
@Composable
private fun InterruptedBanner(note: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(AgentSpacing.space2))
        AkTextButton(text = "知道了", onClick = onDismiss)
    }
}

/** 两条相邻消息是否同一发送者（用户 / AI / 工具三类，工具卡独占一类）。 */
private fun sameSender(a: UiItem, b: UiItem): Boolean = when {
    a is UiItem.User && b is UiItem.User -> true
    a is UiItem.Assistant && b is UiItem.Assistant -> true
    a is UiItem.Tool && b is UiItem.Tool -> true
    else -> false
}

/**
 * 判断一条助手气泡是不是「错误提示」。
 *
 * 依据是 [ChatViewModel.friendlyMessage] 的固定前缀（🔑/⏳/🔧/⚠️/📶）。
 * 用文案前缀判而不是加字段，是因为错误气泡不参与变体切换、也不落 turn，
 * 为它给 UiItem.Assistant 加一个 isError 字段会连带影响落盘格式与去重逻辑，
 * 收益不抵风险。前缀是这几处唯一出口，改文案时一并改这里即可。
 */
private fun isErrorBubble(text: String): Boolean {
    val t = text.trimStart()
    return t.startsWith("🔑") || t.startsWith("⏳") || t.startsWith("🔧") ||
        t.startsWith("⚠️") || t.startsWith("📶")
}

/**
 * 助手气泡操作区（Phase 2 DoD② + 功能四 P0-1）：变体切换器 `← n/m →` + 「复制」+「重新生成」。
 *
 * 缩进对齐助手气泡（头像 28dp + 间距 8dp = 36dp）；`←`/`→` 用文字而非图标，
 * 规避 material-icons-extended 依赖（本模块只依赖核心图标集）。
 * 序号 1-based 展示（「2/3」比「1/2」更符合用户直觉）。
 *
 * 复制位次：放在「重新生成」**之前**（与 RikkaHub 的写法同序，净室实现）——
 * 两者都是「作用于本条消息」的高频动作，复制比重新生成更常用且无副作用。
 */
@Composable
private fun AssistantActions(
    variantIndex: Int,
    variantCount: Int,
    showSwitch: Boolean,
    showRegen: Boolean,
    showCopy: Boolean,
    showMore: Boolean,
    showSpeak: Boolean,
    regenLabel: String = "重新生成",
    speaking: Boolean,
    onSwitch: (Int) -> Unit,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    onSpeak: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // W-96：气泡圆角 18dp，操作栏贴 2dp 会「啃」进圆角的弧线上；
            // 4dp 让这一行落在气泡下沿之外，视觉上是"附在消息下面"而不是"压在消息身上"
            .padding(start = 36.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCopy) {
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopy)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
            if (showSwitch || showRegen) Spacer(Modifier.width(AgentSpacing.space3))
        }
        if (showSwitch) {
            SwitchArrow(label = "←", enabled = variantIndex > 0) { onSwitch(-1) }
            Text(
                text = "${variantIndex + 1}/$variantCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AgentSpacing.space2)
            )
            SwitchArrow(label = "→", enabled = variantIndex < variantCount - 1) { onSwitch(1) }
        }
        if (showRegen) {
            if (showSwitch) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = regenLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRegenerate)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
        }
        // 功能五（P1-2）：朗读 / 停止朗读（同一按钮切换，文案随状态反转）
        if (showSpeak) {
            if (showCopy || showSwitch || showRegen || showMore) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = if (speaking) "停止朗读" else "朗读",
                style = MaterialTheme.typography.labelMedium,
                color = if (speaking) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSpeak)
                    .padding(horizontal = AgentSpacing.space2, vertical = 6.dp)
            )
        }
        // 功能五（P1-1）：更多（分享 / 从此处删除）。用「⋯」文字而非图标，
        // 与既有 ←/→ 同一套「文字符号代替图标」约定
        if (showMore) {
            if (showCopy || showSwitch || showRegen) Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "⋯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
    }
}

/** 变体切换箭头：可点文字，禁用时置灰 38%；触控 48dp 由外层 Row 高度与点击热区共同承载。 */
@Composable
private fun SwitchArrow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AgentSpacing.space1, vertical = 2.dp)
    )
}

/**
 * 功能四（P0-1）：用户气泡操作行——「复制」+「重新发问」（右对齐小字）。
 *
 * 与助手行共享同一套「可点小字」视觉：labelSmall/labelMedium + primary 主色、
 * radius 8dp clip + 4dp 横向内边距；运行中整行隐藏（避免点到旧轮）。
 * [showReask] = false 的草稿态气泡只保留复制——没有轮次可重问。
 */
@Composable
private fun UserMessageActions(
    showReask: Boolean,
    showMore: Boolean,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    onReask: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // W-96：与 AssistantActions 同口径——两侧操作行都从气泡下沿退 4dp
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "复制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
        )
        if (showReask) {
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "重新发问",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onReask)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
        if (showMore) {
            Spacer(Modifier.width(AgentSpacing.space3))
            Text(
                text = "⋯",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = AgentSpacing.space2, vertical = 2.dp)
            )
        }
    }
}

/**
 * 功能五（P1-1）：消息操作弹窗（分享 / 从此处删除）。
 *
 * 视觉语言与其它对话框一致（AkModalHost + 344dp 白卡 + scale/fade）。
 * 「从此处删除」文案明确写出「及之后的全部内容」——只写「删除」会让用户
 * 以为是删单条（本 App 不提供删单条，见 [ChatViewModel.deleteFromTurn] 的说明）。
 */
@Composable
private fun MessageActionsDialog(
    visible: Boolean,
    showDelete: Boolean,
    bookmarked: Boolean,
    translating: Boolean,
    onShare: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTranslate: () -> Unit,
    onDelete: () -> Unit,
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
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = "消息操作",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space6)
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkListItem(
                        icon = if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        title = if (bookmarked) "取消收藏" else "收藏",
                        description = if (bookmarked) "从收藏里移除" else "存到收藏，删会话也不丢",
                        onClick = onToggleBookmark
                    )
                    AkListItem(
                        icon = Icons.Outlined.Translate,
                        title = if (translating) "翻译中…" else "翻译",
                        description = "用任务模型翻成中文，只显示不写入会话",
                        onClick = onTranslate
                    )
                    AkListItem(
                        icon = Icons.Outlined.Share,
                        title = "分享",
                        description = "发送到其它应用",
                        onClick = onShare
                    )
                    if (showDelete) {
                        AkListItem(
                            icon = Icons.Outlined.Delete,
                            title = "从此处删除",
                            description = "删除这条及之后的全部内容",
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「从中间轮重新发问」编辑对话框（Phase 2 DoD②）。
 *
 * 预填该轮原提问，用户可改后发送；确认即截断该轮之后的对话并新开一轮
 * （见 `ChatViewModel.reaskFromTurn`）。样式对齐审批弹窗的简洁卡面。
 */
@Composable
private fun ReaskDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 344.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "从此处重新发问",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "确认后将截断这一轮之后的对话，并以新问题开一轮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(
                                horizontal = AgentSpacing.space3,
                                vertical = AgentSpacing.space2
                            )
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 5,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                    ) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "发送",
                            onClick = { onConfirm(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 首次启动引导（v6）。
 *
 * 真机反馈：「还不如说直接跳出个弹窗教你怎么弄，写份使用说明」。
 *
 * 一次性、可跳过。只在「模型未配置」且用户没关过它时出现——配好模型后自然不再弹，
 * 所以不需要额外的"完成"判定。关掉即写 seen，之后从对话页再也见不到它。
 *
 * 只讲三件事：选供应商、填 Key、测试连接。其余能力（无障碍、通知）放在末尾一句
 * 灰字里带过，不占步骤——第一次用的人只需要先能对话。
 */
@Composable
private fun OnboardingDialog(
    visible: Boolean,
    onGoConfig: () -> Unit,
    onOpenGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val spec = AkMotionDialog
    val scale by animateFloatAsState(1f, animationSpec = spec)
    val alpha by animateFloatAsState(1f, animationSpec = spec)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                // Dialog 是独立窗口，fillMaxSize 拿到的是**整屏**（含状态栏与导航栏）：
                // 不把这些系统栏让出来，下面的 maxHeight 仍是 640dp，减 16dp 之后
                // 卡片照样溢出屏幕（实测按钮只剩底部 21px 可点）。
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // 卡片高度跟着**实测可用高度**走。
            //
            // 之前写死 520dp，注释按「可用 592dp」估的——那是把 720x1280 @320dpi
            // 当成 360x640dp 后只扣了状态栏，漏掉三键导航栏还要吃掉 ~116dp。
            // 云端 720x1280 实测：卡片居中后底部两个按钮被推出屏幕，
            // uiautomator 里「先自己看看」的 bounds 是 [0,0][0,0]，只能按返回键关。
            //
            // 这里不用 maxHeight - 固定值：Dialog 窗口的 insets 在不同 targetSdk 上
            // 报得不一样（实测 maxHeight 比真实可视区还大 ~74px），减固定值仍会溢出。
            // 取 0.88 留够余量，内容更少时 Surface 自己 wrapContent 缩小。
            val cardMax = (maxHeight * 0.88f).coerceAtLeast(240.dp)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 348.dp)
                    .heightIn(max = cardMax)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(
                    Modifier
                        .padding(22.dp)
                ) {
                  Column(Modifier.weight(1f, fill = false).verticalScroll(rememberPersistentScrollState())) {
                    Text(
                        text = "三步开始",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space1))
                    Text(
                        text = "北辰要接一个大模型才能思考。配好就能用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space5))

                    OnboardingStep(
                        index = "1",
                        title = "选供应商",
                        body = "DeepSeek / Kimi / 智谱 / 通义 / 豆包都行。自建服务选「OpenAI 兼容」，填它的地址。"
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    OnboardingStep(
                        index = "2",
                        title = "填 API Key",
                        body = "到供应商的控制台生成一把，粘贴进「API Key」输入框。"
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    OnboardingStep(
                        index = "3",
                        title = "点「测试连接」",
                        body = "显示成功后回到对话，直接在输入框说要做什么。"
                    )

                    Spacer(Modifier.height(AgentSpacing.space4))
                    // P0：把「操作手机」从一句灰字升级成一条可选岔路——
                    // 想用的人一步到位，只想聊天的人不被拦（引导页本身也可跳过）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "想让它替你操作屏幕？",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AkTextButton(text = "去开启", onClick = onOpenGuide)
                    }

                  }
                    Spacer(Modifier.height(AgentSpacing.space5))
                    AkPrimaryButton(
                        text = "去配置模型",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onGoConfig
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkTextButton(
                        text = "先自己看看",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

/** 引导步骤：左侧序号圆点 + 标题 + 说明。 */
@Composable
private fun OnboardingStep(index: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = index,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(AgentSpacing.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 空态（v6）。
 *
 * 真机反馈原话：「主页进来那里就不要有任何东西了呀」「还不如说直接跳出个弹窗
 * 教你怎么弄，写份使用说明」。
 *
 * 所以这里**什么都不放**：一句问候、一张清单、三个建议，全都是"没内容时填充版面"
 * 的产物。用户第一次打开需要的不是这些，而是一次说清楚「先配模型」的引导——
 * 那件事交给 [OnboardingDialog]，一次性、可跳过、不再占据对话页。
 *
 * 保留这个函数而不是直接删调用点：留下一个明确的落点，将来若要放"会话为空时的
 * 微提示"，改这里一处即可，不用翻调用链。
 */
@Composable
private fun EmptyGuide(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize())
}

/**
 * 对话输入栏（§6.10）：贴屏幕底边缘，玻璃拟态全 App 唯一一处。
 *
 * 容器背景延伸至屏幕底（含手势区）：surface @92% + 顶部 1dp outline 装饰线；
 * 交互元素位于 navigationBarsPadding 之上，内容高 68dp（输入框 48 + 上 8 + 下 8 + 底 4）。
 */
@Composable
private fun ChatInputBar(
    text: String,
    running: Boolean,
    images: List<String>,
    files: List<PendingFile>,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickAttach: () -> Unit,
    // W-30：语音输入（系统识别器）；运行中禁用
    onVoiceInput: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    // hotfix30：后台有其他对话在生成时的一行说明（null = 不显示）——
    // 否则用户切到空闲会话发现发不了消息，会以为输入坏了
    runningNotice: String? = null
) {
    Surface(
        // v5：输入栏与页面同底色，只靠顶部 1dp 线与内容分开。
        // 半透明 surface 会让它像一块「贴在页面上的板子」，而它应该只是页面的底部。
        color = MaterialTheme.colorScheme.background,
        // P2：聚光灯引导的「输入栏」锚点（无 Provider 时是空操作）
        modifier = Modifier.guideAnchor(GuideAnchor.INPUT_BAR)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 顶部 1dp outline 装饰线（玻璃拟态唯一处，§6.10）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            // hotfix30：生成中提示行（有才占位，轻量 label 不抢焦点）
            if (runningNotice != null) {
                Text(
                    text = runningNotice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = AgentSpacing.space3,
                        end = AgentSpacing.space3,
                        top = AgentSpacing.space2
                    )
                )
            }
            // 功能四（P0-2）/ 功能五（P1-4）：待发附件（缩略图行 + 文件行，有才占位）
            if (images.isNotEmpty()) AttachedImagesRow(images, onRemoveImage)
            if (files.isNotEmpty()) AttachedFilesRow(files.map { it.name }, onRemoveFile)
            // 能否发送：文字或附件任一非空，与 ComposerState.canSend 是同一判据。
            val canSend = text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = AgentSpacing.space2,
                        bottom = AgentSpacing.space3
                    )
                    .padding(horizontal = AgentSpacing.space3),
                verticalAlignment = Alignment.Bottom
            ) {
                // 附件与快捷指令合并进「＋」的菜单：原先 ＋ 和 ⚡ 并排两个圆钮，
                // 加号已足够表意，第二个图标只是把 320dp 宽的输入行又占掉一个触摸位。
                RoundIconAction(
                    icon = Icons.Outlined.Add,
                    description = "添加图片、文件或快捷指令",
                    enabled = !running,
                    onClick = onPickAttach
                )
                Spacer(Modifier.width(AgentSpacing.space2))
                // v5：输入框不再是一个灰底胶囊，而是「一条可以在上面写字的空白」。
                //
                // 旧版给输入区铺了 surfaceVariant 底 + 20dp 圆角，整屏最显眼的元素是输入框——
                // 可它其实是「你还没说出口的话」，不该比已经说出口的内容更重。
                // 现在只剩 placeholder 与光标，编辑式排版里这是最自然的一行。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = AgentDimens.touchTarget, max = 120.dp)
                        .padding(
                            horizontal = 2.dp,
                            vertical = AgentSpacing.space2
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 5,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Default
                        ),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "说点什么…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    // W-30：语音输入——只在输入框为空时出现（不遮挡正在编辑的文本）。
                    // 有字时隐藏是设计不是缺陷：键盘自带语音输入，这只用于「空框直接说」。
                    if (text.isBlank() && !running) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onVoiceInput),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = "语音输入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(AgentSpacing.space2))
                if (running) {
                    // 运行中：输入框有内容时**同时**给出发送键。
                    //
                    // 改造前这里只有「停止」——用户在等回答时想改口，必须先点停止、再点发送，
                    // 而他不知道要这么做（发送键压根不在）。现在按发送 = 打断并发送
                    // （仲裁在 ChatViewModel.send 里，这里只负责让按钮够得着）。
                    // 输入为空时仍然只有停止键：那是「我不想等了」的最常见形态。
                    // 打断并发送：判据同 canSend，纯附件也算「有话说」——
                    // 旧版这里只看文字，用户在生成中想发张图按住也发不出去。
                    if (canSend) {
                        ChatSendButton(
                            enabled = true,
                            interrupting = true,
                            onClick = onSend
                        )
                        Spacer(Modifier.width(AgentSpacing.space1))
                    }
                    ChatStopButton(onClick = onStop)
                } else {
                    ChatSendButton(
                        enabled = canSend,
                        onClick = onSend
                    )
                }
            }
        }
    }
}

/**
 * 功能五（P1-4）：附件类型选择（图片 / 文件）。
 *
 * 与其它弹窗同一视觉语言（AkModalHost + 344dp 白卡）。两条路径各自的说明写清
 * 「能发什么」与「怎么被处理」——用户事前就知道 PDF 是当图片读的，
 * 不会事后疑惑「为什么模型没引用原文」。
 */
@Composable
private fun AttachPickerDialog(
    visible: Boolean,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenPrompts: () -> Unit,
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
                Column(Modifier.padding(vertical = AgentSpacing.space4)) {
                    Text(
                        text = "添加",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = AgentSpacing.space6)
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkListItem(
                        icon = Icons.Outlined.Image,
                        title = "图片",
                        description = "从相册选择，视觉模型可直接看图",
                        onClick = onPickImage
                    )
                    AkListItem(
                        icon = Icons.Outlined.Description,
                        title = "文件",
                        description = "PDF / DOCX / 文本类（txt、md、json、csv、代码等）",
                        onClick = onPickFile
                    )
                    // 快捷指令原先是输入栏里独立的 ⚡ 圆钮，与 ＋ 并排各占一个触摸位。
                    // 收进这里之后输入行只剩「＋ … 语音 发送」，宽度和视觉噪声都少一档。
                    AkListItem(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "快捷指令",
                        description = "常用提示词填进输入框（带变量的会先让你填槽）",
                        onClick = onOpenPrompts
                    )
                }
            }
        }
    }
}

/**
 * 输入栏的圆形图标按钮（现在是「＋」）。
 *
 * 这套「触摸目标 44dp + 按压 0.97 微缩 + 禁用置灰 0.38」原先在 ＋ 与 ⚡ 里各写一遍，
 * ⚡ 并进菜单后只剩一个调用点，但仍留成具名组件：形制约定写在注释里的那次，
 * 三周后就会被下一个照抄 `IconButton` 的人悄悄改掉。
 */
@Composable
private fun RoundIconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .background(if (pressed) LocalAkColors.surface2 else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 停止按钮：触控 48dp / 视觉 40dp，error 底 + 24dp on-error 停止图标；运行时常驻可点。 */
@Composable
private fun ChatStopButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AgentDimens.topBarButtonVisual)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "停止",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 发送按钮：触控 48dp / 视觉 40dp，primary 底 + 24dp on-primary 发送图标；输入为空 38% alpha 禁用。
 *
 * @param interrupting true = 此刻正有一轮在跑，按下去会先打断它再发送。图标不变
 *   （用户看到的仍是「发出去」），但 contentDescription 换成「打断并发送」——
 *   读屏用户不会被告知一个不存在的行为，也不会被漏掉一个真实后果。
 */
@Composable
private fun ChatSendButton(enabled: Boolean, interrupting: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        modifier = Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            // P2：聚光灯引导的「发送」锚点
            .guideAnchor(GuideAnchor.SEND_BUTTON)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AgentDimens.topBarButtonVisual)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Send,
                contentDescription = if (interrupting) "打断并发送" else "发送",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 单条消息分发。
 *
 * 2026-11-26：助手消息不再有头像与气泡。两者合计吃掉 36dp 横向空间（头像 28 + 间距 8），
 * 再加上 84% 的限宽，一行正文从 21 字被压回 17 字——长回答成了窄柱。
 * 身份不需要两个记号：用户靠右对齐 + 淡色底，助手靠「没有底色」。
 */
@Composable
private fun MessageRow(item: UiItem, streaming: Boolean, trace: TraceRun? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when (item) {
            is UiItem.User -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                MessageBubble(
                    text = item.text,
                    isUser = true,
                    streaming = false,
                    maxWidth = (maxWidth * 0.76f).coerceIn(240.dp, 400.dp),
                    // 发过的图与文件名：气泡里显示的是缩小后的副本（见 AttachmentViews.userBubbleItem）
                    images = item.images,
                    fileNames = item.fileNames
                )
            }

            is UiItem.Assistant -> Column(Modifier.fillMaxWidth()) {
                // 思考是一段过程，不是正文的一部分：外置成一行，与工具组同一套视觉。
                // 旧实现把它塞在气泡里当折叠块，于是「思考和工具」被拆成两种长得不一样的
                // 东西——用户得分别找两个地方点开。
                if (!item.reasoning.isNullOrBlank()) {
                    ThinkingTraceRow(reasoning = item.reasoning, streaming = streaming)
                }
                MessageBubble(
                    text = item.text,
                    isUser = false,
                    streaming = streaming,
                    maxWidth = maxWidth,
                    // 功能五（P1-5）：token 用量（null = 供应商没给，不显示）
                    usage = item.usage,
                    // 2026-11-18：落盘截断时在正文底部明确标注（0 = 完整，不渲染）
                    truncatedChars = item.truncatedChars
                )
            }

            // 过程轨迹由 itemsIndexed 算好后传进来：这一条若不是组首，trace 为 null，
            // 调用方已经跳过、不会走到这里。
            is UiItem.Tool -> trace?.let { ToolTraceRow(it) }
        }
    }
}

/**
 * 消息气泡。
 *
 * 用户：右对齐、主题容器色单色底、右下 4dp 尾角，一眼是「我说的话」。
 * 助手：**没有气泡、没有头像**，就是正文。
 *
 * ## 为什么撤掉助手气泡（2026-11-26）
 *
 * 底色是 #F7F8FB 的浅灰，白卡是唯一的层次手段，于是每样东西都被迫长成一张卡——
 * 助手的话一张、每次工具调用一张、思考再来一张。一屏翻下来全是卡片边界，
 * 正文反而是最不显眼的东西。底色一分层，卡片就成了必需品；卡片一撤，正文才回到主角。
 *
 * 用户气泡改用 [ColorScheme.primaryContainer] 单色，不再用 9%→28% 的主题色渐变：
 * 藏蓝（R=34）混白之后三个通道被拉平，渐变两端实际是 #EBEEF2 → #C1C9D6 的灰蓝，
 * 中间调糊成一片脏色，看着既不「淡」也不「玻璃」。
 */
@Composable
private fun MessageBubble(
    text: String,
    isUser: Boolean,
    streaming: Boolean,
    maxWidth: Dp,
    /** 功能五（P1-5）：助手气泡的 token 用量（null = 无数据不显示；用户气泡恒 null）。 */
    usage: TokenUsage? = null,
    /**
     * 落盘截断标记：正文被丢弃的字符数（0 = 完整）。
     *
     * 只在「从磁盘读回的会话」上非 0——实时上屏的消息永远是全量。没有这一行时，
     * 重启后看到的是半截回答，用户会以为模型话说一半，冷启动续跑也会基于残文继续。
     */
    truncatedChars: Int = 0,
    /** 用户气泡里的附图缩略图（data URL）与文件名；助手气泡恒空。 */
    images: List<String> = emptyList(),
    fileNames: List<String> = emptyList()
) {
    if (isUser) {
        Column(
            Modifier
                .widthIn(max = maxWidth)
                .clip(USER_BUBBLE_SHAPE)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            BubbleAttachments(
                images = images,
                fileNames = fileNames,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            // 流式时末尾带光标（流式全 App 唯一循环动画，§6.2）
            if (streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkdownText(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StreamCursor()
                }
            } else {
                MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // 功能五（P1-5）：本条回答的 token 用量（↑ 输入 / ↓ 输出）。有数据才渲染
        if (usage != null && usage.totalTokens > 0) {
            Text(
                text = "↑ ${formatTokens(usage.promptTokens)} · " +
                    "↓ ${formatTokens(usage.completionTokens)} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (truncatedChars > 0) {
            Text(
                text = "正文已截断：原文 ${text.length + truncatedChars} 字符，" +
                    "落盘只存前 ${text.length} 字符",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 用户气泡：右下 4dp 是「尾巴」，其余三角 18dp。 */
private val USER_BUBBLE_SHAPE = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)

/**
 * 流式光标：2×14dp primary 方块，800ms 闪烁——**全 App 唯一循环动画**（§6.2）。
 * reduce-motion 下降级为静态（UX v1 §5.4：装饰动效移除，信息由文字承载）。
 */
@Composable
private fun StreamCursor() {
    val reduceMotion = rememberReduceMotion()
    val alpha = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "streamCursor")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "streamCursorAlpha"
        )
        animated
    }
    Box(
        Modifier
            .padding(start = 3.dp)
            .width(2.dp)
            .height(14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary)
    )
}

/**
 * 审批等待秒数，由 [APPROVAL_TIMEOUT_MS] 派生（不单独写数字）。10s 实测不够——读命令、
 * 判断风险、点按钮，真机连试三次全被超时拒绝；30s 够读完一条复杂命令，且仍保留
 * 「自动拒绝」这个 fail-safe。真正执行超时的是 ViewModel 的 awaitApprovalDecision。
 */
private val APPROVAL_TIMEOUT_SEC: Int = (APPROVAL_TIMEOUT_MS / 1000).toInt()

/**
 * 危险操作审批 Dialog（§6.4 招牌组件：简洁但警觉）。
 *
 * 红只出现在 4 处（图标 / 将执行左竖条 / 允许按钮 / 倒计时线），合计 <8% 弹窗面；
 * 标题 18sp w600 正常字重；padding 24dp、块间距 16dp。无扫描线、无遥测读数、无脉冲、无全屏红底。
 *
 * @param visible 退场时置 false：fade 150ms（AkMotionExit）后由宿主卸载。
 */
@Composable
internal fun ApprovalDialog(request: ApprovalRequest, visible: Boolean, vm: ChatScreenContract) {
    val ak = LocalAkColors
    val view = LocalView.current
    var askNoMore by remember(request) { mutableStateOf(false) }

    // 倒计时只做展示（进度条 + 无障碍播报）：真正的超时在 ViewModel 的 awaitApprovalDecision，
    // 弹窗被卸载时那里照样生效。这里**不再**自行 denyApproval——两处实现迟早漂移。
    //
    // 【真机第三轮修复】10s → 30s。
    // 实测连试 3 次、3 次都因超时被自动拒绝（读命令 + 判断风险 + 点按钮，10 秒不够）；
    // 而审批是危险操作的最后一道闸，误拒的代价是"用户以为允许了、实际没执行"，
    // 比多等 20 秒严重得多。
    // 注意：**保留自动拒绝这个 fail-safe**，只是放宽时限——不能因为"太麻烦"就让它永不超时，
    // 否则用户放着弹窗不管时，Agent 会一直卡在等待里。
    var remainingSec by remember(request) { mutableFloatStateOf(APPROVAL_TIMEOUT_SEC.toFloat()) }
    var announced by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, visible) {
        if (!visible) return@LaunchedEffect
        // 倒计时用单调时钟：墙钟回拨会让剩余秒数停在 30 不动（用户以为还有时间，
        // 实际闸门早已该落下）；前跳则会瞬间归零，把还在读风险说明的用户直接拒了。
        val startMs = Monotonic.nowMs()
        while (true) {
            val left = APPROVAL_TIMEOUT_SEC - (Monotonic.nowMs() - startMs) / 1000f
            remainingSec = left.coerceIn(0f, APPROVAL_TIMEOUT_SEC.toFloat())
            if (!announced && left <= 5f) {
                announced = true
                view.announceForAccessibility("${APPROVAL_TIMEOUT_SEC} 秒倒计时，即将自动拒绝，剩余 5 秒")
            }
            if (left <= 0f) break
            delay(100)
        }
    }

    val enterSpec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = enterSpec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = enterSpec)

    Dialog(
        onDismissRequest = { vm.denyApproval() },
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
                    // ① 静态警示图标：36dp 圆 danger-container + 20dp danger 线图标（不呼吸）
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ② 标题：陈述事实，不加大不加粗不变红
                    Text(
                        text = "确认执行危险操作",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Agent 正在请求操控你的手机，执行后不可撤销。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ③ 「将执行」块：surface-1 + 左缘 3dp danger 圆角竖条，mono 换行，最多 5 行可滚动
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ak.surface1)
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.error)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberPersistentScrollState())
                                .padding(AgentSpacing.space4)
                        ) {
                            Text(
                                text = request.toolName,
                                style = machineText.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = request.argsPreview,
                                style = machineText,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ④ 来源行：无来源时显示「本次对话直接调用」，不留空
                    Text(
                        text = "来源：本次对话直接调用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // ⑤ 免询行：文字 + checkbox（视觉 40dp / 触控 48dp）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = AgentDimens.touchTarget),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AkCheckbox(
                            checked = askNoMore && request.grant.memorizable, // R-08：删除类勾了也不记
                            onCheckedChange = { if (request.grant.memorizable) askNoMore = it }
                        )
                        Text(
                            text = request.grant.memoLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { askNoMore = !askNoMore }
                                .padding(start = AgentSpacing.space1)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // ⑥ 按钮行：flex 1 等高，拒绝 Outlined / 允许执行 Danger Filled
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)
                    ) {
                        AkOutlinedButton(
                            text = "拒绝",
                            onClick = { vm.denyApproval() },
                            modifier = Modifier.weight(1f)
                        )
                        AkDangerButton(
                            text = "允许执行",
                            onClick = { if (askNoMore) vm.approveAlways() else vm.approveOnce() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space4))
                    // ⑦ 倒计时：功能性进度（非装饰动效），reduce-motion 下保留
                    Text(
                        text = "${APPROVAL_TIMEOUT_SEC} 秒后自动拒绝",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction = (remainingSec / APPROVAL_TIMEOUT_SEC).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }
        }
    }
}

/**
 * W-28：@提及建议条（横滑 chips）。点一个就把输入框里的 `@关键词` 替换成 `@路径`。
 * 贴输入框上方，与 ask/plan 卡片同一语汇（"正在输入时需要的辅助"），选完即消失。
 */
@Composable
private fun MentionSuggestionBar(files: List<String>, onPick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space1)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = AgentSpacing.space2, vertical = AgentSpacing.space1),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            item {
                Text(
                    "@",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
            items(files) { path ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(path) }
                ) {
                    Text(
                        path,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * W-25：计划审批卡片。贴在输入框上方，不遮挡上下文；批准即开始执行，拒绝可附一句修改意见。
 *
 * 交互取舍：
 *  - 「批准执行」是主按钮（实心），「不要执行」是次按钮（描边）——避免误触批准破坏性计划；
 *  - 拒绝时展开一个输入框写原因（可空），模型据此重新规划；
 *  - 步骤多时卡片内部滚动，不把输入栏挤出屏幕。
 */
@Composable
private fun PlanCard(
    plan: PlanBroker.Plan,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    var rejecting by remember(plan.id) { mutableStateOf(false) }
    var reason by remember(plan.id) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2)
    ) {
        Column(Modifier.padding(AgentSpacing.space4)) {
            Text(
                text = "计划待确认（${plan.steps.size} 步）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = plan.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberPersistentScrollState())
            ) {
                plan.steps.forEachIndexed { i, step ->
                    Text(
                        text = "${i + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(AgentSpacing.space3))
            if (rejecting) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text("哪里不对？可留空") }
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkPrimaryButton(text = "提交意见", onClick = { onReject(reason.trim()) })
                    AkTextButton(text = "返回", onClick = { rejecting = false })
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkPrimaryButton(text = "批准执行", onClick = onApprove, modifier = Modifier.weight(1f))
                    AkTextButton(text = "不要执行", onClick = { rejecting = true }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * ask_user 的选择卡片（W-14）。
 *
 * 为什么不用弹窗：弹窗会遮挡上下文，用户往往需要回看上文才能选；
 * 贴在输入框上方既醒目又不打断阅读，选完即消失（pending 归 null）。
 */
@Composable
private fun AskUserCard(
    question: AskUserBroker.Question,
    onAnswer: (String) -> Unit
) {
    var freeText by remember(question.id) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2)
    ) {
        Column(Modifier.padding(AgentSpacing.space4)) {
            Text(
                text = "需要你确认",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(AgentSpacing.space2))
            Text(
                text = question.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AgentSpacing.space3))
            if (question.options.isNotEmpty()) {
                question.options.forEach { option ->
                    // 【真机第三轮修复】AkTextButton → AkOutlinedButton。
                    // 纯文字按钮看起来就是三个链接（实测截图里 Download/Temp/Cache 分不清
                    // "可以点"还是"只是列出来"），而这里恰恰是要用户做选择的——
                    // 描边按钮给出明确的"可点"外观，整行宽度也把热区放大到 48dp 以上。
                    AkOutlinedButton(
                        text = option,
                        onClick = { onAnswer(option) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("输入回答") }
                    )
                    Spacer(Modifier.width(AgentSpacing.space2))
                    AkTextButton(
                        text = "发送",
                        onClick = { if (freeText.isNotBlank()) onAnswer(freeText.trim()) },
                        enabled = freeText.isNotBlank()
                    )
                }
            }
        }
    }
}

/** 首次启动引导的落盘位置（v6）：关掉一次就不再弹，不需要"已配置"再判一次。 */
private const val ONBOARDING_PREFS = "agentkit.onboarding"
private const val ONBOARDING_KEY = "seen"

/** 相邻两条消息间隔超过这个值才重画时间戳（v4）。 */
private const val TIME_GAP_MS = 5 * 60 * 1000L

/**
 * 换手间距（W-96）：一问一答之间的留白。
 *
 * 原来是 space4（16dp）——手机上 16dp 只有两个字宽，一屏里两个圆角块上下叠着，
 * 读起来像「一整段文字被切成两半」。真机视频里用户的原话是「完全挨在一起」。
 *
 * 现在 24dp，与同侧 8dp 形成 3:1：靠间距差本身就能读出「换人了」，
 * 不需要再画分隔线或加时间戳。24dp 仍能让 393dp 宽的屏一屏放下 4~5 轮。
 */
private val TURN_GAP = AgentSpacing.space6
