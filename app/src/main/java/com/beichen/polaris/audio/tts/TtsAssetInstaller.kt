package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * 把 `assets/tts/<name>` 整个目录展开到 `filesDir/tts/<name>`。
 *
 * ## 为什么要展开而不是 `newFromAsset`
 *
 * sherpa-onnx 的模型配置里，`dataDir`（espeak-ng-data、jieba 词典）与 `lexicon.txt`
 * 都是**目录/多文件**。AssetManager 能给单文件一个 fd，却不能给原生层「一个可 seek
 * 的目录集合」，也不会把 assets 里的子目录展开成真实路径。单文件模型（如单个 vits onnx）
 * 可以走 assets，这两套都不行。
 *
 * ## 为什么逐文件比对长度
 *
 * 拷到一半被杀（用户切走、系统清后台、存储满）时，目录会存在但文件是截断的。
 * 只看「目录在不在」就判定完成，接下来拿一个截断的 onnx 去初始化原生层，
 * 报出来的是 `Failed to load model`——根因（上次没拷完）在日志里完全看不出来。
 * 逐文件比长度，下次进来能补齐。
 */
object TtsAssetInstaller {

    private const val tag = "Voice"
    private const val ROOT = "tts"

    fun destDir(context: Context, name: String): File = File(context.filesDir, "$ROOT/$name")

    /**
     * 展开（或补齐）到目标目录。返回本次补拷的文件数。
     *
     * 抛异常而不是静默失败：调用方正在「第一次合成」的路径上，静默失败会变成
     * 「没声音且没有原因」。异常带上目录路径，日志里直接能看见卡在哪。
     */
    fun expand(context: Context, assetRoot: String, dest: File): Int {
        if (!dest.exists() && !dest.mkdirs()) {
            throw IllegalStateException("无法创建模型目录 ${dest.absolutePath}")
        }
        val assets = context.assets
        val queue = ArrayDeque(listOf(assetRoot))
        var copied = 0
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                val target = File(dest, path.removePrefix("$assetRoot/"))
                val expected = runCatching { assets.openFd(path).use { it.length } }.getOrDefault(-1L)
                if (target.length() > 0 && (expected <= 0 || target.length() == expected)) continue
                target.parentFile?.mkdirs()
                assets.open(path).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            } else {
                children.forEach { queue.add("$path/$it") }
            }
        }
        if (copied > 0) {
            AgentLogger.i(tag, "模型展开：$assetRoot → ${dest.absolutePath}（补拷 $copied 个文件）")
        }
        return copied
    }

    /** 关键文件是否都在。`files` 里可以含子目录名（如 `espeak-ng-data`）。 */
    fun allPresent(dest: File, files: List<String>): Boolean =
        files.all { File(dest, it).let { f -> if (f.isDirectory) f.list()?.isNotEmpty() == true else f.length() > 0 } }

    /**
     * 包里是否带着这套模型（**还没展开也算**）。
     *
     * 为什么必须有这个判断：展开动作写在 `synthesize()` 里，而 `synthesize()` 只有先被
     * 音色回落链选中才会跑；回落链用 `isReady()` 筛，`isReady()` 只看 filesDir。
     * 首次安装时 filesDir 是空的 → LOCAL 被判 `MODEL_MISSING` 跳过 → 永不展开
     * → filesDir 永远是空的。**一个自锁的环**：模型在包里，用户却永远听不到。
     *
     * 735 装上去仍然报「本地模型未就绪」，就是这个环，不是模型没打进包。
     */
    fun availableInAssets(context: Context, assetRoot: String, files: List<String>): Boolean {
        val assets = context.assets
        return files.all { rel ->
            val path = "$assetRoot/$rel"
            // 目录：list() 返回子项；文件：list() 返回空数组，得再用 open() 探一次。
            val children = runCatching { assets.list(path) }.getOrNull()
            if (children != null && children.isNotEmpty()) {
                true
            } else {
                runCatching { assets.open(path).use { true } }.getOrDefault(false)
            }
        }
    }
}
