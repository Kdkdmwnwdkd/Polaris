package com.beichen.polaris.core


/**
 * 默认系统提示（全链路 Agent 的公共底座）。
 *
 * 除基础「先调工具、再据结果回答」的引导外，**内嵌「界面操作协议」**——真机日志
 * （08:27-08:28）暴露的策略错误：`ui_click` 失败后模型转用 `shell_run`（dumpsys /
 * am start / settings）绕路操控界面，而本机 user shell 无对应权限，必然失败，
 * 最终任务失败并放弃、把活推回用户。协议段把「唯一可行通道（无障碍 ui_*）」、
 * 「禁止 shell 操控界面」、失败自纠三步、重读/去重 ui_tree、截图与 ui_tree 的
 * 分工（W-9：小模型曾每步都截图，烧 token 又慢）、禁止过早放弃
 * 逐条写死，供大小模型共同遵守。
 *
 * 预算约束：本常量总长须 ≤ [SYSTEM_PROMPT_MAX_CHARS]（QA harness 断言），
 * 故协议段克制为短句祈使句（见 scripts/qa/PROMPT-NOTES.md）。
 */
const val DEFAULT_SYSTEM_PROMPT = """你是一个运行在 Android 设备上的本地 AI 助手。
你可以使用一组工具来完成任务。当完成任务需要信息或操作时，先调用相应工具，再基于工具结果给出最终回答。
如果一步不够，可以连续调用多个工具；工具调用之间要基于上一步的结果做下一步决策。

【界面操作协议】
操控界面只能用无障碍工具（唯一可行通道）：先 ui_tree 看结构，再用 ui_tap/ui_click/ui_input/ui_swipe/ui_key 操作。
禁止用 shell_run 操控界面：dumpsys、am start、settings put/get、input tap 都会因权限不足失败，不要尝试。
shell_run 只用于文件读写、命令执行、查看本机信息；要「已安装应用列表」用 app_list，要启动应用用 app_launch。
ui_click 失败就自纠：① 看返回的候选列表，用最接近的候选重试一次；② 仍失败改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击；③ 坐标也失败才向用户求助。
每次操作后界面已变，必须重新 ui_tree；同一界面内容未变时不要重复 ui_tree。
工具分工：ui_tree 拿界面结构与坐标（操作前必读），ui_screenshot 用于「看见」视觉内容（图片、验证码、动画，或 ui_tree 描述不清的区域）；优先 ui_tree，需要看画面时再截图，不要每个步骤都截图。
不要过早放弃：任务未完成前不要输出「请你手动核对」这类把活推回用户的话，至少按上面规则自纠两轮。"""

/** [DEFAULT_SYSTEM_PROMPT] 的长度上限（防提示膨胀失控；QA harness 断言用）。 */
const val SYSTEM_PROMPT_MAX_CHARS: Int = 4000

/** 记忆使用引导：仅在启用记忆库时拼进系统提示，告诉模型“可以写、可以搜”。 */
internal const val MEMORY_USAGE_PROMPT =
    "<memory_usage>你可调用 memory_write 记录用户偏好/关键事实，用 memory_search 回忆过往信息。以下是当前记忆摘要：</memory_usage>"

/**
 * AgentLoop 执行过程事件（Hotfix2：过程透明化）。
 *
 * 旧模式：run() 整轮跑完才返回完整历史，UI 只能「事后」渲染——执行期间用户在
 * 黑盒里干等，AI 调过什么工具、被什么拦截，一概不可见。现在关键节点实时抛事件，
 * UI 边跑边渲染（工具卡「执行中 → 已完成/已失败/已拒绝」）。
 *
 * T01：本类型**留在 core 包原位置**（不许挪）。新 loop（RealAgentLoop）与适配层
 * 共用本定义。
 *
 * Phase 2 DoD③（断网/失败自动恢复且 UI 可见）：新增 [RetryStarted] / [RetryFinished]
 * 两个子类型，把供应商瞬时失败的重试过程从「黑盒干等」变为可见——UI 据此显示
 * 「正在恢复（n/N）· 原因」横幅，恢复成功 / 最终失败 / 用户取消时清除。
 *
 * ⚠️ Kotlin 1.9 对 sealed 的 `when` 有**穷尽性硬约束**：新增子类型后，所有对
 * [AgentEvent] 做穷尽 `when` 的地方（当前唯一一处为 `ui/ChatViewModel.kt` 的
 * `handleAgentEvent`）必须**同步补分支**，否则编译失败。改动本类型时请全仓搜索
 * `is AgentEvent` / `when (ev)` 逐个核对。
 */
sealed interface AgentEvent {
    /** 工具调用开始执行（参数已定，结果未出）。 */
    data class ToolStarted(val call: ToolCall) : AgentEvent

    /** 工具调用结束（成功 / 失败 / 审批被拒 / 被熔断 / 取消闭合，携带回填的 ToolMessage）。 */
    data class ToolFinished(val message: ToolMessage) : AgentEvent

    /**
     * 一次 LLM 调用遭遇**可重试**错误、即将发起第 [attempt] 次重试（共 [max] 次）。
     *
     * @param attempt 1-based 重试序号（第 1 次重试 = 上一次调用失败后的下一次尝试）。
     * @param max 配置的最大重试次数（= providerRetry）；总请求数上限 = max + 1。
     * @param reason 面向用户的可读原因（如「服务端错误（500）」「网络超时」）。
     */
    data class RetryStarted(val attempt: Int, val max: Int, val reason: String) : AgentEvent

    /**
     * 重试过程收尾：LLM 调用最终成功、最终失败、或用户取消。
     *
     * 语义 = 「重试状态已结束，UI 可清除『正在恢复』横幅」。**取消也算收尾**——
     * T05 不变量：用户点停止后不得残留「正在恢复」（取消不是失败，但重试状态必须清）。
     */
    data object RetryFinished : AgentEvent

    /**
     * 本轮回答因**输出长度上限**被截断（厂商明确报告 `finish_reason=length`）。
     *
     * 为什么要上报而不只是打日志：截断后的回答是「说到一半就停」，但界面上看起来
     * 与「模型自己说完了」完全一样——用户会以为是模型敷衍，而不是撞到了上限。
     * 每回合最多上报一次（多步循环里同一原因会重复出现，刷屏反而让人忽略）。
     *
     * `reason` 保留厂商原值（已归一化为 [com.beichen.polaris.core.llm.FinishReason.LENGTH]），
     * 供日志与后续「自动续写」判断使用；`null` 表示字段缺失，此时不会上报本事件。
     */
    data class OutputTruncated(val reason: String?) : AgentEvent
}

