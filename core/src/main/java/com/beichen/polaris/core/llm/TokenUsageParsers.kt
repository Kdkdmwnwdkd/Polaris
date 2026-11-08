package com.beichen.polaris.core.llm

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.TokenUsage
import kotlinx.serialization.json.JsonObject

/**
 * 功能五（P1-5）：三家协议的 token usage 解析（纯函数，零 Android / 零网络依赖——
 * QA harness 直接喂 JSON 断言，不发起任何请求）。
 *
 * 为什么在客户端外单独一层：三家字段名各异但语义一致，收成纯函数后
 *  - 各客户端的 parseResponse / SSE 末片处理只调一行，不重复正则式挖 JSON；
 *  - harness 可对「响应体样本 → TokenUsage」逐字段校验（含字段缺失/异构名/垃圾值容错）。
 *
 * 共同容错契约：usage 对象缺失或字段不可解析时返回 **null**（而非 0）——
 * 「服务端没给」与「服务端说 0」是两件事，UI 对 null 不显示、对 0 显示。
 */

/** OpenAI 兼容协议：`usage.{prompt_tokens, completion_tokens}`（total 由本端求和，不采信服务端）。 */
fun parseOpenAiUsage(root: JsonObject): TokenUsage? {
    val usage = root["usage"]?.objOrNull() ?: return null
    val prompt = usage.longField("prompt_tokens") ?: usage.longField("input_tokens") ?: return null
    val completion = usage.longField("completion_tokens") ?: usage.longField("output_tokens") ?: return null
    // 命中量在 prompt_tokens 内部，是子集：OpenAI 放嵌套对象，DeepSeek 放顶层。
    // 两家都取不到就记 0——「没报」与「报了 0」在展示上没差别，不值得多一个可空字段。
    val cached = usage["prompt_tokens_details"]?.objOrNull()?.longField("cached_tokens")
        ?: usage.longField("prompt_cache_hit_tokens")
        ?: 0L
    return TokenUsage(prompt, completion, cached)
}

/**
 * Claude Messages API：`usage.{input_tokens, output_tokens}`（cache 字段**不计入**
 * promptTokens，避免与计费口径冲突）。
 *
 * 注意这里与其他两家的结构性差异：Claude 的 **`input_tokens` 不包含**缓存部分，
 * 一次请求的输入被切成三段——`input_tokens`（未缓存）+ `cache_read_input_tokens`
 * （命中，按 0.1x 计费）+ `cache_creation_input_tokens`（写入缓存，按 1.25x 计费）。
 * 所以这里的 `promptTokens` 是「按全价计费的那部分」，比实际输入量**小**。
 * 展示总输入时得三段相加；[TokenUsage.cachedPromptTokens] 只记命中那一段。
 */
fun parseClaudeUsage(root: JsonObject): TokenUsage? {
    val usage = root["usage"]?.objOrNull() ?: return null
    val prompt = usage.longField("input_tokens") ?: return null
    val completion = usage.longField("output_tokens") ?: return null
    val cached = usage.longField("cache_read_input_tokens") ?: 0L
    return TokenUsage(prompt, completion, cached)
}

/**
 * Gemini generateContent：`usageMetadata.{promptTokenCount, candidatesTokenCount}`。
 * thoughtsTokenCount（思考 token）单列不计入输出——它含在 candidates 里会重复计数，
 * 服务端契约：candidatesTokenCount 已含 thoughts，故只取两项。
 */
fun parseGeminiUsage(root: JsonObject): TokenUsage? {
    val meta = root["usageMetadata"]?.objOrNull() ?: return null
    val prompt = meta.longField("promptTokenCount") ?: return null
    val completion = meta.longField("candidatesTokenCount") ?: return null
    val cached = meta.longField("cachedContentTokenCount") ?: 0L
    return TokenUsage(prompt, completion, cached)
}

/**
 * 取 JSON 对象的数字字段为 Long（纯函数）：缺失 / 非数字 / 布尔一律 null。
 * 容忍字符串数字（少数兼容端点把 token 数序列化成 "123"）——trim 后 toLongOrNull。
 */
private fun JsonObject.longField(key: String): Long? =
    this[key]?.primOrNull()?.content?.trim()?.toLongOrNull()

/**
 * 打一行「提示缓存命中」日志；服务端没报命中就什么都不打。
 *
 * 为什么要专门留这一行：**缓存失效是静默的**。断点打错、前缀逐字节变了、端点不支持，
 * 三种情况都不会报错，只会让账单按全价走。不留日志，「到底省没省」就只能靠猜。
 *
 * @param cachedInPrompt 命中量是否已包含在 `promptTokens` 里（OpenAI/DeepSeek/Gemini 是，
 *   Claude 不是——见 [TokenUsage.cachedPromptTokens] 的口径说明）。决定分母怎么算。
 */
fun logPromptCacheUsage(usage: TokenUsage?, cachedInPrompt: Boolean, tag: String = "Cache") {
    val u = usage ?: return
    val cached = u.cachedPromptTokens
    if (cached <= 0) return
    val inputTotal = if (cachedInPrompt) u.promptTokens else u.promptTokens + cached
    if (inputTotal <= 0) return
    val pct = cached * 100 / inputTotal
    AgentLogger.i(tag, "提示缓存命中 $cached/$inputTotal tok（$pct%，命中部分按折扣价计费）")
}
