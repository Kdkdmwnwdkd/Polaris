package com.beichen.polaris.workflow

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beichen.polaris.core.AgentLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 定时任务巡检 Worker（W-17）。
 *
 * 所有工作流共用一个 15 分钟周期任务（WorkManager 的最短周期），每次只做「找出到期项 → 发通知」，
 * 不在后台跑 Agent（原因见 [WorkflowNotifier]）。这样：
 *  - 不需要为每个工作流分别注册 WorkRequest（增删改时无需重新排程）；
 *  - 手机休眠/重启后由 WorkManager 自行恢复，语义稳定。
 */
class WorkflowWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = WorkflowStore(File(applicationContext.filesDir, DIR))
        val now = System.currentTimeMillis()

        // 一次性触发（用户在界面点「立即运行」）优先，只处理指定那一条
        val explicitId = inputData.getString(KEY_WORKFLOW_ID)
        if (explicitId != null) {
            val wf = store.get(explicitId) ?: return Result.success()
            if (!wf.enabled) return Result.success()
            WorkflowNotifier.notify(applicationContext, wf)
            store.markRun(wf.id, now, "已触发（等待前台执行）")
            return Result.success()
        }

        val due = store.list().filter { isWorkflowDue(it, it.lastRunAt, now) }
        if (due.isEmpty()) return Result.success()
        due.forEach { wf ->
            WorkflowNotifier.notify(applicationContext, wf)
            // 先标记再执行：避免周期任务在用户尚未处理时反复提醒
            store.markRun(wf.id, now, "已提醒（点开通知即执行）")
        }
        AgentLogger.i(TAG, "本轮提醒 ${due.size} 个定时任务：${due.joinToString { it.name }}")
        return Result.success()
    }

    companion object {
        private const val TAG = "WorkflowWorker"
        const val DIR = "workflow"
        const val KEY_WORKFLOW_ID = "workflow_id"
    }
}

/**
 * 排程入口（W-17）。
 *
 * 使用方法：
 *  - App 启动时 [ensureScheduled]（幂等，重复调用不会产生多份周期任务）；
 *  - 用户点「立即运行」时走 `WorkflowFacade.runWorkflowNow`（前台对话，不经本调度器）。
 */
object WorkflowScheduler {

    private const val UNIQUE_NAME = "agentkit_workflow_tick"

    /** 注册（或更新）周期巡检；幂等。 */
    fun ensureScheduled(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<WorkflowWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            AgentLogger.i("WorkflowScheduler", "周期巡检已注册（15 分钟）")
        }.onFailure {
            AgentLogger.w("WorkflowScheduler", "注册失败：${it.message}")
        }
    }
}
