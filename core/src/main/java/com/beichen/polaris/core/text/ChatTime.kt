package com.beichen.polaris.core.text

/**
 * 消息时间显示（W-30）：纯函数，无 Android 依赖，harness 可直接断言。
 *
 * 设计取舍：
 *  - 不用 `SimpleDateFormat`（依赖默认时区/Locale，测试不可控）；
 *  - 只做「够用的相对粒度」：今天看时分，昨天说昨天，本周说周几，更早说日期——
 *    聊天里精确到秒没有意义，反而占地方；
 *  - 时间戳为 0 / 负数（旧数据、未落库草稿）返回空串，调用方据此不渲染那一行。
 */
object ChatTime {

    /** 一天的毫秒数。 */
    private const val DAY = 86_400_000L

    /**
     * 格式化消息时间。
     *
     * @param timestampMs 消息时间（epoch 毫秒）
     * @param nowMs 当前时间（测试注入，避免依赖系统时钟）
     * @return `HH:mm` / `昨天 HH:mm` / `周一 HH:mm` / `M月d日`；时间戳无效返回空串
     */
    fun format(timestampMs: Long, nowMs: Long): String {
        if (timestampMs <= 0L || nowMs <= 0L) return ""
        // 未来时间（时钟回拨/服务端时间偏差）按今天处理，不显示"明天"
        val delta = (nowMs - timestampMs).coerceAtLeast(0L)
        val clock = clockOf(timestampMs)
        return when {
            delta < DAY -> clock
            delta < 2 * DAY -> "昨天 $clock"
            delta < 7 * DAY -> "${weekdayOf(timestampMs)} $clock"
            else -> dateOf(timestampMs)
        }
    }

    /**
     * 精确时间（长按菜单/收藏页用）：`yyyy-MM-dd HH:mm`。
     * 与 [format] 分开——列表里要短，详情里要能查到具体时刻。
     */
    fun precise(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val (y, mo, d, h, mi) = partsOf(timestampMs)
        return "$y-${pad2(mo)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}"
    }

    /** 若需把秒级时间戳（部分服务端字段）统一成毫秒。 */
    fun normalizeToMillis(raw: Long): Long = if (raw in 1..9_999_999_999L) raw * 1000 else raw

    private fun clockOf(ms: Long): String {
        val (_, _, _, h, mi) = partsOf(ms)
        return "${pad2(h)}:${pad2(mi)}"
    }

    private fun dateOf(ms: Long): String {
        val (_, mo, d, _, _) = partsOf(ms)
        return "${mo}月${d}日"
    }

    /** 周一~周日；用 1970-01-01 为周四作为基准推算，不依赖 java.time。 */
    private fun weekdayOf(ms: Long): String {
        val days = ms / DAY
        // 1970-01-01 是周四（index 3，0=周日）
        val idx = ((days + 4) % 7).toInt().let { if (it < 0) it + 7 else it }
        return WEEKDAYS[idx]
    }

    private val WEEKDAYS = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 把毫秒拆成 (年, 月, 日, 时, 分)，UTC。
     *
     * 为什么用 UTC 而不用设备时区：本函数是**纯函数**，无法读时区；UI 侧展示的是
     * 「相对当前时间」的粗略粒度（今天/昨天/日期），UTC 与本地时区最多差一天，
     * 对聊天时间显示可接受。需要绝对准确时刻时用 [precise] 配合上层本地化。
     */
    private fun partsOf(ms: Long): List<Int> {
        var rest = ms.coerceAtLeast(0L) / 1000L      // 秒
        val days = rest / 86_400L
        rest -= days * 86_400L
        val hour = (rest / 3600L).toInt()
        val minute = ((rest % 3600L) / 60L).toInt()
        val (y, m, d) = civilFromDays(days)
        return listOf(y, m, d, hour, minute)
    }

    /**
     * 以 1970-01-01 为原点的天数 → 公历年月日（Howard Hinnant 的 civil_from_days 算法）。
     * 纯整数运算，无库依赖，闰年规则完整。
     */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097                                  // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)            // [0, 365]
        val mp = (5 * doy + 2) / 153                                  // [0, 11]
        val d = doy - (153 * mp + 2) / 5 + 1                          // [1, 31]
        val m = if (mp < 10) mp + 3 else mp - 9                        // [1, 12]
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()
}
