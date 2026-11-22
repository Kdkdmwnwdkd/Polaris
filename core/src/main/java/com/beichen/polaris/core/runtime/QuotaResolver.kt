package com.beichen.polaris.core.runtime

/**
 * 把 [Quota] 落成「loop 真正能用的上限」的唯一换算点。
 *
 * ## 为什么需要一个换算层，而不是让 loop 直接读 Quota
 *
 * `Quota.maxSteps = 0` 表示「没设」，但 `RealAgentLoop` 的 `maxSteps` 是
 * `repeat(n)` 的入参——0 会变成「一步都不跑」。两者语义相反。中间这层就是
 * 把「没设」翻译成「用兜底」，并保证 0 永远到不了 loop 里。
 *
 * ## 为什么兜底值从参数传，不在这里写常量
 *
 * 写常量会让这个文件依赖 `LoopDefaults`，进而依赖 `Consent` / `AgentTool`——
 * 那一条链会把本可以纯 JVM 验证的东西拖回 Android 编译闭包（`RetrySchedule`
 * 就是被同样的坑逼出来的）。兜底值由调用方给，本文件零依赖，`QuotaResolverTest`
 * 可以直接进变异门禁。
 */
object QuotaResolver {

    /** 解析结果：两项都保证是正数，可直接交给 loop。 */
    data class Limits(
        val maxSteps: Int,
        val deadlineMs: Long,
    )

    /**
     * @param quota 回合配额；任一字段为 0（或负数）表示该项不设。
     * @param fallbackSteps 不设时使用的步数上限，必须为正——传 0 说明调用方
     *   自己也没想清楚上限，宁可当场炸，也不要静默变成「一步不跑」。
     * @param fallbackDeadlineMs 不设时使用的墙钟预算，必须为正。
     */
    fun resolve(quota: Quota, fallbackSteps: Int, fallbackDeadlineMs: Long): Limits {
        require(fallbackSteps > 0) {
            "fallbackSteps 必须为正数，得到 $fallbackSteps——0 会让回合一步都不跑"
        }
        require(fallbackDeadlineMs > 0L) {
            "fallbackDeadlineMs 必须为正数，得到 $fallbackDeadlineMs"
        }
        return Limits(
            maxSteps = if (quota.maxSteps > 0) quota.maxSteps else fallbackSteps,
            deadlineMs = if (quota.deadlineMs > 0L) quota.deadlineMs else fallbackDeadlineMs,
        )
    }
}
