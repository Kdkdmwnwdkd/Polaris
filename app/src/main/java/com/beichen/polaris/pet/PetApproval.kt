package com.beichen.polaris.pet

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.ApprovalGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 桌宠气泡上的审批通道（P1-3 第二阶段）。
 *
 * ## 为什么这件事必须做
 *
 * 第一阶段把 `ToolApproval? = null` 换成了 `Consent.Deny("桌宠气泡上没有审批按钮")`。
 * 那句话是**事实陈述**，不是设计定稿：桌宠的工具表因此被永久锁在「四个只读工具」，
 * 用户说「帮我打开微信」，模型只能回一句「我这个界面没法确认」。
 *
 * 这一层把那条事实变成历史：气泡上现在有确认卡，桌宠的 `consent` 从 [Consent.Deny]
 * 升到 [Consent.Ask]，`app_launch` 这类有副作用的工具才有资格进来。
 *
 * ## 状态为什么是 object 而不是参数传下去
 *
 * [PetState] 已经解释过一半理由（Service 比 Activity 活得久）。这里是另一半：
 * 审批卡必须能在**任何**界面状态下出现——气泡收起、菜单打开、正在朗读。如果它靠
 * 参数从宿主一层层传，那么「什么时候算展开」就变成了宿主的责任；而宿主在
 * `Service.onCreate` 里建窗口，那一帧还没有任何 UI 状态可言。
 *
 * 让卡片自己订阅一个进程级状态源，宿主只需要做一件事：看到 pending 就把气泡弹开。
 *
 * ## 与 [ApprovalGate] 的分工
 *
 * [ApprovalGate] 只管「等」——超时、取消、收场。这一层管「显示什么」和「谁在等谁」：
 * 它把工具调用翻译成一句人话（[Pending.summary]），并在等待结束后把卡片撤掉。
 * 分开是为了让「超时算拒绝」这类规则能被 harness 直接测，不用起模拟器。
 */
object PetApproval {

    private const val TAG = "PetApproval"

    /** 摘要最多这么长。卡片只有一行位置，超出的部分对「要不要点允许」没有帮助。 */
    private const val SUMMARY_MAX = 120

    /**
     * 一张待确认的卡。
     *
     * [tool] 用工具名而不是中文标题：工具名是模型与用户共同的词汇（用户看到的
     * 「app_launch」与模型调用的是同一个词），换成人话反而对不上。
     */
    data class Pending(
        val tool: String,
        val summary: String
    )

    private val gate = ApprovalGate(ApprovalGate.DEFAULT_TIMEOUT_MS)

    private val _pending = MutableStateFlow<Pending?>(null)

    /** 非空 = 界面该画卡片。宿主据此把气泡弹开。 */
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * 由 [com.beichen.polaris.core.Consent.Ask] 的 handler 调用：挂起直到用户点按钮。
     *
     * 返回值直接喂给 `Consent.decide`：`true` → Allowed，`false` → Denied。
     * 超时与取消都返回 `false`（按拒绝执行），但在日志里分开记——排查「为什么没执行」
     * 时，「用户点了拒绝」和「45 秒没人理」是两件完全不同的事。
     */
    suspend fun ask(tool: AgentTool, args: JsonObject): Boolean {
        _pending.value = Pending(tool.name, summarize(args))
        val outcome = try {
            gate.await()
        } finally {
            // 无论怎么收场都要撤卡：超时后卡片留在屏幕上，用户点它会得到一次
            // 「没有人在等」的空响应，那是比不显示更糟的体验。
            _pending.value = null
        }
        when (outcome) {
            ApprovalGate.Outcome.ALLOWED -> {
                AgentLogger.i(TAG, "用户允许执行 ${tool.name}")
                return true
            }
            ApprovalGate.Outcome.DENIED -> AgentLogger.i(TAG, "用户拒绝执行 ${tool.name}")
            ApprovalGate.Outcome.TIMEOUT ->
                AgentLogger.w(TAG, "审批超时（${ApprovalGate.DEFAULT_TIMEOUT_MS}ms），按拒绝执行 ${tool.name}")
            ApprovalGate.Outcome.CANCELLED ->
                AgentLogger.w(TAG, "审批被撤销（界面已销毁），按拒绝执行 ${tool.name}")
        }
        return false
    }

    /** 卡片上点「允许」。返回 false = 卡片已经不在等了（超时收场后的误触）。 */
    fun allow(): Boolean = gate.settle(true)

    /** 卡片上点「拒绝」。 */
    fun deny(): Boolean = gate.settle(false)

    /**
     * 悬浮窗服务销毁时调用：解除等待、按拒绝收场。
     *
     * 不这么做的话，Service 停了而协程还在等，那一轮会一直吊到 45 秒超时；
     * 期间用户看不到任何卡片，只会觉得「桌宠卡住了」。
     */
    fun onHostDestroyed() {
        _pending.value = null
        gate.cancelAll()
    }

    /** 悬浮窗服务重建时调用：允许下一次等待。与 [onHostDestroyed] 成对。 */
    fun onHostCreated() {
        gate.reopen()
    }

    /** 仅 harness / 诊断用：清空进程内状态，不碰任何持久化。 */
    fun reset() {
        _pending.value = null
        gate.reopen()
    }

    /**
     * 把工具参数压成一句能读的话。
     *
     * 优先取几个常见字段：它们本身就是「要做什么」的答案（打开哪个应用、跑什么命令）。
     * 都没有才退回整段 JSON —— 那通常是只读工具的参数（路径、关键词），
     * 截断到 [SUMMARY_MAX] 也够用户判断。
     */
    private fun summarize(args: JsonObject): String {
        val preferred = listOf("target", "command", "path", "query", "text", "name")
        for (key in preferred) {
            val value = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value.take(SUMMARY_MAX)
        }
        val raw = args.toString()
        return if (raw == "{}") "" else raw.take(SUMMARY_MAX)
    }
}
