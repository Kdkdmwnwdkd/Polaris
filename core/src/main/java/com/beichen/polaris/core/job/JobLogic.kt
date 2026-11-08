package com.beichen.polaris.core.job

/**
 * 后台命令的纯逻辑（W-33）：命令清洗、日志尾行、状态文案、重启对账。
 *
 * 无 Android / 无 IO / 无协程——进程编排在 [JobRunner]，本对象只管"怎么展示、怎么判断"，
 * 因此能被 harness 直接断言（尤其是 tail 截断与状态判定，出错会让用户看到错的结果）。
 */
object JobLogic {

    /** 单条命令长度上限（再长基本是拼错了）。 */
    const val MAX_COMMAND = 4_000

    /** `shell_jobs` 默认返回的日志尾行数。 */
    const val DEFAULT_TAIL_LINES = 80

    /** 日志文件大小上限（字节）：超过后继续读但不再写，防日志撑爆存储。 */
    const val MAX_LOG_BYTES = 2 * 1024 * 1024L

    /** 后台任务硬超时（毫秒）：1 小时。超过则 kill 并标记 timeout。 */
    const val MAX_RUNTIME_MS = 60L * 60 * 1000

    /**
     * 清洗命令：去掉首尾空白与**结尾换行**。
     *
     * 结尾换行必须去掉：`sh -c "echo hi\n"` 多数情况没问题，但命令被拼进
     * `cd xxx && <cmd>` 时多一个换行会让某些 shell 提前结束。
     */
    fun normalizeCommand(raw: String): String? {
        val cmd = raw.trim().trimEnd('\n', '\r')
        if (cmd.isEmpty()) return null
        if (cmd.length > MAX_COMMAND) return null
        return cmd
    }

    /**
     * 取日志最后 [lines] 行。
     *
     * 为什么按行取而不是按字节：用户要看的是"最后报了什么错"，
     * 字节截断会把最后一行砍成半句。行数截断则总是完整的行。
     */
    fun tailLines(text: String, lines: Int = DEFAULT_TAIL_LINES): String {
        if (text.isEmpty()) return "（暂无输出）"
        val limit = lines.coerceIn(1, 2_000)
        val all = text.split('\n')
        // 末尾换行会切出一个空串元素，去掉它，避免"最后一行是空白"
        val trimmed = if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all
        if (trimmed.isEmpty()) return "（暂无输出）"
        return if (trimmed.size <= limit) {
            trimmed.joinToString("\n")
        } else {
            val omitted = trimmed.size - limit
            "…（前 $omitted 行已省略）\n" + trimmed.takeLast(limit).joinToString("\n")
        }
    }

    /** 耗时文案：`8s` / `3m20s` / `1h05m`。 */
    fun durationText(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> {
                val m = totalSeconds / 60
                val s = totalSeconds % 60
                if (s == 0L) "${m}m" else "${m}m${s}s"
            }
            else -> {
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                "${h}h${m.toString().padStart(2, '0')}m"
            }
        }
    }

    /**
     * 单条任务的状态文案（含耗时）。
     *
     * @param nowMs 当前时间（测试注入；running 任务的耗时依赖它）
     */
    fun statusText(job: JobInfo, nowMs: Long): String {
        val duration = when {
            job.finishedAt > 0 && job.startedAt > 0 -> durationText(job.finishedAt - job.startedAt)
            job.startedAt > 0 -> durationText((nowMs - job.startedAt).coerceAtLeast(0))
            else -> "0s"
        }
        return when (job.status) {
            JobStatus.RUNNING -> "⏳ 运行中（已 $duration）"
            JobStatus.DONE -> "✅ 完成（exit 0，$duration）"
            JobStatus.FAILED -> "❌ 失败（exit ${job.exitCode ?: "?"}，$duration）"
            JobStatus.CANCELLED -> "⏹ 已取消（$duration）"
            JobStatus.TIMEOUT -> "⏱ 超时被终止（${durationText(MAX_RUNTIME_MS)}）"
            JobStatus.INTERRUPTED -> "⚠️ 结果未知（App 重启，进程可能已中断）"
            else -> "状态未知：${job.status}"
        }
    }

    /**
     * 重启对账：把「标记为 running 但其实已经不可能在跑」的任务改成 interrupted。
     *
     * 判据用 [staleBeforeMs]：`startedAt < staleBefore` 的 running 任务视为中断。
     * 不直接用"进程还在吗"判断——手机端查不到别的进程，只能靠时间与重启事实。
     */
    fun reconcileStatus(job: JobInfo, staleBeforeMs: Long): JobInfo {
        if (job.status != JobStatus.RUNNING) return job
        if (job.startedAt <= 0L || job.startedAt >= staleBeforeMs) return job
        return job.copy(status = JobStatus.INTERRUPTED, finishedAt = job.finishedAt)
    }

    /** 列表摘要：状态 + 短命令 + id（`shell_jobs` 无 id 时返回多行）。 */
    fun summaryLine(job: JobInfo, nowMs: Long): String {
        val oneLine = job.command.replace('\n', ' ').trim()
        val short = if (oneLine.length <= 48) oneLine else oneLine.take(48) + "…"
        return "${job.id}  ${statusText(job, nowMs)}\n    $short"
    }
}
