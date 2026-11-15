package com.beichen.polaris.audio.asr

import android.content.Context

/**
 * 在「有哪些离线引擎」之间挑一个能用的。
 *
 * 优先级：**准的优先**。paraformer 装了就用它，哪怕慢一档；只有流式模型时才用流式。
 * 这个顺序不是按性能排的，是按「用户要的是听清，不是听快」排的——
 * 2026-11-15 真机反馈里，流式模型把「你在这吗」认成「你在这好」，
 * 那种错误用户没法用，快 0.8 秒毫无意义。
 */
class AsrRouter(context: Context) {

    private val ctx = context.applicationContext

    val streaming = StreamingAsr(ctx)
    val accurate = OfflineAsrEngine(ctx)

    /** 现在该用哪个；一个都没装返回 null。 */
    fun active(): AsrEngine? = when {
        accurate.isInstalled() -> accurate
        streaming.isInstalled() -> streaming
        else -> null
    }

    fun isInstalled(): Boolean = active() != null

    /** 两套模型占的字节合计（界面显示用）。 */
    fun sizeBytes(): Long = streaming.sizeBytes() + accurate.sizeBytes()

    fun ensureSession(): AsrSession? = active()?.ensureSession()

    /** 删模型 / 切模型前调用：两套都释放，避免原生层还拿着已删的文件句柄。 */
    fun releaseAll() {
        streaming.release()
        accurate.release()
    }
}

/**
 * 进程内唯一一份 [AsrRouter]。
 *
 * 为什么不是每次 new：两个引擎各自缓存了**加载好的原生识别器**（加载一次 600–900ms）。
 * 每次点麦克风都 new 一个，等于每次重载模型；而桌宠与聊天页是两条独立入口，
 * 不共享就会各加载一份，那份内存是白占的。
 */
object AsrDirectory {

    @Volatile
    private var instance: AsrRouter? = null

    fun of(context: Context): AsrRouter =
        instance ?: synchronized(this) {
            instance ?: AsrRouter(context.applicationContext).also { instance = it }
        }

    /**
     * 丢掉进程内那一份（供 AppGraph 重置与测试用）。
     *
     * 只释放原生句柄、不删任何文件：模型是用户下的，重置状态不该带走它。
     * 也不写 SharedPreferences——识别引擎的选择本来就不落盘，看的是「哪个装了」。
     */
    fun reset() {
        synchronized(this) {
            runCatching { instance?.releaseAll() }
            instance = null
        }
    }
}
