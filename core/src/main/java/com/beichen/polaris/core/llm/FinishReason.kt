package com.beichen.polaris.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 把三家的「本次输出为什么结束」统一成一套语义。
 *
 * ## 为什么需要它
 *
 * 输出被长度上限截断时，三家都用**不同字段、不同取值**告诉你：
 *
 * | 厂商 | 字段 | 截断值 | 正常值 |
 * |---|---|---|---|
 * | OpenAI 兼容（含 DeepSeek / 中转） | `choices[0].finish_reason` | `length` | `stop` / `tool_calls` |
 * | Claude | `stop_reason` | `max_tokens` | `end_turn` / `tool_use` |
 * | Gemini | `candidates[0].finishReason` | `MAX_TOKENS` | `STOP` |
 *
 * 不统一的话，每个消费者都要写三套映射，迟早漏一家——而漏掉的表现是
 * 「回答说到一半没了，但 App 一脸正常」。统一成 [LENGTH] 后，判断只需一次 `==`。
 *
 * ## 为什么只归一化这两种
 *
 * [LENGTH] 与 [STOP] 是**跨厂商含义相同**的，可以安全合并。`SAFETY` / `RECITATION` /
 * `content_filter` 之类各家的含义并不对齐（Gemini 的 SAFETY 与 OpenAI 的 content_filter
 * 拦的不是一类东西），强行合并会造出「看起来统一、实际失真」的字段，所以原样保留小写值。
 *
 * ## 这只是「记录」，不是「修复」
 *
 * 本类不做续写、不改 content、不拦请求。它只把「被截断了」这个事实从响应里取出来，
 * 让上层能看见。之前全仓库没有一处读这个字段，所以截断在 App 里是**不可见**的。
 */
object FinishReason {

    /** 因输出长度上限而结束（三家归一化后的值）。 */
    const val LENGTH: String = "length"

    /** 模型自己说完了。 */
    const val STOP: String = "stop"

    /**
     * OpenAI 兼容：`choices[0].finish_reason`。
     *
     * 流式响应里该字段通常在**最后**一个 chunk 出现（`delta` 为空、只有 `finish_reason`），
     * 前面的 chunk 是 `null`——所以调用方要「见到非空就覆盖」，不能只看第一个。
     */
    fun fromOpenAi(root: JsonObject): String? =
        root["choices"]?.arrOrNull()
            ?.firstOrNull()?.objOrNull()
            ?.get("finish_reason")?.primOrNull()?.contentOrNull
            ?.let { normalize(it) }

    /** Claude：顶层 `stop_reason`。 */
    fun fromClaude(root: JsonObject): String? =
        root["stop_reason"]?.primOrNull()?.contentOrNull?.let { normalize(it) }

    /** Gemini：`candidates[0].finishReason`。 */
    fun fromGemini(root: JsonObject): String? =
        root["candidates"]?.arrOrNull()
            ?.firstOrNull()?.objOrNull()
            ?.get("finishReason")?.primOrNull()?.contentOrNull
            ?.let { normalize(it) }

    /**
     * 是否因长度上限被截断。
     *
     * 空字符串按「未知」处理：某些兼容端点会下发 `"finish_reason": ""`，
     * 那不是截断，不该让 UI 弹一句「回答被截断了」。
     */
    fun isTruncated(reason: String?): Boolean = reason == LENGTH

    /**
     * 归一化：截断 → [LENGTH]，正常结束 → [STOP]，其余原样小写。
     *
     * `null` / 空白进 `null` 出——「没有这个字段」与「字段是空串」在语义上都是未知，
     * 不要伪造成 [STOP]（伪造会让「截断但厂商没报」更难发现）。
     */
    internal fun normalize(raw: String?): String? {
        val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (v) {
            "length", "max_tokens", "max_output_tokens", "maxtokens" -> LENGTH
            "stop", "end_turn", "stop_sequence", "eos", "complete" -> STOP
            // tool_use / tool_calls / function_call：不是结束，是「等工具结果」，
            // 原样保留让上层能区分「被截断」与「正常调工具」
            else -> v
        }
    }

    /**
     * 截断时落一条 W 级日志（三客户端共用，避免各写一遍措辞还各不相同）。
     *
     * 只记录、不干预：不改 content、不自动续写、不拦请求。它解决的是「看不见」——
     * 之前输出被截断时，日志里没有任何痕迹，排查只能靠用户口述「话说一半停了」。
     *
     * @param source 调用方标识（`chat` / `streamChat` / `claude` / `gemini`），便于定位是哪条链路。
     */
    fun logIfTruncated(source: String, reason: String?) {
        if (!isTruncated(reason)) return
        com.beichen.polaris.core.AgentLogger.w(
            "LLM",
            "$source 输出撞到长度上限（finish_reason=length），本轮回答可能不完整"
        )
    }
}
