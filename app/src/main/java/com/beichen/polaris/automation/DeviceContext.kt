package com.beichen.polaris.automation

/**
 * 设备上下文快照（值对象）。
 *
 * ## 为什么单独一个文件
 *
 * 它原本长在 `DeviceContextStore.kt` 末尾，而那个文件 import 了 `android.content.Context`，
 * 整个文件在 JVM 上编不过；于是 [toPrompt]——真正决定「什么内容会被拼进模型请求」的那个
 * 函数——只能靠静态 grep 断言，没有任何行为验证。审计 F11 把它搬出来：
 * 本文件与 [NotificationSummary] / [NotificationDigest] 一样零 Android 依赖，
 * harness 可以直接构造快照、读出真实提示文本、逐字断言里面没有正文。
 */
data class DeviceContext(
    val foregroundPackage: String,
    val foregroundLabel: String,
    /** 最近通知的元信息（来源 + 时间），不含正文。见 [NotificationDigest]。 */
    val recentNotifications: List<NotificationSummary>,
    val updatedAt: Long
) {
    /** 尚无任何感知数据时返回 true，表示上下文为空、无需注入。 */
    val isEmpty: Boolean
        get() = foregroundPackage.isBlank() && recentNotifications.isEmpty()

    /**
     * 生成 "[当前设备上下文]" 段落；为空则返回空串，调用方据此决定是否注入。
     *
     * 通知部分只有「应用 ×条数（多久前）」，正文一律不在这里——见 [NotificationDigest]
     * 与 [NotificationSummary] 上的说明。需要正文的调用方得走 `device_notifications`。
     */
    fun toPrompt(now: Long = updatedAt): String {
        if (isEmpty) return ""
        val sb = StringBuilder("[当前设备上下文]\n")
        if (foregroundPackage.isNotBlank()) {
            if (foregroundLabel.isNotBlank() && foregroundLabel != foregroundPackage) {
                sb.append("- 前台应用：$foregroundLabel ($foregroundPackage)\n")
            } else {
                sb.append("- 前台应用：$foregroundPackage\n")
            }
        }
        if (recentNotifications.isNotEmpty()) {
            sb.append(NotificationDigest.promptBlock(recentNotifications, now))
        }
        return sb.toString().trimEnd()
    }
}
