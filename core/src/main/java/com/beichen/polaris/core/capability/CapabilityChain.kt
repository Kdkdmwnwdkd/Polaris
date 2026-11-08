package com.beichen.polaris.core.capability

import com.beichen.polaris.core.AgentLogger

/*
 * Phase 2 · DoD⑤「权限/能力申请一次走完降级链」——统一「能力链」抽象（核心，零 Android 依赖）。
 *
 * ## 解决的问题
 * Agent 要执行一个动作时，往往有**多条可用执行路径**（例如「跑一条 shell 命令」：proot 容器
 * → 本机 user shell；「操作屏幕」：无障碍服务 → 调试通道（Shizuku））。此前这些路径的可用性判断
 * 散落在各处，用户在界面上一级级试、一级级被拒，体验断裂。
 *
 * 本文件把这件事收敛成一个**纯函数**：
 *   输入 = 「想要的能力（[Capability]）」 + 「当前各后端可用性快照（[List]<[BackendAvailability]>）」
 *   输出 = [CapabilityResolution]（[CapabilityResolution.Selected] 选中的执行路径，或
 *          [CapabilityResolution.Unavailable] 全链不可用时的最终文案 + 可操作提示）
 * 并附带**降级轨迹**（[TraceEntry]：每一级候选名 + 可用性 + 原因），供日志诊断。
 *
 * ## 纯函数化（可脱离 Android 断言）
 * [resolveCapability] 不做任何 IO、不读全局状态、不抛异常；给定同样的 [CapabilityRequest]
 * 恒返回相等的结果。Android 侧的探测（proot 三件套 / rootfs / 无障碍状态）一律在**调用方**
 * 先转成 [BackendAvailability] 快照，故本文件可在 JVM harness 里直接验证（见
 * scripts/qa/FallbackVerification.kt）。与已有骨架的对接（复用 T07 工厂、AutomationBridge）
 * 见 [CapabilityBridges.kt]。
 *
 * ## 与「诚实降级」一致（T02 hint 语义）
 * 全链不可用时**绝不静默假装成功**：返回明确 `message`（含每一级不可用原因）+ 至少一条
 * 可操作 `hint`（具体到下一步该做什么），与 tools/AppControlTools.kt 里 app_stop 的
 * 诚实降级一脉相承。
 */

/** 日志标签（降级轨迹逐级落日志，便于真机诊断）。 */
private const val TAG = "CapabilityChain"

/**
 * 全链不可用且候选未自带提示时的**兜底引导**（保证终态 `hint` 恒非空）。
 */
const val CAPABILITY_FALLBACK_HINT: String =
    "当前没有任何可用的执行路径。请检查设备环境（如容器页是否已安装 rootfs、无障碍/自动化是否已开启）后重试。"

/**
 * Agent 想做的一件事（能力维度）。
 *
 * 每条能力在 [CapabilityBridges] 里绑定了**有序候选执行路径**；候选顺序即降级优先级
 * （越靠前越优先，不可用才回落下一级）。
 *
 * @param wire 对外线格式标识（日志 / 诊断用）
 * @param title 简体中文展示名（拼装文案用）
 */
enum class Capability(val wire: String, val title: String) {

    /** 执行一条 shell 命令：候选 = proot 容器 → 本机 user shell。 */
    RUN_SHELL("run_shell", "执行 shell 命令"),

    /**
     * 操作手机界面：候选 = 无障碍服务 → 调试通道（Shizuku）。
     *
     * W-50：原先只挂无障碍一条候选，等于「没开无障碍 = 界面类工具全灭」，而 shell（uid 2000）
     * 明明能做 `input tap/swipe/text/keyevent`、`uiautomator dump`、`screencap`。现在无障碍
     * 仍是首选（实时、免 dump），调试通道作为兜底——只开无线调试也能干活，只是读树慢 1~2 秒。
     */
    CONTROL_SCREEN("control_screen", "操作手机界面")
}

/**
 * 一条候选执行路径的**可用性快照**（纯数据，由调用方探测后填入）。
 *
 * @param backendId 后端稳定键（如 `proot` / `user` / `accessibility`），用于匹配与断言
 * @param label 简体中文展示名（如「proot 容器」「本机 user shell」「无障碍服务」）
 * @param available 当前是否可用
 * @param reason 不可用时**为何**不可用（可用时为 null；文案面向诊断）
 * @param hint 不可用时的**可操作提示**（具体到下一步动作；可用时为 null）
 */
data class BackendAvailability(
    val backendId: String,
    val label: String,
    val available: Boolean,
    val reason: String? = null,
    val hint: String? = null
)

/**
 * 降级轨迹里的一条：第 N 级候选被考察到的结果（候选名 + 可用性 + 原因）。
 *
 * @param backendId 候选后端键
 * @param label 候选展示名
 * @param available 该级是否可用（命中即止：轨迹最后一条恒为所选级）
 * @param reason 该级不可用时的原因（可用时为 null）
 */
data class TraceEntry(
    val backendId: String,
    val label: String,
    val available: Boolean,
    val reason: String? = null
)

/**
 * 能力链输入：想要的能力 + 各后端可用性快照（**有序**，顺序即降级优先级）。
 */
data class CapabilityRequest(
    val capability: Capability,
    val candidates: List<BackendAvailability>
)

/**
 * 能力链输出。
 *
 * @param capability 本次求解的能力
 * @param trace 降级轨迹（按候选顺序累计；[Selected] 时最后一条即所选级，
 *   [Unavailable] 时含全部候选级）
 * @param message 主文案（成功 = 选中说明；失败 = 明确原因，含各级不可用原因）
 * @param hint 失败时的可操作提示（成功时为 null）；失败态**恒非空**
 */
sealed interface CapabilityResolution {

    val capability: Capability
    val trace: List<TraceEntry>
    val message: String
    val hint: String?

    /**
     * 已选中一条执行路径。
     *
     * @param backend 被选中的那条候选（其 [BackendAvailability.available] 恒为 true）
     */
    data class Selected(
        override val capability: Capability,
        val backend: BackendAvailability,
        override val trace: List<TraceEntry>,
        override val message: String,
        override val hint: String? = null
    ) : CapabilityResolution

    /**
     * 全链不可用（含空候选链）：终态失败，必须给出明确原因 + 可操作提示。
     */
    data class Unavailable(
        override val capability: Capability,
        override val trace: List<TraceEntry>,
        override val message: String,
        override val hint: String
    ) : CapabilityResolution
}

/**
 * **能力链求解（纯函数）**：按候选顺序逐级考察，选中**首个可用**的路径。
 *
 * 语义（与 DoD⑤ 验收一致）：
 *  1. 第 1 级可用 → 直接选中，轨迹**只含 1 条**；
 *  2. 第 1 级不可用、第 2 级可用 → 选中第 2 级，轨迹含 2 条（含第 1 级不可用原因）；
 *  3. 全链不可用 → [CapabilityResolution.Unavailable]，`message` 列出每一级原因，
 *     `hint` 汇总各级可操作提示（无提示则用 [CAPABILITY_FALLBACK_HINT]），**不抛异常**；
 *  4. 空候选链 → 同样返回 [CapabilityResolution.Unavailable]（兜底提示），**不崩**。
 *
 * 纯函数：不做 IO、不读全局状态、不抛异常；同样入参结果恒相等。
 *
 * @param request 能力链输入
 * @return [CapabilityResolution]
 */
fun resolveCapability(request: CapabilityRequest): CapabilityResolution {
    val capability = request.capability
    val candidates = request.candidates

    // 空候选链：兜底提示，绝不抛（与全链不可用同一失败语义）
    if (candidates.isEmpty()) {
        return CapabilityResolution.Unavailable(
            capability = capability,
            trace = emptyList(),
            message = "无法${capability.title}：当前没有注册任何可用的执行路径。",
            hint = CAPABILITY_FALLBACK_HINT
        )
    }

    val trace = ArrayList<TraceEntry>(candidates.size)
    for (candidate in candidates) {
        trace += TraceEntry(
            backendId = candidate.backendId,
            label = candidate.label,
            available = candidate.available,
            reason = candidate.reason
        )
        if (candidate.available) {
            // 命中即止：轨迹最后一条就是所选级
            return CapabilityResolution.Selected(
                capability = capability,
                backend = candidate,
                trace = trace.toList(),
                message = "已选择执行路径「${candidate.label}」（${capability.title}）。"
            )
        }
    }

    // 全链不可用：列出每一级原因 + 汇总可操作提示
    val reasons = trace.joinToString("；") { entry ->
        "${entry.label}：${entry.reason?.takeIf { it.isNotBlank() } ?: "不可用"}"
    }
    return CapabilityResolution.Unavailable(
        capability = capability,
        trace = trace.toList(),
        message = "无法${capability.title}：所有执行路径均不可用（$reasons）。",
        hint = synthesizeHint(candidates)
    )
}

/**
 * 汇总候选的可操作提示：取各级 [BackendAvailability.hint]（去空）用「；」拼接；
 * 一条都没有时回退 [CAPABILITY_FALLBACK_HINT]，保证失败态 `hint` 恒非空。
 */
private fun synthesizeHint(candidates: List<BackendAvailability>): String {
    val hints = candidates.mapNotNull { it.hint?.takeIf { h -> h.isNotBlank() } }
    return if (hints.isEmpty()) CAPABILITY_FALLBACK_HINT else hints.joinToString("；")
}

/**
 * 把降级轨迹逐级写入日志（每级一行：候选名 + 可用性 + 原因），并在末尾记一条裁决行。
 *
 * 与 [resolveCapability] 的**纯函数**属性解耦：求解本身不改任何状态；可观测性由此独立完成，
 * 调用方（ViewModel / 工具层）在拿到结果后调用本方法即可。异常安全（日志只是旁路）。
 */
fun CapabilityResolution.logTrace() {
    trace.forEach { entry ->
        AgentLogger.i(
            TAG,
            "[${capability.wire}] 候选「${entry.label}」可用=${entry.available}" +
                (entry.reason?.takeIf { it.isNotBlank() }?.let { "，原因=$it" } ?: "")
        )
    }
    when (this) {
        is CapabilityResolution.Selected -> {
            // W-61：原来是 `降级 ${trace.size} 级`——**off by one**。trace 从首候选一直列到
            // 选中项，选中首选时 trace.size 已经是 1，于是日志写成「降级 1 级」；选中第 2 档
            // 写成「降级 2 级」。真机日志里 [run_shell] 明明命中的是首选 Shizuku，却显示
            // 「降级 1 级」，读日志时会被误导成「有更优候选但没选它」。
            val degraded = (trace.size - 1).coerceAtLeast(0)
            AgentLogger.i(
                TAG,
                "[${capability.wire}] 选中「${backend.label}」（" +
                    (if (degraded == 0) "首选" else "降级 $degraded 级") + "）"
            )
        }
        is CapabilityResolution.Unavailable ->
            AgentLogger.w(TAG, "[${capability.wire}] 全链不可用：$message")
    }
}
