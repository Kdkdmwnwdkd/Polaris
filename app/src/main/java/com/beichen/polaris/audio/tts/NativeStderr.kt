package com.beichen.polaris.audio.tts

import android.os.ParcelFileDescriptor
import android.system.Os
import com.beichen.polaris.core.AgentLogger
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * 接管本进程的 fd 2（stderr），把原生层写出来的话留下来。
 *
 * ## 为什么需要
 *
 * sherpa-onnx 的 C++ 在创建引擎失败时，**真正的理由只写 stderr**：缺哪个文件、
 * 哪个字段没传、哪一步加载失败。而 Kotlin 侧只收到一句
 * `Invalid OfflineTtsConfig: failed to create native OfflineTts` —— 没有文件名，
 * 没有字段名。
 *
 * 更麻烦的是 Android 上进程的 fd 2 默认不通向任何地方，那些行直接消失。
 * 于是「为什么失败」永远查不到，只能靠改代码猜一轮、出包、再猜一轮。
 * matcha 这一档就是这样耗掉了 734 到 738 五个版本。
 *
 * ## 做法
 *
 * `ParcelFileDescriptor.createPipe()` 建一对管道，`dup2` 把写端盖到 fd 2 上，
 * 读端起一条守护线程按行收。每行既进环形缓冲（供失败时拼接上下文），
 * 也写进应用日志——这样用户截图就能看到原生层原话，不必再借 adb。
 *
 * 这是幂等的：重复调用只生效一次。必须在第一次创建 TTS 引擎之前安装。
 */
object NativeStderr {

    private const val TAG = "Voice"
    private const val KEEP = 300
    private const val MAX_CHARS = 240

    private val recent = ArrayDeque<String>()
    private var installed = false

    /** 读端必须一直持有：被回收就会关掉管道，原生层的写入随之失败。 */
    private var readFd: ParcelFileDescriptor? = null

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            Os.dup2(pipe[1].fileDescriptor, 2)
            // dup2 复制了描述符，原来那个可以关掉；fd 2 仍然指向管道写端。
            pipe[1].close()
            val stream = FileInputStream(pipe[0].fileDescriptor)
            thread(isDaemon = true, name = "native-stderr") {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        remember(line)
                    }
                }
            }
            AgentLogger.i(TAG, "已接管原生层 stderr：它的报错不再丢进 /dev/null")
        }.onFailure {
            AgentLogger.w(TAG, "接管原生层 stderr 失败：${it.javaClass.simpleName} ${it.message}")
        }
    }

    @Synchronized
    private fun remember(line: String) {
        val text = line.take(MAX_CHARS)
        recent.addLast(text)
        while (recent.size > KEEP) recent.removeFirst()
        // 逐行落日志：这是唯一能在用户手机上看到原生层原话的通道。
        AgentLogger.w(TAG, "native| $text")
    }

    /** 最近 [n] 行，拼成一行附在异常里——失败现场要能自己说明原因。 */
    @Synchronized
    fun tail(n: Int = 10): String =
        if (recent.isEmpty()) "" else recent.toList().takeLast(n).joinToString(" ⟵ ")

    @Synchronized
    fun clear() = recent.clear()

    /**
     * 清掉已捕获的原生行（AppGraph 对账要求每个全局 object 有 reset）。
     *
     * 只清缓冲，**不卸载 fd 2**：`dup2` 是单向覆盖，原始 fd 2 在 Android 上本来就
     * 指向空设备，还原等于把报错重新丢回黑洞。`installed` / `readFd` 属于事实性状态，
     * 重复 install 只会再建一条管道、多漏一个 fd。
     */
    fun reset() {
        synchronized(this) { recent.clear() }
    }

    /** 把原生层原话接在异常消息后面；没有捕获到就原样返回。 */
    fun withNative(cause: Throwable?): String {
        val base = cause?.message ?: cause?.javaClass?.simpleName ?: "未知原因"
        val native = tail()
        return if (native.isBlank()) base else "$base ｜ 原生层：$native"
    }
}
