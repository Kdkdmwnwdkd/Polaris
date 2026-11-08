package com.beichen.polaris.core.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.beichen.polaris.core.lifecycle.launchAppIntentForNotification
import com.beichen.polaris.core.AgentLogger

/**
 * 后台存活自检。
 *
 * ## 为什么不用 adb
 * 判断「锁屏后任务还跑不跑」本该 `dumpsys` + 比对 pid，但大量用户手上只有手机。
 * 这个服务把判据搬到通知栏：**每 5 秒让数字 +5**。
 *
 * 用户锁屏 2 分钟，回来瞄一眼通知：
 * - 数字还在涨 → 进程活着，CPU 没睡，任务能继续
 * - 数字卡住 → 进程活着但被冻结（CPU 睡了 / 被后台限制）
 * - 通知消失 → 进程被杀（厂商省电最典型的表现）
 *
 * 三种结果对应三种修法，比一句「好像断了」有用得多。
 *
 * ## 为什么用前台服务而不是普通协程
 * 普通协程跟着进程走，被杀就没了，连「通知消失」这个信号都留不下。
 * 前台服务能留下常驻通知，正好当指示灯。
 */
class SurvivalCheckService : Service() {

    companion object {
        private const val TAG = "SurvivalCheck"
        private const val CHANNEL_ID = "survival_check"
        private const val NOTIFICATION_ID = 4102
        private const val INTERVAL_MS = 5_000L
        private const val TOTAL_SECONDS = 300

        /** 启动自检（5 分钟）。用户在前台点击时调用，不受后台启动限制。 */
        fun start(context: Context) {
            val intent = Intent(context, SurvivalCheckService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { AgentLogger.w(TAG, "启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SurvivalCheckService::class.java)) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0

    private val tick = object : Runnable {
        override fun run() {
            elapsedSeconds += (INTERVAL_MS / 1000).toInt()
            if (elapsedSeconds >= TOTAL_SECONDS) {
                finish()
                return
            }
            NotificationManagerCompat.from(this@SurvivalCheckService)
                .notify(NOTIFICATION_ID, buildNotification(elapsedSeconds, done = false))
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(0, done = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(0, done = false))
            }
        }.onFailure {
            AgentLogger.w(TAG, "前台服务启动失败：${it.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    /** 时间到：把通知改成「完成」并允许划掉，不让它一直挂着。 */
    private fun finish() {
        handler.removeCallbacks(tick)
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(elapsedSeconds, done = true))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台存活自检", NotificationManager.IMPORTANCE_LOW).apply {
                description = "锁屏后验证任务是否还在跑"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(seconds: Int, done: Boolean): Notification {
        val open = launchAppIntentForNotification(this)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (done) {
            "完成：$seconds 秒无中断。锁屏期间数字一直在涨，说明不会被冻结。"
        } else {
            "已 $seconds / $TOTAL_SECONDS 秒 · 锁屏后回来看这个数字有没有继续涨"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (done) "存活自检完成" else "存活自检进行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
