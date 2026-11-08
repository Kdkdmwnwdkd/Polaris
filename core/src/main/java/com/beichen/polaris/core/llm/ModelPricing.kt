package com.beichen.polaris.core.llm

/**
 * W-RT7：模型价格表与费用下钻的纯函数层——「按模型算钱」的最后一块拼图。
 *
 * 为什么是参考价而不是实时价：各家价格随促销/调价变动，抓实时价要联网要维护，
 * 而用户要的只是「这个会话大概花了多少钱、花在哪个模型上」的量级感。
 * 表里的价格是落表时的官网牌价（美元/百万 tokens），调价不影响**结构**——
 * 具体数字错了不影响归属与排序，只影响绝对值，UI 标明「参考价」即可。
 *
 * 纪律：查不到价格**如实说未知**（返回 null），绝不拿相近模型的价凑数——
 * 错误的价格比没有价格更误导。
 */
object ModelPricing {

    /** 单价：美元 / 百万 tokens（输入 / 输出分开计）。 */
    data class Price(val per1MIn: Double, val per1MOut: Double)

    /**
     * 价格表：**子串匹配**（小写），按声明顺序取第一个命中——具体型号放前面，
     * 家族通配放后面（`claude-sonnet` 优先于 `claude`）。
     */
    private val TABLE: List<Pair<String, Price>> = listOf(
        // 本地推理不花钱（电费不算）
        "local-llama" to Price(0.0, 0.0),
        "llama.cpp" to Price(0.0, 0.0),
        // DeepSeek（人民币牌价折算美元）
        "deepseek-chat" to Price(0.27, 1.10),
        "deepseek-reasoner" to Price(0.55, 2.19),
        // OpenAI
        "gpt-4o-mini" to Price(0.15, 0.60),
        "gpt-4o" to Price(2.50, 10.00),
        "o3-mini" to Price(1.10, 4.40),
        "o3" to Price(2.00, 8.00),
        "gpt-4.1-mini" to Price(0.40, 1.60),
        "gpt-4.1" to Price(2.00, 8.00),
        // Anthropic
        "claude-haiku" to Price(0.80, 4.00),
        "claude-sonnet" to Price(3.00, 15.00),
        "claude-opus" to Price(15.00, 75.00),
        // Google
        "gemini-2.0-flash" to Price(0.10, 0.40),
        "gemini-1.5-flash" to Price(0.075, 0.30),
        "gemini-1.5-pro" to Price(1.25, 5.00),
        // 国产其他（Kimi / 智谱 / 通义 / 豆包）
        "kimi" to Price(0.60, 2.50),
        "glm-4" to Price(0.50, 1.50),
        "qwen-turbo" to Price(0.05, 0.20),
        "qwen-plus" to Price(0.40, 1.20),
        "qwen-max" to Price(1.60, 6.40),
        "doubao" to Price(0.30, 0.60),
        "moonshot" to Price(0.60, 2.50),
    )

    /** 查价：命中返回价格；查不到返回 null（调用方按「价格未知」处理，不许猜）。 */
    fun priceFor(modelId: String): Price? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        return TABLE.firstOrNull { id.contains(it.first) }?.second
    }

    /** 一条用量归属：哪个模型、多少 tokens（入 / 出分开）。 */
    data class UsageEntry(val modelId: String, val promptTokens: Long, val completionTokens: Long)

    /** 单模型下钻结果：tokens 合计 + 估算费用；price 为 null = 价格未知（不计入总额）。 */
    data class ModelCost(
        val modelId: String,
        val promptTokens: Long,
        val completionTokens: Long,
        /** 估算费用（美元）；null = 该模型查不到价格，如实标未知。 */
        val costUsd: Double?
    )

    /**
     * 费用下钻：按模型聚合 tokens、逐模型算钱。
     * 排序按「有价格的按费用降序，未知价格的垫底、再按 tokens 降序」——
     * 花钱大头永远在最上面。
     */
    fun drillDown(entries: List<UsageEntry>): List<ModelCost> {
        if (entries.isEmpty()) return emptyList()
        return entries
            .groupBy { it.modelId.ifBlank { "未知模型" } }
            .map { (model, list) ->
                val pin = list.sumOf { it.promptTokens }
                val pout = list.sumOf { it.completionTokens }
                val price = priceFor(model)
                ModelCost(
                    modelId = model,
                    promptTokens = pin,
                    completionTokens = pout,
                    costUsd = price?.let { pin / 1_000_000.0 * it.per1MIn + pout / 1_000_000.0 * it.per1MOut }
                )
            }
            .sortedWith(
                compareByDescending<ModelCost> { it.costUsd != null }
                    .thenByDescending { it.costUsd ?: 0.0 }
                    .thenByDescending { it.promptTokens + it.completionTokens }
            )
    }

    /** 已知价格部分的合计；全未知返回 null（不显示 $0 装作没花钱）。 */
    fun totalUsd(costs: List<ModelCost>): Double? =
        costs.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()
}
