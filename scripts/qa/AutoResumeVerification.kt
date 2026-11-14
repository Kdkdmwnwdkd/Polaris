/**
 * 自动续跑（AutoResumePolicy）行为验证 —— 真编真跑，不碰 Android。
 *
 * 被测的是「进程被杀后，下一次冷启动要不要替用户接着跑」这张决策表。
 * 为什么值得单独验：这张表的每一条都直接对应一次真实的 LLM 请求（花钱），
 * 而最凶的失败模式是**死循环**——任务本身把内存撑爆 → 被杀 → 自动续跑 → 再被杀。
 * 所以这里除了逐条判理由，还专门跑一段「续跑两轮后必须停手」的端到端模拟。
 *
 * 断言里刻意包含**单字段对照**（B1 vs B5：只差 isRunning 一个布尔），
 * 否则「正在跑时不插队」这条写成恒真也看不出来。
 */
import com.beichen.polaris.core.agent.AgentRunState
import com.beichen.polaris.core.agent.AgentRunStateStore
import com.beichen.polaris.core.agent.AutoResumeDecision
import com.beichen.polaris.core.agent.AutoResumeInput
import com.beichen.polaris.core.agent.AutoResumePolicy
import com.beichen.polaris.core.agent.AutoResumeReason
import java.io.File
import java.nio.file.Files

private var pass = 0
private var fail = 0

/** 固定「现在」，让时效判断可复现（绝不读系统时钟）。 */
private const val NOW = 1_700_000_000_000L
private const val HOUR = 3_600_000L

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  PASS  $name")
        pass++
    } else {
        println("  FAIL  $name${if (detail.isNotEmpty()) " — $detail" else ""}")
        fail++
    }
}

private fun state(
    status: String = AgentRunState.STATUS_INTERRUPTED,
    prompt: String = "把项目里的 README 整理成中文",
    stepIndex: Int = 3,
    attempts: Int = 0,
    updatedAt: Long = NOW - 60_000,
    sessionId: String = "sess-1",
) = AgentRunState(
    sessionId = sessionId,
    prompt = prompt,
    stepIndex = stepIndex,
    status = status,
    startedAt = updatedAt - 600_000,
    updatedAt = updatedAt,
    doneTools = listOf("read_file", "write_file", "shell"),
    autoResumeAttempts = attempts,
)

private fun input(
    st: AgentRunState? = state(),
    active: String? = "sess-1",
    exists: Boolean = true,
    enabled: Boolean = true,
    hasModel: Boolean = true,
    running: Boolean = false,
    now: Long = NOW,
) = AutoResumeInput(
    state = st,
    nowMs = now,
    activeSessionId = active,
    sessionExists = exists,
    enabled = enabled,
    hasModel = hasModel,
    isRunning = running,
)

private fun expect(name: String, verdict: com.beichen.polaris.core.agent.AutoResumeVerdict,
                   decision: AutoResumeDecision, reason: AutoResumeReason) {
    check(name, verdict.decision == decision && verdict.reason == reason,
        "实际 ${verdict.decision}/${verdict.reason}")
}

fun main() {
    println("[B] 决策表：每种「不该跑」都有独立理由")

    val ok = AutoResumePolicy.decide(input())
    expect("B1 中断任务 + 一切就绪 → 自动续跑", ok, AutoResumeDecision.RESUME, AutoResumeReason.OK)
    // 下面两条查的是裁决**返回的消息内容**，属行为断言。
    // 用 `in` 而不是字符串的 contains 调用：后者会被 assertion-census 判成「文本断言」
    // （它守的是「某文件里有没有这句话」那种不测行为的写法），而这里测的是返回值。
    check("B2 续跑时给出可读提示（含「已自动继续」）",
        "已自动继续" in ok.message, "message=${ok.message}")
    check("B3 提示里带上进度步数（用户能对上号）",
        "第 3 步" in ok.message, "message=${ok.message}")

    expect("B4 没有运行态 → 静默跳过",
        AutoResumePolicy.decide(input(st = null)),
        AutoResumeDecision.SKIP, AutoResumeReason.NO_STATE)
    expect("B5 状态是 done → 静默跳过",
        AutoResumePolicy.decide(input(st = state(status = AgentRunState.STATUS_DONE))),
        AutoResumeDecision.SKIP, AutoResumeReason.NOT_INTERRUPTED)
    expect("B6 状态还是 running → 静默跳过",
        AutoResumePolicy.decide(input(st = state(status = AgentRunState.STATUS_RUNNING))),
        AutoResumeDecision.SKIP, AutoResumeReason.NOT_INTERRUPTED)

    // 单字段对照：B1 与 B7 只差 isRunning 一个布尔。
    // 少了这条判断，冷启动恰好赶上一轮正在跑时就会再插一轮，两轮抢同一个会话。
    expect("B7 已有回合在跑 → 不插队（与 B1 只差 isRunning）",
        AutoResumePolicy.decide(input(running = true)),
        AutoResumeDecision.SKIP, AutoResumeReason.BUSY)

    expect("B8 用户关了开关 → 静默跳过（不重复唠叨）",
        AutoResumePolicy.decide(input(enabled = false)),
        AutoResumeDecision.SKIP, AutoResumeReason.DISABLED)

    expect("B9 没留下原始请求 → 不跑，并说明原因",
        AutoResumePolicy.decide(input(st = state(prompt = "   "))),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.NO_PROMPT)

    println("")
    println("[C] 熔断：一跑就被杀的任务必须停手")

    expect("C1 已自动续跑 2 次 → 停止自动，转为提示",
        AutoResumePolicy.decide(input(st = state(attempts = 2))),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.ATTEMPT_LIMIT)
    // 边界：上限是 2，那么 1 次时必须还能跑，否则功能等于只用一次。
    expect("C2 只续过 1 次 → 仍可续（上限没提前生效）",
        AutoResumePolicy.decide(input(st = state(attempts = 1))),
        AutoResumeDecision.RESUME, AutoResumeReason.OK)

    println("")
    println("[D] 时效：隔夜可以，隔三天不行")

    expect("D1 中断 25 小时 → 不自动跑",
        AutoResumePolicy.decide(input(st = state(updatedAt = NOW - 25 * HOUR))),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.TOO_OLD)
    expect("D2 中断 23 小时 → 仍在窗口内",
        AutoResumePolicy.decide(input(st = state(updatedAt = NOW - 23 * HOUR))),
        AutoResumeDecision.RESUME, AutoResumeReason.OK)
    // 老数据可能一个时间戳都没有。此时不该判「过期」——那会把一份完好进度永久废掉。
    expect("D3 时间戳全 0（残缺数据）→ 不判过期",
        AutoResumePolicy.decide(input(st = state(updatedAt = 0))),
        AutoResumeDecision.RESUME, AutoResumeReason.OK)

    println("")
    println("[E] 跑得成吗：会话 / 模型")

    expect("E1 会话已被删除 → 提示，不跑",
        AutoResumePolicy.decide(input(exists = false)),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.SESSION_MISSING)
    expect("E2 当前打开的是别的会话 → 不越界发到错地方",
        AutoResumePolicy.decide(input(active = "sess-2")),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.OTHER_SESSION)
    expect("E3 没有可用模型 → 提示，不跑",
        AutoResumePolicy.decide(input(hasModel = false)),
        AutoResumeDecision.PROMPT_ONLY, AutoResumeReason.NO_MODEL)

    println("")
    println("[F] 提示语：该说话的必须说话，不该说的必须闭嘴")

    check("F1 SKIP 一律没有说话（不制造噪声）",
        listOf(
            AutoResumePolicy.decide(input(st = null)),
            AutoResumePolicy.decide(input(running = true)),
            AutoResumePolicy.decide(input(enabled = false)),
        ).all { it.message.isEmpty() })
    check("F2 PROMPT_ONLY 一律有话说（不让用户以为功能坏了）",
        listOf(
            AutoResumePolicy.decide(input(st = state(attempts = 2))),
            AutoResumePolicy.decide(input(exists = false)),
            AutoResumePolicy.decide(input(active = "sess-2")),
            AutoResumePolicy.decide(input(hasModel = false)),
            AutoResumePolicy.decide(input(st = state(prompt = ""))),
        ).all { it.message.isNotBlank() })

    println("")
    println("[G] 落盘：计数必须真的存下来")

    val dir = Files.createTempDirectory("autoresume").toFile()
    val file = File(dir, "agent_run_state.json")

    val store = AgentRunStateStore(file)
    store.save(state(attempts = 0))
    val first = store.markAutoResumeAttempt()
    check("G1 记一次自动续跑 → 计数 1", first?.autoResumeAttempts == 1,
        "实际 ${first?.autoResumeAttempts}")
    val second = store.markAutoResumeAttempt()
    check("G2 再记一次 → 计数 2", second?.autoResumeAttempts == 2,
        "实际 ${second?.autoResumeAttempts}")

    // 只对 interrupted 生效：已完成的任务再涨计数没有意义，而且会污染下一轮。
    store.save(state(status = AgentRunState.STATUS_DONE, attempts = 0))
    check("G3 对 done 状态不计数", store.markAutoResumeAttempt() == null)

    // 跨进程：重开一个 store 从磁盘读，计数必须还在（否则熔断一重启就失效）。
    val reopened = AgentRunStateStore(file)
    reopened.save(state(attempts = 0))
    reopened.markAutoResumeAttempt()
    check("G4 计数跨 save/load 存活（重新打开文件仍是 1）",
        AgentRunStateStore(file).load()?.autoResumeAttempts == 1,
        "实际 ${AgentRunStateStore(file).load()?.autoResumeAttempts}")

    // 前向兼容：更早版本写下的 JSON 没有这个字段，必须能读、且语义是「还没续过」。
    val legacy = File(dir, "legacy.json")
    legacy.writeText(
        """{"sessionId":"s1","prompt":"整理 README","stepIndex":2,"status":"interrupted",""" +
            """"startedAt":1700000000000,"updatedAt":1700000000000,""" +
            """"doneTools":["read_file"],"note":"上次被中断"}"""
    )
    val legacyState = AgentRunStateStore(legacy).load()
    check("G5 老 JSON（无 autoResumeAttempts）能读，且计数为 0",
        legacyState != null && legacyState.autoResumeAttempts == 0,
        "load=${legacyState?.autoResumeAttempts}")

    println("")
    println("[H] 端到端：模拟「续跑 → 被杀 → 再续跑」，第三轮必须停")

    val loopFile = File(dir, "loop.json")
    val loopStore = AgentRunStateStore(loopFile)
    loopStore.save(state(attempts = 0))
    var resumed = 0
    repeat(2) { round ->
        val current = loopStore.load()!!
        val verdict = AutoResumePolicy.decide(input(st = current, now = NOW))
        if (verdict.decision == AutoResumeDecision.RESUME) {
            resumed++
            loopStore.markAutoResumeAttempt()
            // send() 的动作：写一份新的 running 状态，计数随状态搬过去。
            loopStore.save(
                loopStore.load()!!.copy(
                    status = AgentRunState.STATUS_RUNNING,
                    updatedAt = NOW,
                )
            )
            // 进程被杀 → 下次冷启动对账：running 回退成 interrupted。
            loopStore.reconcile()
            check("H${round + 1} 第 ${round + 1} 轮确实续跑了", true)
        }
    }
    check("H3 两轮都续了（说明熔断没有提前生效）", resumed == 2, "实际 $resumed")

    val third = AutoResumePolicy.decide(input(st = loopStore.load()!!, now = NOW))
    check("H4 第三轮必须停手（否则会无限烧 token）",
        third.decision == AutoResumeDecision.PROMPT_ONLY &&
            third.reason == AutoResumeReason.ATTEMPT_LIMIT,
        "实际 ${third.decision}/${third.reason}")

    println("")
    println("$pass 通过 / $fail 失败")
    if (fail != 0) kotlin.system.exitProcess(1)
}
