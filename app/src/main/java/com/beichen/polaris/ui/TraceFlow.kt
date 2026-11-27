package com.beichen.polaris.ui

import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.UiItem

/**
 * 「过程流」的聚合：把一串 UiItem 压成按发生顺序排好的步骤。
 *
 * ## 为什么和渲染分家
 * 边界条件太多了——段在哪断、思考算不算一步、正文那条要不要留、流式中间态怎么算，
 * 每一条错法都不报错，只是「看起来少了一段」或「同一段思考出现两次」。
 * 这些判断必须能**在 JVM 里直接跑**，不能只靠真机看一眼。
 * 所以这里零 Compose 依赖：只 import core 的数据类型，R06 的 harness 直接编它。
 *
 * 渲染在 ChatToolTrace.kt，本文件不出现在任何 @Composable 里。
 *
 * ## 2026-11-26 的第二次整理
 *
 * 上一版能跑，但「段」这件事被算了三遍：[traceRunAt] 往后找段尾、[traceOwnership]
 * 往前找段首、[TraceRun] 里再塞一个 `absorbed` 集合记「谁被吞了」。三处判据必须
 * 永远一致，而它们各自演进——真正咬人的两次都是这个形态：
 *
 *  - 段首的思考被判成 ABSORBED → 整条流没人渲染，**直接消失**；
 *  - `while (next is Tool)` 只收相邻工具 → 思考与工具被切成两块。
 *
 * 现在只有一处定义：[segmentAt] 给出区间，其余全是它的派生。
 * 顺带把「这一条该渲染什么」合成一个返回值 [TraceSlot]，调用方没法只问一半
 * ——问一半正是上面第一个 bug 的成因。
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
    /**
     * 非故障终态的文字说明：「已中断」「已拒绝」。
     *
     * 这两态与「跑完了」共用中性色（见 [TraceState.NEUTRAL]），只靠颜色区分的话
     * 用户看不出这一步是没跑完、还是被自己拒绝了——旧 `ToolStatus` 有文案，
     * 收成一行时漏掉了，等于把「为什么没有结果」这个信息弄丢。
     */
    val note: String? = null,
    /** 工具返回的图片（data URL）。它是产物不是过程，收起时也照常显示。 */
    val images: List<String> = emptyList()
)

/**
 * 一段「过程流」：按发生顺序连起来的思考与工具调用。
 *
 * 与旧版「工具组」的区别在**边界怎么划**。旧版只把相邻的 `UiItem.Tool` 聚在一起，
 * 再捎带吸收紧邻的一条纯思考（还得 `text` 为空）。可真实对话是
 * 「思考 → 工具 → 思考 → 工具」交替着来的，旧判据于是把它们切成
 * 「一堆工具」和「一块思考」两个互不相干的块——用户的原话是
 * 「工具自己一个地方、思考自己一个地方」。
 *
 * 现在边界由**正文**决定：一段流一直延续到某条助手消息交出正文为止。
 * 段内的思考与工具一律按 index 顺序排，不再分"谁的块"。
 *
 * @param range 本段覆盖的下标区间（含两端）。它是「这一段是谁」的唯一表述：
 *   不再另设「被吸收的下标集合」，谁被吸收由区间 + 正文有无现算。
 * @param bodyIndex 交出正文的那条助手消息下标（`-1` = 段尾没有正文）。
 *   它的思考进了流，但正文仍要在自己的位置渲染。
 */
internal data class TraceRun(
    val range: IntRange,
    val steps: List<TraceStep>,
    val toolCount: Int,
    val failedCount: Int,
    val interruptedCount: Int,
    val running: Boolean,
    val bodyIndex: Int
) {
    /** 段内最后一条的下标：判断「这一段还在长」要用它（流式时思考是段的末尾）。 */
    val lastIndex: Int get() = range.last
}

/**
 * [index] 这一条该渲染成什么。
 *
 * 两个字段必须一起看：`run` 非空 = 由它画整条流程；`ownership` 决定助手消息的正文
 * 与思考要不要在这里出现。分开问过一次，后果是段首的思考被两边都判成「别人负责」，
 * 于是那一整段过程在界面上凭空消失。
 */
internal data class TraceSlot(val run: TraceRun?, val ownership: TraceOwnership)

/** 一条助手消息的归属：整条跳过 / 只渲染正文 / 自己全渲染。 */
internal enum class TraceOwnership { NONE, ABSORBED, TAIL }

/** 过程条目：工具调用，或带思考的助手消息（有没有正文都算）。 */
private fun isProcessEntry(item: UiItem?): Boolean = when (item) {
    is UiItem.Tool -> true
    is UiItem.Assistant -> !item.reasoning.isNullOrBlank()
    else -> false
}

/**
 * 这一条后面还能不能接着长流。
 *
 * 与 [isProcessEntry] 差在「有正文的助手消息」：它是段内合法的**最后一步**，
 * 但正文已经交出去了，后面再出现的工具属于下一轮，不能续在这一段里。
 * 少了这层区分，一段流会跨过一条完整回答，把两轮的事缝成一件。
 */
private fun canContinueFlow(item: UiItem?): Boolean = when (item) {
    is UiItem.Tool -> true
    is UiItem.Assistant -> item.text.isBlank() && !item.reasoning.isNullOrBlank()
    else -> false
}

/**
 * [index] 所属那一段的区间；null 表示「这一条不成段」。
 *
 * **段的唯一定义就在这里。** 前后的判据都用同一把尺 [canContinueFlow]：
 * 往前退到不能续流为止，往后走到非过程条目或「交出正文的助手消息」为止。
 *
 * 三种不成段：
 * 1. 段首是「有正文的助手消息」——它是一条回答，正文得在原地，不能被流吞掉；
 * 2. 段内一个工具都没有——那是普通问答，思考仍按「正文上方一行」渲染，
 *    不能为了统一把一次没有工具的回答也折进「过程」里。
 *
 * 参数不是过程条目时返回 null（由调用方先判 [isProcessEntry]）。
 */
private fun segmentAt(messages: List<UiItem>, index: Int): IntRange? {
    if (!isProcessEntry(messages.getOrNull(index))) return null

    var start = index
    while (canContinueFlow(messages.getOrNull(start - 1))) start--
    // 退到头是「交出过正文的助手消息」：它不能当段首，整段不成立。
    val head = messages.getOrNull(start)
    if (head is UiItem.Assistant && head.text.isNotBlank()) return null

    var end = start
    while (true) {
        val next = messages.getOrNull(end + 1) ?: break
        if (!isProcessEntry(next)) break
        end++
        // 交出正文 = 这一轮到此为止（含它，然后停）。它后面若还有工具，属于下一段。
        if (next is UiItem.Assistant && next.text.isNotBlank()) break
    }

    if ((start..end).none { messages[it] is UiItem.Tool }) return null
    return start..end
}

/**
 * 从 [index] 处取一整段过程流；返回 null 表示「这一条不该由自己渲染」。
 *
 * 只有段首拿得到流，段内其余下标返回 null——同一个区间只画一次。
 */
internal fun traceRunAt(messages: List<UiItem>, index: Int): TraceRun? {
    val seg = segmentAt(messages, index) ?: return null
    if (seg.first != index) return null
    return buildRun(messages, seg)
}

/**
 * [index] 处那条助手消息的归属。
 *
 * 三段判据全部从区间现算，没有第二份状态：区间起点自己画流 → NONE（否则没人画）；
 * 无正文 → 整条已进流 → ABSORBED；否则它必是段尾的正文 → TAIL。
 */
internal fun traceOwnership(messages: List<UiItem>, index: Int): TraceOwnership {
    val item = messages.getOrNull(index) as? UiItem.Assistant ?: return TraceOwnership.NONE
    if (item.reasoning.isNullOrBlank()) return TraceOwnership.NONE
    val seg = segmentAt(messages, index) ?: return TraceOwnership.NONE
    return when {
        seg.first == index -> TraceOwnership.NONE
        item.text.isBlank() -> TraceOwnership.ABSORBED
        index == seg.last -> TraceOwnership.TAIL
        // 段中带正文却不在段尾：段尾一定还有一条交过正文的，那这条属于别的段。
        else -> TraceOwnership.NONE
    }
}

/** 一次算完「画不画流」与「正文归谁」，调用方不会只拿到一半。 */
internal fun traceAt(messages: List<UiItem>, index: Int): TraceSlot =
    TraceSlot(run = traceRunAt(messages, index), ownership = traceOwnership(messages, index))

/**
 * 把区间内的条目按发生顺序展开成步骤。
 *
 * 顺序天然正确，因为就是按下标走的——这正是上一版费 249 行想重建的东西。
 * 段内每条助手消息固定「先思考、后正文」，所以同一下标里思考必然在正文之前，
 * 与模型先想后说的顺序一致。
 */
private fun buildRun(messages: List<UiItem>, seg: IntRange): TraceRun {
    val steps = ArrayList<TraceStep>(seg.count())
    val tools = ArrayList<UiItem.Tool>(seg.count())
    var body = -1
    for (i in seg) {
        when (val m = messages[i]) {
            is UiItem.Tool -> {
                steps += toolStep(m)
                tools += m
            }
            is UiItem.Assistant -> {
                steps += thinkingStep(m.reasoning.orEmpty())
                if (m.text.isNotBlank()) body = i
            }
            else -> {}
        }
    }
    return TraceRun(
        range = seg,
        steps = steps,
        toolCount = tools.size,
        failedCount = tools.count { toolState(it) == TraceState.FAILED },
        interruptedCount = tools.count { it.interrupted },
        running = tools.any { it.running },
        bodyIndex = body
    )
}

/** 一段思考做成一步：标题固定「思考」，摘要取首句，全文进详情。 */
private fun thinkingStep(reasoning: String): TraceStep = TraceStep(
    title = "思考",
    summary = thinkingSummary(reasoning),
    detail = reasoning,
    state = TraceState.DONE,
    isThinking = true
)

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
    // 文案与状态同源：判据和 toolState() 用同一对条件，不各写一遍。
    note = when {
        t.interrupted -> "已中断"
        t.isError && t.result.contains("用户拒绝了") -> "已拒绝"
        else -> null
    },
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
