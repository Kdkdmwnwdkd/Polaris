package com.beichen.polaris.automation

import com.beichen.polaris.core.llm.primOrNull
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.workspace.PathGuard
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.coroutines.resume

/**
 * ocr_image（W-24，缺口清单 #69）：离线识别图片中的文字（中文 + 英文混排）。
 *
 * ## 为什么要它
 * 截图只能让**视觉模型**「看见」；本地小模型、或用户没挂视觉模型时等于瞎。
 * OCR 把画面变成文字直接喂给任何模型：验证码、聊天记录、票据、菜单、报错弹窗都适用。
 *
 * ## 实现取舍
 * - **ML Kit bundled 中文识别**（模型打进 APK，约 +20MB）：不依赖 Google Play 服务，
 *   国内无 GMS 的设备也能用；unbundled 版本在国产 ROM 上大概率不可用。
 * - **完全离线**：不上传图片，不产生网络请求。
 * - 两个来源：不传 path → 无障碍截当前屏（复用 W-12 唯一截图通道）；传 path → 读工作区图片。
 *
 * 只读：读图/截图无副作用。但「读的是谁的画面」要过一次人，见 [needsApprovalFor]。
 */
class OcrTool(
    private val workspace: File,
    /** 截图来源（默认走无障碍服务；测试/替换传输层时可注入）。 */
    private val screenshot: suspend () -> Bitmap? = {
        AutomationBridge.service?.takeScreenshotViaA11y()
    }
) : AgentTool {

    override val name = "ocr_image"
    override val description =
        "识别图片里的文字（离线，中英文混排）。不传 path 时截取当前屏幕再识别——" +
            "适合读验证码、聊天记录、票据、报错弹窗。返回纯文本（按行），不含坐标。"
    override val systemPrompt =
        "需要读取屏幕或图片上的文字时用 ocr_image（不传 path 即截当前屏）。" +
            "它比 ui_screenshot 更适合纯文本场景：输出是文字，任何模型都能读，不消耗视觉 token。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("path", "string", "工作区内图片路径（可选）；省略则截取当前屏幕"),
        JsonSchema.prop("max_chars", "integer", "返回文本的最大字符数，默认 6000")
    )

    /** 只读：读取画面内容，不改变设备状态。 */
    override val readOnly = true

    /**
     * 审计 F9b：不传 `path` 时截当前屏——这**就是** `ui_screenshot` 做的那件事，
     * 只是产出从图片变成了文本。两个工具两种待遇，等于给审批留了一条后门：
     * 用户拒了截图，模型改用 OCR 就能拿到同样的内容，而且纯文本更容易随下一次请求外发。
     *
     * 归组与 `ui_screenshot` 共用（`Approval.UI_SCREENSHOT`）：用户同意的是「读这块屏幕」，
     * 不是「用某个特定工具读这块屏幕」——按工具分档只会让同一个动作问两遍。
     *
     * 传了 `path` 不问：读的是工作区里的文件，边界由 [PathGuard] 管，与 `read_file` 同一档。
     */
    override fun needsApprovalFor(args: JsonObject): Boolean =
        args["path"]?.primOrNull()?.contentOrNull?.trim().isNullOrEmpty()

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args["path"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        val maxChars = (args["max_chars"]?.primOrNull()?.contentOrNull?.toIntOrNull() ?: 6000)
            .coerceIn(200, 20000)

        val bitmap = if (path.isEmpty()) {
            val svc = AutomationBridge.service
            if (!canUseA11yScreenshot(AutomationBridge.isReady(), android.os.Build.VERSION.SDK_INT) || svc == null) {
                return@withContext ToolResult.fail(
                    code = "a11y_screenshot_unavailable",
                    message = SCREENSHOT_UNAVAILABLE_GUIDANCE,
                    hint = "开启无障碍服务后重试；或把图片放进工作区并用 path 传入（如 screenshots/shot_x.png）。"
                )
            }
            try {
                svc.takeScreenshotViaA11y()
            } catch (e: Exception) {
                null
            } ?: return@withContext ToolResult.fail(
                code = "screenshot_failed",
                message = "截屏失败：无障碍服务未能取到屏幕帧。",
                hint = "确认无障碍服务仍在运行；或改用 ui_tree 读取界面结构。"
            )
        } else {
            val file = resolveWorkspaceFile(path)
                ?: return@withContext ToolResult.fail(
                    code = "bad_path",
                    message = "非法路径：$path（只能读工作区内的图片）",
                    hint = "给出相对于工作区的路径，例如 screenshots/shot_123.png。"
                )
            if (!file.isFile) {
                return@withContext ToolResult.fail(
                    code = "not_found",
                    message = "图片不存在：$path",
                    hint = "先用 ui_screenshot 截一张（会存到 screenshots/），再把返回的路径传进来。"
                )
            }
            BitmapFactory.decodeFile(file.absolutePath)
                ?: return@withContext ToolResult.fail(
                    code = "decode_failed",
                    message = "无法解码图片：$path",
                    hint = "确认文件是 PNG/JPEG；过大的图请先压缩。"
                )
        }

        val text = recognize(bitmap)
        // 释放：截图产生的 Bitmap 用完即回收，避免 OCR 多轮累积
        if (path.isEmpty()) runCatching { bitmap.recycle() }

        if (text == null) {
            return@withContext ToolResult.fail(
                code = "ocr_failed",
                message = "文字识别失败（模型未就绪或图片无法处理）",
                hint = "重试一次；若持续失败，可能是机型 ABI 或 ML Kit 模型加载问题，改用 ui_tree / ui_screenshot。"
            )
        }
        val clean = text.trim()
        if (clean.isEmpty()) {
            return@withContext ToolResult.ok(
                code = "ocr_empty",
                message = "识别完成，但画面里没有文字",
                data = "(空)"
            )
        }
        val out = if (clean.length > maxChars) clean.take(maxChars) + "\n…（已截断，共 ${clean.length} 字符）" else clean
        ToolResult.ok(
            code = "ocr_ok",
            message = "识别到 ${clean.length} 字符（${clean.lines().size} 行）",
            data = out
        )
    }

    /** 工作区路径解析（防目录穿越）。判据统一在 [PathGuard]，别再手写前缀比较。 */
    private fun resolveWorkspaceFile(rel: String): File? = PathGuard.resolveInside(workspace, rel)

    /**
     * ML Kit 识别（中文识别器同时支持英文/数字混排）。
     *
     * 用 suspendCancellableCoroutine 包 Task：不引入 play-services-tasks 的 await 扩展，
     * 少一个依赖、也少一类版本冲突。超时 15 秒（大图首次加载模型会慢）。
     */
    private suspend fun recognize(bitmap: Bitmap): String? = withTimeoutOrNull(15_000) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result.text)
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resume(null)
                    }
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
