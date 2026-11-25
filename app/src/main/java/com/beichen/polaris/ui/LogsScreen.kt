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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.CrashLogger
import com.beichen.polaris.core.DiagnosticsExporter
import com.beichen.polaris.core.CrashRecord
import com.beichen.polaris.core.LogHit
import com.beichen.polaris.core.LogSearch
import com.beichen.polaris.ui.contract.LogsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    // 关键词。空格分隔的多个词是「同时命中」（AND），不是「任一命中」：
    // 排查时敲 "http_request 401" 要找的是同时含这两段的那些行，
    // 而不是所有 http 调用 + 所有带 401 的行（后者反而要再翻一遍）。
    var query by rememberSaveable { mutableStateOf("") }
    // 展开项记「原始行」而不是下标：加了关键词过滤后，同一下标在过滤前后指向不同条目，
    // 正在展开的那条会莫名其妙换成另一条。切级别筛选时其实早有这个毛病，只是不易察觉。
    var expandedRaw by rememberSaveable { mutableStateOf<String?>(null) }
    var exportHint by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }

    // —— 搜索（2026-11-25 第二轮：从「过滤屏幕上这 500 行」改成「查磁盘历史」）——
    //
    // hits == null 有明确含义：**还没有搜索结果**（无关键词，或第一次搜索还在路上）。
    // 不能拿空列表当「没结果」，否则每次输入第一个字符都会闪一下「没有匹配」。
    var hits by remember { mutableStateOf<List<LogHit>?>(null) }
    var scannedDays by remember { mutableStateOf<List<String>>(emptyList()) }
    var truncated by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    // 来源拆分与「磁盘到底有没有文件」的证据。三者都来自 core 的 LogSearchResult，
    // 只用于文案：屏幕上必须能看出「这 N 条是磁盘读的，还是内存兜的」。
    var fromDisk by remember { mutableStateOf(0) }
    var scannedDir by remember { mutableStateOf<String?>(null) }
    var otherEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var scannedFiles by remember { mutableStateOf<List<String>>(emptyList()) }

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

    val terms = remember(query) { parseTerms(query) }
    // 无关键词时的列表：内存里最近 500 条，按级别筛。
    val levelFiltered = remember(entries, filter) {
        if (filter == LEVEL_ALL) entries else entries.filter { it.level == filter }
    }

    // 有关键词就交给 core 去查（内存 + 磁盘历史），带 250ms 防抖。
    //
    // 防抖为什么放在 delay 而不是搜索框里：输入法每敲一个字符都会触发一次，
    // 而一次搜索可能要读几个 2MB 的文件。250ms 内的连续输入只跑最后一次，
    // 被取消的那次通常还停在 delay 阶段，一个字节都没读。
    // key 用 terms（List 的值相等比较）：键序变了但词相同，不重搜。
    LaunchedEffect(terms) {
        if (terms.isEmpty()) {
            hits = null
            scannedDays = emptyList()
            truncated = false
            searching = false
            fromDisk = 0
            scannedDir = null
            otherEntries = emptyList()
            scannedFiles = emptyList()
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        // 切 IO：逐行读最多 7 天、每天 4MB 的文件，主线程上会把滚动卡住。
        // 为什么直接调 AgentLogger 而不走 contract：本页已经直连 core 的 CrashLogger
        // 与 DiagnosticsExporter（见文件头 import），再加一层转发只会让 ChatViewModel
        // 再多一个方法——那个文件有行数上限，刚因为这一行被守卫拦下。
        val result = withContext(Dispatchers.IO) {
            AgentLogger.search(terms, SEARCH_MAX_DAYS, SEARCH_LIMIT)
        }
        hits = result.hits
        scannedDays = result.days
        truncated = result.truncated
        fromDisk = result.fromDisk
        scannedDir = result.dirPath
        otherEntries = result.otherEntries
        scannedFiles = result.files
        searching = false
    }

    // 渲染行：统一成「条目 + 它属于哪一天」。
    //
    // day 只在搜索结果里有值（来自 core，永远是 `yyyyMMdd`）；不搜索时是 null，
    // 那一路走的是内存窗口，不显示日期前缀。
    val rows: List<LogRowModel> = remember(levelFiltered, hits, filter, terms.isEmpty()) {
        val searched = hits
        if (terms.isEmpty() || searched == null) {
            levelFiltered.map { LogRowModel(it, null) }
        } else {
            searched.asSequence()
                .map { LogRowModel(it.line.toLogEntry(), it.day) }
                .filter { filter == LEVEL_ALL || it.entry.level == filter }
                .toList()
        }
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

        Spacer(Modifier.height(AgentSpacing.space2))

        // —— 关键词搜索（2026-11-25：用户「运行日志那里又不能搜索」，只能一条条翻）——
        //
        // 为什么常驻而不是收在图标后面：这一页平时没人来，来的都是有具体目标的人
        // （找 401、找某次工具调用、找崩溃前那几行）。一个要先点开才能用的搜索框，
        // 对「不知道能搜」的人是零；多占 45dp，换来一眼看得见，值。
        LogSearchRow(
            query = query,
            countLabel = when {
                query.isEmpty() -> null
                // hits == null 是「第一次搜索还在路上」，不是「没结果」——
                // 混为一谈会让每次输入第一个字符都闪一下「0 条」。
                searching || hits == null -> "搜索中"
                truncated -> "${rows.size} 条+"
                else -> "${rows.size} 条"
            },
            onQueryChange = { new -> query = new },
            onClear = { query = "" }
        )

        Spacer(Modifier.height(AgentSpacing.space3))

        // —— 操作行：导出（复制到剪贴板）/ 清空（二次确认）——
        //
        // v5：三个描边按钮（导出 / 诊断包 / 清空）原来占满一整行 100dp，
        // 是这一页最重的元素，可它们全是低频维护动作。改成一行纯文字动作：
        // 低频操作不该有按钮的体量，需要它的人仍然一眼看得见。
        // 诊断包的两条出口（复制全文 / 分享文件）都放在这个菜单里。
        //
        // 为什么不并排摆两个按钮：这一行已经有三个动作，再加一个会挤成两行
        // 或者被截断，而这页上全是低频维护动作，不值得为它们重构布局。
        var packMenu by remember { mutableStateOf(false) }
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
            // 点开由用户选走哪条路：复制全文（默认推荐）还是生成文件。
            //
            // 为什么把「复制全文」放第一位：2026-11-25 真机上连续 8 份诊断包经过
            // 「分享面板 → 接收 App → 上传」之后全部被补成 4096 的整数倍，
            // 而同一批文本粘贴（54 KB）一次都没坏。文件这条路过不去，就换一条。
            // 菜单文案写「推荐」是给用户的理由，不是替他决定——文件该发还得能发。
            Box {
                AkTextButton(
                    text = "诊断包",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { packMenu = true }
                )
                DropdownMenu(expanded = packMenu, onDismissRequest = { packMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("复制全文（推荐）") },
                        onClick = {
                            packMenu = false
                            val text = DiagnosticsExporter.textOf(ctx, vm.exportLogs())
                            if (text != null) {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("北辰 诊断包", text))
                                // 报字节数：用户能据此判断「复制到的这一段」是否完整，
                                // 报告里写的「完整文件应为」也是同一个量纲。
                                exportHint = "已复制诊断全文 ${text.toByteArray(Charsets.UTF_8).size} 字节" +
                                    "（含设备与配置，Key 已脱敏），直接粘贴即可"
                            } else {
                                exportHint = "诊断生成失败，请改用「导出日志」"
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("生成文件并分享") },
                        onClick = {
                            packMenu = false
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
                                exportHint = "诊断包生成失败，请改用「导出日志」复制"
                            }
                        }
                    )
                }
            }
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

        // —— 搜索范围说明（仅在有关键词且有结果时出现）——
        //
        // 这一行替代了原来那个 `35 / 200`：搜磁盘历史之后，分母已经没法用一句话表达
        // （是内存 500 条？今天 4MB？还是 7 天？）。直接说「在哪些天的日志里命中了几条」
        // 才是能核对的信息。
        if (terms.isNotEmpty() && rows.isNotEmpty()) {
            // 来源必须写出来。日期的「11-24」可能是磁盘读的，也可能是 restoreTail
            // 从昨天文件读回内存的——两者在屏幕上长得一模一样，而修法完全不同。
            val total = hits?.size ?: rows.size
            Text(
                text = buildString {
                    append("在 ${daysLabel(scannedDays)} 的日志中命中 $total 条")
                    append("（磁盘 $fromDisk · 内存 ${(total - fromDisk).coerceAtLeast(0)}）")
                    if (rows.size != total) append("，当前级别 ${rows.size} 条")
                    if (truncated) append("（已达上限，更早的未展开）")
                    // 清单换行跟在下面：这一行回答的是另一件事——
                    // 「我说的这几天，文件到底在不在」。
                    append("\n")
                    append(filesLabel(scannedFiles))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space2
                )
            )
        }

        // —— 列表 / 空态 ——
        if (rows.isEmpty()) {
            when {
                // 第一次搜索还在路上。不能落进「没有匹配」——那会让用户看到一闪而过的
                // 错误结论，而他很可能就此停手。
                terms.isNotEmpty() && searching -> AkEmptyState(
                    icon = Icons.Outlined.Search,
                    title = "正在搜索…",
                    description = "在内存与最近 ${SEARCH_MAX_DAYS} 天的日志里查找。"
                )
                // 搜完了、确实没有——分两种说法，因为它们指向不同的故障：
                //  - 扫到了文件：只是没命中。用户问的是「我搜了哪儿」，给日期范围；
                //  - 一个文件都没扫到：磁盘这一层根本没工作。用户问的是「为什么搜不到」，
                //    必须把目录和目录里的实际内容交出去。
                // 2026-11-25 真机：后者被旧文案说成「已搜索今天的日志」，一个真实故障
                // 被伪装成了正常结果，来回查了三轮才定位到「其实没读磁盘」。
                terms.isNotEmpty() && scannedDays.isEmpty() -> AkEmptyState(
                    icon = Icons.Outlined.Search,
                    title = "没有匹配「${query.trim()}」的日志",
                    description = buildString {
                        append("没有找到历史日志文件")
                        val path = scannedDir
                        if (path == null) {
                            append("（日志目录尚未就绪）")
                        } else {
                            append("（目录：$path）")
                            if (otherEntries.isNotEmpty()) {
                                append("。目录里现有：${otherEntries.joinToString("，")}")
                            }
                        }
                        append(
                            "。本次只能搜内存里的 ${entries.size} 条记录" +
                                "（磁盘保留 ${SEARCH_MAX_DAYS} 天，重启后内存会清空）。"
                        )
                    },
                    actionLabel = "清除搜索",
                    onAction = { query = "" }
                )
                // 真的搜完了、确实没有。文案必须带范围：只说「没有匹配」会让用户断定
                // 「日志里确实没这条」，然后去怀疑代码，而实际可能只是超出了保留天数。
                terms.isNotEmpty() -> AkEmptyState(
                    icon = Icons.Outlined.Search,
                    title = "没有匹配「${query.trim()}」的日志",
                    description = "已搜索 ${daysLabel(scannedDays)} 的日志" +
                        "（保留 ${SEARCH_MAX_DAYS} 天）。空格分隔的多个词要同时命中（AND），" +
                        "词越多范围越窄；也可能只是当前级别下没有。" +
                        "\n\n" + filesLabel(scannedFiles),
                    actionLabel = "清除搜索",
                    onAction = { query = "" }
                )
                filter == LEVEL_ALL -> AkEmptyState(
                    icon = Icons.Outlined.Description,
                    title = "暂无日志",
                    description = "发一条消息或触发一次技能后，这里会实时显示 LLM 请求、工具调用与错误。",
                    actionLabel = "刷新",
                    onAction = { vm.refreshLogs() }
                )
                else -> AkEmptyState(
                    icon = Icons.Outlined.Description,
                    title = "没有 $filter 级日志",
                    description = "切换到「全部」看看其他级别的记录。",
                    actionLabel = "刷新",
                    onAction = { vm.refreshLogs() }
                )
            }
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
                itemsIndexed(rows, key = { index, _ -> index }) { _, row ->
                    LogRow(
                        entry = row.entry,
                        terms = terms,
                        // 搜索时每行都带日期——包括内存里的行：它的 timeMs 是准的，
                        // 日期算得出来，不需要「本次」这种猜法（曾经把昨天 23:12 的
                        // 恢复日志标成「本次」，因为恢复进内存的旧行没有日期）。
                        dayLabel = if (terms.isEmpty()) null else row.day?.let(::formatDayShort),
                        expanded = expandedRaw != null && expandedRaw == row.entry.raw,
                        onClick = {
                            expandedRaw =
                                if (expandedRaw == row.entry.raw) null else row.entry.raw
                        }
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
                expandedRaw = null
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

/**
 * 关键词搜索行（2026-11-25）。
 *
 * 形态沿用 [MemoryScreen] 那条搜索线：图标 + 一条横线，不用填充胶囊。
 * 但**交互不同**：记忆页要查库所以是「点图标才检索」，这里输入即搜——
 * 排查时敲 `401` 希望列表立刻动起来，而不是敲完再点一下。
 *
 * 计数只在有关键词时出现，且只有「N 条」一个数：搜磁盘历史之后，
 * 分母已经没法用一个数字表达（内存 500 条？今天 4MB？还是 7 天？），
 * 范围改由列表上方那行「在 11-19 至 11-25 的日志中命中 N 条」说明。
 *
 * @param countLabel null 时整块隐藏（没在搜索）；「搜索中」时显示进行态。
 */
@Composable
private fun LogSearchRow(
    query: String,
    countLabel: String?,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = AgentSpacing.space5, end = AgentSpacing.space5)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 装饰性图标：contentDescription 留空，由输入框的 placeholder 说明这里是什么。
            // 给它单独的可访问性文案会让读屏把「搜索 搜索日志，空格分隔…」念两遍。
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(AgentSpacing.space3))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Done 而不是 Search：这里没有「提交」这一步，输入即已生效，
                // 按下的唯一目的就是收键盘。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索日志，空格分隔多个关键词",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                val label = countLabel
                if (label != null) {
                    Spacer(Modifier.width(AgentSpacing.space2))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (label == "搜索中") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else if (label.startsWith("0")) {
                            // 0 条用错误色：这一页的搜索只有「找到」和「没找到」两种结果，
                            // 没找到就该显眼到让人立刻去改关键词，而不是以为自己看漏了。
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                AkCircleIconButton(
                    icon = Icons.Outlined.Close,
                    description = "清除搜索关键词",
                    iconSize = 18.dp,
                    onClick = onClear
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        AkHairline()
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

/** 搜索回溯天数：与 `AgentLogger` 的磁盘保留期一致，多要也没有文件。 */
private const val SEARCH_MAX_DAYS = 7

/** 搜索结果上限：与 `AgentLogger.search` 的默认值一致。 */
private const val SEARCH_LIMIT = 300

/** 列表里的一行：日志条目 + 它属于哪一天（null 只在「没搜索」时出现）。 */
private data class LogRowModel(val entry: LogEntry, val day: String?)

/**
 * 搜索范围的人话描述。
 *
 * 只写「近 7 天」会撒谎：7 天里只有 3 天有日志文件时，实际搜索范围就是 3 天。
 * 有命中时传进来的是**命中行自己的日期**（真实来源）；一条都没命中时传的是
 * 「扫过哪些天」（这时用户问的是「你搜过哪儿」）。两者都由 core 给，这里只负责排版。
 */
private fun daysLabel(days: List<String>): String {
    // 空列表 = 一个磁盘文件都没扫到。这条分支正常不会显示：空态那边有独立的
    // 「没有找到历史日志文件」文案（带目录），而结果行只在有命中时出现。
    // 留着兜底是为了不崩，但**不写「今天」**——那是把「没读到文件」说成
    // 「读到了今天的文件」，真机上正是这句话掩盖了一次真实的失败。
    if (days.isEmpty()) return "无磁盘日志"
    val newest = days.first()
    val oldest = days.last()
    return if (newest == oldest) {
        "${formatDayShort(newest)} 当天"
    } else {
        "${formatDayShort(oldest)} 至 ${formatDayShort(newest)}"
    }
}

/** `20261125` → `11-25`；长度不对时原样返回（宁可显示得难看，也不要在这里崩）。 */
private fun formatDayShort(day: String): String =
    if (day.length == 8) "${day.substring(4, 6)}-${day.substring(6, 8)}" else day

/**
 * 目录里日志文件的人话清单。
 *
 * 这一行不为好看，只为回答一个问题：**昨天那份还在不在。**
 *
 * 2026-11-25 真机上，885 时还能搜到 11-22 至 11-25 四天的日志，888 之后只剩 11-25。
 * 而代码里四条删除路径（clear 只删当天、cleanupOldLogs 只删 7 天前、rotate 只改名、
 * init 幂等）都不该删掉前几天。没有清单就只能靠「搜某个词有没有结果」反推，
 * 一旦前提判断错，就要多改一版——这件事已经连错两轮。
 *
 * 最多列 4 个，再多就用「…」收尾：7 个全展开会把范围行挤成三行，
 * 而真正要看的是「前几天有没有」。
 */
private fun filesLabel(files: List<String>): String {
    if (files.isEmpty()) return "日志文件：目录里一个都没有"
    val shown = files.take(4).joinToString(" · ")
    val tail = if (files.size > 4) " …" else ""
    return "日志文件 ${files.size} 个：$shown$tail"
}

/** 一条解析后的日志行：`HH:mm:ss.SSS LEVEL [tag] message`（core/AgentLogger.format）。 */
private data class LogEntry(
    val time: String,
    val level: String,
    val tag: String,
    val message: String,
    /** 原始整行。展开状态以它为准：需要一个跨过滤稳定的标识，
     *  四个显示字段拼起来可能撞，整行不会。 */
    val raw: String
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
            message = parts.getOrElse(3) { "" },
            raw = this
        )
    } else {
        LogEntry(time = "", level = "", tag = "", message = this, raw = this)
    }
}

/**
 * 拆关键词：按空白切分，丢弃空段。
 *
 * 判据本体在 [LogSearch]（core，纯 JVM 可测）：Compose 文件在 JVM 上编不过，
 * 逻辑留在这一层就只能靠读代码确认对不对。这里只做转发。
 */
private fun parseTerms(query: String): List<String> = LogSearch.parseTerms(query)

/**
 * 关键词高亮：命中片段加粗，其余原样。
 *
 * 区间由 [LogSearch.ranges] 算（已合并重叠、保证有序不相交），这里只负责拼样式。
 * 直接用返回的区间逐个 `withStyle`，不要再自己找一遍位置——
 * 两处各找一遍迟早会不一致（那种不一致不崩，只是高亮错位几个字符）。
 */
private fun highlight(text: String, terms: List<String>): AnnotatedString {
    val ranges = LogSearch.ranges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { r ->
            if (r.first > cursor) append(text.substring(cursor, r.first))
            withStyle(style) { append(text.substring(r.first, r.last + 1)) }
            cursor = r.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
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
 *
 * @param dayLabel 行首的日期前缀（`11-25`）；`null` 时不显示。
 *   只在搜索结果里给值——不搜索时列表是内存窗口，每条挂个日期等于噪音。
 */
@Composable
private fun LogRow(
    entry: LogEntry,
    /** 当前关键词（已拆好）。空表示没在搜索，走纯文本路径。 */
    terms: List<String>,
    dayLabel: String?,
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
                    // 日期前缀：搜索跨天了，「13:47」到底是今天还是昨天，只看时间是分不出的。
                    if (dayLabel != null) {
                        Text(
                            text = dayLabel,
                            style = machineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                    }
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
                            // 统一走 AnnotatedString：`if (…) 字符串 else AnnotatedString` 的
                            // 公共父类型是 CharSequence，而 Text 没有这个重载，会编译不过。
                            // 没搜索时 highlight 返回的就是无样式的包装，视觉等同纯文本。
                            text = highlight(entry.tag, terms),
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
                        text = highlight(entry.message, terms),
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
