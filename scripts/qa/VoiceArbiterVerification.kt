/**
 * 音频占用仲裁（MicClaim）与打断仲裁（InterruptPolicy）的行为验证。
 *
 * ## 为什么这两个模块值得单独跑
 *
 * 它们决定的是「能不能出声」「这句话算不算数」，都是**用户能立刻感知**的事，
 * 而且错了不会报错、只会表现成「偶尔有人的语音被吞掉」「AI 自己叫自己」这类
 * 无法复现的怪现象。所以断言的必须是真跑的状态迁移，不是源码长相。
 *
 * 两个模块都是零 Android 依赖的纯 Kotlin（这是刻意的，见 MicClaim 的 log 参数注释），
 * 所以任何装了 kotlinc 的机器都能真编真跑，不会因为「没编过 APK」而 SKIP。
 *
 * ## 不测什么
 *
 * - **AudioManager 的探测**：需要真机和 API 29+，属端到端。
 * - **TextSpeaker 的 UtteranceProgressListener**：要 Android 的 TTS 引擎。
 *   那一层的契约由 `VoiceArbiter` 的代码审查保证（speak 成功即乐观置 speaking，
 *   onDone/onError 置 false）。
 */

import com.beichen.polaris.core.voice.Activity
import com.beichen.polaris.core.voice.AudioHolder
import com.beichen.polaris.core.voice.ClaimResult
import com.beichen.polaris.core.voice.ExternalBusy
import com.beichen.polaris.core.voice.InterruptDecision
import com.beichen.polaris.core.voice.InterruptPolicy
import com.beichen.polaris.core.voice.MicClaim
import com.beichen.polaris.core.voice.RequestSource
import com.beichen.polaris.core.voice.StopScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections

private var pass = 0
private var fail = 0

private fun case(name: String, body: suspend () -> Unit) {
    try {
        runBlocking { body() }
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        // 抓 Throwable：被测代码的运行时异常同样是一条失败，放它逃出去会打断整个
        // harness，结果行都不打印，runner 就把「没跑完」当成「全绿」。
        fail++
        println("  FAIL  $name -> ${t::class.java.simpleName}: ${t.message}")
    }
}

private fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

private fun granted(r: ClaimResult): com.beichen.polaris.core.voice.AudioLease {
    expect(r is ClaimResult.Granted, "预期授予，实际 ${(r as? ClaimResult.Denied)?.code}")
    return (r as ClaimResult.Granted).lease
}

private fun denied(r: ClaimResult, code: String) {
    expect(r is ClaimResult.Denied, "预期拒绝($code)，实际授予")
    expect((r as ClaimResult.Denied).code == code, "拒绝码是 ${r.code}，预期 $code")
}

fun main() {
    println("[B] 行为：MicClaim 状态迁移")

    case("B1 空闲时 Capture 直接拿到") {
        val mic = MicClaim()
        val lease = granted(mic.request(AudioHolder.Capture, "用户点麦克风"))
        expect(lease.holder == AudioHolder.Capture, "持有者不对")
        expect(mic.current.value?.id == lease.id, "current 未同步")
    }

    case("B2 同优先级不抢占（第二个 Capture 被拒）") {
        val mic = MicClaim()
        granted(mic.request(AudioHolder.Capture, "第一句"))
        denied(mic.request(AudioHolder.Capture, "重复触发"), "audio_busy")
    }

    case("B3 Capture 抢占 Speak，旧凭证收到 revoked") {
        val mic = MicClaim()
        val revocations = Collections.synchronizedList(mutableListOf<Long>())
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            mic.revoked.collect { revocations += it.id }
        }
        delay(80) // 让收集器就位（replay=0，错过就是错过）
        val speak = granted(mic.request(AudioHolder.Speak, "朗读"))
        val capture = granted(mic.request(AudioHolder.Capture, "用户插话"))
        delay(80)
        expect(capture.holder == AudioHolder.Capture, "抢占后持有者应为 Capture")
        expect(speak.id in revocations, "旧持有者没收到抢占通知（播报不会停）")
        job.cancel()
    }

    case("B4 Speak 抢占 Wake（播报时停掉常驻唤醒）") {
        val mic = MicClaim()
        granted(mic.request(AudioHolder.Wake, "常驻监听"))
        val speak = granted(mic.request(AudioHolder.Speak, "朗读"))
        expect(speak.holder == AudioHolder.Speak, "高优先级应抢到")
    }

    case("B5 Speak 抢不走 Capture（正在说话时不许播报）") {
        val mic = MicClaim()
        granted(mic.request(AudioHolder.Capture, "用户说话"))
        denied(mic.request(AudioHolder.Speak, "朗读"), "audio_busy")
    }

    case("B6 Wake 谁都抢不过") {
        val mic = MicClaim()
        granted(mic.request(AudioHolder.Speak, "朗读"))
        denied(mic.request(AudioHolder.Wake, "常驻监听"), "audio_busy")
    }

    case("B7 释放后回到空闲，新请求可拿到（且是新的 id）") {
        val mic = MicClaim()
        val a = granted(mic.request(AudioHolder.Capture, "第一次"))
        expect(mic.release(a), "首次释放应成功")
        expect(mic.current.value == null, "释放后应为空闲")
        val b = granted(mic.request(AudioHolder.Speak, "第二次"))
        expect(b.id > a.id, "id 必须单调递增（否则迟到释放会误伤新持有者）")
    }

    case("B8 迟到释放不误伤新持有者") {
        val mic = MicClaim()
        val speak = granted(mic.request(AudioHolder.Speak, "朗读"))
        val capture = granted(mic.request(AudioHolder.Capture, "用户插话")) // 抢走 speak
        // 播报协程收尾时拿着旧凭证来释放——绝不能把 capture 放掉
        expect(!mic.release(speak), "旧凭证释放应返回 false")
        expect(mic.current.value?.id == capture.id, "新持有者被误释放（麦克风会在说话中途被让出）")
    }

    case("B9 外部占用一律拒绝，且不改变现有持有者") {
        val mic = MicClaim()
        mic.setExternalBusy(ExternalBusy.OtherAppRecording)
        denied(mic.request(AudioHolder.Capture, "用户点麦克风"), "audio_external_busy")
        denied(mic.request(AudioHolder.Speak, "朗读"), "audio_external_busy")
        denied(mic.request(AudioHolder.Wake, "常驻监听"), "audio_external_busy")
        expect(mic.current.value == null, "拒绝不应产生持有者")
    }

    case("B10 外部占用出现时只拆 Wake，不拆 Capture") {
        val mic = MicClaim()
        granted(mic.request(AudioHolder.Capture, "用户说话"))
        mic.setExternalBusy(ExternalBusy.OtherAppRecording)
        expect(mic.current.value?.holder == AudioHolder.Capture, "说话说到一半被外部占用掐断，比让它说完更糟")
        mic.setExternalBusy(null)
        expect(mic.release(mic.current.value!!), "解除外部占用后仍可正常释放")
    }

    case("B11 外部占用出现时 Wake 被拆除") {
        val mic = MicClaim()
        val wake = granted(mic.request(AudioHolder.Wake, "常驻监听"))
        mic.setExternalBusy(ExternalBusy.OtherAppRecording)
        expect(mic.current.value == null, "外部录音时唤醒监听必须让位")
        expect(!mic.release(wake), "已被拆除的凭证再释放应返回 false")
    }

    case("B12 解除外部占用不会自动恢复任何持有者") {
        val mic = MicClaim()
        mic.setExternalBusy(ExternalBusy.CallInProgress)
        denied(mic.request(AudioHolder.Wake, "常驻监听"), "audio_external_busy")
        mic.setExternalBusy(null)
        // 被拒方应自行重试，而不是仲裁器替它决定「现在可以了」
        expect(mic.current.value == null, "解除后不应凭空出现持有者")
        granted(mic.request(AudioHolder.Wake, "重试"))
    }

    println("")
    println("[B] 行为：InterruptPolicy 规则表")

    val idle = Activity.IDLE
    val thinking = Activity(turnRunning = true)
    val speaking = Activity(speaking = true)
    val both = Activity(turnRunning = true, speaking = true)

    case("B13 空闲时四种来源全部放行") {
        for (src in RequestSource.values()) {
            expect(InterruptPolicy.decide(idle, src) is InterruptDecision.Accept, "空闲时 $src 应放行")
        }
    }

    case("B14 回合中：打字/说话打断回合") {
        for (src in listOf(RequestSource.Text, RequestSource.Voice)) {
            val d = InterruptPolicy.decide(thinking, src)
            expect(d is InterruptDecision.AcceptAfter, "$src 应打断，实际 $d")
            expect((d as InterruptDecision.AcceptAfter).stop == StopScope.Turn, "$src 应停回合")
            expect(d.notice.isNotBlank(), "$src 打断时必须给用户一句话（否则他不知道为什么回答断了）")
        }
    }

    case("B15 回合中：唤醒词/点击不打断") {
        val w = InterruptPolicy.decide(thinking, RequestSource.Wake)
        expect(w is InterruptDecision.Reject && w.code == "wake_ignored_busy", "唤醒词打断了正在执行的回合")
        val p = InterruptPolicy.decide(thinking, RequestSource.Pet)
        expect(p is InterruptDecision.Reject && p.code == "pet_ignored_busy", "点击打断了正在执行的回合")
    }

    case("B16 仅播报中：说话/打字只停播报，不停回合") {
        for (src in listOf(RequestSource.Text, RequestSource.Voice)) {
            val d = InterruptPolicy.decide(speaking, src)
            expect(d is InterruptDecision.AcceptAfter, "$src 应放行")
            expect((d as InterruptDecision.AcceptAfter).stop == StopScope.Speech, "$src 应只停播报（回合已结束）")
        }
    }

    case("B17 仅播报中：唤醒词被拒（否则麦克风收自己的声音，自激）") {
        val d = InterruptPolicy.decide(speaking, RequestSource.Wake)
        expect(d is InterruptDecision.Reject, "播报时唤醒了，会自己叫自己")
    }

    case("B18 回合+播报同时成立：停回合，且提示里带上播报") {
        val d = InterruptPolicy.decide(both, RequestSource.Text)
        expect(d is InterruptDecision.AcceptAfter, "应放行")
        d as InterruptDecision.AcceptAfter
        expect(d.stop == StopScope.Turn, "回合优先（播报随之停）")
        expect("播报" in d.notice, "提示应说明播报也停了，实际：${d.notice}")
    }

    case("B19 所有 Reject 都带可读原因（不能是空串）") {
        for (a in listOf(thinking, speaking, both)) {
            for (src in listOf(RequestSource.Wake, RequestSource.Pet)) {
                val d = InterruptPolicy.decide(a, src)
                expect(d is InterruptDecision.Reject, "忙时 $src 应被拒")
                d as InterruptDecision.Reject
                expect(d.code.isNotBlank() && d.message.isNotBlank(), "拒绝必须能解释给用户听")
            }
        }
    }

    println("")
    println("[B] 不变量")

    case("B20 优先级严格有序：Capture > Speak > Wake") {
        expect(AudioHolder.Capture.priority > AudioHolder.Speak.priority, "用户说话必须能打断播报")
        expect(AudioHolder.Speak.priority > AudioHolder.Wake.priority, "播报必须能停掉唤醒监听")
        expect(AudioHolder.values().map { it.priority }.toSet().size == 3, "三个优先级不能相等（相等 = 谁都不抢）")
    }

    case("B21 裁决是纯函数：同一输入反复裁决结果恒定") {
        // 断言「没有隐藏状态」。这张表会被反复质疑（「为什么打字能打断、唤醒词不能」），
        // 一旦有人塞进一个 lastSource 之类的字段做「智能判断」，结果就不再可复述了。
        val first = InterruptPolicy.decide(both, RequestSource.Text)
        repeat(100) {
            expect(InterruptPolicy.decide(both, RequestSource.Text) == first, "同一输入得到不同结论——有隐藏状态")
        }
    }

    case("B22 Activity.idle 只由两个维度决定，不存在第三种忙法") {
        expect(Activity.IDLE.idle, "全 false 应为空闲")
        expect(!Activity(turnRunning = true).idle, "回合在跑不算空闲")
        expect(!Activity(speaking = true).idle, "播报中也算忙（用户点麦克风要先停播报）")
        expect(!Activity(true, true).idle, "两个都成立当然不空闲")
    }

    println("")
    println("$pass 通过 / $fail 失败")
    if (fail != 0) kotlin.system.exitProcess(1)
}
