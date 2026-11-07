package com.beichen.polaris.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

/**
 * 功能五（P1-10）：「回答完成提醒」系统通知（净室实现）。
 *
 * 语义：**只在 App 退到后台时**发——用户还在聊天界面时发通知是噪音；
 * 离开页面等长回答（ Agent 跑工具多轮）回来后才有「好了」一声提示的价值。
 * 通知权限（Android 13+ POST_NOTIFICATIONS）由设置页开关处按需申请，
 * 被拒/被关时本类静默降级，绝不抛异常打断聊天主流程。
 *
 * 与 [com.beichen.polaris.tools.BuiltInTools] 的 agent 通知渠道**刻意分开**：
 * 那是 Agent 自主发的业务通知（用户可删除），这是系统完成事件（用户可控开关）。
 */
object CompletionNotifier {

    private const val CHANNEL_ID = "answer_complete"
    private const val NOTIFICATION_ID = 42001

    /**
     * 「App 是否在前台」探针（3.2）。
     *
     * 原来这里直接 import `AgentKitApplication` 读静态计数——连同三处 MainActivity，
     * 是 core 离不开 app 壳的最后几处硬依赖。改由 app 层在 `onCreate` 里装配
     * （见 `AgentKitApplication`），顺带让「前台判定」在测试里可替换。
     *
     * 默认 `false`（当作后台）：宁可多发一条提醒，也不要漏——漏了用户会以为任务没跑完。
     * 装配发生在 Application.onCreate，早于任何一次生成，不会真的误发。
     */
    @Volatile
    var foregroundProbe: () -> Boolean = { false }

    /**
     * 把探针退回「未装配」的保守默认（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 默认值是 `{ false }`（当作后台），这是安全侧：宁可多发一条提醒，也不漏。
     * 回到默认而不是留着上一轮装配的 lambda，是因为那些 lambda 往往闭包了
     * 一个已经销毁的 Activity/Application——测试里继续调用它会崩在别人看不见的地方。
     */
    fun reset() {
        foregroundProbe = { false }
    }

    /** 建渠道（幂等；API 26 以下无渠道概念，空实现）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "回答完成提醒",
                // DEFAULT：完成事件值得一声轻提示，但不弹 heads-up
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Agent 回答生成完毕时提醒（仅 App 在后台时发送）"
                enableVibration(true)
            }
        )
    }

    /**
     * 发一条「回答完成」通知。三重静默降级，任一不满足直接返回不抛：
     * 1. 系统通知总开关被关（areNotificationsEnabled）；
     * 2. App 在前台（用户正看着，不需要提醒）；
     * 3. 任何构建/发送异常（渠道被用户删等）。
     *
     * @param preview 回答预览（截断到 60 字内，通知正文不放全文）。
     */
    fun notifyAnswerComplete(context: Context, preview: String) {
        runCatching {
            if (foregroundProbe()) return
            val nm = context.getSystemService<NotificationManager>() ?: return
            if (!nm.areNotificationsEnabled()) return
            ensureChannel(context)
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Agent 回答完成")
                .setContentText(preview.take(60))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
