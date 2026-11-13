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
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.beichen.polaris.core.lifecycle.launchAppIntentForNotification
import com.beichen.polaris.core.AgentLogger

/**
 * Agent 长任务前台服务（B 批）。
 *
 * ## 为什么需要
 * Agent 跑一个多步任务（连 LLM + 调工具）可能好几分钟。用户切后台、锁屏、
 * 或系统内存吃紧时，App 进程会被回收——任务直接死掉，用户回来什么也没有。
 *
 * 前台服务是 Android 官方给「用户可感知的持续工作」设计的保活手段：
 * 进程优先级被提到前台，**系统不会随手回收**，通知栏可见一条常驻通知。
 * 长任务想「不被系统随手回收」，只有这一条路。
 *
 * ## 边界（明确标注，不吹大）
 * - 前台服务**只能防「系统回收」，防不了「用户强行停止」或「设备断电重启」**。
 * - 进程真被杀时，任务**不会自动续跑**——Agent 回合跑在协程里，没有可重放的执行栈。
 *   本服务解决的是「别被杀」，被杀后的兜底由 [AgentRunStateStore] 负责：
 *   落盘的运行态会让下次启动知道「上次被中断了，可以继续」。
 * - Android 13+ 需要 POST_NOTIFICATIONS 权限；用户拒绝时服务仍能启动
 *   （前台服务本身不需要该权限，没有通知只是不可见），已 runCatching 兜底。
 *
 * ## 生命周期
 * ```
 * AgentRunService.start(context, title)  → startForeground + 发通知
 * AgentRunService.update(context, title, step) → 更新通知文案（进度变化时）
 * AgentRunService.stop(context)        → stopForeground + stopSelf
 * ```
 */
class AgentRunService : Service() {

    /**
     * CPU 唤醒锁。
     *
     * 前台服务只保证「进程有通知、不被随手回收」，**不保证 CPU 不睡**。
     * 锁屏后 CPU 进入 suspend，流式响应和容器命令会被挂起，用户看到的是「任务卡住不动」。
     *
     * 上限 35 分钟：`RealAgentLoop.turnBudgetMs` 是 30 分钟，原来写 15 分钟——
     * 后半个回合在锁屏状态下会被 CPU 休眠拖住，用户看到的是「任务突然变慢」
     * 而不是「被杀」，比直接失败更难判断。多给 5 分钟收尾余量。
     * 仍是有限时长：万一任务卡死，超时后系统自动回收，不会把电池耗干
     * （`setReferenceCounted(false)` 保证只释放一次）。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Polaris:run").apply {
                setReferenceCounted(false)
                acquire(35 * 60 * 1000L)
            }
        }.onFailure {
            // 拿不到锁不该让任务失败：最坏情况是锁屏后变慢，不是不能跑。
            AgentLogger.w(TAG, "WakeLock 获取失败：${it.message}")
        }.getOrNull()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
        val step = intent?.getIntExtra(EXTRA_STEP, 0) ?: 0
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(title, step),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(title, step))
            }
        }.onFailure {
            // Android 12+ 若后台启动前台服务受限会抛 ForegroundServiceStartNotAllowedException。
            // 这不该让 Agent 任务崩掉——保活是锦上添花，不是任务本身的必要条件。
            AgentLogger.w(TAG, "前台服务启动失败（任务继续跑，只是可能被系统回收）：${it.message}")
        }
        // START_STICKY：进程被杀后系统会重建服务，onStartCommand 再次收到 null intent，
        // 至少能把通知补回来，避免出现「有任务在跑但通知栏空着」的状态错位。
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    private fun buildNotification(title: String, step: Int): Notification {
        val intent = launchAppIntentForNotification(this)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (step > 0) "$title · 已执行 $step 步" else title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Agent 正在执行")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n点击返回 App 继续对话"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "AgentRunService"
        private const val CHANNEL_ID = "agentkit_agent_run"
        private const val NOTIFICATION_ID = 0x4150 // 'AP'
        private const val DEFAULT_TITLE = "长任务执行中"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STEP = "step"

        /** 保证通知渠道存在（Android 8+ 必须先建渠道才能发通知）。 */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent 长任务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Agent 执行多步任务时的常驻通知（低优先级，静默）"
                    setShowBadge(false)
                }
            )
        }

        /** 启动保活（幂等：重复调用只是更新通知）。 */
        fun start(context: Context, title: String = DEFAULT_TITLE, step: Int = 0) {
            ensureChannel(context)
            val intent = Intent(context, AgentRunService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STEP, step)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                AgentLogger.w(TAG, "启动前台服务失败（任务继续跑）：${it.message}")
            }
        }

        /** 更新进度文案。 */
        fun update(context: Context, title: String, step: Int) {
            ensureChannel(context)
            val intent = Intent(context, AgentRunService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STEP, step)
            }
            runCatching { context.startService(intent) }
                .onFailure { AgentLogger.w(TAG, "更新前台通知失败：${it.message}") }
        }

        /** 停止保活（任务结束 / 被拒绝时调用）。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AgentRunService::class.java))
            }
        }
    }
}
