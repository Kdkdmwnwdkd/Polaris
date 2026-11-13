package com.beichen.polaris.core.workspace

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.io.AtomicJsonFile
import java.io.File

/**
 * 检查点（快照）存储：把整个 workspace 目录快照到 `<filesDir>/checkpoints/<id>/`，
 * 支持随时恢复到任意检查点。语义等价「git 检查点」，但用目录级快照实现——
 * 零第三方依赖、离线安全、Android 全兼容（[File.copyRecursively]）。
 *
 * 检查点 id = `<epochMs>_<label>`（label 经安全化）。meta 记录在 `checkpoints/<id>.meta`。
 */
object CheckpointStore {

    private const val TAG = "CheckpointStore"

    data class Checkpoint(
        val id: String,
        val label: String,
        val timeMs: Long,
        val fileCount: Int,
        val sizeBytes: Long
    )

    private fun root(context: Context): File =
        File(context.filesDir, "checkpoints").apply { runCatching { mkdirs() } }

    /** 创建检查点：快照当前 workspace。 */
    fun create(context: Context, label: String): Checkpoint? {
        val ws = WorkspaceStore.dir(context)
        // W-27：快照要含子目录（Agent 的产出常在 screenshots/、downloads/ 下）
        val entries = WorkspaceStore.listTree(context).filter { !it.isDir }
        val size = entries.sumOf { File(ws, it.path).length() }
        val ts = System.currentTimeMillis()
        val safeLabel = label.ifBlank { "checkpoint" }
            .filter { it.isLetterOrDigit() || it in "._- " }.take(40).trim().ifBlank { "checkpoint" }
        val id = "${ts}_$safeLabel"
        val snapDir = File(root(context), id)
        val ok = runCatching {
            // 逐个复制文件（按相对路径建目录），空目录不入快照
            entries.forEach { entry ->
                val src = File(ws, entry.path)
                val dst = File(snapDir, entry.path)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            // 写入 meta。走原子写：写坏一行，[list] 就解析不出这条，
            // 而快照文件其实还在——用户看到的是「检查点凭空少了一个」。
            AtomicJsonFile.write(
                File(root(context), "$id.meta"),
                "label=$safeLabel\ntimeMs=$ts\nfileCount=${entries.size}\nsizeBytes=$size\n",
                keepBackup = false
            )
        }.onFailure { AgentLogger.e(TAG, "创建检查点失败：${it.message}") }.isSuccess
        if (!ok) return null
        return Checkpoint(id, safeLabel, ts, entries.size, size)
    }

    /** 列出所有检查点（按时间倒序）。 */
    fun list(context: Context): List<Checkpoint> {
        val r = root(context)
        val metas = r.listFiles { f -> f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { mf ->
            runCatching {
                val id = mf.name.removeSuffix(".meta")
                val props = mf.readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
                Checkpoint(
                    id = id,
                    label = props["label"] ?: id,
                    timeMs = props["timeMs"]?.toLongOrNull() ?: 0L,
                    fileCount = props["fileCount"]?.toIntOrNull() ?: 0,
                    sizeBytes = props["sizeBytes"]?.toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }.sortedByDescending { it.timeMs }
    }

    /** 恢复检查点：清空当前 workspace 后写回快照文件。 */
    fun restore(context: Context, id: String): Boolean {
        val snapDir = File(root(context), id)
        if (!snapDir.isDirectory) {
            AgentLogger.e(TAG, "检查点不存在：$id")
            return false
        }
        return runCatching {
            val ws = WorkspaceStore.dir(context)
            // 清空 workspace（含子目录，保留根目录本身）
            ws.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            // 写回快照（目录/文件都按原结构恢复）
            snapDir.listFiles()?.forEach { src ->
                val dst = File(ws, src.name)
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
                else src.copyTo(dst, overwrite = true)
            }
        }.onFailure { AgentLogger.e(TAG, "恢复检查点失败：${it.message}") }.isSuccess
    }

    /** 删除检查点。 */
    fun delete(context: Context, id: String): Boolean =
        runCatching {
            File(root(context), id).deleteRecursively()
            File(root(context), "$id.meta").delete()
        }.getOrDefault(false)

    /** 人类可读时间。 */
    fun formatTime(timeMs: Long): String {
        if (timeMs <= 0L) return "—"
        val df = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        return df.format(java.util.Date(timeMs))
    }
}
