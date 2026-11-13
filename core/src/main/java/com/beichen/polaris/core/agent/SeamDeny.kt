package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolResult

/**
 * 守卫裁决的落地形态：把 [GuardDecision.Deny] 转成一条**可回喂的错误结果**。
 *
 * 返回 null = 放行。
 *
 * ## 为什么单独一个文件（而不是写在 AgentSeams.kt 里）
 *
 * 这里依赖 [toolMessageOf]（在 ToolExecutor.kt）。而 AgentSeams.kt 要被 QA harness
 * **独立编译**——它一 import ToolExecutor，整条依赖链（registry / approval / exposure）
 * 都得跟着编进来，harness 就跑不动了。拆开后：AgentSeams 保持纯数据 + 纯分发，
 * 本文件负责与 executor 的契约对接，两边各管一件事。
 *
 * ## 文案形状与既有拒绝路径一致
 *
 * `approval_denied` / `tool_not_enabled` / `unknown_tool` 都是「code + message + hint」
 * 三件套回喂。模型是按这套格式学会「这条失败了、下一步怎么改」的；守卫拒绝长得不一样，
 * 模型会当成一次工具崩溃，转而重试同一调用。
 */
internal suspend fun AgentSeams.denyOutcomeOrNull(
    call: ToolCall,
    descriptor: ToolDescriptor?,
    step: Int,
    scope: String,
): ToolCallOutcome.Completed? {
    if (toolGuards.isEmpty()) return null
    val decision = checkTool(GuardContext(call, descriptor, step, scope))
    if (decision !is GuardDecision.Deny) return null
    return ToolCallOutcome.Completed(
        message = toolMessageOf(call, ToolResult.fail(decision.code, decision.message, decision.hint)),
        images = emptyList(),
        code = decision.code,
    )
}

/**
 * 生产装配：把接缝日志接回 [AgentLogger]。
 *
 * [AgentSeams] 与 [ToolGuardChain] 自身不 import AgentLogger（那会把 ConfigSanitizer 与
 * skill 模块拖进依赖链，导致 QA harness 编不动它们）。日志口子留在构造参数上，
 * 由这里统一接——**装配点只有这一个**，将来加守卫时不会有人漏接。
 */
fun productionSeams(
    userMessage: List<UserMessageMiddleware> = emptyList(),
    toolGuards: List<ToolGuard> = emptyList(),
    assistantListeners: List<AssistantMessageListener> = emptyList(),
): AgentSeams = AgentSeams(userMessage, toolGuards, assistantListeners) { AgentLogger.w("Seam", it) }
