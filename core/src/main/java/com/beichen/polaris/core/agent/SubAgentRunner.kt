package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.ToolApproval
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.agent.journal.RunScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * 子代理执行器（W-31）：把若干 [SubTask] 各交给一个**独立上下文**的 [RealAgentLoop] 跑，
 * 收集结论返回给主代理。
 *
 * ## 为什么值得存在
 * 主代理的上下文是稀缺资源：让它「读 8 个文件，只告诉我哪个定义了 X」会把这些文件的
 * 全文都灌进主对话，之后每一轮都要重新计费、还挤掉真正的任务信息。
 * 子代理把这段「脏活」关在**自己的消息栈**里，只把一段结论交回来。
 *
 * ## 四条边界（都写在代码里，不只是注释）
 *  1. **无副作用**：工具表按 [SubAgentLogic.NO_SIDE_EFFECT_TOOLS] 过滤，模型看不到写工具；
 *  2. **不递归**：白名单里没有 `spawn_agents`，子代理无法再派子代理；
 *  3. **有预算**：单任务超时 [timeoutMs]、最多 [maxSteps] 步、上下文 [budgetTokens]——
 *     子代理跑飞不会拖垮整轮任务；
 *  4. **审批可达（R4）**：即使白名单将来放进了需要确认的工具，子代理也带得动审批回调，
 *     不会出现「主代理弹窗、子代理静默放行」的两套标准。
 *
 * 并发用「分批 + 批内并行」而不是一次性全并发：手机端同时打 3 个请求最容易撞 429，
 * 分批能让前一批的 Key 冷却在下一批生效。
 */
class SubAgentRunner(
    private val llmProvider: () -> LLMClient,
    private val baseRegistry: () -> ToolRegistry,
    /** 单个子代理的总超时（毫秒）。 */
    private val timeoutMs: Long = 240_000L,
    /** 单个子代理最多几步工具往返。 */
    private val maxSteps: Int = 8,
    /** 单个子代理的上下文预算（token）。 */
    private val budgetTokens: Int = 12_000,
    /**
     * R4：子代理的审批回调（null = 不弹审批）。
     *
     * 当前白名单只含无副作用工具，所以传不传都「看起来一样」；但这是**今天**的事实，
     * 不是**契约**。R4 之前子代理构造 [RealAgentLoop] 时压根没这个参数，
     * 白名单一旦加进任何需要确认的工具，就是一条静默旁路。传上去，让安全属性
     * 由「白名单恰好没放危险工具」变成「执行器本身会问」。
     */
    private val approval: ToolApproval? = null
) {

    /**
     * 执行全部子任务；顺序与入参一致（汇总时能对上号）。
     * 外层协程取消（用户点停止）会传播进来，正在跑的子代理一起取消。
     */
    suspend fun run(
        tasks: List<SubTask>,
        concurrency: Int = SubAgentLogic.MAX_TASKS
    ): List<SubAgentOutcome> = coroutineScope {
        val limit = SubAgentLogic.clampConcurrency(concurrency, tasks.size)
        if (limit == 0) return@coroutineScope emptyList()
        tasks.chunked(limit).flatMap { batch ->
            batch.map { task ->
                async(Dispatchers.IO) { runOne(task) }
            }.awaitAll()
        }
    }

    /**
     * 跑一个子代理。
     *
     * 异常分三类，处理方式不同：
     *  - [TimeoutCancellationException]：子代理自己超时 → 记为失败，**不影响**其他子代理；
     *  - 其他 [CancellationException]：外层取消（停止按钮）→ 必须重抛，否则停止无效；
     *  - 其余异常：模型/网络问题 → 记为失败并带上原因。
     */
    private suspend fun runOne(task: SubTask): SubAgentOutcome {
        val started = System.currentTimeMillis()
        return try {
            // R7：子代理并入主回合的日志与时间线。
            //
            // 改造前子代理的 RealAgentLoop 压根没有日志句柄——它跑的工具调用在主回合
            // 日志里**一片空白**。用户报「卡住了」时，看到的是主代理在等，
            // 完全看不见子代理在干什么；而子代理虽然只有无副作用工具，
            // 脚本形态技能却能写文件——那就是真实副作用，却没有任何记录。
            //
            // 并入主回合文件（而不是自己开一个）是为了让崩溃恢复保持**单文件自洽**：
            // 恢复器逐文件判定「这个回合有没有未闭合的调用」，拆成多文件就得跨文件聚合。
            val parent = currentAgentScope()
            val subScope = RunScope.subagent(task.title)
            val subTurn = parent?.turn?.scoped(subScope)
            val history = withTimeout(timeoutMs) {
                RealAgentLoop(
                    llmProvider = llmProvider,
                    registry = buildRegistry(),
                    systemPrompt = SubAgentLogic.systemPrompt(task.title),
                    maxSteps = maxSteps,
                    maxParallelTools = 2,
                    contextBudgetTokens = budgetTokens,
                    // R4：审批必须穿过子代理边界——见构造参数注释
                    approval = approval,
                    // 白名单只有十几个工具，不能被「小模型核心集」再裁掉——
                    // 子代理拿不到 read_file 就等于瞎了
                    forceFullTools = true,
                    // R7：写进主回合日志，事件带 scope=sub:<标题>
                    journalTurn = subTurn,
                    scope = subScope
                ).send(SubAgentLogic.userPrompt(task.instruction), emptyList(), emptyList())
            }
            val texts = history.mapNotNull { (it as? AssistantMessage)?.content }
            SubAgentOutcome(
                title = task.title,
                ok = true,
                text = SubAgentLogic.extractConclusion(texts),
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: TimeoutCancellationException) {
            SubAgentOutcome(
                title = task.title,
                ok = false,
                text = "超时（${timeoutMs / 1000} 秒）",
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SubAgentOutcome(
                title = task.title,
                ok = false,
                text = SubAgentLogic.describeFailure(e),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    /** 每次执行现建一个子工具表：主表可能被 MCP 运行时增删，用快照更安全。 */
    private fun buildRegistry(): ToolRegistry {
        val sub = ToolRegistry()
        baseRegistry().list().forEach { tool ->
            if (tool.name in SubAgentLogic.NO_SIDE_EFFECT_TOOLS) sub.register(tool)
        }
        return sub
    }
}
