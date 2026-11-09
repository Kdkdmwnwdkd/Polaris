package com.beichen.polaris.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.CrashLogger
import com.beichen.polaris.core.DiagnosticsExporter
import com.beichen.polaris.core.CrashRecord
import com.beichen.polaris.ui.contract.LogsContract
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页（视觉规范 §3.7 日志级别四色 + UX v1 §1.6）。
 *
 * 从旧自动化页拆出：日志是「出问题时才来」的排查型页面，一屏一条条看，
 * 因此用 LazyColumn（不随外层滚动，长日志不掉帧）+ 级别筛选 + 条目展开。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「运行日志」+ 右上「刷新」）。
 * 进入本页即由宿主标记已读，抽屉上的未读 ERROR 计数随之归零（§6.5.2）。
 */
@Composable
fun LogsScreen(vm: LogsContract) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // 解析一次随 logs 缓存：筛选 / 渲染都读同一份条目，不在每次重组时反复 split
    val entries = remember(logs) { logs.map { it.toLogEntry() } }
    var filter by rememberSaveable { mutableStateOf(LEVEL_ALL) }
    var expandedIndex by rememberSaveable { mutableStateOf(-1) }
    var exportHint by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }

    // —— 最近崩溃（真机第四轮：进程被杀时内存日志来不及落盘，崩溃报告是唯一证据）——
    var crashes by remember { mutableStateOf<List<CrashRecord>>(emptyList()) }
    var crashExpanded by rememberSaveable { mutableStateOf("") }
    var crashDirPath by remember { mutableStateOf("") }
    // 未读崩溃文件名。单条粒度而不是整页一个布尔：用户可能只想看最新那次，
    // 一进页面就全标已读，会让更早的几条悄悄失去提示（下次崩溃时他以为只有一条）。
    var unseenCrashNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 重新扫描崩溃目录，并把崩溃记录回放到 AgentLogger（此刻日志目录已就绪，可落盘）。 */
    fun reloadCrashes() {
        crashes = CrashLogger.listCrashFiles(ctx)
        unseenCrashNames = CrashLogger.unseenCrashRecords(ctx).map { it.fileName }.toSet()
        crashDirPath = CrashLogger.crashDir(ctx).absolutePath
        CrashLogger.replayPendingIntoLogger(ctx)
    }

    // 进页面即扫一次：CrashLogger 在 Application 里安装，回放时日志目录尚未就绪，
    // 这里补一次，崩溃内容才会随当日日志真正落盘并出现在下面的列表里。
    LaunchedEffect(Unit) {
        reloadCrashes()
        vm.refreshLogs()
    }

    // 导出回执 3s 后自动消失：不引入 Snackbar 宿主，页面自己的一句话反馈足够
    LaunchedEffect(exportHint) {
        if (exportHint != null) {
            delay(3000)
            exportHint = null
        }
    }

    val visible = remember(entries, filter) {
        if (filter == LEVEL_ALL) entries else entries.filter { it.level == filter }
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(
            title = AppDest.LOGS.title,
            actionIcon = Icons.Outlined.Refresh,
            actionDescription = "刷新",
            onAction = {
                vm.refreshLogs()
                reloadCrashes()
            }
        )

        // —— 最近崩溃（有记录才出现；进程崩溃时内存日志会丢，崩溃报告是唯一证据）——
        if (crashes.isNotEmpty()) {
            Spacer(Modifier.height(AgentSpacing.space3))
            CrashSection(
                records = crashes,
                unseen = unseenCrashNames,
                dirPath = crashDirPath,
                expandedName = crashExpanded,
                onToggle = { name ->
                    crashExpanded = if (crashExpanded == name) "" else name
                    // 展开即视为看过，且只标记这一条——这是 markSeen 与 markAllSeen 的分工：
                    // 横幅上的「查看 / 关闭」是全标（用户已经被告知了全部），列表里点开某条
                    // 只说明他关心这一条。
                    if (name in unseenCrashNames) {
                        CrashLogger.markSeen(ctx, name)
                        unseenCrashNames = unseenCrashNames - name
                    }
                },
                onClear = {
                    CrashLogger.clearCrashFiles(ctx)
                    crashExpanded = ""
                    reloadCrashes()
                    vm.refreshLogs()
                }
            )
        }

        // —— 级别筛选 chips（§6.7：选中 primary@12% + on-primary-container）——
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AgentSpacing.space5),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            LEVELS.forEach { level ->
                AkChip(
                    text = level,
                    selected = filter == level,
                    onClick = { filter = level }
                )
            }
        }

        Spacer(Modifier.height(AgentSpacing.space3))

        // —— 操作行：导出（复制到剪贴板）/ 清空（二次确认）——
        //
        // v5：三个描边按钮（导出 / 诊断包 / 清空）原来占满一整行 100dp，
        // 是这一页最重的元素，可它们全是低频维护动作。改成一行纯文字动作：
        // 低频操作不该有按钮的体量，需要它的人仍然一眼看得见。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AkTextButton(
                text = "导出日志",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    val text = vm.exportLogs()
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("北辰 运行日志", text))
                    exportHint = "已复制 ${logs.size} 条日志到剪贴板"
                }
            )
            AkTextButton(
                text = "诊断包",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    // R10：诊断文本 + 回合事件流一起发。没有回合日志时只有文本，
                    // 回执里不再谎称「含事件」——用户能据此判断这次有没有可用证据。
                    val files = DiagnosticsExporter.exportBundle(ctx, vm.exportLogs())
                    if (files.isNotEmpty()) {
                        DiagnosticsExporter.share(ctx, files)
                        exportHint = if (files.size > 1) {
                            "诊断包已生成（含回合事件），选择应用发送"
                        } else {
                            "诊断包已生成（配置已脱敏），选择应用发送"
                        }
                    } else {
                        exportHint = "诊断包生成失败，请改用「导出」复制日志"
                    }
                }
            )
            Spacer(Modifier.weight(1f))
            AkTextButton(
                text = "清空",
                onClick = { showClear = true },
                tint = MaterialTheme.colorScheme.error
            )
        }

        val hint = exportHint
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = LocalAkColors.success,
                modifier = Modifier.padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3
                )
            )
        }

        // —— 列表 / 空态 ——
        if (visible.isEmpty()) {
            AkEmptyState(
                icon = Icons.Outlined.Description,
                title = if (filter == LEVEL_ALL) "暂无日志" else "没有 $filter 级日志",
                description = if (filter == LEVEL_ALL) {
                    "发一条消息或触发一次技能后，这里会实时显示 LLM 请求、工具调用与错误。"
                } else {
                    "切换到「全部」看看其他级别的记录。"
                },
                actionLabel = "刷新",
                onAction = { vm.refreshLogs() }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space2,
                    bottom = AgentSpacing.space8
                ),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
            ) {
                itemsIndexed(visible, key = { index, _ -> index }) { index, entry ->
                    LogRow(
                        entry = entry,
                        expanded = expandedIndex == index,
                        onClick = { expandedIndex = if (expandedIndex == index) -1 else index }
                    )
                }
                // 页脚：日志文件落盘位置（mono 小字，排查时要能照着找文件）
                item {
                    Text(
                        text = "日志文件：${vm.logFilePath()}",
                        style = machineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            top = AgentSpacing.space4,
                            bottom = AgentSpacing.space2
                        )
                    )
                }
            }
        }
    }

    // —— 清空二次确认：日志是排查依据，删前拦一道 ——
    AkModalHost(target = if (showClear) Unit else null) { _, dialogVisible ->
        AkConfirmDialog(
            title = "清空运行日志",
            message = "确认清空当前 ${logs.size} 条日志？清空后不可恢复，" +
                "后续运行会重新开始记录。需要留证时请先「导出」。",
            confirmText = "确认清空",
            icon = Icons.Outlined.Delete,
            danger = true,
            visible = dialogVisible,
            onConfirm = {
                vm.clearLogs()
                expandedIndex = -1
                showClear = false
            },
            onDismiss = { showClear = false }
        )
    }
}

/**
 * 最近崩溃区块（真机第四轮新增）。
 *
 * **为什么必须有**：进程崩溃时内存里的日志缓冲来不及落盘，日志列表里一条异常都没有，
 * 只能靠猜。崩溃报告由 CrashLogger 在进程死之前同步写进 `filesDir/crash/`，
 * 这里是用户能直接看到它们的地方：一行汇总（N 次 + 文件名 + 目录）+ 可展开全文。
 *
 * 崩溃记录最多保留 [CrashLogger.MAX_KEEP] 份，故直接用 Column 渲染（不用 LazyColumn）。
 */
@Composable
private fun CrashSection(
    records: List<CrashRecord>,
    /** 其中还没看过的那几条（文件名）。空集 = 全都看过或没有记录。 */
    unseen: Set<String> = emptySet(),
    dirPath: String,
    expandedName: String,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    val ctx = LocalContext.current
    AkSectionCard(
        title = if (records.isEmpty()) "最近崩溃" else "最近崩溃（${records.size}）",
        icon = Icons.Outlined.Error,
        description = if (records.isEmpty()) {
            "暂无崩溃记录（目录：$dirPath）"
        } else {
            "检测到 ${records.size} 次崩溃记录：${records.first().fileName} 位于 $dirPath"
        },
        modifier = Modifier.padding(horizontal = AgentSpacing.space5)
    ) {
        records.forEachIndexed { index, record ->
            val expanded = expandedName == record.fileName
            AkPressSurface(
                onClick = { onToggle(record.fileName) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(AgentSpacing.space3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 未读红点：崩溃是低频事件，列表可能只有两三条，光看时间分不出
                        // 「哪条是我上次看过的」。点开即消，和阅读器的未读逻辑一致。
                        if (record.fileName in unseen) {
                            AkStatusDot(MaterialTheme.colorScheme.error, 6.dp)
                            Spacer(Modifier.width(AgentSpacing.space2))
                        }
                        Text(
                            text = formatCrashClock(record.timeMs),
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                        Text(
                            text = record.exceptionType.substringAfterLast('.'),
                            style = machineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space1))
                    Text(
                        text = record.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (expanded) {
                        Spacer(Modifier.height(AgentSpacing.space2))
                        Text(
                            text = record.text.ifBlank { "（报告为空）" },
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(AgentSpacing.space1))
                        AkTextButton(
                            text = "复制全文",
                            icon = Icons.Outlined.ContentCopy,
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("北辰 崩溃报告", record.text))
                            }
                        )
                    }
                }
            }
            if (index != records.lastIndex) Spacer(Modifier.height(AgentSpacing.space1))
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        AkTextButton(
            text = "清空崩溃记录",
            icon = Icons.Outlined.Delete,
            tint = MaterialTheme.colorScheme.error,
            onClick = onClear
        )
    }
}

/** 崩溃记录时间：`MM-dd HH:mm:ss`（崩溃是低频事件，年份可省，秒级足够定位）。 */
private fun formatCrashClock(timeMs: Long): String =
    if (timeMs <= 0L) "--" else runCatching {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
    }.getOrDefault("--")

/** 筛选 chip 的「全部」项。 */
private const val LEVEL_ALL = "全部"

/** 级别筛选顺序：从最该看的往下排（§3.7）。 */
private val LEVELS = listOf(LEVEL_ALL, "ERROR", "WARN", "INFO", "DEBUG")

/** 一条解析后的日志行：`HH:mm:ss.SSS LEVEL [tag] message`（core/AgentLogger.format）。 */
private data class LogEntry(
    val time: String,
    val level: String,
    val tag: String,
    val message: String
)

/**
 * 解析日志行。格式固定为四段，按段 split（limit = 4）保证消息正文里的空格不被切开；
 * 段数不足时退化为「整行即消息」，绝不因为一行怪数据把列表搞崩。
 */
private fun String.toLogEntry(): LogEntry {
    val parts = split(' ', limit = 4)
    return if (parts.size >= 3) {
        LogEntry(
            time = parts[0],
            level = parts[1],
            tag = parts[2],
            message = parts.getOrElse(3) { "" }
        )
    } else {
        LogEntry(time = "", level = "", tag = "", message = this)
    }
}

/** 级别 → 语义色（§3.7 四色：danger / warning / info / debug）。 */
@Composable
private fun levelColor(level: String): Color = when (level) {
    "ERROR" -> MaterialTheme.colorScheme.error
    "WARN" -> LocalAkColors.warning
    "INFO" -> LocalAkColors.logInfo
    "DEBUG" -> LocalAkColors.logDebug
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 单条日志：左缘 3dp 级别色条 + 首行（时间 / 级别 / tag）+ 次行消息（默认 2 行，点击展开全文）。
 * 整行可点，按压走全局 AkPressSurface 语言（§7-M3）。
 */
@Composable
private fun LogRow(
    entry: LogEntry,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val color = levelColor(entry.level)
    // v5：只有 WARN / ERROR 上色。INFO 占日志的九成，若全部染成信息蓝，
    // 整页就一直在「高亮」，告警反而跳不出来。安静是这一步的目的。
    val alert = entry.level == "ERROR" || entry.level == "WARN"
    AkPressSurface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 左缘色条要随条目高度伸缩（展开时跟着变长）：用 IntrinsicSize.Min 让 Bar 拿到确定高度
                .height(IntrinsicSize.Min)
                .heightIn(min = 56.dp)
                .padding(
                    horizontal = AgentSpacing.space3,
                    vertical = AgentSpacing.space2
                ),
            verticalAlignment = Alignment.Top
        ) {
            // 左缘级别色条：与级别文字同色双编码，色盲可读（UX v1 §5.3）
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        if (alert) color else MaterialTheme.colorScheme.outline
                    )
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.time.isNotEmpty()) {
                        Text(
                            text = entry.time,
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                    }
                    if (entry.level.isNotEmpty()) {
                        Text(
                            text = entry.level,
                            style = machineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (alert) color else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                    }
                    if (entry.tag.isNotEmpty()) {
                        Text(
                            text = entry.tag,
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (entry.message.isNotBlank()) {
                    Spacer(Modifier.height(AgentSpacing.space1))
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
