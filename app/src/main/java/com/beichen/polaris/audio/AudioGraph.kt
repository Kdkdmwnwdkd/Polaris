package com.beichen.polaris.audio

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 音频层（TTS 引擎 + 占用仲裁器）的**进程级**所有者。
 *
 * ## 为什么必须是进程级，而不是 ViewModel 级
 *
 * 改造前 `ChatViewModel` 里写着 `private val speaker = TextSpeaker(app)` 与
 * `VoiceArbiter(app, speaker, viewModelScope)`——两行都很自然，但合起来破坏了这个功能
 * 唯一的不变量：`MicClaim` 的类注释第一句是「让『现在能不能出声/收音』有一个**唯一的事实
 * 来源**」，而当时那个「唯一」只在一个 ViewModel 内成立。
 *
 * 桌宠（`docs/specs/desktop-pet-spec-v1.md`）活在 Service 里，没有 Activity，也没有
 * 这个 ViewModel。它要朗读就得自己 `new TextSpeaker`——于是进程里有两个 TTS 引擎、
 * 两个 `MicClaim`，彼此不可见：主界面在念、桌宠也在念（叠音），桌宠要录音时主界面
 * 占着麦克风（判不出来）。**问题不在桌宠怎么实现，而在仲裁器的作用域选错了。**
 *
 * 所以把两者提到这里，由进程持有；ViewModel 与将来的 `PetAgentSession` 都从这里取
 * **同一个实例**，「单一持有者」才重新成立。
 *
 * ## 为什么放 app 层而不是 core
 *
 * `TextSpeaker` 依赖 `android.speech.tts`，`VoiceArbiter` 依赖 `AudioManager` 与
 * `Handler(Looper.getMainLooper())`。`core` 一旦 import 它们就不再能被 QA harness
 * 独立编译，而 `MicClaim` / `InterruptPolicy` 的规则表恰恰只有真跑才验证得了。
 * 纯逻辑留在 `core/voice`，Android 装配留在这一层——与 `VoiceArbiter` 的分工一致。
 *
 * ## 生命周期
 *
 * 进程存活期间常驻，**不随 Activity 销毁**。理由：Activity 销毁后 Agent 仍可能在后台
 * 跑完一个回合并朗读（`AgentRunService`），此时释放引擎等于把话说一半掐掉；桌宠更是
 * 本来就没有 Activity。
 *
 * 唯一释放点是 [reset]（harness / 诊断），由 `AppGraph` 统一调用。生产路径不调它——
 * 这与 `AppGraph` 的边界一致：清单只负责「让全局状态可枚举、可一键清空」，不接管
 * 生产生命周期。
 *
 * ## 线程模型
 *
 * 单例创建用 [lock] 串行：`VoiceArbiter` 的构造会 `scope.launch` 两个 collect，
 * 并发进来会得到两个仲裁器、两份租约——比不加锁难查得多。
 */
object AudioGraph {

    private val lock = Any()

    private var scopeRef: CoroutineScope? = null
    private var speakerRef: TextSpeaker? = null
    private var arbiterRef: VoiceArbiter? = null

    /**
     * 全局唯一的 TTS 封装。
     *
     * 用 `Dispatchers.Main.immediate` 而不是 IO：`TextToSpeech` 的构造与 `speak`
     * 都要求在带 Looper 的线程上调用（引擎内部会 post 到主线程），从 IO 线程调会
     * 偶发 `RuntimeException: Can't create handler inside thread that has not called Looper.prepare()`。
     */
    fun speaker(context: Context): TextSpeaker = synchronized(lock) {
        speakerRef ?: TextSpeaker(context.applicationContext).also { speakerRef = it }
    }

    /** 全局唯一的音频仲裁器（内含唯一的 [com.beichen.polaris.core.voice.MicClaim]）。 */
    fun arbiter(context: Context): VoiceArbiter = synchronized(lock) {
        arbiterRef ?: run {
            val app = context.applicationContext
            val scope = scopeRef ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                .also { scopeRef = it }
            VoiceArbiter(app, speaker(app), scope) { AgentLogger.i("Voice", it) }
                .also { arbiterRef = it }
        }
    }

    /**
     * 释放并回到未初始化状态；下次 [speaker] / [arbiter] 会重建。
     *
     * 只由 `AppGraph` 的清场入口调用（harness / 诊断）；生产路径不调它——
     * 进程还活着时释放引擎，等于把后台回合的朗读掐在半句上。
     *
     * 顺序：先关仲裁器（它会停播报、注销录音探测、清租约），再关引擎，最后取消协程。
     * 反过来（先 cancel scope）会让仲裁器里那两个 collect 先死，`close()` 的
     * `stopSpeech()` 就没人兜底了——虽然它自己也调了 stop，但依赖顺序不该是隐式的。
     */
    fun reset() {
        val (arbiter, speaker, scope) = synchronized(lock) {
            Triple(arbiterRef, speakerRef, scopeRef).also {
                arbiterRef = null
                speakerRef = null
                scopeRef = null
            }
        }
        runCatching { arbiter?.close() }
        runCatching { speaker?.shutdown() }
        runCatching { scope?.cancel() }
    }
}
