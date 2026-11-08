package com.beichen.polaris.core.workspace

/**
 * 工作区路径规则（W-27）：纯函数，无 Android 依赖，harness 可直接验证。
 *
 * ## 背景：两个工作区曾分家
 * 编辑器 / 检查点 / GitHub 构建用 `<filesDir>/workspace`，而 Agent 的工具
 * （read_file / write_file / edit_file / ui_screenshot）用 `<filesDir>/agent_workspace`。
 * 结果：Agent 写的文件编辑器看不到、检查点拍不到、构建也打包不进去。
 * 现已统一到 `agent_workspace`（Agent 产出为主），老目录一次性迁移（见 WorkspaceStore）。
 *
 * ## 路径规则
 * 编辑器现在支持**子目录**（文件树），所以校验从"单层文件名"放宽到"相对路径"，
 * 但防穿越的底线不变：拒绝绝对路径、`.`/`..`、空段、控制字符。
 */
object WorkspacePaths {

    /** 路径最大长度（防止极端长名把 UI 撑爆）。 */
    const val MAX_PATH_LEN = 240

    /** 允许的字符：字母数字（含中文）、点、下划线、短横线、空格、括号、方括号、#、+、=。 */
    private val ALLOWED_EXTRA = "._- ()[]#+="

    /**
     * 规范化并校验相对路径；非法返回 null。
     * 支持 `a/b/c.txt`；反斜杠统一转成正斜杠；不允许前后空格。
     */
    fun safeRelPath(raw: String): String? {
        val t = raw.trim().replace('\\', '/')
        if (t.isEmpty() || t.length > MAX_PATH_LEN) return null
        if (t.startsWith("/")) return null
        val parts = t.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        for (p in parts) {
            if (p.length > 120) return null
            if (!p.all { c -> c.isLetterOrDigit() || c in ALLOWED_EXTRA }) return null
        }
        return parts.joinToString("/")
    }

    /** 单层文件名（旧 API 语义）：含 `/` 一律非法。 */
    fun safeName(raw: String): String? {
        val p = safeRelPath(raw) ?: return null
        return if (p.contains('/')) null else p
    }

    /** 取扩展名（小写，不含点）；无扩展名返回空串。 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase()
    }

    /**
     * 按扩展名判断高亮语言；不认识返回 null（纯文本，不做任何着色）。
     * 只覆盖常见几种——引入完整语法高亮库会让 APK 再涨几 MB，不值得。
     */
    fun languageOf(path: String): String? = when (extensionOf(path)) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "sh", "bash", "zsh" -> "shell"
        "json", "json5" -> "json"
        "xml", "html", "htm" -> "xml"
        "css" -> "css"
        "md", "markdown" -> "markdown"
        "yml", "yaml" -> "yaml"
        "toml" -> "toml"
        "sql" -> "sql"
        "c", "h", "cpp", "cc", "hpp" -> "c"
        "go" -> "go"
        "rs" -> "rust"
        else -> null
    }

    /** 是否值得用等宽字体编辑（文本类）。 */
    fun isTextLikely(path: String): Boolean {
        val ext = extensionOf(path)
        if (ext.isEmpty()) return true
        return ext !in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "mp4", "mp3", "wav", "zip",
            "apk", "gguf", "pdf", "so", "bin"
        )
    }
}
