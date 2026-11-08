package com.beichen.polaris.core.llm.local

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.CrashLogger
import java.io.File

/**
 * 原生崩溃记忆：读上次的崩溃现场，决定这次还要不要开 GPU。
 *
 * ## 为什么要「下次启动才处理」
 * 崩溃已经发生了，当场什么也做不了（进程正在死）。能做的只有两件事：
 *  1. 把现场留下来，让下一次启动知道「上次是开 GPU 崩的」；
 *  2. 下次**自动关掉 GPU**，让人至少能先把对话用起来，而不是一开就崩、再开再崩。
 *
 * ## 为什么不问用户就直接关
 * 闪退对用户来说是「这个 App 坏了」，不是「一个选项需要权衡」。先退回能用的状态、
 * 再把原因写在设置页，比弹一个「是否继续使用 GPU？」的对话框负责——
 * 后者在最坏的循环里会变成每次启动都弹一次。
 *
 * 解析与判定在 [NativeCrashParser]（纯 JVM，可断言）；这里只做文件与配置的读写。
 */
object NativeCrashMemory {

    /** 启动时调用：读现场 → 必要时关 GPU → 删文件（只处理一次）。 */
    fun handleStartup(context: Context) {
        val crash = runCatching { read(context) }.getOrNull() ?: return
        // 先处理文件：无论后面怎么判定，这份现场都只该被**消费**一次。
        //
        // 但不能直接删——用户此刻正看到设置页的红字、准备点「生成诊断包」求助，
        // 而诊断包里最该有的就是这份现场。所以改名留档：原名消失（不会二次消费），
        // 归档名固定（覆盖上一份，只留最近一次，不占空间）。
        runCatching {
            val archive = archiveFile(context)
            if (archive.exists()) archive.delete()
            crashFile(context).renameTo(archive)
        }

        if (!NativeCrashParser.shouldDisableGpu(crash)) {
            // 纯 CPU 崩在推理阶段：关 GPU 没有意义（本来就没开），能动的只剩内存占用。
            // 这一支是 2026-10-29 真机第二次 SIGABRT 之后加的 —— 上一版只写了
            // 「与 GPU 无关」就收工，用户下次进来还是同样的参数、同样的崩法。
            if (NativeCrashParser.shouldEnableSafeMode(crash)) {
                val note = NativeCrashParser.describeSafeMode(crash)
                runCatching {
                    val setting = LocalLlamaSetting(context)
                    setting.safeMode = true
                    setting.lastGpuCrashNote = note
                }
                // 这里**不**调 LlamaJni.applySafeMode：那会在 Application.onCreate 里
                // 触发 dlopen，把「启动时不碰 native」这条约定打破。开关由
                // LocalLlamaClient 在每次装载模型之前推送（见那边的说明）。
                AgentLogger.e(
                    "LocalLlama",
                    "上次运行在「${crash.stageLabel}」阶段崩溃（${crash.signalName}，" +
                        "GPU ${crash.gpuLayers} 层 / ctx ${crash.ctx} / RSS ${crash.rssKb} KB）" +
                        "——已开启安全模式（上下文 2048 / 批 128）",
                )
                return
            }
            AgentLogger.w(
                "LocalLlama",
                "上次运行被 ${crash.signalName} 终止（${crash.stageLabel}），当时是纯 CPU 运行——与 GPU 无关",
            )
            return
        }

        val note = NativeCrashParser.describe(crash)
        runCatching {
            val setting = LocalLlamaSetting(context)
            setting.gpuMode = GpuMode.OFF
            setting.lastGpuCrashNote = note
        }
        AgentLogger.e(
            "LocalLlama",
            "上次运行在「${crash.stageLabel}」阶段崩溃（${crash.signalName}，" +
                "GPU ${crash.gpuLayers} 层 / ctx ${crash.ctx} / ${crash.model}）——已自动关闭 GPU 加速"
        )
    }

    /** 崩溃文件位置（和 Java 崩溃日志同一个目录，便于一起清理）。 */
    fun crashFile(context: Context): File =
        File(File(context.filesDir, CrashLogger.DIR_NAME), NativeCrashParser.FILE_NAME)

    /**
     * 归档文件：被 [handleStartup] 消费过的现场改名到这里，只留最近一次。
     *
     * 存在的唯一理由是「诊断包要能带走它」——删掉之后就只剩设置页那一句中文，
     * 而 stage / sigcode / ctx / gpu_layers 这些复现必需的量全在文件里。
     */
    fun archiveFile(context: Context): File =
        File(File(context.filesDir, CrashLogger.DIR_NAME), NativeCrashParser.ARCHIVE_NAME)

    /** 读取并解析；文件不存在或内容认不出时返回 null（绝不影响启动）。 */
    fun read(context: Context): NativeCrash? {
        val f = crashFile(context)
        if (!f.exists()) return null
        return NativeCrashParser.parse(f.readText())
    }
}
