package com.beichen.polaris.core.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 声学设备的占用方。
 *
 * 三者**两两互斥**，所以模型是「单一持有者 + 优先级」，不是三个布尔。三个布尔的问题不是
 * 写起来啰嗦，而是它允许出现 `capture && speak` 这种自相矛盾的状态——而那个状态在真机上
 * 的表现是「AI 一边念一边听自己念」，且没有任何地方能判定这是错的。
 *
 * 优先级是**抢占方向**，不是重要性排序：
 * - [Capture] 最高：用户主动说话，必须立刻生效，哪怕要打断播报；
 * - [Speak] 居中：AI 播报，可以打断更早的唤醒监听，但要让位于用户；
 * - [Wake] 最低：常驻监听，谁来了都让。
 *
 * 同优先级**不抢占**：两个 Capture 同时来，后者被拒。理由是先到的那个才是用户此刻在说的
 * 那句话，后来的更可能是同一句话的重复触发（长按、双击、语音键连发）。
 */
enum class AudioHolder(val priority: Int) {
    /** 常驻唤醒词监听（只读麦克风）。默认关，见 `docs/research/10` 里的功耗数据。 */
    Wake(1),

    /** TTS 播报。占的是扬声器与音频焦点，但会形成回声，所以等同「不能采集」。 */
    Speak(2),

    /** 用户主动说话（录音）。 */
    Capture(3),
}

/**
 * 一次占用的凭证。
 *
 * 为什么必须带 [id] 而不是只认 [holder]：A 持有 Capture 期间，B 也来请求 Capture 被拒；
 * 若释放只认 holder，B 结束时调 `release(Capture)` 就把 A 的占用放掉了——A 还在说话，
 * 系统却认为麦克风空闲。这类 bug 在真机上表现为「偶尔有人的语音被吞掉」，无法复现。
 *
 * [id] 单调递增，每次授予新值；被抢占时旧凭证立即失效（再调 release 是 no-op）。
 */
class AudioLease internal constructor(
    val holder: AudioHolder,
    val id: Long,
    val reason: String,
) {
    override fun toString(): String = "Lease(${holder.name}#$id, $reason)"
}

/** 占用结果。 */
sealed interface ClaimResult {

    data class Granted(val lease: AudioLease) : ClaimResult

    /**
     * 拒绝。
     *
     * @property code 机器可读码（进日志与 UI 提示）。必须稳定：调用方会按它分支。
     * @property message 给人看的一句话（谁占着、为什么不让）。
     */
    data class Denied(
        val code: String,
        val message: String,
        val current: AudioLease? = null,
    ) : ClaimResult
}

/**
 * 声学占用状态机（纯逻辑，不依赖 Android）。
 *
 * 存在的理由只有一个：**让「现在能不能出声/收音」有一个唯一的事实来源**。
 * 没有它的时候，每个用到麦克风或扬声器的地方各自判断——
 * `ChatScreen` 点麦克风不知道模型正在播报，`SpeakTool` 不知道用户在说话，
 * 结果就是两者同时进行，录进去的是自己的声音。
 *
 * ## 外部占用（别的 App / 输入法）
 *
 * 这类占用不归我们管，但它们必须**一票否决**：别的 App 在录音时我们既不该抢麦，
 * 也不该播报（会污染它的录音）。所以它不是「持有者」，而是 [Denied] 的独立原因——
 * 混进持有者列表会让优先级比较失去意义（外部占用没有优先级，它只有「有」和「没有」）。
 *
 * ## 线程模型
 *
 * 所有状态变更用 [lock] 串行。这不是热路径（一次交互最多几次调用），用锁比用
 * CAS 循环更容易证明正确——尤其「检查 + 抢占 + 通知旧持有者」是三步，必须原子。
 * 通知在锁**外**发，避免持有者的回调里再调本类造成死锁。
 */
class MicClaim(
    /**
     * 日志出口。默认丢弃。
     *
     * 与 `ToolGuardChain` 同一个理由：本类必须能被 QA harness 单独编译，
     * 而 `AgentLogger` 牵着 ConfigSanitizer 与 skill 模块整条依赖链。
     * 生产装配时由 `VoiceArbiter` 接回。
     */
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()

    private val _current = MutableStateFlow<AudioLease?>(null)

    /** 当前持有者（null = 空闲）。UI 与调用方可直接观察。 */
    val current: StateFlow<AudioLease?> = _current.asStateFlow()

    /**
     * 被抢占通知。持有者应 collect 并据此停止自己的动作（停止播报 / 关闭录音）。
     *
     * 用 [SharedFlow] 而不是回调：持有者的生命周期比本类短（一个协程 vs 整个进程），
     * 回调需要注销，注销漏了就是内存泄漏 + 幽灵调用；collect 随协程结束自动解除。
     * `replay = 0`：被抢占是一个**此刻**发生的事件，补发给后来者没有意义。
     */
    private val _revoked = MutableSharedFlow<AudioLease>(extraBufferCapacity = 8)
    val revoked: SharedFlow<AudioLease> = _revoked.asSharedFlow()

    private var nextId = 1L

    @Volatile
    private var external: ExternalBusy? = null

    /** 外部占用（别的 App 录音 / 输入法语音）。传 null 表示解除。 */
    fun setExternalBusy(busy: ExternalBusy?) {
        external = busy
        // 解除时不主动恢复任何东西：被拒的请求方应该重试，而不是由我们代它决定
        // 「现在可以了」——它可能早就放弃了。
        if (busy != null) {
            // 外部占用出现时，常驻唤醒必须让位（最常见的耗电与隐私投诉来源）。
            // Capture / Speak 不在此处强拆：说话说到一半被掐比让它说完更糟。
            val wake = _current.value
            if (wake?.holder == AudioHolder.Wake) {
                releaseInternal(wake, reason = "外部占用：${busy.message}")
            }
        }
    }

    /** 当前外部占用（供 UI 提示）。 */
    fun externalBusy(): ExternalBusy? = external

    /**
     * 请求占用。
     *
     * @param holder 请求方
     * @param reason 人话原因，进日志与 [AudioLease]，用于事后归因
     */
    fun request(holder: AudioHolder, reason: String): ClaimResult {
        var granted: AudioLease? = null
        var denied: ClaimResult.Denied? = null
        var preempted: AudioLease? = null

        synchronized(lock) {
            val busy = external
            if (busy != null) {
                denied = ClaimResult.Denied(
                    code = "audio_external_busy",
                    message = busy.message,
                    current = _current.value,
                )
            } else {
                val cur = _current.value
                when {
                    // 空闲：直接给
                    cur == null -> granted = AudioLease(holder, nextId++, reason)

                    // 同优先级：不抢。先到的那个才是「此刻这一句」
                    cur.holder.priority >= holder.priority -> denied = ClaimResult.Denied(
                        code = "audio_busy",
                        message = "${cur.holder.label}正在进行（${cur.reason}），等它结束再试。",
                        current = cur,
                    )

                    // 高抢低：给新的，旧凭证作废
                    else -> {
                        granted = AudioLease(holder, nextId++, reason)
                        preempted = cur
                    }
                }
                if (granted != null) _current.value = granted
            }
        }

        // 通知在锁外：持有者收到通知后可能回调进来（例如立刻 re-request），
        // 在锁内发就是自己等自己。
        preempted?.let {
            log("${it.holder} 被抢占（${it.reason}）→ ${holder}")
            _revoked.tryEmit(it)
        }
        granted?.let { return ClaimResult.Granted(it) }
        // denied 与 granted 必有一个被赋值；这里不做 !! ——若将来有人加了分支又忘了赋值，
        // 宁可返回一个可诊断的拒绝，也不要在真机上 NPE。
        return denied ?: ClaimResult.Denied("audio_internal", "音频仲裁内部状态异常，已拒绝本次请求。")
    }

    /**
     * 释放。凭证不匹配（已被抢占 / 已被释放）时为 no-op 并返回 false。
     *
     * 返回布尔值而不是 Unit：调用方据此判断「是不是我该停了」以外的第三种情况——
     * 「我早就被抢了，这次释放是迟到的」。迟到释放通常意味着有人在错误的时机收尾
     * （例如播报已结束才调 stop），值得在日志里看见。
     */
    fun release(lease: AudioLease, reason: String = "正常释放"): Boolean =
        releaseInternal(lease, reason)

    private fun releaseInternal(lease: AudioLease, reason: String): Boolean {
        // 锁内只改状态，日志在锁外：log 的实现方可能回调进本类。
        val snapshot = synchronized(lock) {
            val cur = _current.value
            if (cur != null && cur.id == lease.id) _current.value = null
            cur
        }
        if (snapshot == null || snapshot.id != lease.id) {
            log("迟到的释放被忽略：${lease.holder}#${lease.id}（当前 ${snapshot?.let { "${it.holder}#${it.id}" } ?: "空闲"}，$reason）")
            return false
        }
        log("${lease.holder}#${lease.id} 释放（$reason）")
        return true
    }

    /** 强制清空（进程级收尾 / 测试夹具）。生产路径不应调用。 */
    fun reset() {
        val cur = synchronized(lock) { _current.value.also { _current.value = null } }
        if (cur != null) _revoked.tryEmit(cur)
    }

    private val AudioHolder.label: String
        get() = when (this) {
            AudioHolder.Wake -> "语音唤醒监听"
            AudioHolder.Speak -> "语音播报"
            AudioHolder.Capture -> "语音输入"
        }
}

/**
 * 设备上由**别人**造成的音频占用。
 *
 * 探测来源是 `AudioManager.registerAudioRecordingCallback`（API 29+），
 * 必须用 uid 把自己排除掉，否则本 App 一录音就会被自己判成「外部占用」，
 * 于是自己永远拿不到麦克风——这个坑不写下来，实现的人一定会踩。
 */
enum class ExternalBusy(val code: String, val message: String) {
    OtherAppRecording("audio_other_app", "另一个应用正在录音"),
    CallInProgress("audio_call", "通话进行中"),
}
