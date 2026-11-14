package com.beichen.polaris.core.agent

/**
 * 「自动续跑」的裁决：进程被系统杀掉后，下一次冷启动要不要替用户接着跑。
 *
 * ## 为什么需要一个纯函数
 *
 * 这件事的每一个判断都会**花钱**（一次续跑就是一整轮 LLM 请求），而且失败模式很凶：
 * 任务本身如果就是被内存撑爆的，自动续跑 → 再被杀 → 再续跑，能无限循环下去。
 * 所以判定不能散在 ViewModel 的一串 `if` 里，必须是一个能被逐条断言的决策表——
 * 每条理由都对应一种真实存在过的现场，改坏任何一条都该当场红。
 *
 * ## 三态，不是两态
 *
 * [AutoResumeDecision] 刻意分三档：
 * - `RESUME`：直接跑；
 * - `PROMPT_ONLY`：**不跑，但要说话**——用户需要知道为什么没跑（会话没了、模型没配、
 *   已经续过太多次）。沉默会让他以为功能坏了；
 * - `SKIP`：什么都不做，连提示都不必给（没有中断、正在跑别的、用户自己关了开关）。
 *
 * 分三档而不是「跑 / 不跑」的原因：冷启动时已经有一条通用的中断提示（`AgentRunState.note`，
 * 「可以说继续」）。`PROMPT_ONLY` 的作用是**覆盖**它——当「说继续」并不管用时
 * （会话被删、没有模型），原提示就是一条误导。
 *
 * ## 判定顺序是有讲究的
 *
 * 先判「能不能跑」（状态存在 / 未完成 / 不忙），再判「该不该跑」（开关 / 熔断 / 时效），
 * 最后判「跑得成吗」（会话在不在、是不是当前会话、有没有模型）。
 * 顺序反了会出现「明明正在跑别的任务，却弹一句『上次任务已过期』」这种噪声。
 */
enum class AutoResumeDecision {
    /** 自动发起一轮续跑。 */
    RESUME,

    /** 不续跑，但把 [AutoResumeVerdict.message] 给用户看。 */
    PROMPT_ONLY,

    /** 什么都不做，连提示都不给。 */
    SKIP,
}

/** 裁决理由。取值稳定，可直接进日志与断言。 */
enum class AutoResumeReason {
    /** 可以续跑。 */
    OK,

    /** 磁盘上没有运行态（从没跑过长任务，或上次正常结束已清）。 */
    NO_STATE,

    /** 状态不是 interrupted（已完成 / 正在进行）。 */
    NOT_INTERRUPTED,

    /** 此刻已有回合在跑，不能插队。 */
    BUSY,

    /**
     * 用户正在输入框里打字，不能插队。
     *
     * 自动续跑要往输入框写一句「继续」再走 `send()`。而聊天页的输入框是自动聚焦的，
     * 冷启动后几百毫秒内用户很可能已经在打字了——那一下会把用户敲了一半的字
     * 整个替换掉，**这是数据丢失**，比不自动续跑严重得多。
     */
    INPUT_BUSY,

    /** 用户关掉了「自动续跑」。 */
    DISABLED,

    /**
     * 有中断状态，但没记下原始请求（`prompt` 为空）。
     *
     * 真实来源：更早版本的状态文件、或写入中途被杀只落了半份。此时续跑上下文是空的，
     * 模型只会收到一句「继续」而不知道要继续什么——比不跑更糟。
     */
    NO_PROMPT,

    /** 连续自动续跑次数达到上限，判定为「这个任务一跑就被杀」。 */
    ATTEMPT_LIMIT,

    /** 中断太久，自动重跑会让人莫名其妙（也多半早已不该继续）。 */
    TOO_OLD,

    /** 中断任务所属的会话已被删除。 */
    SESSION_MISSING,

    /** 当前打开的会话不是中断任务所属的那个。 */
    OTHER_SESSION,

    /** 没有可用模型，跑也只会立刻报错。 */
    NO_MODEL,
}

/**
 * 裁决输入。全部是**已经取好的值**，不含任何 Android 类型或副作用，
 * 这样 harness 可以在一张表里把所有分支跑一遍。
 */
data class AutoResumeInput(
    val state: AgentRunState?,
    /** 当前时间（毫秒）。由调用方传入，便于测试「过期」。 */
    val nowMs: Long,
    /** 当前界面打开的会话 id；null 表示还没有会话。 */
    val activeSessionId: String?,
    /** 中断任务所属的会话是否仍然存在。 */
    val sessionExists: Boolean,
    /** 用户设置里的「自动续跑」开关。 */
    val enabled: Boolean,
    /** 是否已配置可用的模型（有 key / 本地模型已就绪）。 */
    val hasModel: Boolean,
    /** 是否已有回合在跑。 */
    val isRunning: Boolean,
    /** 输入框是否为空。为 false 时不续跑——自动续跑要往输入框写「继续」，会覆盖用户正在打的字。 */
    val inputBlank: Boolean = true,
    /** 同一任务允许自动续跑的次数上限。 */
    val maxAttempts: Int = AutoResumePolicy.DEFAULT_MAX_ATTEMPTS,
    /** 中断超过多久就不再自动续跑。 */
    val maxAgeMs: Long = AutoResumePolicy.DEFAULT_MAX_AGE_MS,
)

/** 裁决结果。 */
data class AutoResumeVerdict(
    val decision: AutoResumeDecision,
    val reason: AutoResumeReason,
    /** 给用户看的一句话；`SKIP` 时为空串。 */
    val message: String = "",
)

object AutoResumePolicy {

    /**
     * 允许自动续跑的默认次数。
     *
     * 取 2 而不是 1：系统在低内存下可能连着杀两次（第一次杀进程、第二次杀刚重建的进程），
     * 只给 1 次会让功能在最该生效的场景里失效。
     * 取 2 而不是更多：同一个任务连杀 3 次，基本可以断定「它一跑就会被杀」，
     * 再续只是在烧 token 和电，应该把问题交给用户。
     */
    const val DEFAULT_MAX_ATTEMPTS = 2

    /**
     * 中断超过这个时长就不自动续跑。
     *
     * 24 小时：隔夜回来接着跑是合理的（用户就是回来继续的）；
     * 隔三天回来还被自动扣一次钱，就说不通了。
     */
    const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** 一次裁决。纯函数：同样输入永远同样输出，无时间读取、无 IO。 */
    fun decide(input: AutoResumeInput): AutoResumeVerdict {
        val state = input.state
            ?: return skip(AutoResumeReason.NO_STATE)
        if (state.status != AgentRunState.STATUS_INTERRUPTED) {
            return skip(AutoResumeReason.NOT_INTERRUPTED)
        }
        // 正在跑别的回合：不插队、也不提示。用户眼前已经有事在发生，
        // 这时候弹「上次任务被中断」只会制造困惑。
        if (input.isRunning) return skip(AutoResumeReason.BUSY)
        // 用户正在输入框里打字：自动续跑要往输入框写「继续」，那会把敲了一半的字清空。
        // 数据丢失比不自动续跑严重得多，宁可这一轮不跑——下次冷启动还会再来。
        if (!input.inputBlank) return skip(AutoResumeReason.INPUT_BUSY)
        // 用户自己关的开关。原中断提示（「可以说继续」）已经够用，不必再补一句。
        if (!input.enabled) return skip(AutoResumeReason.DISABLED)

        // 没有原始请求就无从续起：续跑上下文是空的，模型只会看到一句「继续」。
        if (state.prompt.isBlank()) {
            return prompt(
                AutoResumeReason.NO_PROMPT,
                "上次任务没有留下可恢复的内容，无法自动继续。"
            )
        }

        if (state.autoResumeAttempts >= input.maxAttempts) {
            return prompt(
                AutoResumeReason.ATTEMPT_LIMIT,
                "上次任务已经自动续跑 ${state.autoResumeAttempts} 次仍未完成，" +
                    "可能一跑就会被系统回收。为免反复消耗，已停止自动继续；" +
                    "你可以直接说「继续」再试一次。"
            )
        }

        // updatedAt 在旧数据里可能为 0（更早版本写的），回落到 startedAt 而不是当成「极旧」。
        // 两个都是 0 说明这份状态从来没有时间戳（数据残缺），不做时效判断——
        // 宁可多跑一次，也不要把一份完好的进度永远判成「过期」。
        val anchor = state.updatedAt.takeIf { it > 0 } ?: state.startedAt
        if (anchor > 0 && input.nowMs - anchor > input.maxAgeMs) {
            return prompt(
                AutoResumeReason.TOO_OLD,
                "上次任务已中断超过 ${input.maxAgeMs / 3_600_000} 小时，未自动继续。"
            )
        }

        if (!input.sessionExists) {
            return prompt(
                AutoResumeReason.SESSION_MISSING,
                "上次任务所在的会话已被删除，无法自动继续。"
            )
        }
        if (input.activeSessionId != state.sessionId) {
            return prompt(
                AutoResumeReason.OTHER_SESSION,
                "上次任务在另一个会话里被中断，切回那个会话后可以说「继续」。"
            )
        }
        if (!input.hasModel) {
            return prompt(
                AutoResumeReason.NO_MODEL,
                "上次任务被中断，但当前没有可用的模型配置，无法自动继续。"
            )
        }

        return AutoResumeVerdict(
            decision = AutoResumeDecision.RESUME,
            reason = AutoResumeReason.OK,
            message = buildString {
                append("上次任务被系统中断")
                if (state.stepIndex > 0) append("（已进行到第 ${state.stepIndex} 步）")
                append("，已自动继续")
                if (state.autoResumeAttempts > 0) {
                    append("（第 ${state.autoResumeAttempts + 1} 次尝试）")
                }
            }
        )
    }

    private fun skip(reason: AutoResumeReason) =
        AutoResumeVerdict(AutoResumeDecision.SKIP, reason)

    private fun prompt(reason: AutoResumeReason, message: String) =
        AutoResumeVerdict(AutoResumeDecision.PROMPT_ONLY, reason, message)
}
