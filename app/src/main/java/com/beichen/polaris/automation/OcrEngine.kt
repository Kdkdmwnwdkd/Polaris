package com.beichen.polaris.automation

import android.graphics.Bitmap
import com.beichen.polaris.core.agent.OcrPseudoTree
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * ML Kit 中文识别的**唯一入口**。
 *
 * ## 为什么单独一个文件
 *
 * 同一块能力被两处要用，而它们要的东西不一样：
 *
 * - `ocr_image` 要一段纯文本（「读出验证码」）；
 * - `ui_tree` 的降级路径要**带坐标的行**（「点那个『立即购买』」）。
 *
 * 若两边各写一遍 recognizer 的创建/超时/回收，迟早会有一边忘了 close，或者超时值
 * 悄悄不一致。这里只保留「怎么调 ML Kit」这一件事，两种产出各自薄薄一层。
 *
 * ## 实现取舍
 *
 * - **bundled 中文识别**（模型打进 APK，约 +20MB）：不依赖 Google Play 服务，
 *   国内无 GMS 的设备也能用；unbundled 版本在国产 ROM 上大概率不可用。
 * - **完全离线**：不上传图片，不产生网络请求。
 * - 超时 15 秒：大图首次加载模型会慢（冷启动实测 1~3 秒，低端机更久），
 *   但卡住比慢更糟——超时后返回 null，调用方各自给降级文案。
 */
internal object OcrEngine {

    private const val TIMEOUT_MS = 15_000L

    /** 整段文本（按行）。给「只要文字」的调用方用。 */
    suspend fun recognizeText(bitmap: Bitmap): String? =
        run(bitmap) { text -> text.text }

    /**
     * 逐行 + 坐标（**图片像素**，未缩放）。
     *
     * `boundingBox` 理论上可能为 null（ML Kit 文档允许），那种行直接跳过——
     * 没有框的行对「点哪里」毫无用处，留着只会让下游误以为是全屏坐标 (0,0)。
     */
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrPseudoTree.Line>? =
        run(bitmap) { text ->
            text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrPseudoTree.Line(line.text, box.left, box.top, box.right, box.bottom)
                }
            }
        }

    /**
     * 把 Task 包成挂起函数：不引入 play-services-tasks 的 await 扩展，
     * 少一个依赖、也少一类版本冲突。
     *
     * 失败一律回到 null，不抛：OCR 是可选的增强，它坏了不该把 ui_tree 一起带崩。
     */
    private suspend fun <T> run(bitmap: Bitmap, extract: (Text) -> T): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                suspendCancellableCoroutine { cont ->
                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            if (cont.isActive) cont.resume(extract(result))
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                }
            } finally {
                runCatching { recognizer.close() }
            }
        }
}
