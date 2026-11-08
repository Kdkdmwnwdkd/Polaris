package com.beichen.polaris.core

import com.beichen.polaris.skill.ModelConfig

/**
 * 配置清洗工具：纯 Kotlin，无 Android 依赖，便于在 JVM 单测里直接验证。
 *
 * 解决的问题：用户从网页/富文本复制 API Key 时，极易夹带肉眼不可见字符
 * （U+00A0 不间断空格、U+200B 零宽空格、U+FEFF BOM、全角空格等）。
 * Kotlin 的 String.trim() 只去掉 isWhitespace() 为 true 的字符，而上述字符多数不在其中，
 * 导致实际发出的 token ≠ 真实 Key → 服务端报 401，而用户肉眼检查「完全正确」。
 * 这里只保留可打印 ASCII（0x21..0x7E），彻底剔除一切脏字符。
 */
object ConfigSanitizer {

    /**
     * 清洗 API Key：只保留可打印 ASCII（0x21..0x7E），剔除所有空格/换行/
     * NBSP(U+00A0)/零宽(U+200B,U+FEFF)/全角空格等一切非可打印字符。
     *
     * @param raw 用户原始输入的 Key（可能夹带不可见字符）。
     * @return 清洗后的 Key，可直接用于 Authorization 头。
     */
    fun cleanApiKey(raw: String): String {
        return raw.filter { it.code in 0x21..0x7E }
    }

    /**
     * 清洗 Base URL：去首尾空白与所有内部空白（用户误敲的空格）；
     * 去尾部 `/`；若误填了完整端点（以 /chat/completions 结尾）则剥掉该后缀，
     * 避免拼成 .../chat/completions/chat/completions 的 404。
     *
     * @param raw 用户原始输入的 Base URL。
     * @return 规范化后的 Base URL（不带结尾斜杠）。
     */
    fun normalizeBaseUrl(raw: String): String {
        // 先塌缩掉所有内部空白（含被复制进来、肉眼看不见的空格），再统一去尾斜杠
        val collapsed = raw.replace(Regex("\\s+"), "")
        val stripped = collapsed.trimEnd('/')
        return if (stripped.endsWith("/chat/completions", ignoreCase = true)) {
            stripped.removeSuffix("/chat/completions").trimEnd('/')
        } else {
            stripped
        }
    }

    /**
     * 清洗模型名：去首尾空白并剔除不可见字符。
     * 模型名里合法的 `:`/`-`/`_`/`.` 等都在可打印 ASCII 范围内，不会被误删。
     *
     * @param raw 用户原始输入的模型名。
     * @return 清洗后的模型名。
     */
    fun cleanModel(raw: String): String {
        return raw.filter { it.code in 0x21..0x7E }.trim()
    }

    /**
     * 自检一份原始 Key：给出长度/首尾片段/被剔除字符数，便于 UI 实时提示、
     * 也便于日志里归因「是不是粘贴不全/夹带空格」。
     *
     * 注意：head/tail 只取字符片段用于用户自查核对，**绝不**在日志里记录完整 Key。
     *
     * @param raw 用户原始输入的 Key。
     * @return [KeyInspection] 自检结果。
     */
    fun inspectKey(raw: String): KeyInspection {
        val cleaned = cleanApiKey(raw)
        val removedCount = raw.length - cleaned.length
        return KeyInspection(
            length = cleaned.length,
            head = cleaned.take(3),
            tail = cleaned.takeLast(4),
            removedCount = removedCount,
            hasIllegalChars = removedCount > 0
        )
    }

    /**
     * 组合入口：对 [ModelConfig] 三元组（baseUrl/apiKey/model）做整体清洗后，
     * 返回一份已净化的新配置对象。调用方（ViewModel 落盘、LLMClient 兜底）统一走它，
     * 避免散落各处的 `trim()` 漏洗不可见字符。
     *
     * @param cfg 原始配置（可能含脏字符）。
     * @return 清洗后的配置。
     */
    fun sanitized(cfg: ModelConfig): ModelConfig {
        return ModelConfig(
            baseUrl = normalizeBaseUrl(cfg.baseUrl),
            apiKey = cleanApiKey(cfg.apiKey),
            model = cleanModel(cfg.model),
            // 透传 providerId：否则每次保存/读取都会把供应商打回 "openai"
            providerId = cfg.providerId
        )
    }

    /**
     * 重载：对三要素分别清洗（UI 实时自检/测试连接时直接传输入框文本，免去先组装 ModelConfig）。
     *
     * @param baseUrl 原始 Base URL。
     * @param apiKey 原始 API Key。
     * @param model 原始模型名。
     * @return 清洗后的 [ModelConfig]。
     */
    fun sanitized(baseUrl: String, apiKey: String, model: String): ModelConfig {
        return ModelConfig(
            baseUrl = normalizeBaseUrl(baseUrl),
            apiKey = cleanApiKey(apiKey),
            model = cleanModel(model)
        )
    }

    /**
     * 重载：四要素版（含 providerId）。测试连接/保存走这支，
     * 避免供应商在清洗环节被打回默认值。
     */
    fun sanitized(baseUrl: String, apiKey: String, model: String, providerId: String): ModelConfig {
        return ModelConfig(
            baseUrl = normalizeBaseUrl(baseUrl),
            apiKey = cleanApiKey(apiKey),
            model = cleanModel(model),
            providerId = providerId
        )
    }

    /**
     * W-29：解析自定义请求头文本（每行 `Key: Value`；`#` 开头为注释）。
     *
     * 规则：
     *  - 冒号后可有空格；值里的冒号保留（如 `Referer: https://a.com/x`）；
     *  - 键名清洗成可打印 ASCII（HTTP 头名不允许中文/空格）；
     *  - 跳过空行与注释；同名键后者覆盖前者；
     *  - 值保留原样（去除首尾空白），允许中文（部分中转站用它做备注）。
     */
    fun parseHeaderLines(text: String): Map<String, String> =
        com.beichen.polaris.core.text.HeaderParsing.parseHeaderLines(text)

    /**
     * W-29：校验自定义 body 文本；返回错误文案，null 表示通过。
     * 允许空（= 不追加），但非空必须是**JSON 对象**（数组/标量会被拒绝，避免把请求体搞坏）。
     */
    fun validateCustomBody(text: String): String? =
        com.beichen.polaris.core.text.HeaderParsing.validateCustomBody(text)
}

/**
 * Key 自检结果：供日志与 UI 共用，便于用户凭 head/tail 核对是否粘贴正确。
 *
 * @property length 清洗后 Key 的实际长度（拼到请求里的长度）。
 * @property head 前 3 字符，供核对开头（如 `sk-`）。
 * @property tail 后 4 字符，供核对结尾（如 `d38d`）。
 * @property removedCount 清洗时剔除了多少字符（>0 即夹带了不可见字符）。
 * @property hasIllegalChars 是否检测到了被剔除的不可见字符。
 */
data class KeyInspection(
    val length: Int,
    val head: String,
    val tail: String,
    val removedCount: Int,
    val hasIllegalChars: Boolean
)
