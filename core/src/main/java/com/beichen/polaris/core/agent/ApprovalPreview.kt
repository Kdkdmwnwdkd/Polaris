package com.beichen.polaris.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 审批弹窗里的「将执行」预览。
 *
 * ## 修的是什么
 *
 * 原来只有一句 `args.toString().take(500)`。而审批的**判定**用的是全量参数
 * （[ApprovalScope.of] 解析 `command` / `url` / 路径）。两者不同源时，危险的那一半
 * 很可能正好在被砍掉的尾部：
 *
 * ```
 * {"command":"cd ~/work && ./gradlew assembleRelease && rm -rf build/cache && ..."}
 * ```
 *
 * 前 500 字符恰好停在 `rm -rf` 之前，用户看到的是「构建一下」，点「允许」，
 * 实际执行的是构建 + 删除。这不是文案瑕疵，是审批通道的信息差——
 * **用户同意的是他看到的东西，不是我们实际执行的东西**。
 *
 * ## 为什么还是截断
 *
 * 完全放开会把 `ui_screenshot` 的 base64 参数（几百 KB）塞进 Compose 文本排版，
 * 弹窗会卡住甚至 OOM。所以保留上限，但改成**保头保尾**：
 * 头部给上下文（哪个工具、什么参数），尾部给结局（`&& rm -rf` 藏在这里）。
 *
 * 中间被省略的部分显式写出来（省略了多少字符），用户至少知道「这里还有东西」，
 * 而不是以为看到的就是全部。
 */
object ApprovalPreview {

    /**
     * 预览上限。
     *
     * 2000 字符 ≈ 一整条复杂 shell 命令 + 若干参数，仍远小于一张截图的 base64。
     * 保留下来的尾部占 7 成：命令的结局（重定向、`&&` 串接、目标路径）都在末尾。
     */
    const val DEFAULT_LIMIT = 2_000

    /** 头部占比。3 成够看清「这是什么参数」，7 成留给尾部。 */
    private const val HEAD_RATIO = 0.3

    /** 省略标记的预留长度（实际文案随数字长度浮动，这里只保证总量不失控）。 */
    private const val MARKER_RESERVE = 48

    /**
     * 生成预览。长度不超过 [limit] 时原样返回——**不做任何改写**，
     * 让「短参数」的弹窗与判定输入逐字节一致。
     */
    fun of(args: String, limit: Int = DEFAULT_LIMIT): String {
        if (args.length <= limit) return args
        val headLen = (limit * HEAD_RATIO).toInt().coerceAtLeast(1)
        val tailLen = (limit - headLen - MARKER_RESERVE).coerceAtLeast(1)
        val omitted = args.length - headLen - tailLen
        if (omitted <= 0) return args
        return buildString(headLen + tailLen + MARKER_RESERVE) {
            append(args, 0, headLen)
            append("\n…（中间省略 ").append(omitted).append(" 字符，以下是结尾）…\n")
            append(args, args.length - tailLen, args.length)
        }
    }

    /**
     * 带工具名的预览（2026-11-21）。
     *
     * `ui_batch` 一次性提交多个界面动作，整批只审批一次。如果卡片上只显示
     * `{"steps":[{"action":"tap","x":540,"y":320},…]}` 这一坨，用户看不懂就会直接点
     * 「允许」——那这次审批等于没有。所以对它单独生成人话摘要，其余工具走原路径，
     * **逐字节不变**（批处理之外的行为零改动）。
     */
    fun of(toolName: String, args: JsonObject, limit: Int = DEFAULT_LIMIT): String {
        val human = if (toolName == BATCH_TOOL) batchSummary(args) else null
        return of(human ?: args.toString(), limit)
    }

    private const val BATCH_TOOL = "ui_batch"

    /**
     * 把 `{"steps":[{"action":"tap","x":540,"y":320},…]}` 翻成
     * 「3 个界面动作：\n1. 点 (540, 320)\n2. 输入「天气」\n3. 按 enter」。
     *
     * 结构不符（没有 steps、或空数组）时返回 null，让调用方回退到原始 JSON——
     * 宁可难看，也不要在看不懂参数的情况下编一句摘要。
     */
    internal fun batchSummary(args: JsonObject): String? {
        val steps = args["steps"] as? JsonArray ?: return null
        if (steps.isEmpty()) return null
        val lines = steps.mapIndexedNotNull { i, el ->
            val step = el as? JsonObject ?: return@mapIndexedNotNull null
            val action = (step["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            "${i + 1}. ${describeStep(action, step)}".trim()
        }
        if (lines.isEmpty()) return null
        return "${steps.size} 个界面动作（按顺序执行，失败即停）：\n" + lines.joinToString("\n")
    }

    private fun describeStep(action: String, step: JsonObject): String {
        fun s(k: String) = (step[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun n(k: String) = (step[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return when (action) {
            "tap" -> "点 (${n("x") ?: "?"}, ${n("y") ?: "?"})"
            "long_press" -> "长按 (${n("x") ?: "?"}, ${n("y") ?: "?"})"
            "click" -> if (s("text").isNotBlank()) "点「${s("text").take(30)}」"
            else "点 #${s("resource_id").take(30)}"
            "input" -> "输入「${s("text").take(60)}」"
            "key" -> "按 ${s("key")} 键"
            "swipe" -> "从 (${n("start_x") ?: "?"}, ${n("start_y") ?: "?"}) " +
                "滑到 (${n("end_x") ?: "?"}, ${n("end_y") ?: "?"})"
            "tree" -> "读取界面结构"
            "wait" -> "等 ${n("ms") ?: 0}ms"
            else -> action.ifBlank { "未知动作" }
        }
    }
}
