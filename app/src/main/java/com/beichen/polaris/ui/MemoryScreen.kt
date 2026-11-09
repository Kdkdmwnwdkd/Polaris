package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beichen.polaris.memory.MemoryForceLayout
import com.beichen.polaris.memory.MemoryItem
import com.beichen.polaris.ui.contract.MemoryBankContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sin

/**
 * 记忆库页（UX v1 §1.7）：跨会话长期记忆的图谱浏览 / 沉淀 / 清理。
 *
 * 用户反馈记忆库平庸（旧版是纵向列表），参照 OPeritAI 的记忆库改图谱式：
 * Canvas 同心环节点网络 + 共享 tag 连边，点节点看详情，右侧竖排 FAB 做新增 / 清空。
 * 数据能力一件不减（检索 / 手动记一条 / 清空全部），只是换了一件衣服。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「记忆库」+ 右上「刷新」）。
 */
@Composable
fun MemoryScreen(vm: MemoryBankContract) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // hotfix31 曾把默认模式改成列表（理由：图谱只是照着星图摆了个形状，节点难戳）。
    // W-68 把星图换成真力导向之后，这个理由不成立了：节点按重要度放大、可按可拖、
    // 选中后邻居高亮，图谱成了**信息量更大**的那一面，于是默认回到图谱，列表留作扫读。
    var listMode by rememberSaveable { mutableStateOf(false) }
    // hotfix31：编辑对话框三件套草稿（content 多行 + salience 滑杆 + tags 逗号分隔）
    var showEdit by remember { mutableStateOf(false) }
    var editContent by rememberSaveable { mutableStateOf("") }
    var editSalience by rememberSaveable { mutableStateOf(5) }
    var editTags by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    // 选中项随结果集派生：搜索 / 刷新 / 遗忘后原选中可能已不在列表里，不强行续命
    val selected = memories.firstOrNull { it.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 详情卡封顶 45% 屏高：图谱/列表永远是主视觉，长内容在卡内滚动（OPeritAI 同款层级）
        val detailMaxHeight = maxHeight * 0.45f
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PageTopBar(
                    title = AppDest.MEMORY.title,
                    actionIcon = Icons.Outlined.Refresh,
                    actionDescription = "刷新",
                    onAction = { vm.refreshMemories() }
                )

                // —— 顶栏下方一行：圆角搜索框 + 计数徽章；有筛选时再给一枚「全部」复位 —
                MemorySearchRow(
                    query = query,
                    resultCount = memories.size,
                    onQueryChange = { query = it },
                    onSearch = { vm.searchMemory(query) },
                    onReset = {
                        query = ""
                        vm.refreshMemories()
                    }
                )
                // hotfix31：模式切换 chip 行（列表 ⇄ 图谱）——「全部」复位仅在
                // 有筛选时出现，与模式 chip 同行不打架
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AgentSpacing.space5,
                            end = AgentSpacing.space5,
                            bottom = AgentSpacing.space2
                        ),
                    horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AkChip(
                        text = "图谱",
                        selected = !listMode,
                        onClick = { listMode = false }
                    )
                    AkChip(
                        text = "列表",
                        selected = listMode,
                        onClick = { listMode = true }
                    )
                    if (query.isNotBlank()) {
                        AkChip(
                            text = "全部",
                            selected = false,
                            onClick = {
                                query = ""
                                vm.refreshMemories()
                            }
                        )
                    }
                    // v5：原来右侧挂着两个实心圆 FAB（红 + 蓝），是整页最重的两块颜色。
                    // 「记一条」「清空」都是低频动作，收成同一行的文字按钮：需要时看得见，
                    // 不需要时不占版面。红色的圆留给真正危险且高频的动作，这里不是。
                    Spacer(Modifier.weight(1f))
                    AkTextButton(
                        text = "记一条",
                        onClick = { showAdd = true }
                    )
                    AkTextButton(
                        text = "清空",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showClear = true }
                    )
                }

                if (memories.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AkEmptyState(
                            icon = Icons.Outlined.Storage,
                            title = if (query.isBlank()) "暂无记忆" else "没有匹配的记忆",
                            description = if (query.isBlank()) {
                                "Agent 每次成功回答会自动沉淀一条事件记忆；偏好和关键信息也可以点上方「记一条」手动记录。"
                            } else {
                                "换个关键词试试，或点「全部」查看全部。"
                            }
                        )
                    }
                } else if (listMode) {
                    // hotfix31：列表模式——LazyColumn 记忆行，好扫好点；
                    // 行内直接显示 kind 徽章 + 摘要 + 重要度 + 时间，点选看详情
                    MemoryList(
                        memories = memories,
                        selectedId = selected?.id,
                        onSelect = { id -> selectedId = if (selectedId == id) null else id },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    // —— 星图模式：核心视觉，吃满剩余空间；可拖可缩放（W-68 力导向）——
                    MemoryGraph(
                        memories = memories,
                        selectedId = selected?.id,
                        onSelect = { id -> selectedId = if (selectedId == id) null else id },
                        onClearSelection = { selectedId = null },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
                if (selected != null) {
                    MemoryDetailCard(
                        item = selected,
                        maxHeight = detailMaxHeight,
                        onForget = {
                            vm.forgetMemory(selected.id)
                            selectedId = null
                        },
                        onEdit = {
                            // hotfix31：编辑入口——草稿三件套取自当前选中项
                            editContent = selected.content
                            editSalience = selected.salience
                            editTags = selected.tags.joinToString(", ")
                            showEdit = true
                        }
                    )
                }
            }

        }
    }

    // —— 手动记一条：小对话框，提交 note 类记忆（kind 分类用户不敏感，默认 note）——
    AkModalHost(target = if (showAdd) Unit else null) { _, dialogVisible ->
        MemoryAddDialog(
            draft = draft,
            onDraftChange = { draft = it },
            visible = dialogVisible,
            onConfirm = {
                vm.addMemory(draft, "note", emptyList())
                draft = ""
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    // —— 清空二次确认：Agent 会忘掉全部偏好与过往，删前必须拦一道 ----
    AkModalHost(target = if (showClear) Unit else null) { _, dialogVisible ->
        AkConfirmDialog(
            title = "清空记忆库",
            message = "确认清空全部 ${memories.size} 条记忆？Agent 将忘掉所有偏好与过往，且无法恢复。",
            confirmText = "确认清空",
            icon = Icons.Outlined.Delete,
            danger = true,
            visible = dialogVisible,
            onConfirm = {
                vm.clearMemory()
                selectedId = null
                showClear = false
            },
            onDismiss = { showClear = false }
        )
    }

    // —— hotfix31：编辑记忆对话框（内容 / 重要度 / 标签三件套）——
    AkModalHost(target = if (showEdit) Unit else null) { _, dialogVisible ->
        MemoryEditDialog(
            content = editContent,
            salience = editSalience,
            tags = editTags,
            visible = dialogVisible,
            onContentChange = { editContent = it },
            onSalienceChange = { editSalience = it },
            onTagsChange = { editTags = it },
            onConfirm = {
                val target = selectedId
                // store 拒收（空白内容/目标不在）时保持对话框不关 + Toast 说明
                val ok = target != null && vm.updateMemory(
                    id = target,
                    content = editContent,
                    salience = editSalience,
                    tags = editTags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
                )
                if (ok) {
                    showEdit = false
                } else {
                    Toast.makeText(context, "内容不能为空", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showEdit = false }
        )
    }
}

/**
 * 搜索行：圆角搜索面（填充式语汇，比 AkTextField 少一条 label，顶栏下方一行放下）
 * + 右侧计数徽章；框内「搜索」图标即触发（保留旧版显式触发语义，不改成输入即检索），
 * 有内容时出「×」= 清空并看全部。
 */
@Composable
private fun MemorySearchRow(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onReset: () -> Unit
) {
    val ak = LocalAkColors
    // v5：搜索框不再是灰底胶囊，而是页面上一条可以写字的横线。
    // 原来那块 surfaceVariant 圆角是整页最显眼的形状，可搜索只是这一页的辅助动作。
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space5,
                top = AgentSpacing.space2
            )
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AkCircleIconButton(
                icon = Icons.Outlined.Search,
                description = "搜索",
                iconSize = 20.dp,
                onClick = onSearch
            )
            Spacer(Modifier.width(AgentSpacing.space1))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索记忆...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                AkCircleIconButton(
                    icon = Icons.Outlined.Close,
                    description = "清空关键词并显示全部记忆",
                    iconSize = 18.dp,
                    onClick = onReset
                )
            }
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        // 计数（OPeritAI 右上「0/1」同款语义）：这里是「当前结果集共 N 条」。
        // v5：去掉了灰底药丸——一个数字不需要一个容器。
        Text(
            text = "$resultCount 条",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
        Spacer(Modifier.height(4.dp))
        AkHairline()
    }
}

/**
 * 记忆星图（W-68 重做）。
 *
 * 上一版是「同心环 + 角度均匀分布」的静态图：形状是照着星图摆的，但节点被钉死在环上，
 * 拖动只是把点搬走，边不会跟着收放。用户原话：「就像星图一样能动啊，之前他弄的就很拉」。
 *
 * 这一版换成真正的力导向：斥力 + 弹簧 + 向心，位置每帧由 [MemoryForceLayout] 算出。
 * 松开手指后整张网自己找平衡；点选 / 拖动 / 数据变化都会重新加温一小段再收敛。
 *
 * 三个手感细节：
 *  1. 拖一个点，邻居被弹簧拽着跟过来 —— 单点搬走不叫「能动」，网跟着变形才是；
 *  2. 双指缩放 + 平移（以双指中心为锚），单指拖节点，点空白取消选中；
 *  3. 静止后立刻掐掉帧循环（[MemoryForceLayout.isSettled]），不为「看起来活着」白烧 60fps。
 *
 * 视觉：节点半径按重要度（salience 1..10 → 8..18dp），颜色按 kind；选中时邻居高亮、
 * 其余淡出；节点 ≤14 个时全部显示标签，多了只显示选中与邻居，避免糊成一团。
 */
@Composable
private fun MemoryGraph(
    memories: List<MemoryItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val nodes = remember(memories) { buildStarNodes(memories) }
    val edges = remember(nodes) { buildStarEdges(nodes) }
    val radiusPx = remember(nodes, density) {
        FloatArray(nodes.size) { with(density) { starRadius(nodes[it].salience).toPx() } }
    }
    val layout = remember(nodes) { MemoryForceLayout(nodes.size, edges, radiusPx) }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // 每帧 +1 触发 Canvas 重绘：位置在 FloatArray 里（不是 snapshot state），
    // 一帧写几十次 state 会让重组队列吃不消，用一个计数器当「帧号」最省
    var frame by remember { mutableIntStateOf(0) }
    // 叫醒信号：静止后帧循环挂起，触摸 / 归位时 +1 把它唤醒
    var wake by remember { mutableIntStateOf(0) }
    var draggingIndex by remember { mutableIntStateOf(-1) }

    val selectedIndex = remember(nodes, selectedId) {
        nodes.indexOfFirst { it.id == selectedId }
    }
    // 每个节点的度数：没有选中项时用它挑「枢纽节点」显示标签——
    // 22 条记忆全标会糊，全不标又像一堆没名字的点
    val degrees = remember(edges, nodes.size) {
        IntArray(nodes.size).also { deg ->
            var e = 0
            while (e < edges.size) {
                deg[edges[e]]++
                deg[edges[e + 1]]++
                e += 2
            }
        }
    }

    // 选中项的直接邻居：高亮它们、淡化其余，比「整张图一样亮」有用得多
    val neighbors = remember(edges, selectedIndex) {
        if (selectedIndex < 0) emptySet() else buildSet {
            var e = 0
            while (e < edges.size) {
                val a = edges[e]
                val b = edges[e + 1]
                e += 2
                if (a == selectedIndex) add(b)
                if (b == selectedIndex) add(a)
            }
        }
    }

    // 手势里要读最新值，但 pointerInput 的 key 不能挂 zoom/pan（每帧变化会重启手势识别）
    val zoomNow by rememberUpdatedState(zoom)
    val panNow by rememberUpdatedState(pan)
    val nodesNow by rememberUpdatedState(nodes)

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val scheme = MaterialTheme.colorScheme
    val outlineColor = scheme.outline
    val primaryColor = scheme.primary
    val onSurfaceVariant = scheme.onSurfaceVariant
    val kindColor: (String) -> Color = { kind ->
        when (kind.lowercase()) {
            "fact" -> scheme.primary
            "preference" -> scheme.secondary
            "event" -> scheme.tertiary
            else -> scheme.outline
        }
    }

    // —— 帧循环：跑物理；收敛后挂起等唤醒 ——
    LaunchedEffect(layout, viewport) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        var seen = wake
        while (isActive) {
            if (layout.isSettled) {
                // 静止：真正挂起（不是空转），直到有人把 wake 加一
                snapshotFlow { wake }.first { it != seen }
                seen = wake
                continue
            }
            withFrameNanos {
                layout.step()
                frame++
            }
        }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    if (it != viewport) {
                        viewport = it
                        layout.setViewport(it.width.toFloat(), it.height.toFloat())
                        wake++
                    }
                }
                .semantics {
                    contentDescription =
                        "记忆星图，共 ${nodes.size} 条；单指拖动节点、双指缩放，点节点看详情"
                }
                .pointerInput(layout, viewport) {
                    if (viewport == IntSize.Zero) return@pointerInput
                    val hitSlop = 16.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val worldX = (down.position.x - panNow.x) / zoomNow
                        val worldY = (down.position.y - panNow.y) / zoomNow
                        val hit = layout.nearest(worldX, worldY, hitSlop / zoomNow)
                        var lastPos = down.position
                        var moved = 0f
                        if (hit >= 0) {
                            // 按在节点上：立刻选中（拖谁选谁），后续拖动直接改这个点的位置
                            draggingIndex = hit
                            onSelect(nodesNow[hit].id)
                            layout.reheat()
                            wake++
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            val delta = change.position - lastPos
                            lastPos = change.position
                            moved += delta.getDistance()

                            if (draggingIndex >= 0) {
                                // 拖节点：手指跟到哪，点就到哪（世界坐标 = 屏幕位移 / 缩放）
                                val idx = draggingIndex
                                layout.dragTo(
                                    idx,
                                    layout.x[idx] + delta.x / zoomNow,
                                    layout.y[idx] + delta.y / zoomNow
                                )
                                wake++
                                change.consume()
                            } else {
                                // 空白处：双指缩放（以双指中心为锚）+ 单指/双指平移
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    val newZoom = (zoomNow * zoomChange).coerceIn(MIN_GRAPH_ZOOM, MAX_GRAPH_ZOOM)
                                    val worldFocusX = (centroid.x - panNow.x) / zoomNow
                                    val worldFocusY = (centroid.y - panNow.y) / zoomNow
                                    zoom = newZoom
                                    pan = Offset(
                                        centroid.x - worldFocusX * newZoom,
                                        centroid.y - worldFocusY * newZoom
                                    )
                                }
                                val move = event.calculatePan()
                                if (move != Offset.Zero) pan += move
                                change.consume()
                            }
                        }
                        // 抬手：没怎么动 = 轻点；点空白清选中，拖过节点则让它继续找平衡
                        if (moved < viewConfiguration.touchSlop) {
                            if (hit < 0) onClearSelection()
                        } else if (draggingIndex >= 0) {
                            layout.reheat(0.55f)
                        }
                        draggingIndex = -1
                        wake++
                    }
                }
        ) {
            // 读 frame：让 Canvas 每帧失效重绘（位置不在 state 里，靠它驱动）
            @Suppress("UNUSED_VARIABLE")
            val tick = frame
            val z = zoom
            val p = pan
            val sel = selectedIndex
            val hasSel = sel >= 0
            // 选中态的光晕呼吸：只在还在动的时候算，静止后固定，不空转
            val pulse = if (layout.isSettled) 0.35f else (sin(frame * 0.09f) * 0.5f + 0.5f)

            // —— 背景星点：视差 0.3，不随缩放，给「夜空」一点纵深 ——
            var s = 0
            while (s < MEMORY_STARFIELD.size) {
                val nx = MEMORY_STARFIELD[s]
                val ny = MEMORY_STARFIELD[s + 1]
                val r = MEMORY_STARFIELD[s + 2]
                drawCircle(
                    color = onSurfaceVariant.copy(alpha = 0.10f),
                    radius = r,
                    center = Offset(nx * size.width, ny * size.height) + p * 0.3f
                )
                s += 3
            }

            withTransform({
                translate(p.x, p.y)
                scale(z, z, pivot = Offset.Zero)
            }) {
                // —— 连边 ——
                var e = 0
                while (e < edges.size) {
                    val a = edges[e]
                    val b = edges[e + 1]
                    e += 2
                    val hot = hasSel && (a == sel || b == sel)
                    val dim = hasSel && !hot
                    drawLine(
                        color = if (hot) primaryColor.copy(alpha = 0.85f)
                        else outlineColor.copy(alpha = if (dim) 0.05f else 0.22f),
                        start = Offset(layout.x[a], layout.y[a]),
                        end = Offset(layout.x[b], layout.y[b]),
                        strokeWidth = (if (hot) 1.8.dp.toPx() else 1.dp.toPx()) / z
                    )
                }

                // —— 节点 ——
                for (idx in nodes.indices) {
                    val node = nodes[idx]
                    val center = Offset(layout.x[idx], layout.y[idx])
                    val isSel = idx == sel
                    val isNeighbor = hasSel && idx in neighbors
                    val alpha = when {
                        !hasSel -> 1f
                        isSel -> 1f
                        isNeighbor -> 0.95f
                        else -> 0.26f
                    }
                    val r = radiusPx[idx] * when {
                        isSel -> 1.35f
                        idx == draggingIndex -> 1.25f
                        else -> 1f
                    }
                    if (isSel) {
                        // 选中光晕：外扩一圈低透明度，随呼吸微变
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.10f + 0.10f * pulse),
                            radius = r + 9.dp.toPx(),
                            center = center
                        )
                    }
                    drawCircle(
                        color = kindColor(node.kind).copy(alpha = alpha),
                        radius = r,
                        center = center
                    )
                    drawCircle(
                        color = outlineColor.copy(alpha = 0.45f * alpha),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.dp.toPx() / z)
                    )
                }

                // —— 标签：单独一遍画，带避让 ——
                //
                // 上一版是「画完节点顺手画字」，22 条记忆直接叠成一团黑。
                // 这里按优先级（选中 → 邻居 → 其余）逐个尝试：与已放置的标签重叠就跳过，
                // 宁可少显示几个字，也不让文字糊住图。标签朝外（右半边写左侧），
                // 从中心向外发散，进一步减少碰撞。
                val order = nodes.indices.sortedByDescending { idx ->
                    when {
                        idx == sel -> 2
                        hasSel && idx in neighbors -> 1
                        else -> 0
                    }
                }
                val placed = ArrayList<Rect>(nodes.size)
                for (idx in order) {
                    val node = nodes[idx]
                    val isSel = idx == sel
                    val isNeighbor = hasSel && idx in neighbors
                    if (hasSel && !isSel && !isNeighbor) continue
                    if (!hasSel && nodes.size > 14 && degrees[idx] < 2) continue
                    val cx = layout.x[idx]
                    val cy = layout.y[idx]
                    val rr = radiusPx[idx] * if (isSel) 1.35f else 1f
                    val text = measurer.measure(node.label, labelStyle)
                    val tw = text.size.width.toFloat()
                    val th = text.size.height.toFloat()
                    val onRight = cx < size.width / 2f
                    val left = if (onRight) cx + rr + 5.dp.toPx() else cx - rr - 5.dp.toPx() - tw
                    val top = cy - th / 2f
                    val rect = Rect(left, top, left + tw, top + th)
                    if (placed.any { it.overlaps(rect) }) continue
                    // 除了不压标签，也不许压到别的节点：圆与矩形相交即放弃这条标签
                    val hitsNode = nodes.indices.any { j ->
                        if (j == idx) return@any false
                        val nx = layout.x[j]
                        val ny = layout.y[j]
                        val nr = radiusPx[j]
                        val closestX = nx.coerceIn(rect.left, rect.right)
                        val closestY = ny.coerceIn(rect.top, rect.bottom)
                        val dx = nx - closestX
                        val dy = ny - closestY
                        dx * dx + dy * dy < nr * nr
                    }
                    if (hitsNode) continue
                    placed += rect
                    drawText(
                        textLayoutResult = text,
                        color = if (isSel) primaryColor
                        else onSurfaceVariant.copy(alpha = 0.55f + 0.4f * (if (hasSel) 0.95f else 1f)),
                        topLeft = Offset(left, top)
                    )
                }
            }
        }

        // —— 右上角：归位（重置缩放平移 + 重新布局）——
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(AgentSpacing.space3)
        ) {
            AkCircleIconButton(
                icon = Icons.Outlined.CenterFocusStrong,
                description = "归位：重置缩放并重新布局",
                iconSize = 20.dp,
                onClick = {
                    zoom = 1f
                    pan = Offset.Zero
                    layout.reset()
                    wake++
                }
            )
        }

        // —— 左下角操作提示：第一次看星图的人不知道该干嘛 ——
        Text(
            text = "拖动节点 · 双指缩放 · 点空白取消",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = AgentSpacing.space5, bottom = AgentSpacing.space2)
        )
    }
}

/** 选中节点详情卡：完整 content + kind 徽章 + tags / 重要度 / 时间 + 「忘记」+ hotfix31「编辑」。 */
@Composable
private fun MemoryDetailCard(
    item: MemoryItem,
    maxHeight: Dp,
    onForget: () -> Unit,
    onEdit: () -> Unit = {}
) {
    val ak = LocalAkColors
    AkGroupCard(
        Modifier.padding(
            start = AgentSpacing.space5,
            end = AgentSpacing.space5,
            bottom = AgentSpacing.space3
        )
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberPersistentScrollState())
                .padding(AgentSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AkBadge(
                    text = kindLabel(item.kind),
                    container = ak.surface2,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                // hotfix31：编辑入口（真机反馈「可以点开可以看还可以管」）
                AkTextButton(text = "编辑", onClick = onEdit)
                AkTextButton(text = "忘记", onClick = onForget, tint = MaterialTheme.colorScheme.error)
            }
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            val meta = buildList {
                if (item.tags.isNotEmpty()) add("#${item.tags.joinToString(" #")}")
                add("重要度 ${item.salience}")
                add(formatMemoryTime(item.createdAt))
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 手动记一条对话框：多行 AkTextField + 确定 / 取消（旧版「手动记一条」卡的对话框化）。 */
@Composable
private fun MemoryAddDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 宽 = min(344dp, 100vw − 48dp)：与 AkConfirmDialog 同款宽度语汇
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "手动记一条",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = "要记住的内容",
                        placeholder = "例如：我喜欢喝美式咖啡，不加糖",
                        singleLine = false,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "记住这条",
                            icon = Icons.Outlined.Bookmark,
                            modifier = Modifier.weight(1f),
                            enabled = draft.isNotBlank(),
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}

/**
 * hotfix31：列表模式——LazyColumn 记忆行。
 *
 * 真机反馈原话：「记忆库是说搞个和 OPeritAI 差不多的，但是你现在只学到了样子，
 * 人家可以点开，可以看，但是你这个不行，这只学到一个布局的样子而已」。
 * Canvas 图谱节点在真机上小而难戳、扫不动；列表是「可管理」的底色：行内直接给
 * kind 徽章 + 摘要两行 + 重要度 + 时间，点选与图谱同语义（选中看详情/编辑）。
 * 选中态左缘 3dp primary 竖条，与 DrawerConversationRow 同一套视觉语言。
 */
@Composable
private fun MemoryList(
    memories: List<MemoryItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ak = LocalAkColors
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = AgentSpacing.space5,
            vertical = AgentSpacing.space2
        ),
        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        items(memories, key = { it.id }) { item ->
            val selected = item.id == selectedId
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                AkGroupCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (selected) 6.dp else 0.dp)
                        .clickable { onSelect(item.id) }
                ) {
                    Column(Modifier.padding(AgentSpacing.space3)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AkBadge(
                                text = kindLabel(item.kind),
                                container = ak.surface2,
                                content = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                text = "重要度 ${item.salience}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (item.tags.isNotEmpty()) {
                                Spacer(Modifier.width(AgentSpacing.space2))
                                Text(
                                    text = "#${item.tags.joinToString(" #")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(AgentSpacing.space1))
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatMemoryTime(item.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * hotfix31：编辑记忆对话框——content 多行 + salience 滑杆（1..10）+ tags 逗号分隔。
 * 骨架同 [MemoryAddDialog]；「保存」 enabled = content 非空白（store 还有一道拒收，
 * 双保险）。tags 拆分（中英文逗号）在调用方做，对话框只当纯文本编辑器。
 */
@Composable
private fun MemoryEditDialog(
    content: String,
    salience: Int,
    tags: String,
    visible: Boolean,
    onContentChange: (String) -> Unit,
    onSalienceChange: (Int) -> Unit,
    onTagsChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spec = if (visible) AkMotionDialog else AkMotionExit
    val scale by animateFloatAsState(if (visible) 1f else 0.96f, animationSpec = spec)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = spec)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 344.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = "编辑记忆",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = content,
                        onValueChange = onContentChange,
                        label = "内容",
                        singleLine = false,
                        maxLines = 5
                    )
                    Spacer(Modifier.height(AgentSpacing.space3))
                    // salience 滑杆：1..10 离散（steps = 8），实时读数
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "重要度",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(AgentSpacing.space3))
                        Slider(
                            value = salience.toFloat(),
                            onValueChange = { onSalienceChange(it.toInt().coerceIn(1, 10)) },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(AgentSpacing.space2))
                        Text(
                            text = "$salience",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    AkTextField(
                        value = tags,
                        onValueChange = onTagsChange,
                        label = "标签",
                        placeholder = "用逗号分隔，如：饮食, 偏好"
                    )
                    Spacer(Modifier.height(AgentSpacing.space6))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkOutlinedButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        AkPrimaryButton(
                            text = "保存",
                            icon = Icons.Outlined.Edit,
                            modifier = Modifier.weight(1f),
                            enabled = content.isNotBlank(),
                            onClick = onConfirm
                        )
                    }
                }
            }
        }
    }
}

/**
 * 右侧竖排悬浮操作（参照 OPeritAI）：48dp 触控小圆面，颜色 primary / error 区分新增与清空。
 * 自绘而非 AkCircleIconButton：后者无底色槽，而 FAB 的语义恰恰要靠容器色承载（§3.1 红色只此一处）。
 */
@Composable
private fun MemoryFab(
    icon: ImageVector,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = AkMotionPress
    )
    Box(
        Modifier
            .size(AgentDimens.touchTarget)
            .scale(scale)
            .clip(CircleShape)
            .background(if (pressed) container.copy(alpha = 0.85f) else container)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 星图节点：只保留渲染与连边需要的东西，位置交给 [MemoryForceLayout]。
 *
 * 与旧版 MemNode 的区别：不再有 ring/angle —— 位置是模拟出来的，不是摆出来的。
 */
private data class StarNode(
    val id: String,
    val label: String,
    val kind: String,
    val tags: List<String>,
    val salience: Int
)

/** 节点半径：重要度 1..10 映射到 7..14dp（重要的记忆更显眼，但不至于糊成一片）。 */
private fun starRadius(salience: Int): Dp =
    (7f + (salience.coerceIn(1, 10) - 1) * 7f / 9f).dp

/** 标签截断：超过 10 字符加省略号（星图里标签是提示不是正文）。 */
private fun buildStarNodes(memories: List<MemoryItem>): List<StarNode> =
    memories.map { item ->
        val text = item.content.trim().replace("\n", " ")
        StarNode(
            id = item.id,
            label = if (text.length > 10) text.take(10) + "…" else text,
            kind = item.kind,
            tags = item.tags,
            salience = item.salience
        )
    }

/**
 * 连边规则（按优先级，去重、限度）：
 *  1. 共享任一 tag 就连 —— 这是「记忆之间真实的关系」；
 *  2. 一条边都没有的节点，连到同类（kind）里最早的一个 —— 不让孤立条目飘成散沙；
 *  3. 同类只有它自己，接到 0 号 —— 至少保证整张图是连通的星图，不是随机点云。
 *
 * 限度 6：50 条记忆如果 tag 高度重合，全连会产生上千条边，糊成一团黑。
 * 宁可少连几条，也要看得清结构。
 */
private fun buildStarEdges(nodes: List<StarNode>): IntArray {
    if (nodes.size < 2) return IntArray(0)
    val out = ArrayList<Int>(nodes.size * 3)
    val degree = IntArray(nodes.size)

    fun link(a: Int, b: Int) {
        if (a == b) return
        if (degree[a] >= MAX_STAR_DEGREE || degree[b] >= MAX_STAR_DEGREE) return
        out += a
        out += b
        degree[a]++
        degree[b]++
    }

    // 1. 共享 tag
    for (i in nodes.indices) {
        val tags = nodes[i].tags.toSet()
        if (tags.isEmpty()) continue
        for (j in i + 1 until nodes.size) {
            if (nodes[j].tags.any { it in tags }) link(i, j)
        }
    }

    // 2. 孤立节点 → 同 kind 的第一个
    val byKind = HashMap<String, Int>()
    nodes.forEachIndexed { index, node ->
        if (degree[index] == 0) byKind.getOrPut(node.kind) { index }
    }
    nodes.forEachIndexed { index, node ->
        if (degree[index] == 0) {
            val anchor = byKind[node.kind]
            if (anchor != null && anchor != index) link(index, anchor!!)
        }
    }

    // 3. 仍然孤立 → 接到 0 号，保证连通
    nodes.indices.forEach { index ->
        if (degree[index] == 0 && index != 0) link(index, 0)
    }

    return out.toIntArray()
}

/** 每个节点最多连 6 条边（限度，防止 tag 高度重合时连成毛球）。 */
private const val MAX_STAR_DEGREE = 6

/** 星图缩放的上下限：太小点不着，太大只剩几个点。 */
private const val MIN_GRAPH_ZOOM = 0.55f
private const val MAX_GRAPH_ZOOM = 2.6f

/**
 * 背景星点（归一化坐标 + 半径，三个一组）。
 *
 * 用固定种子的 LCG 生成，不用 Random：同一台机器每次进页面星点位置一致，
 * 否则「星星在跳」会像渲染 bug。
 */
private val MEMORY_STARFIELD: FloatArray = run {
    var state = 0x2F6E_2B1DL
    val out = FloatArray(STARFIELD_COUNT * 3)
    for (i in 0 until STARFIELD_COUNT) {
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) and 0xFFFF).toFloat() / 0xFFFF
        }
        out[i * 3] = next()
        out[i * 3 + 1] = next()
        out[i * 3 + 2] = 0.5f + next() * 1.4f
    }
    out
}

private const val STARFIELD_COUNT = 46

/** 记忆 kind（英文枚举）→ 中文展示名（规范 §4.1 纯中文；未知值原样透传，不吞数据）。 */
private fun kindLabel(kind: String): String = when (kind.lowercase()) {
    "fact" -> "事实"
    "preference" -> "偏好"
    "event" -> "事件"
    "note" -> "随手记"
    else -> kind
}

/**  epoch 毫秒 → 「MM-dd HH:mm」本地时间（minSdk 26 起 java.time 可用，无需脱糖）。 */
private val memoryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatMemoryTime(epochMilli: Long): String =
    memoryTimeFormatter.format(
        Instant.ofEpochMilli(epochMilli).atZone(ZoneId.systemDefault())
    )
