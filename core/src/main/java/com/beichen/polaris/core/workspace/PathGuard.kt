package com.beichen.polaris.core.workspace

import java.io.File

/**
 * 工作区路径的越界校验。
 *
 * 判据只有一条：**规范化后的绝对路径要以「根目录 + 分隔符」开头**。
 * 少写那个分隔符是很常见的失误——`/w/polaris-evil/x` 在字符串上以 `/w/polaris`
 * 开头，但它在目录上根本不在 `/w/polaris` 里面。项目里原本有三份各自手写的
 * 前缀检查（FileBackupStore / SkillImportRepository / BuiltInTools 写对了，
 * AppManageTools 和 OcrTool 漏了分隔符），这就是把它收成一处的原因。
 *
 * 绝对路径单独拒：`File(root, rel)` 对绝对路径**不会**返回 rel，而是把前导分隔符
 * 吃掉再拼到 root 后面（`/etc/passwd` → `<root>/etc/passwd`），静默指到一个
 * 完全无关的文件。这种改写比直接报错更难查，所以在入口就拦掉。
 */
object PathGuard {

    /** [rel] 相对 [root] 解析；越界（`..` 穿越、绝对路径、解析失败）返回 null。 */
    fun resolveInside(root: File, rel: String): File? = runCatching {
        val trimmed = rel.trim()
        // 绝对路径必须显式拒绝：`File(parent, child)` 在 child 是绝对路径时并不会
        // 返回 child，而是把前导分隔符吃掉再拼到 parent 后面——`/etc/passwd` 会变成
        // `<root>/etc/passwd`，看着在根内，其实指向一个完全无关的文件。
        // 静默改写路径比报错更难查，所以直接拒。
        if (trimmed.isEmpty() || File(trimmed).isAbsolute) return@runCatching null
        val base = root.canonicalPath.trimEnd(File.separatorChar)
        val target = File(root, trimmed)
        if (target.canonicalPath.startsWith(base + File.separator)) target else null
    }.getOrNull()

    /** [file] 是否确实位于 [root] 之内（[root] 本身不算）。 */
    fun isInside(root: File, file: File): Boolean = runCatching {
        val base = root.canonicalPath.trimEnd(File.separatorChar)
        file.canonicalPath.startsWith(base + File.separator)
    }.getOrDefault(false)
}
