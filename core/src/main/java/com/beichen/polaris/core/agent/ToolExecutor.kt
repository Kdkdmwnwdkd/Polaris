package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.GuardVerdict
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.LogRedaction
import com.beichen.polaris.core.MAX_TOOL_RESULT_CHARS
import com.beichen.polaris.core.ToolApproval
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.ToolPart
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.formatToolResult
import com.beichen.polaris.core.isTransientFailureCode
import com.beichen.polaris.core.truncateToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单次工具调用的默认预算（R2 三层超时的第二层，毫秒）。
 *
 * 为什么分两档而不是一个数：只读调用超时的代价是「浪费一次调用」，写调用超时的
 * 代价是「不知道改没改」——后者必须给足时间，前者卡着只是在白等。
 *
 * 取值依据：
 *  - 只读 90s：截图/OCR/读大文件/一次网络查询都在秒级，90 秒还没回来基本是卡死；
 *  - 写 600s：`gradle build`、装 APK、批量写文件都可能跑几分钟，卡太紧会误杀正常任务。
 *    真正需要更久的长任务应走 `shell_background`（有独立作业通道），而不是让回合干等。
 *
 * 注意这是**保底**而不是**精确控制**：`withTimeoutOrNull` 只能取消可挂起的执行，
 * 若工具内部用阻塞 IO / `runBlocking` 且不响应取消，超时只会停止「等待」，
 * 后台线程仍会跑完。这也是超时结果必须按「可能已生效」措辞的原因。
 */
/** internal 而非 private：技能路径（R7）复用同一套默认预算，避免两处数字漂移。 */
internal const val DEFAULT_READ_ONLY_BUDGET_MS = 90_000L
internal const val DEFAULT_WRITE_BUDGET_MS = 600_000L

/**
 * 单次工具调用的产出（T01，本任务最微妙处，吃透再写）。
 *
 * 三分支语义边界（直接决定「回不回喂模型」）：
 *  - [Completed]：调用有确定终态——**含全部业务失败**（工具自己返回
 *    ToolResult.fail、未知工具、熔断拦截、审批拒绝、工具 execute 抛异常）。
 *    这些一律由 executor 内部捕获并包装成错误 ToolMessage 回喂模型
 *    （T02 自纠语义，必须保持：错误信息即修复指引）。
 *  - [Interrupted]：确定未完成、无副作用（只读工具被 kill）。仅 [ToolExecutor.onInterrupt] 产出。
 *  - [Unknown]：可能已有副作用（写工具被 kill），状态未知。仅 [ToolExecutor.onInterrupt] 产出。
 *
 * 注意 [Completed] 与中断态的**生产者分离**：execute() 正常路径只产出
 * Completed（业务失败也在内）；Interrupted/Unknown 只在取消路径由
 * onInterrupt() 产出。execute() 若违规返回中断态，RealAgentLoop 有防御填充
 * （见 RealAgentLoop.executeOne），不变量不依赖 executor 自律。
 */
sealed interface ToolCallOutcome {

    /**
     * 调用有确定终态，携带回喂模型的 ToolMessage 与工具返回的图片 url 列表。
     *
     * @property message 回喂历史 / UI 的 ToolMessage（统一契约文本 + T06 截断）。
     * @property images 工具返回的图片（如 ui_screenshot），由 loop 汇合回灌视觉模型。
     */
    data class Completed(
        val message: ToolMessage,
        val images: List<String>,
        /**
         * R5：结果码（如 "ok" / "approval_denied" / "tool_timeout_unknown"）。
         *
         * 为什么要单独带一个码，而不是从 [ToolMessage.content] 里解析：
         * content 是**给人看**的格式化文本（"[ok] code=xxx\nmessage: ..."），
         * 解析它等于把排障能力绑在展示格式上——改一次文案就断一次排障。
         *
         * 失败原因千差万别（审批被拒 / 超时未生效 / 熔断 / 未知工具 / 工具异常），
         * 只记 isError 等于没记。
         */
        val code: String = "ok",
        /**
         * 工具返回的**原始结果**（未经 [formatToolResult] 包装）。
         *
         * 为什么要有它：`message.content` 是给模型看的展示文本，前面拼着
         * "[ok] code=read_ok / message: 已读取 …"。技能脚本要的是文件原文，
         * 拿展示文本去统计会系统性偏大——真机上 wordcount 报 22 词，
         * 文件实际只有 13 词，多出来的全是那句「已读取」。
         *
         * 只走主成功路径填充；错误路径为 null，消费方需回退到 `message.content`。
         */
        val result: com.beichen.polaris.core.ToolResult? = null
    ) : ToolCallOutcome

    /** 确定未完成、无副作用（只读工具被 kill）。 */
    data object Interrupted : ToolCallOutcome

    /** 可能已有副作用（写工具被 kill），状态未知。 */
    data object Unknown : ToolCallOutcome

    /**
     * 未派发：调用已声明、但执行边界根本没越过（崩在 `ToolDispatched` 落盘之前）。
     *
     * 与 [Interrupted] 的区别是判据不同：Interrupted 看「工具有没有副作用」，
     * 本态看「日志里有没有派发记录」。两者正交，合起来才是完整判据——
     * 未派发时无论副作用类别，重试都安全，不需要走「先核对」流程。
     */
    data object NotDispatched : ToolCallOutcome
}

/**
 * executor 自身违约异常（T01 验收③）：executor **实现代码**抛异常
 * （NPE / Error / 测试替身故意抛的 RuntimeException 等——契约漏洞，
 * 区别于工具 execute 抛的业务异常）。
 *
 * 语义：RealAgentLoop 在回合级兜底捕获后**整个回合失败**（本异常上抛给
 * 调用方），该调用**不回喂模型**——业务 bug 显形，而不是被悄悄包装成
 * 一条错误 ToolMessage 让模型「自纠」一个根本不存在的工具问题。
 * CancellationException 永远不是失败：绝不被包装成本异常（见 execute 约定）。
 *
 * @property toolName 出错的工具名（消息内含，便于日志定位）。
 * @property reason 原始违约异常（作为 cause 保留）。
 */
class ToolExecutionFailedException(
    val toolName: String,
    val reason: Throwable
) : RuntimeException(
    "工具执行器违约（executor 实现代码抛异常，非工具业务失败）：tool=$toolName, " +
        "cause=${reason.javaClass.simpleName}: ${reason.message}",
    reason
)

/**
 * 工具执行器契约（T01）：loop 只认本接口，执行策略（真 registry / 沙箱 /
 * 远端 MCP / 测试替身）全部内聚在实现类内。
 *
 * 调用约定：
 *  - execute() **永不抛异常**（契约目标）：业务失败一律转 [ToolCallOutcome.Completed]
 *    错误 ToolMessage；实现若违约抛异常，由 loop 回合级兜底转
 *    [ToolExecutionFailedException]（验收③）。
 *  - CancellationException 永远不是失败：execute() 不得将其吞成任何 outcome，
 *    必须原样上抛（T05：由树尾闭合收尾）。
 *  - onInterrupt() 在取消路径被 loop 询问「在飞的这一调用算 Interrupted 还是
 *    Unknown」，由实现按工具 readOnly 自判；非挂起函数，可在 NonCancellable 内安全调用。
 */
interface ToolExecutor {

    /**
     * 执行一次工具调用。
     *
     * @param call 调用请求（id / name / arguments）。
     * @param descriptor loop 解析出的工具描述符；工具不在本回合视图里时为 loop 合成的占位
     *   descriptor（name=call.name、wireName=call.name、inputSchemaJson="{}"），
     *   实现应按「分组未启用」与「名称写错」分流文案（前者指出所属组，后者给模糊匹配）。
     * @return [ToolCallOutcome.Completed]（正常与业务失败）——中断态只由
     *   [onInterrupt] 产出。
     */
    suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome

    /**
     * 取消路径询问：在飞的这一调用算 [ToolCallOutcome.Interrupted]（只读，确定
     * 无副作用）还是 [ToolCallOutcome.Unknown]（写操作，可能已有副作用）。
     * loop 据此生成树尾闭合的 ToolResult 文案（code=cancelled / cancelled_unknown）。
     */
    fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome
}

/**
 * 批次感知的 executor 扩展（T04 同批重复熔断需要，可选实现）。
 *
 * 「同批重复」是**批次级**判定（同一 Assistant 一批调用里出现完全相同的
 * 同名同参调用，第二个起拦截），只有 loop 知道批次边界，故由 loop 预计算
 * 位置标志（与旧 AgentLoop.executeBatch 的 duplicate 预计算逐行等价），
 * 经本接口的 execute 重载传给 executor。
 *
 * 未实现本接口的 executor（如 harness 测试替身）自行忽略同批重复语义，
 * 只认基础 [ToolExecutor.execute] 契约。
 */
interface BatchToolExecutor : ToolExecutor {

    /**
     * 同 [ToolExecutor.execute]，额外携带批次预计算标志。
     *
     * @param duplicateInBatch 本调用在同一批次内是否已有同指纹（同名同参）前驱。
     */
    suspend fun execute(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome
}

/**
 * 基于 ToolRegistry 的默认 [ToolExecutor]（T01）：旧 AgentLoop.executeCallInner
 * 的全部逻辑原样搬入——未知工具 / 审批 / 熔断 / 执行 / onExecuted / 截断格式化 /
 * 图片收集，产出 [ToolCallOutcome.Completed]。
 *
 * 依赖注入而非自持：registry / failureGuard / approval / hooks 全部由构造参数
 * 传入——failureGuard 持有在 loop 上（T04 熔断状态不随 executor 每批重建丢失；
 * W-8 起其生命周期改为**回合作用域**：loop.send 入口 resetForTurn，回合内跨批保留、
 * 跨回合不继承），registry 由 loop 每批按当前字段构造本类（update(registry) 即时生效）。
 */
class RegistryToolExecutor(
    private val registry: ToolRegistry,
    private val failureGuard: FailureGuard,
    private val approval: ToolApproval? = null,
    private val hooks: Hooks = NoopHooks,
    /**
     * W-11：单条结果字符上限。默认全局 [MAX_TOOL_RESULT_CHARS]；AgentLoop 会按上下文
     * 预算传入更小值（本地 2k 预算 → 2.4k 字符），否则一条大结果单条就撑爆 n_ctx。
     */
    private val maxResultChars: Int = MAX_TOOL_RESULT_CHARS,
    /**
     * R1：越过执行边界前的最后一道问询——返回 false 表示「这一步不能执行」。
     *
     * 位置很关键：它在**审批通过之后、真正调用工具之前**。早先放在 executor 之外
     * （executeOne 开头）时，审批弹窗那段等待也被算进了「已越过执行边界」——
     * 用户在弹窗上犹豫时进程被杀，重启后会被告知「写操作可能已生效」，
     * 实际上一次都没跑。放到这里，日志记的才是「真的要动手了」。
     *
     * 注入方是 AgentLoop（写回合日志）；测试替身不传即无此关卡，行为与改造前一致。
     */
    private val beforeEffect: (suspend (ToolCall) -> Boolean)? = null,
    /**
     * 全量工具注册表：用于区分「工具根本不存在」与「工具存在但所属分组未启用」。
     *
     * 背景：回合级工具视图（`RealAgentLoop.scopedRegistry`）只装本回合暴露的工具，
     * 被裁掉的工具在这个视图里查无此工具。若一律回「未知工具」，模型会以为自己
     * 记错了名字，转而去猜别的名字——而它其实**没记错**，只是少开了一个组。
     *
     * 注入 null 时行为与改造前逐字节相同（一律按「未知工具」处理）。
     *
     * 注入方是 RealAgentLoop（它同时持有全量 registry 与回合级视图）；
     * 技能路径（SkillExecutor）用不到这个概念，不注入。
     */
    private val fullRegistry: ToolRegistry? = null,
    /**
     * R2：单次调用预算（毫秒），按副作用分档。测试注入小值（如 50ms）即可断言超时路径。
     */
    private val readOnlyBudgetMs: Long = DEFAULT_READ_ONLY_BUDGET_MS,
    private val writeBudgetMs: Long = DEFAULT_WRITE_BUDGET_MS,
    /**
     * R6：工具自声明预算的上界（毫秒）。默认回合软截止（30 分钟）。
     *
     * 超过它的自声明预算毫无意义——回合早就在步边界收尾了。
     */
    private val maxDeclaredBudgetMs: Long = ToolBudget.MAX_DECLARED_BUDGET_MS
) : BatchToolExecutor {

    /** 基础契约入口：duplicateInBatch 缺省 false（单发调用无同批前驱）。 */
    override suspend fun execute(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome =
        execute(call, descriptor, duplicateInBatch = false)

    /**
     * 执行一次调用：beforeToolCall 钩子 → 全部业务分支（未知/审批/熔断/执行/异常）
     * → afterToolCall 钩子（仅产出 Completed 时命中，与 beforeToolCall 严格配对）。
     */
    override suspend fun execute(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome {
        hooks.beforeToolCall(call)
        val outcome = executeInner(call, descriptor, duplicateInBatch)
        if (outcome is ToolCallOutcome.Completed) {
            hooks.afterToolCall(call, outcome.message)
        }
        return outcome
    }

    /**
     * 单步执行：未知工具、审批拒绝、熔断拦截、异常都包装成 ToolMessage 进入历史
     * （T02 自纠语义）。若工具返回图片（ToolPart.Image），其 url 进 Completed.images
     * 供 loop 回灌视觉模型。
     */
    private suspend fun executeInner(
        call: ToolCall,
        descriptor: ToolDescriptor,
        duplicateInBatch: Boolean
    ): ToolCallOutcome {
        val tool = registry.get(call.name)
            ?: run {
                // 「查无此工具」有两种截然不同的原因，**必须分开回喂**：
                //
                //  A. 工具根本不存在 → 模型记错了名字 → 该改名字/换工具；
                //  B. 工具存在，但所属分组本回合未启用 → 模型**没记错**，只是少开一个组
                //     → 该先 `__use_tools` 启用它，下一轮就能直接调。
                //
                // 统一说「未知工具」会让模型在 B 情形下去猜别的名字，把一次「开组」
                // 能解决的往返变成若干次瞎猜 + 失败回填。
                //
                // 判据：全量注册表（fullRegistry）里有它、而本回合视图里没有 → B。
                val group = fullRegistry
                    ?.takeIf { it.get(call.name) != null }
                    ?.let { KernelExposure.groupOf(call.name) }
                if (group != null) {
                    AgentLogger.w("Tool", "工具未启用：${call.name}（组 $group）——请先 __use_tools 启用该组")
                    return ToolCallOutcome.Completed(
                        toolMessageOf(
                            call,
                            ToolResult.fail(
                                code = "tool_not_enabled",
                                message = registry.toolNotEnabledMessage(call.name, group),
                                hint = "先调用 __use_tools 启用 '${group}' 组（参数 groups=\"$group\"），" +
                                    "下一轮即可直接调用 ${call.name}；不要重复以同一个未启用的工具名重试。"
                            )
                        ),
                        emptyList(),
                        code = "tool_not_enabled"
                    )
                }
                // T02：未知工具 + 编辑距离 ≤2 模糊匹配建议，让模型从错误里自纠
                AgentLogger.w("Tool", "未知工具：${call.name}（wireName=${descriptor.wireName}）")
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(
                            code = "unknown_tool",
                            message = registry.unknownToolMessage(call.name),
                            hint = "从当前可用工具列表中选择正确名称重新调用；若任务无法完成请直接告知用户。"
                        )
                    ),
                    emptyList(),
                    code = "unknown_tool"
                )
            }

        // T04：失败熔断 + 指纹去重——命中则直接回错误结果，不走真实执行
        val fingerprint = failureGuard.fingerprint(call.name, call.arguments)
        when (val verdict = failureGuard.check(fingerprint, duplicateInBatch)) {
            is GuardVerdict.Blocked -> {
                AgentLogger.w("Tool", "熔断拦截 ${call.name}：${verdict.message}")
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(verdict.code, verdict.message, verdict.hint)
                    ),
                    emptyList(),
                    code = verdict.code
                )
            }
            GuardVerdict.Allow -> Unit
        }

        // W-79：审批判定从「工具级布尔」细化到「参数级」——shell_run 的 `ls` 不该弹窗，
        // `rm -rf` 必须弹。默认实现仍返回 needsApproval，其余工具行为不变。
        if (tool.needsApprovalFor(call.arguments)) {
            // 审计 F4：审批通道缺失时**拒绝**，不是放行。
            //
            // 修前是 `tool.needsApprovalFor(...) && approval != null`——「忘记注入 approval」
            // 与「该工具免审」在行为上完全等价。今天三个构造点都传了值，所以没有可触发的
            // 漏洞；但这是安全闸门，缺省方向必须是关：宁可让一次调用失败并说明原因，
            // 也不要让它在没有用户确认的情况下真的执行了。
            val approver = approval ?: return ToolCallOutcome.Completed(
                toolMessageOf(
                    call,
                    ToolResult.fail(
                        code = "no_approver",
                        message = "该操作需要用户确认，但当前执行通道没有审批能力，已拒绝执行（${call.name}）。",
                        hint = "不要重复发起相同调用；请回到主对话中直接调用该工具，由用户确认后再执行。"
                    )
                ),
                emptyList(),
                code = "no_approver"
            )
            val allowed = try {
                approver(tool, call.arguments)
            } catch (cancel: CancellationException) {
                // 取消不是拒绝：上抛由树尾闭合处理，绝不吞成「用户拒绝」
                throw cancel
            } catch (e: Throwable) {
                AgentLogger.e("Tool", "审批回调异常：${e.message}")
                false
            }
            if (!allowed) {
                AgentLogger.w("Tool", "审批未通过，拒绝执行 ${call.name}")
                return ToolCallOutcome.Completed(
                    toolMessageOf(
                        call,
                        ToolResult.fail(
                            code = "approval_denied",
                            message = "用户拒绝了该危险操作（${call.name}）。",
                            hint = "不要重复发起相同调用；可改换非危险工具完成，或向用户说明需要该操作的原因。"
                        )
                    ),
                    emptyList(),
                    code = "approval_denied"
                )
            }
        }
        // R1「先落盘再执行」：日志写不进就**不越过执行边界**。
        //
        // 只读放行，写操作拒绝。这个不对称是有意的：崩溃时只读调用最多被重放一次
        // （浪费一次调用），而写操作重放是数据损坏；反过来，因为日志坏掉就一律拒绝，
        // 会让存储吃紧的手机连「读一下这个文件」都做不到——那不是保护，是不能用。
        //
        // 未注入日志（beforeEffect == null）时直接放行，行为与改造前一致。
        if (beforeEffect?.invoke(call) == false && !ToolBudget.readOnlyOf(tool)) {
            AgentLogger.w("Tool", "日志不可写，拒绝写操作 ${call.name}（只读不受影响）")
            return ToolCallOutcome.Completed(
                toolMessageOf(
                    call,
                    ToolResult.fail(
                        code = "journal_unavailable",
                        message = "运行日志写入失败（存储可能已满），为避免同一操作被执行两次，本次写操作未执行。",
                        hint = "请告知用户清理存储空间后重试；只读工具不受影响，可先用只读方式确认当前状态。"
                    )
                ),
                emptyList()
            )
        }
        // 审计 F13：敏感判据不能只查静态名单。MCP 工具是运行期注册的、不在 ToolCatalog 里，
        // 查表恒为 false —— 而它们的入参与输出正是「外部服务能看到的全部内容」。
        // 判据在这里算，因为只有这里拿得到工具实例（`tool.needsApprovalFor`）。
        val logSensitive = LogRedaction.shouldRedact(
            call.name,
            runCatching { tool.needsApprovalFor(call.arguments) }.getOrDefault(tool.needsApproval)
        )
        AgentLogger.toolCall(call.name, call.arguments.toString().take(300), logSensitive)
        return try {
            val start = System.currentTimeMillis()
            // R2 第二层：工具级预算。null = 本层超时（工具内部自己抛的
            // TimeoutCancellationException 也会被这里吞成 null，同样按超时处理）；
            // 其余 CancellationException 原样传播——用户点停止是取消，不是超时。
            // R6：预算按「工具自声明 > 只读/写分档」解析，自声明值夹在安全区间内。
            val budget = ToolBudget.resolve(tool, readOnlyBudgetMs, writeBudgetMs, maxDeclaredBudgetMs)
            ToolBudget.declaredBudgetOf(tool)?.let { declared ->
                ToolBudget.warnIfClamped(tool.name, declared, budget)
            }
            // 等用户的工具不加预算：`ask_user` / `plan_propose` 标了 readOnly（确实无副作用），
            // 但它们挂起时是在等人思考，不是卡死。用 90 秒把用户的问题掐掉是纯回归。
            // 这两类工具自己有等待上限（180s / 300s），超时文案也更准确。
            val result = if (ToolBudget.awaitsUserOf(tool)) {
                tool.execute(call.arguments)
            } else {
                withTimeoutOrNull(budget) { tool.execute(call.arguments) }
                    ?: return timeoutOutcome(
                        call, tool.name, ToolBudget.readOnlyOf(tool), fingerprint, budget,
                        System.currentTimeMillis() - start
                    )
            }
            val cost = System.currentTimeMillis() - start
            val imgUrls = result.parts.filterIsInstance<ToolPart.Image>().map { it.url }
            // T04：成功清指纹，失败累加计数；W-8：瞬时故障（服务未就绪等）三不——
            // 不计数不清除，环境就绪后原样重试放行（修真机日志恒定指纹被毒化挡死）
            failureGuard.onExecuted(
                fingerprint,
                failed = result.isError,
                transient = result.isError && isTransientFailureCode(result.code)
            )
            // T02 + T06：统一契约格式化 + 单结果截断（UI 与模型历史共用同一份文本）
            val text = truncateToolResult(formatToolResult(result), maxResultChars)
            AgentLogger.toolResult(call.name, !result.isError, cost, text.take(300), logSensitive)
            ToolCallOutcome.Completed(
                ToolMessage(call.id, tool.name, call.arguments, text, result.isError, imgUrls),
                imgUrls,
                code = result.code,
                result = result
            )
        } catch (cancel: CancellationException) {
            // 取消不是失败：不上计数、不造假结果，直接上抛由树尾闭合补全
            throw cancel
        } catch (e: Throwable) {
            failureGuard.onExecuted(fingerprint, failed = true)
            AgentLogger.e("Tool", "${call.name} 执行异常", e)
            ToolCallOutcome.Completed(
                toolMessageOf(
                    call,
                    ToolResult.fail(
                        code = "exception",
                        message = "工具执行异常：${e.message}",
                        hint = "检查参数格式与取值；如持续失败请换用其他工具或告知用户。"
                    )
                ),
                emptyList(),
                code = "exception"
            )
        }
    }

    /**
     * R2 第二层超时的产出。
     *
     * 仍然是 [ToolCallOutcome.Completed]——超时是有确定终态的「本次等待结束」，
     * 必须回喂模型（T02 自纠语义），而不是让整轮失败。区别只在文案与熔断计数：
     *
     *  - **只读**：确定没有副作用，`transient = true`（不毒化指纹），文案允许直接重试；
     *  - **写操作**：可能已经改了东西，按失败计数（重复超时会被熔断挡住），
     *    文案要求先核对再决定——与取消路径 `cancelled_unknown` 同一套措辞逻辑。
     */
    private fun timeoutOutcome(
        call: ToolCall,
        toolName: String,
        readOnly: Boolean,
        fingerprint: String,
        budgetMs: Long,
        costMs: Long
    ): ToolCallOutcome.Completed {
        val seconds = budgetMs / 1000
        // 只读超时是环境性的（服务没起、网络卡住），与 service_not_ready 同类：不计入熔断。
        // 写超时相反——同一个写调用连续超时还让它重试，就是在拿数据赌运气。
        failureGuard.onExecuted(fingerprint, failed = true, transient = readOnly)
        AgentLogger.w(
            "Tool",
            "工具超时 $toolName：${seconds}s（${if (readOnly) "只读" else "写操作"}，实际等待 ${costMs}ms）"
        )
        val result = if (readOnly) {
            ToolResult.fail(
                code = "tool_timeout",
                message = "工具「$toolName」执行超时（${seconds} 秒），已停止等待。",
                hint = "只读调用没有副作用，可以直接重试；若连续超时说明该工具当前不可用，请换用其他工具或告知用户。"
            )
        } else {
            ToolResult.fail(
                code = "tool_timeout_unknown",
                message = "工具「$toolName」执行超时（${seconds} 秒），已停止等待。" +
                    "这是写操作，可能已经产生副作用，当前状态未知。",
                hint = "不要直接重试。先用只读方式核对结果（读文件 / 查状态 / 看进程），" +
                    "确认没有生效再决定是否重做；无法确认时告知用户。"
            )
        }
        AgentLogger.toolResult(toolName, false, costMs, result.message)
        return ToolCallOutcome.Completed(
            toolMessageOf(call, result), emptyList(), code = result.code, result = result
        )
    }

    /**
     * 取消路径自判（T01）：只读工具 → [ToolCallOutcome.Interrupted]（确定未完成、
     * 无副作用）；写工具 → [ToolCallOutcome.Unknown]（可能已有副作用）。
     * 未知工具按只读处理（它只会得到一条错误结果，无副作用——与
     * [ToolRegistry.batchHasMutation] 的未知工具判据一致）。
     */
    override fun onInterrupt(call: ToolCall, descriptor: ToolDescriptor): ToolCallOutcome {
        val readOnly = registry.get(call.name)?.let { ToolBudget.readOnlyOf(it) } ?: true
        AgentLogger.w(
            "Tool",
            "onInterrupt：${call.name} 判为 ${if (readOnly) "Interrupted（只读，确定未完成）" else "Unknown（写操作，状态未知）"}"
        )
        return if (readOnly) ToolCallOutcome.Interrupted else ToolCallOutcome.Unknown
    }
}

/**
 * ToolResult → ToolMessage 的单一构造点（T01 抽出，executor 与 loop 共用）：
 * 统一契约文本（[formatToolResult]）+ T06 单结果截断（[truncateToolResult]），
 * UI 与模型历史同源。纯函数。
 */
internal fun toolMessageOf(
    call: ToolCall,
    result: ToolResult,
    images: List<String> = emptyList()
): ToolMessage = ToolMessage(
    id = call.id,
    name = call.name,
    arguments = call.arguments,
    content = truncateToolResult(formatToolResult(result)),
    isError = result.isError,
    images = images
)

/**
 * 中断态（Interrupted / Unknown / NotDispatched）→ 回喂的错误 ToolMessage。
 *
 * 与 [toolMessageOf] 放一起：两者都是「执行结局 → 回喂文本」的构造点，
 * 分开会让同一套文案散在两个文件里。树尾闭合与防御填充共用本函数。
 *
 * - Interrupted（只读，确定未完成、无副作用）→ `cancelled`；
 * - Unknown（写操作被 kill，可能已有副作用）→ `cancelled_unknown`，要求模型先确认再重试；
 * - NotDispatched（未越过执行边界）→ `not_dispatched`，可直接重试。
 */
internal fun interruptToolMessage(call: ToolCall, verdict: ToolCallOutcome): ToolMessage =
    when (verdict) {
        is ToolCallOutcome.Interrupted -> toolMessageOf(
            call,
            ToolResult.fail(
                code = "cancelled",
                message = "该调用已被用户停止，未取得真实结果。",
                hint = "如仍需此操作，请在下一轮重新调用 ${call.name}（可调整参数）。"
            )
        )

        is ToolCallOutcome.Unknown -> toolMessageOf(
            call,
            ToolResult.fail(
                code = "cancelled_unknown",
                message = "该调用已被用户停止，执行状态未知（写操作可能已产生部分副作用）。",
                hint = "请先向用户确认 ${call.name} 是否已实际生效，再决定是否重试；不要原样重复调用。"
            )
        )

        // 未越过执行边界：无论工具是否有副作用，都不可能产生效果，可直接重试。
        // 与 Interrupted 分开是必要的——把这种情况也说成"先确认"，会让模型白问一轮。
        is ToolCallOutcome.NotDispatched -> toolMessageOf(
            call,
            ToolResult.fail(
                code = "not_dispatched",
                message = "该调用尚未开始执行就被中断，没有产生任何效果。",
                hint = "可以安全地重新调用 ${call.name}。"
            )
        )

        is ToolCallOutcome.Completed -> verdict.message
    }
