package com.beichen.polaris.qa

import com.beichen.polaris.core.voice.VoiceAvailability
import com.beichen.polaris.core.voice.VoiceCatalog
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoicePlan
import com.beichen.polaris.core.voice.VoiceProfile
import com.beichen.polaris.core.voice.VoiceSkip

/**
 * 音色目录与回落链的行为验证。
 *
 * ## 为什么这两张表值得单独跑一遍
 *
 * 它们错了不会崩：链上空了就是「没声音」，顺序错了就是「断网时退回系统电话音」，
 * 去重错了就是「同一句念两遍」。这三种表现都像「手机卡了一下」，
 * 用户不会报，我们也抓不到——只有把表钉在 harness 里才看得见。
 *
 * 全部只依赖 `core`，不碰 Android：判定逻辑一旦 import android，
 * 这里就编不过，也就再也跑不了。
 */
object VoiceStyleVerification {

    private var pass = 0
    private var fail = 0

    private fun ok(name: String, cond: Boolean) {
        if (cond) {
            println("  PASS  $name")
            pass++
        } else {
            println("  FAIL  $name")
            fail++
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        catalog()
        plan()
        clone()
        println("=== 合计: ${pass + fail} 条，通过 $pass / 失败 $fail ===")
        if (fail > 0) System.exit(1)
    }

    private fun catalog() {
        val all = VoiceCatalog.all
        ok("C1 目录非空且覆盖三档", all.any { it.kind == VoiceKind.LOCAL } &&
            all.any { it.kind == VoiceKind.ONLINE } &&
            all.any { it.kind == VoiceKind.SYSTEM })
        ok("C2 id 唯一", all.map { it.id }.toSet().size == all.size)
        ok("C3 在线恰好 6 条", all.count { it.kind == VoiceKind.ONLINE } == 6)
        ok("C4 本地只有 1 条（matcha 单说话人）", all.count { it.kind == VoiceKind.LOCAL } == 1)
        ok("C5 系统只有 1 条", all.count { it.kind == VoiceKind.SYSTEM } == 1)
        ok("C6 所有 label 非空", all.all { it.label.isNotBlank() })
        ok("C7 在线音色都带供应商音色名", all.filter { it.kind == VoiceKind.ONLINE }
            .all { it.providerVoice.isNotBlank() })
        ok("C8 系统音色没有供应商音色名", VoiceCatalog.system.providerVoice.isBlank())
        ok("C9 在线音色都是 zh 前缀", all.filter { it.kind == VoiceKind.ONLINE }
            .all { it.providerVoice.startsWith("zh-CN") })
        ok("C10 默认音色在目录里", VoiceCatalog.byId(VoiceCatalog.DEFAULT_ID) != null)
        ok("C11 默认音色是本地（开箱有声）",
            VoiceCatalog.byId(VoiceCatalog.DEFAULT_ID)?.kind == VoiceKind.LOCAL)
        ok("C12 脏 id 归到系统引擎", VoiceCatalog.require("nope-123").id == VoiceCatalog.SYSTEM_ID)
        ok("C13 空 id 归到系统引擎", VoiceCatalog.require(null).id == VoiceCatalog.SYSTEM_ID)
        ok("C14 空串 label 也能给出可读名字",
            com.beichen.polaris.core.voice.VoiceProfile("x", "  ", VoiceKind.SYSTEM, "zh", "").safeLabel == "x")
        ok("C15 全空 label 兜底为「未知音色」",
            com.beichen.polaris.core.voice.VoiceProfile("", "", VoiceKind.SYSTEM, "zh", "").safeLabel == "未知音色")
        ok("C16 requiresNetwork 只对在线为真",
            all.all { VoiceCatalog.requiresNetwork(it) == (it.kind == VoiceKind.ONLINE) })
        ok("C17 requiresModel 只对本地为真",
            all.all { VoiceCatalog.requiresModel(it) == (it.kind == VoiceKind.LOCAL) })
    }

    /**
     * 克隆音色：用户自己录的那一条。
     *
     * 这一组和前两组的关键差别是 [VoiceAvailability] —— 克隆要「模型在」+「参考音频在」
     * 两个条件同时成立。少任何一个都发不出声，但用户该做的事完全不同：
     * 前者是重装/查存储，后者是「重新录一句」。把两者混成一个「不可用」，
     * 界面就只能说一句「用不了」，用户会反复点。
     */
    private fun clone() {
        val mine = VoiceProfile(
            id = "clone-test",
            label = "我的声音",
            kind = VoiceKind.CLONE,
            locale = "zh",
            tagline = "测试用",
        )
        val all = VoiceAvailability.ALL_READY
        val chain = VoicePlan.chain(mine, all)

        ok("K1 克隆是独立类别（不混进 LOCAL）",
            mine.kind == VoiceKind.CLONE && VoiceKind.values().count { it == VoiceKind.CLONE } == 1)
        ok("K2 克隆链：克隆 → 本地 → 系统",
            chain.map { it.profile.kind } == listOf(VoiceKind.CLONE, VoiceKind.LOCAL, VoiceKind.SYSTEM))
        ok("K3 克隆链永不包含在线（选克隆就是要离线）",
            chain.none { it.profile.kind == VoiceKind.ONLINE })
        ok("K4 克隆链尾是系统引擎", chain.last().profile.kind == VoiceKind.SYSTEM)
        ok("K5 克隆链不重复", chain.map { it.profile.id }.toSet().size == chain.size)
        ok("K6 全就绪时首选就是克隆本身",
            VoicePlan.firstPlanned(chain)?.profile?.id == mine.id)

        val noModel = VoicePlan.chain(mine, all.copy(cloneModel = false))
        ok("K7 克隆模型缺失 → 标 MODEL_MISSING",
            noModel.first().skip == VoiceSkip.MODEL_MISSING)
        ok("K8 克隆模型缺失时退到本地（不是直接掉系统）",
            VoicePlan.firstPlanned(noModel)?.profile?.kind == VoiceKind.LOCAL)

        val noRef = VoicePlan.chain(mine, all.copy(reference = false))
        ok("K9 参考音频缺失 → 标 REFERENCE_MISSING（与模型缺失区分开）",
            noRef.first().skip == VoiceSkip.REFERENCE_MISSING)
        ok("K10 两种缺失是两个不同的值", VoiceSkip.REFERENCE_MISSING != VoiceSkip.MODEL_MISSING)
        ok("K11 参考音频缺失时退到本地",
            VoicePlan.firstPlanned(noRef)?.profile?.kind == VoiceKind.LOCAL)

        val both = VoicePlan.chain(mine, all.copy(cloneModel = false, reference = false))
        ok("K12 两个都缺时仍能退到系统（链不会空）",
            both.isNotEmpty() && both.last().profile.kind == VoiceKind.SYSTEM)

        val nothing = VoicePlan.chain(mine, all.copy(localModel = false, cloneModel = false, reference = false))
        ok("K13 全都缺时唯一能试的是系统",
            VoicePlan.firstPlanned(nothing)?.profile?.kind == VoiceKind.SYSTEM)

        // 回归：新增类别不能改变旧调用（只传 id 的那条）的结果。
        ok("K14 旧签名仍按「在线→本地→系统」给链",
            VoicePlan.chain(VoiceCatalog.online.first().id, online = true, localInstalled = true)
                .map { it.profile.kind } == listOf(VoiceKind.ONLINE, VoiceKind.LOCAL, VoiceKind.SYSTEM))

        // 「选了本地就绝不偷偷联网」这条对克隆同样成立：两个都是本地的，
        // 回落时联网既是意图违背，也是没网时多等一个超时。
        val localChain = VoicePlan.chain(VoiceCatalog.local, all.copy(online = false))
        ok("K15 本地链在断网时不含在线", localChain.none { it.profile.kind == VoiceKind.ONLINE })

        ok("K16 ALL_READY 确实全部为真",
            all.online && all.localModel && all.cloneModel && all.reference)
    }

    private fun plan() {
        val online = VoiceCatalog.online.first()
        val local = VoiceCatalog.local

        val noNet = VoicePlan.chain(online.id, online = false, localInstalled = true)
        ok("P1 在线无网 → 第一条被标 NO_NETWORK", noNet.first().skip == VoiceSkip.NO_NETWORK)
        ok("P2 在线无网 → 退到本地（不是直接退系统）",
            noNet.getOrNull(1)?.profile?.id == local.id)
        ok("P3 链尾永远是系统", noNet.last().profile.id == VoiceCatalog.SYSTEM_ID)
        ok("P4 系统那一环永不被判不可用", noNet.last().skip == null)

        val noModel = VoicePlan.chain(online.id, online = true, localInstalled = false)
        ok("P5 本地未装 → 标 MODEL_MISSING",
            noModel.firstOrNull { it.profile.id == local.id }?.skip == VoiceSkip.MODEL_MISSING)
        ok("P6 在线可用时首条可planned", noModel.first().planned)

        val localChosen = VoicePlan.chain(local.id, online = true, localInstalled = true)
        ok("P7 选了本地就不回在线", localChosen.none { it.profile.kind == VoiceKind.ONLINE })
        ok("P8 本地链是两条（本地 + 系统）", localChosen.size == 2)
        ok("P9 本地未装时首选被标不可用",
            VoicePlan.chain(local.id, online = true, localInstalled = false).first().skip == VoiceSkip.MODEL_MISSING)

        val sys = VoicePlan.chain(VoiceCatalog.SYSTEM_ID, online = true, localInstalled = true)
        ok("P10 选系统就给系统一条", sys.size == 1 && sys.first().profile.kind == VoiceKind.SYSTEM)

        val dirty = VoicePlan.chain("garbage", online = false, localInstalled = false)
        ok("P11 脏 id → 单条系统链", dirty.size == 1 && dirty.first().profile.kind == VoiceKind.SYSTEM)

        ok("P12 链内不重复", listOf(online.id, local.id).all { id ->
            val c = VoicePlan.chain(id, online = true, localInstalled = true)
            c.map { it.profile.id }.toSet().size == c.size
        })
        ok("P13 链永不为空", listOf(online.id, local.id, VoiceCatalog.SYSTEM_ID, "junk").all {
            VoicePlan.chain(it, online = false, localInstalled = false).isNotEmpty()
        })
        ok("P14 全不可用时 firstPlanned 只返回系统",
            VoicePlan.firstPlanned(VoicePlan.chain(online.id, online = false, localInstalled = false))
                ?.profile?.id == VoiceCatalog.SYSTEM_ID)

        // 队列去重的实际意义：同一段文本被念两遍，用户只会觉得「卡了」。
        val dup = VoicePlan.chain(local.id, online = true, localInstalled = true)
        ok("P15 本地链首条就是所选音色", dup.first().profile.id == local.id)
    }
}
