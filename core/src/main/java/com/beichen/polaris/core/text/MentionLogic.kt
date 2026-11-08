package com.beichen.polaris.core.text

/**
 * @提及文件的解析与匹配（W-28，缺口 #27）：纯函数，无 Android 依赖，harness 可测。
 *
 * ## 交互约定
 * 用户在输入框打 `@` 后跟一段字符（通常停在句末），弹出工作区文件建议；
 * 选中后把这段 `@xxx` 替换成 `@相对路径 `（带尾空格，方便继续打字）。
 *
 * 只认**文本末尾**的 mention（`@` 前必须是行首/空白），避免把邮箱 `a@b.com`
 * 里的 `@` 误当提及。
 */
object MentionLogic {

    /** 一段正在输入的提及：`@` 的位置 + 已输入的关键词。 */
    data class Query(val atIndex: Int, val keyword: String)

    /** 关键词最大长度（超过就认为用户不是在选文件了）。 */
    const val MAX_KEYWORD_LEN = 64

    /**
     * 检测文本**末尾**是否有正在输入的提及；没有返回 null。
     *
     * 例：`"帮我改一下 @src/ma"` → Query(atIndex=6, keyword="src/ma")；
     *     `"a@b.com"` → null（`@` 前不是空白）。
     */
    fun activeQueryAtEnd(text: String): Query? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        // `@` 必须在行首或空白之后
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val keyword = text.substring(at + 1)
        if (keyword.length > MAX_KEYWORD_LEN) return null
        // 关键词里不允许空白（空格就结束提及）
        if (keyword.any { it.isWhitespace() }) return null
        // 关键词里不允许再出现 @
        if (keyword.contains('@')) return null
        return Query(at, keyword)
    }

    /**
     * 提取文本中**所有**已完成的提及路径（`@path`），按出现顺序去重。
     * 只认 `@` 前是行首/空白、且路径字符合法的片段。
     */
    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val at = text.indexOf('@', i)
            if (at < 0) break
            val validStart = at == 0 || text[at - 1].isWhitespace()
            if (validStart) {
                var j = at + 1
                while (j < text.length && isPathChar(text[j])) j++
                if (j > at + 1) {
                    val path = text.substring(at + 1, j)
                    // 必须像一个文件路径（含点或斜杠），纯单词（@某人）不当文件
                    if (path.contains('.') || path.contains('/')) out += path
                }
                i = j
            } else {
                i = at + 1
            }
        }
        return out.toList()
    }

    /** 把末尾的 `@keyword` 替换成 `@path `。没有末尾提及则原样返回。 */
    fun replaceTailWith(text: String, path: String): String {
        val q = activeQueryAtEnd(text) ?: return text
        return text.substring(0, q.atIndex) + "@$path "
    }

    private fun isPathChar(c: Char): Boolean =
        c.isLetterOrDigit() || c in "._-/\\"

    /**
     * 文件匹配：前缀 > 包含 > 子序列（逐字符有序出现）。
     * 空关键词返回前 [limit] 个（让用户先看到有什么文件）。
     * 结果按分数降序，同分按原顺序稳定。
     */
    fun match(files: List<String>, keyword: String, limit: Int = 8): List<String> {
        val kw = keyword.trim().lowercase().replace('\\', '/')
        if (files.isEmpty()) return emptyList()
        if (kw.isEmpty()) return files.take(limit)
        data class Scored(val path: String, val score: Int, val order: Int)
        val scored = ArrayList<Scored>()
        files.forEachIndexed { idx, path ->
            val lower = path.lowercase()
            val score = when {
                lower.startsWith(kw) -> 100
                lower.substringAfterLast('/').startsWith(kw) -> 90
                lower.contains(kw) -> 60
                isSubsequence(kw, lower) -> 30
                else -> 0
            }
            if (score > 0) scored += Scored(path, score, idx)
        }
        return scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
            .take(limit)
            .map { it.path }
    }

    /** kw 的字符是否按顺序出现在 hay 中（子序列匹配，容忍中间跳过）。 */
    fun isSubsequence(kw: String, hay: String): Boolean {
        if (kw.isEmpty()) return true
        var k = 0
        for (c in hay) {
            if (c == kw[k]) {
                k++
                if (k == kw.length) return true
            }
        }
        return false
    }
}
