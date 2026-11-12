package com.beichen.polaris.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.beichen.polaris.core.net.BoundedBody
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 附件（图片 / PDF / DOCX / 文本）→ 内存对象的读取层。
 *
 * ## 为什么单独成文件
 *
 * 这些函数原本都是 `ChatViewModel` 的私有方法。审计 F23–F27 给它们加限读、采样解码、
 * 位图回收之后，`ChatViewModel` 从 3588 行涨到 3657 行，撞上架构守卫的「存量文件只减不增」
 * 冻结上限（`scripts/qa/arch-guard` 那一关）。
 *
 * 那道守卫不是在挑刺：3588 行的 ViewModel 已经很难读了，再往上加只会更糟。正确做法是
 * 把「URI → 字节 → 对象」这段**与状态无关**的纯 IO 搬出来，ViewModel 只留编排。
 *
 * ## 一条贯穿全文件的规则：任何读取都必须有上限
 *
 * 这个项目在 `read_file`（F19）、`http_request`（F21）、MCP 响应（F29）上各踩过一次
 * 「上限只作用在返回值上」的坑，附件路径是第四次。所以这里每个入口都是**边读边计数**：
 * 文本 8MB、DOCX 的 document.xml 8MB、图片先按 `inSampleSize` 降采样、PDF 只渲染前几页，
 * 每页位图用完立刻 `recycle()`。
 */
internal object AttachmentIo {

    /**
     * 单条消息最多挂几张图（审计 F27）。
     *
     * 8 张 ≈ 3MB base64，是「够用」与「请求体不炸」的交点：主流多模态接口对单请求图片数
     * 多在 10~20 张，但像素总量普遍有上限；再叠加 PDF 一次 5 页，8 张留了余量。
     */
    const val MAX_PENDING_IMAGES = 8

    /**
     * 文本附件读取。
     *
     * 修前是 `input.readBytes().toString(UTF_8)`：整份进堆，调用方的 `take(MAX_FILE_CHARS)`
     * 发生在读完以后——对 500MB 的 log 完全不设防，进程先 OOM，`take` 一次都没机会执行。
     *
     * [BoundedBody] 边读边计数，8MB 即停；截断点落在字符中间时丢半字符而不是产出 U+FFFD
     * （模型会把替换符当成真实内容继续推理）。
     */
    fun readText(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.openInputStream(uri)?.use { input ->
            BoundedBody.read(input, BoundedBody.MAX_READ_BYTES).text
        }
    }.getOrNull()

    /**
     * 两阶段采样解码（审计 F26）。
     *
     * 第一次只读尺寸（`inJustDecodeBounds`，不分配像素内存），据此算出 `inSampleSize`
     * ——只要结果不小于 [maxEdge] 就继续翻倍降采样，内存占用按 1/4 递减。第二次才真解码。
     *
     * 为什么开两遍流：`ContentResolver` 给的是不可 seek 的 InputStream，读完没法回头。
     * 开两遍的代价是一次 URI 解析，远小于解一张 400MB 位图——而修前正是「解了再说」：
     * 一张 108MP 照片是 12000×9000×4B ≈ 432MB，`decodeStream` 当场 OOM。
     */
    fun decodeSampled(cr: ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** 长边超过 [maxEdge] 等比缩小（采样解码之后仍可能偏大，这一步把它压到目标尺寸）。 */
    fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / max
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true
        )
    }

    /**
     * PDF → 图片 data URL（前 [maxPages] 页，长边 ≤ [maxEdge]）。
     * 渲染前铺白底：PDF 页面本身透明，不铺白会得到黑底图（视觉模型也读不清）。
     *
     * 每页位图在 `finally` 里回收（审计 F25）：修前从不 recycle，5 页 × 1024² × 4B ≈ 20MB
     * 要等 GC 才释放，低内存机在渲染阶段就可能 OOM。
     */
    fun renderPdf(cr: ContentResolver, uri: Uri, maxPages: Int, maxEdge: Int): List<String> {
        val images = mutableListOf<String>()
        runCatching {
            val fd = cr.openFileDescriptor(uri, "r") ?: return emptyList()
            fd.use { parcel ->
                android.graphics.pdf.PdfRenderer(parcel).use { renderer ->
                    val pages = minOf(renderer.pageCount, maxPages)
                    for (i in 0 until pages) {
                        renderer.openPage(i).use { page ->
                            val scale = maxEdge.toFloat() / maxOf(page.width, page.height)
                            val w = (page.width * scale).toInt().coerceIn(1, maxEdge)
                            val h = (page.height * scale).toInt().coerceIn(1, maxEdge)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val bytes = try {
                                bmp.eraseColor(0xFFFFFFFF.toInt())
                                page.render(
                                    bmp, null, null,
                                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                                ByteArrayOutputStream().use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                    out.toByteArray()
                                }
                            } finally {
                                bmp.recycle()
                            }
                            images += "data:image/jpeg;base64," +
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                        }
                    }
                }
            }
        }
        return images
    }

    /**
     * DOCX → 文本：`word/document.xml` 里逐个 `<w:t>` 取文字，`</w:p>` 视为换行。
     * 纯系统 `ZipInputStream` + 正则，不引第三方库。
     *
     * `document.xml` 是压缩包里的一个 entry，可以被做成 zip 炸弹（解压后几 GB），
     * 所以这里同样走 [BoundedBody] 而不是 `readBytes()`（审计 F24）。
     */
    fun readDocx(cr: ContentResolver, uri: Uri): String {
        val sb = StringBuilder()
        runCatching {
            cr.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val xml = BoundedBody.read(zip, BoundedBody.MAX_READ_BYTES).text
                            val normalized = xml.replace("</w:p>", "\n")
                            Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                                .findAll(normalized)
                                .forEach { sb.append(decodeXmlEntities(it.groupValues[1])) }
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return sb.toString()
    }

    /** 最小 XML 实体解码（DOCX 文本里常见五种；`&amp;` 必须最后解，否则会二次解码）。 */
    private fun decodeXmlEntities(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
