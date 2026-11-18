package com.beichen.polaris.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.beichen.polaris.core.mcp.server.McpTier

/**
 * 常驻前台服务：MCP 监听必须活着，外部客户端才能随时连。
 *
 * ## 为什么必须前台
 *
 * 一个只绑 loopback 的 Socket 在后台会被 Doze 打断：App 进 cached 状态后，
 * 进程随时可回收，`ServerSocket` 跟着消失。客户端看到的是「连接被拒」，
 * 而用户以为自己开着。前台服务是唯一能让「我说开就一直在」成立的机制。
 *
 * ## 为什么用 startService 而不是 startForegroundService
 *
 * 入口只有一处：用户在设置页点「启动」，此时 App 必在前台。用
 * `startForegroundService` 会引入 5 秒 startForeground 窗口——主线程忙（Compose 首帧、
 * 大量 IO）时就在 `ActivityThread` 里炸 `RemoteServiceException`，`runCatching` 抓不住。
 * 桌宠已经踩过一次（HANDOFF §3.9），这里不重复。
 */
class McpHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须先建 channel 再 startForeground：Android 8+ 对不存在的 channel
        // 直接抛 IllegalArgumentException，服务起不来且不留痕。
        ensureChannel()
        // onCreate 里立刻进前台：即便走到这里的是 START_STICKY 重建路径，
        // 也不能出现「服务起来了但通知没挂上」的窗口。
        startForeground(NOTIFICATION_ID, buildNotification(null))
        pruneAudit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tier = McpTier.fromWire(intent?.getStringExtra(EXTRA_TIER))
        val result = McpHostRuntime.start(applicationContext, tier)
        val nm = getSystemService(NotificationManager::class.java)
        result.fold(
            onSuccess = { port -> nm?.notify(NOTIFICATION_ID, buildNotification(port)) },
            onFailure = { e ->
                nm?.notify(NOTIFICATION_ID, buildNotification(null, e.message))
                stopSelf()
                return START_NOT_STICKY
            },
        )
        return START_STICKY
    }

    override fun onDestroy() {
        McpHostRuntime.stop()
        super.onDestroy()
    }

    private fun buildNotification(port: Int?, error: String? = null) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MCP 对外提供")
            .setContentText(
                error ?: if (port != null) "监听 127.0.0.1:$port · 外部 Agent 可连接" else "正在启动…"
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

    private fun pruneAudit() {
        runCatching { McpHostRuntime.audit(applicationContext).prune() }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MCP 对外提供",
                // LOW：常驻但不打扰。用 DEFAULT 会让「连接了外部工具」这件事
                // 在每次服务重建时响一声，用户会以为是故障。
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "把手机工具以 MCP 协议提供给外部 Agent 时的常驻通知"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "mcp_host"
        const val NOTIFICATION_ID = 0x4D43 // 'MC'
        const val EXTRA_TIER = "tier"

        /** 构建启动 Intent。调用方用 `startService`（App 必须在前台）。 */
        fun intent(context: android.content.Context, tier: McpTier): Intent =
            Intent(context, McpHostService::class.java).putExtra(EXTRA_TIER, tier.wire)

        /** 供 UI 判断是否需要起服务。 */
        fun isRunning(): Boolean = McpHostRuntime.status.value.running
    }
}
