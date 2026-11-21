package com.beichen.polaris.model

import android.content.Context
import java.io.File

/**
 * 一笔空间账。
 *
 * [note] 是给用户看的解释，不是技术说明——他要回答的问题是「删了会损失什么」，
 * 而不是「这是什么文件」。
 */
data class SpaceLine(val label: String, val bytes: Long, val note: String)

/**
 * 本应用现在占了多少空间，按「能不能退」分账。
 *
 * ## 为什么要分三笔
 *
 * 用户看到设置里「300 多 MB」时，默认反应是「是不是你们偷偷下东西/重复下了」。
 * 实际上三笔钱的性子完全不同：
 *
 *  - **安装包 + 运行时库**：一次性、删模型不会变小。它大是因为应用里带了一个
 *    能在手机上跑 Linux 的容器（proot）与本地推理运行时，而这些 .so 必须解压到
 *    磁盘上才能执行——不是我们懒得优化。
 *  - **语音模型**：四套权重，随下随删。不想要语音的人可以一分不付。
 *  - **临时缓存**：下载残留、日志之类，可以直接清。
 *
 * 把这三笔录在一处，界面才有办法回答「我从 30 多变成 300 多，多的那 270 在哪」。
 *
 * ## 为什么不统计 dex / oat
 *
 * 系统会为每个应用额外生成一份编译产物（`/data/app/.../oat`），体积随 ROM
 * 与是否 AOT 编译而变。把它算进来只会让数字和设备对不上，反而更让人怀疑。
 * 这里只报**我们能负责的部分**：包体、解压的 .so、模型、缓存。
 */
data class StorageReport(
    val apkBytes: Long,
    val nativeBytes: Long,
    /** 四套模型各自的落盘字节；0 表示没装。 */
    val models: List<Pair<ModelSpec, Long>>,
    val cacheBytes: Long,
) {

    /** 已装的模型（只留真正占了字节的）。 */
    val installedModels: List<Pair<ModelSpec, Long>> get() = models.filter { it.second > 0 }

    /**
     * 按用途筛（语音页只算 ASR/TTS，容器页只算 CONTAINER）。
     *
     * 分用途是必要的：语音页把「Alpine 容器 3MB」算进「语音模型」，
     * 用户会以为语音又偷偷多下了东西。
     */
    fun ofKind(kind: ModelSpec.Kind): List<Pair<ModelSpec, Long>> =
        models.filter { it.first.kind == kind }

    fun bytesOfKind(vararg kinds: ModelSpec.Kind): Long =
        models.filter { it.first.kind in kinds }.sumOf { it.second }

    val modelBytes: Long get() = models.sumOf { it.second }

    /** 应用本体：包体 + 解压的运行时库。删模型不会动这一笔。 */
    val bodyBytes: Long get() = apkBytes + nativeBytes

    /** 三笔合计（不含系统生成的 dex 缓存）。 */
    val totalBytes: Long get() = bodyBytes + modelBytes + cacheBytes

    /** 「现在能立刻腾出多少」——把所有模型都删掉的收益。 */
    val reclaimableBytes: Long get() = modelBytes + cacheBytes
}

/**
 * 读出当前的空间账。
 *
 * 只走 `applicationInfo` 与 `filesDir`，不遍历整个 `/data/data`——那既慢又需要权限，
 * 而我们要回答的问题只在两处：包体（系统告诉我们路径）与我们自己写的目录。
 */
fun readStorage(context: Context): StorageReport {
    val app = context.applicationContext
    val info = app.applicationInfo

    // sourceDir 是已安装 APK 的绝对路径（split APK 时是 base）。
    val apk = info.sourceDir?.let { File(it).length() } ?: 0L
    // nativeLibraryDir 里是解压出来的 .so（本应用开了 useLegacyPackaging，
    // 因为 proot 必须从磁盘 exec）。它是「装完就固定」的一笔。
    val native = info.nativeLibraryDir?.let { dirSize(File(it)) } ?: 0L

    val models = ModelSpec.entries.map { spec -> spec to ModelDownloader(app, spec).bytesOnDisk() }

    return StorageReport(
        apkBytes = apk,
        nativeBytes = native,
        models = models,
        cacheBytes = dirSize(app.cacheDir),
    )
}

/**
 * 清掉下载残留。
 *
 * 只动两处：`cacheDir/models`（我们自己的 `.part` 与临时 zip）与 `cacheDir/updates`
 * 里**已下完的 .apk**。不清整个 cacheDir —— 那里还可能有别的模块要用的东西，
 * 一个叫「清缓存」的按钮不该替所有人做决定。
 *
 * 更新包为什么也算「可清」：装完就没用了。`ApkDownloader` 里那句「只留当前这一版」
 * 保证的是**不累积**，并不保证清掉自己——于是每次更新后，cacheDir 里都会静静躺着
 * 一个等于安装包大小的 .apk。这正是用户看到「怎么又多了几十 MB」的一个来源。
 *
 * `.part` 跳过：那可能正是用户此刻正在下载的包，删掉等于把进度清零。
 *
 * 返回释放的字节数，供界面显示「已释放 xxMB」——没有反馈的清理按钮，
 * 用户会连点三次。
 */
fun clearDownloadCache(context: Context): Long {
    var freed = 0L
    // 只清已经下完的残留。`.part` 必须留着——那可能正是此刻在下的包，
    // 删掉等于把进度清零（用户看到的是「下到 80% 突然从头开始」）。
    File(context.cacheDir, "models").listFiles()?.forEach { f ->
        if (f.name.endsWith(".part")) return@forEach
        freed += if (f.isDirectory) dirSize(f) else f.length()
        f.deleteRecursively()
    }
    File(context.cacheDir, "updates").listFiles()
        ?.filter { it.isFile && it.name.endsWith(".apk") }
        ?.forEach {
            freed += it.length()
            it.delete()
        }
    return freed
}

private fun dirSize(dir: File): Long {
    if (!dir.isDirectory) return 0
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
