package com.beichen.polaris.qa

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
            "B19 语言不支持 → 出路是「换语言 / 装识别包」",
            langBad is SpeechCapability.LanguageUnsupported &&
                langBad.remedy == SpeechRemedy.ChangeLanguage,
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

        println()
        println("=== 合计: ${passed + failed} 条，通过 $passed / 失败 $failed ===")
        if (failed > 0) {
            System.exit(1)
        }
    }
}
