package com.beichen.polaris.core.time

/**
 * 单调时钟：**所有超时判定都必须用它，不能用 `System.currentTimeMillis()`**。
 *
 * 墙钟会被 NTP 校准、用户手动改时间、时区切换改动，而且可能**回拨**：
 *  - 回拨 → `while (now < deadline)` 永远不退出。`JobRunner` 的后台任务轮询、
 *    `LocalLlamaClient` 的重试生成这两处**没有外层 `withTimeout` 兜底**，真会卡死；
 *  - 前跳 → 提前判超时。`BuildEngine` 的 40 分钟 CI 等待会立刻报「CI 超时」。
 *
 * 基于 [System.nanoTime]（Android 上映射 `CLOCK_MONOTONIC`），不受墙钟调整影响。
 * 设备休眠期间计时暂停——对「任务/脚本跑太久」的语义来说，「休眠不计时」正是想要的：
 * 用户把手机放下一晚上，不该让一个后台任务因为「过了 8 小时」被判超时。
 *
 * 零 Android 依赖，纯 JVM 环境可直接跑，harness 与单测都能注入假时钟。
 */
object Monotonic {

    /** 起始点任意，只保证**差值**可靠——不要把它当时间戳存盘或展示。 */
    private val DEFAULT: () -> Long = { System.nanoTime() / 1_000_000L }

    @Volatile
    private var source: () -> Long = DEFAULT

    fun nowMs(): Long = source()

    /**
     * 恢复默认时钟源。
     *
     * 给 AppGraph 的统一清场用（harness 跑完一个用例要还原，否则假时钟会漏到下一个
     * 用例里，表现为「莫名其妙立刻超时」）。生产代码不要调用。
     */
    fun reset() {
        source = DEFAULT
    }

    /**
     * 仅供测试：替换时钟源，用来模拟「时钟回拨 / 前跳」。
     * 传 `null` 等价于 [reset]。
     */
    fun installForTest(clock: (() -> Long)?) {
        source = clock ?: DEFAULT
    }
}
