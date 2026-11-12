package com.beichen.polaris.core.build

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.time.Monotonic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 构建引擎（调研 07 号文档流程设计的编排层）。
 *
 * 「构建」按钮点一下，本对象把六步链路跑完并逐阶段落盘：
 *   ① PUSHING   工作区 → Git Data API 推 commit
 *   ② QUEUED    workflow_dispatch 触发 CI
 *   ③ BUILDING  轮询 run（15s 一次，40 分钟超时——云端排队+编译都算正当时间）
 *   ④ DOWNLOADING  release/latest 优先，artifact zip 回退
 *   ⑤ SUCCEEDED SHA-256 落档，UI 出「安装」按钮
 *   任一环节失败 → FAILED + 可读原因（LogsScreen 可见 HTTP 状态码）
 *
 * 每个阶段都 [BuildStore.upsert]——杀进程重进「构建」页，历史与进度按
 * builds.json 还原（与 06 媒体任务同款的持久化灵魂）。
 */
object BuildEngine {

    private const val TAG = "BuildEngine"

    /** run 出现前的轮询：dispatch 到 run 可见通常几秒，给 6 次 × 5s。 */
    private const val FIND_RUN_ATTEMPTS = 6
    private const val FIND_RUN_DELAY_MS = 5_000L

    /** run 状态轮询间隔（doc 建议 30s；取 15s 让进度条更顺滑，请求量仍可忽略）。 */
    private const val POLL_INTERVAL_MS = 15_000L

    /** run 超时：40 分钟（排队 + 完整 Android 构建 + NDK 拉取都算正当时间）。 */
    private const val RUN_TIMEOUT_MS = 40 * 60 * 1000L

    /**
     * 构建自己的作用域：**不跟页面走**。
     *
     * 之前 UI 用 `rememberCoroutineScope` 启动，切个 tab 就把协程取消了——一次 40 分钟的
     * 构建被逼成「不能离开此页」。页面现在只负责读 [BuildStore]。
     */
    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)

    @Volatile
    private var running: Job? = null

    /** 是否已有构建在跑（防重复点击；进程重启后自然为 false）。 */
    fun isRunning(): Boolean = running?.isActive == true

    /**
     * 清场（`AppGraph.resetAll` 与诊断用）：取消正在跑的构建并丢掉句柄。
     *
     * 不写 SharedPreferences，也不删 builds.json 或已下载的 APK——那些是用户数据，
     * 该由卸载或用户在构建历史里自己处理。取消本身会走正常收尾（写 CANCELLED）。
     */
    fun reset() {
        running?.cancel()
        running = null
    }

    /**
     * 后台启动一次构建。
     *
     * @return false = 已有构建在进行，本次未启动（调用方提示即可）
     */
    fun startInBackground(context: Context): Boolean {
        if (isRunning()) return false
        running = scope.launch {
            try {
                startBuild(context)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // AppScope.io 自带异常出口，但这里补一条带阶段信息的日志
                AgentLogger.e(TAG, "构建异常退出：${e.message}")
            }
        }
        return true
    }

    /**
     * 清理僵尸任务：进程重启后没有任何 job 在跑，非终态记录都是上次被系统杀掉的。
     *
     * 不做这一步，构建页会永远挂着一条「构建中」——进度条不走、按钮点不动、
     * 也没法判断到底还要不要等。同进程内正在跑的任务不碰（页面重建/旋转会重入）。
     */
    suspend fun reconcileStale(context: Context) = withContext(Dispatchers.IO) {
        if (isRunning()) return@withContext
        val stale = BuildStore.list(context).filter { !it.isTerminal }
        if (stale.isEmpty()) return@withContext
        stale.forEach {
            BuildStore.upsert(
                context,
                it.copy(
                    stage = BuildStage.CANCELLED,
                    error = "应用重启，已停止跟踪（CI 可能仍在运行，可在 GitHub 查看 run）"
                )
            )
        }
        AgentLogger.w(TAG, "清理 ${stale.size} 个中断的构建任务")
    }

    /**
     * 启动一次构建。返回最终任务记录（SUCCEEDED 时 apkPath 可安装）。
     *
     * @param onStage 阶段变更回调（UI 可借此即时刷新，不必等轮询 builds.json）。
     */
    suspend fun startBuild(
        context: Context,
        onStage: (BuildTask) -> Unit = {}
    ): BuildTask = withContext(Dispatchers.IO) {
        val cfg = ConfigStore(context).loadGithubConfig()
            ?: return@withContext newTask(BuildStage.FAILED, error = "未配置 GitHub（owner/repo/PAT）")
                .also { BuildStore.upsert(context, it); onStage(it) }

        var task = newTask(BuildStage.PUSHING)
        BuildStore.upsert(context, task)
        onStage(task)

        fun advance(stage: BuildStage, error: String? = null, sha: String? = task.commitSha) {
            task = task.copy(stage = stage, error = error, commitSha = sha ?: task.commitSha)
            BuildStore.upsert(context, task)
            onStage(task)
        }

        val service = GitHubBuildService(cfg)

        try {
            // ① 推送工作区
            val sha = try {
                service.pushWorkspace(context).getOrThrow()
            } catch (c: CancellationException) {
                // 取消 ≠ 失败：不写 FAILED，也不 advance。用户退出页面 / 点停止时，
                // 留下「构建失败：推送失败：Canceled」会让下一次点开历史的人以为 CI 真的挂了。
                throw c
            } catch (e: Throwable) {
                AgentLogger.e(TAG, "推送失败：${e.message}")
                advance(BuildStage.FAILED, "推送失败：${e.message}")
                return@withContext task
            }
            advance(BuildStage.QUEUED, sha = sha)

            // ② 触发 CI
            try {
                service.dispatch().getOrThrow()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                AgentLogger.e(TAG, "dispatch 失败：${e.message}")
                advance(BuildStage.FAILED, "触发 CI 失败：${e.message}")
                return@withContext task
            }

            // ③ 找 run（可能延迟可见）
            var run: RunInfo? = null
            for (attempt in 1..FIND_RUN_ATTEMPTS) {
                run = try {
                    service.findRun(sha).getOrThrow()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // 网络抖动不判死刑，下一轮再找；旧写法 getOrNull() 连取消一起吞了，
                    // 于是「取消」在这里变成「继续重试 6 次」。
                    null
                }
                if (run != null) break
                delay(FIND_RUN_DELAY_MS)
            }
            if (run == null) {
                advance(BuildStage.FAILED, "触发后未找到对应 CI run（可稍后在构建历史重试）")
                return@withContext task
            }
            advance(BuildStage.BUILDING)
            task = task.copy(runNumber = run.number).also { BuildStore.upsert(context, it); onStage(it) }

            // ④ 轮询 run 至终态
            // 单调时钟：墙钟前跳（对时/手动改时间）会让 40 分钟的等待立刻判超时。
            val startedAt = Monotonic.nowMs()
            var last: RunInfo = run
            while (true) {
                if (Monotonic.nowMs() - startedAt > RUN_TIMEOUT_MS) {
                    advance(BuildStage.FAILED, "CI 超时（40 分钟未完成），请在 GitHub 查看 run #${run.number}")
                    return@withContext task
                }
                // 轮询失败不判死刑：网络抖动记一笔、等下一轮（continue 在循环体内，不在 lambda 里）
                val polled = try {
                    service.runStatus(run.id).getOrThrow()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    AgentLogger.e(TAG, "轮询 run 失败：${e.message}")
                    null
                }
                if (polled == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                last = polled
                if (last.isCompleted) break
                delay(POLL_INTERVAL_MS)
            }
            if (!last.isSuccess) {
                advance(BuildStage.FAILED, "CI 失败（${last.conclusion}），run #${run.number}")
                return@withContext task
            }

            // ⑤ 下载 APK：release/latest 优先，artifact 回退
            advance(BuildStage.DOWNLOADING)
            val apkFile = File(BuildStore.apkDir(context), "polaris-run-${run.number}.apk")
            val downloaded = try {
                val viaRelease = try {
                    service.downloadLatestApk(
                        minUpdatedAt = task.createdAt,
                        into = apkFile,
                        onProgress = { p ->
                            if (task.downloadProgress != p) {
                                task = task.copy(downloadProgress = p)
                                BuildStore.upsert(context, task)
                                onStage(task)
                            }
                        }
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    AgentLogger.e(TAG, "release 下载回退：${e.message}")
                    service.downloadArtifact(run.id, apkFile)
                }
                viaRelease.getOrThrow()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                AgentLogger.e(TAG, "APK 下载失败：${e.message}")
                advance(BuildStage.FAILED, "APK 下载失败：${e.message}")
                return@withContext task
            }

            // ⑥ 校验落档
            val digest = service.sha256(downloaded)
            val done = task.copy(
                stage = BuildStage.SUCCEEDED,
                downloadProgress = 1f,
                apkPath = downloaded.absolutePath,
                sha256 = digest,
                sizeBytes = downloaded.length(),
                error = null
            )
            BuildStore.upsert(context, done)
            onStage(done)
            AgentLogger.d(TAG, "构建 #${run.number} 成功：${downloaded.name}（${downloaded.length()}B, sha256 ${digest.take(12)}…）")
            done
        } catch (c: CancellationException) {
            // 页面关闭 / 显式停止：既不写 FAILED（CI 可能仍在云端跑，写失败是误导），
            // 也不留在 BUILDING（那样再进来会看到一个永远不动的「构建中」）。
            advance(BuildStage.CANCELLED, "已停止跟踪（CI 可能仍在 GitHub 上运行）")
            throw c
        }
    }

    private fun newTask(stage: BuildStage, error: String? = null) = BuildTask(
        id = UUID.randomUUID().toString(),
        stage = stage,
        error = error,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}
