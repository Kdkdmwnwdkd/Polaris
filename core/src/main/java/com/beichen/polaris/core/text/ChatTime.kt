package com.beichen.polaris.core.text

/**
 * 消息时间显示（W-30）：纯函数，无 Android 依赖，harness 可直接断言。
 *
 * 设计取舍：
 *  - 不用 `SimpleDateFormat`（依赖默认时区/Locale，测试不可控）；
 *  - 只做「够用的相对粒度」：今天看时分，昨天说昨天，本周说周几，更早说日期——
 *    聊天里精确到秒没有意义，反而占地方；
 *  - 时间戳为 0 / 负数（旧数据、未落库草稿）返回空串，调用方据此不渲染那一行。
 *
 * ## 时区（2026-11-23 真机修正）
 *
 * 改造前 `partsOf` 按 **UTC** 拆年月日时分，注释给的理由是「本函数是纯函数，无法读时区；
 * 粗略粒度下最多差一天，可接受」。这个判断是错的，真机一眼就看出来：
 *
 * 北京时间 21:45 发出的消息显示成 `13:45`，用户原话「这时间是哪里的，看不懂」。
 * 更糟的是跨日边界——北京时间 00:30 的消息在 UTC 里属于前一天，会被打成
 * 「昨天 16:30」，日期整个错一天。
 *
 * 正确做法是**跟随设备时区**（用户在中国就是北京时间）。纯函数与可测性不冲突：
 * 偏移作为参数暴露，默认读 [java.util.TimeZone.getDefault]；harness 传固定值，
 * 断言不随跑测试的机器漂移。
 */
object ChatTime {

    /** 一天的毫秒数。 */
    private const val DAY = 86_400_000L

    /** 北京时间（UTC+8）的固定偏移，供测试与文档引用。 */
    const val OFFSET_BEIJING_MS: Int = 8 * 3_600_000

    /**
     * 设备时区在 [ms] 这一刻的偏移（毫秒）。
     *
     * 用 [java.util.TimeZone] 而不是写死 +8：core 不假设用户在中国，有夏令时的地区
     * 必须按当日实际偏移算，否则换季那两周会差一小时。
     * `ms <= 0` 时按「此刻」取——0 的语义是「没有时间戳」，不该参与取偏移。
     */
    fun localOffset(ms: Long): Int {
        val at = if (ms > 0L) ms else System.currentTimeMillis()
        return java.util.TimeZone.getDefault().getOffset(at)
    }

    /**
     * 格式化消息时间。
     *
     * @param timestampMs 消息时间（epoch 毫秒）
     * @param nowMs 当前时间（测试注入，避免依赖系统时钟）
     * @param offsetMs 展示时区相对 UTC 的偏移；默认取设备时区
     * @return `HH:mm` / `昨天 HH:mm` / `周一 HH:mm` / `M月d日`；时间戳无效返回空串
     */
    fun format(
        timestampMs: Long,
        nowMs: Long,
        offsetMs: Int = localOffset(timestampMs)
    ): String {
        if (timestampMs <= 0L || nowMs <= 0L) return ""
        // 未来时间（时钟回拨/服务端时间偏差）按今天处理，不显示"明天"
        val delta = (nowMs - timestampMs).coerceAtLeast(0L)
        val clock = clockOf(timestampMs, offsetMs)
        return when {
            delta < DAY -> clock
            delta < 2 * DAY -> "昨天 $clock"
            delta < 7 * DAY -> "${weekdayOf(timestampMs, offsetMs)} $clock"
            else -> dateOf(timestampMs, offsetMs)
        }
    }

    /**
     * 精确时间（长按菜单/收藏页用）：`yyyy-MM-dd HH:mm`。
     * 与 [format] 分开——列表里要短，详情里要能查到具体时刻。
     *
     * @param offsetMs 展示时区相对 UTC 的偏移；默认取设备时区
     */
    fun precise(timestampMs: Long, offsetMs: Int = localOffset(timestampMs)): String {
        if (timestampMs <= 0L) return ""
        val (y, mo, d, h, mi) = partsOf(timestampMs, offsetMs)
        return "$y-${pad2(mo)}-${pad2(d)} ${pad2(h)}:${pad2(mi)}"
    }

    /** 若需把秒级时间戳（部分服务端字段）统一成毫秒。 */
    fun normalizeToMillis(raw: Long): Long = if (raw in 1..9_999_999_999L) raw * 1000 else raw

    private fun clockOf(ms: Long, offsetMs: Int): String {
        val (_, _, _, h, mi) = partsOf(ms, offsetMs)
        return "${pad2(h)}:${pad2(mi)}"
    }

    private fun dateOf(ms: Long, offsetMs: Int): String {
        val (_, mo, d, _, _) = partsOf(ms, offsetMs)
        return "${mo}月${d}日"
    }

    /** 周一~周日；用 1970-01-01 为周四作为基准推算，不依赖 java.time。 */
    private fun weekdayOf(ms: Long, offsetMs: Int): String {
        val days = Math.floorDiv(ms + offsetMs, DAY)
        // 1970-01-01 是周四（index 3，0=周日）
        val idx = ((days + 4) % 7).toInt().let { if (it < 0) it + 7 else it }
        return WEEKDAYS[idx]
    }

    private val WEEKDAYS = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /**
     * 把毫秒拆成 (年, 月, 日, 时, 分)，按 [offsetMs] 指定的时区。
     *
     * 用 `floorDiv` / `floorMod` 而不是 `/` `%`：负偏移（美洲时区）下 `ms + offsetMs`
     * 可能落到 1970 之前，向零取整会让 UTC 23:30 算成次日 00:30 的反面。
     * 纯整数运算，无库依赖，闰年规则完整。
     */
    private fun partsOf(ms: Long, offsetMs: Int): List<Int> {
        val totalSec = Math.floorDiv(ms + offsetMs, 1000L)
        val days = Math.floorDiv(totalSec, 86_400L)
        val secOfDay = Math.floorMod(totalSec, 86_400L)
        val hour = (secOfDay / 3600L).toInt()
        val minute = ((secOfDay % 3600L) / 60L).toInt()
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
