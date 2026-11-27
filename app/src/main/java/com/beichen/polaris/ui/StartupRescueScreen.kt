package com.beichen.polaris.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 启动失败救援页（2026-11-27 真机）。
 *
 * 真机现象：一小时十次 `Cannot create an instance of ChatViewModel`，
 * 每一次都是「点开 App → 白一下 → 回桌面」，用户既进不去、也拿不到任何证据。
 * 他最后能做的只有「清应用数据」——会话、日志、崩溃报告一起没。
 *
 * 这个页面的唯一职责是**把死路变成岔路**：
 *  ① 把异常原样摆出来并允许复制（用户能发给我）；
 *  ② 给一条不丢会话的自救路（清运行态 → 重建）；
 *  ③ 把「隔离业务状态」这种重手段留给用户自己按，并写明后果。
 *
 * 刻意不复用 `AppScaffold`：那时 ViewModel 还不存在，整套导航都建立在 vm 上。
 * 这里只用最基础的组件，保证「VM 构造失败」这条路径上不再依赖任何业务代码——
 * 否则救援页自己也可能崩，用户就真的没路了。
 */
@Composable
fun StartupRescueScreen(
    /** ViewModel 构造时抛出的异常；null 表示「就是建不出来，且没有原因可给」。 */
    cause: Throwable?,
    /** 清掉可再生状态后重建 Activity。 */
    onRetry: () -> Unit,
    /** 隔离业务状态后重建。调用方负责先做隔离。 */
    onResetBusinessState: () -> Unit,
) {
    val ctx = LocalContext.current
    // 堆栈字符串一次算完就存住：每次重组重算一遍，等于在「已经出错了」的页面上
    // 反复做几百行字符串拼接，纯属给主线程添堵。
    val detail = remember(cause) { rescueDetail(cause) }

    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = AgentSpacing.space5)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                text = "启动时出错了",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "对话页没能创建起来，所以界面停在这里。日志与崩溃报告都还在，" +
                    "下面的内容可以复制发出去。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AkSectionCard(
                title = "错误详情",
                icon = Icons.Outlined.WarningAmber,
                description = "含 Caused by 链；复制后可直接发给开发者"
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(AgentSpacing.space3)
                    )
                }
                Spacer(Modifier.height(AgentSpacing.space2))
                AkTextButton(
                    text = "复制错误详情",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("北辰 启动失败", detail))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            AkSectionCard(
                title = "先试试这个",
                icon = Icons.Outlined.Refresh,
                description = "只清「上次跑到哪一步」这类可再生状态，会话内容不动"
            ) {
                Text(
                    text = "会删掉：运行态快照、回合日志、后台任务队列、未发送的草稿。\n" +
                        "不会删：会话记录、日志、崩溃报告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                AkTextButton(
                    text = "清理运行状态并重试",
                    icon = Icons.Outlined.Refresh,
                    onClick = onRetry
                )
            }

            AkSectionCard(
                title = "还是不行的话",
                icon = Icons.Outlined.DeleteSweep,
                description = "把可能带病的业务状态整体挪到隔离目录（不删除）"
            ) {
                Text(
                    text = "会挪走：会话记录、草稿、提示词、记忆、待办、书签、工作流。\n" +
                        "挪到 files/startup-quarantine/<时间>/，目录里有 README 说明怎么搬回来。\n" +
                        "日志与崩溃报告**不会**被挪走——那是查这件事的唯一证据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                AkTextButton(
                    text = "重置业务状态并重启",
                    icon = Icons.Outlined.DeleteSweep,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onResetBusinessState
                )
            }

            Spacer(Modifier.height(AgentSpacing.space5))
            Text(
                text = "如果连续三次都停在这一页，App 会在下次启动时自行隔离一次——" +
                    "那是最后一道兜底，不是另一条路。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 把异常展开成「类型 + 消息 + 完整堆栈（含 Caused by）」。
 *
 * 用 `printStackTrace` 而不是 `toString()`：`Cannot create an instance of
 * ChatViewModel` 这句话没有任何信息量，有用的是它下面那段 `Caused by:`。
 * 两者差着一个「能不能定位」，不是格式偏好。
 */
private fun rescueDetail(cause: Throwable?): String {
    if (cause == null) return "(没有取到异常对象)"
    val head = buildString {
        appendLine(cause.javaClass.name)
        cause.message?.trim()?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    }
    return head + "\n" + runCatching {
        // 不用 `toString()`：`Cannot create an instance of ChatViewModel` 这句话没有任何
        // 信息量，有用的是它下面的 `Caused by:` 段。printStackTrace 才会展开。
        val sw = java.io.StringWriter()
        java.io.PrintWriter(sw).use { cause.printStackTrace(it) }
        sw.toString().trimEnd()
    }.getOrDefault("(堆栈读取失败)")
}
