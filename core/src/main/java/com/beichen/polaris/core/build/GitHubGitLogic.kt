package com.beichen.polaris.core.build

import java.security.MessageDigest

/**
 * 一个待提交的文件变更。
 *
 * @param remoteSha 远端已有的 blob sha；null = 远端没有这个文件（新增）
 */
data class FileChange(
    val path: String,
    val localSha: String,
    val remoteSha: String?
) {
    /** `added` / `modified`（本批不做删除：误删远端文件的代价远大于少删一个）。 */
    val status: String get() = if (remoteSha == null) "added" else "modified"
}

/**
 * GitHub 提交的纯逻辑（W-32）：计算 blob sha、比对差异、路径与消息规范化。
 *
 * **不依赖 Android / 不联网**——网络编排在 [GitHubGitService]，本对象只做"该提交什么"的判断，
 * 所以能被 harness 直接断言（尤其是 blob sha 必须与 git 算出来的一致，否则整棵树会脏）。
 */
object GitHubGitLogic {

    /** 单文件上限：超过就不提交（GitHub blob API 支持 100MB，但手机端没必要传这么大）。 */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    /** 单次最多提交多少个文件（避免一次 tree 请求过大、也避免失控）。 */
    const val MAX_FILES = 200

    /** 提交标题建议长度（超过截断，与 git 惯例一致）。 */
    const val MAX_SUBJECT = 72

    /**
     * 计算 git blob sha1：`sha1("blob {len}\u0000" + bytes)`。
     *
     * 这是 git 的对象哈希公式。**必须本地算**：先算再比对，只有真正变了的文件才上传 blob——
     * 否则每次提交都要把所有文件传一遍（30 个文件的项目就是几 MB 白流量）。
     */
    fun blobSha(content: ByteArray): String {
        val header = "blob ${content.size}".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val md = MessageDigest.getInstance("SHA-1")
        md.update(header)
        md.update(content)
        // Byte 转无符号再格式化：直接 format(Byte) 在负数上依赖 Formatter 的位宽推断
        return md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    /**
     * 规范化仓库内相对路径；非法返回 null。
     *
     * 拒绝：空、绝对路径、含 `..` 段、含反斜杠（统一转 `/`）、控制字符、
     * 超长（> 500）。这些都会让 Git tree 写入失败或写到意料之外的位置。
     */
    fun normalizePath(raw: String): String? {
        val path = raw.trim().replace('\\', '/').trimStart('/')
        if (path.isEmpty() || path.length > 500) return null
        if (path.any { it.code < 0x20 }) return null
        if (path.split('/').any { it == ".." || it.isEmpty() }) return null
        return path
    }

    /**
     * 比对本地文件与远端 tree，返回**需要提交**的变更（新增 + 修改，不含删除）。
     *
     * 结果按路径排序：同样的输入永远得到同样的顺序，提交摘要与测试断言都稳定。
     */
    fun diff(local: Map<String, ByteArray>, remote: Map<String, String>): List<FileChange> {
        val out = ArrayList<FileChange>()
        for ((path, bytes) in local) {
            val normalized = normalizePath(path) ?: continue
            val localSha = blobSha(bytes)
            val remoteSha = remote[normalized]
            if (remoteSha == localSha) continue
            out += FileChange(path = normalized, localSha = localSha, remoteSha = remoteSha)
        }
        return out.sortedBy { it.path }
    }

    /**
     * 规范化提交消息。
     *
     * 规则：去首尾空白与末尾空行；首行超 [MAX_SUBJECT] 截断；空则用 [fallback]；
     * 总长 500（GitHub 对 message 没硬限制，但过长的消息在列表里没法看）。
     */
    fun normalizeMessage(raw: String, fallback: String = "chore: 更新工作区"): String {
        val text = raw.trim().trimEnd('\n')
        if (text.isEmpty()) return fallback
        val lines = text.split('\n')
        val subject = lines.first().trim()
        // 正文首尾都要 trim：drop(1) 会保留标题后的空行，不 trim 会拼出三个换行
        val body = lines.drop(1).joinToString("\n").trim()
        val fixedSubject = if (subject.length <= MAX_SUBJECT) subject
        else subject.take(MAX_SUBJECT - 1) + "…"
        val full = if (body.isEmpty()) fixedSubject else "$fixedSubject\n\n$body"
        return if (full.length <= 500) full else full.take(500) + "\n…"
    }

    /** 变更摘要（工具返回给模型看的那段）：`+` 新增 / `~` 修改。 */
    fun summarizeChanges(changes: List<FileChange>, max: Int = 20): String {
        if (changes.isEmpty()) return "（没有变更）"
        val head = changes.take(max).joinToString("\n") { change ->
            "${if (change.status == "added") "+" else "~"} ${change.path}"
        }
        val rest = changes.size - max
        return if (rest > 0) "$head\n…还有 $rest 个文件" else head
    }

    /** 文件是否值得提交（超限的跳过，并在工具层提示）。 */
    fun isCommittable(path: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_FILE_BYTES) return false
        return normalizePath(path) != null
    }
}
