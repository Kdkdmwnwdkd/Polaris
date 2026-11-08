package com.beichen.polaris.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 检查结果。 */
data class UpdateInfo(
    /** 远端最新构建号（= CI 的 run number）。 */
    val latestCode: Int,
    /** 本机 versionCode。 */
    val currentCode: Int,
    /** 下载地址（走加速通道）。 */
    val downloadUrl: String
) {
    val hasUpdate: Boolean get() = latestCode > currentCode
}

/**
 * 应用内检查更新。
 *
 * ## 为什么自己解析而不是用现成库
 * 只服务一个固定的 Release（`tags/latest`），逻辑就是「读一个数字、比大小」。
 * 引入 AppUpdater 之类的库要加几百 KB 和一堆权限，不值。
 *
 * ## 版本号的来源
 * CI 用 `GITHUB_RUN_NUMBER` 同时写 `versionCode` 和 Release 标题 `北辰 latest (run N)`。
 * 所以只要从标题里把 N 抠出来，就能和本机 `versionCode` 直接比——
 * 不需要额外的版本清单文件，也不会出现「清单忘了更新」的错位。
 *
 * 标题里的中文不影响解析：匹配的是 `(run N)` 这个后缀，不是标题全称。
 * 改标题文案时只要保住这个后缀，自动更新就不会断。
 *
 * ## 网络失败怎么处理
 * `api.github.com` 在国内不一定通。失败时不重试、不弹窗，由 UI 显示一句
 * 「检查失败，可手动下载」并给出固定链接——比转圈转半天强。
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/Kdkdmwnwdkd/Polaris/releases/tags/latest"

    /**
     * 下载地址固定写死，不从接口返回值里取。
     *
     * 理由：接口返回的 `browser_download_url` 指向 github.com 直链，国内很慢；
     * 而且让远端决定「去哪下载」本身就没必要——这个 App 只有一个包。
     */
    const val DOWNLOAD_URL =
        "https://ghfast.top/https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest/app-debug.apk"

    /** Release 页面（给「打不开加速链接」时的手动兜底）。 */
    const val RELEASE_PAGE =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/tag/latest"

    suspend fun check(currentCode: Int): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Polaris")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val latest = Regex("\\(run (\\d+)\\)")
                    .find(body)?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("响应里没找到构建号")
                UpdateInfo(latestCode = latest, currentCode = currentCode, downloadUrl = DOWNLOAD_URL)
            } finally {
                conn.disconnect()
            }
        }
    }
}
