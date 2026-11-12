package com.beichen.polaris.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检查结果。 */
data class UpdateInfo(
    /** 远端最新构建号（= CI 的 run number）。 */
    val latestCode: Int,
    /** 本机 versionCode。 */
    val currentCode: Int,
    /** Release 上这个包的资产名（正常是 app-release.apk）。 */
    val assetName: String,
    /** 资产字节数；接口没给时为 0（进度条退化成不确定态）。 */
    val assetSize: Long,
    /** 官方下载地址（不含加速前缀，由 [ApkDownloader] 决定走哪条链路）。 */
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
 * ## 资产名为什么从接口读
 * 曾经写死成 `app-debug.apk`，而 CI 在 2026-11-12 把发布物换成了 `app-release.apk`
 * 并删掉了 debug 资产——写死的那条链接直接 404，用户点「更新」下到一个错误页。
 * 现在从 `assets[]` 里挑第一个 `.apk`，换名字也能跟着走；常量只作为接口异常时的兜底。
 *
 * ## 网络失败怎么处理
 * `api.github.com` 在国内不一定通。失败时不重试、不弹窗，由 UI 显示一句
 * 「检查失败」并保留「浏览器打开」的兜底——比转圈转半天强。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val API =
        "https://api.github.com/repos/Kdkdmwnwdkd/Polaris/releases/tags/latest"

    private const val RELEASE_DOWNLOAD_BASE =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest/"

    /** 接口没给出资产名时用它（当前 CI 的产物名）。 */
    const val DEFAULT_ASSET = "app-release.apk"

    /** 官方下载地址（给「浏览器打开」兜底用；应用内下载走 [ApkDownloader] 的多源）。 */
    const val DOWNLOAD_URL =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest/app-release.apk"

    /** Release 页面（给「打不开加速链接」时的手动兜底）。 */
    const val RELEASE_PAGE =
        "https://github.com/Kdkdmwnwdkd/Polaris/releases/tag/latest"

    /** 按资产名拼官方地址。 */
    fun officialUrl(assetName: String): String = RELEASE_DOWNLOAD_BASE + assetName

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
                val json = JSONObject(body)
                val latest = Regex("\\(run (\\d+)\\)")
                    .find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("响应里没找到构建号")

                var assetName = DEFAULT_ASSET
                var assetSize = 0L
                val assets = json.optJSONArray("assets")
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        assetName = name
                        assetSize = a.optLong("size", 0L)
                        break
                    }
                }
                UpdateInfo(
                    latestCode = latest,
                    currentCode = currentCode,
                    assetName = assetName,
                    assetSize = assetSize,
                    downloadUrl = officialUrl(assetName)
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}
