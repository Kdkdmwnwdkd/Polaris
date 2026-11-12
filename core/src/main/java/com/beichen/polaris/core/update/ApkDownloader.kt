package com.beichen.polaris.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.runCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 下载完成的安装包。 */
data class DownloadedApk(
    val file: File,
    /** 包内 versionCode（已与期望值比对通过）。 */
    val versionCode: Int,
    /**
     * 与本机已装包的签名是否一致。
     *
     * 不一致不代表包有问题——本地构建（debug 签名）与 CI 发布包（release 签名）本来就不同。
     * 但系统会因此拒绝覆盖安装，所以要把判断结果带到 UI，提前告诉用户「得先卸载」，
     * 而不是让他点完「安装」再看系统弹一句「应用未安装」。
     */
    val signatureMatches: Boolean
)

/** 下载失败。[message] 直接展示给用户，所以全部写成短句，不带类名与堆栈。 */
class ApkDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 应用内更新包下载器。
 *
 * ## 为什么不用 DownloadManager
 * 系统的 DownloadManager 会把包丢进「下载」目录并弹通知，用户还得去文件管理器点一次；
 * 而且各 ROM 对它的后台限制不同（有的会在息屏后掐掉），下载进度也拿不到应用内。
 * 这里只有 37MB、只有一个固定地址，自己拿 OkHttp 流式写盘更可控：
 * 进度、取消、校验、清理都在一处。
 *
 * ## 校验为什么值得写
 * 加速通道在限流/维护时会返回 200 + 一张 HTML 说明页；只判断 HTTP 状态码的话，
 * 用户会得到一个叫 `.apk` 的网页。这里三层校验：
 * 长度对不对（有 Content-Length 时）、是不是 ZIP（魔数）、包名与 versionCode 对不对。
 * 任何一层不过就换源重下，绝不把坏文件交给安装器。
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    /** 缓存子目录（须与 `res/xml/file_paths.xml` 的 updates 路径一致）。 */
    private const val DIR = "updates"

    /** 小于这个体积的「APK」一定不是完整包。 */
    private const val MIN_APK_BYTES = 1L * 1024 * 1024

    /**
     * 统一走 `LlmHttp.download`（connect 30s / read 120s / 无总超时）。
     *
     * 不自己 new 一个客户端：R-13 把全仓 14 处各自构造收口到 `LlmHttp`，
     * 就是为了不再出现「每个功能一套超时」——新增下载器顺手 new 一个客户端，
     * 正是这条约束要挡的回归。`download` 预设本来就是给大文件传输留的。
     */
    private val client get() = LlmHttp.download

    /**
     * 加速前缀，按顺序尝试；末尾空串 = 直连。
     *
     * 为什么放在下载层而不是 `UpdateChecker`：换源是「怎么拿到字节」的问题，
     * 检查更新只需要知道官方地址。海外网络下直连往往比多一跳更快，所以直连必须保留。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        ""
    )

    fun cacheDir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * 下载并校验 [officialUrl] 指向的安装包。
     *
     * 已经下过且校验通过的文件会直接复用——用户重复点「更新」不该再走一遍 37MB。
     *
     * @param expectedSize Release 接口给出的字节数，仅用于进度条总长与空间预估，可为 0。
     */
    suspend fun download(
        context: Context,
        officialUrl: String,
        expectedVersionCode: Int,
        expectedSize: Long = 0L,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadedApk = withContext(Dispatchers.IO) {
        val dir = cacheDir(context)
        val target = File(dir, "polaris-$expectedVersionCode.apk")

        if (target.isFile) {
            verify(context, target, expectedVersionCode)?.let {
                AgentLogger.i(TAG, "复用已下载的安装包：${target.name}")
                return@withContext it
            }
            target.delete()
        }

        // 空间检查放在发请求之前：下到一半才发现满了，既浪费时间又留下半个文件。
        val need = if (expectedSize > 0) expectedSize + 16L * 1024 * 1024 else 128L * 1024 * 1024
        val free = runCatching { context.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < need) {
            throw ApkDownloadException("存储空间不足：还需要约 ${mb(need - free)} MB")
        }

        var lastError: Throwable? = null
        for (prefix in MIRROR_PREFIXES) {
            val url = prefix + officialUrl
            val part = File(dir, "${target.name}.part")
            try {
                part.delete()
                fetch(url, part, expectedSize, onProgress)
                if (!part.renameTo(target)) throw ApkDownloadException("写入安装包失败")
                val ok = verify(context, target, expectedVersionCode)
                if (ok != null) {
                    // 只留当前这一版：cacheDir 不清理的话，每更新一次多 37MB，
                    // 而系统清理缓存的时机由不得我们。
                    dir.listFiles { f -> f.name.startsWith("polaris-") && f != target }
                        ?.forEach { it.delete() }
                    AgentLogger.i(TAG, "更新包就绪：${target.name}（${mb(target.length())} MB）")
                    return@withContext ok
                }
                target.delete()
                lastError = ApkDownloadException("安装包校验未通过")
            } catch (ce: CancellationException) {
                part.delete()
                throw ce
            } catch (t: Throwable) {
                part.delete()
                lastError = t
                AgentLogger.w(TAG, "下载源失败（${prefix.ifEmpty { "直连" }}）：${t.message}")
            }
        }
        throw ApkDownloadException(
            "下载失败：${lastError?.message ?: "所有下载源都不可用"}",
            lastError
        )
    }

    private suspend fun fetch(
        url: String,
        part: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        // runCancellable：用户点取消时同步 call.cancel()，否则线程会卡在 read() 上，
        // 直到下一次数据到达才发现协程已取消（大文件下可能几秒到几十秒）。
        client.runCancellable(
            Request.Builder().url(url).header("User-Agent", "Polaris").build()
        ) { resp ->
            if (!resp.isSuccessful) throw ApkDownloadException("HTTP ${resp.code}")
            val body = resp.body ?: throw ApkDownloadException("响应为空")
            val total = if (body.contentLength() > 0) body.contentLength() else expectedSize
            var written = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        // 双保险：cancel() 已经能打断读，这里拦住「刚好读完一块」的窗口。
                        coroutineContext.ensureActive()
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        // 节流：每 120ms 最多推一次进度，否则 Compose 会被重组打满。
                        if (now - lastEmit >= 120) {
                            lastEmit = now
                            onProgress(written, total)
                        }
                    }
                    out.flush()
                }
            }
            onProgress(written, total)
            if (total > 0 && written != total) {
                throw ApkDownloadException("下载不完整（${mb(written)} / ${mb(total)} MB）")
            }
        }
    }

    /** 校验通过返回结果，否则返回 null（调用方换源重试）。 */
    private fun verify(context: Context, apk: File, expectedVersionCode: Int): DownloadedApk? {
        if (!apk.isFile || apk.length() < MIN_APK_BYTES) return null

        // ZIP 魔数：镜像返回 HTML 错误页时体积可能也不小，但绝不会以 PK 开头。
        val head = ByteArray(2)
        runCatching { apk.inputStream().use { it.read(head) } }.getOrElse { return null }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return null

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return null
        if (archive.packageName != context.packageName) return null

        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode
        }
        // 严格相等而不是 >=：下载地址是按「最新 run 号」拼的，包里就该是那个号。
        // 不等说明拿到的不是这一版（缓存/镜像串了），宁可重下。
        if (code != expectedVersionCode) return null

        return DownloadedApk(
            file = apk,
            versionCode = code,
            signatureMatches = signaturesMatch(context, archive)
        )
    }

    private fun signaturesMatch(context: Context, archive: android.content.pm.PackageInfo): Boolean {
        val remote = archive.signatures?.firstOrNull()?.toByteArray() ?: return true
        val local = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
        }.getOrNull() ?: return true
        return local.contentEquals(remote)
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)
}
