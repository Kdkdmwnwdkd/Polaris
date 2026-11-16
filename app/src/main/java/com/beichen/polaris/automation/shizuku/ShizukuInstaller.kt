package com.beichen.polaris.automation.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * 内置 Shizuku 安装器（W-13 B 方案）。
 *
 * 为什么内置 APK：
 *  - 国内应用商店基本没有 Shizuku，`market://` 会落到空白页（真机录像实证「当前页面已失效」）；
 *  - 让用户自己开浏览器找 releases 页，等于把「装一个 App」变成「找安装包」；
 *  - Shizuku 是 Apache-2.0 开源、官方签名发布，内置安装与从官网下载完全等价。
 *
 * APK 来源（2026-11-16 改）：**不再随包**，改成应用内按需下载（2.5MB，
 * `ModelSpec.Shizuku` → `filesDir/tools/shizuku/shizuku.apk`）。理由与四个语音模型一样：
 * 只有用 Shizuku 提权的人需要它，其余人没必要替这 2.5MB 付安装包体积。
 *
 * 没下过时 [install] 返回 [Result.NEED_DOWNLOAD]，调用方先下载再装。
 */
object ShizukuInstaller {

    private const val TAG = "ShizukuInstaller"

    /** 安装包文件名（下载落地后就是这个）。 */
    const val APK_NAME = "shizuku.apk"

    /** 下载落地目录：`filesDir/tools/shizuku/`，与 `ModelSpec.Shizuku` 的 home/dirName 对齐。 */
    private fun apkFile(context: Context): File =
        File(File(context.filesDir, "tools/shizuku"), APK_NAME)

    /** 解出到 cacheDir 下的子目录（须与 `res/xml/file_paths.xml` 的 shizuku 路径一致）。 */
    private const val CACHE_DIR = "shizuku"

    /** 安装动作结果：调用方据此决定「等安装完成」还是「去开权限/去下载」。 */
    enum class Result {
        /** 已拉起系统安装器（用户点「安装」即可）。 */
        INSTALLER_LAUNCHED,

        /** 未授予「安装未知应用」：已跳转该设置页，用户回来再点一次。 */
        NEED_UNKNOWN_SOURCES,

        /** 还没下载安装包：调用方应先去下载（[ModelSpec.Shizuku]），下完再点一次。 */
        NEED_DOWNLOAD,

        /** 解包/拉起失败：调用方退到官方发布页。 */
        FAILED
    }

    /** 安装包是否已下载。 */
    fun isDownloaded(context: Context): Boolean = apkFile(context).let { it.isFile && it.length() > 0 }

    /**
     * 用内置 APK 拉起系统安装器。
     *
     * 流程：检查「安装未知应用」授权 → 解出到 cacheDir/shizuku/ → FileProvider 出 content:// →
     * ACTION_VIEW(application/vnd.android.package-archive)。全程不静默安装（系统不允许，也不需要）。
     */
    fun install(context: Context): Result {
        if (!isDownloaded(context)) return Result.NEED_DOWNLOAD

        // Android 8.0+：必须先允许本应用「安装未知应用」
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val allowed = runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)
            if (!allowed) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { AgentLogger.w(TAG, "无法打开「安装未知应用」设置：${it.message}") }
                return Result.NEED_UNKNOWN_SOURCES
            }
        }

        return try {
            // 拷到 cacheDir 再交出去：FileProvider 的路径是按 cacheDir/shizuku 授权的
            // （见 res/xml/file_paths.xml），filesDir 那份不在授权范围内。
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val apk = File(dir, APK_NAME)
            apkFile(context).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            AgentLogger.i(TAG, "已拉起 Shizuku 安装器（${apk.length()} 字节）")
            Result.INSTALLER_LAUNCHED
        } catch (t: Throwable) {
            AgentLogger.w(TAG, "内置 Shizuku 安装失败：${t.message}")
            Result.FAILED
        }
    }

    /** 官方发布页（兜底与「查看更新」共用）。 */
    fun openReleasePage(context: Context) {
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
    }
}
