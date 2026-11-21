package com.beichen.polaris.automation

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 一次调用里按顺序执行多个界面动作（2026-11-21）。
 *
 * ## 为什么要它
 *
 * 改造前每一次界面动作都是一次完整的模型往返：
 *
 * ```
 * 往返1 → ui_tap(搜索框)      2~5s 网络 + 1.5s 回执
 * 往返2 → ui_input("天气")    2~5s 网络 + 1.5s 回执
 * 往返3 → ui_key(enter)       2~5s 网络 + 1.5s 回执
 * 往返4 → ui_tree             2~5s 网络 + 1.5s 回执
 * 往返5 → ui_tap(第一条结果)  2~5s 网络 + 1.5s 回执
 * ```
 *
 * 用户在真机反馈「无障碍操控又慢」——慢的不是点击本身（几十毫秒），是这 5 次往返。
 * 把这 5 步压进一次调用，网络那部分就从 5 份变成 1 份。
 *
 * 参照：dsh 的 `dsh-tool-executor-ptc` 解法更彻底（模型写一段代码，代码里调工具），
 * 但那要引入一个 JS 解释器。北辰先做「动作数组」这个轻量版：不改模型能力，
 * 只把已经存在的多个动作合成一批。
 *
 * ## 边界（这三条不能省）
 *
 * 1. **白名单写死，不做 `ui_` 前缀匹配**。批处理等于把「一次审批」变成「N 次动作」，
 *    如果把 `ui_screenshot` 这种会回灌图片的工具放进来，一次调用可能返回 8 张图，
 *    直接把上下文打爆。白名单只收纯动作与只读的 `ui_tree`。
 * 2. **整批只审批一次**。这是体验改进的核心——用户从点 5 次「允许」变成点 1 次。
 *    代价是审批卡上看不到每一步的细节，所以 [description] 必须让模型自己保证
 *    批内动作是「同一个意图」的连续操作。
 * 3. **批内坐标基于同一屏**。任何会跳转页面的动作（点开新 App、跳详情页）之后，
 *    后续坐标全部失效。这条写进 description 让模型自己分组，而不是我们替它猜。
 */
class UiBatchTool(private val registry: ToolRegistry) : AgentTool {

    override val name = "ui_batch"

    /** 整批一次审批（见类注释第 2 条）。 */
    override val needsApproval = true

    /** 含真实点击/输入，改变设备状态。 */
    override val readOnly = false

    override val description: String =
        "一次提交最多 $MAX_STEPS 个界面动作，按顺序执行并返回每一步的结果。整批只审批一次。" +
            "用于「同一屏上连续的几个动作」：点搜索框 → 输入 → 回车 → 点第一条结果。\n" +
            "动作类型：tap(x,y) / click(text 或 resource_id) / long_press(x,y) / " +
            "swipe(start_x,start_y,end_x,end_y,duration_ms) / input(text) / key(home|back|recent) / " +
            "tree(读取当前界面) / wait(ms，等界面加载)。\n" +
            "**不要**把会跳转到新页面的动作和之后的动作放进同一批——跳转后坐标全部失效，" +
            "应该分批调用，中间用 tree 重新取坐标。\n" +
            "某步失败时默认整批停止并返回停在哪一步。"

    override val parameters: JsonSchema = JsonSchema.objectSchema(
        JsonSchema.prop("steps", "array", "按顺序执行的界面动作，最多 $MAX_STEPS 个"),
        JsonSchema.prop("stop_on_error", "boolean", "某步失败是否停止后续（默认 true）")
    ).let { base ->
        // steps 需要 items 才能被严格 function-calling 接受（W-31 已支持 items）。
        base.copy(
            properties = base.properties.mapValues { (k, v) ->
                if (k != "steps") v else v.copy(items = STEP_SCHEMA)
            },
            required = listOf("steps")
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args["steps"]?.arrOrNull()
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 steps",
                hint = "ui_batch 需要 steps 数组，例如 " +
                    """[{"action":"tap","x":540,"y":320},{"action":"input","text":"天气"},{"action":"key","key":"enter"}]"""
            )

        if (raw.isEmpty()) {
            return ToolResult.fail(
                code = "empty_steps",
                message = "steps 是空数组",
                hint = "至少给一个动作；只有一个动作时直接调对应的单个工具（ui_tap 等）更省 token。"
            )
        }
        if (raw.size > MAX_STEPS) {
            return ToolResult.fail(
                code = "too_many_steps",
                message = "steps 有 ${raw.size} 个，超过上限 $MAX_STEPS",
                hint = "拆成多批：每批不超过 $MAX_STEPS 个动作，批之间用 ui_tree 重新确认界面。"
            )
        }

        val stopOnError = args["stop_on_error"]?.primOrNull()?.contentOrNull?.toBooleanStrictOrNull() ?: true

        // 先整体校验一遍再动手：半途因为一个拼错的 action 停下，前面已经点掉的动作收不回来。
        val plan = ArrayList<PlannedStep>(raw.size)
        for ((i, el) in raw.withIndex()) {
            val step = el.objOrNull()
                ?: return ToolResult.fail(
                    code = "invalid_step",
                    message = "第 ${i + 1} 个动作不是对象",
                    hint = "每个动作必须是对象，例如 {\"action\":\"tap\",\"x\":540,\"y\":320}。"
                )
            val action = step["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()
                ?: return ToolResult.fail(
                    code = "missing_param",
                    message = "第 ${i + 1} 个动作缺少 action",
                    hint = "action 取值：${ALLOWED.keys.joinToString(" / ")} / wait。"
                )
            if (action != WAIT) {
                val toolName = ALLOWED[action]
                    ?: return ToolResult.fail(
                        code = "unknown_action",
                        message = "第 ${i + 1} 个动作的类型不认识：$action",
                        hint = "action 取值：${ALLOWED.keys.joinToString(" / ")} / wait。" +
                            "界面动作以外的能力（shell、装应用、读文件）不走 ui_batch，单独调用。"
                    )
                val tool = registry.get(toolName)
                    ?: return ToolResult.fail(
                        code = "tool_unavailable",
                        message = "动作 $action 对应的 $toolName 当前不在工具表里",
                        hint = "该工具在本模式下未启用；改用可用的动作，或逐个调用其他工具。"
                    )
                plan.add(PlannedStep(i, action, tool, buildArgs(action, step)))
            } else {
                val ms = step["ms"]?.primOrNull()?.contentOrNull?.toIntOrNull() ?: DEFAULT_WAIT_MS
                plan.add(PlannedStep(i, WAIT, null, null, ms.coerceIn(0, MAX_WAIT_MS)))
            }
        }

        val lines = ArrayList<String>(plan.size + 2)
        var done = 0
        for (p in plan) {
            if (p.action == WAIT) {
                delay(p.waitMs.toLong())
                done++
                lines.add("[${p.index + 1}] wait ${p.waitMs}ms → ok")
                continue
            }
            val result = try {
                p.tool!!.execute(p.args!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单个工具内部抛异常不该让整批以「崩溃」收场——记录并交给 stopOnError 规则处理。
                ToolResult.fail(
                    code = "step_exception",
                    message = "第 ${p.index + 1} 步（${p.action}）执行时抛异常：${e.javaClass.simpleName}",
                    hint = "重读 ui_tree 确认界面后重试这一步。"
                )
            }
            done++
            lines.add(
                if (result.ok) "[${p.index + 1}] ${p.action} → ${result.code}"
                else "[${p.index + 1}] ${p.action} → ${result.code}：${result.message.take(120)}"
            )
            if (!result.ok && stopOnError) {
                return ToolResult.fail(
                    code = "batch_stopped",
                    message = "第 ${p.index + 1} 步（${p.action}）失败，已停止；前 ${done - 1} 步已执行",
                    hint = (result.hint ?: "") +
                        " 修好这一步后，可以直接从第 ${p.index + 1} 步继续（前面的已经做完了，不要再重复）。"
                    ,
                    data = lines.joinToString("\n")
                )
            }
        }

        return ToolResult.ok(
            code = "batch_ok",
            message = "已按顺序执行 $done 个动作（整批一次调用完成，省去 ${(done - 1).coerceAtLeast(0)} 次模型往返）",
            data = lines.joinToString("\n") + "\n界面可能已变化：需要坐标时重新 ui_tree，不要沿用旧 bounds。"
        )
    }

    /** 把批内一步的字段搬成目标工具认识的形状；多余字段直接丢弃（模型常把 x/y 塞给 ui_click）。 */
    private fun buildArgs(action: String, step: JsonObject): JsonObject {
        val keys = FIELD_MAP[action] ?: emptyList()
        return buildJsonObject {
            for (k in keys) {
                val v = step[k] ?: continue
                put(k, v)
            }
        }
    }

    private data class PlannedStep(
        val index: Int,
        val action: String,
        val tool: AgentTool?,
        val args: JsonObject?,
        val waitMs: Int = 0
    )

    private companion object {
        /** 上限 8：再多，模型给的后半段坐标几乎必然已经过时，失败率会盖过省的往返。 */
        const val MAX_STEPS = 8
        const val WAIT = "wait"
        const val DEFAULT_WAIT_MS = 500

        /** 单步等待上限 5s：模型偶尔会写 10000，那种等待应该显式说明理由而不是默默干等。 */
        const val MAX_WAIT_MS = 5_000

        /** 动作 → 工具名。白名单，不做前缀匹配（见类注释第 1 条）。 */
        val ALLOWED: Map<String, String> = mapOf(
            "tap" to "ui_tap",
            "click" to "ui_click",
            "long_press" to "ui_long_press",
            "swipe" to "ui_swipe",
            "input" to "ui_input",
            "key" to "ui_key",
            "tree" to "ui_tree"
        )

        /** 各动作允许透传的字段（其余丢弃）。 */
        val FIELD_MAP: Map<String, List<String>> = mapOf(
            "tap" to listOf("x", "y"),
            "click" to listOf("text", "resource_id"),
            "long_press" to listOf("x", "y"),
            "swipe" to listOf("start_x", "start_y", "end_x", "end_y", "duration_ms"),
            "input" to listOf("text"),
            "key" to listOf("key"),
            "tree" to emptyList()
        )

        /** steps 的元素 schema。字段全可选，按 action 取用——比 7 个分支对象更好写也更好读。 */
        val STEP_SCHEMA: JsonSchema.Property = JsonSchema.Property(
            type = "object",
            description = "一个界面动作；字段按 action 取用",
            properties = mapOf(
                "action" to JsonSchema.Property(
                    type = "string",
                    description = "动作类型",
                    enum = ALLOWED.keys.toList() + WAIT
                ),
                "x" to JsonSchema.Property("integer", "tap/long_press 的 X 坐标"),
                "y" to JsonSchema.Property("integer", "tap/long_press 的 Y 坐标"),
                "text" to JsonSchema.Property("string", "click 的可见文本，或 input 要写入的文本"),
                "resource_id" to JsonSchema.Property("string", "click 的 resource-id 后缀"),
                "start_x" to JsonSchema.Property("integer", "swipe 起点 X"),
                "start_y" to JsonSchema.Property("integer", "swipe 起点 Y"),
                "end_x" to JsonSchema.Property("integer", "swipe 终点 X"),
                "end_y" to JsonSchema.Property("integer", "swipe 终点 Y"),
                "duration_ms" to JsonSchema.Property("integer", "swipe 时长（毫秒，默认 300）"),
                "key" to JsonSchema.Property(
                    type = "string",
                    description = "系统键",
                    enum = listOf("home", "back", "recent")
                ),
                "ms" to JsonSchema.Property("integer", "wait 的毫秒数（上限 $MAX_WAIT_MS）")
            ),
            required = listOf("action")
        )
    }
}
