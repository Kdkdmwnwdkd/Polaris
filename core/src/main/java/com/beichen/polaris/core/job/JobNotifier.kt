package com.beichen.polaris.core.job

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.beichen.polaris.core.lifecycle.launchAppIntentForNotification

/**
 * 后台任务完成通知（W-33）。
 *
 * 与定时任务通知（`WorkflowNotifier`）分开一个 channel：用户可能想关掉其中一类
 * （例如"长命令跑完要提醒"但"定时任务别响"），系统设置里能分别控制。
 */
object JobNotifier {

    const val CHANNEL_ID = "agentkit_jobs"

    fun notify(context: Context, job: JobInfo) {
        ensureChannel(context)
        val title = if (job.status == JobStatus.DONE) {
            "命令完成"
        } else {
            "命令失败（exit ${job.exitCode ?: "?"}）"
        }
        val intent = launchAppIntentForNotification(context)
        val pending = PendingIntent.getActivity(
            context,
            job.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val duration = JobLogic.durationText(job.finishedAt - job.startedAt)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("${job.command.take(60)}（$duration）")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${job.command.take(300)}\n\n耗时 $duration，输出用 shell_jobs 查看")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(job.id.hashCode(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台命令",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "长命令跑完后提醒（可在系统设置里单独关闭）"
            }
        )
    }
}
