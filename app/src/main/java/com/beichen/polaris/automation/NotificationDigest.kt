package com.beichen.polaris.automation

/**
 * 一条通知的**元信息**——来源应用与到达时间，**不含标题、不含正文**。
 *
 * ## 为什么这里没有 content 字段
 *
 * 审计 F11。修前 `DeviceContextStore.addNotification("$label - $body")` 把
 * 「应用名 - 标题：正文」整串塞进设备上下文，而 [DeviceContext.toPrompt] 的结果
 * 会在 `ChatViewModel` 里**每条用户消息**都自动拼进请求。也就是说：只要用户在系统里
 * 开了通知访问权限，此后收到的每一条通知正文（验证码、私聊、银行推送）都会自动、
 * 持续、不问一声地发给模型——而审计 F9 刚刚给 `device_notifications` 加的审批，
 * 拦的是「模型主动去读」，拦不到这条。
 *
 * 修法有两种，这里选了后一种：
 *
 *  1. 照样存全文，在出口（`toPrompt` / UI）过滤掉正文。**每个出口都得写对**，
 *     将来多一个出口（日志、导出、调试面板）就多一个漏点。
 *  2. 根本不存正文。**只有一处需要写对**：`AgentNotificationListener` 不构造它。
 *
 * 安全上「不收集」永远强于「收集后不输出」——前者是结构上的，后者是纪律上的。
 * 需要正文时走 `device_notifications`，那个工具已经过审批。
 *
 * @param packageName 用于聚合（同一个 App 的多条通知合成一条）
 * @param label 展示用应用名；解析失败时调用方传包名
 * @param postTime 系统投递时间（毫秒）
 */
data class NotificationSummary(
    val packageName: String,
    val label: String,
    val postTime: Long
)

/**
 * 通知元信息的聚合与措辞（**纯函数**，无 Android 依赖，JVM harness 可直接跑）。
 *
 * 单独成文件而不是塞进 `DeviceContextStore.kt`：后者 import 了
 * `android.content.Context`，整个文件在 JVM 上编不过，纯函数跟着一起没得测。
 */
object NotificationDigest {

    /** 提示里最多列几个应用；超出的折成「等 N 个应用」，避免上下文被通知占满。 */
    const val MAX_APPS = 3

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * 把通知列表压成「应用 ×条数（多久前）」的数字摘要。
     *
     * 聚合而不是逐条列出，是因为逐条列出一屏「微信 - ……」既占上下文又没信息量；
     * 「微信 ×3、支付宝 ×1」已经足够让模型判断该不该进一步去看。
     *
     * 顺序：按**最近一条**的时间倒序——用户关心的是「刚才谁在响」。
     *
     * @param now 当前时间（毫秒）。显式传入而非内部取 `System.currentTimeMillis()`，
     *   否则断言只能写「含 N 分钟前」这种含糊判据。
     */
    fun lines(items: List<NotificationSummary>, now: Long): List<String> {
        if (items.isEmpty()) return emptyList()
        // 按包名聚合；LinkedHashMap 保留首次出现顺序，随后整体按最新时间重排，
        // 所以这里的顺序只是聚合的副产品，不承担排序语义。
        val grouped = LinkedHashMap<String, MutableList<NotificationSummary>>()
        for (it in items) {
            grouped.getOrPut(it.packageName) { mutableListOf() }.add(it)
        }
        val ordered = grouped.values
            .map { group -> group to group.maxOf { it.postTime } }
            .sortedByDescending { it.second }

        val shown = ordered.take(MAX_APPS).map { (group, latest) ->
            val name = displayName(group.first())
            val count = group.size
            val suffix = if (count > 1) " ×$count" else ""
            "$name$suffix（${ago(latest, now)}）"
        }.toMutableList()
        if (ordered.size > MAX_APPS) {
            shown.add("等 ${ordered.size} 个应用")
        }
        return shown
    }

    /** 「多久之前」的人类措辞。未来时间（时钟回拨）按「刚刚」处理，不显示负数。 */
    fun ago(postTime: Long, now: Long): String {
        val delta = now - postTime
        return when {
            delta < MINUTE -> "刚刚"
            delta < HOUR -> "${delta / MINUTE} 分钟前"
            delta < DAY -> "${delta / HOUR} 小时前"
            else -> "${delta / DAY} 天前"
        }
    }

    /**
     * 拼成提示里的那一行（含换行与缩进），供 `DeviceContext.toPrompt` 直接追加。
     *
     * 第二句「这里只有来源与条数，没有正文」不是客套：模型看到「微信 ×2」很容易
     * 顺势编出「它说了什么」，而它其实什么都没看到。把边界写在数据旁边，
     * 比写在系统提示的某个角落更有效——这是这条数据自己的说明。
     */
    fun promptBlock(items: List<NotificationSummary>, now: Long): String {
        val body = lines(items, now)
        if (body.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("- 最近通知：")
        sb.append(body.joinToString("、"))
        sb.append('\n')
        sb.append("  （这里只有来源与条数，没有正文。需要内容请调用 device_notifications，")
        sb.append("会向用户申请授权。）")
        return sb.toString()
    }

    /** 包名 → 展示名；label 与包名都空时给个占位，避免提示里出现「最近通知：（刚刚）」。 */
    private fun displayName(s: NotificationSummary): String =
        s.label.ifBlank { s.packageName }.ifBlank { "未知应用" }
}
