package com.beichen.polaris.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.build.BuildEngine
import com.beichen.polaris.core.build.BuildStage
import com.beichen.polaris.core.build.BuildStore
import com.beichen.polaris.core.build.BuildTask
import com.beichen.polaris.core.build.GitHubBuildService
import com.beichen.polaris.core.build.GitHubConfig
import com.beichen.polaris.core.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 构建页（调研 07 号文档「BuildScreen.kt」直译）——九步流水线的收口。
 *
 * 一个「构建」按钮跑完全链路：工作区推 commit → CI → 拉 APK → 安装。
 * 配置未就绪时走首次引导（owner/repo/PAT + 测连通）；历史列表按
 * builds.json 还原，成功行出「安装」。
 */
@Composable
fun BuildScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ConfigStore(context) }

    // 配置态（已存则预填）
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var workflow by remember { mutableStateOf("build.yml") }
    var cfgLoaded by remember { mutableStateOf(false) }
    if (!cfgLoaded) {
        store.loadGithubConfig()?.let {
            owner = it.owner; repo = it.repo; pat = it.pat; workflow = it.workflow
        }
        cfgLoaded = true
    }
    val cfgReady = owner.isNotBlank() && repo.isNotBlank() && pat.isNotBlank()

    // 任务态
    var tasks by remember { mutableStateOf(BuildStore.list(context)) }
    var current by remember { mutableStateOf<BuildTask?>(null) }
    var running by remember { mutableStateOf(BuildEngine.isRunning()) }
    // 「已等待 N 分钟」需要一个走得动的时钟：进度条只在 DOWNLOADING 出现，
    // 而编译阶段要等二十多分钟，静止的文案会让人以为卡死了。
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val workspaceCount = remember { WorkspaceStore.list(context).size }

    // 构建跑在 BuildEngine 自己的作用域里，页面只读结果：
    // 1.2s 轮询 builds.json，既能看进度，也能接上「离开一会儿再回来」的构建。
    LaunchedEffect(Unit) {
        // 进程被杀过：能续的 run 续上（CI 可能已跑完），不能续的标停。
        // 放在轮询前，避免用户先看到一秒「构建中」再跳成「已停止」。
        BuildEngine.resumeInterrupted(context)
        while (true) {
            // 读盘放 IO：builds.json 几十 KB，但每 1.2s 在主线程解析一次没有道理
            val list = withContext(Dispatchers.IO) { BuildStore.list(context) }
            tasks = list
            current = list.firstOrNull()
            running = BuildEngine.isRunning()
            nowTick = System.currentTimeMillis()
            delay(1_200)
        }
    }

    fun startBuild() {
        if (!cfgReady) {
            Toast.makeText(context, "请先填写并保存 GitHub 配置", Toast.LENGTH_SHORT).show()
            return
        }
        // 不走页面 scope：切走 tab 会把整条 40 分钟的流程一起取消
        val blocked = BuildEngine.startInBackground(context)
        if (blocked != null) {
            Toast.makeText(context, blocked, Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        Toast.makeText(context, "构建已开始，可离开此页", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(title = AppDest.BUILD.title)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberPersistentScrollState())
                .padding(horizontal = AgentSpacing.space5)
                .padding(bottom = AgentSpacing.space5)
        ) {
            // ——— GitHub 配置（首次引导）———
            AkSectionCard(
                title = "GitHub 配置",
                icon = Icons.Outlined.Cloud,
                description = "PAT 需 Contents: R&W + Actions: R&W 权限；明文存于本机私有配置（开源前迁移加密）。"
            ) {
                BuildField(label = "Owner", value = owner, onValueChange = { owner = it }, placeholder = "Kdkdmwnwdkd")
                Spacer(Modifier.height(AgentSpacing.space2))
                BuildField(label = "仓库", value = repo, onValueChange = { repo = it }, placeholder = "Polaris")
                Spacer(Modifier.height(AgentSpacing.space2))
                BuildField(label = "PAT", value = pat, onValueChange = { pat = it }, placeholder = "github_pat_…")
                Spacer(Modifier.height(AgentSpacing.space2))
                BuildField(label = "Workflow", value = workflow, onValueChange = { workflow = it }, placeholder = "build.yml")
                Spacer(Modifier.height(AgentSpacing.space3))
                Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                    AkPrimaryButton(
                        text = "保存配置",
                        onClick = {
                            store.saveGithubConfig(GitHubConfig(owner.trim(), repo.trim(), pat.trim(), workflow.trim()))
                            Toast.makeText(context, "已保存 GitHub 配置", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AkOutlinedButton(
                        text = "测连通",
                        onClick = {
                            if (!cfgReady) {
                                Toast.makeText(context, "先填完 Owner/仓库/PAT", Toast.LENGTH_SHORT).show()
                                return@AkOutlinedButton
                            }
                            scope.launch {
                                val cfg = GitHubConfig(owner.trim(), repo.trim(), pat.trim(), workflow.trim())
                                GitHubBuildService(cfg).testConnection()
                                    .onSuccess { login ->
                                        Toast.makeText(context, "连接成功：$login", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure { e ->
                                        Toast.makeText(context, "连接失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(AgentSpacing.space4))

            // ——— 构建按钮 + 当前进度 ———
            AkSectionCard(
                title = "构建",
                icon = Icons.Outlined.Build,
                description = "工作区 $workspaceCount 个文件将随本次构建提交到 polaris-workspace/，" +
                    "CI 编译后自动拉回 APK。整条流水线约 20–30 分钟（含 138 项 QA harness），" +
                    "期间可以离开此页，回来进度还在。"
            ) {
                AkPrimaryButton(
                    text = when {
                        running -> "构建中…"
                        workspaceCount > 0 -> "提交并构建"
                        else -> "构建"
                    },
                    onClick = { startBuild() },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )

                // 「停止」只停本地跟踪：云端 run 会继续跑完。
                // 不给按钮的话，用户唯一的「取消」就是杀进程——那会把历史留成
                // 一条永远不动的「构建中」，正是这次要消灭的状态。
                if (running) {
                    Spacer(Modifier.height(AgentSpacing.space2))
                    AkOutlinedButton(
                        text = "停止跟踪",
                        onClick = {
                            BuildEngine.stop(context)
                            Toast.makeText(
                                context,
                                "已停止跟踪；CI 可能仍在 GitHub 上跑完",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val cur = current
                if (cur != null) {
                    Spacer(Modifier.height(AgentSpacing.space3))
                    Text(
                        stageText(cur, nowTick),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (cur.stage) {
                            BuildStage.FAILED -> MaterialTheme.colorScheme.error
                            BuildStage.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                            BuildStage.SUCCEEDED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    // 3.5 拆模块：跨模块的 public 属性不能 smart cast（编译器不保证 getter 稳定），
                    // 先取到局部 val 再判空。
                    val dlProgress = cur.downloadProgress
                    if (cur.stage == BuildStage.DOWNLOADING && dlProgress != null) {
                        Spacer(Modifier.height(AgentSpacing.space2))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(dlProgress.coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    val apkPath = cur.apkPath
                    if (cur.stage == BuildStage.SUCCEEDED && apkPath != null) {
                        Spacer(Modifier.height(AgentSpacing.space2))
                        AkPrimaryButton(
                            text = "安装（${formatBytes(cur.sizeBytes ?: 0)}）",
                            onClick = { installApk(context, apkPath) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(AgentSpacing.space4))

            // ——— 构建历史 ————
            AkSectionCard(
                title = "构建历史",
                icon = Icons.Outlined.Download,
                description = "「构建 #N」与 GitHub run number 一一对应，出问题能精确定位。"
            ) {
                if (tasks.isEmpty()) {
                    Text(
                        "还没有构建记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    tasks.forEach { task ->
                        // 组合期不直接 stat：remember 按 (apkPath, stage) 缓存，
                        // 同一任务重组时不再每帧一次系统调用。文件真被删了也不怕——
                        // installApk 自己会检查并提示，这里只决定按钮显不显示。
                        val apkReady = remember(task.apkPath, task.stage) {
                            task.stage == BuildStage.SUCCEEDED && task.apkPath != null &&
                                File(task.apkPath).exists()
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = AgentSpacing.space2),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildHeadline(task),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    detailLine(task, apkReady),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (task.stage == BuildStage.FAILED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (apkReady) {
                                // apkPath 在 apkReady 里已判非空，但那个判断跨了 lambda，
                                // 智能转换不再成立，这里用 ?.let 显式收窄
                                AkTextButton(
                                    text = "安装",
                                    onClick = { task.apkPath?.let { installApk(context, it) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stageText(task: BuildTask, nowMs: Long): String = when (task.stage) {
    BuildStage.PUSHING -> "正在提交工作区…"
    BuildStage.QUEUED -> "云端排队中…（已触发 CI）${waitedSuffix(task, nowMs)}"
    BuildStage.BUILDING -> {
        val n = task.runNumber
        val head = if (n != null) "CI 编译中…（run #$n）" else "CI 编译中…"
        head + waitedSuffix(task, nowMs)
    }

    BuildStage.DOWNLOADING -> {
        val p = task.downloadProgress
        if (p != null) "下载 APK… ${(p * 100).toInt()}%" else "下载 APK…"
    }

    BuildStage.SUCCEEDED -> "构建成功"
    BuildStage.FAILED -> "构建失败：${task.error ?: "未知原因"}"
    BuildStage.CANCELLED -> "已停止跟踪：${task.error ?: "CI 可能仍在运行"}"
}

/**
 * 「· 已等待 N 分钟」。
 *
 * 原本这里写的是「通常 3-6 分钟」——那是纯编译时间。实测一次 run 从 dispatch 到
 * 出包是 **20-30 分钟**（CI 要跑 QA harness：138 个 runner + 六条门禁）。
 * 按 3-6 分钟预期去等 25 分钟，用户会认定卡死然后点停止。
 *
 * 改成显示真实已等待时长：数字一直涨，就说明它没卡。
 */
private fun waitedSuffix(task: BuildTask, nowMs: Long): String {
    val minutes = (nowMs - task.updatedAt) / 60_000L
    return if (minutes >= 1) " · 已等待 $minutes 分钟" else ""
}

private fun buildHeadline(task: BuildTask): String {
    val n = task.runNumber
    val head = if (n != null) "构建 #$n" else "构建"
    val status = when (task.stage) {
        BuildStage.SUCCEEDED -> "成功"
        BuildStage.FAILED -> "失败"
        BuildStage.CANCELLED -> "已停止"
        else -> "进行中"
    }
    return "$head · $status"
}

private fun detailLine(task: BuildTask, apkReady: Boolean): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.createdAt))
    val sha = task.commitSha?.take(8) ?: "?"
    // 包被 pruneApks 清掉之后就别再报大小了：写着「43.9 MB」却没有安装按钮，
    // 看着像按钮坏了，其实文件已经不在了。直接把原因写在原来的位置。
    val size = if (task.stage == BuildStage.SUCCEEDED && !apkReady) {
        " · 包已清理"
    } else {
        task.sizeBytes?.let { " · ${formatBytes(it)}" } ?: ""
    }
    val err = if (task.stage == BuildStage.FAILED || task.stage == BuildStage.CANCELLED) {
        " · ${task.error}"
    } else ""
    return "$time · $sha$size$err"
}

/** 拉起系统安装器；未授权「安装未知应用」时先跳设置引导。 */
private fun installApk(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "APK 文件不存在：$path", Toast.LENGTH_SHORT).show()
        return
    }
    if (!context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "请先允许本应用「安装未知应用」", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "无法拉起安装器：${it.message}", Toast.LENGTH_LONG).show()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 配置字段：等宽单行输入 + 左侧标签（与 MCP/媒体页同语汇）。 */
@Composable
private fun BuildField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = BuildMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = BuildMonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                }
            )
        }
    }
}

/** 构建页等宽文本样式（noFontPadding）。 */
private val BuildMonoStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    textAlign = TextAlign.Start
)
