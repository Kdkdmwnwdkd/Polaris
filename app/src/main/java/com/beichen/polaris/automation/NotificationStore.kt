package com.beichen.polaris.automation

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 一条捕获到的通知全文。
 *
 * 只由 `device_notifications` 工具读取，而那个工具要审批（F9），
 * 并且从 F15 起还要说清读哪几个应用。自动注入的 `DeviceContextStore` 收的是
 * [NotificationSummary]（没有正文字段），两条路刻意不同构。
 */
data class NotificationRecord(
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val key: String
)

/**
 * 通知结构化落库：只缓存最近 N 条，供 `device_notifications` 工具读取。
 *
 * 单独成文件（原先寄生在 [AgentNotificationListener] 里）有两个原因：
 *  - 本对象只依赖 `ConcurrentLinkedQueue`，**零 Android 依赖**，可被 JVM harness 直接编译；
 *  - 监听服务拖 `NotificationListenerService`，跟它编在一起，本对象就永远测不了——
 *    而「按应用过滤」恰恰是这段代码里最容易写成假修的一处（弹窗写了微信，读的还是全量）。
 */
object NotificationStore {
    private val queue = ConcurrentLinkedQueue<NotificationRecord>()
    private const val MAX = 60

    /** 全部缓存（旧 → 新）。调用方自行倒序。 */
    val all: List<NotificationRecord> get() = queue.toList()

    /** 条数。给工具消息用，避免为了数个数把整份列表搬一遍。 */
    val size: Int get() = queue.size

    /**
     * 按应用过滤（审计 F15）。**这是唯一允许的读取入口**——工具不再直接拿 [all]。
     *
     * 返回顺序与 [all] 一致（旧 → 新），由调用方决定展示顺序。
     *
     * [packages] 来自 [com.beichen.polaris.core.agent.NotificationScope.parse]，已 trim、
     * 转小写、去重。这里再折一次大小写：两侧都折，才不会出现「键里有、过滤漏」
     * 这种只在大小写上分叉的假修。
     *
     * 空集合返回空列表而不是全量：空集合在 [com.beichen.polaris.core.agent.NotificationScope]
     * 里已经被归一成「全部」，能走到这里说明调用方自己拼了个空集合——
     * 那时**读不到**比**读到全部**安全。
     */
    fun byPackages(packages: Set<String>): List<NotificationRecord> {
        if (packages.isEmpty()) return emptyList()
        return queue.filter { it.packageName.lowercase() in packages }
    }

    fun add(r: NotificationRecord) {
        queue.add(r)
        while (queue.size > MAX) queue.poll()
    }

    fun clear() = queue.clear()
}
