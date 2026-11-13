package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.audio.VoiceArbiter
import com.beichen.polaris.audio.VoiceDenied
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * speak（W-16）：用系统 TTS 朗读一段文字。
 *
 * 典型场景：用户在开车/做饭，要求「念给我听」；或长回答只想听结论。
 * 与对话界面的文字输出互补——工具只负责「出声」，内容仍会留在聊天记录里。
 *
 * ## 为什么经 [VoiceArbiter] 而不是直连 TextSpeaker（2026-11-13）
 *
 * 直连意味着模型可以在**用户正在说话**的时候开口——麦克风会把播报声录进去，
 * 识别结果是一团乱码，而用户完全不知道发生了什么。仲裁器会在这种情况下明确拒绝，
 * 并给出「稍后再读」的说明，模型据此能自己改写策略（比如改用文字回复）。
 */
class SpeakTool(private val voice: VoiceArbiter) : AgentTool {

    override val name = "speak"

    override val description =
        "用系统语音朗读一段文本（异步播放，立即返回）。适合用户明确要求「念给我听/读一下」时使用；" +
            "不要用它朗读整篇长文，只读结论或要点。"

    override val systemPrompt =
        "用户要求朗读时，把要读的内容整理成口语化短句（去掉 Markdown 符号与表格），再调 speak。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "要朗读的文本（建议 500 字以内，去掉 Markdown 标记）"),
        JsonSchema.prop("language", "string", "语言：zh / en / auto（默认 auto）", enum = listOf("zh", "en", "auto")),
        JsonSchema.prop("rate", "number", "语速 0.5–2.0，默认 1.0")
    )

    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args["text"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) {
            return ToolResult.fail("missing_param", "缺少 text", "传入要朗读的文本，例如 {\"text\":\"已完成 3 项清理\"}。")
        }
        val language = args["language"]?.primOrNull()?.contentOrNull ?: "auto"
        val rate = args["rate"]?.primOrNull()?.contentOrNull?.toFloatOrNull() ?: 1.0f
        return voice.speak(text, language, rate).fold(
            onSuccess = {
                ToolResult.ok(
                    code = "speak_started",
                    message = "已开始朗读（${text.length} 字）。",
                    data = "语音在后台播放；如需停止，可让用户直接说「停」。"
                )
            },
            onFailure = { e ->
                // 被仲裁拒绝和引擎故障要分开：前者是「等一下就好」，后者是「这台机器不行」。
                // 混成一句会让模型在设备没有语音引擎时反复重试。
                if (e is VoiceDenied) {
                    ToolResult.fail(
                        code = e.code,
                        message = e.message,
                        hint = "现在不是出声的时候。把内容用文字回复给用户，不要重试、不要改成别的朗读方式。"
                    )
                } else {
                    ToolResult.fail(
                        code = "speak_failed",
                        message = "朗读失败：${e.message}",
                        hint = "设备可能没有语音引擎或缺少对应语言包；改把内容用文字回复给用户，不要反复重试。"
                    )
                }
            }
        )
    }
}
