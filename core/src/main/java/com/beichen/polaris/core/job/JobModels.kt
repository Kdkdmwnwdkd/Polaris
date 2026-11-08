package com.beichen.polaris.core.job

import kotlinx.serialization.Serializable

/**
 * 一条后台命令（W-33）。
 *
 * 状态机：`running` → `done` / `failed` / `cancelled` / `timeout`；
 * 进入终态后不再变化（重启恢复时 running 会被 [JobLogic.reconcileStatus] 改成 `interrupted`）。
 */
@Serializable
data class JobInfo(
    val id: String,
    val command: String,
    /** 工作目录（绝对路径；空 = 应用默认 cwd）。 */
    val cwd: String = "",
    val startedAt: Long = 0,
    /** 结束时间；0 = 还没结束。 */
    val finishedAt: Long = 0,
    /** 退出码；null = 还没结束（或拿不到）。 */
    val exitCode: Int? = null,
    val status: String = JobStatus.RUNNING,
    /** 日志文件绝对路径（读 tail 用）。 */
    val logPath: String = ""
) {
    /** 是否已进入终态。 */
    val finished: Boolean get() = status != JobStatus.RUNNING
}

/** 任务状态常量（不用 enum：要进 JSON，字符串更宽容）。 */
object JobStatus {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val TIMEOUT = "timeout"
    /** 进程在结束时仍标记为 running（App 被杀/重启）—— 明确告诉用户"结果未知"。 */
    const val INTERRUPTED = "interrupted"
}
