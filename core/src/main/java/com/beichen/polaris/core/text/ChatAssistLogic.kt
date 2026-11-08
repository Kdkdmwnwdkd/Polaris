package com.beichen.polaris.core.text

/**
 * 翻译与语音识别的纯逻辑（W-30）：无 Android / 无网络依赖，harness 可直接断言。
 *
 * 分开两个 object 而不是堆在一个里：翻译是「LLM 提示词工程」，语音是「输入清洗」，
 * 领域不同、改动理由也不同，混在一起改一处要读两份无关代码。
 */
object TranslateLogic {

    /** 目标语言选项（UI 下拉用；value 同时作为提示词里的语言名）。 */
    val LANGUAGES = listOf("中文", "English", "日本語", "한국어")

    /**
     * 构造翻译系统提示词。
     *
     * 强约束「只输出译文」——否则模型爱加「以下是翻译：」「希望对你有帮助」，
     * 贴到气泡下面会显得很脏。同时要求保留代码块/行内代码原样：
     * 技术内容里 `foo()` 被翻成中文是最常见的破坏。
     */
    fun systemPrompt(target: String): String =
        "你是专业翻译。把用户给出的内容翻译成${target}。" +
            "只输出译文本身，不要加「翻译：」「以下是」等任何前后缀，不要解释。" +
            "保留原文的 Markdown 结构、代码块、行内代码、URL、变量名与技术术语原样不译。"

    /** 用户侧内容：把原文包进明确边界，避免模型把指令当正文翻译。 */
    fun userPrompt(text: String): String =
        "请翻译以下内容：\n<<<\n${text.take(6000)}\n>>>"

    /**
     * 清洗模型输出：
     *  - 去掉「译文：」「翻译：」等前缀；
     *  - 去掉整体包裹的成对引号（模型很爱加）；
     *  - 去掉首尾多余空行；
     *  - 与原文完全相同 → 返回 null（视为没翻出来，UI 不显示"译文"）。
     */
    fun clean(raw: String, source: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        // 前缀（可叠加，如「译文：\n」）
        val prefixes = listOf("译文：", "译文:", "翻译：", "翻译:", "Translation:", "translation:")
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (t.startsWith(p)) {
                    t = t.removePrefix(p).trimStart()
                    changed = true
                }
            }
        }
        t = stripWrappingQuotes(t)
        t = t.trim()
        if (t.isEmpty()) return null
        if (t == source.trim()) return null
        return t
    }

    /** 去掉整体成对包裹的引号（`"`、`“”`、`「」`、`『』`）；不成对不动。 */
    fun stripWrappingQuotes(s: String): String {
        if (s.length < 2) return s
        val pairs = listOf(
            '"' to '"',
            '“' to '”',
            '「' to '」',
            '『' to '』',
            '\'' to '\''
        )
        for ((open, close) in pairs) {
            if (s.first() == open && s.last() == close) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }
}

/**
 * 语音识别结果清洗（W-30）。
 *
 * 系统识别器返回的东西不干净：末尾常带句号、可能只有标点、可能把停顿识别成
 * 「嗯」「啊」。这里只做**保守**处理——不确定的宁可不改，改了用户会困惑。
 */
object SpeechLogic {

    /** 无意义的语气词（整句仅由它们构成时才丢弃）。 */
    private val FILLERS = setOf("嗯", "啊", "呃", "哦", "唉", "嗯嗯", "啊啊", "那个", "这个")

    /**
     * 清洗一条识别结果；不可用返回 null（调用方据此不往输入框塞东西）。
     *
     * @param raw 识别器原始文本
     * @return 可直接追加到输入框的文本
     */
    fun cleanResult(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        // 只由标点/空白构成 → 丢弃（识别器常把静音识别成「。」）
        if (t.none { it.isLetterOrDigit() }) return null
        // 整句是语气词 → 丢弃
        if (FILLERS.contains(t.trimEnd('。', '，', '.', ','))) return null
        // 中文识别器爱在末尾加句号；保留问号/感叹号（那是语义），只去平白句号
        if (t.endsWith("。") && t.count { it == '。' } == 1 && t.length > 2) {
            t = t.dropLast(1)
        }
        return t.ifBlank { null }
    }

    /**
     * 把识别结果追加到已有输入（有内容时补一个空格分隔；英文之间需要空格，中文不需要）。
     * 纯函数，UI 只负责调用。
     */
    fun appendTo(existing: String, addition: String): String {
        if (existing.isBlank()) return addition
        val last = existing.last()
        val first = addition.firstOrNull() ?: return existing
        val needSpace = last.isLetterOrDigit() && last.code < 128 &&
            first.isLetterOrDigit() && first.code < 128
        return if (needSpace) "$existing $addition" else existing + addition
    }
}
