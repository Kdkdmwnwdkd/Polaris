package com.beichen.polaris.core.agent

import com.beichen.polaris.core.io.AtomicJsonFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Agent 长任务的运行态持久化（B 批）。
 *
 * ## 要解决什么
 * Agent 跑长任务时，用户可能切后台、被系统回收、或手机重启。此时：
 * - 任务**确实中断**了（进程都没了，不可能自己续跑）；
 * - 但用户回来看到的应该是「上次在做什么、到哪一步了、怎么继续」，
 *   而不是一片空白 + 重新开始。
 *
 * 要的是**中断可以，但不能失忆**。
 *
 * ## 与 JobStore 的分工
 * - [com.beichen.polaris.core.job.JobStore] 管的是**后台 shell 命令**进程，
 *   有真正的 Process 对象，重启后进程已死，只能对账成 interrupted。
 * - 本文件管的是**Agent 对话回合**（LLM 多步工具调用），它跑在 App 进程内的协程里，
 *   进程死了同样没了——但我们可以把「进行到第几步、上下文是什么」记下来。
 *
 * ## 落盘格式
 * `<filesDir>/agent_run_state.json` —— 单文件，原子写（tmp + rename）。
 */
@Serializable
data class AgentRunState(
    /** 会话 id（对应 ConversationStore 的会话）。 */
    val sessionId: String,
    /** 最后一次用户输入的摘要（用于恢复界面提示「上次你让我做…」）。 */
    val prompt: String = "",
    /** 当前进行到第几步工具调用。 */
    val stepIndex: Int = 0,
    /** 状态：running（进行中）/ interrupted（被系统中断）/ done（已完成）。 */
    val status: String = STATUS_RUNNING,
    /** 开始时间戳。 */
    val startedAt: Long = 0L,
    /** 最后更新时间戳。 */
    val updatedAt: Long = 0L,
    /** 已完成的工具调用摘要（工具名列表，够用户看懂进度）。 */
    val doneTools: List<String> = emptyList(),
    /** 恢复提示：被中断时给用户的一句话。 */
    val note: String = "",
    /**
     * 已自动续跑过的次数（熔断用，见 [AutoResumePolicy]）。
     *
     * 默认 0 是**前向兼容**的落点：更早版本写下的状态没有这个字段，反序列化后即 0，
     * 语义正好是「还没自动续过」。反过来若给它一个非零默认值，老数据一读出来就
     * 直接撞上熔断上限，功能对老用户等于没做。
     */
    val autoResumeAttempts: Int = 0
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_INTERRUPTED = "interrupted"
        const val STATUS_DONE = "done"
    }
}

/**
 * 运行态存储：读写 [AgentRunState]。
 *
 * 纯文件操作、无 Android 依赖，可被 JVM harness 直接断言。
 */
class AgentRunStateStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        runCatching { file.parentFile?.mkdirs() }
    }

    /** 读当前状态；没有或损坏返回 null。 */
    fun load(): AgentRunState? = runCatching {
        // 主文件坏了回退 .bak：此处丢状态等于「忘了上次做到哪」，
        // 比丢一条会话更难受——用户会看到助手从第一步重新开始。
        val text = AtomicJsonFile.readWithBackupOrNull(file) { decodeOrNull(it) != null }
            ?: return null
        json.decodeFromString<AgentRunState>(text)
    }.getOrNull()

    /**
     * 写状态（原子：tmp → fsync → rename）。
     *
     * 这里**不再先 delete 主文件**：旧写法 `file.delete(); tmp.renameTo(file)` 一旦
     * rename 失败（存储满、只读挂载、外部存储异常），主文件已删、tmp 还在，
     * 下次启动读到的就是 null——等于把「有进度可续」变成「什么都没发生」。
     * rename 本身是原子覆盖，先删是纯粹的自伤。
     */
    fun save(state: AgentRunState) {
        AtomicJsonFile.write(file, json.encodeToString(state))
    }

    private fun decodeOrNull(text: String): AgentRunState? =
        runCatching { json.decodeFromString<AgentRunState>(text) }.getOrNull()

    /** 清空（任务正常结束后调用）。 */
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * 对账：进程启动时调用。
     *
     * 若磁盘上仍是 running，说明上次进程**没有正常结束**（被系统杀了 / 崩溃了），
     * 标记为 interrupted 并补一句恢复提示。
     *
     * @return 需要提示用户的中断状态；无中断返回 null
     */
    fun reconcile(): AgentRunState? {
        val state = load() ?: return null
        if (state.status != AgentRunState.STATUS_RUNNING) return null
        val updated = state.copy(
            status = AgentRunState.STATUS_INTERRUPTED,
            updatedAt = System.currentTimeMillis(),
            note = buildInterruptNote(state)
        )
        save(updated)
        return updated
    }

    /** 生成恢复提示文案。 */
    private fun buildInterruptNote(state: AgentRunState): String {
        val prompt = state.prompt.take(40)
        val tools = state.doneTools.takeLast(3).joinToString("、")
        return buildString {
            append("上次任务「")
            append(prompt.ifBlank { "未命名任务" })
            append("」被系统中断")
            if (state.stepIndex > 0) append("（已进行到第 ${state.stepIndex} 步")
            if (tools.isNotBlank()) append("，完成过 $tools")
            if (state.stepIndex > 0) append("）")
            append("。对话上下文仍在，可以直接说「继续」。")
        }
    }

    /**
     * 推进运行态：记一步工具完成（C 批）。
     *
     * B 批的 `stepIndex` / `doneTools` 只在初始化时被赋值，之后再没动过——
     * 于是中断提示永远显示「第 0 步」、完成过的工具永远为空，恢复信息形同虚设。
     * 这里把「进行到第几步、做过哪些工具」真正记下来。
     *
     * 只在状态为 running 时推进（已 done/interrupted 的不再记）。
     *
     * @param toolName 本次完成的工具名
     * @return 更新后的状态；无 running 状态返回 null
     */
    fun advance(toolName: String): AgentRunState? {
        val state = load() ?: return null
        if (state.status != AgentRunState.STATUS_RUNNING) return null
        val updated = state.copy(
            stepIndex = state.stepIndex + 1,
            doneTools = state.doneTools + toolName,
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }

    /**
     * 生成「续跑」提示：把中断的任务重新描述一遍，供下一轮作为上下文拼进去（C 批）。
     *
     * 与 [reconcile] 的 `note` 区别：
     * - `note` 是**给人看**的一句话（「上次被中断了，说继续」）；
     * - 本方法返回的是**给模型看**的结构化摘要（做了什么、做到哪、接着做什么）。
     *
     * @return 续跑上下文文本；无可续跑状态返回 null
     */
    fun buildResumeContext(): String? {
        val state = load() ?: return null
        if (state.status != AgentRunState.STATUS_INTERRUPTED) return null
        if (state.prompt.isBlank()) return null
        return buildString {
            appendLine("【上次任务被系统中断，以下是进度摘要】")
            appendLine("原始请求：${state.prompt.take(200)}")
            appendLine("已完成步骤数：${state.stepIndex}")
            if (state.doneTools.isNotEmpty()) {
                appendLine("已完成的工具调用：${state.doneTools.joinToString(" → ")}")
            }
            append("请从中断处继续，不要重复已完成的工作；如已完成全部要求，直接给出最终结论。")
        }
    }

    /**
     * 标记为「已续跑」：中断状态被消费掉，不应再次提示。
     *
     * @return 成功返回 true
     */
    fun markResumed(): Boolean = runCatching {
        val state = load() ?: return false
        if (state.status != AgentRunState.STATUS_INTERRUPTED) return false
        save(state.copy(status = AgentRunState.STATUS_RUNNING, note = "", updatedAt = System.currentTimeMillis()))
        true
    }.getOrDefault(false)

    /**
     * 记一次**自动**续跑尝试（熔断计数）。
     *
     * 只对 interrupted 生效：已经跑起来或已完成的任务不该再涨。
     * 这个计数不会自己清零，要等任务真正正常结束——那时 `save()` 会写一份新的
     * running 状态，调用方须显式把本字段带过去（见 `ChatViewModel.send` 里的
     * `priorState?.autoResumeAttempts`）。不带走的话，计数每轮复位，
     * 熔断形同虚设：一个「一跑就被杀」的任务会被无限自动续跑。
     *
     * @return 更新后的状态；无可续跑状态返回 null
     */
    fun markAutoResumeAttempt(): AgentRunState? {
        val state = load() ?: return null
        if (state.status != AgentRunState.STATUS_INTERRUPTED) return null
        val updated = state.copy(
            autoResumeAttempts = state.autoResumeAttempts + 1,
            updatedAt = System.currentTimeMillis()
        )
        save(updated)
        return updated
    }
}
