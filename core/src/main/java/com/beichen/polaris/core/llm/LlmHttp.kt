package com.beichen.polaris.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全项目共用的 OkHttp 配置（W-46 建立，R-13 收口为三个预设）。
 *
 * ## 为什么必须收口
 *
 * 原来有 14 处各自 `OkHttpClient.Builder()`，超时是**各写各的**：
 * 余额查询 10s、Web 搜索 12s、市场 20s、媒体生成 60~120s、模型下载 120s、LLM 180s。
 * 这不致命，但代价是重试、超时、UA、日志四条横切逻辑只能各写一遍，
 * 改一次要翻 14 个文件，而且**改漏了不会有任何提示**。
 *
 * 更实际的损失是没人说得清「这个请求的超时是多少」——得逐个翻文件才知道。
 *
 * ## 三个预设，按「会不会长时间没有字节」分
 *
 * | 预设 | read | 用于 |
 * |---|---|---|
 * | [llm] | 180s | 推理（首 token 前思考半分钟是常态）、MCP、媒体生成 |
 * | [short] | 30s | 列表、余额、搜索、git API、技能市场 |
 * | [download] | 120s | 生成结果下载、GGUF 模型文件 |
 *
 * **取值原则：收口时每一处都只放宽、不收紧。** 30s 的 `short` 覆盖了原来 10/12/20 四档，
 * 120s 的 `download` 覆盖原来两处 120s，180s 的 `llm` 覆盖原来 60/120/180 三档。
 * 全部是等号或放大——所以这次收口不可能让原本能跑通的请求开始超时。
 *
 * ## readTimeout 的语义（这段值得记住）
 *
 * 它是「**两次收到字节之间的最长间隔**」，不是整通时长。推理模型在吐第一个 token 前
 * 思考半分钟是常态，长系统提示与弱网下更久。W-46 之前用的是 OkHttp 默认 10 秒，
 * 结果请求在 10s 抛 `SocketTimeoutException`，被当成「网络抖动」重试三次，
 * 用户干等 40 秒后看到「调用失败」——日志里全是网络超时，与真实原因（模型在想）完全对不上。
 *
 * ## callTimeout = 0 的理由
 *
 * 0 = 不限整通时长。一次长回答可能要流几分钟，按整通计时会误杀；
 * 真正的失控由协程取消（用户点停止 / ViewModel 回收）兜底。
 */
object LlmHttp {

    /** 推理通道：可能长时间静默（LLM / MCP / 媒体生成）。 */
    val llm: OkHttpClient by lazy { build(connect = 15, read = 180, write = 60, call = 0) }

    /** 短请求：列表 / 余额 / 搜索 / git API / 技能市场。 */
    val short: OkHttpClient by lazy { build(connect = 10, read = 30, write = 30, call = 60) }

    /** 传输通道：生成结果与模型文件的下载（单次读取间隔可能很久）。 */
    val download: OkHttpClient by lazy { build(connect = 30, read = 120, write = 60, call = 0) }

    /**
     * 兼容旧调用点，语义等同 [llm]。
     *
     * 保留它而不是直接改名，是因为 LLM 三处客户端（`LLMClient` / `ClaudeClient` / `GeminiClient`）
     * 已经在用这个名字，改名没有收益、只有风险。
     */
    val shared: OkHttpClient get() = llm

    private fun build(connect: Long, read: Long, write: Long, call: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(connect, TimeUnit.SECONDS)
            .readTimeout(read, TimeUnit.SECONDS)
            .writeTimeout(write, TimeUnit.SECONDS)
            .callTimeout(call, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}

/**
 * 可取消的阻塞请求（审计 F22）。
 *
 * ## 修的是什么
 *
 * 全项目 32 处 `newCall(request).execute()` 都是**阻塞**调用。上面 [LlmHttp] 的注释写着
 * 「真正的失控由协程取消（用户点停止 / ViewModel 回收）兜底」——但这句话在此之前是假的：
 * `execute()` 阻塞在 socket 读上时，`Job.cancel()` 只是把协程标记成取消，**线程照样停在
 * `readUtf8Line()` 里**，要等对端发完或 readTimeout（llm 预设 180s）才返回。
 *
 * 用户看到的现象：点「停止」后界面立刻恢复，但请求还在跑——流式 token 继续被服务端推送、
 * 继续计费，连接与线程也一直被占着。停止按钮只停了「显示」，没停「请求」。
 *
 * ## 做法
 *
 * 把 `Call` 挂到当前协程的完成回调上：协程被取消时同步 `call.cancel()`，OkHttp 会立刻
 * 中断 socket 读并让 `execute()` 抛 `IOException("Canceled")`。再把那个 IOException
 * 还原成 [CancellationException]——否则上层会把它当网络故障，触发重试与「网络异常」提示，
 * 用户明明是自己点的停止。
 *
 * ## 为什么不是 `suspendCancellableCoroutine` + `enqueue`
 *
 * 本项目的流式读取是「拿到 Response 后在自己的循环里逐行读」，enqueue 只能异步拿到响应头，
 * 后续仍是阻塞读，等于没解决。改动面最小、语义最直白的还是「阻塞 + 取消时 cancel call」。
 *
 * 回调在线程池上执行，`dispose()` 防止正常结束后残留引用。
 */
suspend fun <T> OkHttpClient.runCancellable(
    request: Request,
    block: suspend (Response) -> T,
): T {
    val call = newCall(request)
    val job = currentCoroutineContext()[Job]
    val watchdog = job?.invokeOnCompletion { cause ->
        // 正常完成时 cause 为 null，不能误伤已成功的请求
        if (cause is CancellationException) runCatching { call.cancel() }
    }
    try {
        val resp = call.execute()
        try {
            return block(resp)
        } finally {
            resp.close()
        }
    } catch (e: IOException) {
        // call.cancel() 的表现是 IOException("Canceled")；协程确实被取消时不能当网络错误
        if (job?.isCancelled == true) {
            throw CancellationException("请求已随协程取消").apply { initCause(e) }
        }
        throw e
    } finally {
        watchdog?.dispose()
    }
}
