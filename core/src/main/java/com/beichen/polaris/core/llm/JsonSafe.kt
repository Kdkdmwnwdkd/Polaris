package com.beichen.polaris.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * hotfix33：JSON 安全访问器——真机崩溃「Element class kotlinx.serialization.json.JsonNull
 * (Kotlin reflection is not available) is not a JsonObject」的根治。
 *
 * **根因**：kotlinx.serialization 的 [JsonElement.jsonObject] / [JsonElement.jsonPrimitive] /
 * [JsonElement.jsonArray] 是硬转换（内部即 `as JsonObject` 等），拿到 **JsonNull**
 * （JSON 里显式的 `null` 字面量）直接抛 IllegalArgumentException。
 *
 * 而真实供应商会下发 null 值，且是**间歇性**的（取决于模型/回合/中转）：
 *  - DeepSeek 非推理回合的 delta 里 `"reasoning_content": null`、`"content": null`；
 *  - 收尾块（带 finish_reason / usage）常把 `"delta"` 整个写成 null；
 *  - 空 tool_calls、空标注字段同理。
 * 旧实现里 LLM 链路共 83 处裸强转 → 首字或收尾任一块带 null，整条回答就报
 * 「出错了：Element class ... JsonNull ... is not a JsonObject」。
 *
 * 这解释了「测试连接正常、聊天却崩」：非流式响应（测试连接）与流式分片（聊天）
 * 的 JSON 形态不同，后者更容易出现 null 片段。
 *
 * **修法**：统一 `as?` 软转换——类型不符（含 JsonNull）一律返 null。
 * 语义上「字段不存在」与「字段是 null」在取值层面等价，调用方本就有 `?:` 兜底；
 * 而「把 null 当解析失败」远比「让 null 崩掉整轮回答」正确。
 *
 * 命名带 OrNull 后缀，与 kotlinx 硬转换一眼区分，防止后续误用回硬转换。
 */
fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

/**
 * 软转换：非 JsonPrimitive（含 JsonNull）一律 null。
 *
 * **W-47 补漏**：`JsonNull` 在 kotlinx 里是 `JsonPrimitive` 的子类，单用 `as? JsonPrimitive`
 * 会把它放行——于是 `?.primOrNull()?.long` 会走到 `"null".toLong()` 抛 NumberFormatException，
 * `.content` 还会把字符串 `"null"` 当成真实取值（例如把工具调用 id 变成 "null"）。
 * 语义上「字段是 null」与「字段不存在」应当同解，故这里显式把 JsonNull 归为无值。
 */
fun JsonElement?.primOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
