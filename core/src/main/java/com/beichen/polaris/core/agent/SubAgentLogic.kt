package com.beichen.polaris.core.agent

/**
 * 一个子任务：标题（给用户看）+ 指令（给子代理执行）。
 *
 * @param title 短标题，汇总时显示；空则由指令自动截取
 * @param instruction 完整指令，必须自包含（子代理看不到主对话）
 */
data class SubTask(val title: String, val instruction: String)

/**
 * 一个子代理的执行结果。
 *
 * @param ok 是否正常返回（false 时 [text] 是失败原因，不是结论）
 * @param elapsedMs 耗时，汇总时带上——三个子任务里哪个慢到超时，一眼看得出
 */
data class SubAgentOutcome(
    val title: String,
    val ok: Boolean,
    val text: String,
    val elapsedMs: Long
)

/**
 * 子代理的参数校验、提示词与结果汇总（W-31）。
 *
 * **纯函数、无 Android / 无协程依赖**——执行编排在 [SubAgentRunner]，
 * 本对象只负责「能不能派」「派出去怎么说」「拿回来怎么汇总」，可直接被 harness 断言。
 */
object SubAgentLogic {

    /** 一次最多派几个子任务。 */
    const val MAX_TASKS = 3

    /** 单条指令上限（字符）；超出截断——子代理上下文只有 12k，指令太长没意义。 */
    const val MAX_INSTRUCTION = 2_000

    /** 单个子代理的结论上限（字符）。 */
    const val MAX_CONCLUSION = 1_200

    /** 汇总总预算（字符）——超出时后面的任务只留标题与状态。 */
    const val MAX_SUMMARY = 4_000

    /**
     * 子代理可用的**无副作用**工具白名单（R4：原名 `READ_ONLY_TOOLS`）。
     *
     * 为什么硬限制：
     *  - 并行执行写操作会互相踩（两个子任务同时改同一个文件 = 谁后写谁赢，且没人知道）；
     *  - 子代理没有 UI 确认通道，写错没人拦；
     *  - 调研/搜索/看界面本来就只需要读——要写就让主代理串行做。
     *
     * **改名不是措辞洁癖**：`readOnly` 在工具契约里只表示「可并行」，不表示「无外部影响」。
     * 按 `readOnly` 挑白名单，会把「读起来无害、实际上在往外发数据」的工具放进来。
     * `http_request` 就是这一类——它可以 POST 任意 body 到任意地址，是标准的数据外泄通道。
     * R4 把它移出白名单：子代理要发请求，由主代理在审批下代做。
     *
     * 白名单外的工具名不会被注册进子代理的工具表，模型看不到也就调不到。
     */
    val NO_SIDE_EFFECT_TOOLS: Set<String> = setOf(
        "read_file",
        "web_search",
        "web_open",
        "web_snapshot",
        "ocr_image",
        "ui_tree",
        "ui_screenshot",
        "app_list",
        "device_foreground_app",
        "device_notifications",
        "memory_search",
        "memory_list",
        "conversation_search",
        "calculator",
        "todo_read"
    )

    /** 参数校验结果。 */
    sealed interface Parse {
        data class Ok(val tasks: List<SubTask>) : Parse
        data class Err(val code: String, val message: String, val hint: String) : Parse
    }

    /**
     * 校验并规范化子任务列表。
     *
     * @param raw 已从 JSON 解析出的 (标题, 指令) 对；空标题允许
     */
    fun parseTasks(raw: List<Pair<String, String>>): Parse {
        val cleaned = raw
            .map { (title, instruction) ->
                SubTask(
                    title = title.trim().ifEmpty { autoTitle(instruction) },
                    instruction = instruction.trim().take(MAX_INSTRUCTION)
                )
            }
            .filter { it.instruction.isNotEmpty() }

        if (cleaned.isEmpty()) {
            return Parse.Err(
                code = "empty_tasks",
                message = "没有可执行的子任务",
                hint = "tasks 里每一项都要有非空的 instruction，说明这个子代理具体要查什么。"
            )
        }
        if (cleaned.size > MAX_TASKS) {
            return Parse.Err(
                code = "too_many_tasks",
                message = "一次最多派 $MAX_TASKS 个子任务（收到 ${cleaned.size} 个）",
                hint = "并行太多会拖慢整体、也更容易超时。请合并相近的子任务，或分两次派。"
            )
        }
        return Parse.Ok(cleaned)
    }

    /** 标题为空时用指令前 20 字兜底（去掉换行，避免标题里出现多行）。 */
    fun autoTitle(instruction: String): String {
        val oneLine = instruction.replace('\n', ' ').trim()
        if (oneLine.isEmpty()) return "子任务"
        return if (oneLine.length <= 20) oneLine else oneLine.take(20) + "…"
    }

    /**
     * 子代理的系统提示。
     *
     * 三条硬约束（都是踩过的坑）：
     *  1. **只读**——写操作没有确认通道，且会与其他子代理互相踩；
     *  2. **自包含**——子代理看不到主对话，必须让它只依赖 instruction；
     *  3. **结论优先**——子代理的过程不进主上下文，只有这段文本会回去，所以要「结论 + 依据」。
     */
    fun systemPrompt(title: String): String = buildString {
        appendLine("你是子代理「$title」，只负责主代理交给你的这一件子任务。")
        appendLine("规则：")
        appendLine("1. 只做只读操作（读文件、搜网页、看界面、查记忆）。你没有写权限，也不要尝试修改任何东西。")
        appendLine("2. 不要寒暄、不要复述任务、不要问问题——直接干，拿到结论就停。")
        appendLine("3. 最终回复只输出结论：先说结论，再列 1~3 条关键依据（文件路径、链接、数值）。总长不超过 600 字。")
        appendLine("4. 查不到就明确说「没找到」，并列出你查过什么。不要编造。")
        appendLine("5. 用最少步骤完成；不需要调用工具时直接回答。")
    }.trim()

    /** 给子代理的用户输入：把指令再包一层边界，避免它把「规则」当任务内容。 */
    fun userPrompt(instruction: String): String =
        "<<<\n${instruction.take(MAX_INSTRUCTION)}\n>>>"

    /**
     * 从子代理的消息历史里取结论：最后一条非空 assistant 文本。
     *
     * 为什么从后往前找而不是取最后一条：循环可能以一条空的 assistant（只有工具调用）收尾，
     * 直接取最后一条会拿到空串，汇总里就成了「（无结论）」。
     */
    fun extractConclusion(assistantTexts: List<String>): String {
        for (text in assistantTexts.asReversed()) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) {
                return if (trimmed.length <= MAX_CONCLUSION) trimmed
                else trimmed.take(MAX_CONCLUSION) + "\n…（结论过长，已截断）"
            }
        }
        return "（子代理没有给出文字结论）"
    }

    /**
     * 失败原因转人话（脱敏交给调用方；这里只做兜底与截断）。
     *
     * 这里**不判断超时/取消**：那两类在 [SubAgentRunner] 里单独捕获（超时要记为失败、
     * 取消要重抛），本函数只管「其余异常怎么变成一句话」。也因此本对象不依赖协程库。
     */
    fun describeFailure(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        if (raw.isNotEmpty()) return raw.take(200)
        return error::class.simpleName?.take(80)?.ifEmpty { "未知错误" } ?: "未知错误"
    }

    /**
     * 汇总多个子代理结果，供主代理一次性看到全部结论。
     *
     * 格式刻意用 Markdown 小标题：主代理（也是模型）读分节文本比读 JSON 更稳，
     * 而且这段文本会直接出现在工具卡片里，用户也能看懂。
     */
    fun summarize(outcomes: List<SubAgentOutcome>, maxChars: Int = MAX_SUMMARY): String {
        if (outcomes.isEmpty()) return "（没有子代理结果）"
        val okCount = outcomes.count { it.ok }
        val sb = StringBuilder()
        sb.append("子代理结果：共 ${outcomes.size} 个，成功 $okCount，失败 ${outcomes.size - okCount}\n")
        for ((index, item) in outcomes.withIndex()) {
            val status = if (item.ok) "✓" else "✗"
            val seconds = item.elapsedMs / 1000
            val header = "\n### ${index + 1}. ${item.title} $status（${seconds}s）\n"
            val body = if (item.ok) item.text else "失败：${item.text}"
            // 预算控制：加进去会超标就只留标题与状态
            if (sb.length + header.length + body.length > maxChars) {
                sb.append(header)
                sb.append("（汇总已达长度上限，本条结论省略——需要细节请单独指派这一个子任务）\n")
            } else {
                sb.append(header)
                sb.append(body).append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** 并发度归一：1..[MAX_TASKS]，且不超过任务数。 */
    fun clampConcurrency(requested: Int, taskCount: Int): Int {
        if (taskCount <= 0) return 0
        return requested.coerceIn(1, MAX_TASKS).coerceAtMost(taskCount)
    }
}
