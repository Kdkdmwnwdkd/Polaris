package com.beichen.polaris.core.workspace

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * 工作区文件存储：**与 Agent 工具同一个目录** `<filesDir>/agent_workspace`。
 *
 * ## W-27 修：两个目录曾经分家
 * 本类原先指向 `<filesDir>/workspace`，而 Agent 的 read_file / write_file /
 * edit_file / ui_screenshot 全部用 `<filesDir>/agent_workspace`。
 * 于是编辑器和 Agent 各看各的：Agent 写的文件编辑器里没有，检查点拍不到，
 * GitHub 构建也打包不进去。
 *
 * 现在统一到 `agent_workspace`，并在首次访问时把老 `workspace` 目录的内容
 * **一次性迁移**（不覆盖同名文件），迁移后删除空的老目录。
 *
 * ## 路径
 * 支持子目录（`src/main.kt`），校验规则见 [WorkspacePaths]（拒绝对路径与 `..`）。
 */
object WorkspaceStore {

    private const val TAG = "WorkspaceStore"

    /** 新目录名（Agent 工具同款）。 */
    private const val DIR_NAME = "agent_workspace"
    /** 老目录名（仅迁移用）。 */
    private const val LEGACY_DIR = "workspace"

    @Volatile
    private var migrated = false

    /**
     * 让下一次 [dir] 重新走迁移检查（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * `migrated` 是「老目录只需搬一次」的进程级记忆。harness 每轮新建临时 filesDir，
     * 不复位这个标记的话，第二个用例的工作区会指向第一个用例的目录——而且不报错，
     * 只是写进了错误的地方。
     *
     * 不删除任何文件：迁移动作本身是「目标不存在才搬」，重复执行是安全的。
     */
    fun reset() {
        migrated = false
    }

    /** 工作区根目录；首次调用触发老目录迁移。 */
    fun dir(context: Context): File {
        val target = File(context.filesDir, DIR_NAME)
        if (!migrated) {
            synchronized(this) {
                if (!migrated) {
                    runCatching { migrateLegacy(context, target) }
                    migrated = true
                }
            }
        }
        runCatching { target.mkdirs() }
        return target
    }

    /**
     * 把老 `workspace` 迁到 `agent_workspace`：
     *  - 目标不存在 → 整体改名（最快、保留 mtime）；
     *  - 两边都在 → 逐项复制（不覆盖同名），成功后删源；
     *  - 老目录空 → 直接删。
     */
    private fun migrateLegacy(context: Context, target: File) {
        val legacy = File(context.filesDir, LEGACY_DIR)
        if (!legacy.isDirectory) return
        if (!target.exists()) {
            if (legacy.renameTo(target)) {
                AgentLogger.i(TAG, "工作区已迁移：$LEGACY_DIR → $DIR_NAME")
                return
            }
        }
        val children = legacy.listFiles().orEmpty()
        var moved = 0
        children.forEach { src ->
            val dst = File(target, src.name)
            if (!dst.exists()) {
                val ok = runCatching {
                    if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
                    else {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = false)
                    }
                    true
                }.getOrDefault(false)
                if (ok) {
                    runCatching { src.deleteRecursively() }
                    moved++
                }
            }
        }
        if (legacy.listFiles()?.isEmpty() != false) {
            runCatching { legacy.delete() }
        }
        if (moved > 0) AgentLogger.i(TAG, "工作区迁移：$moved 项从 $LEGACY_DIR 并入 $DIR_NAME")
    }

    /** 一条树节点（相对路径 + 是否目录 + 深度，供 UI 缩进渲染）。 */
    data class TreeEntry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val depth: Int
    )

    /** 顶层文件列表（兼容旧调用；需要子目录请用 [listTree]）。 */
    fun list(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /**
     * 递归列出工作区（目录在前、各自按名称排序），最多 [maxEntries] 条防卡顿。
     * 跳过隐藏文件（`.` 开头）与 `.part`/`.akitmp` 半成品。
     */
    fun listTree(context: Context, maxEntries: Int = 800): List<TreeEntry> {
        val root = dir(context)
        val out = ArrayList<TreeEntry>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (out.size >= maxEntries) return
            val children = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            ).orEmpty()
            for (f in children) {
                if (out.size >= maxEntries) return
                if (f.name.startsWith(".") || f.name.endsWith(".part") || f.name.endsWith(".akitmp")) continue
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                out += TreeEntry(rel, f.name, f.isDirectory, depth)
                if (f.isDirectory) walk(f, rel, depth + 1)
            }
        }
        runCatching { walk(root, "", 0) }
        return out
    }

    fun exists(context: Context, path: String): Boolean {
        val n = WorkspacePaths.safeRelPath(path) ?: return false
        return File(dir(context), n).isFile
    }

    /** 读取文本（UTF-8）；不存在/非法返回 null。 */
    fun read(context: Context, path: String): String? {
        val n = WorkspacePaths.safeRelPath(path) ?: return null
        val f = File(dir(context), n)
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }
            .onFailure { AgentLogger.e(TAG, "读取 $n 失败：${it.message}") }
            .getOrNull()
    }

    /** 写入（覆盖，自动建父目录）；返回是否成功。 */
    fun write(context: Context, path: String, content: String): Boolean {
        val n = WorkspacePaths.safeRelPath(path) ?: return false
        val f = File(dir(context), n)
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }.onFailure { AgentLogger.e(TAG, "写入 $n 失败：${it.message}") }.isSuccess
    }

    /** 删除文件（幂等）；删空的父目录。 */
    fun delete(context: Context, path: String): Boolean {
        val n = WorkspacePaths.safeRelPath(path) ?: return false
        val f = File(dir(context), n)
        return runCatching {
            if (!f.exists()) true
            else {
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                f.parentFile?.takeIf { it != dir(context) && it.listFiles()?.isEmpty() == true }?.delete()
                ok
            }
        }.getOrDefault(false)
    }

    /** 新建空文件（已存在返回 false）。 */
    fun create(context: Context, path: String): Boolean {
        val n = WorkspacePaths.safeRelPath(path) ?: return false
        if (exists(context, n)) return false
        return write(context, n, "")
    }

    /** 单层文件名（旧 API 语义；含 `/` 非法）。 */
    fun safeName(name: String): String? = WorkspacePaths.safeName(name)
}
