package com.beichen.polaris.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * 把下载好的安装包交给系统安装器。
 *
 * ## 为什么只能「拉起安装器」
 * 覆盖安装另一个应用需要 `INSTALL_PACKAGES`（signature 权限），普通应用拿不到；
 * 系统允许的路径只有 `ACTION_VIEW(application/vnd.android.package-archive)`。
 * 所以「应用内更新」的终点是安装器界面，用户点一下「安装」——这一步没法省，
 * 但前面找浏览器、找文件管理器、找下载目录的步骤全省了。
 *
 * ## 为什么不复用 ShizukuInstaller
 * 那个类绑定 assets 里的内置包，职责是「装别人」；这里是「装自己」，
 * 唯一相似的是权限检查那十行。为了十行把两个生命周期完全不同的流程耦在一起不划算。
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    enum class Result {
        /** 已拉起系统安装器。 */
        LAUNCHED,

        /** 没有「安装未知应用」授权：已跳设置页，用户回来再点一次。 */
        NEED_PERMISSION,

        /** 文件丢失/FileProvider 配置不对/没有安装器。 */
        FAILED
    }

    fun hasInstallPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }

    fun install(context: Context, apk: File): Result {
        if (!apk.isFile || apk.length() <= 0L) {
            AgentLogger.w(TAG, "安装包不存在：${apk.absolutePath}")
            return Result.FAILED
        }
        if (!hasInstallPermission(context)) {
            openInstallPermission(context)
            return Result.NEED_PERMISSION
        }
        return try {
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
            AgentLogger.i(TAG, "已拉起安装器（${apk.name}，${apk.length()} 字节）")
            Result.LAUNCHED
        } catch (t: Throwable) {
            AgentLogger.w(TAG, "拉起安装器失败：${t.message}")
            Result.FAILED
        }
    }

    /** 跳「安装未知应用」授权页；部分 ROM 没有这个页面，退到应用详情。 */
    fun openInstallPermission(context: Context) {
        val direct = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(direct) }.isSuccess ||
            runCatching { context.startActivity(fallback) }.isSuccess
        if (!ok) AgentLogger.w(TAG, "无法打开安装授权设置页")
    }
}
