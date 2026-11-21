package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.model.Bucket
import com.beichen.polaris.model.SpaceItem
import com.beichen.polaris.model.StorageAudit
import com.beichen.polaris.model.auditStorage
import com.beichen.polaris.model.clearStorageTemporary
import com.beichen.polaris.model.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储空间：把「我的 6GB 是什么」摊成一张能看懂的账。
 *
 * ## 为什么需要这一页
 *
 * 用户看到系统设置里的「数据 6.38GB」，第一反应是「你们偷偷下了什么」。
 * 而应用里原来只有语音页那一小块账（包体 + 四套语音模型 + 缓存），
 * **本地大模型、会话、附件、知识库、日志一项都没算**——两边数字对不上，
 * 用户只能猜，猜出来的结论通常对我们不利。
 *
 * ## 这一页的三个原则
 *
 * 1. **账要平**：所有条目加起来必须等于磁盘总数，对不上的部分显式列成「其它」。
 *    漏算会以一行大数字的形式自己暴露出来，而不是悄悄消失。
 * 2. **每笔都说清「删了会损失什么」**，而不是「这是什么文件」。用户要做的决定是
 *    「要不要删」，不是「这叫什么」。
 * 3. **只把真能一键清的放进「清理」**。模型不算——删模型是要用户自己决定的事，
 *    把它算进「可腾出」会诱导他误删几个 GB 然后重新下。
 */
@Composable
fun StorageScreen() {
    val context = LocalContext.current

    // 读盘是 IO，GB 级目录在低端机上要一两秒，不能每帧做。
    // 版本号驱动重算：只有「清理」这一个动作会改磁盘。
    var version by remember { mutableIntStateOf(0) }
    var audit by remember { mutableStateOf<StorageAudit?>(null) }
    var lastFreed by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    // 页面级作用域：离开页面自动取消，不会出现「点了清理、退出后 Toast 才弹」。
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        audit = withContext(Dispatchers.IO) { auditStorage(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space5,
                top = AgentSpacing.space1,
                bottom = AgentSpacing.space8,
            )
            .navigationBarsPadding()
    ) {
        AkDisplayTitle(
            title = "存储空间",
            subtitle = "每一笔都写清「删了会损失什么」；算不准的部分单独列出来，不藏在合计里"
        )

        val a = audit
        if (a == null) {
            AkGroup(title = "正在统计") {
                AkListItem(
                    icon = null,
                    title = "正在算…",
                    description = "模型目录大的话要一两秒。这项统计要把每个文件都数一遍"
                )
            }
            return@Column
        }

        // —— 总账 ——
        AkGroup(
            title = "一共 ${formatBytes(a.totalBytes)}",
            description = if (a.reclaimableBytes >= 1048576) {
                "其中 ${formatBytes(a.reclaimableBytes)} 可以一键清掉（只清临时文件与日志，不动你的内容）"
            } else {
                "没有可以一键清掉的东西——临时文件已经很少了"
            },
        ) {
            Bucket.entries.forEach { bucket ->
                if (a.isEmpty(bucket)) return@forEach
                AkListItem(
                    icon = null,
                    title = "${bucket.title} · ${formatBytes(a.bytesOf(bucket))}",
                    description = bucket.subtitle,
                    onClick = null
                )
            }
            if (a.otherBytes >= 1048576) {
                // 这一行是**清单完整性的自检**：它一大，就说明有目录没被确认归类。
                AkListItem(
                    icon = null,
                    title = "其它 · ${formatBytes(a.otherBytes)}",
                    description = "设置、运行状态等零碎文件。这一项如果很大，说明我漏了某个目录——告诉我",
                    onClick = null
                )
            }
        }

        // —— 分组明细 ——
        Bucket.entries.forEach { bucket ->
            val items = a.of(bucket)
            if (a.isEmpty(bucket)) return@forEach
            AkGroup(
                title = bucket.title,
                description = bucket.subtitle
            ) {
                // 大的排前面：用户想先看到「谁占的」。
                items.filter { it.bytes >= 1024 }.sortedByDescending { it.bytes }.forEach { item ->
                    AkListItem(
                        icon = null,
                        title = "${item.label} · ${formatBytes(item.bytes)}",
                        description = "${item.detail}。${item.note}",
                        onClick = null
                    )
                }
            }
        }

        // —— 清理：只清临时与日志 ——
        AkGroup(
            title = "腾空间",
            description = "只清临时文件、日志与回合现场。聊天、附件、模型、知识库都不动"
        ) {
            AkListItem(
                icon = Icons.Outlined.DeleteSweep,
                title = if (busy) "正在清理…" else "清理临时文件 · 可腾出 ${formatBytes(a.reclaimableBytes)}",
                description = when {
                    busy -> "别退出，清完会自动刷新这笔账"
                    lastFreed > 0 -> "刚清掉 ${formatBytes(lastFreed)}"
                    a.reclaimableBytes < 1048576 -> "现在没什么可清的"
                    else -> "下载残留、更新包、日志。正在下载的模型包不会被删"
                },
                enabled = !busy && a.reclaimableBytes >= 1048576,
                onClick = if (busy || a.reclaimableBytes < 1048576) null else ({
                    busy = true
                    // 清理是几毫秒到几百毫秒的 IO；放主线程会让按钮卡住，用户会连点。
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { clearStorageTemporary(context) }
                        lastFreed = freed
                        busy = false
                        version++
                        Toast.makeText(
                            context,
                            if (freed >= 1024) "已腾出 ${formatBytes(freed)}" else "已经是最干净的状态了",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            )
        }

        // —— 为什么和系统设置里的数字不一样 ——
        // 这段不能省：用户十有八九会拿系统设置里的「数据 xx GB」来对。
        // 对不上而不解释，等于把「是不是你们偷偷下东西」这个怀疑留给他自己填。
        AkGroup(
            title = "和系统设置里的数字对不上，是正常的",
            description = null
        ) {
            AkListItem(
                icon = null,
                title = "系统算的是另一笔账",
                description = "系统设置会把系统为应用生成的编译产物（dex/oat）也算进「数据」，" +
                    "还会包含别的应用共享给我们的临时文件。这里只报**我们能负责的部分**：" +
                    "安装包、解压的运行时库、模型、你的内容、缓存。",
                onClick = null
            )
        }
    }
}
