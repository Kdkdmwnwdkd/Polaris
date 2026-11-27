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
 * 现在边界是**用户消息**：一段流就是一轮（到下一条用户消息为止）。
 * 段内的思考、工具、途中说明一律按 index 顺序排，不再分"谁的块"。
 *
 * @param range 本段覆盖的下标区间（含两端）。它是「这一段是谁」的唯一表述：
 *   不再另设「被吸收的下标集合」，谁被吸收由区间 + 回答位置现算。
 * @param bodyIndex 本轮**回答**的下标（最后一条有正文的助手消息；`-1` = 没交正文）。
 *   只有它留在流外当气泡，其余有正文的步都作为「说明」进流；
 *   动作行（复制 / 朗读）也只属于它一条。
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
internal data class TraceSlot(
    val run: TraceRun?,
    val ownership: TraceOwnership,
    /**
     * 这一条的**正文**要不要画。
     *
     * 新形态：段首也可能只是一句途中说明（模型先说一句、再调工具）。这时流由它来画，
     * 正文已经作为「说明」进了流——再画一遍，同一句话就在屏幕上出现两次。
     * `ownership` 管不了这件事：段首必须是 NONE（否则整条流没人画）。
     */
    val drawBody: Boolean
)

/** 一条助手消息的归属：整条跳过 / 只渲染正文 / 自己全渲染。 */
internal enum class TraceOwnership { NONE, ABSORBED, TAIL }

/**
 * 一轮的边界：**用户消息是墙**。
 *
 * 同一轮 = 用户消息之后、下一条用户消息之前的所有助手 / 工具条目。
 * 两轮之间必然隔着一条用户消息，所以「墙」不需要第二个判据——拿正文当墙会误伤
 * 「我先查一下」+ tool_call 这种同一条消息里的真实形态。
 */
private fun isTurnItem(item: UiItem?): Boolean = when (item) {
    is UiItem.Tool -> true
    is UiItem.Assistant -> true
    else -> false
}

/** [index] 所属那一轮的区间（含两端）；不是轮内条目则返回 null。 */
private fun turnRange(messages: List<UiItem>, index: Int): IntRange? {
    if (!isTurnItem(messages.getOrNull(index))) return null
    var start = index
    while (start > 0 && isTurnItem(messages[start - 1])) start--
    var end = index
    while (end < messages.lastIndex && isTurnItem(messages[end + 1])) end++
    return start..end
}

/**
 * 本轮「最终回答」的下标：最后一条**有正文**的助手消息；`-1` = 本轮没交出正文。
 *
 * 这是全文件第二把尺，也是「回答」二字唯一的定义。之前拿「有没有正文」当判据，
 * 于是**途中说的那句话**（「我先看一下配置」「再查一次」）被当成回答：
 * 它成了独立气泡、带自己的复制/朗读，思考被夹在两个气泡中间——
 * 真机上看到的就是「正文 → 思考 → 正文」，而参考实现是「一块过程 + 一段回答」。
 */
private fun answerAt(messages: List<UiItem>, turn: IntRange): Int {
    for (i in turn.last downTo turn.first) {
        val m = messages[i] as? UiItem.Assistant ?: continue
        if (m.text.isNotBlank()) return i
    }
    return -1
}

/**
 * [index] 所属那一轮的区间；null 表示「这一轮不折成流」。
 *
 * **段的唯一定义就在这里**，其余全是它的派生。
 *
 * 不折流的只有一种：一轮里只有一条助手消息、且没有工具调用——普通问答。
 * 那种情况思考仍按「回答上方一行」渲染（与 ChatGPT 的 collapsed Thought 同形），
 * 硬折成过程卡只会让一次简单问答多一层要点的东西。
 */
private fun segmentAt(messages: List<UiItem>, index: Int, liveIndex: Int = -1): IntRange? {
    val turn = turnRange(messages, index) ?: return null
    val hasTool = (turn.first..turn.last).any { messages[it] is UiItem.Tool }
    // 草稿不算「多出来的一步」：它落库后会并进同一条助手消息，若把它计入，
    // 单步问答在流式中会先长出一张一步的过程卡、落库后又缩回「思考一行」——一次白跳。
    val assistants = (turn.first..turn.last)
        .count { it != liveIndex && messages[it] is UiItem.Assistant }
    if (!hasTool && assistants < 2) return null
    return turn
}

/**
 * 从 [index] 处取一整段过程流；返回 null 表示「这一条不该由自己渲染」。
 *
 * 只有段首拿得到流，段内其余下标返回 null——同一个区间只画一次。
 */
internal fun traceRunAt(messages: List<UiItem>, index: Int, liveIndex: Int = -1): TraceRun? {
    val seg = segmentAt(messages, index, liveIndex) ?: return null
    if (seg.first != index) return null
    return buildRun(messages, seg, liveIndex)
}

/**
 * [index] 处那条助手消息的归属。
 *
 * 判据只有一条：**它是不是本轮最后一条有正文的消息**。
 *  - 段首自己画流 → NONE（正文画不画由 [TraceSlot.drawBody] 决定，思考已进流）；
 *  - 是最终回答 → TAIL（思考进流，正文留在原地，动作行只属于它一条）；
 *  - 其余（途中说明 / 纯思考）→ ABSORBED（整条进流：思考一步、说明一步）。
 *
 * 途中说明被吸收是**有意的**，不是丢内容：它变成流里的一步，展开就能读全文。
 * 真正不能吞的只有最终回答——那是用户要的东西，也是下一轮模型的上下文。
 */
internal fun traceOwnership(messages: List<UiItem>, index: Int, liveIndex: Int = -1): TraceOwnership {
    val item = messages.getOrNull(index) as? UiItem.Assistant ?: return TraceOwnership.NONE
    val seg = segmentAt(messages, index, liveIndex) ?: return TraceOwnership.NONE
    if (seg.first == index) return TraceOwnership.NONE
    return if (index == answerAt(messages, seg)) TraceOwnership.TAIL else TraceOwnership.ABSORBED
}

/** 一次算完「画不画流」与「正文归谁」，调用方不会只拿到一半。 */
internal fun traceAt(messages: List<UiItem>, index: Int, liveIndex: Int = -1): TraceSlot {
    val run = traceRunAt(messages, index, liveIndex)
    val own = traceOwnership(messages, index, liveIndex)
    val item = messages.getOrNull(index)
    val drawBody = when {
        item !is UiItem.Assistant || item.text.isBlank() -> false
        run == null -> true // 不折流（普通问答）：正文照常渲染
        own == TraceOwnership.TAIL -> true // 本轮答案
        // 段首：正文只在本段最后一句（= 本轮答案）时画，否则它已经是流里的一步
        run.range.first == index -> run.bodyIndex == index
        else -> false
    }
    return TraceSlot(run, own, drawBody)
}

/**
 * 把「还没落库的流式草稿」接在列表末尾，让段算法看得见它。
 *
 * **这是运行中「思考与工具分两层」的唯一成因。**（2026-11-27）
 *
 * 流式期间正文/推理只活在 `streamDraft` / `reasoningDraft` 里，不在这份 `List<UiItem>` 中；
 * 渲染侧于是只能把它当尾巴另起一块。结果就是：上面的流走完了工具，正在想的那一步
 * 却挂在下面、样式与流里其他步骤都不一样——用户的原话是「跑的时候是两层，
 * 跑完了才合到一起」。
 *
 * 解法不是另写一套运行中渲染，而是**让运行中和完成态用同一份输入**：
 * 草稿作为虚拟末项参与段计算，它自己那一步就自然接在流尾，
 * 落库后只是把虚拟项换成真实项，位置、顺序都不用重排。
 *
 * @param text 流式正文（null = 还没有正文）
 * @param reasoning 流式推理（null/空白 = 还没开始想）
 */
internal fun withLiveDraft(messages: List<UiItem>, text: String?, reasoning: String?): List<UiItem> =
    if (text == null && reasoning.isNullOrBlank()) messages
    else messages + UiItem.Assistant(text.orEmpty(), reasoning = reasoning)

/**
 * 把区间内的条目按发生顺序展开成步骤。
 *
 * 顺序天然正确，因为就是按下标走的——这正是上一版费 249 行想重建的东西。
 * 段内每条助手消息固定「先思考、后正文」，所以同一下标里思考必然在正文之前，
 * 与模型先想后说的顺序一致。
 */
private fun buildRun(messages: List<UiItem>, seg: IntRange, liveIndex: Int): TraceRun {
    val steps = ArrayList<TraceStep>(seg.count())
    val tools = ArrayList<UiItem.Tool>(seg.count())
    val answer = answerAt(messages, seg)
    var body = -1
    for (i in seg) {
        when (val m = messages[i]) {
            is UiItem.Tool -> {
                steps += toolStep(m)
                tools += m
            }
            is UiItem.Assistant -> {
                // liveIndex 是末尾那条还没落库的草稿：它这一步还在长，必须染成「跑」，
                // 否则用户看到的是「思考」打着已完成的对勾、正文却还在往外蹦。
                // 局部变量：reasoning 是跨模块属性，Kotlin 不给它做 smart cast（实测 e: 263:43）
                val think = m.reasoning
                if (!think.isNullOrBlank()) {
                    steps += thinkingStep(think, running = i == liveIndex)
                }
                if (m.text.isNotBlank()) {
                    // 只有最后那条有正文的是回答，其余都是「途中说明」：
                    // 说给用户听、但还没答完的一句，属于过程，不是一条独立回复。
                    if (i == answer) body = i else steps += textStep(m.text)
                }
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
        // 段内在长 = 有工具在跑，或最后一步还是流式思考
        running = tools.any { it.running } || liveIndex in seg,
        bodyIndex = body
    )
}

/**
 * 一段途中说明做成一步：标题「说明」，摘要取首行，全文进详情。
 *
 * 为什么不是把它并进上一条思考：模型「说」和「想」是两件事，混成一步后
 * 用户展开只看到一大坨，分不出哪句是说给他听的。
 */
private fun textStep(text: String): TraceStep {
    val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    return TraceStep(
        title = "说明",
        summary = if (line.length > 60) line.take(60) + "…" else line,
        detail = text.trim(),
        state = TraceState.DONE,
        isThinking = false
    )
}

/** 一段思考做成一步：标题固定「思考」，摘要取首句，全文进详情。 */
private fun thinkingStep(reasoning: String, running: Boolean = false): TraceStep = TraceStep(
    title = if (running) "思考中" else "思考",
    summary = thinkingSummary(reasoning),
    detail = reasoning,
    state = if (running) TraceState.RUNNING else TraceState.DONE,
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
