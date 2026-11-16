package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.model.ModelDownloader

/**
 * 离线识别模型的下载 / 删除。
 *
 * ## 为什么不塞进 APK
 *
 * 装包曾经 361MB（两套音色模型在里面）。再塞 52MB 识别模型，对「只想打字」的人
 * 就是白付的流量与存储。而这套模型**只有第一次点语音时才会用到**——
 * 按需下载能把它从「所有人的成本」变成「用的人的 24MB」。
 *
 * ## 实现去哪了
 *
 * 746 之前这里有一整套「逐文件下载 + 自定义清单」的逻辑。2026-11-16 把四套模型
 * （两套识别 + 两套音色）收成同一张表之后，那些细节搬进了
 * [com.beichen.polaris.model.ModelDownloader]。这里只留识别特有的两件事：
 * 选对引擎、删之前让原生识别器松手。
 */
class AsrModelInstaller(context: Context, private val model: AsrModel) {

    private val router = AsrDirectory.of(context)

    private val downloader = ModelDownloader(
        context = context,
        spec = model.spec,
        // 两套都释放：原生层可能持有任一套的文件句柄，只放一套会「删了但还占着」。
        onBeforeRemove = { router.releaseAll() },
    )

    fun isInstalled(): Boolean = downloader.isInstalled()

    fun bytesOnDisk(): Long = downloader.bytesOnDisk()

    /** 下载并校验；失败抛异常，消息直接给人看。 */
    suspend fun download(onProgress: (done: Long, total: Long, label: String) -> Unit) =
        downloader.download(onProgress)

    /** 删掉模型目录（下一句识别会因此回到「没装」状态）。 */
    fun remove() = downloader.remove()
}
