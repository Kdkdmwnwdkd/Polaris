package com.beichen.polaris.startup

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动自救的隔离动作（纯 JVM，不依赖 Android、不写日志）。
 *
 * 拆出来的理由与 [StartupGuardPolicy] 相同：这一段要在 CI 里真跑。
 * 日志由调用方记——纯逻辑不该依赖日志器，否则连测试都得先初始化日志。
 */
internal object StartupQuarantine {

    /** 隔离目录名（相对 `filesDir`）。 */
    const val DIR_NAME: String = "startup-quarantine"

    /**
     * 允许被隔离的条目（相对 `filesDir`）。**只列业务状态，绝不列日志与崩溃报告**。
     *
     * `logs/` 与 `crash/` 是排障的唯一证据，隔离它们等于把事故现场一起锁进柜子。
     * 用户被锁在门外时，唯一还能救回来的东西就是这两样。
     */
    val MOVABLE: List<String> = listOf(
        "conversations.json",
        "conversations.d",
        "agent_run_state.json",
        "run-journal",
        "drafts.json",
        "jobs",
        "todos.json",
        "workflow",
        "bookmarks.json",
        "prompts.json",
        "memory",
    )

    /** 一次隔离的结果。 */
    data class Result(
        val dir: File,
        val moved: List<String>,
        val failed: List<String>,
    )

    /**
     * 把 [MOVABLE] 里的条目移到 `<filesDir>/startup-quarantine/<时间戳>/`。
     *
     * 逐条 `renameTo`，单条失败不影响其余——某条正被占用就跳过它，剩下的照样搬。
     * 搬不动的那条留在原地；它若真是崩溃原因，下次启动会再来一次。
     *
     * 返回 null 表示「什么都没搬」：要么目录建不出来，要么本来就没有可搬的东西。
     * 两者都不该被当成「隔离成功」——否则计数会被清零，用户永远等不到自救。
     */
    fun move(filesDir: File?, now: Long): Result? {
        if (filesDir == null) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val dir = File(File(filesDir, DIR_NAME), stamp)
        if (!dir.mkdirs() && !dir.isDirectory) return null

        val moved = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in MOVABLE) {
            val src = File(filesDir, name)
            if (!src.exists()) continue
            if (src.renameTo(File(dir, name))) moved += name else failed += name
        }
        if (moved.isEmpty()) return null

        runCatching { writeReadme(dir, moved, failed, now) }
        return Result(dir, moved, failed)
    }

    /**
     * 说明文件是给人看的：用户若想把会话找回来，照着这一段搬回去就行。
     *
     * 写失败不影响隔离本身（文件已经搬走了），所以调用方用 runCatching 兜住。
     */
    private fun writeReadme(dir: File, moved: List<String>, failed: List<String>, now: Long) {
        File(dir, "README.txt").writeText(
            buildString {
                appendLine("启动自救隔离目录")
                appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}")
                appendLine()
                appendLine("发生了什么：App 连续 ${StartupGuardPolicy.MAX_STRIKES} 次在启动阶段没起来（界面没能出来）。")
                appendLine("为了让你能进来，这里把「可能带病」的状态整体移了出来，**没有删除**。")
                appendLine()
                appendLine("已移出（${moved.size} 项）：${moved.joinToString(", ")}")
                if (failed.isNotEmpty()) {
                    appendLine("未能移出（${failed.size} 项）：${failed.joinToString(", ")}")
                }
                appendLine()
                appendLine("日志与崩溃报告不在本目录，仍在 logs/ 与 crash/ 下——")
                appendLine("那是排障证据，任何情况下都不该被挪走。")
                appendLine()
                appendLine("想把会话找回来：把本目录下的条目复制回 App 数据目录（与 logs 同级）后重启 App。")
                appendLine("若搬回去之后又开始崩溃，说明那份状态确实是原因，只能删掉它。")
            }
        )
    }
}
