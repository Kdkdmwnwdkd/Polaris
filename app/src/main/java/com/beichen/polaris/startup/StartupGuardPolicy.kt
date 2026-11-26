package com.beichen.polaris.startup

/**
 * 启动自救的判据（纯函数，无 Android 依赖）。
 *
 * 单独抽出来的理由和其他 Policy 一致：真正的执行时机是「用户被锁在门外」，
 * 那个场景没法在开发机上重演，只能靠断言把语义钉死。
 * 见 `scripts/qa/StartupGuardVerification.kt`。
 */
internal object StartupGuardPolicy {

    /**
     * 上次尝试距今超过这个时长，就不算「上次崩了」。
     *
     * 理由：进程被系统回收、用户按 HOME 离开、手机放了一夜——都会留下
     * 「attempt 写了、booted 没写」的痕迹。用时间窗口把它们排除，
     * 否则用户正常用一周后会被莫名其妙隔离一次。
     */
    const val FRESH_MS: Long = 5 * 60_000L

    /** 连续几次没起来才动手。2 = 用户点第三次图标时自救（前两次留给「偶发」）。 */
    const val MAX_STRIKES: Int = 2

    /**
     * 算本次的连续失败次数。
     *
     * 下界必须是 0 而不是 1：崩溃后用户可能立刻再点一次图标，两次启动可以落在
     * 同一毫秒里。写成 `in 1..` 会把这个最该救的场景判成「不算崩」，
     * 连带后续计数全部错位（2026-11-26 本机测试 S1~S3 就是这么红的）。
     * 时钟回拨（负值）仍要排除。
     */
    fun nextStrikes(prev: Int, attemptAt: Long, booted: Boolean, now: Long): Int {
        val crashedLastTime = attemptAt > 0L && !booted && (now - attemptAt) in 0..FRESH_MS
        return if (crashedLastTime) prev + 1 else 0
    }

    fun shouldQuarantine(strikes: Int): Boolean = strikes >= MAX_STRIKES
}
