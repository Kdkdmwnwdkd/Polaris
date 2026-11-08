package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.MAX_TOOL_RESULT_CHARS
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.UserMessage

/**
 * 回合内上下文预算治理（W-10）——按「估算 token」而不是「消息条数」决定留什么。
 *
 * ## 为什么不用条数
 *
 * W-9 之前的实现是 `maxHistoryMessages=40` 的条数滑动窗口。条数对体积没有解释力：
 *  - 一条工具结果上限 24k 字符（[com.beichen.polaris.core.MAX_TOOL_RESULT_CHARS]），
 *    40 条最坏可到 96 万字符 ≈ 30 万 token——32k 上下文的小模型直接爆；
 *  - 反过来 40 条短消息可能不到 2k token，又白白丢掉了有用的历史。
 *
 * 真机体感「越跑越慢、越跑越贵」的上下文侧根因就在这里：每多一步工具调用，
 * 请求体就多一条最长 24k 字符的结果，且**每轮都全量重发**。
 *
 * ## 三个纯函数（零 Android 依赖，QA harness 可直测）
 *
 * 1. [estimateTokens]：CJK 感知的**保守**估算（CJK 1 字符≈1 token，ASCII≈4 字符/token）。
 *    宁可高估：低估的代价是真的撑爆上下文，高估只是多丢一点旧历史。
 * 2. [pruneImages]：只保留最近 N 条工具截图的 base64，旧图剥成一行省略标记。
 *    截图是单条最大的上下文炸弹——768px JPEG 的 data URL 约 6~12 万字符，而真正
 *    计费的是图像 token（约 900），若按字符估算会高估 100 倍，故图片单独计价。
 * 3. [fitToBudget]：超预算时从**最老的完整步**开始整组丢弃，保留：
 *    - 首条用户消息（任务锚点，丢了模型就不知道要干什么）；
 *    - 最后一段（当前步，丢了这一步等于没做）；
 *    - `assistant(tool_calls)` ↔ `tool` 结果的配对（拆开会被严格供应商直接 400 拒绝）。
 *
 * ## 为什么是「构建请求视图」而不是原地压缩
 *
 * 本模块只返回新列表、不改入参。调用方（[RealAgentLoop]）每轮用完整历史构建一份
 * **只发给 LLM 的视图**，UI/落盘历史保持完整——用户仍能看到每一步工具卡片，
 * 且不会出现「上一轮还在、这一轮没了」的抖动（无状态 = 天然无抖动，无需滞回计数）。
 */
object ContextBudget {

    /** 默认预算：24k token（覆盖 32k 上下文的常见小模型，留出输出与工具定义余量）。 */
    const val DEFAULT_BUDGET_TOKENS: Int = 24_000

    /**
     * 本地 llama.cpp 通道的预算上限（2k token）。
     *
     * 依据：`LocalLlamaSetting.DEFAULT_N_CTX = 4096`——手机端默认只开 4k 上下文，
     * 其中系统提示（含工具片段/记忆摘要）与工具定义已占约 2k，输出再留几百；
     * 若沿用 24k 预算，压缩形同虚设，请求会直接被 llama.cpp 截断或报错。
     */
    const val LOCAL_LLAMA_BUDGET_TOKENS: Int = 2_000

    /** 本地通道的 provider id（与 `ToolExposurePolicy` 同一标识）。 */
    const val LOCAL_LLAMA_PROVIDER_ID: String = "local-llama"

    /**
     * 按通道取实际预算：本地小模型取 [LOCAL_LLAMA_BUDGET_TOKENS] 与配置值的较小者
     * （配置更小则尊重配置，绝不上调）；云端模型再按模型窗口收敛（R10）。
     *
     * ## 为什么云端也要收敛
     *
     * 35 家共用一个 24k 预算，两边都吃亏：1M 窗口的模型浪费 97% 的上下文；
     * 8k 窗口的小模型直接溢出，靠上层 `maybeCompact` 兜底，用户看到的是
     * 「聊到一半忘了前面」。有了 [LlmProvider.contextWindow] 之后，预算至少
     * 不会超过「窗口的一半」。
     *
     * ## 为什么是一半，不是「窗口减去输出」
     *
     * 输出上限、工具定义、系统提示、以及**估算误差**都在这条链路上。给一半是
     * 经验上的安全边际：token 估算按字符数折算，中文 1:1、英文 4:1，
     * 混排时误差可达 ±30%。宁可少带几条历史，也不能让服务端拒掉整个请求——
     * 溢出表现为 400 或静默截断，比「上下文短一点」难排查得多。
     *
     * @param contextWindow 模型窗口（token）。**0 = 未知**，保持改造前行为（只用
     *   [configured]），不能因为「不知道」就把预算砍半——那会让所有未收录的供应商
     *   一起退化。
     */
    fun budgetForProvider(
        providerId: String,
        configured: Int = DEFAULT_BUDGET_TOKENS,
        contextWindow: Int = 0,
    ): Int {
        if (providerId == LOCAL_LLAMA_PROVIDER_ID) return minOf(configured, LOCAL_LLAMA_BUDGET_TOKENS)
        if (contextWindow <= 0) return configured
        return minOf(configured, contextWindow / 2)
    }

    /**
     * 单条工具结果的字符上限（W-11）：与上下文预算对齐。
     *
     * 背景：全局上限 [MAX_TOOL_RESULT_CHARS]=24k 是按「大上下文云端模型」定的。
     * 在本地 2k 预算下，一条 24k 字符的结果 ≈ 8k~24k token，**单条就超预算**；
     * 而当前步不可丢（丢了等于没做），压缩也救不回来——请求仍会撑爆 n_ctx。
     *
     * 口径：结果字符数 ≤ 预算的 1.2 倍（CJK 约 1 字 1 token；ASCII 约 4 字 1 token，
     * 给纯英文结果留出余量）；再与全局上限取小，云端行为不变（24k 封顶）。
     * 下限 1000 字符，避免预算配置极小时把结果截成残句。
     */
    fun toolResultLimitChars(
        providerId: String,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS
    ): Int {
        val budget = budgetForProvider(providerId, budgetTokens)
        val byBudget = (budget * 6 / 5).coerceAtLeast(1_000)
        return minOf(MAX_TOOL_RESULT_CHARS, byBudget)
    }

    /** 默认保留最近几条带图消息的图片（更早的截图剥成文字标记）。 */
    const val DEFAULT_KEEP_IMAGES: Int = 2

    /**
     * 单张图片的估算 token。
     *
     * 768px 长边的 JPEG 缩略图，视觉模型按像素计费约 `768*768/750 ≈ 786`；
     * 取 900 留一点余量。**绝不能按 base64 字符数估算**（会高估约 100 倍）。
     */
    const val IMAGE_TOKENS: Int = 900

    /** 旧图被剥离后追加的标记（模型据此知道「这里曾有一张图，但被省略了」）。 */
    const val PRUNED_IMAGE_NOTE: String = "\n（早前截图已从上下文省略以节省 token）"

    /**
     * 估算一段文本的 token 数（保守上界）。
     *
     * 口径：CJK 1 字符 ≈ 1 token；ASCII ≈ 4 字符/token；其余（emoji/西里尔等）≈ 2 字符/token。
     * 这是**估算**，不是分词器——目的是给出稳定的预算判据，不追求与某家 tokenizer 逐字对齐。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            when {
                c < 0x80 -> ascii++
                c in 0x3000..0x30FF -> cjk++ // CJK 标点 + 假名
                c in 0x3400..0x4DBF -> cjk++ // 扩展 A
                c in 0x4E00..0x9FFF -> cjk++ // 基本区
                c in 0xAC00..0xD7AF -> cjk++ // 谚文
                c in 0xFF00..0xFFEF -> cjk++ // 全角
                else -> other++
            }
        }
        return cjk + (ascii + 3) / 4 + (other + 1) / 2
    }

    /** 单条消息的估算 token（图片按 [IMAGE_TOKENS] 计价，不按 base64 长度）。 */
    fun messageTokens(message: ChatMessage): Int = when (message) {
        is SystemMessage -> estimateTokens(message.content)
        is UserMessage -> estimateTokens(message.content) + message.images.size * IMAGE_TOKENS
        is AssistantMessage -> {
            val calls = message.toolCalls.sumOf { call ->
                // 工具名 + 参数 JSON + 结构开销（id/type 字段）
                estimateTokens(call.name) + estimateTokens(call.arguments.toString()) + 8
            }
            estimateTokens(message.content) + calls
        }
        is ToolMessage -> {
            estimateTokens(message.content) +
                estimateTokens(message.name) + 8 +
                message.images.size * IMAGE_TOKENS
        }
    }

    /** 整段历史的估算 token。 */
    fun totalTokens(messages: List<ChatMessage>): Int = messages.sumOf { messageTokens(it) }

    /**
     * 只保留最近 [keepLast] 条**工具截图**消息的图片，更早的剥掉 base64 并追加省略标记。
     *
     * **本轮输入里的图片不裁**——那是用户本次任务的输入（例如「按这张图改」），裁掉等于把
     * 任务本身删了。只裁工具回灌的截图。
     *
     * ## 锚点必须由调用方显式给（W-49 修）
     *
     * 早前实现用 `indexOfFirst { it is UserMessage }` 当锚点，那是单回合形态下的写法。
     * loop 实际传进来的是 `[System] + priorMessages + [本轮输入]`，**首条用户消息是多轮
     * 历史里最早的那条**，不是本轮输入：
     * - 历史消息本身不带图（`ChatViewModel.buildPriorMessages` 只拼纯文本），所以那个
     *   「保护」在正常路径上是个空操作——测试里能过，真机上什么也没保护；
     * - 更糟的是反例：用户一次贴 3 张图，agent 又截了 2 次图，`keepLast=2` 会把
     *   **本轮输入那条整个剥掉**（图片是整条消息原子丢弃的），任务输入当场消失。
     *
     * 因此新增 [anchorIndex]：调用方（[RealAgentLoop.buildRequestMessages]）传入本轮输入
     * 在 messages 里的下标。不传时退回旧语义（首条 UserMessage），保证既有 harness 与
     * 单回合调用点行为不变。
     *
     * 入参不被修改；返回新列表（未命中的元素原样复用，零拷贝）。
     *
     * @param keepLast 保留最近几条工具截图的图片（负数 = 完全关闭裁剪，原样返回）
     * @param anchorIndex 本轮输入消息的下标（必须指向一条 UserMessage，否则忽略并退回默认）
     */
    fun pruneImages(
        messages: List<ChatMessage>,
        keepLast: Int = DEFAULT_KEEP_IMAGES,
        anchorIndex: Int? = null
    ): List<ChatMessage> {
        if (keepLast < 0) return messages
        val anchorIdx = anchorIndex
            ?.takeIf { it in messages.indices && messages[it] is UserMessage }
            ?: messages.indexOfFirst { it is UserMessage }
        val imageIdx = messages.indices.filter { i ->
            i != anchorIdx && (messages[i] as? UserMessage)?.images?.isNotEmpty() == true
        }
        if (imageIdx.size <= keepLast) return messages
        val drop = imageIdx.dropLast(keepLast).toHashSet()
        return messages.mapIndexed { i, m ->
            if (i !in drop) {
                m
            } else {
                val u = m as UserMessage
                u.copy(
                    images = emptyList(),
                    content = if (u.content.endsWith(PRUNED_IMAGE_NOTE)) u.content else u.content + PRUNED_IMAGE_NOTE
                )
            }
        }
    }

    /**
     * 把历史压进预算：超限时从最老的**完整步**开始丢，直到 ≤ [targetTokens] 且 ≤ [maxMessages]。
     *
     * 「完整步」= `assistant(tool_calls)` + 其后所有 `tool` 结果 + 紧随的截图回灌消息。
     * 整组丢/整组留，保证任何 `tool` 结果都有配对的 `tool_call`（严格供应商的硬约束）。
     *
     * **只丢到「刚好不超」为止**（[targetTokens] 默认 = [budgetTokens]），不多丢一步：
     * 曾有「压到 60% 留滞回空间」的设计，那是**有状态就地压缩**的产物（怕每轮都触发）；
     * 本实现是无状态请求视图，每轮从完整历史重算、天然无抖动，多丢的旧历史纯属浪费。
     * 需要预留余量的调用方可显式传更小的 targetTokens。
     *
     * ## 折叠（[fold] = true，默认）
     *
     * 丢掉的段不是凭空消失，而是换成**一条用户消息**说明「早前发生了什么」：条数、步数、
     * 最初的任务、用过的工具、头两条结果的首行。这是纯结构化摘要——不调模型、不加延迟、
     * 结果确定（同样输入必得同样字节），因此不会破坏请求视图的可重复性。
     *
     * 为什么是「折叠」而不是「丢弃」：直接丢，模型会突然不知道自己为什么在做这件事，
     * 然后重复调一遍已经做过的工具——那比多带 200 字符贵得多。
     *
     * 为什么用 [UserMessage] 而不是 [SystemMessage]：Claude / Gemini 会把**所有**
     * SystemMessage 拼进顶层 system 字段，插在中间会让系统提示每轮变化，刚做好的
     * KV 分段立刻作废（见 `core/PromptSegments.kt`）。用户消息只会出现在它该在的位置。
     *
     * 折叠条本身**不额外占预算**：先按原规则丢到 ≤ [targetTokens]，再用剩下的空间写摘要
     * （至少 [MIN_FOLD_TOKENS]，至多 [MAX_FOLD_TOKENS]）。剩余空间不够就不折——宁可少信息，
     * 不可为了塞摘要而多丢一步真实结果。
     *
     * 永不丢弃：首条 SystemMessage、首条 UserMessage（任务锚点）、最后一段、最后一个
     * 含工具结果的步（防止「最终文本尾随」时把当前步整步丢掉）。
     * 若丢无可丢仍超预算，返回尽力而为的结果（调用方只能接受——比整轮失败好）。
     *
     * 入参不被修改；未超限时原样返回（同一个 List 实例，零分配）。
     */
    fun fitToBudget(
        messages: List<ChatMessage>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        targetTokens: Int = budgetTokens,
        maxMessages: Int = Int.MAX_VALUE,
        fold: Boolean = true
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        var total = totalTokens(messages)
        if (total <= budgetTokens && messages.size <= maxMessages) return messages

        val segs = segmentsOf(messages)
        if (segs.size <= 2) return messages // 只剩锚点段与末段，没有可丢的中间步

        val anchorIdx = segs.indexOfFirst { s ->
            (s.start until s.end).any { messages[it] is UserMessage }
        }
        val lastIdx = segs.lastIndex
        // 最后一个「含工具结果的步」也要钉住：若末尾跟着一条最终文本，lastIdx 指向文本段，
        // 当前步就成了可丢的中间段——预算紧时会把「刚做完这一步」的证据整段删掉。
        val lastStepIdx = segs.indexOfLast { s ->
            (s.start until s.end).any { messages[it] is ToolMessage }
        }
        // 折叠条**不额外占预算**：先按原规则丢到 ≤ targetTokens，再用「剩下的空间」写摘要。
        // 反过来做（先扣预留再丢）会让边界情况多丢一步——为了塞一条摘要而丢掉一步真实结果，
        // 是拿贵的东西换便宜的东西。
        val dropped = BooleanArray(messages.size)
        var kept = messages.size

        for (idx in segs.indices) {
            if (total <= targetTokens && kept <= maxMessages) break
            if (idx == 0 || idx == anchorIdx || idx == lastIdx || idx == lastStepIdx) continue
            val s = segs[idx]
            for (j in s.start until s.end) dropped[j] = true
            kept -= s.size
            total -= (s.start until s.end).sumOf { messageTokens(messages[it]) }
        }
        if (kept == messages.size) return messages

        // 剩余空间够写一句摘要才折，否则维持纯丢弃（少信息好过爆上下文）。
        val room = targetTokens - total
        val note = if (fold && room >= MIN_FOLD_TOKENS && kept + 1 <= maxMessages) {
            buildFoldNote(messages.filterIndexed { i, _ -> dropped[i] }, room)
        } else {
            null
        }
        val usable = note?.takeIf { total + messageTokens(UserMessage(it)) <= targetTokens }

        val out = ArrayList<ChatMessage>(kept + 1)
        var placed = false
        for (i in messages.indices) {
            if (!dropped[i]) {
                out.add(messages[i])
            } else if (usable != null && !placed) {
                out.add(UserMessage(usable))
                placed = true
            }
        }
        return out
    }

    /** 折叠条的固定开头（日志与 UI 据此识别「这条不是用户说的话」）。 */
    const val FOLD_MARK: String = "[早前对话已折叠]"

    /** 折叠条至少要这么多 token 才值得写（一句任务 + 两三个工具名）。 */
    private const val MIN_FOLD_TOKENS: Int = 60

    /** 折叠条最多占多少 token（防止大预算下摘要膨胀成第二份历史）。 */
    private const val MAX_FOLD_TOKENS: Int = 300

    /**
     * 生成折叠说明（纯函数，确定性）。
     *
     * 只写「这段里发生了什么」：多少条、几步、用了哪些工具、头两条结果的第一行。
     * **不写「最初的任务」**——任务锚点（首条用户消息）永远保留在折叠条前面，
     * 重复一遍既浪费预算，又可能把用户后来的话误当成原始意图。
     *
     * 过程（哪一步点了哪个坐标）一律不留：那正是被折掉的部分，留着就白折了。
     */
    private fun buildFoldNote(source: List<ChatMessage>, reserveTokens: Int): String? {
        if (source.isEmpty()) return null
        val maxChars = (reserveTokens * 9 / 10).coerceIn(80, MAX_FOLD_TOKENS)
        val toolResults = source.filterIsInstance<ToolMessage>()
        val steps = source.count { it is AssistantMessage && it.toolCalls.isNotEmpty() }
        val tools = toolResults.map { it.name }.distinct().take(6)
        val outcomes = toolResults
            .map { it.content.lineSequence().firstOrNull()?.trim().orEmpty().take(36) }
            .filter { it.isNotEmpty() }
            .take(2)

        val sb = StringBuilder(maxChars + 32)
        sb.append(FOLD_MARK).append(" ").append(source.size).append(" 条")
        if (steps > 0) sb.append("、").append(steps).append(" 步工具调用")
        if (tools.isNotEmpty()) sb.append("（").append(tools.joinToString("、")).append("）")
        sb.append("。")
        if (outcomes.isNotEmpty()) sb.append("关键结果：").append(outcomes.joinToString(" | "))
        val text = sb.toString()
        return if (text.length <= maxChars) text else text.take(maxChars)
    }

    // ---- 内部分段 ----------------------------------------------------------

    private data class Segment(val start: Int, val end: Int) {
        val size: Int get() = end - start
    }

    /**
     * 把消息切成「完整步」：assistant(tool_calls) 与其后的 tool 结果、截图回灌消息同组。
     * 其余消息各自成段。返回的段连续覆盖整个列表。
     */
    private fun segmentsOf(messages: List<ChatMessage>): List<Segment> {
        val out = ArrayList<Segment>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            var end = i + 1
            if (msg is AssistantMessage && msg.toolCalls.isNotEmpty()) {
                while (end < messages.size && messages[end] is ToolMessage) end++
                // 截图回灌消息紧跟在工具结果之后，属于同一「步」
                if (end < messages.size) {
                    val next = messages[end]
                    if (next is UserMessage && next.images.isNotEmpty()) end++
                }
            }
            out.add(Segment(i, end))
            i = end
        }
        return out
    }
}
