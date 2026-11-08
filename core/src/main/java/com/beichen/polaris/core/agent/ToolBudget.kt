package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool

/**
 * 工具预算解析（R6）。
 *
 * ## 为什么需要「按工具覆盖」
 * R2 的工具级预算只有两档（只读 90s / 写 600s），一刀切的结果是**两头不讨好**：
 * - `gradle build`、`git clone` 这类真实的长任务，写预算 600s 不够，
 *   超时的代价是「状态未知」——模型不敢重试，用户也不敢确认；
 * - `http_request` 这类实际几十秒就完的只读工具，90s 纯属干等——
 *   卡住的网络连接白占一个工具槽位。
 *
 * 分档只解决了「只读 vs 写」这一个维度，**没解决「同一个工具在不同时长下」**。
 * 按工具覆盖才是真正的粒度。
 *
 * ## 优先级（三层，自上而下）
 * 1. **工具自声明** [AgentTool.timeoutBudgetMs] —— 工具作者最清楚自己要跑多久
 *    （`shell_run` 知道命令可能是编译，`http_request` 知道就是个网络往返）；
 * 2. **分档默认** —— executor 注入的只读 / 写预算（未被声明覆盖时用）。
 *
 * ## 夹紧规则（为什么需要）
 * 自声明值来自代码，但也可能写错。三条边界：
 * - **下界** [MIN_DECLARED_BUDGET_MS]：小于 1 秒的预算等于「立刻判死」，
 *   工具连启动都没来得及，多半是写错了单位（秒写成毫秒）；
 * - **上界** [maxBudgetMs]：不能超过回合软截止（30 分钟）——
 *   超过它的预算毫无意义，回合早就在步边界收尾了，工具还挂着只是在拖。
 *
 * 夹紧只作用于**工具自声明值**；executor 注入的分档默认**原样使用**——
 * 测试要靠注入 80ms 这样的极小值来断言超时路径，若把它也夹住，超时分支就永远测不到。
 */
object ToolBudget {

    /**
     * 工具自声明预算的下界（毫秒）：1 秒。
     *
     * 小于 1 秒基本等于「立刻判死」，通常是单位写错。
     */
    const val MIN_DECLARED_BUDGET_MS: Long = 1_000L

    /**
     * 工具自声明预算的默认上界（毫秒）：30 分钟。
     *
     * 与 [RealAgentLoop] 的回合软截止一致——预算超过回合上限没有意义：
     * 回合会在步边界收尾，工具还在跑只是在拖慢收尾。
     */
    const val MAX_DECLARED_BUDGET_MS: Long = 30 * 60_000L

    /**
     * 解析一次工具调用的预算（毫秒）。
     *
     * @param tool 目标工具。
     * @param readOnlyBudgetMs 只读默认预算（executor 注入）。
     * @param writeBudgetMs 写操作默认预算（executor 注入）。
     * @param maxBudgetMs 自声明值的上界。
     * @return 最终预算。
     */
    fun resolve(
        tool: AgentTool,
        readOnlyBudgetMs: Long,
        writeBudgetMs: Long,
        maxBudgetMs: Long = MAX_DECLARED_BUDGET_MS,
    ): Long {
        val declared = declaredBudgetOf(tool)
        if (declared == null) {
            // 未声明：分档默认**原样使用**（测试注入的极小值必须原样生效）
            return if (readOnlyOf(tool)) readOnlyBudgetMs else writeBudgetMs
        }
        // 自声明：夹到安全区间
        val lower = MIN_DECLARED_BUDGET_MS
        val upper = maxOf(maxBudgetMs, lower)
        return declared.coerceIn(lower, upper)
    }

    /**
     * 读工具自声明的预算；读不到时退回 `null`（= 用分档默认）。
     *
     * 公开是因为 [RegistryToolExecutor] 还要用它决定「要不要报夹紧警告」——
     * 那条警告必须拿原始声明值比对，不能再走一次 [resolve]。
     */
    fun declaredBudgetOf(tool: AgentTool): Long? = safeMeta(tool, null) { it.timeoutBudgetMs }

    /** 读只读标记；理由见 [safeMeta]。读不到按**写**处理（更保守）。 */
    fun readOnlyOf(tool: AgentTool): Boolean = safeMeta(tool, false) { it.readOnly }

    /** 读「是否在等用户」；理由见 [safeMeta]。读不到按**不等**处理（走超时护栏）。 */
    fun awaitsUserOf(tool: AgentTool): Boolean = safeMeta(tool, false) { it.awaitsUser }

    /**
     * 读工具的元数据属性，容忍**二进制不兼容**。
     *
     * ## 为什么要吞 [LinkageError]
     * `timeoutBudgetMs` / `readOnly` / `awaitsUser` 都是**接口默认方法**。Kotlin 在
     * `-Xjvm-default=disable`（默认模式）下把它们编译成「接口里的抽象方法 +
     * `AgentTool$DefaultImpls` 静态实现」，由每个实现类生成桥接转发。
     *
     * 于是存在一种真实的二进制不兼容：接口新增默认方法后，若某个实现类**没有重新编译**
     * （增量构建的典型产物：改接口的那次没带上这个包），它的 class 里没有对应桥接，
     * `invokeinterface` 直接抛 [AbstractMethodError]。
     *
     * 这不是理论风险——R7 真机验证时 `use_skill` 就栽在这里：工具本身完好，
     * 却在「读预算」这一步让整个调用崩掉，日志只有一行
     * `use_skill 执行异常 / AbstractMethodError`，看起来像技能执行失败。
     *
     * 这三项都是**护栏参数**，不是业务逻辑。读不到就退回最保守的一侧
     * （写操作、有超时），让工具照常执行，比让整个调用死在一个可选属性上合理。
     * 记 warn 是为了不静默：这种不兼容说明构建产物陈旧，需要 clean 重建。
     */
    private fun <T> safeMeta(tool: AgentTool, fallback: T, read: (AgentTool) -> T): T =
        try {
            read(tool)
        } catch (e: LinkageError) {
            AgentLogger.w(
                "Tool",
                "工具 ${safeName(tool)} 的元数据不可读（${e::class.java.simpleName}）：" +
                    "实现类可能按旧版 AgentTool 编译，已退回最保守默认；建议 clean 重建"
            )
            fallback
        }

    /** 工具名本身也可能因同类不兼容读不到，取不到就给个占位，别让日志再抛一次。 */
    private fun safeName(tool: AgentTool): String = safeMeta(tool, "<unnamed>") { it.name }

    /**
     * 夹紧后的值与原值不同时记一条警告——静默改写预算会让排障时对不上数。
     *
     * 非挂起，可从任何上下文调用。
     */
    fun warnIfClamped(toolName: String, declared: Long, resolved: Long) {
        if (declared == resolved) return
        AgentLogger.w(
            "Tool",
            "工具 $toolName 声明的预算 ${declared}ms 超出允许区间，已夹为 ${resolved}ms"
        )
    }
}
