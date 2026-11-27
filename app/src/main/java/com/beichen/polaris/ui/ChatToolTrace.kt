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
 * 纯渲染。聚合只在 [traceRunAt] 里做，读的是 `List<UiItem>` 的发生顺序，
 * 不写任何状态、不碰落盘格式、不改 `send()` 那条主链。
 */

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
internal fun ToolTraceRow(run: TraceRun, live: Boolean = false) {
    // active：这一段正长到消息列表末尾。模型思考时还没有新工具进来（tools.running 全 false），
    // 光看 run.running 会在「思考刚出现」那一刻就折回去，用户看不到它在想什么。
    val active = run.running || live
    var expanded by remember { mutableStateOf(active) }
    var touchedByUser by remember { mutableStateOf(false) }
    var openStep by remember { mutableStateOf(-1) }

    // 正在想的那一步默认摊开：它是整条流里唯一还在变的文字。
    // 折起来用户只能盯着「思考中」三个字，等于把实时推理藏了。
    val liveStep = run.steps.indexOfLast { it.isThinking && it.state == TraceState.RUNNING }
    LaunchedEffect(liveStep) {
        if (liveStep >= 0 && !touchedByUser) openStep = liveStep
    }

    // 运行结束后的收起规则（2026-11-27 改）：
    //
    // 旧规则是「跑完一律折回一行」。真机上被骂的正是这一点——一轮跑完，思考与工具
    // 合成「2 次工具调用」一行，**链本身看不见了**，用户以为过程被删了。
    // 现在的规则：**带思考的流跑完保持展开**（那正是他要看的东西，一步一行，并不长）；
    // 只有「全是工具、一句思考都没有」的流才自动收起，那种流看头就知道发生了什么。
    //
    // 用 LaunchedEffect 而不是 remember(active)：后者会在每次状态变化时重建 state，
    // 运行中每来一步就把用户的展开态冲掉。
    LaunchedEffect(active) {
        if (!active && !touchedByUser && run.steps.none { it.isThinking }) {
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
            StateMark(run, active)
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = headline(run, active),
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
private fun StateMark(run: TraceRun, active: Boolean) {
    val ak = LocalAkColors
    when {
        active -> CircularProgressIndicator(
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
            // 「已中断」「已拒绝」：收起态也要说得清这一步为什么没有结果。
            step.note?.let {
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
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
private fun headline(run: TraceRun, active: Boolean): String = when {
    active -> "正在执行"
    run.failedCount > 0 -> "${run.toolCount} 次工具调用 · ${run.failedCount} 个失败"
    // 中断不是失败，但也不能显示成「跑完了」：收起态一行里就要能看出来。
    run.interruptedCount > 0 -> "${run.toolCount} 次工具调用 · 已中断"
    // 没有工具的轮次（只有思考、或思考 + 途中说明）也得给一个可点开的由头，
    // 否则收起行写着「0 次工具调用」——用户会以为那一段是空的。
    run.toolCount == 0 -> "已完成 · ${run.steps.size} 步"
    else -> "已完成 · ${run.toolCount} 次工具调用"
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
