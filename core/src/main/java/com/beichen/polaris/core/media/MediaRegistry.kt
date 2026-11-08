package com.beichen.polaris.core.media

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.media.providers.DashScopeMediaProvider
import com.beichen.polaris.core.media.providers.MiniMaxMediaProvider
import com.beichen.polaris.core.media.providers.OpenAIMediaProvider
import com.beichen.polaris.core.media.providers.VolcMediaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体 Provider 注册表 + 任务编排（对标 LLM 侧 ProviderManager）。
 *
 * 职责：
 *  1. 按 ConfigStore 的媒体 Key 实例化四家 Provider（零 Key 的也注册，
 *     提交时由 Provider 自己抛「未配置 Key」——UI 提前拦截，双保险）；
 *  2. submit：建任务 → 调 Provider.submit → 落盘；同步 Provider 提交即成功，
 *     立刻下载落盘（本地路径才是资产真理）；
 *  3. pollTask：调 Provider.poll → 状态推进 → 成功即下载落盘；
 *  4. pollDue：扫一遍未终态任务批量推进（前台轮询器每 4s 调一次）。
 */
object MediaRegistry {

    private const val TAG = "MediaRegistry"

    /** 按当前 Key 配置实例化全部 Provider。 */
    fun providers(context: Context): List<MediaProvider> {
        val store = ConfigStore(context)
        return listOf(
            DashScopeMediaProvider(store.loadMediaKey("dashscope")),
            OpenAIMediaProvider(store.loadMediaKey("openai"), store.load().baseUrl),
            VolcMediaProvider(store.loadMediaKey("volc")),
            MiniMaxMediaProvider(store.loadMediaKey("minimax"))
        )
    }

    /** 支持指定 kind 的 Provider（表单过滤用）。 */
    fun providersFor(context: Context, kind: MediaKind): List<MediaProvider> =
        providers(context).filter { kind in it.kinds }

    fun provider(context: Context, id: String): MediaProvider? =
        providers(context).firstOrNull { it.id == id }

    /**
     * 提交任务。失败时以 FAILED 任务落盘并回传（UI 展示原因，用户可改参数重提）——
     * 提交失败不是「什么都没发生」，任务列表里要看得见。
     */
    suspend fun submit(
        context: Context,
        providerId: String,
        request: MediaRequest
    ): MediaTask = withContext(Dispatchers.IO) {
        val provider = provider(context, providerId)
            ?: return@withContext fail(context, providerId, request, "未知 Provider：$providerId")
        val task = MediaTask(
            id = MediaTaskStore.newId(),
            providerId = providerId,
            request = request,
            status = TaskStatus.SUBMITTED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        MediaTaskStore.upsert(context, task)
        val handle = runCatching { provider.submit(request) }.getOrElse { e ->
            AgentLogger.e(TAG, "submit 失败（$providerId）：${e.message}")
            return@withContext fail(context, providerId, request, e.message ?: "提交失败")
        }
        return@withContext if (handle.state is MediaTaskState.Succeeded) {
            // 同步 Provider：提交即成功，立刻下载（state 已智能转换为 Succeeded）
            finish(context, task.copy(providerTaskId = handle.providerTaskId), handle.state.urls)
        } else {
            val running = task.copy(
                providerTaskId = handle.providerTaskId,
                status = TaskStatus.RUNNING
            )
            MediaTaskStore.upsert(context, running)
            running
        }
    }

    /** 轮询单个任务并推进状态（成功即下载落盘）。 */
    suspend fun pollTask(context: Context, task: MediaTask): MediaTask = withContext(Dispatchers.IO) {
        if (!task.isActive) return@withContext task
        val provider = provider(context, task.providerId)
            ?: return@withContext task.copy(
                status = TaskStatus.FAILED,
                error = "Provider ${task.providerId} 已不可用",
                updatedAt = System.currentTimeMillis()
            ).also { MediaTaskStore.upsert(context, it) }
        val taskId = task.providerTaskId
            ?: return@withContext task.copy(
                status = TaskStatus.FAILED,
                error = "缺少云端任务 id，无法轮询",
                updatedAt = System.currentTimeMillis()
            ).also { MediaTaskStore.upsert(context, it) }
        runCatching { provider.poll(taskId) }
            .onFailure { e ->
                // 轮询失败（网络抖动）不立刻判死刑：保持 RUNNING，下次再试；
                // 只有 Provider 明确返回 Failed 才终态化。
                AgentLogger.e(TAG, "poll 失败（${task.id}）：${e.message}")
            }
            .onSuccess { state ->
                when (state) {
                    is MediaTaskState.Running -> {
                        MediaTaskStore.upsert(
                            context,
                            task.copy(
                                status = TaskStatus.RUNNING,
                                progress = state.progress,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    is MediaTaskState.Succeeded ->
                        finish(context, task, state.urls)

                    is MediaTaskState.Failed -> {
                        MediaTaskStore.upsert(
                            context,
                            task.copy(
                                status = TaskStatus.FAILED,
                                error = state.message,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        return@withContext MediaTaskStore.list(context).firstOrNull { it.id == task.id } ?: task
    }

    /**
     * 推进所有未终态任务（轮询入口）。
     *
     * 轮询节奏由调用方掌握：MediaScreen 在页面生命周期内每 4s 调一次本方法
     * 并顺带刷新列表——杀进程就停、重进 App 按 tasks.json 续查
     * （调研 06 号文档明示的取舍：不上 WorkManager 的复杂约束）。
     */
    suspend fun pollDue(context: Context) = withContext(Dispatchers.IO) {
        MediaTaskStore.list(context).filter { it.isActive }.forEach { pollTask(context, it) }
    }

    private suspend fun fail(
        context: Context,
        providerId: String,
        request: MediaRequest,
        message: String
    ): MediaTask {
        val task = MediaTask(
            id = MediaTaskStore.newId(),
            providerId = providerId,
            request = request,
            status = TaskStatus.FAILED,
            error = message,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        MediaTaskStore.upsert(context, task)
        return task
    }

    private suspend fun finish(context: Context, task: MediaTask, urls: List<String>): MediaTask {
        val paths = MediaDownloader.download(context, task.id, urls, task.request.kind)
        val done = task.copy(
            status = if (paths.isNotEmpty()) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
            progress = 1f,
            resultPaths = paths,
            resultUrls = urls,
            error = if (paths.isNotEmpty()) null else "下载结果失败：${urls.joinToString()}",
            updatedAt = System.currentTimeMillis()
        )
        MediaTaskStore.upsert(context, done)
        return done
    }
}
