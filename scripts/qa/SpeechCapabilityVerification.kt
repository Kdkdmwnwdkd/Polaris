package com.beichen.polaris.qa

import com.beichen.polaris.core.voice.EngineCapability
import com.beichen.polaris.core.voice.EngineFit
import com.beichen.polaris.core.voice.EngineSelector
import com.beichen.polaris.core.voice.LanguageTag
import com.beichen.polaris.core.voice.SpeechCapability
import com.beichen.polaris.core.voice.SpeechCapabilityPolicy
import com.beichen.polaris.core.voice.SpeechRemedy
import com.beichen.polaris.core.voice.VoicePath

/**
 * 语音能力判定的行为验证。
 *
 * 为什么要有这个 harness：这类逻辑错了**不报错**，只是静默失败——
 * 桌宠表现为「按了没反应」，聊天页表现为「弹了个系统页然后什么都没发生」。
 * CI 里没有真机也没有真引擎，行为测试永远抓不到它。所以能抽成纯函数的部分
 * 必须抽出来，让这里逐条钉死。
 *
 * 跑法见 scripts/qa/run-speech-capability-verification.sh。
 */
object SpeechCapabilityVerification {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            passed++
            println("  PASS  $name")
        } else {
            failed++
            println("  FAIL  $name${if (detail.isEmpty()) "" else "  →  $detail"}")
        }
    }

    private fun expect(
        name: String,
        actual: SpeechCapability,
        code: String,
    ) {
        val actualCode = when (actual) {
            is SpeechCapability.Available -> "available"
            is SpeechCapability.Unavailable -> actual.code
            is SpeechCapability.LanguageUnsupported -> actual.code
        }
        check(name, actualCode == code, "期望 $code，实际 $actualCode")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("[B] 行为：语音能力判定表")

        // —— 就绪 ——
        expect(
            "B1 一切就绪 → 可用",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = true,
            ),
            "available",
        )

        // —— 单字段对照：每次只改一个字段，证明每个理由各自独立 ——
        expect(
            "B2 与 B1 只差 hasRecognitionService",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = false,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = true,
            ),
            "no_service",
        )
        expect(
            "B3 与 B1 只差 hasDefaultEngine",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = false,
                languageSupported = true,
                hasMicPermission = true,
            ),
            "no_default_engine",
        )
        expect(
            "B4 与 B1 只差 languageSupported",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = false,
                hasMicPermission = true,
            ),
            "language_unsupported",
        )

        // —— 优先级：缺服务时，其他问题一律不提 ——
        expect(
            "B5 没服务 + 没权限 → 先说没服务（没有服务时，麦克风权限给不给都用不上）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = false,
                hasDefaultEngine = false,
                languageSupported = false,
                hasMicPermission = false,
            ),
            "no_service",
        )
        expect(
            "B6 没服务 + 有权限 → 说没服务",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = false,
            ),
            "no_service",
        )
        expect(
            "B7 没权限 + 没默认引擎 → 先说没权限（当场就能解决）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = false,
                hasMicPermission = false,
            ),
            "no_permission",
        )

        // —— 未知信息不乱下结论 ——
        expect(
            "B8 查不到默认引擎（null）→ 不据此拒绝",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = null,
                languageSupported = null,
            ),
            "available",
        )
        expect(
            "B9 查不到语言支持（null）→ 不据此拒绝",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = null,
            ),
            "available",
        )

        // —— 路径维度：谁来录音，决定哪几维算数 ——
        //
        // 这是本轮最要紧的一条。2026-11-14 之前聊天页和桌宠共用「缺权限就拦」，
        // 那是误诊：聊天页走系统识别页，录音在系统进程里，我们的权限与它无关。
        // 照着误诊去改，会把一条**本来能用的路**拦下来——
        // 拦住一条本来能用的路，比不拦更糟：用户看到的还是「语音没反应」。
        expect(
            "B10 系统页路径 + 我们没麦克风权限 → **不拦**（录音在系统进程里）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SystemPage,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = false,
                hasSystemRecognizerPage = true,
            ),
            "available",
        )
        expect(
            "B11 自己录音 + 我们没麦克风权限 → 拦（录音在我们进程里）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = false,
            ),
            "no_permission",
        )
        expect(
            "B12 系统页路径 + 没有能处理识别 Intent 的 Activity → 拦（连识别页都弹不出来）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SystemPage,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = true,
                hasSystemRecognizerPage = false,
            ),
            "no_system_page",
        )
        expect(
            "B13 自己录音 + 没有系统识别页 → 不拦（那条路用不到系统识别页）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SelfRecorded,
                hasRecognitionService = true,
                hasDefaultEngine = true,
                languageSupported = true,
                hasMicPermission = true,
                hasSystemRecognizerPage = false,
            ),
            "available",
        )
        expect(
            "B14 系统页路径 + 没服务 → 仍然先说没服务（两条路都要引擎）",
            SpeechCapabilityPolicy.decide(
                path = VoicePath.SystemPage,
                hasRecognitionService = false,
                hasMicPermission = false,
                hasSystemRecognizerPage = false,
            ),
            "no_service",
        )

        // —— 出路必须可执行：结构化断言，不查文案长相 ——
        //
        // 为什么不用「文案里含不含『系统设置』」这种写法：那种断言守不住行为。
        // 把话术从 A 文件搬到 B 文件它照样绿，把「系统设置」改成「设置页」它就红了，
        // 哪怕用户拿到的话还是一样有用。而 [SpeechRemedy] 是判定结果的一部分，
        // 断言它就是在断言行为：「缺权限 → 出路必须是去授权」。
        val noPermission = SpeechCapabilityPolicy.decide(
            path = VoicePath.SelfRecorded,
            hasRecognitionService = true,
            hasMicPermission = false,
        )
        check(
            "B15 缺权限 → 出路是「去系统设置开麦克风」",
            noPermission is SpeechCapability.Unavailable &&
                noPermission.remedy == SpeechRemedy.GrantMicPermission &&
                noPermission.message == SpeechCapabilityPolicy.MSG_NO_PERMISSION,
            "remedy=${(noPermission as? SpeechCapability.Unavailable)?.remedy}",
        )

        val noEngine = SpeechCapabilityPolicy.decide(
            path = VoicePath.SelfRecorded,
            hasRecognitionService = true,
            hasDefaultEngine = false,
        )
        check(
            "B16 缺默认引擎 → 出路是「去默认应用里选一个」",
            noEngine is SpeechCapability.Unavailable &&
                noEngine.remedy == SpeechRemedy.SelectDefaultEngine &&
                noEngine.message == SpeechCapabilityPolicy.MSG_NO_DEFAULT_ENGINE,
            "remedy=${(noEngine as? SpeechCapability.Unavailable)?.remedy}",
        )

        val noService = SpeechCapabilityPolicy.decide(
            path = VoicePath.SelfRecorded,
            hasRecognitionService = false,
        )
        check(
            "B17 没识别服务 → 出路是「装一个语音服务」",
            noService is SpeechCapability.Unavailable &&
                noService.remedy == SpeechRemedy.InstallRecognitionService &&
                noService.message == SpeechCapabilityPolicy.MSG_NO_SERVICE,
            "remedy=${(noService as? SpeechCapability.Unavailable)?.remedy}",
        )

        val noSystemPage = SpeechCapabilityPolicy.decide(
            path = VoicePath.SystemPage,
            hasRecognitionService = true,
            hasSystemRecognizerPage = false,
        )
        check(
            "B18 没有系统识别页 → 出路是「启用系统语音助手」",
            noSystemPage is SpeechCapability.Unavailable &&
                noSystemPage.remedy == SpeechRemedy.EnableVoiceAssistant &&
                noSystemPage.message == SpeechCapabilityPolicy.MSG_NO_SYSTEM_PAGE,
            "remedy=${(noSystemPage as? SpeechCapability.Unavailable)?.remedy}",
        )

        val langBad = SpeechCapabilityPolicy.decide(
            path = VoicePath.SelfRecorded,
            hasRecognitionService = true,
            hasDefaultEngine = true,
            languageSupported = false,
        )
        check(
            "B19 语言不支持 → 出路是「换识别服务」（不是换系统语言）",
            langBad is SpeechCapability.LanguageUnsupported &&
                langBad.remedy == SpeechRemedy.SwitchEngine,
            "remedy=${(langBad as? SpeechCapability.LanguageUnsupported)?.remedy}",
        )
        // 为什么钉死「换引擎」而不是「换语言」：
        // 换系统语言会把整台手机所有应用变成另一种语言，只为用一次语音输入；
        // 而真机上「不支持当前语言」绝大多数是当前默认引擎的问题——
        // 设备上常常还装着另一个认中文的引擎，换过去就好了。
        // 这条断言防的是「把代价不对等的出路又写回去」。
        check(
            "B19b 出路是换引擎而不是换系统语言（代价不对等）",
            langBad is SpeechCapability.LanguageUnsupported &&
                langBad.remedy != SpeechRemedy.ChangeLanguage,
            "remedy=${(langBad as? SpeechCapability.LanguageUnsupported)?.remedy}",
        )

        // —— 错误码翻译：每一条都要能回答「我现在该做什么」——
        println("[B] 行为：错误码翻译")
        val knownErrorCodes = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13)
        for (code in knownErrorCodes) {
            check(
                "B20 错误码 $code 有专属文案（非空）",
                SpeechCapabilityPolicy.explainError(code).isNotBlank(),
                "msg=${SpeechCapabilityPolicy.explainError(code)}",
            )
            check(
                "B21 错误码 $code 有明确出路（不是兜底）",
                SpeechCapabilityPolicy.remedyForError(code) != SpeechRemedy.ReportErrorCode,
                "remedy=${SpeechCapabilityPolicy.remedyForError(code)}",
            )
        }
        check(
            "B22 ERROR_CLIENT(5) 出路是「去选定语音识别服务」而不是笼统重试",
            SpeechCapabilityPolicy.remedyForError(5) == SpeechRemedy.SelectDefaultEngine,
            "remedy=${SpeechCapabilityPolicy.remedyForError(5)}",
        )
        check(
            "B23 ERROR_INSUFFICIENT_PERMISSIONS(9) 出路与前置判定一致（同为去授权）",
            SpeechCapabilityPolicy.remedyForError(9) == SpeechRemedy.GrantMicPermission &&
                SpeechCapabilityPolicy.explainError(9) == SpeechCapabilityPolicy.MSG_NO_PERMISSION,
            "remedy=${SpeechCapabilityPolicy.remedyForError(9)}",
        )
        check(
            "B24 未知错误码走兜底出路（带原码上报）",
            SpeechCapabilityPolicy.remedyForError(999) == SpeechRemedy.ReportErrorCode,
            "remedy=${SpeechCapabilityPolicy.remedyForError(999)}",
        )
        check(
            "B25 未知错误码的文案保留原码（便于对日志）",
            SpeechCapabilityPolicy.explainError(999) == "识别失败（错误码 999）",
            "msg=${SpeechCapabilityPolicy.explainError(999)}",
        )

        // ———— [C] 引擎选择：设备上有多个引擎时挑哪个 ————
        //
        // 真机日志（2026-11-14）：
        //   识别服务 2 个：com.google.android.tts/…GoogleTTSRecognitionService、
        //                  com.qualcomm.qti.voiceai.speech/…SpeechRecognitionService
        //   语音能力检查通过（引擎已选=true，语言支持=null）
        //   识别失败 code=12 → 语音识别服务不支持当前语言
        //
        // 也就是说：两个引擎都在，设置里也选了，但选的那个不认中文。
        // 这段断言钉死「这种情况下该挑哪个」。
        println()
        println("[C] 行为：引擎选择")

        fun engine(
            name: String,
            offline: Boolean = false,
            supported: Boolean = false,
            online: Boolean = false,
            default: Boolean = false,
        ) = EngineCapability(
            component = name,
            installedOffline = offline,
            supportedOffline = supported,
            online = online,
            isDefault = default,
        )

        check(
            "C1 离线包装了 → ReadyOffline（最强证据）",
            EngineSelector.fit(engine("a", offline = true)) == EngineFit.ReadyOffline,
        )
        check(
            "C2 只有在线 → ReadyOnline（能用但依赖网络）",
            EngineSelector.fit(engine("a", online = true)) == EngineFit.ReadyOnline,
        )
        check(
            "C3 声称支持但没装包 → NeedsDownload（差一次下载）",
            EngineSelector.fit(engine("a", supported = true)) == EngineFit.NeedsDownload,
        )
        check(
            "C4 什么支持都没有 → Unsupported",
            EngineSelector.fit(engine("a")) == EngineFit.Unsupported,
        )
        check(
            "C5 离线已装且也在线 → 仍判 ReadyOffline（离线优先：不耗流量、断网也能用）",
            EngineSelector.fit(engine("a", offline = true, online = true)) == EngineFit.ReadyOffline,
        )

        check(
            "C6 没有任何引擎 → 挑不出来（返回 null，调用方据此给提示）",
            EngineSelector.pick(emptyList()) == null,
        )
        check(
            "C7 全都不支持 → null（不能硬挑一个假装能用）",
            EngineSelector.pick(listOf(engine("a"), engine("b"))) == null,
        )

        // 这一条最要紧：默认引擎能用时**不许换**。
        // 换引擎会改变识别质量与断句习惯，用户没要求换，我们不该自作主张。
        val defaultOnline = engine("default", online = true, default = true)
        val otherOffline = engine("other", offline = true)
        check(
            "C8 默认引擎能用就保留（哪怕另一个是离线包也不换）",
            EngineSelector.pick(listOf(defaultOnline, otherOffline)) == defaultOnline,
            "picked=${EngineSelector.pick(listOf(defaultOnline, otherOffline))?.component}",
        )

        // 真机上的那一幕：默认的不认中文，另一个认 → 必须换过去。
        val defaultBad = engine("com.google.android.tts/X", default = true)
        val qualcommOk = engine("com.qualcomm.qti.voiceai.speech/Y", offline = true)
        check(
            "C9 默认引擎不支持中文 → 换到支持的那个（真机上那一幕）",
            EngineSelector.pick(listOf(defaultBad, qualcommOk)) == qualcommOk,
            "picked=${EngineSelector.pick(listOf(defaultBad, qualcommOk))?.component}",
        )

        val defaultNeedsDownload = engine("default", supported = true, default = true)
        check(
            "C10 默认要下载、另一个离线已装 → 用离线的（别让用户先下包）",
            EngineSelector.pick(listOf(defaultNeedsDownload, otherOffline)) == otherOffline,
        )
        check(
            "C11 全都得下载 → 仍挑一个（能用，只是差下载）",
            EngineSelector.pick(listOf(engine("a", supported = true), engine("b", supported = true)))
                ?.component == "a",
        )
        check(
            "C12 在线可用优先于需下载（少一步操作）",
            EngineSelector.pick(
                listOf(engine("needs", supported = true), engine("online", online = true)),
            )?.component == "online",
        )

        // languageVerdict 的三种输入必须给三种答案。
        // 「空表」不能当成「都不支持」——它的真实含义是「没问到」。
        // 混为一谈会让低版本系统上的用户每次都被拦住，而那台设备可能好得很。
        check("C13 没探测过（null）→ 不下结论", EngineSelector.languageVerdict(null) == null)
        check(
            "C14 探测不可用（空表）→ 不下结论（不是「不支持」）",
            EngineSelector.languageVerdict(emptyList()) == null,
        )
        check(
            "C15 探测过且没有能用的 → 明确不支持",
            EngineSelector.languageVerdict(listOf(engine("a"))) == false,
        )
        check(
            "C16 探测过且有能用的 → 支持",
            EngineSelector.languageVerdict(listOf(engine("a", online = true))) == true,
        )

        // ———— [D] 语言标签比较 ————
        //
        // 引擎回报中文有四种写法：zh、zh-CN、zh-Hans-CN、cmn-Hans-CN。
        // 用 == 比会把后两种判成不支持，然后给用户一句「换系统语言」——
        // 而它其实完全能识别。这类误判只在真机上出现（引擎说了算）。
        println()
        println("[D] 行为：语言标签比较")

        check("D1 引擎给 zh，我们要 zh-CN → 覆盖", LanguageTag.covers("zh", "zh-CN"))
        check("D2 引擎给 zh-CN，我们要 zh → 覆盖", LanguageTag.covers("zh-CN", "zh"))
        check(
            "D3 引擎给 zh-Hans-CN（带文种）→ 覆盖",
            LanguageTag.covers("zh-Hans-CN", "zh-CN"),
        )
        check(
            "D4 引擎给 cmn-Hans-CN（普通话的 ISO 639-3 代码）→ 覆盖",
            LanguageTag.covers("cmn-Hans-CN", "zh-CN"),
        )
        check("D5 引擎给 zh_CN（下划线写法）→ 覆盖", LanguageTag.covers("zh_CN", "zh-CN"))
        check("D6 引擎给 en-US，我们要中文 → 不覆盖", !LanguageTag.covers("en-US", "zh-CN"))
        check("D7 引擎给空串 → 不覆盖（没有证据不能当成支持）", !LanguageTag.covers("", "zh-CN"))
        check(
            "D8 目标为空 → 视为不限制（引擎报什么都能用）",
            LanguageTag.covers("en-US", ""),
        )
        check(
            "D9 一组里只要有一个覆盖就算支持",
            LanguageTag.anyCovers(listOf("en-US", "zh-Hans-CN"), "zh-CN"),
        )
        check(
            "D10 一组里一个都不覆盖 → 不支持",
            !LanguageTag.anyCovers(listOf("en-US", "ja-JP"), "zh-CN"),
        )
        check(
            "D11 规范化：zh_CN 与 zh-CN 等价",
            LanguageTag.canonical("zh_CN") == LanguageTag.canonical("zh-CN"),
        )

        println()
        println("=== 合计: ${passed + failed} 条，通过 $passed / 失败 $failed ===")
        if (failed > 0) {
            System.exit(1)
        }
    }
}
