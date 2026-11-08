package com.beichen.polaris.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 功能五（P1-9）：三家「推理过程（思维链）」字段的纯解析（零 IO/Android 依赖，
 * harness 直接断言）。与 [TokenUsageParsers] 同文件同纪律：解析缺失返 null
 * 而非空串——「没给思考」与「给了空思考」是两件事。
 *
 * 字段事实（各厂文档/实测惯例）：
 *  - OpenAI 兼容系（DeepSeek-R1 / QwQ / GLM / 部分 one-api 中转）：
 *    `choices[0].delta.reasoning_content`（DeepSeek 官方命名）；
 *    另有中转厂用 `delta.reasoning`（OpenRouter 早期命名）——两个都认，前者优先。
 *  - Claude extended thinking：SSE `content_block_delta` 事件里
 *    `delta.type == "thinking_delta"`，正文在 `delta.thinking`。
 *  - Gemini 2.5 thinking：`candidates[0].content.parts[]` 里带 `"thought": true`
 *    的 part，其 `text` 即思考内容（正式回答的 part 无该字段）。
 */

/** OpenAI 兼容系的思考增量（入参 = choices[0].delta 对象；缺失返 null）。 */
fun parseOpenAiReasoning(delta: JsonObject): String? {
    delta["reasoning_content"]?.primOrNull()?.contentOrNull?.let { return it }
    return delta["reasoning"]?.primOrNull()?.contentOrNull
}

/**
 * Claude 的思考增量（入参 = 整条 SSE data 对象）。
 * 只认 `content_block_delta` + `delta.type == "thinking_delta"` 组合，
 * 其它事件（text_delta / input_json_delta / message_*）一律返 null。
 */
fun parseClaudeThinkingDelta(obj: JsonObject): String? {
    if (obj["type"]?.primOrNull()?.contentOrNull != "content_block_delta") return null
    val delta = obj["delta"]?.objOrNull() ?: return null
    if (delta["type"]?.primOrNull()?.contentOrNull != "thinking_delta") return null
    return delta["thinking"]?.primOrNull()?.contentOrNull
}

/**
 * Gemini 的思考增量（入参 = candidates[0].content.parts[] 的单个 part）。
 * `thought: true` 的 part 才是思考；正式回答 part 无此字段（返 null）。
 */
fun parseGeminiThought(part: JsonObject): String? {
    if (part["thought"]?.primOrNull()?.contentOrNull != "true") return null
    return part["text"]?.primOrNull()?.contentOrNull
}
