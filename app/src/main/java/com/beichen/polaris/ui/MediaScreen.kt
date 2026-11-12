package com.beichen.polaris.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaRegistry
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaProvider
import com.beichen.polaris.core.media.MediaTask
import com.beichen.polaris.core.media.MediaTaskStore
import com.beichen.polaris.core.media.Ratio
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.media.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 创作工坊（调研 06 号文档「第 8 步：生图/视频工坊」直译）。
 *
 * 三个 Tab：图片（文生图/图生图）· 视频（文生视频）· 作品库（本地资产网格）。
 * 任务模型：提交 → 前台轮询（页面生命周期内 4s）→ 成功下载落盘 → 作品库可见。
 * 杀进程重进按 tasks.json 续查；URL 会过期，**本地路径才是资产真理**。
 */
@Composable
fun MediaScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ConfigStore(context) }

    var tab by rememberSaveable { mutableStateOf(0) } // 0 图片 / 1 视频 / 2 作品库

    // ——— 表单态（rememberSaveable：旋屏不丢）———
    var prompt by rememberSaveable { mutableStateOf("") }
    var ratio by rememberSaveable { mutableStateOf(Ratio.SQUARE) }
    var imageProviderId by rememberSaveable { mutableStateOf("dashscope") }
    var videoProviderId by rememberSaveable { mutableStateOf("dashscope") }
    var modelExtra by rememberSaveable { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") }
    var refDataUri by remember { mutableStateOf<String?>(null) }

    // Key 保存后 bump 一次，强制重算 providers（SharedPreferences 无响应式）
    var keyVersion by remember { mutableStateOf(0) }
    val providers: List<MediaProvider> = remember(keyVersion) { MediaRegistry.providers(context) }

    // ——— 任务态 ———
    var tasks by remember { mutableStateOf(MediaTaskStore.list(context)) }

    // 前台轮询：页面在则每 4s 推进未终态任务并刷新列表；离开页面（或杀进程）即停
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { MediaRegistry.pollDue(context) }
            tasks = MediaTaskStore.list(context)
            delay(4000)
        }
    }

    val kind = if (tab == 1) MediaKind.TEXT_TO_VIDEO
    else if (refDataUri != null) MediaKind.IMAGE_TO_IMAGE
    else MediaKind.TEXT_TO_IMAGE
    val available = providers.filter { kind in it.kinds }
    val selectedId = if (tab == 1) videoProviderId else imageProviderId
    val selected = available.firstOrNull { it.id == selectedId } ?: available.firstOrNull()
    val hasKey = selected != null && store.loadMediaKey(selected.id).isNotBlank()

    // 相册选参考图 → data URI（OpenAI images/edit 只认字节，先转 base64）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 审计 F31：修前是 `readBytes()` 之后再判 `bytes.size > 4MB`——上限被放在
                // 读完之后，选一张 100MB 的原图先 OOM、再报「超过 4MB」，用户看到的是闪退。
                // 改为读的过程中就限长，超限直接返回 null。
                val limit = 4L * 1024 * 1024
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            BoundedBody.readBytes(it, limit)
                        }
                    }.getOrNull()
                }
                when {
                    bytes == null ->
                        Toast.makeText(context, "读取失败：图片超过 4MB 或无法访问", Toast.LENGTH_SHORT).show()

                    else -> {
                        refDataUri = "data:image/png;base64," +
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        // 参考图意味着图生图：选中项不支持时切到第一个支持的
                        if (selected != null && MediaKind.IMAGE_TO_IMAGE !in selected.kinds) {
                            available.firstOrNull { MediaKind.IMAGE_TO_IMAGE in it.kinds }?.let {
                                imageProviderId = it.id
                            }
                        }
                    }
                }
            }
        }
    }

    fun submit() {
        val p = prompt.trim()
        if (p.isEmpty()) {
            Toast.makeText(context, "先写点提示词", Toast.LENGTH_SHORT).show()
            return
        }
        if (selected == null || !hasKey) {
            Toast.makeText(
                context,
                "请先配置 ${selected?.displayName ?: "该 Provider"} 的 API Key",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (kind == MediaKind.IMAGE_TO_IMAGE && refDataUri == null) {
            Toast.makeText(context, "图生图需要先选参考图", Toast.LENGTH_SHORT).show()
            return
        }
        val extra = if (modelExtra.isNotBlank()) mapOf("model" to modelExtra.trim()) else emptyMap()
        scope.launch {
            MediaRegistry.submit(
                context,
                selected.id,
                MediaRequest(
                    kind = kind,
                    prompt = p,
                    ratio = ratio,
                    refImageUris = listOfNotNull(refDataUri),
                    extra = extra
                )
            )
            tasks = MediaTaskStore.list(context)
            Toast.makeText(context, "已提交生成任务", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(title = AppDest.MEDIA.title)

        // ——— Tab 胶囊（图片 / 视频 / 作品库）———
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AgentSpacing.space5, vertical = AgentSpacing.space2),
            horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
        ) {
            listOf("图片", "视频", "作品库").forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (on) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { tab = i }
                        .padding(vertical = AgentSpacing.space2),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (tab == 2) {
            GalleryPane(context, tasks) { tasks = MediaTaskStore.list(context) }
        } else {
            // ——— 表单 + 任务（滚动区）———
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberPersistentScrollState())
                    .padding(horizontal = AgentSpacing.space5)
                    .padding(bottom = AgentSpacing.space5)
            ) {
                AkSectionCard(
                    title = if (tab == 1) "文生视频" else "文生图 / 图生图",
                    icon = Icons.Outlined.Palette,
                    description = if (tab == 1) "分钟级长任务：提交后切页面不打断，完成自动进作品库。"
                    else "提示词越具体越好；选参考图即图生图。"
                ) {
                    // 提示词
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(AgentSpacing.space3)
                    ) {
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.TopStart) {
                                    if (prompt.isEmpty()) {
                                        Text(
                                            if (tab == 1) "描述你想生成的视频画面与运动…"
                                            else "描述你想生成的画面，如：一只戴墨镜的橘猫在冲浪",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))

                    // 比例胶囊
                    Text(
                        "画面比例",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        Ratio.entries.forEach { r ->
                            val on = ratio == r
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { ratio = r }
                                    .padding(vertical = AgentSpacing.space2),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    r.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))

                    // Provider 胶囊（按 kind 过滤）
                    Text(
                        "生成服务",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    if (available.isEmpty()) {
                        Text(
                            "当前类型没有可用服务（需先配置对应 API Key）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                            available.forEach { p ->
                                val on = selected?.id == p.id
                                val keyed = store.loadMediaKey(p.id).isNotBlank()
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable {
                                            if (tab == 1) videoProviderId = p.id
                                            else imageProviderId = p.id
                                            keyDraft = ""
                                        }
                                        .padding(vertical = AgentSpacing.space2),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            p.displayName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        if (!keyed) {
                                            Text(
                                                "未配 Key",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))

                    // Key 输入（选中项未配 Key 时才出现）
                    if (selected != null && !hasKey) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(
                                        horizontal = AgentSpacing.space4,
                                        vertical = AgentSpacing.space2
                                    ),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = keyDraft,
                                    onValueChange = { keyDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MediaMonoStyle.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    singleLine = true,
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = ImeAction.Done
                                    ),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (keyDraft.isEmpty()) {
                                                Text(
                                                    "${selected.displayName} API Key",
                                                    style = MediaMonoStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            inner()
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.width(AgentSpacing.space2))
                            AkTextButton(text = "保存", onClick = {
                                val k = keyDraft.trim()
                                if (k.isEmpty()) {
                                    Toast.makeText(context, "Key 不能为空", Toast.LENGTH_SHORT).show()
                                } else {
                                    store.saveMediaKey(selected.id, k)
                                    keyDraft = ""
                                    keyVersion++
                                    Toast.makeText(
                                        context,
                                        "已保存 ${selected.displayName} Key",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            })
                        }
                        Spacer(Modifier.height(AgentSpacing.space3))
                    }

                    // 高级参数：模型 id（火山视频必填，其余可空用默认）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(
                                    horizontal = AgentSpacing.space4,
                                    vertical = AgentSpacing.space2
                                ),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = modelExtra,
                                onValueChange = { modelExtra = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MediaMonoStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (modelExtra.isEmpty()) {
                                            Text(
                                                "模型 id（可选，留空用默认）",
                                                style = MediaMonoStyle,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        inner()
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))

                    // 参考图（仅图片 Tab）
                    if (tab == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AkOutlinedButton(
                                text = if (refDataUri == null) "选择参考图" else "已选参考图 ✓",
                                onClick = {
                                    pickImage.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            )
                            if (refDataUri != null) {
                                Spacer(Modifier.width(AgentSpacing.space2))
                                AkTextButton(
                                    text = "清除",
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { refDataUri = null }
                                )
                            }
                            Spacer(Modifier.width(AgentSpacing.space2))
                            Text(
                                if (refDataUri == null) "选参考图 = 图生图" else "将按图生图提交",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(AgentSpacing.space3))
                    }

                    AkPrimaryButton(
                        text = if (tab == 1) "生成视频" else "生成图片",
                        onClick = { submit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(AgentSpacing.space4))

                // ——— 任务列表 ———
                AkSectionCard(
                    title = "任务",
                    icon = Icons.Outlined.Description,
                    description = "进行中可切页面；完成后自动落盘进作品库。"
                ) {
                    if (tasks.isEmpty()) {
                        Text(
                            "暂无任务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tasks.take(20).forEach { task ->
                            TaskCard(
                                task = task,
                                onDelete = {
                                    MediaTaskStore.delete(context, task.id)
                                    tasks = MediaTaskStore.list(context)
                                }
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                        }
                    }
                }
            }
        }
    }
}

/** 任务卡：状态 + 进度 + 失败原因 + 终态可删除。 */
@Composable
private fun TaskCard(task: MediaTask, onDelete: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AgentSpacing.space3)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.request.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (!task.isActive) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除任务",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDelete)
                        .padding(6.dp)
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space1))
        Text(
            "${timeFmt.format(Date(task.createdAt))} · ${task.providerId} · ${statusText(task)}",
            style = MaterialTheme.typography.bodySmall,
            color = when (task.status) {
                TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                TaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (task.status == TaskStatus.RUNNING) {
            Spacer(Modifier.height(AgentSpacing.space2))
            val p = task.progress
            if (p != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
        }
    }
}

private fun statusText(task: MediaTask): String = when (task.status) {
    TaskStatus.SUBMITTED -> "已提交"
    TaskStatus.RUNNING -> {
        val p = task.progress
        if (p != null) "生成中 ${(p * 100).toInt()}%" else "生成中…"
    }

    TaskStatus.SUCCEEDED -> "已完成 · ${task.resultPaths.size} 个文件"
    TaskStatus.FAILED -> "失败：${task.error ?: "未知原因"}"
}

/**
 * 作品库：filesDir/media/outputs/ 的文件网格。图片采样解码防 OOM，
 * 视频显示播放角标（点击调系统播放器）。点按 → 预览/分享/删除对话框。
 */
@Composable
private fun GalleryPane(context: android.content.Context, tasks: List<MediaTask>, onChanged: () -> Unit) {
    var outputs by remember(tasks) { mutableStateOf(listOutputFiles(context)) }
    var preview by remember { mutableStateOf<File?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val totalBytes = remember(outputs) { outputs.sumOf { it.length() } }

    Column(Modifier.fillMaxSize().padding(horizontal = AgentSpacing.space5)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 ${outputs.size} 个作品 · 占用 ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (outputs.isNotEmpty()) {
                AkTextButton(
                    text = "清空",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true }
                )
            }
        }
        Spacer(Modifier.height(AgentSpacing.space2))

        if (outputs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "作品库还是空的——去图片/视频 Tab 生成第一个作品吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = AgentSpacing.space5),
                horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
            ) {
                items(outputs.size) { i ->
                    val file = outputs[i]
                    val isVideo = file.extension.equals("mp4", true) ||
                        file.extension.equals("mov", true)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { preview = file }
                    ) {
                        if (isVideo) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "视频",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        } else {
                            val bmp = remember(file.path) { decodeSampled(file.path, 480) }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 预览 / 分享 / 删除
    val file = preview
    if (file != null) {
        val isVideo = file.extension.equals("mp4", true) || file.extension.equals("mov", true)
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(file.name, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    if (!isVideo) {
                        val bmp = remember(file.path) { decodeSampled(file.path, 1024) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                            )
                        }
                    } else {
                        Text(
                            "视频文件（${formatBytes(file.length())}），可分享到其他应用播放。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shareFile(context, file)
                }) { Text("分享") }
            },
            dismissButton = {
                Row {
                    if (isVideo) {
                        TextButton(onClick = {
                            openVideo(context, file)
                        }) { Text("播放") }
                    }
                    TextButton(onClick = {
                        file.delete()
                        preview = null
                        outputs = listOutputFiles(context)
                        onChanged()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { preview = null }) { Text("关闭") }
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空作品库") },
            text = { Text("将删除全部 ${outputs.size} 个本地文件（任务记录保留），不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    File(context.filesDir, "media/outputs").deleteRecursively()
                    confirmClear = false
                    outputs = listOutputFiles(context)
                    onChanged()
                    Toast.makeText(context, "作品库已清空", Toast.LENGTH_SHORT).show()
                }) { Text("全部删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

// ——— 作品库工具函数 ———

private fun listOutputFiles(context: android.content.Context): List<File> {
    val root = File(context.filesDir, "media/outputs")
    return root.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.toList()
}

/** 采样解码（防大图 OOM）：短边缩到 maxDim 量级后加载。 */
private fun decodeSampled(path: String, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var scale = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / scale > maxDim) scale *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
    android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}.getOrNull()

private fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val isVideo = file.extension.equals("mp4", true) || file.extension.equals("mov", true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享作品"))
    }.onFailure {
        Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun openVideo(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "没有可播放视频的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 工坊等宽小字样式（模型 id / Key 输入用）。 */
private val MediaMonoStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    textAlign = TextAlign.Start
)
