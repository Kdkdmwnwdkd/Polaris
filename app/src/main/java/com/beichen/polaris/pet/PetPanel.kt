package com.beichen.polaris.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 桌宠的全部视觉：一个会呼吸的小方块，加一个点开才出现的对话气泡。
 *
 * ## 为什么形象是画的
 *
 * spec §4 给了三条路（矢量素材 / Lottie / 序列帧）。这里走第四条：**Canvas 直接画**。
 * 理由是体积与可动性——一个 84dp 的圆角身体 + 两只眼睛 + 一根天线，代码不到 60 行，
 * 零素材、零依赖，而且「眨眼」「呼吸」「思考时上下浮动」都是几个数字，改起来不用重新导出。
 *
 * 代价是它只能长这样了：真要一个像样的角色，还是得有美术资源。等形象定了再换，
 * 现在这版的作用是**证明窗口能活、能拖、能对话**，不是定稿。
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
 * 会让最常见的动作变成三步。菜单本身只有两项，挪进气泡反而更容易被发现。
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
    /** 语音识别出的待发送文本。非空即发送，发完回调 [onVoicePromptConsumed]。 */
    voicePrompt: String?,
    onVoicePromptConsumed: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onReply: (PetReply) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<PetBubble>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

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
            scope.launch {
                val reply = ask(text)
                sending = false
                if (!reply.silent) {
                    messages = messages + PetBubble(reply.text, mine = false, failed = reply.failed)
                }
                onReply(reply)
            }
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
            BubbleCard(
                messages = messages,
                input = input,
                onInputChange = { input = it },
                sending = sending,
                notice = notice,
                voiceState = voiceState,
                onSend = { send(input) },
                onCollapse = { onExpandedChange(false) },
                onMenu = {
                    menuOpen = !menuOpen
                },
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop
            )
            Spacer(Modifier.height(6.dp))
        }

        if (menuOpen) {
            MenuCard(
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

/** 气泡里的一条。 */
private data class PetBubble(val text: String, val mine: Boolean, val failed: Boolean = false)

@Composable
private fun BubbleCard(
    messages: List<PetBubble>,
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    notice: String?,
    voiceState: PetVoiceState,
    onSend: () -> Unit,
    onCollapse: () -> Unit,
    onMenu: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme

    // 展开时自动聚焦输入框：桌宠的主要用法是「点开就说」，
    // 还要用户再点一下输入框是多余的一步。延迟是因为窗口刚去掉
    // FLAG_NOT_FOCUSABLE，系统还没把焦点给到窗口，立刻 requestFocus 会落空。
    //
    // **只跑一次**（key 是 Unit）。二期加进来一个反例：语音「没听清」会展开气泡提示，
    // 若监听 voiceState 就会在那时抢焦点、弹出键盘——用户刚说完话，屏幕上突然冒出
    // 一半屏键盘。想打字的人自己点输入框，一步不多。
    LaunchedEffect(Unit) {
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
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    ),
                    color = scheme.error
                )
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
private fun MenuCard(onOpenApp: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
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
 * 三个动画叠在一起：整体上下浮动（呼吸）、眼睛定时闭合（眨眼）、
 * 思考时头顶那根天线的光点加快闪烁。都是 `rememberInfiniteTransition`，
 * 没有一处依赖业务状态——除了 `thinking`。
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
    val body = if (listening) scheme.primary.copy(alpha = 0.92f) else scheme.primary
    val eye = scheme.onPrimary
    val glow = scheme.primary.copy(alpha = 0.22f)

    val transition = rememberInfiniteTransition(label = "pet")

    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    // 眨眼：4.2 秒一轮，闭合只占 250ms。用 keyframes 而不是「随机」，
    // 因为 Random 会让每帧都重组，且动画不再可预测。
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

    // 天线：在听时最快（300ms 一个来回，接近人紧张时的呼吸），思考时次之，空闲时最慢。
    // 这是「收起态下唯一能表达『它在听』的东西」——气泡没展开时用户看不到任何文字。
    val antenna by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                when {
                    listening -> 300
                    thinking -> 520
                    else -> 1800
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "antenna"
    )

    // 在听时从身体向外扩散的圈。只在录音期间绘制，空闲时是纯计算不画。
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
        val lift = bob * h * 0.03f

        // 录音圈：先画，被身体盖住一半才好看（像从身体后面散出来的）
        if (listening) {
            val cx = w / 2f
            val cy = h * 0.58f
            val r = w * (0.36f + 0.22f * ripple)
            drawCircle(
                color = scheme.primary.copy(alpha = 0.28f * (1f - ripple)),
                radius = r,
                center = Offset(cx, cy)
            )
        }

        val bodyW = w * 0.76f
        val bodyH = h * 0.62f
        val left = (w - bodyW) / 2f
        val top = h * 0.28f - lift

        // 天线
        val antennaX = w / 2f
        val antennaTop = top - h * 0.14f
        drawLine(
            color = body.copy(alpha = 0.85f),
            start = Offset(antennaX, top),
            end = Offset(antennaX, antennaTop),
            strokeWidth = w * 0.022f
        )
        drawCircle(
            color = body.copy(alpha = antenna),
            radius = w * 0.045f,
            center = Offset(antennaX, antennaTop)
        )

        // 落地光晕
        drawOval(
            color = glow,
            topLeft = Offset(left - w * 0.02f, top + bodyH + h * 0.03f),
            size = Size(bodyW + w * 0.04f, h * 0.08f)
        )

        // 身体
        drawRoundRect(
            color = body,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyH * 0.42f, bodyH * 0.42f)
        )

        // 眼睛
        val eyeW = bodyW * 0.12f
        val eyeH = bodyH * 0.2f * blink
        val eyeY = top + bodyH * 0.42f - eyeH / 2f
        val eyeGap = bodyW * 0.2f
        drawRoundRect(
            color = eye,
            topLeft = Offset(w / 2f - eyeGap - eyeW / 2f, eyeY),
            size = Size(eyeW, eyeH.coerceAtLeast(bodyH * 0.02f)),
            cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
        )
        drawRoundRect(
            color = eye,
            topLeft = Offset(w / 2f + eyeGap - eyeW / 2f, eyeY),
            size = Size(eyeW, eyeH.coerceAtLeast(bodyH * 0.02f)),
            cornerRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
        )

        // 嘴：思考时是一条扁椭圆（像在出声），闲置时是一条短线
        if (thinking) {
            drawOval(
                color = eye.copy(alpha = 0.9f),
                topLeft = Offset(w / 2f - bodyW * 0.08f, top + bodyH * 0.66f),
                size = Size(bodyW * 0.16f, bodyH * 0.09f)
            )
        } else {
            drawLine(
                color = eye.copy(alpha = 0.55f),
                start = Offset(w / 2f - bodyW * 0.06f, top + bodyH * 0.7f),
                end = Offset(w / 2f + bodyW * 0.06f, top + bodyH * 0.7f),
                strokeWidth = bodyH * 0.018f
            )
        }
    }
}
