package com.beichen.polaris.pet

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
import androidx.core.app.NotificationCompat
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面助手的前台服务：一个悬浮窗 + 一条低优先级通知。
 *
 * ## 为什么必须是前台服务
 *
 * 普通 Service 在 App 退到后台几分钟后就会被系统回收，悬浮窗跟着消失——用户看到的
 * 是「桌宠自己没了」。前台服务把进程优先级提到前台，是 Android 上唯一合法的长期
 * 存活手段（与 [com.beichen.polaris.core.agent.AgentRunService] 同一套理由）。
 *
 * 代价是通知栏必须常驻一条。渠道用 `IMPORTANCE_MIN`：不响、不亮、不占锁屏，
 * 只在用户下拉通知栏时能看到——这是「我确实在运行」的凭据，不是打扰。
 *
 * ## 与主应用的关系
 *
 * 进程是同一个（没有 `android:process`），所以：
 * - [com.beichen.polaris.audio.AudioGraph] 的 TTS 与麦克风仲裁器是**同一个实例**，
 *   主界面在念的时候桌宠不会跟着念；
 * - 但 Agent 是两条独立通道（[PetAgentSession] vs `ChatViewModel`），历史不互通。
 *
 * ## 生命周期
 *
 * ```
 * PetState.start(ctx) → startForegroundService → onCreate(建窗) → onStartCommand(START_STICKY)
 * PetState.stop(ctx)  → stopService            → onDestroy(拆窗)
 * ```
 *
 * `START_STICKY` 让系统在内存吃紧时重建服务；重建后 `onCreate` 会重新读
 * [PetState] 的开关，开关已关就直接 `stopSelf`——不会出现「用户关了、它又回来了」。
 */
class PetOverlayService : Service() {

    private var overlay: PetOverlayHost? = null
    private var session: PetAgentSession? = null

    override fun onCreate() {
        super.onCreate()
        // 先把自己提到前台：Android 要求 startForegroundService 之后 5 秒内
        // 必须调用 startForeground，否则直接 ANR/崩溃。
        startAsForeground()

        PetState.sync(this)
        if (!PetState.enabled.value) {
            // START_STICKY 重建时的正常路径：用户早就关了，只是系统又把服务拉起来了
            AgentLogger.i(TAG, "开关已关闭，服务自行退出")
            stopSelf()
            return
        }
        if (!PetState.canDrawOverlays(this)) {
            // 权限被系统回收（部分 ROM 在「一键清理」时会收回 AppOps）
            AgentLogger.w(TAG, "悬浮窗权限已失效，桌面助手不启动")
            PetState.markRunning(false)
            stopSelf()
            return
        }

        val s = PetAgentSession(this)
        session = s
        val host = PetOverlayHost(
            context = this,
            ask = { question -> withContext(Dispatchers.IO) { s.ask(question) } },
            onReply = { reply ->
                // 错误文案不朗读：用户已经在看了，念一遍「网络不太好」只是噪音
                if (!reply.failed && !reply.silent) overlay?.speak(reply.text)
            },
            onClose = { PetState.stop(this) },
            onOpenApp = { openMainApp() }
        )
        overlay = host
        host.show()
        PetState.markRunning(true)
        AgentLogger.i(TAG, "桌面助手已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlay == null) {
            // onCreate 已经判定不该存在（开关关 / 没权限 / 加窗失败）
            stopSelf()
            return START_NOT_STICKY
        }
        // 只有显式带上 ACTION_LISTEN 才算「叫它听一句」：START_STICKY 被系统重建时
        // intent 为 null，那时绝不能顺手开始录音——用户没叫它。
        if (intent?.action == ACTION_LISTEN) {
            overlay?.beginVoice()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        session = null
        PetState.markRunning(false)
        stopForegroundSafely()
        AgentLogger.i(TAG, "桌面助手已停止")
        super.onDestroy()
    }

    private fun openMainApp() {
        runCatching { startActivity(mainAppIntent()) }
            .onFailure { AgentLogger.w(TAG, "打开主应用失败：${it.message}") }
    }

    /**
     * 回到主应用的 Intent。
     *
     * 不用 `core.lifecycle.launchAppIntentForNotification`：它是 `internal`，
     * core 模块内部（`AgentRunService`）能用，跨模块调会编译失败。这里只需要
     * 「launcher intent + NEW_TASK」，两行就够，不值得为它去放开 core 的可见性。
     */
    private fun mainAppIntent(): Intent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification = buildNotification()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (ok.isFailure) {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 服务照样继续跑（只是可能被回收），不该为此崩掉用户的桌面。
            AgentLogger.w(TAG, "前台服务启动失败：${ok.exceptionOrNull()?.message}")
        }
    }

    private fun stopForegroundSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            mainAppIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("北辰")
            .setContentText("桌面助手正在运行")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                "说一句",
                listenPendingIntent()
            )
            .build()
    }

    /**
     * 通知上的「说一句」。
     *
     * 走 `getService` 而不是 `getBroadcast` + 静态接收器：服务本来就在跑（通知只在
     * 运行时存在），多一个接收器只是多一处要维护的清单项。
     *
     * `requestCode` 必须与 contentIntent 的 0 不同，否则两个 PendingIntent 会
     * 撞成同一个（`Intent.filterEquals` 不同的只有 action，但仍按 requestCode 区分，
     * 显式给不同值最稳妥）。
     */
    private fun listenPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQ_LISTEN,
        Intent(this, PetOverlayService::class.java).setAction(ACTION_LISTEN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "桌面助手",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "桌面助手悬浮窗运行时的常驻通知（最低优先级，不打扰）"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val TAG = "PetService"
        private const val CHANNEL_ID = "agentkit_pet_overlay"
        private const val NOTIFICATION_ID = 0x5045 // 'PE'

        /** 通知按钮专用 requestCode（与 contentIntent 的 0 区分开）。 */
        private const val REQ_LISTEN = 1

        /**
         * 「请开始听」。
         *
         * 刻意做成服务自己的 action 而不是广播：这条路径要求「窗已经在、麦克风还能用」，
         * 而这两件事只有服务自己知道。广播出去还得回服务里判断一遍，白绕一层。
         */
        private const val ACTION_LISTEN = "com.beichen.polaris.pet.action.LISTEN"

        /**
         * 磁贴 / 通知共用的入口：确保桌宠在运行，然后让它听一句。
         *
         * @return true = 已发出启动或聆听请求（服务是否真的起来看 [PetState.running]）；
         *         false = 没有悬浮窗权限，什么都没做。
         *
         * **不要在这里判断「服务是否已在跑」**：`start` 本身是幂等的，
         * 而多一条 `if (running)` 分支就多一处会与真实状态不同步的判断。
         */
        fun listen(context: Context): Boolean {
            val app = context.applicationContext
            PetState.sync(app)
            if (!PetState.canDrawOverlays(app)) {
                AgentLogger.w(TAG, "没有悬浮窗权限，无法开始聆听")
                return false
            }
            return PetState.start(app, ACTION_LISTEN)
        }
    }
}
