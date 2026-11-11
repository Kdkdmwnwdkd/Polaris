package com.beichen.polaris.automation

/**
 * 通知入库判据（审计 F18）：**哪些通知该被收下**。
 *
 * ## 为什么要单独一个判据
 *
 * 通知监听是系统级入口，收什么决定了两条下游的内容：`NotificationStore`（正文，
 * 走 `device_notifications` 审批）与 `DeviceContextStore`（只收来源+时间，自动注入）。
 * 这个判据原来不存在——收到什么都收，连**本应用自己发的**也收。
 *
 * 真机实测（模拟器 API 30，定时任务入口）：
 *
 * ```
 * // 全量读通知的返回里，四条有三条是 App 自己
 * {"package":"com.beichen.polaris","title":"Agent 正在执行","text":"notifyall"}
 * {"package":"com.beichen.polaris","title":"Agent 正在执行","text":"notify"}
 * {"package":"com.android.shell","title":"建行","text":"尾号1234账户支出500元"}
 * {"package":"com.beichen.polaris","title":"Agent 正在执行","text":"notify"}
 * ```
 *
 * 两个后果：
 *  1. **回环**：Agent 发通知 → 自己的监听器收下 → 被 `device_notifications` 读回 → 进模型
 *     上下文。不是外部泄漏，但它是纯噪声，还占用「最近通知」的名额（12 条），
 *     把真正的用户通知挤出去。
 *  2. **正文里是用户输入**：那条通知的正文是定时任务的 `prompt` 原文。它出现在锁屏上
 *     （Android 默认展示通知内容，除非用户手动开「隐藏敏感内容」），也在自己的库里
 *     存了一份。
 *
 * ## 两种修法，为什么选「不收」
 *
 * 与 F11 同一条原则：**出口过滤要求每个出口都写对，入口拒绝只需一处**。所以这里不是
 * 「收下来再在展示时过滤」，而是让它根本进不了库。
 *
 * 单独抽成零依赖纯函数，是为了让判据在 JVM harness 上可被逐条断言（`AgentNotificationListener`
 * 拖 Android 的 `NotificationListenerService`，整个文件编不动）。
 */
object NotificationIntake {

    /**
     * 这条通知要不要入库。
     *
     * @param packageName 通知的来源包名（`StatusBarNotification.packageName`）
     * @param selfPackage 本应用的包名（`context.packageName`）
     * @return true = 入库；false = 丢弃
     */
    fun shouldIngest(packageName: String, selfPackage: String): Boolean {
        // 脏数据：拿不到包名就没法判断来源，也没法按应用授权读取（F15 的范围靠它划）
        if (packageName.isBlank()) return false
        // 自己发的通知：回环 + 正文常含用户输入（见上面的真机样本）
        if (selfPackage.isBlank()) return true
        return packageName != selfPackage
    }
}
