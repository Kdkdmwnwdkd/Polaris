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
 * @param absorbed 已并入本流、整条不该再渲染的条目（纯思考消息）。
 * @param tailIndex 交出正文的那条助手消息下标（`-1` = 段尾没有正文）。
 *   它的思考进了流，但正文仍要在自己的位置渲染——所以不在 [absorbed] 里。
 */
internal data class TraceRun(
    val steps: List<TraceStep>,
    val toolCount: Int,
    val failedCount: Int,
    /** 被中断的步数：它不是失败，但「没跑完」得让用户在收起态也看得见。 */
    val interruptedCount: Int,
    val running: Boolean,
    val absorbed: Set<Int>,
    val tailIndex: Int,
    /** 段内最后一条的下标：判断「这一段还在长」要用它（流式时思考是段的末尾）。 */
    val lastIndex: Int
)

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
 * 从 [index] 处取一整段过程流；返回 null 表示「这一条不该由自己渲染」。
 *
 * 四种 null：
 * 1. 不是过程条目（用户消息、只有正文的助手消息）；
 * 2. 自己是「有正文的助手消息」——它是一条回答，正文得在原地渲染，
 *    不能让它当流首（否则正文被流吞掉）；
 * 3. 前面那一条还能续流——这条属于前一段，由段首统一渲染；
 * 4. **整段里一个工具都没有**：那是普通问答，思考仍按「正文上方一行」渲染。
 *    不能为了统一，把一次没有工具的回答也折进「过程」里。
 */
internal fun traceRunAt(messages: List<UiItem>, index: Int): TraceRun? {
    val head = messages.getOrNull(index) ?: return null
    if (!isProcessEntry(head)) return null
    if (head is UiItem.Assistant && head.text.isNotBlank()) return null
    if (canContinueFlow(messages.getOrNull(index - 1))) return null

    var end = index
    var hasTool = head is UiItem.Tool
    while (true) {
        val next = messages.getOrNull(end + 1) ?: break
        if (!isProcessEntry(next)) break
        end++
        if (next is UiItem.Tool) hasTool = true
        // 交出正文 = 这一轮到此为止（含它，然后停）。它后面若还有工具，属于下一段。
        if (next is UiItem.Assistant && next.text.isNotBlank()) break
    }
    if (!hasTool) return null

    val steps = ArrayList<TraceStep>(end - index + 1)
    val absorbed = HashSet<Int>()
    var tail = -1
    for (i in index..end) {
        when (val m = messages[i]) {
            is UiItem.Tool -> steps += toolStep(m)
            is UiItem.Assistant -> {
                steps += thinkingStep(m.reasoning.orEmpty())
                // 无正文：整条就是一段思考，已经进流了，原地不必再显示一次。
                // 有正文：思考进流，正文留在原地（tailIndex）。
                if (m.text.isBlank()) absorbed += i else tail = i
            }
            else -> {}
        }
    }

    val tools = (index..end).mapNotNull { messages[it] as? UiItem.Tool }
    return TraceRun(
        steps = steps,
        toolCount = tools.size,
        failedCount = tools.count { toolState(it) == TraceState.FAILED },
        interruptedCount = tools.count { it.interrupted },
        running = tools.any { it.running },
        absorbed = absorbed,
        tailIndex = tail,
        lastIndex = end
    )
}

/** 一条助手消息的归属：整条跳过 / 只渲染正文 / 自己全渲染。 */
internal enum class TraceOwnership { NONE, ABSORBED, TAIL }

/**
 * [index] 处那条助手消息，是否已并进前面某一段过程流。
 *
 * 判据从 [traceRunAt] 反推，不另写一套：两边各写一遍就会漏，
 * 要么流里有一份、原地还有一份，要么两边都不显示。
 */
internal fun traceOwnership(messages: List<UiItem>, index: Int): TraceOwnership {
    val item = messages.getOrNull(index) as? UiItem.Assistant ?: return TraceOwnership.NONE
    if (item.reasoning.isNullOrBlank()) return TraceOwnership.NONE
    // 自己是段首：整条流由这条负责渲染，不算「被别人吸收」——否则段首是思考时，
    // 流会连一条步骤都显示不出来。
    if (traceRunAt(messages, index) != null) return TraceOwnership.NONE
    var start = index
    // 与 traceRunAt 的段首判据同一把尺：能续流才往前退，交出过正文的助手消息是墙。
    while (canContinueFlow(messages.getOrNull(start - 1))) start--
    val run = traceRunAt(messages, start) ?: return TraceOwnership.NONE
    return when {
        run.absorbed.contains(index) -> TraceOwnership.ABSORBED
        run.tailIndex == index -> TraceOwnership.TAIL
        else -> TraceOwnership.NONE
    }
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

