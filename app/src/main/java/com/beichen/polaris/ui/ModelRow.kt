package com.beichen.polaris.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.model.ModelDownloader
import com.beichen.polaris.model.ModelSpec
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 一套模型的「下载 / 删除」行。
 *
 * ## 为什么一个组件管四种模型
 *
 * 识别（24MB / 74MB）与音色（139MB / 196MB）在用户眼里是同一件事：
 * **一段可下可删的私有数据**。四套各写一遍 UI，就会出现「有的能删、有的不能删」
 * 「进度条长得不一样」这类不一致——而这正是用户反馈「和 IRIDIS 差不多」想避免的。
 *
 * ## 为什么状态各自持有
 *
 * 每行的 busy / 进度 / 错误都是本组件的 `remember`。共用一份时，下第二套会显示
 * 第一套的进度，看起来像「卡住了」。
 */
@Composable
fun ModelRow(
    spec: ModelSpec,
    title: String,
    hint: String,
    /** 删之前释放引擎的钩子（识别/音色各自不同）。 */
    onBeforeRemove: () -> Unit = {},
    /** 下载完成后回调（音色页据此刷新「能不能用」）。 */
    onChanged: () -> Unit = {},
    /**
     * 状态前缀：「正在用」「没在用」。
     *
     * 两套识别都装着时，只有一套真的参与识别（更准优先）。另一套的 24MB 是纯占位——
     * 但界面原本只写「已下载 24MB」，用户不知道它其实没被用过，也就不会想到删。
     * 把这件事写在行首，比在别处加一段说明有用得多。
     */
    badge: String? = null,
) {
    val context = LocalContext.current
    val downloader = remember(spec) { ModelDownloader(context, spec, onBeforeRemove) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()

    var ready by remember { mutableStateOf(downloader.isInstalled()) }
    var bytes by remember { mutableStateOf(downloader.bytesOnDisk()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0.0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AkListItem(
        icon = if (spec.kind == ModelSpec.Kind.ASR) Icons.Outlined.Mic else Icons.Outlined.VolumeUp,
        title = when {
            busy -> "$title · 下载中 ${(progress * 100).toInt()}%"
            ready -> "$title · 已下载 ${max(1L, bytes / 1048576)}MB"
            else -> "$title（约 ${spec.approxMb}MB）"
        },
        description = error ?: buildString {
            // 状态只在「已下载」时才有意义：没装的套不用告诉用户它在不在用。
            if (ready && badge != null) append(badge).append(" · ")
            append(
                when {
                    ready -> "点一下可以删掉，随时能重下（会先问一句）"
                    else -> hint
                }
            )
        },
        // 下载与删除是**两种**动作，不能都挂在「点一下」上：
        // 已下载时点一下应该是「我想看看/我想换」，直接删掉 74MB 太容易误触。
        onClick = when {
            busy -> null
            ready -> ({ confirmDelete = true })
            else -> ({
                busy = true
                error = null
                progress = 0.0f
                scope.launch {
                    runCatching {
                        downloader.download { done, total, _ ->
                            val ratio = done.toFloat() / max(1L, total)
                            main.post { progress = ratio }
                        }
                    }.onFailure { e -> main.post { error = e.message ?: "下载失败" } }
                    main.post {
                        busy = false
                        ready = downloader.isInstalled()
                        bytes = downloader.bytesOnDisk()
                        onChanged()
                    }
                }
            })
        },
    )

    // 误删的代价是几十上百 MB 流量，且重下要等。所以删之前问一句。
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「$title」？") },
            text = {
                Text("会删掉已下载的 ${max(1L, bytes / 1048576)}MB 模型。以后要用时再下一次就行。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    downloader.remove()
                    ready = false
                    bytes = 0
                    error = null
                    onChanged()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("留着") }
            },
        )
    }
}
