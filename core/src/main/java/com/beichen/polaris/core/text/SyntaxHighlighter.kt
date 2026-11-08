package com.beichen.polaris.core.text

/**
 * 轻量语法高亮词法器（W-27）：纯函数、无 Android/Compose 依赖，harness 可直接验证。
 *
 * ## 为什么不引库
 * 完整语法高亮（tree-sitter / Prism）会给 APK 再涨几 MB，而手机端编辑小文件
 * 只需要"能分清注释/字符串/关键字"就够。这里 60 行单遍扫描解决 80% 观感。
 *
 * ## 识别范围
 *  - 行注释：`//`（类 C）、`#`（py/sh/yaml/toml）
 *  - 字符串：`"…"` / `'…'`，支持反斜杠转义
 *  - 数字：`123` / `3.14`
 *  - 关键字：按语言集合（见 [KEYWORDS]）
 *  - 不识别：块注释跨行、模板字符串插值、正则字面量（纯文本预览够用）
 */
object SyntaxHighlighter {

    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER }

    /** 高亮片段：`[start, end)` 字符区间。 */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /** 超过这个长度就不高亮（手机端逐字符扫描会卡输入）。 */
    const val MAX_HIGHLIGHT_CHARS = 120_000

    private val HASH_COMMENT_LANGS = setOf("python", "shell", "yaml", "toml")
    private val SLASH_COMMENT_LANGS = setOf(
        "kotlin", "java", "javascript", "typescript", "c", "go", "rust", "json", "css", "sql"
    )

    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
            "return", "break", "continue", "try", "catch", "finally", "throw", "import", "package",
            "private", "public", "internal", "protected", "override", "open", "data", "sealed",
            "suspend", "inline", "null", "true", "false", "is", "in", "as", "this", "super", "by"
        ),
        "java" to setOf(
            "class", "interface", "enum", "public", "private", "protected", "static", "final",
            "void", "int", "long", "double", "float", "boolean", "char", "if", "else", "for",
            "while", "switch", "case", "return", "new", "try", "catch", "finally", "throw",
            "import", "package", "extends", "implements", "null", "true", "false"
        ),
        "javascript" to setOf(
            "function", "const", "let", "var", "class", "extends", "return", "if", "else", "for",
            "while", "do", "switch", "case", "break", "continue", "try", "catch", "finally",
            "throw", "new", "typeof", "instanceof", "import", "export", "default", "async",
            "await", "null", "undefined", "true", "false", "this"
        ),
        "typescript" to setOf(
            "function", "const", "let", "var", "class", "interface", "type", "enum", "extends",
            "implements", "return", "if", "else", "for", "while", "switch", "case", "break",
            "continue", "try", "catch", "finally", "throw", "new", "import", "export", "default",
            "async", "await", "null", "undefined", "true", "false", "this", "public", "private"
        ),
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
            "try", "except", "finally", "raise", "import", "from", "as", "with", "lambda",
            "None", "True", "False", "and", "or", "not", "in", "is", "pass", "yield", "async", "await"
        ),
        "shell" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "return", "export", "local", "echo", "cd", "source", "exit"
        ),
        "go" to setOf(
            "func", "var", "const", "type", "struct", "interface", "return", "if", "else", "for",
            "range", "switch", "case", "break", "continue", "go", "defer", "chan", "select",
            "package", "import", "nil", "true", "false"
        ),
        "rust" to setOf(
            "fn", "let", "mut", "const", "struct", "enum", "impl", "trait", "return", "if", "else",
            "match", "for", "while", "loop", "break", "continue", "use", "mod", "pub", "crate",
            "self", "Self", "Some", "None", "Ok", "Err", "true", "false"
        ),
        "c" to setOf(
            "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
            "int", "long", "float", "double", "char", "void", "struct", "typedef", "static",
            "const", "sizeof", "include", "define", "NULL"
        ),
        "sql" to setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP",
            "BY", "ORDER", "HAVING", "LIMIT", "NULL", "AND", "OR", "NOT", "AS"
        )
    )

    /**
     * 语言别名归一（W-30）：模型输出的代码块标记五花八门（`kt`/`js`/`py`/`bash`/`c++`），
     * 词法器只认规范名。不认识的返回 null（= 不高亮，纯文本显示，不猜）。
     */
    fun normalizeLanguage(raw: String?): String? {
        val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (key) {
            "kotlin", "kt", "kts" -> "kotlin"
            "java" -> "java"
            "javascript", "js", "mjs", "cjs", "jsx", "node" -> "javascript"
            "typescript", "ts", "tsx" -> "typescript"
            "python", "py", "python3" -> "python"
            "shell", "sh", "bash", "zsh", "console", "shell-session" -> "shell"
            "go", "golang" -> "go"
            "rust", "rs" -> "rust"
            "c", "h", "cpp", "c++", "cc", "hpp", "objective-c" -> "c"
            "sql" -> "sql"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "css" -> "css"
            else -> null
        }
    }

    /**
     * 扫描整段文本，返回按起点排序、互不重叠的高亮片段。
     * [language] 为 null 或文本过长 → 返回空列表（调用方按纯文本显示）。
     */
    fun spans(text: String, language: String?): List<Span> {
        val lang = language?.lowercase() ?: return emptyList()
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        val slashComment = lang in SLASH_COMMENT_LANGS
        val hashComment = lang in HASH_COMMENT_LANGS
        val keywords = KEYWORDS[lang] ?: emptySet()
        // SQL 关键字通常大写；统一大写比较
        val sqlMode = lang == "sql"

        val out = ArrayList<Span>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // 行注释
            if ((slashComment && c == '/' && i + 1 < n && text[i + 1] == '/') ||
                (hashComment && c == '#')
            ) {
                var j = i
                while (j < n && text[j] != '\n') j++
                out += Span(i, j, Kind.COMMENT)
                i = j
                continue
            }
            // 字符串
            if (c == '"' || c == '\'') {
                val quote = c
                var j = i + 1
                while (j < n) {
                    val ch = text[j]
                    if (ch == '\\' && j + 1 < n) { j += 2; continue }
                    if (ch == quote) { j++; break }
                    if (ch == '\n') break // 未闭合字符串到行尾为止
                    j++
                }
                out += Span(i, j.coerceAtMost(n), Kind.STRING)
                i = j
                continue
            }
            // 数字
            if (c.isDigit()) {
                var j = i
                while (j < n && (text[j].isDigit() || text[j] == '.')) j++
                out += Span(i, j, Kind.NUMBER)
                i = j
                continue
            }
            // 标识符 → 关键字
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                val hit = if (sqlMode) keywords.any { it.equals(word, ignoreCase = true) }
                else word in keywords
                if (hit) out += Span(i, j, Kind.KEYWORD)
                i = j
                continue
            }
            i++
        }
        return out
    }
}
