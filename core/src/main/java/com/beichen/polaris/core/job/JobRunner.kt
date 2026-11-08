package com.beichen.polaris.core.job

import com.beichen.polaris.core.AppScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台命令执行器（W-33）。
 *
 * ## 为什么独立于终端会话池
 * 会话池的 `exec` 靠 sentinel 标记判断"这条命令跑完了"，本质是**同步**的；
 * 在同一个 PTY 里塞一个长时间运行的任务，会让后续所有命令都在排队等它。
 * 后台任务用独立的 `ProcessBuilder` 起进程、输出直接落文件，不与会话池共享任何状态——
 * 代价是**不继承终端会话的环境与容器**（proot 里的工具链在后台任务里用不了），
 * 这一点在工具描述与 NOTES 里都写明了。
 *
 * ## 生命周期
 * 进程归 App 所有：App 被杀时系统会一并回收（同进程组）。重启后 [JobStore.reconcile]
 * 把残留的 running 任务标成 interrupted——**不假装它还在跑，也不假装它成功**。
 */
class JobRunner(
    private val store: JobStore,
    /** 任务进入终态时回调（用于发通知）。在 IO 线程调用，实现方自己切主线程。 */
    private val onFinished: (JobInfo) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)

    /** id → 进程（取消用）。 */
    private val processes = ConcurrentHashMap<String, Process>()

    /**
     * 启动一条后台命令。
     *
     * @param cwd 工作目录（null = 进程默认 cwd）
     * @return 成功返回初始 JobInfo（status=running）
     */
    fun start(command: String, cwd: File?): Result<JobInfo> = runCatching {
        val clean = JobLogic.normalizeCommand(command)
            ?: throw IllegalArgumentException("命令为空或超过 ${JobLogic.MAX_COMMAND} 字")
        val id = "j" + System.currentTimeMillis().toString(36)
        val logFile = store.logFile(id)
        logFile.parentFile?.mkdirs()

        val process = ProcessBuilder("sh", "-c", clean)
            .apply {
                if (cwd != null && cwd.isDirectory) directory(cwd)
                redirectErrorStream(true)   // stderr 并进 stdout：日志按发生顺序读才有意义
            }
            .start()

        val job = JobInfo(
            id = id,
            command = clean,
            cwd = cwd?.absolutePath.orEmpty(),
            startedAt = System.currentTimeMillis(),
            status = JobStatus.RUNNING,
            logPath = logFile.absolutePath
        )
        store.save(job)
        processes[id] = process

        scope.launch { pump(id, process, logFile) }
        job
    }

    /** 取消：杀进程并立刻标记 cancelled（不等 pump 收尾，避免 UI 上"点了没反应"）。 */
    fun cancel(id: String): Boolean {
        val job = store.get(id) ?: return false
        if (job.finished) return false
        val process = processes[id]
        runCatching { process?.destroy() }
        store.save(
            job.copy(
                status = JobStatus.CANCELLED,
                finishedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    /** App 退出时调用：杀掉所有在跑的任务（不落盘为 cancelled，交给下次启动对账）。 */
    fun shutdown() {
        processes.values.forEach { runCatching { it.destroy() } }
        processes.clear()
    }

    /**
     * 读输出 → 落日志 → 等结束 → 更新状态 → 回调。
     *
     * 读流与等结束**必须并行**：`waitFor()` 在流未读完时可能死锁（管道缓冲写满），
     * 所以读流单独起一个协程，主流程轮询 `isAlive`。
     */
    private suspend fun pump(id: String, process: Process, logFile: File) {
        val reader = scope.launch {
            var written = 0L
            runCatching {
                logFile.bufferedWriter().use { writer ->
                    process.inputStream.bufferedReader().use { input ->
                        val buf = CharArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            // 超过上限继续读但不再写：不写会让管道堵住、进程卡死
                            if (written < JobLogic.MAX_LOG_BYTES) {
                                writer.write(buf, 0, n)
                                written += n
                            }
                        }
                        writer.flush()
                    }
                }
            }
        }

        // 轮询而不是阻塞 waitFor：既能有超时，也不会被管道缓冲卡住
        val deadline = System.currentTimeMillis() + JobLogic.MAX_RUNTIME_MS
        var exitCode: Int? = null
        while (System.currentTimeMillis() < deadline) {
            val value = runCatching { if (process.isAlive) null else process.exitValue() }.getOrNull()
            if (value != null) {
                exitCode = value
                break
            }
            delay(400)
        }

        if (exitCode == null) {
            runCatching { process.destroyForcibly() }
        }
        // 等读流收尾，保证日志完整（进程结束后流会 EOF，正常几百毫秒内结束）
        runCatching { kotlinx.coroutines.withTimeoutOrNull(5_000) { reader.join() } }

        processes.remove(id)
        val current = store.get(id) ?: return
        // 已经被用户取消：保留 cancelled，不覆盖成 failed
        if (current.finished) return
        val finished = current.copy(
            status = when {
                exitCode == null -> JobStatus.TIMEOUT
                exitCode == 0 -> JobStatus.DONE
                else -> JobStatus.FAILED
            },
            exitCode = exitCode,
            finishedAt = System.currentTimeMillis()
        )
        store.save(finished)
        runCatching { onFinished(finished) }
        store.prune()
    }
}
