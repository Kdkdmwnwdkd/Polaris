package com.beichen.polaris.qa

import com.beichen.polaris.core.voice.VoiceCatalog
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoicePlan
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
