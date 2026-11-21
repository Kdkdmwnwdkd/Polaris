package com.beichen.polaris.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.asr.LocalSpeechInput
import com.beichen.polaris.audio.asr.AsrDirectory

/**
 * 聊天页的「自带离线识别」录音层。
 *
 * ## 为什么聊天页也要它
 *
 * 聊天页原来走系统识别页（`RecognizerIntent`）——那是一个**系统弹的全屏 Activity**，
 * 录音与识别都在系统进程里。国行机上这条路经常整条不通：Google 的识别服务在国内不可达，
 * 厂商引擎又不认中文，弹出来就是「说什么都没字」。
 *
 * 自带模型把这条路收回到我们自己的进程里：录、算、出字都在本地，不依赖任何外部服务。
 *
 * ## 为什么做成 Dialog 而不是塞进输入框
 *
 * 录音要占住整个麦克风、要显示实时文本、还要能被一键取消。这三件事塞进输入框
 * 会把 `ChatScreen` 撑长（那边有行数冻结），而它本质上是**一次模态操作**：
 * 说完就消失，不该常驻在页面上。
 */
@Composable
fun LocalVoiceSheet(
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(true) }
    val input = remember { LocalSpeechInput(ctx, AsrDirectory.of(ctx), AudioGraph.arbiter(ctx)) }

    DisposableEffect(Unit) {
        val started = input.start { event ->
            // 录音线程的回调，Compose 状态必须在主线程写。
            main.post {
                when (event) {
                    is LocalSpeechInput.Event.Partial -> text = event.text
                    is LocalSpeechInput.Event.Final -> {
                        listening = false
                        // 端点自己判停时会走到这里：说了话就直接交，没说话就留在原地。
                        // 这里不能自动重开麦（cancel 与下一次 start 会抢同一个 AudioRecord），
                        // 所以文案要说清下一步动作是「再点一次麦克风」，而不是「重说」——
                        // 面板上此刻唯一的按钮是「关闭」。
                        if (event.text.isNotBlank()) {
                            onSubmit(event.text)
                        } else {
                            error = "没听清，关掉后再点一次麦克风"
                        }
                    }
                    is LocalSpeechInput.Event.Failed -> {
                        listening = false
                        error = event.message
                    }
                }
            }
        }
        if (!started) listening = false
        onDispose { input.cancel() }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (listening) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = if (listening) "  正在听（说完自己停）" else "  已停下",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = error ?: text.ifBlank { if (listening) "说点什么…" else "" },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("取消") }
                    TextButton(
                        onClick = {
                            if (listening) {
                                // 交给录音线程收尾：最终文本会从 Final 回来。
                                listening = false
                                input.stop()
                            } else {
                                onClose()
                            }
                        },
                    ) { Text(if (listening) "说完了" else "关闭") }
                }
            }
        }
    }
}
