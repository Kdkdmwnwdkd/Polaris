package com.beichen.polaris.core.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 自定义请求头 / 请求体的解析与校验（W-29）：纯函数，无 Android 依赖，harness 可测。
 */
object HeaderParsing {

    /**
     * 解析自定义请求头文本（每行 `Key: Value`；`#` 开头为注释）。
     *
     * 规则：
     *  - 冒号后可有空格；值里的冒号保留（如 `Referer: https://a.com/x`）；
     *  - 键名清洗成可打印 ASCII（HTTP 头名不允许中文/空格）；
     *  - 跳过空行与注释；同名键后者覆盖前者；
     *  - 值保留原样（去除首尾空白），允许中文（部分中转站用它做备注）。
     */
    fun parseHeaderLines(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim().filter { it.code in 0x21..0x7E }
            if (name.isEmpty()) return@forEach
            val value = line.substring(colon + 1).trim()
            out[name] = value
        }
        return out
    }

    /**
     * 校验自定义 body 文本；返回错误文案，null 表示通过。
     * 允许空（= 不追加），但非空必须是 **JSON 对象**（数组/标量会被拒绝，避免把请求体搞坏）。
     */
    fun validateCustomBody(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("{")) return "自定义请求体必须是 JSON 对象（以 { 开头）"
        val parsed = runCatching { Json.parseToJsonElement(t) }.getOrNull()
            ?: return "JSON 解析失败，请检查括号与引号"
        if (parsed !is JsonObject) return "自定义请求体必须是 JSON 对象"
        return null
    }

    /** 把请求头 map 渲染回文本（供编辑框回显；键序保持插入序）。 */
    fun renderHeaderLines(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
}
