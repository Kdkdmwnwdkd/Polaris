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
 *
 * ## 生命周期
 *
 * 构建跑在 [scope]（进程级），不在页面 scope 里：一次构建 15–40 分钟，用户不可能
 * 一直停在构建页。页面只读 [BuildStore]，[startInBackground] 负责防重入，
 * [stop] 是用户显式停止，[resumeInterrupted] 在进程被杀后把还没出结果的 run 接回来。
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

    /** 停止跟踪的统一文案：CI 在云端停不掉，说清「本地不管了」而不是「构建没了」。 */
    private const val STOPPED_REASON = "已停止跟踪（CI 可能仍在 GitHub 上运行）"

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
        val job = scope.launch {
            try {
                startBuild(context)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // AppScope.io 自带异常出口，但这里补一条带阶段信息的日志
                AgentLogger.e(TAG, "构建异常退出：${e.message}")
            }
        }
        running = job
        job.invokeOnCompletion { if (running === job) running = null }
        return true
    }

    /**
     * 停止跟踪当前构建（用户点「停止」）。
     *
     * **只停本地**：GitHub 上那一轮 run 会继续跑完。真要取消云端 run 得调
     * `POST /actions/runs/{id}/cancel`，那是另一个语义（浪费已跑的算力、可能影响同仓库的
     * 并发额度），也容易让人以为「包没出来是取消对了」。所以文案一律写「已停止跟踪」。
     */
    fun stop(context: Context) {
        val job = running
        if (job != null) {
            // 有协程在跑：取消后由 startBuild 的 catch 统一落盘，这里不重复写
            job.cancel()
            return
        }
        // 进程重启过、续跟踪又没接上：没有协程可取消，直接落盘，别留「构建中」
        scope.launch {
            BuildStore.list(context).firstOrNull { !it.isTerminal }?.let { markCancelled(context, it) }
        }
    }

    /**
     * 进构建页时调一次：把上次进程留下的、还没出结果的任务处理掉。
     *
     * 分两种，不能一刀切成「已停止」：
     *
     * - **已经拿到 run 的**（[BuildTask.runId] 非空）：CI 很可能还在跑甚至已经跑完。
     *   直接标停等于让用户去 GitHub 手动找 run、手动下包——而这正是本页面要省掉的事。
     *   所以先续上跟踪：查一次远端状态，已完成就接着下载，还在跑就继续轮询。
     * - **run 之前的**（PUSHING / QUEUED）：本地没有任何标识能定位云端状态，
     *   老实写「已停止跟踪」。
     *
     * 同进程内 [isRunning] 为真时不动（页面重建、旋转会重入）。
     */
    suspend fun resumeInterrupted(context: Context) = withContext(Dispatchers.IO) {
        if (isRunning()) return@withContext
        val pending = BuildStore.list(context).filter { !it.isTerminal }
        if (pending.isEmpty()) return@withContext

        val cfg = ConfigStore(context).loadGithubConfig()
        // list 已按 createdAt 降序；同一时刻只可能有一次构建，取最新那条续
        val resumable = if (cfg != null) pending.firstOrNull { it.runId != null } else null

        pending.filter { it.id != resumable?.id }.forEach { stale ->
            markCancelled(context, stale, "应用重启，已停止跟踪（CI 可能仍在运行，可在 GitHub 查看 run）")
            AgentLogger.w(TAG, "清理中断的构建任务 ${stale.id.take(8)}（阶段 ${stale.stage}）")
        }

        if (resumable == null || cfg == null) return@withContext

        AgentLogger.d(TAG, "接续跟踪 run #${resumable.runNumber}（id ${resumable.runId}）")
        val job = scope.launch { resumeTrack(context, resumable, cfg) }
        running = job
        job.invokeOnCompletion { if (running === job) running = null }
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

        fun advance(stage: BuildStage, error: String? = null) {
            task = task.copy(stage = stage, error = error)
            BuildStore.upsert(context, task)
            onStage(task)
        }

        val service = GitHubBuildService(cfg)

        try {
            // ① 推送工作区
            val sha = try {
                service.pushWorkspace(context).getOrThrow()
            } catch (c: CancellationException) {
                // 取消 ≠ 失败：不写 FAILED。留下「构建失败：推送失败：Canceled」
                // 会让下一次点开历史的人以为 CI 真的挂了。
                throw c
            } catch (e: Throwable) {
                AgentLogger.e(TAG, "推送失败：${e.message}")
                advance(BuildStage.FAILED, "推送失败：${e.message}")
                return@withContext task
            }
            task = task.copy(stage = BuildStage.QUEUED, commitSha = sha)
            BuildStore.upsert(context, task)
            onStage(task)

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
            var found: RunInfo? = null
            for (attempt in 1..FIND_RUN_ATTEMPTS) {
                found = try {
                    service.findRun(sha).getOrThrow()
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    // 网络抖动不判死刑，下一轮再找；旧写法 getOrNull() 连取消一起吞了，
                    // 于是「取消」在这里变成「继续重试 6 次」。
                    null
                }
                if (found != null) break
                delay(FIND_RUN_DELAY_MS)
            }
            val run = found
            if (run == null) {
                advance(BuildStage.FAILED, "触发后未找到对应 CI run（可稍后在构建历史重试）")
                return@withContext task
            }

            // ④⑤⑥ 轮询 → 下载 → 落档（与重启续跟踪共用同一条尾巴）
            trackRun(context, task, run, service, onStage)
        } catch (c: CancellationException) {
            // 页面关闭 / 用户点停止：既不写 FAILED（CI 可能仍在云端跑），
            // 也不留在 BUILDING（那样再进来会看到一个永远不动的「构建中」）。
            markCancelled(context, task)
            throw c
        } catch (e: Throwable) {
            // 未预期异常（解析出错、磁盘满、JSON 结构变了……）：必须有终态，
            // 否则页面挂「构建中」而进程里已经没有协程在跑了。
            markFailed(context, task, "构建异常中断：${e.message}")
            throw e
        }
    }

    /**
     * 从「run 已拿到」开始跑完整条尾巴：轮询 → 下载 → 落档。
     *
     * 抽出来是为了让「首次构建」和「重启续跟踪」走同一段代码——两份实现必然会
     * 在某次改动里分叉（比如只有一边处理了 release 回退），而这条链路一跑就是十几分钟，
     * 分叉后很难在真机上验出来。
     */
    private suspend fun trackRun(
        context: Context,
        task0: BuildTask,
        first: RunInfo,
        service: GitHubBuildService,
        onStage: (BuildTask) -> Unit
    ): BuildTask {
        var task = task0.copy(
            stage = BuildStage.BUILDING,
            runId = first.id,
            runNumber = first.number,
            downloadProgress = null,
            error = null
        )

        fun publish() {
            BuildStore.upsert(context, task)
            onStage(task)
        }

        fun fail(message: String): BuildTask {
            task = task.copy(stage = BuildStage.FAILED, error = message)
            publish()
            return task
        }

        publish()

        // 超时预算按「任务创建时间」算，不是「本次进入函数的时间」：
        // 否则被杀一次进程就白送 40 分钟，重启三次能拖两小时。
        // 墙钟只取这一次（用户改系统时间会偏），之后用单调时钟守剩余量。
        val remaining = (task.createdAt + RUN_TIMEOUT_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        val startedAt = Monotonic.nowMs()

        // ④ 轮询 run 至终态
        var last = first
        while (!last.isCompleted) {
            if (Monotonic.nowMs() - startedAt > remaining) {
                return fail("CI 超时（40 分钟未完成），请在 GitHub 查看 run #${first.number}")
            }
            // 轮询失败不判死刑：网络抖动记一笔、等下一轮（continue 在循环体内，不在 lambda 里）
            val polled = try {
                service.runStatus(first.id).getOrThrow()
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
            return fail("CI 失败（${last.conclusion}），run #${first.number}")
        }

        // ⑤ 下载 APK：release/latest 优先，artifact 回退
        // downloadProgress 必须清零：续跟踪接上的任务可能带着上次的 0.6，
        // 那样进度条会从半路开始，看着像下了一半其实刚要开始。
        task = task.copy(stage = BuildStage.DOWNLOADING, downloadProgress = 0f)
        publish()
        val apkFile = File(BuildStore.apkDir(context), "polaris-run-${first.number}.apk")
        val downloaded = try {
            val viaRelease = try {
                service.downloadLatestApk(
                    minUpdatedAt = task.createdAt,
                    into = apkFile,
                    onProgress = { p ->
                        if (task.downloadProgress != p) {
                            task = task.copy(downloadProgress = p)
                            publish()
                        }
                    }
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                AgentLogger.e(TAG, "release 下载回退：${e.message}")
                service.downloadArtifact(first.id, apkFile)
            }
            viaRelease.getOrThrow()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            AgentLogger.e(TAG, "APK 下载失败：${e.message}")
            return fail("APK 下载失败：${e.message}")
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
        AgentLogger.d(
            TAG,
            "构建 #${first.number} 成功：${downloaded.name}（${downloaded.length()}B, sha256 ${digest.take(12)}…）"
        )
        return done
    }

    /**
     * 进程重启后续跟踪一轮 run。
     *
     * 与 [startBuild] 的区别只在第一步：这里先问远端「那轮 run 现在什么状态」。
     * 查不到（断网 / PAT 过期 / 仓库改了名）就老实标停——留一个没人盯的「构建中」
     * 比标停更糟：用户会一直等一个永远不会动的进度条。
     */
    private suspend fun resumeTrack(context: Context, task0: BuildTask, cfg: GitHubConfig) {
        val runId = task0.runId ?: return
        val service = GitHubBuildService(cfg)

        // 第一步必须单独兜：查不到 ≠ 构建失败，是「本地丢了线索」。
        // 标停（而不是标失败）才能让用户知道该去 GitHub 看，而不是以为代码红了。
        val info = try {
            service.runStatus(runId).getOrThrow()
        } catch (c: CancellationException) {
            markCancelled(context, task0)
            throw c
        } catch (e: Throwable) {
            AgentLogger.w(TAG, "续查 run 失败（$runId）：${e.message}")
            markCancelled(
                context,
                task0,
                "已停止跟踪（无法查询 CI 状态：${e.message}；可到 GitHub 查看 run）"
            )
            return
        }

        AgentLogger.d(TAG, "续查 run #${info.number}：${info.status}/${info.conclusion}")
        try {
            trackRun(context, task0, info, service) { }
        } catch (c: CancellationException) {
            markCancelled(context, task0)
            throw c
        } catch (e: Throwable) {
            markFailed(context, task0, "构建异常中断：${e.message}")
            throw e
        }
    }

    /**
     * 落盘「已停止跟踪」。
     *
     * 从 [BuildStore] 取**最新**记录再改：调用点手里的 `task` 可能落后于磁盘
     * （trackRun 内部已经把 runId / progress 写进去了）。用旧快照整条 upsert
     * 会把「构建 #N」和下载进度一起抹掉——用户看到的就是一条没有 run 号的停止记录。
     */
    private fun markCancelled(
        context: Context,
        task: BuildTask,
        reason: String = STOPPED_REASON
    ): BuildTask {
        val latest = BuildStore.list(context).firstOrNull { it.id == task.id } ?: task
        if (latest.stage == BuildStage.CANCELLED) return latest
        val stopped = latest.copy(stage = BuildStage.CANCELLED, error = reason)
        BuildStore.upsert(context, stopped)
        return stopped
    }

    /**
     * 落盘「失败」。同 [markCancelled]：以磁盘上的最新记录为底，不要把 `runId` / 进度写丢。
     *
     * 只给「未预期异常」用——可预期的失败在 [trackRun] 里已经通过局部函数 `fail()` 精确落档
     * （带 HTTP 状态码之类的原因）。这里的兜底是为了保证**任何**退出路径都留下终态，
     * 否则页面会一直停在「构建中」，而进程里其实已经没有任何协程在跑。
     */
    private fun markFailed(context: Context, task: BuildTask, reason: String): BuildTask {
        val latest = BuildStore.list(context).firstOrNull { it.id == task.id } ?: task
        if (latest.isTerminal) return latest
        val failed = latest.copy(stage = BuildStage.FAILED, error = reason)
        BuildStore.upsert(context, failed)
        return failed
    }

    private fun newTask(stage: BuildStage, error: String? = null) = BuildTask(
        id = UUID.randomUUID().toString(),
        stage = stage,
        error = error,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}
