package com.beichen.polaris.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.beichen.polaris.audio.asr.AsrDirectory
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.model.ModelDownloader
import com.beichen.polaris.model.ModelSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 语音环境的**诊断报告**。
 *
 * ## 为什么还需要它
 *
 * 系统识别那套探测删掉之后，「为什么没声音」仍然有几种完全不同的原因：
 * 没给麦克风权限、模型没下、模型只下了一半、音色模型没装。
 * 它们在用户眼里都是「语音坏了」，而对开发者是四种不同的修法。
 *
 * 报告只写**这台机器此刻的事实**：权限、四套模型的落点与体积、识别会走哪一套。
 * 用户把这段话发过来，就不用来回问五遍。
 *
 * ## 为什么不写系统引擎
 *
 * 那条路已经整条移除（2026-11-16）。报告里再列「Google / 高通引擎支持什么语言」
 * 只会让人以为还能去系统设置里修——而实际上改哪儿都不会有字。
 */
object VoiceDiagnostics {

    private const val TAG = "VoiceDiag"

    /** 生成一份自包含的报告：带时间与版本，隔几天发出来也能对上具体状态。 */
    fun buildReport(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()

        sb.appendLine("=== 北辰 语音诊断 ===")
        sb.appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("")

        val micGranted = ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        sb.appendLine("麦克风权限：${if (micGranted) "已授予" else "未授予 ← 语音输入与克隆录音都需要它"}")
        sb.appendLine("")

        sb.appendLine("—— 模型（都在应用私有目录，可下可删）——")
        ModelSpec.values().forEach { spec ->
            val d = ModelDownloader(app, spec)
            val state = if (d.isInstalled()) "已装 ${d.bytesOnDisk() / 1048576}MB" else "未装"
            sb.appendLine("  · ${spec.label}（${spec.approxMb}MB）：$state")
        }
        sb.appendLine("")

        // 识别用的是哪一套：两套都装时是「更准」。这句话决定用户该删哪套。
        val router = AsrDirectory.of(app)
        val active = when {
            router.accurate.isInstalled() -> "更准识别（paraformer）"
            router.streaming.isInstalled() -> "快速识别（流式 zipformer）"
            else -> "无（两套都没装）"
        }
        sb.appendLine("识别会走：$active")
        if (router.active() == null) {
            sb.appendLine("  ← 到 设置 → 语音识别 里下「更准识别」，约 74MB")
        }
        sb.appendLine("")

        sb.appendLine("—— 结论 ——")
        sb.appendLine(
            "语音输入：" + when {
                !micGranted -> "不可用（缺麦克风权限）"
                !router.isInstalled() -> "不可用（没下识别模型）"
                else -> "就绪（全部在本机计算，不联网）"
            }
        )
        sb.appendLine(
            "本地音色：" + if (ModelDownloader(app, ModelSpec.TtsMatcha).isInstalled()) {
                "就绪（22kHz）"
            } else {
                "未下载模型 ← 会退到在线音色或系统语音"
            }
        )
        sb.appendLine(
            "声音克隆：" + if (ModelDownloader(app, ModelSpec.TtsZipVoice).isInstalled()) {
                "就绪"
            } else {
                "未下载模型"
            }
        )
        return sb.toString()
    }

    /**
     * 生成报告并回调。
     *
     * 以前这里要先异步探测系统引擎，所以接口是「异步」的。现在全是本地文件状态，
     * 同步就能给——保留这个名字是为了调用方（设置页那个按钮）不用改。
     */
    fun buildReportAsync(context: Context, onReady: (String) -> Unit) {
        val report = buildReport(context.applicationContext)
        report.lines().forEach { line ->
            if (line.isNotBlank()) AgentLogger.i(TAG, line)
        }
        onReady(report)
    }

    /** 只写日志、不弹窗：给「用户点了某个按钮」这类场景用。 */
    fun logReport(context: Context) {
        buildReport(context).lines().forEach { line ->
            if (line.isNotBlank()) AgentLogger.i(TAG, line)
        }
    }
}
