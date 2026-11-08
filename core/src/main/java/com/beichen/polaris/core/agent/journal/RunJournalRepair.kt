package com.beichen.polaris.core.agent.journal

/**
 * 崩溃尾修复（规格 M4）：把一份"断在回合中间"的日志补成自洽的。
 *
 * 要解决的问题很具体：进程在「工具已派发、结果未落盘」的窗口被系统杀掉。
 * 重进时历史里有一条 assistant 声明了调用，却没有对应结果——
 *  - 直接丢弃 → 历史不完整，模型下一轮看到悬空的 tool_call，供应商会拒；
 *  - 当成失败重放 → **写操作被执行两次**，这是数据损坏，不是体验问题。
 *
 * 所以修复只做一件事：**按日志事实判断"越没越过执行边界"，然后补一条带明确语义的结果**。
 * 越过了但没结果 = 结局未知；没越过 = 肯定没跑。
 *
 * 三条硬约束：
 *  1. 只追加，不修改任何既有事件（改动历史等于伪造证据）；
 *  2. 幂等——对已修复的日志再跑一次必须返回空（否则每次启动都在长日志）；
 *  3. 时间戳复用最后一条真实事件，不用当前时间（否则同样的日志每次修复结果不同，没法断言）。
 *
 * @see RunEvent 事件语义
 * @see RepairResult 修复产物与诊断信息
 */
object RunJournalRepair {

    /**
     * 扫描并生成补全事件。
     *
     * 时间复杂度 O(n)，空间 O(未应答调用数)。可在启动主线程调用（千级事件 < 1ms），
     * 但调用方仍应在 IO 线程读文件。
     *
     * @param events 已按 seq 升序读出的日志，允许尾部截断（半条 JSON 由读取层丢弃）。
     * @return 需要追加的事件（可能为空）+ 诊断信息。调用方负责写入，本函数不碰 IO。
     */
    fun repair(events: List<RunEvent>): RepairResult {
        if (events.isEmpty()) return RepairResult(emptyList(), null, emptyList(), emptyList())

        var openTurnId: String? = null
        var openStep: Int? = null

        // LinkedHashMap 保序：补结果时必须按模型声明的顺序，供应商对乱序 tool_result 会报错。
        val pending = LinkedHashMap<String, PendingCall>()
        val anomalies = mutableListOf<String>()

        for (event in events) {
            when (event) {
                is RunEvent.TurnOpened -> {
                    if (openTurnId != null) {
                        anomalies.add("回合 ${openTurnId} 未关闭就开了新回合 ${event.turnId}（seq=${event.seq}）")
                    }
                    openTurnId = event.turnId
                    openStep = null
                    pending.clear()
                }

                is RunEvent.TurnClosed -> {
                    // R7：只有**回合拥有者**的 CLOSE 才闭合回合。
                    // 子代理/技能不该有能力结束主回合——否则主回合会被提前判为已闭合，
                    // 未闭合的写操作就此不再补尾。
                    if (event.scope != RunScope.MAIN) {
                        anomalies.add("非拥有者作用域的回合结束事件被忽略（scope=${event.scope}，seq=${event.seq}）")
                    } else {
                        if (event.turnId != openTurnId) {
                            anomalies.add("回合结束事件与当前回合不匹配（seq=${event.seq}）")
                        }
                        openTurnId = null
                        openStep = null
                        pending.clear()
                    }
                }

                is RunEvent.StepClosed -> {
                    // R7：步收口只清**同作用域**的待应答调用。
                    //
                    // 子代理的一步收口不该把主代理尚未应答的调用清空——那会让一个
                    // 可能已生效的写操作被静默漏掉（数据损坏级别，不是显示问题）。
                    //
                    // 步已关闭却还有未应答调用，说明日志被截断/写坏过。
                    // 不在这里补——补了会写进一个已经结束的步，破坏"一调用一结果"的时序。
                    // 记下来，由上层决定是否上报诊断。
                    val closedScope = event.scope
                    val remaining = pending.filterValues { it.scope != closedScope }
                    val dropped = pending.size - remaining.size
                    if (dropped > 0) {
                        anomalies.add(
                            "步 ${event.step}（scope=$closedScope）关闭时仍有 $dropped 个未应答调用"
                        )
                    }
                    pending.clear()
                    pending.putAll(remaining)
                    if (closedScope == RunScope.MAIN) openStep = null
                }

                is RunEvent.AssistantCommitted -> {
                    // R7：只有回合拥有者的声明才登记「本步待应答的调用」并推进 openStep。
                    // 子代理的声明是**它自己**的待应答表，不能登记进主代理的表。
                    if (event.scope != RunScope.MAIN) {
                        anomalies.add("非拥有者作用域的调用声明被忽略（scope=${event.scope}，seq=${event.seq}）")
                    } else {
                        openStep = event.step
                        for (call in event.calls) {
                            pending[call.id] = PendingCall(
                                call.id, call.name, event.step, dispatched = false, scope = event.scope
                            )
                        }
                    }
                }

                is RunEvent.ToolDispatched -> {
                    // 「日志里出现过 ToolDispatched」就算越过边界。
                    //
                    // R7：这里**不能**用「pending 里有没有」来判断要不要登记——
                    // 技能脚本的调用没有 ASSIST（它不经过「模型声明调用」这一步，
                    // 脚本自己决定调什么），子代理的调用同理（派生句柄不写 ASSIST）。
                    // 但它们同样真的越过��执行边界、同样可能已生效。
                    //
                    // 改造前这里写的是 `pending[event.callId]?.dispatched = true`——
                    // 遇到没有声明的 DISP 就**整个忽略**。于是脚本写完文件、进程被杀，
                    // 恢复后判成「什么都没发生」，用户（或系统）重放一次 → 数据损坏。
                    //
                    // 现在把「有 DISP 无 ASSIST」的调用登记为**已派发**：
                    // 崩溃时按 OUTCOME_UNKNOWN 补尾。宁可多提醒一次核对，不可漏判副作用。
                    val existing = pending[event.callId]
                    if (existing != null) {
                        existing.dispatched = true
                    } else {
                        pending[event.callId] = PendingCall(
                            event.callId,
                            toolNameOf(event.callId),
                            event.step,
                            dispatched = true,
                            scope = event.scope
                        )
                    }
                }

                is RunEvent.ToolSettled -> {
                    pending.remove(event.callId)
                }
            }
        }

        val turnId = openTurnId ?: return RepairResult(emptyList(), null, emptyList(), anomalies)
        val last = events.last()

        // 合成事件的 seq 接在最后一条之后；时间复用最后一条，任何输入都得到同一份输出。
        var nextSeq = last.seq + 1
        val closers = mutableListOf<RunEvent>()
        val orphans = mutableListOf<OrphanCall>()

        for (call in pending.values) {
            val status = if (call.dispatched) {
                ToolSettleStatus.OUTCOME_UNKNOWN
            } else {
                ToolSettleStatus.NOT_DISPATCHED
            }
            orphans.add(OrphanCall(call.callId, call.toolName, call.step, status))
            closers.add(
                RunEvent.ToolSettled(
                    seq = nextSeq++,
                    atMs = last.atMs,
                    turnId = turnId,
                    step = call.step,
                    callId = call.callId,
                    status = status,
                    errorCode = if (call.dispatched) CODE_OUTCOME_UNKNOWN else CODE_NOT_DISPATCHED,
                    // R7：补的终态要带原调用的作用域，否则读日志时一条 skill scope
                    // 的调用配上 main scope 的终态，配对关系就断了。
                    scope = call.scope
                )
            )
        }

        // 先关步再关回合：顺序反了会让读取方看到"回合已结束但步还开着"。
        if (openStep != null) {
            closers.add(RunEvent.StepClosed(nextSeq++, last.atMs, turnId, openStep))
        }
        closers.add(RunEvent.TurnClosed(nextSeq, last.atMs, turnId, REASON_INTERRUPTED))

        return RepairResult(closers, turnId, orphans, anomalies)
    }

    /** 日志里有声明、但恢复时发现没有结果。用于生成回喂模型的 ToolMessage。 */
    data class OrphanCall(
        val callId: String,
        val toolName: String,
        val step: Int,
        val status: ToolSettleStatus
    ) {
        /** 结局未知 = 可能已生效，必须让模型先核对；未派发 = 可以放心重试。 */
        val mayHaveTakenEffect: Boolean get() = status == ToolSettleStatus.OUTCOME_UNKNOWN
    }

    /**
     * @property closers 待追加事件，空表示日志本就平衡（幂等的判据）。
     * @property openTurnId 被修复的回合，null 表示无需修复。
     * @property orphans 未应答调用清单，供上层按工具元数据生成文案。
     * @property anomalies 日志自身的不一致，不影响修复，但应进诊断导出。
     */
    data class RepairResult(
        val closers: List<RunEvent>,
        val openTurnId: String?,
        val orphans: List<OrphanCall>,
        val anomalies: List<String>
    ) {
        val repaired: Boolean get() = closers.isNotEmpty()
    }

    private class PendingCall(
        val callId: String,
        val toolName: String,
        val step: Int,
        var dispatched: Boolean,
        /** R7：所属作用域。步收口只清同作用域的调用。 */
        val scope: String = RunScope.MAIN
    )

    /**
     * 从 callId 反推工具名（R7）。
     *
     * 主代理的 callId 由模型给（不透明），工具名来自 ASSIST 事件的声明；
     * 但技能脚本的 callId 是我们生成的 `skill:<技能id>:<工具名>#<序号>`，
     * 能在没有 ASSIST 的情况下把工具名还原出来——恢复提示里能说清是哪个工具，
     * 而不是只给一个不透明的 id。
     *
     * 认不出来时返回空串（恢复提示会退化成只报 id，仍比漏判好）。
     */
    private fun toolNameOf(callId: String): String {
        if (!callId.startsWith(RunScope.SKILL_PREFIX)) return ""
        val rest = callId.removePrefix(RunScope.SKILL_PREFIX)
        val tool = rest.substringAfter(':', "")
            .substringBefore('#')
        return tool
    }

    const val CODE_OUTCOME_UNKNOWN = "journal_outcome_unknown"
    const val CODE_NOT_DISPATCHED = "journal_not_dispatched"
    const val REASON_INTERRUPTED = "interrupted"
}
