package com.beichen.polaris.tools

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * 等这一次网络往返的结果，**并且跟着协程一起被取消**。
 *
 * ## 为什么不用 `call.execute()`
 *
 * `execute()` 是阻塞调用：线程卡在 socket 读上，协程被取消时它毫无反应。
 * `withTimeoutOrNull` 只能停止「等」，停不掉那次请求本身——真机上点了停止，
 * 界面还得等它自己读到超时（用户日志里是 14 秒：`onInterrupt` 与 `turn.end`
 * 之间那段空白，对应那次请求自始至终没有收尾行）。
 *
 * 换成 `enqueue` 之后，取消会在 [suspendCancellableCoroutine] 这个可挂起点上
 * 变成 `CancellationException` 向上传播，同时 [Call.cancel] 真正关掉那条连接——
 * 用户点停止，就是立刻停。
 *
 * ## 两个必须处理的细节
 *
 * 1. **取消后 OkHttp 仍会回调 `onFailure`**（cancelled 也是一种 IOException）。
 *    此时 continuation 已经不再活动，再 resume 会抛 `IllegalStateException`，
 *    把真正的取消原因盖成一句莫名其妙的「already resumed」。
 * 2. **已经拿到响应但协程恰好被取消**：继续 resume 同样会失败，而 [Response]
 *    握着连接和 socket，没人 close 就是泄漏——所以那条分支里必须自己关掉。
 *
 * ## 边界（如实说明，别当成已经解决）
 *
 * 只覆盖「等响应头」这一段。响应体的读取仍是阻塞式（`BoundedBody.read`，
 * 上限 64KB），那一段不响应取消；64KB 在正常网络下是毫秒级，不会造成
 * 用户可感知的等待，但严格说它不是可取消的。
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // 取消触发的 onFailure 走到这里时 cont 已取消：什么都不做，
            // 让 CancellationException 自己向上传播，别用 IOException 覆盖它。
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
