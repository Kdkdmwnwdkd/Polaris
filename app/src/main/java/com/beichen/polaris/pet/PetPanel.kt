package com.beichen.polaris.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.beichen.polaris.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 桌宠的全部视觉：一个会呼吸、会眨眼的小人，加一个点开才出现的对话气泡。
 *
 * ## 形象从哪来
 *
 * 最早是 Canvas 现场画的圆角方块（零素材，用来证明窗口能活、能拖、能对话）。
 * 形象定了之后换成两张重绘的位图：`pet_avatar_open` / `pet_avatar_blink`。
 * 画是照参考图重画的，不是把别人的图打进包里——角色设定本身不受保护，
 * 受保护的是「那一张具体的画」。
 *
 * 为什么留着 Canvas 而不是直接 `Image`：录音时那圈扩散的光晕要画在人物**身后**，
 * 而且要和呼吸共用一套动画时间轴。用 `drawImage` 叠在同一块画布上，
 * 一个 `rememberInfiniteTransition` 就够，不必套两层 Composable。
 *
 * ## 交互
 *
 * - 点形象：展开 / 收起气泡
 * - 长按形象：开始听（说完自动发送；点气泡里的麦克风可提前结束）
 * - 拖形象：移动；松手由 [PetOverlayHost.snapToEdge] 吸附到左右边缘
 * - 气泡标题栏「⋯」：弹出「打开主应用 / 关闭桌面助手」
 *
 * **长按原来是菜单，二期让给了语音。** 这不是随意挪动：桌宠的主路径是语音，
 * 「长按说话」在用户心智里就是最自然的那个动作；把它放进二级菜单（点开气泡→找麦克风）
 * 会让最常见的动作变成三步。菜单本身只有三项（清空对话 / 打开主应用 / 关闭），挪进气泡反而更容易被发现。
 *
 * 收起气泡时故意不销毁输入框所在的 composition 之外的协程作用域——
 * [rememberCoroutineScope] 挂在 [PetPanel] 上，用户收起气泡不影响正在跑的那一轮。
 */
@Composable
fun PetPanel(
    expanded: Boolean,
    petSize: Int,
    bubbleWidth: Int,
    /** true = 贴在屏幕右侧。形象跟着贴边方向排，展开时它才不会从边上「跳」到中间。 */
    alignEnd: Boolean,
    ask: suspend (String) -> PetReply,
    /** 当前语音状态。收起态下 `Listening` 会让形象本身进入「在听」的样子。 */
    voiceState: PetVoiceState,
    /** 外部注入的瞬时提示（没权限 / 没听清 / 被占用）。5 秒后由宿主清空。 */
    notice: String?,
    /**
     * 提示附带的动作（如「去授权」）。null = 提示只是文字。
     *
     * 存在的理由：缺麦克风权限时，光给一句「去系统设置」是让用户自己去设置里
     * 找这个 App、找权限项、点开——桌宠没有 Activity，弹不了申请框，
     * 所以必须给一条能一键跳过去的出路。
     */
    noticeAction: (() -> Unit)?,
    /** 语音识别出的待发送文本。非空即发送，发完回调 [onVoicePromptConsumed]。 */
    voicePrompt: String?,
    /**
     * 展开后是否自动把焦点给输入框。
     *
     * 语音唤起与瞬时提示传 false：用户正在说话，弹起半屏键盘既挡视线也盖住反馈。
     * 详见 `PetOverlayHost.autoFocusInput`。
     */
    autoFocusInput: Boolean,
    onVoicePromptConsumed: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onReply: (PetReply) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    /** 是否正在朗读。朗读走进程级仲裁器，宿主从 `TextSpeaker.speaking` 观察后传进来。 */
    speaking: Boolean,
    /** 停止朗读。桌宠的「停」要能止住声音，而不是只把气泡收起来。 */
    onStopSpeaking: () -> Unit,
    /**
     * 回放上次的对话（首次展开时调用一次）。
     *
     * 做成 suspend 而不是「构造时塞一个 List」：读盘是 IO，宿主在 `Service.onCreate`
     * 里建窗口，那一帧不能等磁盘。真正展开时再读，用户感知不到。
     */
    loadHistory: suspend () -> List<PetTurn>,
    /** 清空历史（内存 + 磁盘）。宿主负责落盘那半，界面负责清气泡。 */
    onClearHistory: () -> Unit,
    /**
     * 非空 = 有一张待确认的卡（P1-3 第二阶段）。
     *
     * 从宿主传进来而不是在这里直接订阅 [PetApproval]：这个 Composable 至今没有
     * 读过任何全局单例，保持它「给状态就画」的形状，渲染才能在 harness 里用假数据驱动。
     * 订阅由 [PetOverlayHost] 做——顺带它还得负责「把气泡弹开」这件事，
     * 那是窗口级动作，本来就不在 Composable 的职责里。
     */
    approval: PetApproval.Pending? = null,
    /** 点「允许」。 */
    onApprovalAllow: () -> Unit = {},
    /** 点「不允许」。 */
    onApprovalDeny: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<PetBubble>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    /**
     * 当前这一轮的协程。
     *
     * 存下来是为了「停」：以前 `scope.launch` 的 Job 无人持有，用户想中止只能等模型
     * 自己说完或关掉整个桌宠。桌宠是即时交互，等到 40 秒才出结果等于没有这个入口。
     */
    var job by remember { mutableStateOf<Job?>(null) }

    /** 停：先掐回合，再掐声音。两步都要做——只掐声音，模型还在跑，气泡会突然冒出新回答。 */
    val stopAll: () -> Unit = {
        job?.cancel()
        job = null
        sending = false
        onStopSpeaking()
    }

    /**
     * 发一轮。文字输入与语音走同一条路——语音识别成功后直接调这里，
     * 不另写一套「识别完再发」，否则两条路的 loading / 错误 / 朗读行为会慢慢分叉。
     */
    val send: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isNotEmpty() && !sending) {
            input = ""
            messages = messages + PetBubble(text, mine = true)
            sending = true
            job = scope.launch {
                val reply = ask(text)
                sending = false
                if (!reply.silent) {
                    messages = messages + PetBubble(reply.text, mine = false, failed = reply.failed)
                }
                onReply(reply)
            }
        }
    }

    /**
     * 首次展开时回放上次的对话。
     *
     * 条件里带 `messages.isEmpty()`：用户在读盘完成前已经打字发出去了，回放结果
     * 不能把这条刚发的消息顶掉（`send` 是先往 `messages` 追加、再进协程的）。
     */
    LaunchedEffect(expanded) {
        if (expanded && messages.isEmpty()) {
            messages = loadHistory().map { PetBubble(it.text, mine = it.mine) }
        }
    }

    Column(
        modifier = Modifier.width(if (expanded) bubbleWidth.dp else petSize.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        // 语音识别完成 → 走与打字完全相同的发送路径。
        //
        // 放置位置有讲究：`send` 要改 messages / sending 这些局部状态，只能写在 PetPanel 里。
        // 顺序是「先发后清」——反过来会先触发一次重组（voicePrompt 变 null），
        // LaunchedEffect 重启并取消当前协程，某些情况下会把还没跑到的 `send` 掐掉。
        LaunchedEffect(voicePrompt) {
            val text = voicePrompt ?: return@LaunchedEffect
            send(text)
            onVoicePromptConsumed()
        }

        if (expanded) {
            // 审批卡画在气泡**上方**：它是这一轮的前置条件，不是对话的一部分。
            // 塞进消息列表里会被历史顶上去，用户正说着话根本注意不到。
            if (approval != null) {
                ApprovalCard(
                    pending = approval,
                    onAllow = onApprovalAllow,
                    onDeny = onApprovalDeny
                )
                Spacer(Modifier.height(6.dp))
            }
            BubbleCard(
                messages = messages,
                input = input,
                onInputChange = { input = it },
                sending = sending,
                notice = notice,
                noticeAction = noticeAction,
                voiceState = voiceState,
                autoFocusInput = autoFocusInput,
                onSend = { send(input) },
                onCollapse = { onExpandedChange(false) },
                onMenu = {
                    menuOpen = !menuOpen
                },
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                speaking = speaking,
                busy = sending,
                onStop = stopAll
            )
            Spacer(Modifier.height(6.dp))
        }

        if (menuOpen) {
            MenuCard(
                onClear = {
                    menuOpen = false
                    // 先掐在途回合与朗读：清空后模型还跑着，下一句回答会落在空气泡里
                    stopAll()
                    messages = emptyList()
                    onClearHistory()
                },
                onOpenApp = {
                    menuOpen = false
                    onOpenApp()
                },
                onClose = {
                    menuOpen = false
                    onClose()
                }
            )
            Spacer(Modifier.height(6.dp))
        }

        PetBody(
            sizeDp = petSize,
            thinking = sending,
            voiceState = voiceState,
            onClick = {
                if (menuOpen) {
                    menuOpen = false
                } else {
                    onExpandedChange(!expanded)
                }
            },
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop
        )
    }
}

/**
 * 待确认卡（P1-3 第二阶段）。
 *
 * ## 为什么只有两个按钮、没有「本次会话都允许」
 *
 * 「记住选择」需要一个持久化的策略表（哪些工具永久放行），那是 [Consent] 上一层的事，
 * 现在没有。做一个只在内存里生效的「本次允许」，用户会以为它长期有效，
 * 下次再问时他会点得比这次快——那比每次都问更危险。宁可啰嗦，不要假授权。
 *
 * ## 为什么没有倒计时数字
 *
 * 45 秒的窗口里跳动一个数字，用户的反应是「赶紧点」，而不是「看清楚」。
 * 超时后卡片自己消失、工具结果里写明「没人回应」，信息不缺；
 * 在卡上催促只会把审批变成反射动作。
 */
@Composable
private fun ApprovalCard(
    pending: PetApproval.Pending,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.98f),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "要执行 ${pending.tool} 吗？",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                ),
                color = scheme.primary
            )
            if (pending.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pending.summary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "不允许",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDeny)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.primary)
                        .clickable(onClick = onAllow)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "允许",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = scheme.onPrimary
                    )
                }
            }
        }
    }
}

/** 气泡里的一条。 */
private data class PetBubble(val text: String, val mine: Boolean, val failed: Boolean = false)

@Composable
private fun BubbleCard(
    messages: List<PetBubble>,
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    notice: String?,
    noticeAction: (() -> Unit)?,
    voiceState: PetVoiceState,
    autoFocusInput: Boolean,
    onSend: () -> Unit,
    onCollapse: () -> Unit,
    onMenu: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    speaking: Boolean,
    /** 模型还在跑（`sending`）。与 [speaking] 分开传：两者的「停」是两件事。 */
    busy: Boolean,
    onStop: () -> Unit
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme

    // 展开时自动聚焦输入框：点形象展开的人多半是来打字的，还要他再点一下输入框
    // 是多余的一步。延迟是因为窗口刚去掉 FLAG_NOT_FOCUSABLE，系统还没把焦点给到
    // 窗口，立刻 requestFocus 会落空。
    //
    // **不能无条件跑**。语音唤起（磁贴 / 通知按钮）与「没听清」提示也会展开气泡，
    // 那两种场景下弹出半屏键盘是纯粹的破坏：用户正张着嘴，键盘挡住他自己的视线，
    // 还可能盖住声音反馈。所以 key 取 autoFocusInput，false 时直接不抢焦点。
    //
    // （早先这里写的是 `LaunchedEffect(Unit)`，注释还断言「只跑一次不会抢焦点」——
    //   那是错的：收起态不组合 BubbleCard，展开时是全新组合，Unit 照样会跑一遍。
    //   二期「没听清」时莫名其妙弹键盘就是这么来的。）
    LaunchedEffect(autoFocusInput) {
        if (!autoFocusInput) return@LaunchedEffect
        delay(160)
        runCatching { focus.requestFocus() }
    }

    // 新消息到达时滚到底
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.lastIndex) }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "北辰",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = scheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 「停」只在真的有事可停时出现：说话中或思考中。
                    // 常驻一个灰色「停」会让用户以为它坏了（点了没反应）。
                    if (speaking || busy) {
                        Text(
                            text = "停",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = scheme.error,
                            modifier = Modifier
                                .pointerInput(Unit) { detectTapGestures { onStop() } }
                                .padding(horizontal = 8.dp)
                        )
                    }
                    Text(
                        text = "⋯",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .pointerInput(Unit) { detectTapGestures { onMenu() } }
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        text = "收起",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures { onCollapse() }
                        }
                    )
                }
            }

            // 瞬时提示（没权限 / 没听清 / 麦克风被占）。宿主 5 秒后清空，
            // 但「没听清」这类提示用户可能正在读，所以不设自动消失的定时器在这里。
            if (!notice.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                if (noticeAction != null) {
                    // 有出路时整条提示可点。做成「文字 + 箭头」而不是纯文字：
                    // 纯文字的提示没人会意识到能点，加个箭头才是明确的邀请。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = noticeAction)
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            ),
                            color = scheme.error,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                } else {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = scheme.error
                    )
                }
            }

            if (messages.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "问点什么。答案会念出来。",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 208.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages) { m -> BubbleRow(m) }
                }
                Spacer(Modifier.height(6.dp))
            }

            // 一眼能看出「它现在在干什么」。三件事互斥，所以写成一条状态行，
            // 不各占一行——气泡只有 296dp 宽，堆三行会把消息区挤没。
            val capturing = voiceState is PetVoiceState.Arming ||
                voiceState is PetVoiceState.Listening ||
                voiceState is PetVoiceState.Transcribing
            when {
                voiceState is PetVoiceState.Arming -> {
                    Text(
                        text = "准备中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                voiceState is PetVoiceState.Listening -> {
                    // 有实时文本就显示它（用户能立刻看到「识别到了」），
                    // 没有就显示省略号——部分引擎前几百毫秒什么都不给。
                    Text(
                        text = voiceState.partial.ifBlank { "在听…" },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = scheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                }
                voiceState is PetVoiceState.Transcribing -> {
                    Text(
                        text = "识别中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                sending -> {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = scheme.onSurface
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    text = when {
                                        capturing -> "在听，说完自动发"
                                        sending -> "等它说完…"
                                        else -> "说点什么…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f)
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(6.dp))
                // 麦克风：点一下开始听，再点一下结束。**不是**「按住」。
                //
                // 为什么不做按住：语音识别自带静音判定（引擎认为说完 → onResults），
                // 由它决定「什么时候说完了」比手指按住更准——句子中间的停顿不会被切掉。
                // 而且「按住」需要一个抬手事件，而 Compose 的 AwaitPointerEventScope 是
                // `@RestrictsSuspension` 的，拿不到（这一版第一稿就死在 CI 上）。
                //
                // 正在请求回答时禁用：二期不做「说话打断回答」，桌宠的气泡只有一间房。
                val recording = voiceState is PetVoiceState.Arming ||
                    voiceState is PetVoiceState.Listening
                val micEnabled = !sending && voiceState !is PetVoiceState.Transcribing
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (recording) scheme.primary else scheme.surfaceVariant.copy(alpha = 0.95f)
                        )
                        .pointerInput(micEnabled, recording) {
                            if (micEnabled) {
                                detectTapGestures { if (recording) onVoiceStop() else onVoiceStart() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = if (recording) "结束说话" else "语音输入",
                        tint = if (recording) scheme.onPrimary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = input.isNotBlank() && !sending
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) scheme.primary else scheme.onSurface.copy(alpha = 0.12f)
                        )
                        .pointerInput(canSend) {
                            if (canSend) detectTapGestures { onSend() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↑",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        color = if (canSend) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleRow(bubble: PetBubble) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (bubble.mine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .bubbleMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        bubble.mine -> scheme.primary.copy(alpha = 0.14f)
                        bubble.failed -> scheme.errorContainer.copy(alpha = 0.5f)
                        else -> scheme.surfaceVariant.copy(alpha = 0.6f)
                    }
                )
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = bubble.text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = if (bubble.failed) scheme.error else scheme.onSurface,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 气泡单条的最大宽度：86% 留出「谁在说」的左右错位空间。
 *
 * 不叫 `widthIn()`：那会与 `androidx.compose.foundation.layout.widthIn` 同名，
 * 后来的人 import 一下就成了另一件事。
 */
private fun Modifier.bubbleMaxWidth(): Modifier = this.fillMaxWidth(0.86f)

@Composable
private fun MenuCard(onClear: () -> Unit, onOpenApp: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // 历史落盘之后必须有「清空」：没有它，用户唯一的删除手段是去系统设置里
            // 清应用数据，那会把主界面的会话、配置、工作区一起清掉
            MenuRow("清空对话", scheme.onSurface, onClear)
            MenuRow("打开主应用", scheme.onSurface, onOpenApp)
            MenuRow("关闭桌面助手", scheme.error, onClose)
        }
    }
}

@Composable
private fun MenuRow(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = color
        )
    }
}

/**
 * 形象本体。
 *
 * ## 从「代码画的」换成「画好的图」
 *
 * 上一版是 Canvas 现场画的圆角方块，作用是证明窗口能活、能拖、能对话，不是定稿。
 * 形象定下来后换成两张位图：`pet_avatar_open` / `pet_avatar_blink`，
 * 384×384 WebP，28KB 与 27KB。两张是同一张底图重绘出来的，构图逐像素对齐
 * （`getbbox()` 完全一致），所以眨眼只是换图，不会位移、不会抖。
 *
 * ## 保留的三个动画
 *
 * 上下浮动（呼吸）、定时换图（眨眼）、听声时向外扩散的圈，都挂在
 * `rememberInfiniteTransition` 上，不依赖业务状态——除了 `thinking`。
 *
 * 画布背景是透明的：`TYPE_APPLICATION_OVERLAY` 窗口上没有底色，
 * 图周围那圈留白必须是 alpha=0，否则会在桌面上贴出一个白方块。
 *
 * ## 尺寸
 *
 * 位图四周留了 6% 透明边，所以 96dp 窗口里人物约占 90dp。
 * 上一版方块的身体只占窗口的 62%，换成图之后同样窗口会显得大一圈——
 * 这也是 [PET_SIZE_DP] 84 → 96 看着并不突兀的原因。
 */
@Composable
private fun PetBody(
    sizeDp: Int,
    thinking: Boolean,
    voiceState: PetVoiceState,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val listening = voiceState is PetVoiceState.Arming || voiceState is PetVoiceState.Listening

    // 两张图常驻内存：加起来 55KB 解码后约 1.2MB，切换时不重新解码，
    // 才不会在眨眼那一刻掉一帧。
    val open = ImageBitmap.imageResource(R.drawable.pet_avatar_open)
    val closed = ImageBitmap.imageResource(R.drawable.pet_avatar_blink)

    val transition = rememberInfiniteTransition(label = "pet")

    // 呼吸。思考时快一倍——这是收起态下唯一能表达「它在跑」的东西，
    // 因为气泡没展开时用户看不到任何文字。
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (thinking) 900 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    // 眨眼：4.2 秒一轮，闭合只占 280ms。用 keyframes 而不是「随机」，
    // 因为 Random 会让每帧都重组，且动画不再可预测。
    // 值是「睁眼那张的不透明度」：1 = 完全睁着，0.06 = 基本闭上。
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4200
                1f at 0
                1f at 3700
                0.06f at 3820
                1f at 3980
            }
        ),
        label = "blink"
    )

    // 在听时从人物向外扩散的圈。
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    Canvas(
        modifier = Modifier
            .size(sizeDp.dp)
            .pointerInput(thinking) {
                detectTapGestures(
                    onTap = { onClick() },
                    // 长按 = 开始听。
                    //
                    // **没有「松手结束」**：Compose 的 `AwaitPointerEventScope` 标注了
                    // `@RestrictsSuspension`，在里面拿不到抬手事件，也不能调 `coroutineScope`
                    // 起计时器（第一版就是这么写的，CI 两处报
                    // "Restricted suspending functions can only invoke ..."）。
                    //
                    // 但这反而更对：语音识别自带静音判定（说完 → onResults），
                    // 由它决定结束时机比手指按住更符合说话节奏——句中的停顿不会被切掉。
                    // 想提前结束就点气泡里的麦克风（toggle）。
                    //
                    // `thinking` 时不响应：正在等回答，二期不做「说话打断回答」。
                    onLongPress = { if (!thinking) onVoiceStart() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val lift = bob * h * 0.025f

        // 背后的光晕：听声时最亮（跟着呼吸闪），思考次之，空闲只留一点。
        // 没有它，深色壁纸上一张深蓝头发的人物会糊进背景里。
        val halo = when {
            listening -> 0.32f * (1f - ripple * 0.45f)
            thinking -> 0.18f + 0.12f * bob
            else -> 0.08f
        }
        drawCircle(
            color = scheme.primary.copy(alpha = halo),
            radius = w * 0.44f,
            center = Offset(w / 2f, h * 0.60f)
        )

        // 录音圈：从人物身后散出来
        if (listening) {
            drawCircle(
                color = scheme.primary.copy(alpha = 0.26f * (1f - ripple)),
                radius = w * (0.34f + 0.26f * ripple),
                center = Offset(w / 2f, h * 0.60f)
            )
        }

        val side = w * 0.98f
        val left = (w - side) / 2f
        val top = h - side - lift

        // 落地阴影：把它按在桌面上，而不是悬空贴图。
        // 画在图**之前**，被脚和尾巴压住一半才自然。
        drawOval(
            color = Color.Black.copy(alpha = 0.12f),
            topLeft = Offset(left + side * 0.13f, top + side * 0.885f),
            size = Size(side * 0.60f, side * 0.055f)
        )

        // 两张图交叉淡化：blink 是睁眼那张的 alpha，闭眼那张拿剩下的。
        // 这样 280ms 的闭合是渐变而不是硬切，小尺寸下不容易看出「跳帧」。
        val dstSize = IntSize(side.toInt(), side.toInt())
        val dstOffset = IntOffset(left.toInt(), top.toInt())
        drawImage(
            image = open,
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = blink,
            filterQuality = FilterQuality.High
        )
        if (blink < 1f) {
            drawImage(
                image = closed,
                dstOffset = dstOffset,
                dstSize = dstSize,
                alpha = 1f - blink,
                filterQuality = FilterQuality.High
            )
        }
    }
}
