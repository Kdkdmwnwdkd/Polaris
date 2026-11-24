package com.beichen.polaris.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.beichen.polaris.core.UiItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件在界面上的三种出现方式：待发（可删）、已发（只看）、工具返回（只读大图）。
 *
 * ## 为什么集中在一个文件
 *
 * 这三种都是「把 data URL 变成能看的像素」，共享同一套解码预算（[PREVIEW_MAX_EDGE_PX]，
 * 采样解码防 OOM）与同一种缩略图外形。以前它们散在 `ChatScreen.kt` 里三处，
 * 各自算尺寸、各自 `Base64.decode`，改一处像素预算要改三遍。
 *
 * ## 缩略图与真图的分工
 *
 * 输入区与气泡里显示的都是**缩略图**（64dp / 56dp）；发给模型的是 `ComposerState` 里
 * 那份原图 data URL，与这里无关。用户气泡落盘时存的是 [AttachmentIo.shrink] 压过的 320px 副本，
 * 所以「回看」看到的是缩略图——这正是它需要的全部。
 */
internal const val PREVIEW_MAX_EDGE_PX = 1080

/** 气泡内缩略图边长：够看清是哪张，又不至于把一条消息顶成一屏。 */
private val BUBBLE_THUMB = 56.dp

/** 输入区缩略图边长：比气泡大一圈，因为这里要能点中右上角那个 ×。 */
private val COMPOSER_THUMB = 64.dp

/**
 * 把 data URL 解码成缩略图位图。
 *
 * 只用于预览，不参与请求——请求始终带 data URL 原文，避免二次编码损耗。
 * 采样解码：这张图只用于界面预览（fillMaxWidth ≈ 1080px），按原始尺寸解一张
 * 4000×3000 就是 48MB 堆，连续几张直接 OOM。
 */
internal fun decodeDataUrl(dataUrl: String): Bitmap? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma < 0) return null
    val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
    return AttachmentIo.decodeSampled(bytes, PREVIEW_MAX_EDGE_PX)
}

/**
 * 一张缩略图。[onRemove] 为 null 时右上角不画 ×（已发出的消息不该还能删）。
 */
@Composable
internal fun Thumb(dataUrl: String, size: Dp = COMPOSER_THUMB, onRemove: ((String) -> Unit)? = null) {
    val bitmap = remember(dataUrl) { decodeDataUrl(dataUrl) }
    Box(modifier = Modifier.size(size)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onRemove(dataUrl) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 功能五（P1-4）：文件名 chip。
 *
 * 名字截断到 180dp：长文件名不能把「×」挤出屏幕——那是唯一能取消的入口。
 */
@Composable
internal fun FileChip(name: String, onRemove: ((String) -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "文件 · $name",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp)
        )
        if (onRemove != null) {
            Spacer(Modifier.width(AgentSpacing.space2))
            Text(
                text = "×",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onRemove(name) }
                    .padding(horizontal = AgentSpacing.space1)
            )
        }
    }
}

/**
 * 功能四（P0-2）：待发附件缩略图行（输入框上方，横向滚动）。
 *
 * 放在输入框上方——附件是「随消息一起走的东西」，视觉上必须贴着输入框，不能丢到别处。
 */
@Composable
internal fun AttachedImagesRow(images: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        images.forEach { Thumb(it, COMPOSER_THUMB, onRemove) }
    }
}

/** 功能五（P1-4）：待发文件行。与 [AttachedImagesRow] 并排：图片看缩略图、文件看名字。 */
@Composable
internal fun AttachedFilesRow(names: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        names.forEach { FileChip(it, onRemove) }
    }
}

/**
 * 已发出那条消息里的附件（只读）。
 *
 * 之前用户发完图，自己那条气泡只有一行字，看不到发了什么——「能发了但看不见发了什么」
 * 等于功能只做了一半。这里把缩略图与文件名铺在气泡正文下方。
 */
@Composable
internal fun BubbleAttachments(
    images: List<String>,
    fileNames: List<String>,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty() && fileNames.isEmpty()) return
    Column(
        modifier = modifier.padding(top = AgentSpacing.space1),
        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space1)
    ) {
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space1)) {
                images.forEach { Thumb(it, BUBBLE_THUMB) }
            }
        }
        fileNames.forEach { FileChip(it) }
    }
}

/** 把 data URL（base64）解码成可显示的位图，异步避免阻塞主线程。 */
@Composable
internal fun DataImage(dataUrl: String) {
    val bitmap by produceState<ImageBitmap?>(null, dataUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val comma = dataUrl.indexOf(',')
                if (comma < 0 || !dataUrl.startsWith("data:image")) return@runCatching null
                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                AttachmentIo.decodeSampled(bytes, PREVIEW_MAX_EDGE_PX)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "工具返回的图片",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        )
    }
}

/**
 * 构造一条用户气泡。
 *
 * 图片在这里被压成 320px 副本：落盘的是它，上屏显示的也是它（气泡里只有 56dp，
 * 320px 肉眼看不出差别）。**发给模型的原图不经过这里**——那条路径是
 * `loop.send(fullInput, images, priorMessages)`，用的是 `takeAttachments()` 的原始列表。
 *
 * 压图放 IO：8 张 4000×3000 的照片要解码 + 压缩，在主线程做会卡出一个可见的掉帧。
 */
internal suspend fun userBubbleItem(
    text: String,
    images: List<String>,
    fileNames: List<String>,
    timeMs: Long
): UiItem.User = UiItem.User(
    text,
    timeMs = timeMs,
    fileNames = fileNames,
    images = if (images.isEmpty()) emptyList()
    else withContext(Dispatchers.IO) { images.map { AttachmentIo.shrink(it) } }
)
