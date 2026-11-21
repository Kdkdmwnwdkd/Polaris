package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage

/**
 * 跨回合历史压缩（P0-1）——把「被窗口挤出去的轮次」折成一段摘要，而不是直接丢掉。
 *
 * ## 修的是什么
 *
 * 改造前的实现是 `turns.takeLast(PRIOR_TURNS_MAX)`：第 21 轮及以前**整段消失**，
 * 请求里连一句「我们之前聊过 30 轮」都没有。用户在第 25 轮问「上面第三个来源是哪家」，
 * 模型看到的是「一个陌生人从第 21 轮开始说话」——只能道歉或瞎猜。
 *
 * 会话本身是完整的（落盘一份不少，用户翻记录都在），**丢的只是发给模型的那份视图**。
 * 所以这不是数据问题，是视图问题：视图可以压，但不能什么都不留。
 *
 * ## 为什么不调模型做摘要
 *
 * 「让模型总结前 20 轮」是最直觉的方案，但它把成本放在了最贵的位置：
 *  - 延迟：每次跨窗口都要多一次往返，用户等的是第一句回答，不是等摘要；
 *  - 失败面：摘要调用失败就得降级，降级路径照样要写，等于两套都要维护；
 *  - 不可复现：同样的历史两次摘要可能不一样，请求视图每轮都在变，
 *    前缀缓存（[com.beichen.polaris.core.PromptSegments] 那套）全部失效。
 *
 * 本实现是**确定性**的：同样输入必得同样字节，零延迟、零成本、可单测。
 * 与回合内的 [ContextBudget.fitToBudget] 折叠同一哲学——先保住「发生过什么」，
 * 再谈「细节有多细」。
 *
 * ## 三档降级
 *
 * 被挤出的轮次可能只有 1 轮，也可能有 200 轮。摘要必须随规模自己降级：
 *  1. [Tier.RICH]：每轮「问 + 答首句」，适合十几轮；
 *  2. [Tier.LEAN]：只留每轮的问题，适合几十轮；
 *  3. [Tier.SKIM]：只留最初的任务 + 末尾几轮的问题，中间标「N 轮已省略」，适合上百轮。
 *
 * **最初的任务永远单独写一行**：它是任务锚点。被挤出的轮次越多，最先没的越是它，
 * 而它恰恰是模型最需要的那句「我们到底在干什么」。
 */
object HistoryCompactor {

    /**
     * 喂给模型的历史默认回看多少轮。
     *
     * 20 轮 ≈ 40 条消息，覆盖常见追问；再多也不是靠加大这个数解决的——
     * 窗口之外由 [digestOf] 折成一段，而不是拉长窗口去撑爆预算。
     */
    const val DEFAULT_KEEP_TURNS: Int = 20

    /**
     * 折叠条最多占多少字符（约 1200 token 的中文量级）。
     *
     * 上限的意义：折叠条本身也是上下文。给太松，它会把预算从「最近 20 轮真对话」
     * 手里抢走——那是拿真话换摘要，方向反了。
     */
    const val DIGEST_BUDGET_CHARS: Int = 1_200

    /** 折叠条的统一开头。与回合内折叠共用同一标记，便于将来统一识别。 */
    const val DIGEST_MARK: String = ContextBudget.FOLD_MARK

    /** 一轮的历史视图（已选中变体、纯正文，不含工具消息）。 */
    data class Turn(
        val question: String,
        val answer: String = "",
        /** 本轮用过的工具名（去重后进摘要，帮模型回忆「我做过什么」）。 */
        val tools: List<String> = emptyList()
    )

    // 各档位的字段长度（字符）。数字都是「够读懂一句」的下限，不是美学选择。
    private const val RICH_Q = 40
    private const val RICH_A = 60
    private const val LEAN_Q = 40
    private const val SKIM_Q = 28
    private const val FIRST_Q = 80
    private const val TAIL_TURNS = 5
    private const val MAX_TOOLS = 6

    /**
     * 把整段会话压成「折叠条 + 最近 [keepRecent] 轮」的消息列表。
     *
     * 行为契约（单测与 harness 都按这个断言）：
     *  - `turns.size <= keepRecent` → 折叠条不出现，逐轮 Q/A，与改造前逐字一致；
     *  - 超出时，折叠条**排在最前**，且是唯一一条 `UserMessage` 形态的摘要；
     *  - 摘要里一定包含最初那一轮的问题（任务锚点）；
     *  - 空问题的轮次（异常数据）跳过，且不占窗口名额——与旧实现一致。
     *
     * @param turns 全部轮次（时间正序）
     * @param keepRecent 保留原文的最近轮数（负数按 0 处理）
     */
    fun buildPriorMessages(
        turns: List<Turn>,
        keepRecent: Int = DEFAULT_KEEP_TURNS
    ): List<ChatMessage> {
        if (turns.isEmpty()) return emptyList()
        // 空问题（未落库的半轮、异常数据）在切窗口**之前**滤掉：
        // 否则一次「问了没答」的空轮会白占一个名额，最近几轮里少带一轮真对话。
        val rows = turns.filter { it.question.isNotBlank() }
        if (rows.isEmpty()) return emptyList()

        val keep = keepRecent.coerceAtLeast(0)
        val split = (rows.size - keep).coerceAtLeast(0)
        val dropped = rows.subList(0, split)
        val recent = rows.subList(split, rows.size)

        return buildList {
            if (dropped.isNotEmpty()) add(UserMessage(digestOf(dropped)))
            recent.forEach { turn ->
                add(UserMessage(turn.question.trim()))
                val answer = turn.answer.trim()
                if (answer.isNotBlank()) add(AssistantMessage(answer))
            }
        }
    }

    /** 折叠条的字符数上限（按档位）。 */
    private fun digestOf(rows: List<Turn>): String {
        val header = buildString {
            append(DIGEST_MARK).append(" 本次会话更早的 ").append(rows.size).append(" 轮对话（细节已省略）。")
        }
        val toolsLine = toolsLineOf(rows)

        // 档 1：问 + 答首句。十几轮时最有用——模型能顺着「上次说到 X」继续。
        richBody(rows)?.let { body ->
            val text = header + body + toolsLine
            if (text.length <= DIGEST_BUDGET_CHARS) return text
        }
        // 档 2：只留问题。答案没了，但「用户提过哪些要求」还在，指代多半靠这个。
        leanBody(rows)?.let { body ->
            val text = header + body + toolsLine
            if (text.length <= DIGEST_BUDGET_CHARS) return text
        }
        // 档 3：最初 + 末尾几轮，中间明说省略了多少轮——比假装「一共就这么几轮」诚实。
        return (header + skimBody(rows) + toolsLine).take(DIGEST_BUDGET_CHARS)
    }

    /**
     * 档 1：每轮一行「问 ｜ 答首句」。
     *
     * 首轮写「最初的任务」而不是「问」：它是任务锚点，值得单独一个标签。
     * 但**只在正文里写一次**——早前版本在 header 里也写了一遍，同一句话占了两次预算，
     * 白花掉约 60 字（相当于挤掉一轮真对话）。
     */
    private fun richBody(rows: List<Turn>): String? {
        val lines = rows.mapIndexed { i, turn ->
            val q = clip(turn.question, if (i == 0) FIRST_Q else RICH_Q)
            val label = if (i == 0) "· 最初的任务：$q" else "· 问：$q"
            val a = firstSentence(turn.answer, RICH_A)
            if (a.isBlank()) label else "$label ｜ 答：$a"
        }
        return if (lines.isEmpty()) null else "\n" + lines.joinToString("\n")
    }

    /** 档 2：每轮一行问题（首轮仍是任务锚点）。 */
    private fun leanBody(rows: List<Turn>): String? {
        val lines = rows.mapIndexedNotNull { i, turn ->
            clip(turn.question, if (i == 0) FIRST_Q else LEAN_Q)
                .takeIf { q -> q.isNotBlank() }
                ?.let { q -> if (i == 0) "· 最初的任务：$q" else "· $q" }
        }
        return if (lines.isEmpty()) null else "\n" + lines.joinToString("\n")
    }

    /**
     * 档 3：首轮之后跳到末尾 [TAIL_TURNS] 轮，中间用一行说明省略了多少。
     *
     * 为什么保留末尾而不是均匀抽样：**最近**被挤出的那几轮与当前问题最相关
     * （用户往往是在前面铺垫、最后才动手），均匀抽样会把它们打散掉。
     */
    private fun skimBody(rows: List<Turn>): String {
        val sb = StringBuilder()
        val first = clip(rows.first().question, FIRST_Q)
        if (first.isNotBlank()) sb.append("\n· 最初的任务：").append(first)
        val tailFrom = (rows.size - TAIL_TURNS).coerceAtLeast(1)
        if (tailFrom > 1) sb.append("\n· …中间 ").append(tailFrom - 1).append(" 轮已省略…")
        for (i in tailFrom until rows.size) {
            val q = clip(rows[i].question, SKIM_Q)
            if (q.isNotBlank()) sb.append("\n· ").append(q)
        }
        return sb.toString()
    }

    /** 「涉及工具」一行：帮模型回忆做过什么，避免重复调一遍已经做过的事。 */
    private fun toolsLineOf(rows: List<Turn>): String {
        val tools = rows.flatMap { it.tools }.filter { it.isNotBlank() }.distinct()
        if (tools.isEmpty()) return ""
        val shown = tools.take(MAX_TOOLS).joinToString("、")
        val more = if (tools.size > MAX_TOOLS) " 等 ${tools.size} 个" else ""
        return "\n涉及工具：$shown$more"
    }

    /**
     * 取第一句并限长。
     *
     * 为什么是首句而不是开头 N 字：模型的回答常常先给结论再展开（「三家，分别是…」），
     * 掐掉句号之后的展开正好留下结论；而按字数硬切常切在「因为…」这种半句上。
     */
    private fun firstSentence(text: String, maxChars: Int): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        var end = t.length
        for (i in t.indices) {
            if (t[i] in "。！？!?\n") {
                end = i + 1
                break
            }
        }
        return clip(t.substring(0, end), maxChars)
    }

    /** 限长；被截断时补省略号（让模型知道「这里还有话」而不是以为句子就这么断的）。 */
    private fun clip(text: String, maxChars: Int): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= maxChars) return t
        return t.take(maxChars - 1) + "…"
    }
}
