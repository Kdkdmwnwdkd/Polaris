package com.beichen.polaris.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.net.GitHubMirror
import com.beichen.polaris.core.llm.runCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * 源通了，但慢到没有继续等的意义。
 *
 * 单独一个类型是因为处理方式不同：网络错误要清掉 `.part` 从头来，
 * 这个**必须保留已写的字节**——下一个源用 Range 从断点续，否则换源等于重新下一遍。
 */
private class SlowSourceException(message: String) : IOException(message)

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
 * 用户会得到一个叫 `.apk` 的网页。这里四层校验：
 * 长度对不对（有 Content-Length 时）、是不是 ZIP（魔数）、包名与 versionCode 对不对、
 * **SHA-256 与 Release 接口给的 digest 是否一致**。
 *
 * 前三层都是「内容自证」——包名和 versionCode 是攻击者可以随便写的。只有第四层能证明
 * 这份字节确实来自 CI：`ghfast.top` / `gh-proxy.com` 是第三方代理，运营方完全可以
 * 投放一个同包名、同版本、重签名的包，前三层全部放行。系统会因签名不同拒绝覆盖，
 * 但 UI 提示「需先卸载」时，用户照做就中招了。所以 digest 缺失时**只允许直连源**。
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    /** 缓存子目录（须与 `res/xml/file_paths.xml` 的 updates 路径一致）。 */
    private const val DIR = "updates"

    /** 小于这个体积的「APK」一定不是完整包。 */
    private const val MIN_APK_BYTES = 1L * 1024 * 1024

    /**
     * 探测窗口：每条线路试读这么多字节，按吞吐排序。
     *
     * 256 KB 是权衡：太小（32 KB）测不出带宽差异，慢源也会显得正常；太大（2 MB）三条
     * 线路光探测就要多下 6 MB。实测 256 KB 足够区分 30 KB/s 和 1 MB/s 两个数量级。
     */
    private const val PROBE_BYTES = 256 * 1024

    /** 单条线路的探测超时。死源要尽快被判定掉，否则探测本身就成了新的等待。 */
    private const val PROBE_TIMEOUT_MS = 2_500L

    /**
     * 慢源看门狗：观察窗口内平均速度低于 [SLOW_MIN_BPS] 就换源。
     *
     * 8 秒 / 40 KB/s 的取值来自实际体验：40 KB/s 下 50 MB 要 21 分钟，用户早就以为卡死了；
     * 而 8 秒足够滤掉开头的 TCP 慢启动和镜像预热，不会把一个正常源误杀。
     */
    private const val SLOW_WINDOW_MS = 8_000L

    /** 已下这么多字节才开始判速——太小的时候速度没有统计意义。 */
    private const val SLOW_MIN_BYTES = 2L * 1024 * 1024

    /** 低于这个速度算「慢源」；换源续传比死等更快。 */
    private const val SLOW_MIN_BPS = 40L * 1024

    /**
     * 统一走 `LlmHttp.download`（connect 30s / read 120s / 无总超时）。
     *
     * 不自己 new 一个客户端：R-13 把全仓 14 处各自构造收口到 `LlmHttp`，
     * 就是为了不再出现「每个功能一套超时」——新增下载器顺手 new 一个客户端，
     * 正是这条约束要挡的回归。`download` 预设本来就是给大文件传输留的。
     */
    private val client get() = LlmHttp.download

    /**
     * 加速候选表在 [GitHubMirror] 里，与模型下载共用一份。
     *
     * 为什么放在下载层而不是 `UpdateChecker`：换源是「怎么拿到字节」的问题，
     * 检查更新只需要知道官方地址。
     */

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
        expectedSha256: String = "",
        expectedSize: Long = 0L,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadedApk = withContext(Dispatchers.IO) {
        val dir = cacheDir(context)
        // 文件名带哈希前 8 位：换了源/换了版本也不会复用上一份字节。
        // 没有 digest 时退回旧命名，但这种包只可能来自直连（见下面的 sources）。
        val hashTag = if (expectedSha256.length >= 8) "-" + expectedSha256.take(8) else ""
        val target = File(dir, "polaris-$expectedVersionCode$hashTag.apk")

        if (target.isFile) {
            verify(context, target, expectedVersionCode, expectedSha256)?.let {
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

        // 没有 digest 时只信直连：镜像返回的字节无从认证，宁可慢一点，也不装来源不明的包。
        // 这不是「保守」——加速前缀是第三方运营的，能在链路上替换内容。
        val candidates = if (expectedSha256.isNotBlank()) {
            GitHubMirror.sources(officialUrl)
        } else {
            listOf(GitHubMirror.Source("直连", officialUrl))
        }

        // 先探一遍再下：固定顺序盲试的毛病不是「可能失败」，而是「可能很慢却一直不失败」。
        // 30 KB/s 的通道会把 50 MB 跑成 28 分钟，而它从头到尾都不会抛异常。
        // 只有一条候选时（无 digest）没必要探——省掉 256 KB 和 2.5 秒。
        val sources = if (candidates.size > 1) {
            val probes = probeAll(candidates)
            val ranked = GitHubMirror.rank(candidates, probes, stickyLabel(context))
            AgentLogger.i(
                TAG,
                "线路排序 → " + ranked.joinToString(" > ") { it.label } + "（" +
                    probes.joinToString("，") { p ->
                        if (p.reachable) "${p.label} ${p.bytesPerSec / 1024}KB/s" else "${p.label} 不通"
                    } + "）"
            )
            ranked
        } else {
            candidates
        }

        val part = File(dir, "${target.name}.part")
        var lastError: Throwable? = null
        // 进度与哈希都跨源保留：换源走 Range 续传，重建 digest 会让前面那段字节白算。
        var written = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for ((index, src) in sources.withIndex()) {
            try {
                written = fetch(
                    url = src.url,
                    part = part,
                    offset = written,
                    digest = digest,
                    expectedSize = expectedSize,
                    // 最后一个源不再看门狗：它再慢也得下完，否则等于主动失败。
                    watchdog = index < sources.lastIndex,
                    onProgress = onProgress
                )
                if (!part.renameTo(target)) throw ApkDownloadException("写入安装包失败")
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                val ok = verify(context, target, expectedVersionCode, expectedSha256, actualSha)
                if (ok != null) {
                    // 只留当前这一版：cacheDir 不清理的话，每更新一次多 37MB，
                    // 而系统清理缓存的时机由不得我们。
                    dir.listFiles { f -> f.name.startsWith("polaris-") && f != target }
                        ?.forEach { it.delete() }
                    saveSticky(context, src.label)
                    AgentLogger.i(
                        TAG,
                        "更新包就绪：${target.name}（${mb(target.length())} MB，源 ${src.label}）"
                    )
                    return@withContext ok
                }
                // 校验没过 = 这份字节不能用，续传没有意义：清空重来。
                target.delete()
                part.delete()
                written = 0L
                digest.reset()
                lastError = ApkDownloadException("安装包校验未通过")
            } catch (ce: CancellationException) {
                part.delete()
                throw ce
            } catch (slow: SlowSourceException) {
                // 慢源保留 .part：下一个源从断点续，不白下这几 MB。
                lastError = slow
                AgentLogger.w(TAG, "源过慢，换源续传（${src.label}）：${slow.message}")
            } catch (t: Throwable) {
                part.delete()
                written = 0L
                digest.reset()
                lastError = t
                AgentLogger.w(TAG, "下载源失败（${src.label}）：${t.message}")
            }
        }
        throw ApkDownloadException(
            "下载失败：${lastError?.message ?: "所有下载源都不可用"}",
            lastError
        )
    }

    /**
     * 流式下载并在**同一次读**里算 SHA-256；返回累计写入字节数。
     *
     * 不在下载完再读一遍文件：50 MB 读两次，一次是写盘、一次是算哈希，
     * 在慢设备上多等 1–2 秒，而哈希本来就能边收边算。
     *
     * ## 为什么要 [offset] 与外部传入 [digest]
     * 慢源被看门狗切掉后，下一个源不能从头下——已写进 `.part` 的几 MB 是白花的流量。
     * 所以这里带 `Range: bytes=<offset>-` 续传，哈希对象也由调用方持有、跨源累加。
     * 细节在于：镜像对 Range 的支持不齐，**有的会忽略 Range 直接回 200 + 整个文件**，
     * 那时只能截断重写并把哈希清零，否则算出来的是一个「前半段 + 后半段」的假值。
     *
     * @return 本次下载结束后的累计字节数（含续传前已有的部分）。
     */
    private suspend fun fetch(
        url: String,
        part: File,
        offset: Long,
        digest: java.security.MessageDigest,
        expectedSize: Long,
        watchdog: Boolean,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val builder = Request.Builder().url(url).header("User-Agent", "Polaris")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        // runCancellable：用户点取消时同步 call.cancel()，否则线程会卡在 read() 上，
        // 直到下一次数据到达才发现协程已取消（大文件下可能几秒到几十秒）。
        return client.runCancellable(builder.build()) { resp ->
            if (!resp.isSuccessful) throw ApkDownloadException("HTTP ${resp.code}")
            val body = resp.body ?: throw ApkDownloadException("响应为空")
            // 206 = 服务端接受了 Range；200 = 它不认，得从 0 重来。
            val resumed = resp.code == 206 && offset > 0
            val base = if (resumed) offset else 0L
            if (!resumed && offset > 0) {
                // 前面那段的字节不会出现在这次响应里，哈希必须清掉重算。
                digest.reset()
                AgentLogger.w(TAG, "该源不支持续传，从 0 重下")
            }
            val declared = body.contentLength()
            val total = if (declared > 0) base + declared else expectedSize
            var written = base
            var lastEmit = 0L
            val startedAt = System.currentTimeMillis()
            body.byteStream().use { input ->
                // append = resumed：续传要接在已有字节后面，不能截断。
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        // 双保险：cancel() 已经能打断读，这里拦住「刚好读完一块」的窗口。
                        coroutineContext.ensureActive()
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        // 节流：每 120ms 最多推一次进度，否则 Compose 会被重组打满。
                        if (now - lastEmit >= 120) {
                            lastEmit = now
                            onProgress(written, total)
                        }
                        if (watchdog) {
                            val elapsed = now - startedAt
                            if (elapsed >= SLOW_WINDOW_MS && written >= SLOW_MIN_BYTES) {
                                val bps = (written - base) * 1000 / elapsed
                                // 已经下到 95% 就别切了：换源要重新握手、重新定位，
                                // 剩下的 5% 多半比换源快。
                                val nearEnd = total > 0 && written * 100 >= total * 95
                                if (bps < SLOW_MIN_BPS && !nearEnd) {
                                    throw SlowSourceException("${bps / 1024}KB/s，已下 ${mb(written)}MB")
                                }
                            }
                        }
                    }
                    out.flush()
                }
            }
            onProgress(written, total)
            if (total > 0 && written != total) {
                throw ApkDownloadException("下载不完整（${mb(written)} / ${mb(total)} MB）")
            }
            // 接口给的大小也要对上：Content-Length 是镜像自己写的，不能只信它。
            if (expectedSize > 0 && written != expectedSize) {
                throw ApkDownloadException("包大小不符（期望 ${mb(expectedSize)} MB，实际 ${mb(written)} MB）")
            }
            written
        }
    }

    /**
     * 并发探一遍所有候选，按实测吞吐排序用。
     *
     * 三条线路串行探最多要 7.5 秒（每条 2.5 秒超时），并发只要 2.5 秒——
     * 探测本身不该成为新的等待。
     */
    private suspend fun probeAll(
        sources: List<GitHubMirror.Source>
    ): List<GitHubMirror.Probe> = coroutineScope {
        sources.map { s -> async(Dispatchers.IO) { probeOne(s) } }.awaitAll()
    }

    /**
     * 单条线路探测：读 [PROBE_BYTES] 就停，按「首字节耗时」和「实测吞吐」两个数打分。
     *
     * 用 `Call.timeout()` 而不是客户端级超时：这是**同一次下载里的探测阶段**，
     * 不该为了它另建一个客户端（R-13 约束），也不该改动全局的 120s 读超时。
     */
    private fun probeOne(src: GitHubMirror.Source): GitHubMirror.Probe {
        val req = Request.Builder()
            .url(src.url)
            .header("User-Agent", "Polaris")
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .build()
        val call = client.newCall(req)
        runCatching { call.timeout().timeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val t0 = System.nanoTime()
        var firstByteAt = 0L
        var got = 0L
        return try {
            call.execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.byteStream()?.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (got < PROBE_BYTES) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (firstByteAt == 0L) firstByteAt = System.nanoTime()
                            got += n
                        }
                    }
                }
            }
            val elapsedNs = (System.nanoTime() - t0).coerceAtLeast(1_000_000L)
            if (got <= 0) {
                GitHubMirror.Probe(src.label, false, Long.MAX_VALUE, 0L)
            } else {
                GitHubMirror.Probe(
                    label = src.label,
                    reachable = true,
                    rttMs = ((firstByteAt - t0) / 1_000_000).coerceAtLeast(0),
                    bytesPerSec = got * 1_000_000_000L / elapsedNs
                )
            }
        } catch (t: Throwable) {
            GitHubMirror.Probe(src.label, false, Long.MAX_VALUE, 0L)
        }
    }

    /**
     * 上次实际下完的那个源，下次优先。
     *
     * 存在 `filesDir/update/best-source.txt` 而不是 SharedPreferences：core 层没有
     * Context 依赖，一个只存一行的文件比为了这行字去拿 Context 更省事。
     * 读失败一律当「没有」，不影响正常流程。
     */
    private fun stickyFile(context: Context): File = File(context.filesDir, "update/best-source.txt")

    private fun stickyLabel(context: Context): String? = runCatching {
        stickyFile(context).takeIf { it.isFile }?.readText()?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun saveSticky(context: Context, label: String) {
        runCatching {
            stickyFile(context).apply { parentFile?.mkdirs() }.writeText(label)
        }
    }

    /** 校验通过返回结果，否则返回 null（调用方换源重试）。 */
    private fun verify(
        context: Context,
        apk: File,
        expectedVersionCode: Int,
        expectedSha256: String,
        actualSha256: String? = null
    ): DownloadedApk? {
        if (!apk.isFile || apk.length() < MIN_APK_BYTES) return null

        // ZIP 魔数：镜像返回 HTML 错误页时体积可能也不小，但绝不会以 PK 开头。
        val head = ByteArray(2)
        runCatching { apk.inputStream().use { it.read(head) } }.getOrElse { return null }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return null

        // 来源认证。前三层（大小、魔数、包名/版本）全是内容自证，只有这一层能证明
        // 「字节确实来自 CI」。下载时已经边收边算过，直接传进来；复用缓存文件时才现算。
        if (expectedSha256.isNotBlank()) {
            val actual = actualSha256 ?: runCatching { sha256Of(apk) }.getOrNull() ?: return null
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                AgentLogger.w(
                    TAG,
                    "SHA-256 不符，拒绝安装：期望 ${expectedSha256.take(12)}…，实际 ${actual.take(12)}…"
                )
                return null
            }
        }

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

    /** 本地文件的 SHA-256（hex 小写）。只在复用缓存包时用到。 */
    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signaturesMatch(context: Context, archive: android.content.pm.PackageInfo): Boolean {
        // 取不到签名一律按「不一致」处理：能装上的 APK 必然有签名，
        // 这里返回 true 只会把「读不出来」伪装成「没问题」。
        val remote = archive.signatures?.firstOrNull()?.toByteArray() ?: return false
        val local = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
        }.getOrNull() ?: return false
        return local.contentEquals(remote)
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)
}
