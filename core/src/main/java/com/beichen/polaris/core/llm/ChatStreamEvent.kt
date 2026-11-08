package com.beichen.polaris.core.llm

import com.beichen.polaris.core.AssistantMessage

/**
 * 流式对话事件（T01）：[com.beichen.polaris.core.LLMClient.streamChat] 的产出。
 *
 * 两种事件覆盖「文本增量」与「整条 commit」两个消费需求：
 *  - [TextDelta]：文本增量，UI 打字机效果直接渲染；
 *  - [Completed]：整条 AssistantMessage（含 ToolCall）——RealAgentLoop 的
 *    「整条 Assistant commit（含 ToolCall）」三段式在流式下同样成立：
 *    增量只进 onTextDelta 回调，进历史的一定是 Completed 整条。
 *
 * 放 core.llm 包：与 LLMClient 同包，四个供应商实现与 harness 共同引用。
 */
sealed interface ChatStreamEvent {

    /** 一段文本增量（content 非空才发射；空 content 的 chunk 不产生事件）。 */
    data class TextDelta(val text: String) : ChatStreamEvent

    /**
     * 功能五（P1-9）：一段「推理过程」增量（思维链）。
     *
     * 与 [TextDelta] 分流：思考内容不是最终答案，UI 要折叠展示（ReasoningBlock），
     * 混进正文会让「回答」里多出一段模型的自言自语。进历史的也只有
     * [Completed] 整条（message.reasoning）。
     */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /** 流结束：整条助手消息（content 为累积全文，toolCalls 为累积结果）。 */
    data class Completed(val message: AssistantMessage) : ChatStreamEvent
}
