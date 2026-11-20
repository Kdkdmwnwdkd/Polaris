package com.beichen.polaris.core.agent

import com.beichen.polaris.core.Consent

/**
 * [RealAgentLoop] 的**纯常量默认值**，单一真源（P1 遗留债 §18.7）。
 *
 * ## 为什么需要这个对象
 *
 * 不变量 1 把 loop 的构造点收进了 [com.beichen.polaris.core.runtime.AgentLoopFactory]，
 * 但那条纪律只保证「只有一处 new」，不保证**两处写的默认值一样**：工厂的参数表是
 * 逐字复刻 [RealAgentLoop] 的，而复刻是**人肉**完成的。历史上正是这类漂移造成过两次事故：
 * `maxSteps=16` 让长任务在第 16 步被截断、`providerRetry=2` 让 429 的第三档退避永不触发。
 * 两次都编译通过、单测通过，只在真机上表现为「任务莫名停住」。
 *
 * 所以这里只放**纯常量**：两处都写成 `= LoopDefaults.X`，改一处就是改两处，
 * 「复刻时抄错一个数字」这件事在语法上不再可能。由
 * `scripts/qa/run-runtime-invariants-verification.sh` 的 F 段钉住（默认值必须是
 * `LoopDefaults.` 引用，不许写字面量）。
 *
 * ## 什么不该进来
 *
 * - **有状态的实例**（[AttemptOutputTracker]、`FailureGuard`）：每次构造必须 new，
 *   共享一份会让两个 loop 的「已上屏字符数」互相污染。它们是默认值表达式，不是常量。
 * - **已经是单一真源的引用**（`ContextBudget.DEFAULT_BUDGET_TOKENS`、`RunScope.MAIN`、
 *   `RetryBackoffPolicy::backoffMillis`）：再包一层只是多一次跳转。
 * - **null / false / 空集合**：`= null` 本身没有漂移空间。
 */
object LoopDefaults {

    /** 最大步数。60 步 × 每步 30 秒 ≈ 半小时，与 [TURN_BUDGET_MS] 互为止损。 */
    const val MAX_STEPS = 60

    /** 单批并行工具数上限。 */
    const val MAX_PARALLEL_TOOLS = 5

    /**
     * 供应商瞬时失败重试次数。`-1` = 跟随 `retryProfileProvider` 的档位（R10 起的默认）。
     *
     * 不写死 3：35 家供应商共用一个次数是 R10 修掉的缺陷——官方直连 3 次够用、
     * 聚合中转商高峰期不够、本地推理一次都不该重试。
     */
    const val PROVIDER_RETRY = -1

    /** 历史消息条数硬上限（与上下文 token 预算构成双闸）。 */
    const val MAX_HISTORY_MESSAGES = 40

    /**
     * 单次 LLM 调用的上限。流式响应没有「整通超时」，这里卡的是「连接挂死」。
     * 端侧模型（`local-` 前缀）在 loop 内部自动放宽到 3 倍。
     */
    const val LLM_CALL_TIMEOUT_MS = 300_000L

    /** 单回合总预算（软截止：到点在步边界收尾，不硬杀）。 */
    const val TURN_BUDGET_MS = 30 * 60 * 1000L

    /**
     * 未声明审批能力时的默认值。
     *
     * 共享一个实例是安全的：[Consent.Deny] 不可变，且 `channel` 只进日志。
     * 字符串保留 `RealAgentLoop` 而不是改叫「工厂」——它要回答的是「哪一层忘了传」，
     * 而真正消费这个值的是 loop 的执行器。
     */
    val CONSENT: Consent = Consent.unset("RealAgentLoop")
}
