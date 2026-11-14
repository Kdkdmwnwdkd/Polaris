package com.beichen.polaris.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.EngineSelector
import com.beichen.polaris.core.voice.SpeechCapability
import com.beichen.polaris.core.voice.VoicePath

/**
 * 语音环境的**诊断报告**。
 *
 * ## 为什么需要它
 *
 * 语音不好用的原因有五种（没服务 / 没权限 / 没选默认引擎 / 被占用 / 语言不支持），
 * 而它们在用户眼里都是「语音坏了」。没有诊断的时候，用户只能说一句「语音不能用」，
 * 开发者只能靠猜——而这类问题**猜不出来**：五个原因对应的修法完全不同，
 * 有的要装服务，有的要跳权限页，有的要改系统设置。
 *
 * 报告的价值在于把「坏在哪一步」变成一行事实。用户把这段话发过来，
 * 就不用来回试五遍。
 *
 * ## 为什么日志与报告分开
 *
 * 日志给开发者看（能捞到完整上下文），报告给用户看（能直接复制粘贴）。
 * 两者内容重叠但形式不同——日志是逐条追加的，报告是自包含的一段话。
 */
object VoiceDiagnostics {

    private const val TAG = "VoiceDiag"

    /**
     * 生成一份自包含的诊断报告。
     *
     * 报告刻意写成**不依赖上下文**的一段话：用户可能隔几天才发出来，
     * 中间 App 已经升级过很多次。带时间戳与版本号，让报告能对上某一次具体的状态。
     *
     * @return 多行文本，每行一个事实。
     */
    fun buildReport(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()

        sb.appendLine("=== 北辰 语音诊断 ===")
        sb.appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("")

        // —— 权限 ——
        val micGranted = ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        sb.appendLine(
            "麦克风权限：${if (micGranted) "已授予" else "未授予"}" +
                "（**只有桌宠语音需要它**——聊天页走系统识别页，录音在系统进程里）"
        )

        // —— 识别服务 ——
        val services = runCatching {
            app.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0
            )
        }.getOrElse { emptyList() }
        sb.appendLine("识别服务数量：${services.size}")
        services.forEach { s ->
            sb.appendLine("  · ${s.serviceInfo.packageName}/${s.serviceInfo.name}")
        }
        if (services.isEmpty()) {
            sb.appendLine("  ← 装一个语音服务（如 Google 语音服务）才能用")
        }

        // —— 系统识别页（聊天页专用）——
        val systemPages = runCatching {
            app.packageManager.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
            )
        }.getOrElse { emptyList() }
        sb.appendLine("系统语音识别页：${systemPages.size} 个")
        systemPages.forEach { a ->
            sb.appendLine("  · ${a.activityInfo.packageName}/${a.activityInfo.name}")
        }
        if (systemPages.isEmpty()) {
            sb.appendLine("  ← 聊天页语音靠它弹识别界面；没有就说明系统语音助手/搜索应用被停用了")
        }

        // —— 默认引擎 ——
        val defaultEngine = runCatching {
            Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
        }.getOrNull()
        sb.appendLine(
            "系统默认识别引擎：${defaultEngine ?: "未设置 ← 引擎装了也不认，系统设置 → 默认应用 → 语音识别 里选一个"}"
        )

        val probe = SpeechProbe(app)

        // —— 语言 ——
        val locale = java.util.Locale.getDefault()
        val languageTag = locale.toLanguageTag()
        sb.appendLine("系统语言：$languageTag（BCP-47）")

        // —— 各引擎对当前语言的支持 ——
        //
        // 这一段的由来：2026-11-14 的真机日志里，设备上有两个识别服务、
        // 系统也选定了默认引擎，可桌宠每次录音都拿到 code=12（不支持当前语言）——
        // 因为默认那个是 Google TTS 自带的，要单独下中文离线包。
        // 光看「识别服务 2 个」「引擎已选」看不出问题，必须逐个引擎问。
        //
        // 结果来自进程级缓存：桌宠启动时会探一次。用户先开一次桌宠再点诊断，
        // 这里就有内容；没探过就直说「未探测」，并告诉他怎么让它有值——
        // 比留空或编一个结论诚实。
        val engines = probe.cachedEngines()
        sb.appendLine("")
        sb.appendLine("—— 各引擎对 $languageTag 的支持 ——")
        if (engines == null) {
            sb.appendLine("  未探测。先长按一次桌宠说句话，再回来生成报告就能看到")
        } else if (engines.isEmpty()) {
            sb.appendLine("  探测不可用（系统版本低于 13，或查询不到识别服务）")
        } else {
            engines.forEach { e ->
                val fit = EngineSelector.fit(e)
                val mark = if (e.isDefault) "[系统默认] " else ""
                sb.appendLine("  · $mark${e.component}")
                sb.appendLine("      ${EngineSelector.describe(fit)}")
            }
            val chosen = EngineSelector.pick(engines)
            sb.appendLine(
                "  当前应使用：${chosen?.let { "${it.component}（${EngineSelector.describe(EngineSelector.fit(it))}）" }
                    ?: "没有能用的 ← ${EngineSelector.MSG_NO_ENGINE_FOR_LANGUAGE}"}"
            )
        }

        // —— 结论：两条路分开说 ——
        //
        // 为什么分开：两条路的前置条件不同（谁来录音），
        // 合成一句「语音可用/不可用」会把「桌宠能用但聊天页不能用」这种
        // 最常见的情况说成「全都不能用」，用户就没法判断该修哪边。
        sb.appendLine("")
        sb.appendLine("结论（桌宠 · 自己录音）：${describe(probe.check(VoicePath.SelfRecorded))}")
        sb.appendLine("结论（聊天页 · 系统识别页）：${describe(probe.check(VoicePath.SystemPage))}")

        return sb.toString()
    }

    /**
     * 把判定结果写成一句话。
     *
     * remedy 一并写出来：它就是「该做什么」的结构化版本，
     * 对不上就说明文案与出路分叉了（harness 里钉死了两者同源）。
     */
    private fun describe(verdict: SpeechCapability): String = when (verdict) {
        is SpeechCapability.Available ->
            "环境就绪，可以语音输入"
        is SpeechCapability.Unavailable ->
            "${verdict.message}（code=${verdict.code}，出路=${verdict.remedy}）"
        is SpeechCapability.LanguageUnsupported ->
            "${verdict.message}（code=${verdict.code}，出路=${verdict.remedy}）"
    }

    /**
     * 生成报告并写进日志。
     *
     * 单独一个函数，是为了给「用户点了某个按钮」这种场景用——
     * 那种场景的产物是日志，不是弹窗。
     */
    fun logReport(context: Context) {
        val report = buildReport(context)
        report.lines().forEach { line ->
            if (line.isNotBlank()) AgentLogger.i(TAG, line)
        }
    }
}
