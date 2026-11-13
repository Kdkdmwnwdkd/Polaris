package com.beichen.polaris.core.agent

import com.beichen.polaris.core.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次工具调用的守卫现场（只读快照）。
 *
 * 守卫拿不到 [ToolRegistry]，也拿不到 executor——它只能看「模型要做什么」，
 * 不能顺手把工具执行了。这条限制不是封装洁癖：守卫一旦能执行工具，
 * 就会有人写「守卫里先试一下」的代码，于是同一次调用跑两遍。
 *
 * @property call 模型声明的调用（名称 + 原始参数，未经解析）。
 * @property descriptor 本回合工具视图里的描述（被裁或名称写错时为 null）。
 * @property step 当前步号（0 起），供「同一步内限流」这类策略使用。
 * @property scope 执行域（主代理 `main`，子代理 `sub:<标题>`）。
 */
data class GuardContext(
    val call: ToolCall,
    val descriptor: ToolDescriptor?,
    val step: Int,
    val scope: String,
)

/**
 * 守卫裁决。**只有两种，没有「允许」。**
 *
 * 这不是为了少写一个分支，而是这套机制唯一的安全性质：
 *
 * - 守卫链是**单调递减**的——每多一个守卫，可能被执行的调用集合只会变小，不会变大。
 * - 若存在 `Allow`，语义就变成「我同意执行」，而后置守卫无法推翻它（它只能否认自己的
 *   部分）。于是「某个守卫放行了」会被读成「这条调用已获批准」，一个本意是加限制的
 *   插件，反而成了绕过前一道限制的通行证。
 * - 弃权（[Abstain]）表达的才是真话：**我没意见**，不是**我批准**。
 *
 * 因此判断顺序固定为「首个 Deny 即终止」：一旦有人拒绝，后面的守卫不再有机会说话。
 * 反过来（先收集全部意见再投票）也不做——投票需要权重，而权重没有客观来源，
 * 最后必然退化成「谁后注册谁说了算」。
 */
sealed interface GuardDecision {

    /** 没意见。继续问下一个守卫；全部弃权 = 放行。 */
    object Abstain : GuardDecision

    /**
     * 拒绝执行。这条调用不会进入 executor，直接以错误结果回喂模型。
     *
     * @property code 机器可读码（进 ToolMessage 与回合日志，如 `path_denied`）。
     *   必须稳定：模型会按 code 学会「换条路走」，改文案不该改它。
     * @property message 回喂模型的主文本（说明「什么被挡了」）。
     * @property hint 修复指引（说明「改什么才能过」）。空串表示「无路可走，直接告诉用户」。
     */
    data class Deny(
        val code: String,
        val message: String,
        val hint: String = "",
    ) : GuardDecision
}

/**
 * 工具调用守卫（接缝③，bail 语义）。
 *
 * ## 为什么挂在 loop 层，而不是 executor 里
 *
 * [ToolExecutor] 是可替换的（`executorFactory` 注入、子代理用自己的实现、harness 用替身）。
 * 守卫若写在 executor 内部，换一个执行器就等于换掉整套策略——而这正是最不该被替换的东西。
 * 挂在 [RealAgentLoop.executeOne] 里，无论底下是谁执行，策略都先过一遍。
 *
 * ## 实现约束
 *
 * - **只读**：不得修改 [GuardContext] 之外的任何状态（要计数请用 atomic）。
 * - **可挂起**：允许查配置、查网络、问用户；但循环是串行等待的，慢守卫直接拖慢每一步。
 *   超过 [ToolGuardChain.GUARD_TIMEOUT_MS] 视为守卫自身故障，按拒绝处理。
 * - **抛异常 = 拒绝**（见 [ToolGuardChain]）。守卫的 bug 不能变成放行。
 */
interface ToolGuard {

    suspend fun check(ctx: GuardContext): GuardDecision
}

/**
 * 守卫链：按注册序串行询问，首个 [GuardDecision.Deny] 即终止。
 *
 * ## 异常为什么按拒绝处理（fail-closed）
 *
 * 这里是本项目里少数**故意**选择 fail-closed 的地方，理由与「审批通道缺失时必须拒绝」
 * 同源：守卫的唯一职责是减少执行，它的崩溃若等价于放行，那这条链就从「限制」变成了
 * 「限制的假象」——装了三个守卫、第三个是坏的，实际防护反而比不装更少（因为没人会怀疑它）。
 *
 * 代价是「一个写得烂的守卫会让所有工具不可用」。可以接受，因为：
 *  1. 守卫由我们自己注册，不是第三方动态加载（`ToolGuard` 不是公开插件点）；
 *  2. 拒绝文案带 `guard_failed` 与守卫序号，用户报障时能直接定位到是哪一条；
 *  3. 反过来（fail-open）的代价是「不该执行的被悄悄执行了」，事后无法察觉。
 *
 * 超时同理：不响应挂起取消的守卫（内部跑阻塞 IO）会在 [GUARD_TIMEOUT_MS] 后被丢弃，
 * 按拒绝处理——否则一次卡死会让整个回合停在一步上，用户只看到「卡住了」。
 */
/** 接缝内部日志的出口签名：只收一句人话，级别与 tag 由装配方决定。 */
internal typealias SeamLog = (String) -> Unit

class ToolGuardChain(
    private val guards: List<ToolGuard>,
    /**
     * 日志出口。默认丢弃。
     *
     * 为什么不用 [com.beichen.polaris.core.AgentLogger]：它牵着 ConfigSanitizer 与
     * skill 模块的一整条依赖链，一 import 就让本文件无法被 QA harness 独立编译——
     * 而守卫的「首个拒绝即终止」「崩溃等于拒绝」这些语义**只有真跑才验证得了**。
     * 生产装配时由 [productionSeams] 把日志接回 AgentLogger。
     */
    private val log: SeamLog = {},
) : ToolGuard {

    override suspend fun check(ctx: GuardContext): GuardDecision {
        for ((index, guard) in guards.withIndex()) {
            val decision = try {
                withTimeoutOrNull(GUARD_TIMEOUT_MS) { guard.check(ctx) }
                    ?: return GuardDecision.Deny(
                        code = "guard_timeout",
                        message = "工具调用被策略层拦下：第 ${index + 1} 个守卫在 ${GUARD_TIMEOUT_MS}ms 内没有返回。",
                        hint = "这不是参数问题，重试同样会失败。请告知用户检查策略设置。",
                    )
            } catch (cancel: CancellationException) {
                // 取消不是拒绝：点「停止」要能立刻退出，不能被守卫兜成一条错误结果
                throw cancel
            } catch (e: Throwable) {
                log("守卫 #${index + 1} 执行失败（${e::class.java.simpleName}: ${e.message}），按拒绝处理")
                return GuardDecision.Deny(
                    code = "guard_failed",
                    message = "工具调用被策略层拦下：第 ${index + 1} 个守卫执行失败（${e::class.java.simpleName}）。",
                    hint = "这不是参数问题，重试同样会失败。请告知用户检查策略设置。",
                )
            }
            if (decision is GuardDecision.Deny) {
                log("守卫 #${index + 1} 拒绝 ${ctx.call.name}：${decision.code}")
                return decision
            }
        }
        return GuardDecision.Abstain
    }

    companion object {

        /** 单条守卫的上限。超过视为守卫故障（按拒绝处理），不是「再等等」。 */
        const val GUARD_TIMEOUT_MS = 3_000L

        /** 空链：任何调用都弃权。默认值用它，行为与「没有守卫」逐字节一致。 */
        val NONE = ToolGuardChain(emptyList())
    }
}
