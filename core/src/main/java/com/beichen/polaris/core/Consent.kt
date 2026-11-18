package com.beichen.polaris.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * P1-3：审批的**契约**，不是一个可以为空的回调。
 *
 * ## 修的是什么
 *
 * 修前四个构造点（`RealAgentLoop` / `SubAgentRunner` / `RegistryToolExecutor` /
 * `SkillExecutor.buildToolExecutor`）的参数都是 `ToolApproval? = null`。`null` 有两个
 * 完全不同的含义被挤在同一个值里：
 *
 *  - 「这条通道**没有弹窗位置**」——桌宠、后台任务，是设计；
 *  - 「**忘了传**」——是 bug。
 *
 * 审计 F4 之前，两者的行为都是「不弹窗、直接执行」。F4 把运行期改成了拒绝，堵住了
 * 安全洞，但 `null` 仍然是一个**沉默的**值：读者无法从类型上判断这个 `null` 是
 * 有意设计还是疏忽。
 *
 * ## 修法：把「没有能力」变成一个必须写出来的值
 *
 * [Deny] 不是 `null` 的同义词，它要求写的人给出**理由**。这条理由会原样进入工具结果，
 * 于是「为什么这次操作没执行」不再是一个需要翻代码才能回答的问题——用户和模型都能看见。
 *
 * ```kotlin
 * // 桌宠：气泡上没有放按钮的位置，这是设计，不是疏忽
 * consent = Consent.Deny("桌宠气泡上没有审批按钮")
 * ```
 *
 * ## 为什么「怎么裁决」也放在这里，而不是执行器里
 *
 * 原先把 try/catch 写在 `RegistryToolExecutor` 里，带来两个后果：
 *
 *  1. 语义散落——「取消不算拒绝」这条规则只有执行器知道，别的地方想复用就得抄一遍；
 *  2. 测不了——`RegistryToolExecutor` 牵 `ToolRegistry`，后者用 `JsonSchema.serializer()`，
 *     需要序列化编译器插件。行为断言在没装插件的机器上编不过 → 被 SKIP → 静默消失。
 *     （这与 `McpServerHost` 当初绕开 `ToolRegistry` 是同一个教训。）
 *
 * [decide] 只依赖 `AgentTool` 与 `JsonObject`，两个源文件就能真编真跑。
 * 执行器退化成一层 `when`，不再拥有任何审批语义。
 *
 * ## 为什么默认值仍然是「拒绝」而不是「必须传」
 *
 * 全仓 96 处 `RealAgentLoop(` 与 11 处 `RegistryToolExecutor(` 构造点，绝大多数是
 * harness 里的「我只想测工具执行，不测审批」。把它们逐一改成必填，得到的是 107 行
 * 噪声，而**判别力为零**——每个 harness 都会随手传一个 `Deny` 让编译过去。
 *
 * 所以三层各管一件事：
 *
 *  - **类型层**：`null` 不可表达，`Deny` 必须带理由 —— 写的人必须表态；
 *  - **默认值**：忘记表态时退到 [unset]，方向是**关**，不是开（与审计 F4 同一条纪律）；
 *  - **门禁层**：`run-consent-verification.sh` 静态断言四个**生产入口**都显式声明了
 *    consent —— 真正需要判别力的地方只有这四处，门禁盯这四处就够了。
 *
 * 三层合起来，「忘记传」的后果是**功能不可用且原因可见**，不是**静默放行**。
 */
sealed interface Consent {

    /**
     * 有弹窗通道：问过用户才执行。
     *
     * [handler] 返回 `true` 表示放行。它抛出的异常一律按「拒绝」处理，唯一例外是
     * [CancellationException]——取消是控制流，必须上抛，否则用户点了「停止」会被
     * 记成「用户拒绝了这个操作」。
     */
    class Ask(
        val handler: suspend (tool: AgentTool, args: JsonObject) -> Boolean
    ) : Consent

    /**
     * 本通道明确没有审批界面：`needsApprovalFor` 为真的调用**一律拒绝**。
     *
     * [reason] 是给人看的一句话，会拼进工具结果。写「谁没有能力」，不要写「操作被拒绝」
     * ——后者是结果，前者才是用户需要知道的。
     */
    data class Deny(val reason: String) : Consent

    /**
     * 对一次「需要审批」的调用给出裁决。
     *
     * 纯逻辑，不碰工具执行、不碰日志：调用方拿到 [Decision] 后自己决定怎么记账。
     * 这样「问不问、怎么算拒绝」只有一处实现，执行器与将来的桌宠气泡、后台策略
     * 共用同一份判定。
     */
    suspend fun decide(tool: AgentTool, args: JsonObject): Decision = when (this) {
        is Deny -> Decision.Unavailable(reason)
        is Ask -> try {
            if (handler(tool, args)) Decision.Allowed else Decision.Denied
        } catch (cancel: CancellationException) {
            // 取消不是拒绝：交给调用方的结构化并发去闭合。
            throw cancel
        } catch (e: Throwable) {
            // 审批回调自己坏了（弹窗已销毁、Compose 作用域已取消……）。
            // 按拒绝处理：宁可让一次调用失败，也不能在没有用户确认的情况下执行。
            Decision.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** [decide] 的四种结果。刻意不是布尔——布尔会把「没问成」和「问了被拒」混成一个 `false`。 */
    sealed interface Decision {

        /** 问了，用户允许。 */
        data object Allowed : Decision

        /** 问了，用户拒绝。 */
        data object Denied : Decision

        /** 没问成：这条通道没有审批界面。 */
        data class Unavailable(val reason: String) : Decision

        /** 想问了，但审批回调自己抛了异常。与 [Denied] 分开，因为日志措辞不同。 */
        data class Failed(val cause: String) : Decision
    }

    companion object {

        /** 有审批通道的简写，省掉 `Consent.Ask { … }` 的嵌套。 */
        fun ask(handler: suspend (tool: AgentTool, args: JsonObject) -> Boolean): Consent =
            Ask(handler)

        /**
         * 默认值：没有表态的通道。
         *
         * 参数用通道名而不是泛泛的「未配置」——日志里要能看出**是哪一层**忘了传，
         * 否则四个构造点会互相推诿。
         */
        fun unset(channel: String): Consent =
            Deny("执行通道「$channel」没有声明审批能力")
    }
}
