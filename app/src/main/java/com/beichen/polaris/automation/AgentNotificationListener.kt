package com.beichen.polaris.automation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 通知监听服务（L3 感知层）：实时捕获系统通知，供 Agent “感知”手机正在发生的事。
 *
 * 设计（净室原创）：NotificationListenerService 由用户在系统设置中手动授权开启；
 * 收到的通知只做结构化落库（package / title / text / time），不读取通知以外的任何内容，
 * 也不拦截、不转发到外部，符合最小权限与隐私底线。
 *
 * ## 两条下游，两种待遇（审计 F11）
 *
 *  - [NotificationStore]：存全文，只由 `device_notifications` 工具读取，**那个工具要审批**；
 *  - [DeviceContextStore]：只收来源与时间，会被自动拼进每条模型请求，**不收正文**。
 *
 * 修前两条都收全文，于是「读通知要授权」形同虚设——自动注入那条路根本不用授权。
 *
 * 注：这是系统级服务而非普通权限，必须在
 *   “设置 → 通知 → 通知访问权限” 中手动授予本应用。
 */
data class NotificationRecord(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val key: String
)

/** 通知结构化落库：只缓存最近 N 条，供 device_notifications 工具读取。 */
object NotificationStore {
    private val queue = ConcurrentLinkedQueue<NotificationRecord>()
    private const val MAX = 60

    val all: List<NotificationRecord> get() = queue.toList()

    fun add(r: NotificationRecord) {
        queue.add(r)
        while (queue.size > MAX) queue.poll()
    }

    fun clear() = queue.clear()
}

class AgentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        // 忽略无正文的系统占位通知，避免噪声
        if (title.isBlank() && text.isBlank()) return
        NotificationStore.add(
            NotificationRecord(
                packageName = sbn.packageName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                key = sbn.key
            )
        )
        // L4 主动感知：把通知**来源**同步到设备上下文，供对话层自动注入。
        //
        // 审计 F11：修前这里传的是 "$label - $body"（正文全在里面），而设备上下文
        // 会在每条用户消息里自动拼进模型请求——等于「读通知」这件事从来没经过授权，
        // F9 加在 device_notifications 上的审批被这条自动通道整个绕开。
        // 现在只传来源与时间；正文留在上面那份 NotificationStore 里，由那个已审批的工具取。
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Throwable) { sbn.packageName }
        DeviceContextStore.addNotification(
            NotificationSummary(
                packageName = sbn.packageName,
                label = label,
                postTime = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
