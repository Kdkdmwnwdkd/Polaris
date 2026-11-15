package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.io.AtomicJsonFile
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoiceProfile
import java.io.File

/**
 * 用户自己录的克隆音色。
 *
 * ## 为什么用目录而不是 SharedPreferences
 *
 * 一条克隆音色是「一段 wav + 一句参考文本 + 一个名字」，wav 有几百 KB。
 * 塞进 SharedPreferences 意味着每次读设置都把这堆字节读进内存，而且写一半被杀会留下
 * 一条指向不存在音频的记录。目录 + 三个小文件，坏哪一条都能单独判出来。
 *
 * ## 为什么参考文本必须和数据存在一起
 *
 * ZipVoice 的参考文本是**逐字转录**，不是「主题描述」。它和音频绑死：
 * 换了文本，同一段音频克隆出来的音色会明显变差。所以两者放在同一个目录，
 * 删一个必须删另一个——分开存就一定会出现「音频换了、文本还是旧的」。
 *
 * ## 为什么参考音频放 filesDir 而不是 cacheDir
 *
 * cacheDir 会被系统在存储紧张时清掉，而且不通知。用户录了声音、选了它，
 * 某天打开应用发现「音色失效」，还找不到原因。filesDir 只在卸载时消失。
 */
object CloneVoiceStore {

    private const val tag = "Voice"
    private const val ROOT = "tts/clone"
    private const val REF_WAV = "ref.wav"
    private const val REF_TEXT = "ref.txt"
    private const val LABEL = "label.txt"

    /** 参考音频最短时长。低于这个长度，克隆出来的音色会「不像」——样本不够描述一个人。 */
    const val MIN_REFERENCE_MS = 2_000L

    /** 界面上的默认名。用户不填名字时用它，而不是用时间戳。 */
    const val DEFAULT_LABEL = "我的声音"

    fun root(context: Context): File = File(context.filesDir, ROOT)

    /**
     * 全部克隆音色，按创建时间倒序（新的在前）。
     *
     * 只列出「三件套齐全」的目录：缺 ref.wav 或 ref.txt 的记录留在磁盘上但不出现在界面，
     * 因为选它必然会失败——显示出来只会让用户点一次、听一次「用不了」。
     */
    fun list(context: Context): List<CloneVoice> {
        val dir = root(context)
        val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        return children
            .mapNotNull { read(context, it.name) }
            .sortedByDescending { it.createdAt }
    }

    /** 按 id 取；找不到返回 null（调用方据此把用户的选择当作失效）。 */
    fun read(context: Context, id: String?): CloneVoice? {
        if (id.isNullOrBlank() || id.contains('/') || id.contains("..")) return null
        val dir = File(root(context), id)
        if (!dir.isDirectory) return null
        val wav = File(dir, REF_WAV)
        val textFile = File(dir, REF_TEXT)
        if (!wav.isFile || wav.length() < 44 || !textFile.isFile) return null
        val refText = runCatching { textFile.readText() }.getOrNull()?.trim().orEmpty()
        if (refText.isEmpty()) return null
        val label = runCatching { File(dir, LABEL).readText() }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_LABEL
        return CloneVoice(
            id = id,
            label = label,
            refText = refText,
            wavFile = wav,
            createdAt = wav.lastModified(),
        )
    }

    /**
     * 存一条新音色。返回 null 表示样本不合格（太短/太轻），此时**磁盘上不留东西**——
     * 留一半的目录会让 [list] 与用户看到的「我录过」对不上。
     */
    fun save(
        context: Context,
        samples: FloatArray,
        sampleRate: Int,
        refText: String,
        label: String?,
    ): CloneVoice? {
        val durationMs = samples.size * 1000L / maxOf(sampleRate, 1)
        if (durationMs < MIN_REFERENCE_MS) {
            AgentLogger.w(tag, "参考音频太短：${durationMs}ms < ${MIN_REFERENCE_MS}ms")
            return null
        }
        val id = "clone-" + System.currentTimeMillis().toString(36)
        val dir = File(root(context), id)
        if (!dir.mkdirs()) {
            AgentLogger.w(tag, "无法创建克隆音色目录 ${dir.absolutePath}")
            return null
        }
        return try {
            VoiceSampleIo.writeWav(File(dir, REF_WAV), VoiceSampleIo.toShorts(samples), sampleRate)
            // 内容和 JSON 无关，用 AtomicJsonFile 是为了**原子替换**：
            // 直接 writeText 写到一半被杀，会留下一个空文本文件，而 ref.wav 已经写好了。
            // 下次读到的就是「音频在、文本空」——克隆直接失效，且看不出为什么。
            val textOk = AtomicJsonFile.write(File(dir, REF_TEXT), refText.trim(), keepBackup = false)
            val labelOk = AtomicJsonFile.write(
                File(dir, LABEL),
                (label ?: "").trim().ifEmpty { DEFAULT_LABEL },
                keepBackup = false,
            )
            if (!textOk || !labelOk) throw IllegalStateException("参考文本落盘失败")
            AgentLogger.i(tag, "克隆音色已保存：$id，${durationMs}ms，参考文本 ${refText.length} 字")
            CloneVoice(
                id = id,
                label = (label ?: "").trim().ifEmpty { DEFAULT_LABEL },
                refText = refText.trim(),
                wavFile = File(dir, REF_WAV),
                createdAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            AgentLogger.w(tag, "保存克隆音色失败：${e.javaClass.simpleName} ${e.message}")
            runCatching { dir.deleteRecursively() }
            null
        }
    }

    fun delete(context: Context, id: String) {
        if (id.isBlank() || id.contains("..") || id.contains('/')) return
        val dir = File(root(context), id)
        val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        AgentLogger.i(tag, "删除克隆音色 $id：${if (ok) "已清理" else "目录不在"}")
    }

    /** 读参考音频（浮点 + 采样率）。文件坏了返回 null。 */
    fun loadReference(voice: CloneVoice, targetRate: Int = VoiceSampleIo.REFERENCE_RATE): Pair<FloatArray, Int>? {
        val (samples, rate) = VoiceSampleIo.readWav(voice.wavFile) ?: return null
        if (rate == targetRate) return samples to rate
        return VoiceSampleIo.resample(samples, rate, targetRate) to targetRate
    }

    /** 目录条目 → 音色条目。`locale` 写 "zh"：这一批模型是中英双语，界面上按中文呈现。 */
    fun toProfile(voice: CloneVoice): VoiceProfile = VoiceProfile(
        id = voice.id,
        label = voice.label,
        kind = VoiceKind.CLONE,
        locale = "zh",
        tagline = "你的声音 · 离线克隆",
    )
}

/** 一条已录好的克隆音色。 */
data class CloneVoice(
    val id: String,
    val label: String,
    /** 录制时读的那句话，必须与音频逐字对应。 */
    val refText: String,
    val wavFile: File,
    val createdAt: Long,
) {
    val profile: VoiceProfile get() = CloneVoiceStore.toProfile(this)
}
