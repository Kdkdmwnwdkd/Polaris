package com.beichen.polaris.core

/**
 * 系统提示的**分段决策**：每段归哪一档稳定性、什么条件下才出现。
 *
 * 拼装本身在 [PromptAssembler]（去空段、排序、算指纹）；这里只回答「该有哪几段」。
 * 拆成两半的理由是稳定性分级错了**不会报错，只会变慢**——记忆段若被写成 FROZEN，
 * 每轮记忆一变整段前缀作废，端侧 prefill 从头来过，用户只感觉「这轮特别慢」，
 * 日志里也只是一条前缀变化的 INFO。这种错必须由断言守，不能靠 review。
 *
 * ## 三段的稳定性理由
 * - `identity`（身份与规则）：整个会话不变 → [Stability.FROZEN]，排最前；
 * - `tools`（本回合工具面的用法说明）：只随工具面变（`__use_tools` 激活新组时）→
 *   [Stability.SESSION]，进指纹但不进 TURN；
 * - `memory` / `memory-usage`：随时可能变 → [Stability.TURN]，压在最后，且必须
 *   **在指纹之外**——这正是「只改记忆不该让缓存失效」的落点。
 *
 * ## 两条条件规则
 * - 记忆段只在**确实有摘要**时拼。没有可读记忆还教一遍 `memory_usage`，等于白占上下文，
 *   小模型的注意力会被这条用不上的指令分走。
 * - 空白的身份/工具说明不产生段。空段会在拼装时留下连续空行，多耗 token，还让不同轮次
 *   的空白不一致（前缀指纹是逐字节比的，空白不同就是不同的前缀）。
 *
 * 迁移说明：判断逻辑原在 `RealAgentLoop.buildSystemPrompt` 里，依赖 `memoryProvider`
 * 这个 lambda 与 `AgentTool`，无法脱离 Android 编译。抽成纯函数后 `SystemPromptPlanTest`
 * 能在 kotlinc + JUnit 的闭包里跑，变异门禁因此多了一组。
 */
internal object SystemPromptPlan {

    /** 段 key。日志与断言都按它定位，改名等于改对外可观测行为，不要随手动。 */
    const val KEY_IDENTITY = "identity"
    const val KEY_TOOLS = "tools"
    const val KEY_MEMORY = "memory"
    const val KEY_MEMORY_USAGE = "memory-usage"

    /**
     * 决定本回合该有哪几段，顺序即拼装顺序。
     *
     * @param identity 身份与规则（[DEFAULT_SYSTEM_PROMPT] 或调用方自定）。空白则不产生段。
     * @param toolHints 本回合**暴露**工具的 systemPrompt 片段（不是全量 registry 的）。
     *   已裁掉的工具不该还在提示里教用法——模型会去调一个已经不给它的工具。
     * @param memorySummary 记忆摘要；null 或纯空白视为「没有记忆」。
     * @param memoryUsagePrompt 记忆段的配套引导，与摘要同生同灭。
     */
    fun sections(
        identity: String,
        toolHints: List<String>,
        memorySummary: String?,
        memoryUsagePrompt: String
    ): List<Section> {
        val out = ArrayList<Section>(4)

        if (identity.isNotBlank()) {
            out += Section(KEY_IDENTITY, identity, Stability.FROZEN)
        }

        val hints = toolHints.filter { it.isNotBlank() }
        if (hints.isNotEmpty()) {
            out += Section(KEY_TOOLS, hints.joinToString("\n\n"), Stability.SESSION)
        }

        val summary = memorySummary?.trim().orEmpty()
        if (summary.isNotEmpty()) {
            out += Section(KEY_MEMORY, summary, Stability.TURN)
            out += Section(KEY_MEMORY_USAGE, memoryUsagePrompt, Stability.TURN)
        }

        return out
    }

    /** [sections] + [PromptAssembler.assemble] 的组合，调用方通常只需要这个。 */
    fun assemble(
        identity: String,
        toolHints: List<String>,
        memorySummary: String?,
        memoryUsagePrompt: String
    ): Prompt = PromptAssembler.assemble(sections(identity, toolHints, memorySummary, memoryUsagePrompt))
}
