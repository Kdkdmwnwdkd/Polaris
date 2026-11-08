package com.beichen.polaris.core.agent

/**
 * AI 会话标题的清洗纯函数（W-26，顺带补缺口 #33「重新生成会话标题」）。
 *
 * 模型返回的标题很脏：带引号（`"…"` `「…」`）、带「标题：」前缀、带句末标点、
 * 甚至返回一整段解释。这里统一清洗成"能直接放进抽屉列表"的短标题。
 *
 * 为什么单独成文件：无 Android / 无协程依赖，harness 可直接验证；
 * 生产侧（ChatViewModel）与测试侧共用同一份规则。
 */
object TitleLogic {

    /** 标题长度上限（抽屉列表一行放得下；超过会截断加省略号）。 */
    const val MAX_CHARS = 20

    /**
     * 清洗模型输出为标题；无法得到有效标题时返回 null（调用方保持原标题）。
     *
     * 规则：
     *  1. 取第一行（模型常把解释写在第二行）；
     *  2. 去掉「标题：」「Title:」等前缀；
     *  3. 去掉包裹引号（中英文、直角引号）；
     *  4. 去掉首尾空白与句末标点（。！？.!?）；
     *  5. 超过 [MAX_CHARS] 截断并加省略号；
     *  6. 空串返回 null。
     */
    fun clean(raw: String): String? {
        var s = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (s.isEmpty()) return null

        // 前缀（模型爱写「标题：xxx」）
        s = s.replaceFirst(Regex("^(标题|题目|Title|title)\\s*[:：]\\s*"), "").trim()

        // 成对包裹引号：只去最外层一对
        s = stripWrappingQuotes(s)

        // 句末标点
        s = s.trimEnd('。', '！', '？', '.', '!', '?', '，', ',', '；', ';', '：', ':').trim()
        if (s.isEmpty()) return null

        // 长度
        if (s.length > MAX_CHARS) {
            s = s.take(MAX_CHARS).trimEnd() + "…"
        }
        return s.ifBlank { null }
    }

    /** 去掉最外层一对引号（“…” 「…」 "…" '…'）。 */
    fun stripWrappingQuotes(s: String): String {
        if (s.length < 2) return s
        val pairs = listOf(
            '"' to '"', '\'' to '\'',
            '“' to '”', '‘' to '’',
            '「' to '」', '『' to '』'
        )
        for ((open, close) in pairs) {
            if (s.first() == open && s.last() == close) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    /**
     * 是否值得为这段内容生成标题（纯函数，便于测试）：
     *  - 至少有 [minUserChars] 个字；
     *  - 不是纯符号/表情；
     *  - 不是"你好/在吗"这类无信息量开场（不值得花一次模型调用）。
     */
    fun worthGenerating(userText: String, minUserChars: Int = 4): Boolean {
        val t = userText.trim()
        if (t.length < minUserChars) return false
        if (t.none { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }) return false
        val trivial = setOf(
            "你好", "您好", "hi", "hello", "在吗", "在不在", "test", "测试", "哈喽", "嗨"
        )
        return t.lowercase().trim() !in trivial
    }
}
