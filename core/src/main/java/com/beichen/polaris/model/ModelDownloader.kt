package com.beichen.polaris.model

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.GitHubMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 一个模型的下载 / 删除。
 *
 * ## 地址为什么是 `latest` Release
 *
 * 模型和 APK 同源，用户已经在用这条路下安装包。引第二个下载源（网盘、对象存储）
 * 意味着第二个会过期、会被限额、会被墙的地方——而它的失败方式和「模型坏了」
 * 在日志里长得一样。
 *
 * 但「自家仓库」不等于「能下」：真机上 `github.com:443` 会直接超时（同网络下
 * `api.github.com` 通）。所以这里拿到的是**官方地址**，实际请求由 [GitHubMirror]
 * 展开成「加速 → 加速 → 直连」的候选表，逐个试、把每个源的失败原因留在日志里。
 *
 * ## 清单为什么是单文件
 *
 * 四个模型（两套识别 + 两个音色）共用 `model-manifest.txt`，每行
 * `资产名 字节 sha256`。一次请求就能判断「服务端到底有没有这个模型」，
 * 也能在没有 manifest 时明确说「还没上传」，而不是让用户对着 404 猜。
 */
class ModelDownloader(
    context: Context,
    private val spec: ModelSpec,
    /** 删除前调用：让持有原生句柄的引擎先松手（否则「删了但内存里还占着」）。 */
    private val onBeforeRemove: () -> Unit = {},
) {

    private val tag = "ModelDownload"
    private val app = context.applicationContext

    val dir: File = File(File(app.filesDir, spec.home), spec.dirName)

    private val baseUrl =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest"

    /**
     * 模型是否可用 = 必需文件都在。
     *
     * 与「下载过」不是一回事：746 及更早的版本是逐文件下载的，落盘路径相同。
     * 按文件判定，升级上来的用户不会因为换了压缩包格式就要重下 74MB。
     */
    fun isInstalled(): Boolean = ModelZip.missingIn(dir, spec.required).isEmpty()

    /** 目录里现在占多少字节（界面显示「已下载 74MB」用）。 */
    fun bytesOnDisk(): Long {
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 下载整包 → 校验 → 解压 → 删压缩包。
     *
     * 任何一步失败都抛异常，消息直接给人看（「服务端还没上传这个模型」
     * 「第 2 个源校验不符」）。调用方只管把它显示出来。
     */
    suspend fun download(onProgress: (done: Long, total: Long, label: String) -> Unit) =
        withContext(Dispatchers.IO) {
            val entry = fetchManifest()
            // D-7：内嵌哈希 × 服务端清单，**交叉校验**。
            //
            // 清单与 zip 在同一个 Release 里，能换掉其中一个的人也能换掉另一个——
            // 单看清单，这套校验只防传输损坏，不防「服务端说这是对的」。
            // 内嵌值是编译进 APK 的，攻击者要一起改就得让用户装一个新 APK，
            // 而 APK 有签名校验。
            //
            // 不一致**不换源重试**：内容本身就是错的，换源只会换一种方式失败，
            // 还会把「有人换了资产」这件事伪装成网络抖动。
            val pinned = spec.sha256
            if (pinned == null) {
                AgentLogger.w(tag, "${spec.label}的哈希尚未固定（D-7），本次只信服务端清单")
            } else if (!pinned.equals(entry.sha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "${spec.label}校验失败：发布清单里的哈希与内置值不一致。" +
                        "这通常意味着发布资产被替换过——请勿重试，先告知开发者。"
                )
            }
            val zip = File(File(app.cacheDir, "models").apply { mkdirs() }, spec.zipName + ".part")
            zip.delete()
            try {
                downloadTo(entry, zip, onProgress)
                onProgress(entry.size, entry.size, spec.label)
                ModelZip.extract(zip, dir, spec.required)
                AgentLogger.i(tag, "${spec.label}就绪：${bytesOnDisk() / 1048576}MB → ${dir.absolutePath}")
            } finally {
                // 压缩包只在安装时有用。留着等于让用户多付一份空间。
                zip.delete()
            }
        }

    /** 删掉模型目录。先让引擎释放句柄，再删文件。 */
    fun remove() {
        runCatching { onBeforeRemove() }
            .onFailure { AgentLogger.w(tag, "释放引擎失败（继续删文件）：${it.message}") }
        dir.deleteRecursively()
        AgentLogger.i(tag, "${spec.label}已删除（${dir.absolutePath}）")
    }

    // —— 内部 ——

    private class Entry(val name: String, val size: Long, val sha256: String) {
        fun digestMatches(f: File): Boolean = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
        }.getOrDefault(false)
    }

    /**
     * 取清单并挑出自己那一行。
     *
     * 逐源尝试，失败原因汇总：用户看到的不能只是「下载失败」——
     * 要能分清「服务端没传」和「这台机器到不了 github」。
     */
    private fun fetchManifest(): Entry {
        val failures = ArrayList<String>()
        for (src in GitHubMirror.sources("$baseUrl/model-manifest.txt")) {
            val body = try {
                fetchText(src.url)
            } catch (t: Throwable) {
                failures += "${src.label}：${t.message}"
                AgentLogger.w(tag, "清单下载失败（${src.label}）：${t.message}")
                continue
            }
            val entries = parse(body)
            val hit = entries[spec.zipName]
            if (hit != null) {
                AgentLogger.i(tag, "清单来自 ${src.label}（共 ${entries.size} 项，${spec.zipName}=${hit.size} 字节）")
                return hit
            }
            failures += if (entries.isEmpty()) {
                "${src.label}：返回的不是清单"
            } else {
                "${src.label}：清单里没有 ${spec.zipName}（有 ${entries.keys.joinToString("、")}）"
            }
        }
        throw IllegalStateException("模型清单取不到——${failures.joinToString("；")}")
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return LlmHttp.short.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val why = if (resp.code == 404) "服务端还没上传模型" else "网络问题"
                throw IllegalStateException("HTTP ${resp.code}：$why")
            }
            // 清单是外部输入，长度由服务端决定：走有上限读取（审计 F21）。
            resp.body?.let { BoundedBody.read(it.byteStream(), 64L * 1024).text }.orEmpty()
        }
    }

    /** 每行 `资产名 字节 sha256`；注释与空行跳过。 */
    private fun parse(body: String): Map<String, Entry> = body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split(Regex("\\s+"))
            if (p.size < 3) return@mapNotNull null
            val size = p[1].toLongOrNull() ?: return@mapNotNull null
            p[0] to Entry(p[0], size, p[2])
        }
        .toMap()

    /**
     * 下这一个压缩包：多源轮换，**校验通过才算数**。
     *
     * 三道关缺一不可：传输完整（字节数对得上）、内容没被替换（sha256）、
     * 落盘用 `.part` 改名（避免下次把半截包当成品去解压）。
     */
    private fun downloadTo(entry: Entry, part: File, onProgress: (Long, Long, String) -> Unit) {
        val failures = ArrayList<String>()
        for (src in GitHubMirror.sources("$baseUrl/${entry.name}")) {
            part.delete()
            var written = 0L
            val got = runCatching {
                fetchTo(src.url, part) { n ->
                    written += n
                    onProgress(written, entry.size, spec.label)
                }
            }
            if (got.isFailure) {
                failures += "${src.label}：${got.exceptionOrNull()?.message}"
                AgentLogger.w(tag, "${entry.name} 下载失败（${src.label}）：${got.exceptionOrNull()?.message}")
                continue
            }
            if (part.length() != entry.size || !entry.digestMatches(part)) {
                failures += "${src.label}：校验不符（${part.length()} / ${entry.size} 字节）"
                AgentLogger.w(tag, "${entry.name} 校验不符（${src.label}）")
                continue
            }
            AgentLogger.i(tag, "${entry.name} 就绪（来自 ${src.label}，${written} 字节）")
            return
        }
        throw IllegalStateException("${spec.label}下载失败——${failures.joinToString("；")}")
    }

    private fun fetchTo(url: String, part: File, onBytes: (Long) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        LlmHttp.download.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应为空")
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        }
    }

    private companion object {
        /** 部分镜像对不带 UA 的请求直接回 403，与「文件不存在」的 404 混在一起分不清。 */
        const val USER_AGENT = "Polaris"
    }
}
