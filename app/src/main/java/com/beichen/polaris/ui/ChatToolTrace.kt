package com.beichen.polaris.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.UiItem

/**
 * 一次 run 的「过程轨迹」：把相邻的工具调用、以及被吸收进来的纯思考，
 * 压成一行 `✓ 3 个工具`，点开才铺开每一步。
 *
 * ## 为什么要有这个文件
 *
 * 旧实现里每一次工具调用都是一张全宽白卡（56dp = 48 触控 + 8 底距），
 * 思考是气泡内另一个折叠块（36dp）。一轮用三个工具，就是
 *
 *     3 × 56 + 2 × 8（卡间距）+ 36（思考块）= 212dp
 *
 * 而它承载的信息量只有「跑了哪三个工具、成功没有」。真机上连排五六张卡时，
 * 一屏几乎全是卡片，正文被挤到看不见——这是「用完了也不收回去，浪费空间」的原话。
 *
 * 改判断的不是「卡片不好看」，是**这张卡不该存在**：工具调用是过程，不是内容。
 * 过程应该像日志一样细、像日志一样可折叠，而不是像消息一样占版面。
 *
 * ## 为什么单独一个文件
 *
 * `ChatScreen.kt` 卡在 3053 行冻结上限（arch-guard [B]，只减不增）。把这块搬出来，
 * 那边删掉 `ToolCard` 与 `ReasoningBlock` 共约 160 行，反而腾出余量。
 *
 * ## 边界
 *
 * 纯渲染。聚合只在 [traceRunAt] 里做，读的是 `List<UiItem>` 的相邻关系，
 * 不写任何状态、不碰落盘格式、不改 `send()` 那条主链。
 */

/**
 * 一步的状态。
 *
 * 只有三种颜色语义：跑（主色）/ 成（success）/ 败（error）。
 * 「已拒绝」「已中断」归 [NEUTRAL]——它们是用户的决定或环境的终止，不是故障，
 * 染成红色会让用户以为出了错（继承 v2.1 R3 的判断）。
 */
internal enum class TraceState { RUNNING, DONE, FAILED, NEUTRAL }

/** 过程中的一步：一次工具调用，或一段思考。 */
internal data class TraceStep(
    /** 工具名（mono）；思考固定为「思考」。 */
    val title: String,
    /** 一行摘要：工具的参数里最有信息量的那个值，或思考的首句。 */
    val summary: String,
    /** 点开单步时显示的正文：工具的入参 + 结果，思考的全文。 */
    val detail: String,
    val state: TraceState,
    val isThinking: Boolean,
    /** 工具返回的图片（data URL）。它是产物不是过程，收起时也照常显示。 */
    val images: List<String> = emptyList()
)

/**
 * 一组相邻的过程步骤。
 *
 * @param absorbedThinking 被本组吸收掉的「纯思考」消息下标（`-1` = 没有）。
 *   列表渲染时那一条要跳过，否则同一段思考会显示两次：一次在组里，一次在它自己的位置。
 */
internal data class TraceRun(
    val steps: List<TraceStep>,
    val toolCount: Int,
    val failedCount: Int,
    val running: Boolean,
    val absorbedThinking: Int
)

/**
 * 从 [index] 处取一组过程步骤；返回 null 表示「这一条不该由自己渲染」。
 *
 * 两种情况返回 null：
 * 1. `index` 不是工具调用（不是本函数的职责）；
 * 2. `index` 是工具调用，但前一条也是工具调用——它属于前一组，由组首统一渲染。
 *
 * 第三件事：如果紧邻前一条是「有思考、无正文」的助手消息，把它吸收进来。
 * 那条消息是模型在决定调工具之前的推理，本来就是这段过程的第一步；
 * 单独占一行会让「思考 → 工具」看起来是两件事。
 */
internal fun traceRunAt(messages: List<UiItem>, index: Int): TraceRun? {
    val head = messages.getOrNull(index) as? UiItem.Tool ?: return null
    if (messages.getOrNull(index - 1) is UiItem.Tool) return null

    val lead = messages.getOrNull(index - 1)
    val absorbed = if (lead is UiItem.Assistant &&
        lead.text.isBlank() &&
        !lead.reasoning.isNullOrBlank()
    ) index - 1 else -1

    var end = index
    while (messages.getOrNull(end + 1) is UiItem.Tool) end++

    val tools = (index..end).map { messages[it] as UiItem.Tool }
    val steps = ArrayList<TraceStep>(tools.size + 1)
    if (absorbed >= 0) {
        steps += TraceStep(
            title = "思考",
            summary = thinkingSummary((lead as UiItem.Assistant).reasoning.orEmpty()),
            detail = lead.reasoning.orEmpty(),
            state = TraceState.DONE,
            isThinking = true
        )
    }
    tools.forEach { steps += toolStep(it) }

    return TraceRun(
        steps = steps,
        toolCount = tools.size,
        failedCount = tools.count { toolState(it) == TraceState.FAILED },
        running = tools.any { it.running },
        absorbedThinking = absorbed
    )
}

/**
 * [index] 处那条「纯思考」是否已被后面的工具组吸收。
 *
 * 判据与 [traceRunAt] 必须完全一致，否则会出现「组里有一份、原地还有一份」，
 * 或者「两边都不显示」——所以两处都从 [isPureThinking] 取判据，不各写一遍。
 */
internal fun thinkingAbsorbedBy(messages: List<UiItem>, index: Int): Boolean {
    val item = messages.getOrNull(index) as? UiItem.Assistant ?: return false
    return isPureThinking(item) && messages.getOrNull(index + 1) is UiItem.Tool
}

/** 只有思考、没有正文的助手消息：它是一段过程，不是一条回答。 */
private fun isPureThinking(item: UiItem.Assistant): Boolean =
    item.text.isBlank() && !item.reasoning.isNullOrBlank()

private fun toolState(t: UiItem.Tool): TraceState = when {
    t.running -> TraceState.RUNNING
    t.interrupted -> TraceState.NEUTRAL
    // 拒绝是用户决策不是故障（core/AgentLoop 回填的固定文案）
    t.isError && t.result.contains("用户拒绝了") -> TraceState.NEUTRAL
    t.isError -> TraceState.FAILED
    else -> TraceState.DONE
}

private fun toolStep(t: UiItem.Tool): TraceStep = TraceStep(
    title = t.call.name,
    summary = summarizeCall(t.call),
    detail = buildString {
        append("入参\n")
        append(t.call.arguments.toString())
        if (!t.running) {
            append("\n\n结果\n")
            append(t.result.take(2_000))
        }
    },
    state = toolState(t),
    isThinking = false,
    images = t.images
)

/**
 * 从参数里挑一个最有信息量的值当摘要。
 *
 * 顺序不是随便排的：路径/命令/查询能让人一眼认出「它在干嘛」，
 * 而 `id`、`timeout` 这类字段几乎每步都有、却什么也没说明。
 * 全都没有时退回整个 JSON——总比空着强，但截断，不然一行里塞进几百字符。
 */
internal fun summarizeCall(call: ToolCall): String {
    val keys = listOf(
        "path", "file_path", "file", "command", "cmd", "query",
        "url", "prompt", "name", "action", "id"
    )
    for (k in keys) {
        val v = call.arguments[k]?.toString()?.trim('"')
        if (!v.isNullOrBlank() && v != "null") return v.oneLine(46)
    }
    val raw = call.arguments.toString()
    return if (raw.isBlank() || raw == "{}") "" else raw.oneLine(46)
}

/** 思考的首句：用户扫一眼就知道「它在想什么」，不需要读全文。 */
internal fun thinkingSummary(reasoning: String): String = reasoning.oneLine(46)

private fun String.oneLine(max: Int): String =
    replace('\n', ' ').replace(Regex("\\s+"), " ").trim().let {
        if (it.length <= max) it else it.take(max - 1) + "…"
    }

/**
 * 一组过程的收起态 + 展开态。
 *
 * 展开初值是「正在跑」：运行中用户要看得到它此刻在调什么，这是过程透明化
 * （Hotfix2 的初衷）。跑完自动折回去——**这是本次改动的核心**，
 * 旧实现里工具卡只有「用户点开」，从不自动收起，于是一屏里堆满历史卡片。
 *
 * 用户手动展开过就不再自动折：他正在看的东西不该被程序合上。
 */
@Composable
internal fun ToolTraceRow(run: TraceRun) {
    var expanded by remember { mutableStateOf(run.running) }
    var touchedByUser by remember { mutableStateOf(false) }
    var openStep by remember { mutableStateOf(-1) }

    // 运行结束 -> 没被用户干预过就折回。用 LaunchedEffect 而不是 remember(running)：
    // 后者会在每次状态变化时重建 state，运行中每来一步就把用户的展开态冲掉。
    LaunchedEffect(run.running) {
        if (!run.running && !touchedByUser) {
            expanded = false
            openStep = -1
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = AkMotionEasing))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 视觉高度 32，点击区给到 44：整行宽 320dp，扁而宽的目标比 48 的方块更好点
                .heightIn(min = 44.dp)
                .clickable {
                    touchedByUser = true
                    expanded = !expanded
                    if (!expanded) openStep = -1
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            StateMark(run)
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = headline(run),
                style = MaterialTheme.typography.bodySmall,
                color = if (run.failedCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Chevron(expanded)
        }
        if (expanded) {
            Column(
                Modifier
                    .padding(start = 7.dp, bottom = AgentSpacing.space1)
                    .drawTraceRail(MaterialTheme.colorScheme.outline)
            ) {
                run.steps.forEachIndexed { i, step ->
                    StepRow(
                        step = step,
                        open = openStep == i,
                        onClick = { openStep = if (openStep == i) -1 else i }
                    )
                }
            }
        }
        // 工具产出的图片不进折叠区：它是这次 run 的**结果**，不是过程日志。
        // 折起来等于让用户以为没截到图（ui_screenshot 最常见的用法）。
        val shots = run.steps.flatMap { it.images }
        if (shots.isNotEmpty()) {
            Column(
                Modifier.padding(top = AgentSpacing.space2, bottom = AgentSpacing.space1),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
            ) {
                shots.forEach { DataImage(it) }
            }
        }
    }
}

/**
 * 独立的思考行：助手消息带思考、但后面没有工具调用时走这里
 * （纯问答的推理、以及带正文的回答在正文上方的那一段）。
 *
 * 视觉与 [ToolTraceRow] 同一套：32 视觉高、13sp 灰、右侧箭头、无卡片。
 * 它和工具组看起来是一类东西——因为它们确实是一类：过程。
 */
@Composable
internal fun ThinkingTraceRow(reasoning: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    var touchedByUser by remember { mutableStateOf(false) }

    LaunchedEffect(streaming) {
        if (!streaming && !touchedByUser) expanded = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = AkMotionEasing))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable {
                    touchedByUser = true
                    expanded = !expanded
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                // 完成态的思考用中性圆点，不用勾：思考没有「成功」这回事
                StateDot(6.dp, LocalAkColors.outlineStrong)
            }
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = if (streaming) "思考中" else "思考 · ${reasoning.length} 字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Chevron(expanded)
        }
        if (expanded) {
            DetailBlock(
                text = reasoning,
                modifier = Modifier.padding(start = 20.dp, bottom = AgentSpacing.space2)
            )
        }
    }
}

// —— 下面是小件，全部无状态 ——

@Composable
private fun StateMark(run: TraceRun) {
    val ak = LocalAkColors
    when {
        run.running -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
        run.failedCount > 0 -> CrossMark(MaterialTheme.colorScheme.error)
        else -> CheckMark(ak.success)
    }
}

@Composable
private fun StateDot(size: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(color)
    )
}

/** 勾用 Canvas 画：不依赖特定图标包，线宽与圆角完全可控（1.8dp / 圆头）。 */
@Composable
private fun CheckMark(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.54f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.84f, size.height * 0.26f)
        }
        drawPath(
            path = p,
            color = color,
            style = Stroke(
                width = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun CrossMark(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.24f, size.height * 0.24f),
            end = Offset(size.width * 0.76f, size.height * 0.76f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.76f, size.height * 0.24f),
            end = Offset(size.width * 0.24f, size.height * 0.76f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AkMotionBase,
        label = "traceChevron"
    )
    Icon(
        imageVector = Icons.Outlined.ExpandMore,
        contentDescription = if (expanded) "收起过程" else "展开过程",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .size(16.dp)
            .rotate(rotation)
    )
}

@Composable
private fun StepRow(step: TraceStep, open: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (step.state) {
                TraceState.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                TraceState.FAILED -> CrossMark(MaterialTheme.colorScheme.error)
                TraceState.NEUTRAL -> StateDot(6.dp, LocalAkColors.outlineStrong)
                TraceState.DONE -> StateDot(6.dp, LocalAkColors.success.copy(alpha = 0.55f))
            }
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = step.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = if (step.isThinking) FontFamily.Default else FontFamily.Monospace
                ),
                color = if (step.state == TraceState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 116.dp)
            )
            if (step.summary.isNotBlank()) {
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = step.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        if (open && step.detail.isNotBlank()) {
            DetailBlock(
                text = step.detail,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = AgentSpacing.space2)
            )
        }
    }
}

/**
 * 内嵌详情块：mono 12sp、surface-1 底、最高 160dp 可滚。
 *
 * 高度上限是硬的：工具结果动辄几千字（一次 `ls -R` 或者整段 HTML），
 * 不限高会让一条结果把整屏顶走，用户以为对话卡住了。
 */
@Composable
private fun DetailBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalAkColors.surface1)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = machineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

/** 收起行的文案：跑的时候说「在跑什么」，跑完只说「跑了几步、错了几个」。 */
private fun headline(run: TraceRun): String = when {
    run.running -> "正在执行"
    run.failedCount > 0 -> "${run.toolCount} 个工具 · ${run.failedCount} 个失败"
    else -> "${run.toolCount} 个工具"
}

/** 展开区的左侧竖线：把散落的步骤连成一段，而不是若干条独立文字。 */
private fun Modifier.drawTraceRail(color: Color): Modifier = this.then(
    Modifier.drawBehind {
        val x = 0.5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height - 8.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }
)
