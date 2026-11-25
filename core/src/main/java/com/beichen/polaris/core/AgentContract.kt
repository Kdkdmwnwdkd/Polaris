package com.beichen.polaris.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 工具统一接口：Agent 核心只依赖这个抽象，完全不接触任何 Android API。
 * 所有能力（文件、网络、剪贴板…）都通过 execute() 暴露给 LLM。
 *
 * 三个可选声明的分工：
 *  - [systemPrompt]：工具可自带一段提示词，注入到系统提示里。
 *  - [needsApproval]：危险工具可声明调用前需用户审批。
 *  - [readOnly]：是否只读（无副作用）。并发判据用——本批任一工具非只读即整批串行，
 *    全批只读才并行（T03）。默认 false（未知工具按写处理，
 *    走向串行这一安全侧）。
 *  - execute 返回统一契约 [ToolResult]（T02：ok/code/message/hint/data，失败 hint 必填）。
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonSchema

    /** 工具自带提示词片段，拼进系统提示。默认空。 */
    val systemPrompt: String get() = ""

    /** 是否危险操作，调用前需要用户审批。默认 false。 */
    val needsApproval: Boolean get() = false

    /**
     * 按**本次入参**决定要不要审批（W-79）。
     *
     * 默认沿用 [needsApproval]，行为与旧版逐字节一致；需要「看参数才知道危不危险」的
     * 工具覆盖它。目前只有 `shell_run` 用到：`ls`、`cat`、`grep` 这类只读命令没有副作用，
     * 每次都弹窗只会训练用户闭眼点「允许」，反而让真正危险的 `rm -rf` 失去拦截意义。
     *
     * 判定规则见 [com.beichen.polaris.core.agent.ShellCommandRules]（纯函数、有单测）。
     */
    fun needsApprovalFor(args: JsonObject): Boolean = needsApproval

    /**
     * 是否只读（T03）：true = 无副作用可并行；false = 写操作须串行。
     * 不标注 = false（保守走串行）。
     */
    val readOnly: Boolean get() = false

    /**
     * 执行期间是否在**等用户**（R2）。
     *
     * `ask_user` / `plan_propose` 这类工具会挂起等待人回答，它们本身没有副作用，
     * 因此标了 `readOnly = true`——但执行器的「只读 90 秒预算」对它们是错的：
     * 用户思考三分钟不是「工具卡死」。标了本位的工具，执行器不加超时，
     * 由工具自己的等待超时（`ask_user` 180s / `plan_propose` 300s）负责收尾。
     */
    val awaitsUser: Boolean get() = false

    /**
     * 本工具的单次调用预算（毫秒）。null = 用执行器的分档默认（R2）。
     *
     * ## 为什么工具能自声明预算
     * R2 的分档只区分「只读 vs 写」，但**同一个工具在不同时长下差别巨大**：
     * `shell_run` 执行 `ls` 是毫秒级，执行 `gradle build` 要好几分钟；
     * `http_request` 正常几十秒，网络黑洞时会一直挂着。
     * 分档解决不了这种粒度——工具作者最清楚自己要跑多久，所以由工具自声明。
     *
     * 声明值会被 [com.beichen.polaris.core.agent.ToolBudget.resolve] 夹到安全区间
     * （下界 1 秒，上界为回合软截止），避免把预算写错导致「立刻判死」或「永远不超时」。
     *
     * 注意本属性**不覆盖** [awaitsUser]：等用户的工具（`ask_user` / `plan_propose`）
     * 不吃工具级预算——用户思考三分钟不是「工具卡死」，它们的等待上限由工具自己负责。
     */
    val timeoutBudgetMs: Long? get() = null

    /** 执行工具，返回统一契约结果（T02）。 */
    suspend fun execute(args: JsonObject): ToolResult
}

/** 工具返回的片段：文本或图片（图片以 url 形式传给 UI）。 */
sealed interface ToolPart {
    val text: String get() = ""
    val isError: Boolean get() = false
    data class Text(val content: String, override val isError: Boolean = false) : ToolPart {
        override val text get() = content
    }
    data class Image(val url: String, val alt: String = "") : ToolPart {
        override val text get() = "[图片: $alt]"
    }
}

/**
 * 工具审批回调：`true` 允许、`false` 用户拒绝、`null` 时限内没人回应。
 *
 * P1-3 起这个别名只描述**回调的形状**，不再用来表达「有没有审批通道」——
 * 后者由 [Consent] 承担。参数位置写 `ToolApproval?` 会在类型上把
 * 「没有弹窗位置」和「忘了传」混成同一个值，那正是 [Consent] 要拆开的歧义。
 *
 * 2026-11-22 起返回值放宽成三态：超时与拒绝共用 `false` 时，日志、模型提示、
 * 界面文案会一起说错话（详见 [Consent.Decision.TimedOut]）。
 */
typealias ToolApproval = suspend (tool: AgentTool, args: JsonObject) -> Boolean?

/** Agent 调用某工具时的一次请求。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject
)

/**
 * 对话消息模型——同时作为 LLM 的上下文。
 * Agent 核心只认 ChatMessage，不关心这些消息最终怎么渲染。
 */
sealed interface ChatMessage
data class SystemMessage(val content: String) : ChatMessage
data class UserMessage(
    val content: String,
    /** 视觉模型图片（data URL 列表），作为多模态内容附在消息里发给 LLM。 */
    val images: List<String> = emptyList()
) : ChatMessage
data class AssistantMessage(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 功能五（P1-5）：本次 LLM 往返的 token 用量（服务端 usage 字段解析而来）。
     * null = 供应商没给 usage（本地 llama / 兼容端点缺字段）——UI 不显示而非猜一个数。
     * 多步回合（调工具的轮次）由调用方按轮汇总（见 ChatViewModel.send）。
     */
    val usage: TokenUsage? = null,
    /**
     * 功能五（P1-9）：本次 LLM 往返的推理过程（思维链全文，非流式增量）。
     * null/空 = 模型没给思考（非推理模型或厂方不回传）——UI 不渲染折叠块而非摆个空框。
     * 与 content 分流存储：思考是「怎么想的」，content 是「想了什么」，
     * 灌进同一字段会让历史请求把模型的自言自语也当上下文发给下一轮。
     */
    val reasoning: String? = null,
    /**
     * 本次输出「为什么结束」，三家字段已归一化（见 [com.beichen.polaris.core.llm.FinishReason]）。
     *
     * `length` = 撞到输出长度上限，content 很可能是半截的；`stop` = 模型自己说完；
     * 其余（`tool_calls` / `safety`…）原样保留。null = 供应商没报这个字段。
     *
     * 加它的原因很具体：DeepSeek 官方 `deepseek-chat` 的 `max_tokens` 默认只有 4K，
     * 而 OpenAI 兼容路径不显式传该参数——长回答会在 4K 处被服务端截断，
     * 旧实现里 App 完全不知道，用户看到的是「模型话说一半停了」。
     */
    val finishReason: String? = null
) : ChatMessage

/**
 * 功能五（P1-5）：token 用量（一次 LLM 往返或一段时间窗口的合计）。
 *
 * prompt = 输入侧（含系统提示/工具定义/历史，通常远大于输出侧）；
 * completion = 输出侧。三家协议字段名各不相同，解析统一成本结构后再上传
 * （OpenAI usage.prompt_tokens/completion_tokens；Claude usage.input_tokens/
 * output_tokens；Gemini usageMetadata.promptTokenCount/candidatesTokenCount）。
 */
data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /**
     * 输入侧里**命中提示缓存**的部分。
     *
     * 它**不参与** [totalTokens]：`promptTokens` 已经是服务端的计费口径，
     * 缓存部分在里面按各家的折扣价算，再叠一次就会把账算重。
     * 单独留这一项只为回答「这轮到底省没省」，不用于计费。
     *
     * 三家口径不同，换算命中率前必须先分清（这是踩过的坑）：
     *  - OpenAI / DeepSeek / Gemini：命中量**包含**在 `promptTokens` 里，是子集，
     *    命中率 = cached / prompt；
     *  - Claude：`cache_read_input_tokens` **不在** `input_tokens` 里，三段输入是
     *    `input + cache_read + cache_creation`，分母得自己加，否则会算出 >100%。
     *
     * 默认 0 且排在最后：历史会话从库里读回时没有这一列，构造调用方无需改动。
     */
    val cachedPromptTokens: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    companion object {
        /** 空用量（无数据时的占位，避免 UI 判空两套逻辑）。 */
        val EMPTY: TokenUsage = TokenUsage()

        /** 汇总多条（一个回合多次 LLM 往返 → 整轮成本）。null 元素跳过。 */
        fun sum(usages: List<TokenUsage?>): TokenUsage =
            TokenUsage(
                promptTokens = usages.sumOf { it?.promptTokens ?: 0L },
                completionTokens = usages.sumOf { it?.completionTokens ?: 0L },
                cachedPromptTokens = usages.sumOf { it?.cachedPromptTokens ?: 0L }
            )
    }
}

/** 工具执行结果进入历史：带调用 id / 工具名 / 入参，方便 UI 折叠展示。 */
data class ToolMessage(
    val id: String,
    val name: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
    val content: String,
    val isError: Boolean = false,
    /** 工具返回的图片（data URL 列表），供 UI 直接渲染（如 ui_screenshot 截图）。 */
    val images: List<String> = emptyList()
) : ChatMessage

/**
 * 工具参数的 JSON Schema 描述，用于暴露给 LLM 做 function calling。
 * 第一阶段只描述“顶层扁平字段”，嵌套 object 仅作 type=object 占位即可。
 * 结构与 MCP 工具的 inputSchema 兼容，便于后续直接接入 MCP 服务器。
 */
@Serializable
data class JsonSchema(
    val type: String = "object",
    val properties: Map<String, Property> = emptyMap(),
    val required: List<String> = emptyList()
) {
    @Serializable
    data class Property(
        val type: String,
        val description: String = "",
        val enum: List<String>? = null,
        /**
         * W-31：数组元素的 schema。
         *
         * 为什么现在必须补：`spawn_agents` 的 tasks 是对象数组，而严格校验的
         * function-calling 实现要求 `type=array` 必须带 `items`，否则整个工具声明被拒。
         * 默认 null —— 既有工具（全是扁平字段）序列化结果一个字节都不变。
         */
        val items: Property? = null,
        /** W-31：对象字段（type=object 时用；同样默认 null，不影响既有工具）。 */
        val properties: Map<String, Property>? = null,
        /** W-31：对象必填字段名。 */
        val required: List<String>? = null
    )

    companion object {
    fun prop(
        name: String,
        type: String,
        description: String,
        enum: List<String>? = null
    ): Pair<String, Property> = name to Property(type, description, enum)

        /**
         * 这个参数在**描述里**已经声明了「可以不传」。
         *
         * 判据只认描述文字，因为那是模型唯一能读到的语义：
         * - `（可选）` / `可选，` / `可选；` —— 直接写明
         * - `默认 X` —— 省略时取这个默认值
         * - `留空 = ...` / `省略则...` —— 等价说法
         *
         * ## 为什么这条规则必须存在
         *
         * `required` 的旧默认值是「所有参数」，于是描述写着「（可选）」、schema 说不传不行，
         * 严格按契约生成的模型只能硬塞一个值——最省事的就是空串。`http_request` 就是这么
         * 炸的：GET 带上了 `body: ""`，被 OkHttp 挡在发出之前，报 `GET must not have a
         * request body`，还伪装成「网络异常」。
         *
         * 一份工具声明，描述和 required 必须说同一件事，否则模型只能在两者之间猜。
         * 这条规则让「描述说可选」自动等于「required 里没有它」，不需要每个调用点重复声明。
         *
         * ## 已知的粗糙处（接受，因为方向只会更宽松）
         *
         * 关键词是子串匹配，理论上会误伤——比如某个必填参数的描述里恰好写了「默认」。
         * 后果是模型可能省略它，然后在工具里被 `?: return fail("缺少 xxx")` 拦住，多一次往返。
         * 反过来（把可选标成必填）会让模型塞空串，那才是真正会出事的方向，所以宁可偏松。
         */
        private fun Property.declaredOptional(): Boolean {
            val d = description
            if (d.isEmpty()) return false
            return d.contains("可选") || d.contains("默认") || d.contains("留空") || d.contains("省略")
        }

        /**
         * 拼一份工具参数 schema。
         *
         * [required] 缺省 = 「除描述里声明可选的之外，都必填」。要精确控制就显式传：
         * 显式传的列表**原样使用**，不做任何过滤——需要「描述说可选但实际必填」这种
         * 反常情况时，显式写法是唯一出口，此时描述应当同步改掉，不要留下自相矛盾的两句话。
         */
        fun objectSchema(
            vararg props: Pair<String, Property>,
            required: List<String> = props.filterNot { it.second.declaredOptional() }.map { it.first }
        ): JsonSchema = JsonSchema(properties = mapOf(*props), required = required)
    }
}
