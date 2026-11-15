package com.beichen.polaris.core.voice

/**
 * 一条候选被跳过的原因。
 *
 * 只列**已知的、可以提前判定的**两种情况：没网、模型没装。
 * 合成失败（接口 403、超时、服务端 500）不在这里——那是运行期事件，
 * 属于「试了才知道」，用 [VoiceAttemptResult.Failed] 表达。
 *
 * 区分这两层的理由：提前判定可以写进界面（「这个音色要联网」），
 * 运行期失败只能事后解释（「刚才那条在线音色没响，已自动换成本地」）。
 * 混成一个字段，界面就只能写一句「可能不可用」，等于什么都没说。
 */
enum class VoiceSkip {
    /** 需要联网，但当前没有可用网络。 */
    NO_NETWORK,

    /** 本地模型没下载/不完整。 */
    MODEL_MISSING,
}

/**
 * 回落链上的一环：候选音色 + 若已知不可用的原因。
 *
 * `skip == null` 不等于「一定成功」，只等于「没有已知的拦路理由」。
 */
data class VoiceAttempt(
    val profile: VoiceProfile,
    val skip: VoiceSkip?,
) {
    val planned: Boolean get() = skip == null
}

/**
 * 回落链：从用户选的音色开始，逐级退到「一定还在」的那一条。
 *
 * ## 不变量（QA 逐条钉死）
 *
 *  1. 永远非空；
 *  2. 最后一条永远是 [VoiceCatalog.system]——系统引擎是唯一不依赖下载和网络的东西；
 *  3. 不重复；
 *  4. 链上每一条的 [VoiceSkip] 只由**入参事实**决定，不猜。
 *
 * ## 为什么「在线 → 本地 → 系统」而不是「在线 → 系统」
 *
 * 用户要的是好听。在线挂了直接掉到系统语音包（可能是 8kHz 电话音），
 * 等于「一断网就退回上个版本」。本地模型就是为这一步存在的：断网时它比系统好听，
 * 只是比在线平。跳过它，等于把已经付过体积代价的东西晾在那儿。
 *
 * ## 为什么系统引擎永远排在最后而不是按「可用性」筛掉
 *
 * 系统有没有装中文语音包，只有 `TextToSpeech.setLanguage` 的返回值知道，
 * 那是运行期事实。在这里预先筛掉，就会出现「链空了、没声音、也无从解释」。
 * 让它留在链尾，失败了由 `TextSpeaker` 给出确切原因（缺哪个语言包）。
 */
object VoicePlan {

    fun chain(
        desiredId: String?,
        online: Boolean,
        localInstalled: Boolean,
    ): List<VoiceAttempt> {
        val desired = VoiceCatalog.require(desiredId)
        val candidates = when (desired.kind) {
            // 用户选了在线：在线 → 本地 → 系统
            VoiceKind.ONLINE -> listOf(desired, VoiceCatalog.local, VoiceCatalog.system)
            // 用户选了本地：本地 → 系统。不再回在线——他选本地就是要离线，
            // 失败时偷偷联网既违背意图，也会在没网时多等一个超时。
            VoiceKind.LOCAL -> listOf(desired, VoiceCatalog.system)
            // 用户选了系统：就用系统。别替他升级。
            VoiceKind.SYSTEM -> listOf(VoiceCatalog.system)
        }
        val seen = HashSet<String>(candidates.size)
        return candidates
            .filter { seen.add(it.id) }
            .map { profile ->
                val skip = when {
                    profile.kind == VoiceKind.ONLINE && !online -> VoiceSkip.NO_NETWORK
                    profile.kind == VoiceKind.LOCAL && !localInstalled -> VoiceSkip.MODEL_MISSING
                    else -> null
                }
                VoiceAttempt(profile, skip)
            }
    }

    /**
     * 真正要试的第一条；全被提前判定为不可用时返回 null。
     *
     * 注意 [VoiceCatalog.system] 永远不会被判成不可用，所以只有在
     * 「要系统却要不到」这种自相矛盾的情况下才会返回 null——保持类型诚实，
     * 让调用方必须处理「一条都没有」这个分支，而不是假装链永不为空。
     */
    fun firstPlanned(chain: List<VoiceAttempt>): VoiceAttempt? = chain.firstOrNull { it.planned }
}

/** 一次实际尝试的结果。供 `TextSpeaker` 记录「为什么最后用的是这一条」。 */
sealed interface VoiceAttemptResult {
    data class Spoken(val profile: VoiceProfile, val demotedFrom: List<VoiceSkip>) : VoiceAttemptResult
    data class Failed(val profile: VoiceProfile, val reason: String) : VoiceAttemptResult
}
