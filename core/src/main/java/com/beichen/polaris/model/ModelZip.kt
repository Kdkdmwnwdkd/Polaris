package com.beichen.polaris.model

import com.beichen.polaris.core.AgentLogger
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 把模型 zip 解到目标目录。
 *
 * ## 为什么先解到 `.new` 再换名
 *
 * 解压要几十秒（最大的那个 196MB、几千个文件）。中途被杀、空间不足、
 * 或者用户又点了一次删除，都会留下一个「有目录但文件不全」的现场。
 * 引擎那边只能看到一句 `Invalid OfflineTtsConfig`——这种状态查起来最费劲。
 *
 * 所以：解到同级 `.new` 目录 → 逐项核对必需文件 → 齐了才删旧的、改名。
 * 任何一步失败，用户手里仍然是上一份完整模型（或者干脆没有），不会是半份。
 *
 * ## 为什么自己写解压
 *
 * [ZipInputStream] 是 JDK 自带的，够用。不引第三方库的理由和项目其他地方一致：
 * 一个「只做解压」的依赖会拖进构建期风险与体积，而这里的逻辑不到一百行。
 * 但**必须自己防 zip slip**——条目名里的 `../` 会把文件写到目录外面去。
 */
object ModelZip {

    private const val tag = "ModelDownload"

    /**
     * 解压并校验。成功返回目标目录；失败抛异常，`dest` 保持原样。
     *
     * @param zip 已下载并通过 sha256 的压缩包
     * @param dest 最终目录（形如 `filesDir/tts/matcha`）
     * @param required 必需文件；目录（无扩展名的项）按「目录非空」判
     */
    fun extract(zip: File, dest: File, required: List<String>) {
        val staging = File(dest.parentFile, dest.name + ".new")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IllegalStateException("无法创建 ${staging.absolutePath}")

        // 解压总量上限：zip 里声明的字节数可以撒谎（zip bomb），
        // 而这是外部输入。按「压缩包的 4 倍 + 64MB」封顶，正常模型远低于此。
        val cap = zip.length() * 4 + 64L * 1024 * 1024
        var written = 0L

        try {
            zip.inputStream().buffered().use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name
                        // zip slip：`../../databases/x` 这类条目必须拒掉，不能「规范化后再说」。
                        if (name.startsWith("/") || name.contains("..")) {
                            throw IllegalStateException("压缩包里有可疑路径：$name")
                        }
                        val out = File(staging, name)
                        if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            throw IllegalStateException("压缩包条目越界：$name")
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            written += copyBounded(zis, os, cap - written)
                        }
                    }
                }
            }
            val missing = missingIn(staging, required)
            if (missing.isNotEmpty()) {
                throw IllegalStateException("压缩包缺文件：$missing")
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }

        // 换名：旧目录先让位。这一步很快，失败也只会留下「没有模型」而不是半份。
        val retired = File(dest.parentFile, dest.name + ".old")
        retired.deleteRecursively()
        if (dest.exists() && !dest.renameTo(retired)) {
            staging.deleteRecursively()
            throw IllegalStateException("旧模型目录无法移除：${dest.absolutePath}")
        }
        if (!staging.renameTo(dest)) {
            // 换名失败时把旧的放回去，别让用户从「能用」变成「不能用」。
            retired.renameTo(dest)
            staging.deleteRecursively()
            throw IllegalStateException("模型目录改名失败：${dest.absolutePath}")
        }
        retired.deleteRecursively()
        AgentLogger.i(tag, "解压完成：${dest.absolutePath}（${written / 1048576}MB）")
    }

    /** 必需项是否齐全。目录按「有子项」判——空目录等于没解开。 */
    fun missingIn(dir: File, required: List<String>): List<String> = required.filter { rel ->
        val f = File(dir, rel)
        if (f.isDirectory) f.list()?.isNotEmpty() != true else f.length() <= 0
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, remain: Long): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > remain) throw IllegalStateException("压缩包解压后超出预期大小")
            output.write(buf, 0, n)
        }
        return total
    }
}
