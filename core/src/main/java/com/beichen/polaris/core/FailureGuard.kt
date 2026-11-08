package com.beichen.polaris.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个调用指纹的失败状态（T04）：同一指纹最多真跑 N 次，超了直接熔断。
 *
 * @property executions 已**真实执行**次数（被熔断拦截的调用不计）。成功会清指纹
 *   （见 [FailureGuard.onExecuted]），故任何存活状态中 executions 恒等于 failures。
 * @property failures 其中失败次数。成功即清指纹（见 [FailureGuard.onExecuted]）。
 */
data class FailureState(
    val executions: Int = 0,
    val failures: Int = 0
) {
    /**
     * 同一调用已失败 ≥2 次：要求模型换参数/换工具再试（requires_changed_call 判据）。
     *
     * 取 ≥2 而非 ≥1：给模型一次「原样重试」的机会（瞬时故障可自愈），
     * 但第 3 次同参调用起直接熔断——与验收「第 3 次不再真执行」对齐。
     */
    val requiresChangedCall: Boolean get() = failures >= 2
}

/** 熔断判定结果（纯数据，便于单测/纯函数级验证）。 */
sealed interface GuardVerdict {
    /** 放行：可以真实执行。 */
    object Allow : GuardVerdict

    /**
     * 拦截：不真执行，直接把错误结果回喂模型。
     * @property code 机器可读码（"duplicate_in_batch" / "requires_changed_call"）。
     * @property message 回喂模型的失败主文本。
     * @property hint 修复指引（教模型改参数/换工具）。
     */
    data class Blocked(
        val code: String,
        val message: String,
        val hint: String
    ) : GuardVerdict
}

/**
 * 瞬时故障码集合（W-8）：**基础设施未就绪**类失败——不是「参数错了」，而是「环境还没准备好」。
 *
 * 真机日志（23:56-23:58）根因：无障碍服务绑定前 ui_tree 连续两次 `service_not_ready`，
 * 恒定指纹（ui_tree 无参数）的失败计数跨用户回合永久存活，服务就绪后第 3 次调用起
 * 仍被熔断挡死——模型「有眼（截图）无手（ui_tree 全挡）」，任务必然失败。
 * 熔断的本意是阻止「同参打转」，这类故障重试即可自愈，计入失败纯属误伤。
 */
internal val TRANSIENT_FAILURE_CODES: Set<String> = setOf(
    "service_not_ready",   // 自动化桥未就绪（无障碍服务绑定中/用户尚未授权）——就绪后可自愈
    "service_unavailable", // 系统能力缺失（如 UsageStatsManager 不支持）——非参数错误
)

/**
 * 瞬时故障判据（纯函数，JVM harness 可断言）：
 * 返回 true 时该次失败**不计入**熔断计数——见 [FailureGuard.onExecuted] 的 transient 形参。
 */
internal fun isTransientFailureCode(code: String): Boolean = code in TRANSIENT_FAILURE_CODES

/**
 * 失败熔断 + 调用指纹去重（T04）。
 *
 * 判据（命中任一即 [GuardVerdict.Blocked]，不走真实执行）：
 *  1. **同批重复**：同一批次内出现完全相同的调用（同名同参）——第二个起拦截；
 *  2. **requiresChangedCall**：同一调用历史失败 ≥2 次（[FailureState.requiresChangedCall]）。
 *
 * 生命周期（W-8 修订）：
 *  - **回合作用域**：[RealAgentLoop.send] 入口调 [resetForTurn] 清空——同一用户回合内
 *    跨批次保留（防模型同参打转的本意不变），跨回合/跨会话不继承（修掉真机日志里
 *    「上个回合两次 service_not_ready 毒死下个回合全部 ui_tree」的污染）；
 *  - **回合内**：执行后调 [onExecuted]——成功**清指纹**（同一调用再次出现时重新放行），
 *    失败累加 [FailureState.executions] / [FailureState.failures]（失败时二者同步 +1，
 *    故存活状态中恒相等；不存在只按执行次数单独触发的判据），瞬时故障（transient=true）
 *    三不（不计数、不清除、不拦截）。
 * 被拦截的调用不更新状态：模型必须改参数/换工具才能解锁。
 */
class FailureGuard {

    // T03 并行批：多个只读工具在 async 内并发 check/onExecuted（不同指纹键），
    // 裸 HashMap 并发 put 会丢键/resize 损坏（QA 已复现 199/200、166/200）。
    // FailureState 为不可变 data class 且每键单写者（同批重复已被 duplicate_in_batch 拦截），
    // 故换 ConcurrentHashMap 即足够：put/get/remove 均原子，无需额外同步。
    private val states = ConcurrentHashMap<String, FailureState>()

    /** 当前指纹状态（不存在则空态）。供日志与测试观测。 */
    fun state(fingerprint: String): FailureState = states[fingerprint] ?: FailureState()

    /**
     * 调用指纹 = hash(name + normalizedArgs)（FNV-1a 64bit，纯函数、无加密依赖）。
     * 参数先归一化（键排序、字符串去引号）——`{"a":"1","b":2}` 与 `{"b":2,"a":"1"}` 同指纹。
     */
    fun fingerprint(name: String, args: JsonObject): String =
        fnv1a64(name + "\u0000" + normalizeArgs(args))

    /**
     * 执行前熔断判定。
     *
     * @param fingerprint [fingerprint] 的返回值。
     * @param duplicateInBatch 同一批次内更早时刻是否已出现过同指纹调用。
     */
    fun check(fingerprint: String, duplicateInBatch: Boolean): GuardVerdict {
        if (duplicateInBatch) {
            return GuardVerdict.Blocked(
                code = "duplicate_in_batch",
                message = "同一批次内已包含完全相同的工具调用（同名同参），重复执行不会产生新信息。",
                hint = "请合并批次内的重复调用；如确需再次执行，请修改参数或在下一轮单独调用。"
            )
        }
        val st = states[fingerprint] ?: return GuardVerdict.Allow
        if (st.requiresChangedCall) {
            return GuardVerdict.Blocked(
                code = "requires_changed_call",
                message = "同一调用已失败 ${st.failures} 次，请修改参数。",
                hint = "上次相同参数以失败告终，请改变参数或换一个工具后重试。"
            )
        }
        return GuardVerdict.Allow
    }

    /**
     * 一次真实执行结束后的状态更新。
     *
     * @param fingerprint [fingerprint] 的返回值。
     * @param failed 本次执行是否失败（以回喂模型的 isError 为准）。
     * @param transient 是否为瞬时故障（基础设施未就绪，判据见 [isTransientFailureCode]）。
     *   true 时**三不**：不计数、不清指纹、不影响后续判定——环境就绪后原样重试应被放行。
     *   修前形态把 service_not_ready 等计入失败，恒定指纹（如无参 ui_tree）被毒化后
     *   跨回合永久拦截（真机日志 23:58：服务 23:57:47 已就绪，ui_tree 仍被挡死）。
     */
    fun onExecuted(fingerprint: String, failed: Boolean, transient: Boolean = false) {
        if (transient) return
        val cur = states[fingerprint] ?: FailureState()
        if (failed) {
            states[fingerprint] = cur.copy(
                executions = cur.executions + 1,
                failures = cur.failures + 1
            )
        } else {
            // 成功清指纹：调用已被证明可工作，允许后续原样重试
            states.remove(fingerprint)
        }
    }

    /**
     * 回合作用域重置（W-8）：每个用户回合（[RealAgentLoop.send]）入口调用。
     *
     * 修前形态：failureGuard 随 loop 活在 ViewModel 上，全仓无人 clear——失败指纹
     * 跨回合、跨会话（含 newConversation/switchConversation）永久存活，只有杀 App 能清。
     * 回合内打转防护不受影响（回合内不清），跨回合污染消除。
     */
    fun resetForTurn() {
        states.clear()
    }

    /** 清空全部状态（切会话/重置时用）。 */
    fun clear() {
        states.clear()
    }
}

/**
 * 参数归一化：键排序 + 字符串去引号 + 标类型前缀（s:/n:）。
 * 保证语义等价的 JSON 产出同字符串，进而同指纹；同时保持纯函数确定性。
 */
internal fun normalizeArgs(args: JsonObject): String {
    if (args.isEmpty()) return "{}"
    return args.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + normalizeElement(v) }
}

private fun normalizeElement(el: JsonElement): String = when (el) {
    is JsonPrimitive -> if (el.isString) "s:${el.content}" else "n:${el.content}"
    is JsonObject -> normalizeArgs(el)
    is JsonArray -> "[" + el.joinToString(",") { normalizeElement(it) } + "]"
}

/**
 * FNV-1a 64bit 哈希（纯函数实现，不依赖 java.security）。
 * 输出固定 16 位小写十六进制，仅用于指纹去重，不承担安全职责。
 */
internal fun fnv1a64(input: String): String {
    var hash = 0xcbf29ce484222325UL
    val prime = 0x100000001b3UL
    for (c in input) {
        hash = hash xor c.code.toULong()
        hash *= prime
    }
    val hex = hash.toString(16)
    return hex.padStart(16, '0')
}
