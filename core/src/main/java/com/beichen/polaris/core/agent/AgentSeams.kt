package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AssistantMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用户一轮输入（接缝①的输入值，可变）。
 *
 * 用 data class 而不是两个裸参数，是为了让中间件**只能整体替换**：
 * 若签名是 `(String, List<String>) -> ...`，将来加一个字段（比如附件 ID）时，
 * 所有中间件的签名都要改一遍，而它们本来并不关心新字段。
 */
data class UserTurnInput(
    val text: String,
    val images: List<String> = emptyList(),
)

/** 用户消息接缝的裁决。 */
sealed interface UserMessageDecision {

    /** 继续（可带改写后的文本 / 图片）。内容与进来时相同 = 没人改。 */
    data class Proceed(val input: UserTurnInput) : UserMessageDecision

    /**
     * 就地答复，**不进 Agent 循环**。
     *
     * 用途：完全不需要模型的场景（本地命令、快捷指令、权限未配置时的引导语）。
     * 代价要说清楚：走这条路的回合没有工具调用、没有 journal 的 step 记录，
     * 崩溃恢复时它不算「未闭合回合」——它本来就没有副作用，可接受。
     */
    data class Handled(val reply: String) : UserMessageDecision
}

/**
 * 用户消息中间件（接缝①，waterfall / 洋葱语义）。
 *
 * 实现方**必须**在合适的时候调用 `next`：
 * - 调了 → 后续中间件继续，最终返回值可能来自下游；
 * - 不调，直接返回 [UserMessageDecision.Proceed] → **截断**后续中间件；
 * - 不调，返回 [UserMessageDecision.Handled] → 整个回合短路。
 *
 * 「不调 next」是故意的能力（前缀中间件可以据条件跳过后续），所以它不是错误，
 * 但意味着**顺序有意义**：注册在前的中间件能看到未被改写的原文。
 */
interface UserMessageMiddleware {

    suspend fun intercept(
        input: UserTurnInput,
        next: suspend (UserTurnInput) -> UserMessageDecision,
    ): UserMessageDecision
}

/**
 * 助手消息监听器（接缝②，parallel / 只读观测）。
 *
 * 与 [UserMessageMiddleware] 的区别是**没有任何返回值**——观测者不能改写已经产生的
 * 助手消息，也不能阻止它进入历史。要拦截助手输出，正确的位置是工具守卫（③）或
 * 系统提示，不是这里。
 *
 * 多个监听器**并发**执行，彼此不可见对方的副作用顺序。用途：埋点、桌宠表情驱动、
 * 通知栏摘要、语音播报。这些都是「知道了就行」的事，不该互相等待。
 */
interface AssistantMessageListener {

    suspend fun onAssistantMessage(message: AssistantMessage)
}

/**
 * 三个接缝的装配点（对应 [RealAgentLoop] 的 `seams` 构造参数）。
 *
 * ## 三个接缝，三种分发语义（不是随便配的）
 *
 * | 接缝 | 语义 | 能不能改 | 为什么是这种语义 |
 * |---|---|---|---|
 * | ① 用户消息 | waterfall（环绕） | 能改写、能短路 | 「收到一句话」之后要做的事天然是链式的：脱敏 → 变量替换 → 指令解析，后一步要看到前一步的结果 |
 * | ② 助手消息 | parallel（并发） | 不能改 | 观测者之间没有先后关系，串行只会让回合变慢 |
 * | ③ 工具调用 | bail（首个非弃权定案） | 只能拒绝 | 见 [GuardDecision]：加限制必须单调，否则「放行」会被读成「批准」 |
 *
 * ## 为什么不合进 [Hooks]
 *
 * [Hooks] 的契约写死了「只读通知，不参与改写」，四个时机全是无返回值。
 * 把可改写的东西塞进去，会让每个既有实现都开始怀疑「我是不是也该改点什么」——
 * 而它现在的零成本来自「谁都不会改」。能力边界分开，比塞进去再写注释可靠。
 *
 * ## 空装配的代价为零
 *
 * [NONE] 下三个分发方法都在第一行返回，不做任何分配。harness 与子代理不传，
 * 行为与改造前逐字节一致。
 *
 * ## 当前生产装配状态（2026-11-13）
 *
 * **没有装配任何业务**：`ChatViewModel` 与 `SubAgentRunner` 都还走默认 [NONE]。
 * 这是刻意的——本轮交付的是「挂点」，不是「挂在挂点上的功能」。先接业务再接的话，
 * 一旦功能设计返工，接缝的形状会跟着返工；先定形状，业务是后两轮的事：
 *  - 语音/桌宠要的「AI 说完话」→ ②；
 *  - 麦克风仲裁与打断仲裁要的「这句话要不要处理」→ ①；
 *  - 只读模式、路径边界、危险命令二次拦截 → ③。
 *
 * 装配入口只有一个：[productionSeams]（在 SeamDeny.kt），它负责把日志接回 AgentLogger。
 * 不要在别处直接 `AgentSeams(...)`——那样日志会静默丢失。
 */
class AgentSeams(
    /** ① 用户消息中间件。按注册序**从外到内**调用（第 0 个最先看到原文）。 */
    val userMessage: List<UserMessageMiddleware> = emptyList(),
    /** ③ 工具守卫。按注册序串行询问，首个 Deny 即终止。 */
    val toolGuards: List<ToolGuard> = emptyList(),
    /** ② 助手消息监听器。并发执行，异常与超时都不影响回合。 */
    val assistantListeners: List<AssistantMessageListener> = emptyList(),
    /** 内部日志（拒绝 / 超时 / 中间件崩溃）。默认丢弃，理由见 [ToolGuardChain] 的 log 参数。 */
    private val log: SeamLog = {},
) {

    val isEmpty: Boolean
        get() = userMessage.isEmpty() && toolGuards.isEmpty() && assistantListeners.isEmpty()

    /**
     * ① 分发：跑完整条中间件链。
     *
     * ## 失败为什么是 fail-open（与守卫相反）
     *
     * 两边的不对称是刻意的，判据只有一条：**哪种错误不可恢复**。
     *
     * - 守卫放行一个本该被拒的写操作 → 副作用已经发生，事后无法察觉、无法撤销；
     * - 中间件崩溃导致用户这句话被吞掉 → 用户看到「没反应」，重说一遍即可，
     *   但如果按 fail-closed 处理（直接报错或空回复），用户会以为 App 坏了。
     *
     * 所以：中间件坏了就**用原文继续**，并留一条 error 日志。这也是为什么中间件
     * 不适合承担安全职责——安全职责在守卫。
     */
    suspend fun applyUserMessage(input: UserTurnInput): UserMessageDecision {
        if (userMessage.isEmpty()) return UserMessageDecision.Proceed(input)
        // 整条链共用一个上限：逐条设超时会让「5 个中间件各卡 1 秒」也变成 5 秒，
        // 而用户感受到的是「发出去没反应」的总时长，不是一个中间件的耗时。
        val decision = withTimeoutOrNull(USER_SEAM_TIMEOUT_MS) { runChain(0, input) }
        if (decision == null) {
            log("用户消息中间件链超过 ${USER_SEAM_TIMEOUT_MS}ms，按原文继续")
            return UserMessageDecision.Proceed(input)
        }
        return decision
    }

    private suspend fun runChain(index: Int, current: UserTurnInput): UserMessageDecision {
        if (index >= userMessage.size) return UserMessageDecision.Proceed(current)
        val middleware = userMessage[index]
        return try {
            middleware.intercept(current) { next -> runChain(index + 1, next) }
        } catch (cancel: CancellationException) {
            // 取消不是中间件失败：上抛，由回合层收尾
            throw cancel
        } catch (e: Throwable) {
            log("用户消息中间件 #${index + 1} 失败（${e::class.java.simpleName}: ${e.message}），按原文继续")
            runChain(index + 1, current)
        }
    }

    /** ③ 分发：守卫链。空装配时直接弃权，不分配对象。 */
    suspend fun checkTool(ctx: GuardContext): GuardDecision =
        if (toolGuards.isEmpty()) GuardDecision.Abstain else ToolGuardChain(toolGuards, log).check(ctx)

    /**
     * ② 分发：并发通知全部监听器，**等它们全部结束**再返回。
     *
     * 为什么不 fire-and-forget：监听器要驱动 UI（桌宠表情、语音播报），
     * 若不等就进入下一步，下一轮的工具结果可能比上一轮的表情先到——
     * 桌宠会「先说后动」。等它们的代价是一条消息一次（毫秒级），可以接受。
     *
     * 单个监听器 [LISTENER_TIMEOUT_MS] 上限：一个卡死的埋点 SDK 不该拖住整个回合。
     */
    suspend fun notifyAssistant(message: AssistantMessage) {
        if (assistantListeners.isEmpty()) return
        coroutineScope {
            for (listener in assistantListeners) {
                launch {
                    try {
                        withTimeoutOrNull(LISTENER_TIMEOUT_MS) { listener.onAssistantMessage(message) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Throwable) {
                        log("助手消息监听器失败：${e::class.java.simpleName}: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {

        /** 整条用户消息链的上限。2 秒：超过这个时间，用户已经在怀疑「是不是没发出去」。 */
        const val USER_SEAM_TIMEOUT_MS = 2_000L

        /** 单个助手消息监听器的上限。 */
        const val LISTENER_TIMEOUT_MS = 1_500L

        /** 空装配。默认值用它，行为与「没有接缝」逐字节一致。 */
        val NONE = AgentSeams()
    }
}
