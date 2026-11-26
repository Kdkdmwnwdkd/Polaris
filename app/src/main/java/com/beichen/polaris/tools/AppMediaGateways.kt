package com.beichen.polaris.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.build.BuildEngine
import com.beichen.polaris.core.build.BuildGateway
import com.beichen.polaris.core.build.BuildStore
import com.beichen.polaris.core.build.BuildTask
import com.beichen.polaris.core.media.MediaGateway
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaProviderInfo
import com.beichen.polaris.core.media.MediaRegistry
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTask
import com.beichen.polaris.core.media.MediaTaskStore
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 两个 Gateway 的 Android 实现（2026-11-26）。
 *
 * 这一层只做转发，不含判断——所有「选哪个 Provider / 该不该报错」的逻辑都在工具里，
 * 因为那里能测。这里一旦长出 `if`，就等于把可测的边界挪回了不可测的一侧。
 *
 * 每次调用都现读 `ConfigStore` / 现建 Provider，不缓存：用户在设置页刚填的 Key，
 * 下一句话就该生效。代价是每次多读一次 SharedPreferences，工具调用频率下可忽略。
 */
class AndroidMediaGateway(private val context: Context) : MediaGateway {

    override fun providers(kind: MediaKind): List<MediaProviderInfo> =
        MediaRegistry.providersFor(context, kind).map { MediaProviderInfo(it.id, it.displayName) }

    override fun hasKey(providerId: String): Boolean =
        ConfigStore(context).loadMediaKey(providerId).isNotBlank()

    override suspend fun submit(providerId: String, request: MediaRequest): MediaTask =
        MediaRegistry.submit(context, providerId, request)

    override suspend fun poll(task: MediaTask): MediaTask =
        MediaRegistry.pollTask(context, task)

    override fun recent(limit: Int): List<MediaTask> =
        MediaTaskStore.list(context).take(limit)
}

class AndroidBuildGateway(private val context: Context) : BuildGateway {

    /**
     * 缺哪一项说哪一项。
     *
     * `loadGithubConfig()` 三缺一就返回 null，工具若只说「未配置」，
     * 用户得自己排查三个输入框；这里逐项点名。
     */
    override fun missingConfig(): String? {
        val store = ConfigStore(context)
        if (store.loadGithubConfig() != null) return null
        // 走 ConfigStore 的公开方法，**不在这里猜私有键名**：
        // 键名或存储名一改，猜的那份会静默失效——配好了也报「缺 owner」，比不报更糟。
        val missing = store.missingGithubFields()
        return if (missing.isEmpty()) "配置读不出来（可能被系统清理了）"
        else "缺 ${missing.joinToString(" / ")}"
    }

    override fun isRunning(): Boolean = BuildEngine.isRunning()

    override fun latest(): BuildTask? = runCatching { BuildStore.list(context) }.getOrNull()?.firstOrNull()

    override fun trigger(): String? = BuildEngine.startInBackground(context)
}

/**
 * 本地图片 → 缩略图 data URL（工具回话时把图直接摆进对话）。
 *
 * 参数照抄 `UiScreenshotTool`：768px 长边 + JPEG 80。理由一致——**token 成本**。
 * 原图 1024×1024 PNG 转 base64 是 1.5 MB 起步，回灌进下一轮上下文等于每轮都在为
 * 同一张图付费；768/80 大约是 60-90 KB，视觉模型仍看得清。
 *
 * 返回 null 而不是抛异常：缩略图失败不该让「已生成成功」变成失败。
 */
object MediaThumbnails {

    fun dataUrl(file: File, maxDim: Int = 768, quality: Int = 80): String? {
        if (!file.isFile) return null
        return runCatching {
            // 先只读边界拿尺寸，再按 2 的幂降采样解码——避免一张 4K 图直接进内存
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val scale = (maxDim.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
            val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
