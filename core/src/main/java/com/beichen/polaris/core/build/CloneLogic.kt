package com.beichen.polaris.core.build

/**
 * 克隆（下载仓库 zip 并解压）的纯逻辑（W-35）：条目名安全校验、顶层目录剥离、目标目录命名。
 *
 * 无 Android / 无 IO——下载与解压在 `GitHubCloneTool`，本对象只做"这个名字能不能写"的判断。
 * Zip Slip 是这类功能的经典漏洞，所以条目名校验必须由 harness 逐条断言。
 */
object CloneLogic {

    /** 解压条目上限。 */
    const val MAX_ENTRIES = 1_500

    /** 解压总量上限（字节）。 */
    const val MAX_TOTAL_BYTES = 80L * 1024 * 1024

    /** 单个文件上限（字节）。 */
    const val MAX_SINGLE_BYTES = 20L * 1024 * 1024

    /** 下载包上限（字节，压缩后）。 */
    const val MAX_ZIP_BYTES = 60L * 1024 * 1024

    /**
     * zip 条目名 → 安全相对路径。
     *
     * GitHub 的仓库 zip 每个条目都带一层顶层目录（`Repo-main/`），先剥掉。
     * 拒绝：Zip Slip（`..`）、绝对路径、连续斜杠（空段）、`.git/` 开头、超长。
     *
     * @return 可安全拼接的相对路径；**目录条目以 `/` 结尾**；非法返回 null
     */
    fun safeEntryName(raw: String): String? {
        // 绝对路径条目：合法 zip 里不该出现。必须在剥顶层目录**之前**判——
        // 否则 "/etc/passwd" 会被 stripTopDir 削成 "etc/passwd" 而漏过
        if (raw.startsWith("/")) return null
        val stripped = stripTopDir(raw)
        if (stripped.isEmpty()) return null
        if (stripped.length > 500) return null
        if (stripped.startsWith("/")) return null
        val parts = stripped.split('/')
        if (parts.any { it == ".." }) return null
        // 空段（连续斜杠）会解析到意料之外的位置；末尾空段（目录标记）是合法的
        for (i in 0 until parts.size - 1) {
            if (parts[i].isEmpty()) return null
        }
        if (parts.first() == ".git") return null
        return stripped
    }

    /** 剥掉 GitHub zip 的顶层目录（`AgentKit-main/src/x.kt` → `src/x.kt`）。 */
    fun stripTopDir(name: String): String {
        val idx = name.indexOf('/')
        return if (idx < 0) "" else name.substring(idx + 1)
    }

    /**
     * 目标子目录名：默认 `repo-<仓库名>`，清洗成文件名安全的字符。
     *
     * 为什么默认放到子目录而不是工作区根：工作区里有用户/模型自己的文件，
     * 直接铺到根上会把它们盖掉。子目录是"隔离带"。
     */
    fun safeTargetDir(raw: String?, repoName: String): String {
        val fallback = "repo-$repoName"
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        // 同样要在 trim('/') **之前**判绝对路径：先 trim 会把 "/" 吃掉，检查就失效了
        if (trimmed.startsWith("/")) return fallback
        val cleaned = trimmed.replace('\\', '/')
        if (cleaned.split('/').any { it == ".." }) return fallback
        val stripped = cleaned.trim('/')
        if (stripped.isEmpty()) return fallback
        val safe = stripped.filter {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '/' || it == '.'
        }
        return safe.ifBlank { fallback }
    }

    /** 是否目录条目（名字以 `/` 结尾）。 */
    fun isDirectoryEntry(name: String): Boolean = name.endsWith('/')
}
