package com.beichen.polaris.core.llm.local

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.ModelInfo
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.time.Monotonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import com.beichen.polaris.core.llm.ChatStreamEvent
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import java.util.Locale
import java.util.UUID

/**
 * 本地 llama.cpp 推理客户端（LLMClient 实现）。
 *
 * 与云端 Provider 的差异全部收敛在本类：
 * 1. **不走网络**：模型文件在本地，prompt 由 llama.cpp 的 Jinja 对话模板渲染
 *    （Qwen/Llama/Gemma 各自的格式由模型自带，不在这里手拼——这是体验不碎片化的关键）。
 * 2. **工具调用是提示词协议**，不是协议原生：本地小模型对各家模板的 tool_use 支持
 *    参差不齐，统一用「整个回复就是一个 JSON」的约定（见 [LocalAgentPrompt]），
 *    解析则走宽容解析器（见 [LocalToolCallParser]）——3B 写不标准是常态，读得懂才算数。
 * 3. **图片忽略**：本地小模型多为纯文本，多模态消息只取文字部分（截图类工具结果
 *    在 [ToolMessage] 里附了图片，这里也只取文本，UI 层已单独渲染）。
 *
 * 实例生命周期在 [LocalLlamaEngine]（进程级复用，避免每轮重新加载模型）。
 *
 * 流式（T01/T02）：[streamText] 与 [streamChat] 都走 native 的 token 级回调，
 * 边生成边上屏；工具调用因为走「整条 JSON」协议，首字符是 `{` 时改为一憋到底。
 */
class LocalLlamaClient(
    private val engine: LocalLlamaEngine,
    private val setting: LocalLlamaSetting,
    private val modelStore: GgufModelStore,
    private val config: LLMConfig
) : LLMClient {

    override val id: String = "local-llama"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 本进程内「GPU 后端已经炸过」的原因 —— **存在 [LocalLlamaEngine] 里**，不是这里。
     *
     * 这个 client 是每轮对话新建的（provider 工厂），实例字段活不过一轮。之前把它放在
     * 这里，真机表现就是「上一轮说已关闭 GPU，下一轮又打出 99 层」：
     *   13:13:09 本次进程内已关闭 GPU 加速 → 13:13:16 GPU 卸载：99 层
     * 于是同一个坏驱动被反复踩、每轮都要先白跑一次。
     */
    private val gpuFaultDisabled: String?
        get() = LocalLlamaEngine.gpuDisabledReason

    // —— 对话主路径 ————————————————————————————————————————————————

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        ensureModelLoaded()
        val eff = effectivePrompt(messages, tools)
        val prompt = buildPrompt(eff.messages)
        AgentLogger.llmRequest(currentModelLabel(), eff.messages.size, eff.tools.size)
        val startedAt = System.currentTimeMillis()
        var text = engine.generate(prompt, setting.maxTokens, null)
        // GPU 后端自己炸了（Vulkan 驱动在 decode 里抛了 C++ 异常）：本轮没算出来，但进程还在。
        // 这不是「模型不会答」，所以别把原因包装成推理失败——关掉 GPU、按 CPU 重新加载，
        // 当场把这一轮补出来。用户付出的只是多等一次加载，而不是「又崩了」。
        val fault = engine.takeGpuFault()
        if (fault.isNotEmpty()) {
            LocalLlamaEngine.disableGpu(fault)
            AgentLogger.e("LocalLlama", "GPU 后端在生成时抛出异常：$fault")
            AgentLogger.w("LocalLlama", "本次进程内已关闭 GPU 加速，改用 CPU 重新加载并重试这一轮…")
            ensureModelLoaded()
            text = engine.generate(prompt, setting.maxTokens, null)
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (text.isEmpty()) {
            val diag = nativeDiagnostics()
            AgentLogger.e("LocalLlama", "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag")
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.llmResponse(currentModelLabel(), text.isNotEmpty(), elapsed, "生成长度=${text.length}")
        parseAssistant(text, eff.tools)
    }

    /**
     * 确保模型已加载（必要时加载，秒级）。
     *
     * 三个失败分支都必须抛异常而不是返回空文本——空文本会让 AgentLoop 以为
     * 「模型说完了」，用户侧表现为毫无反应的静默失败，是最难排查的一类 bug。
     *
     * 用 [LocalInferenceException] 而非 `IOException`：上层把 IOException 一律当成
     * 网络问题，而这里每一条都是「本地配置/文件」问题，提示不能往网络上引。
     */
    private fun ensureModelLoaded() {
        val name = setting.activeModel
        if (name.isBlank()) {
            throw LocalInferenceException("尚未选择本地模型：请到「模型配置 → 本地模型」下载并选用")
        }
        val file = modelStore.modelFile(name)
        if (!file.isFile) {
            throw LocalInferenceException("本地模型文件缺失：$name（可能已被删除，请重新下载）")
        }
        // 安全模式是进程级开关，且 n_batch/n_ubatch 在 llama_init_from_model 里就定死了，
        // 所以必须在 acquire 之前推。放在这里（而不是启动时）有两个原因：
        //   1) 启动时不 dlopen，App 冷启动不被 .so 拖慢；
        //   2) 用户在设置页开关安全模式后，「下一条消息」就生效，不用重启。
        LlamaJni.applySafeMode(setting.safeMode)

        val gpu = resolveGpuPlan()
        // 显存保护：GPU 开启时上下文压到安全值。
        //
        // 这一步不能省。真机 2026-10-29：3B + ctx=16384 + 全量卸载，用户发一句话，
        // 进程直接消失（KV 和权重一起把显存顶爆，崩在 native，Java 层一句日志都没有）。
        // 与其让用户「等两分钟然后闪退」，不如用 4096 跑起来——少一点上下文，
        // 换来的是能出字。用户真要长上下文，把 GPU 关掉就是了。
        val nCtx = LocalDeviceRules.effectiveCtx(setting.nCtx, gpu.layers, setting.safeMode)
        if (setting.safeMode && nCtx != setting.nCtx) {
            AgentLogger.w(
                "LocalLlama",
                "安全模式：上下文限制为 $nCtx（设置里是 ${setting.nCtx}）。" +
                    "上次在本机纯 CPU 推理时崩溃过，先把负载压下来；稳定后可在「推理参数」里关闭安全模式",
            )
        } else if (nCtx != setting.nCtx) {
            AgentLogger.w(
                "LocalLlama",
                "GPU 开启时上下文限制为 $nCtx（设置里是 ${setting.nCtx}）：KV 缓存要和权重共用显存，" +
                    "超过这个值容易在装载阶段分配失败甚至闪退。需要更长上下文请把 GPU 设为关闭",
            )
        }
        var handle = engine.acquire(file.absolutePath, nCtx, setting.nThreads, gpu.layers)

        // GPU 装载失败要能退回 CPU，而不是把「本地推理」整个打死。
        //
        // 这条兜底不是防御性编程，是场景决定的：Vulkan 路线在真机上有两类失败
        // （驱动拒绝分配设备内存、模型层卸载时 vk 报错），两者都表现为 nativeInit 返回 0。
        // 而用户点开 GPU 的动机是「想更快」，结果却「一个字都出不来」——这个代价太大了。
        // 退回 CPU 一定还能用，只是慢；代价是必须把原因写进日志，否则又变成一次静默降级。
        if (handle == 0L && gpu.layers > 0) {
            AgentLogger.e(
                "LocalLlama",
                "GPU 装载失败（n_gpu_layers=${gpu.layers}），退回纯 CPU 重试一次；" +
                    "原因见上一行 llama-jni 记录。若反复出现，请在「推理参数 › GPU 加速」里选「关闭」",
            )
            // 回退也要守安全模式：GPU 崩了说明余量已经不多，再用满 ctx 只会换个地方崩。
            val cpuCtx = LocalDeviceRules.effectiveCtx(setting.nCtx, 0, setting.safeMode)
            handle = engine.acquire(file.absolutePath, cpuCtx, setting.nThreads, 0)
        }
        if (handle == 0L) {
            throw LocalInferenceException("本地模型加载失败：${file.name}（详见「运行日志」里的 llama-jni 记录）")
        }
    }

    /**
     * 这台机器这一轮该不该把层卸载到 GPU。
     *
     * 两个前提缺一不可，而且它们的失效方式完全不同，所以必须分开判：
     *  - **装没装**（[LlamaJni.gpuBackendAvailable]）：编译期事实，整个安装包统一；
     *  - **能不能跑**（[LocalDeviceRules.resolveGpu]）：按设备判，同一份包装到不同手机上结果不同。
     *
     * 拿不到画像时按 CPU 走并写明原因——「没探测到」不等于「支持」。
     */
    private fun resolveGpuPlan(): LocalDeviceRules.GpuPlan {
        // 之前在本进程里被 GPU 后端炸过一次：直接判 CPU。
        //
        // 这条必须排在设备画像之前——画像只能说「这台机器理论上有 Adreno 740」，
        // 它不知道驱动刚在 createComputePipeline 上返回过 VK_ERROR_UNKNOWN。
        // 用户把「自动」理解成「安全」是合理的，所以这里不赌第二次。
        gpuFaultDisabled?.let { why ->
            val line = "GPU 未启用：上次生成时 GPU 后端抛出异常（$why），本次进程改用 CPU"
            if (line != lastGpuLogLine) {
                lastGpuLogLine = line
                AgentLogger.w("LocalLlama", line)
            }
            return LocalDeviceRules.GpuPlan(0, "上次 GPU 后端抛出异常：$why")
        }
        val profile = AndroidDeviceProbe.cachedProfile
            ?: return LocalDeviceRules.GpuPlan(0, "设备画像尚未采集完成，本轮先用 CPU")
        val advice = LocalDeviceRules.advise(profile)
        val plan = LocalDeviceRules.resolveGpu(advice, LlamaJni.gpuBackendAvailable, setting.gpuMode)
        // 已知驱动缺陷先拦一道：Adreno 上 Q4_K 的 compute pipeline 建不出来，开了必炸。
        //
        // **「强制开启」也拦。** 早先给 FORCE 留了口子（「用户明知风险」），但真机证据
        // 说明那不是风险而是必然：2026-10-29 用户在「自动 / 强制开启 / 关闭」三档里
        // 各试一次，强制开启那轮 100% 停在 createComputePipeline。
        // 留这个口子的唯一效果是「多一轮 7 秒加载 + 一条报错」，不产生任何信息量。
        // 想验证 GPU 是否可用，正确做法是换成 Q4_0 / Q8_0 再开——那才是变量实验。
        val risk = if (plan.usingGpu) {
            LocalDeviceRules.adrenoQ4kRisk(setting.activeModel, advice.gpuModel)
        } else {
            null
        }
        val finalPlan = if (risk != null) LocalDeviceRules.GpuPlan(0, risk) else plan
        // 每轮对话都会走这里，但结论几乎不变。只记「变化」——否则 Agent 跑十轮就刷十行
        // 同样的「GPU 未启用」，真正有用的那行反而被淹掉。
        val line = if (finalPlan.usingGpu) {
            "GPU 卸载：${finalPlan.layers} 层 → ${advice.gpuModel ?: advice.gpu.label}" +
                "（Vulkan ${advice.vulkanVersion ?: "?"}）"
        } else {
            // 这条日志是「用户以为在用 GPU」的唯一解药：把「为什么没用」写进运行日志。
            "GPU 未启用：${finalPlan.reason}"
        }
        if (line != lastGpuLogLine) {
            lastGpuLogLine = line
            AgentLogger.i("LocalLlama", line)
        }
        return finalPlan
    }


    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = channelFlow {
        ensureModelLoaded()
        val prompt = buildPrompt(messages)
        AgentLogger.llmRequest(currentModelLabel(), messages.size, tools.size)
        val startedAt = System.currentTimeMillis()
        val meter = GenMeter()
        val acc = StringBuilder()
        // 原生回调在生成线程逐 token 触发，经 channel 中转给 Flow 收集方；
        // 协程取消（离开对话页等）时 invokeOnClose 通知 native 停止，线程自然收尾。
        val thread = Thread({
            try {
                engine.generate(prompt, setting.maxTokens) { piece ->
                    meter.onPiece(piece)
                    acc.append(piece)
                    trySend(piece)   // 通道满/已关时静默丢弃：宁可丢 token 不可卡死 native
                }
            } catch (t: Throwable) {
                AgentLogger.e("LocalLlama", "流式生成异常", t)
            } finally {
                close()
            }
        }, "local-llama-generate")
        thread.start()
        invokeOnClose { engine.stop() }
        thread.join()
        if (acc.isEmpty()) {
            val diag = nativeDiagnostics()
            val fault = engine.takeGpuFault()
            if (fault.isNotEmpty()) {
                LocalLlamaEngine.disableGpu(fault)
                AgentLogger.e("LocalLlama", "GPU 后端在生成时抛出异常：$fault")
                AgentLogger.w("LocalLlama", "本次进程内已关闭 GPU 加速，下一条消息将改用 CPU（无需重启）")
                throw LocalInferenceException(
                    "GPU 加速在这台机器上用不了：驱动创建计算管线失败（$fault）。" +
                        "已自动改用 CPU，请再发一次。"
                )
            }
            AgentLogger.e("LocalLlama", "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag")
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.llmResponse(
            currentModelLabel(),
            true,
            System.currentTimeMillis() - startedAt,
            "流式生成结束 ${meter.summary()}"
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 流式对话（T02）：把 native 的 token 级回调做成逐字上屏。
     *
     * 与云端 Provider 的最大差异在工具调用：本地用的是「整条回复就是一个 JSON」的
     * 提示词协议，所以首字符判定为 `{` 时**先憋着不吐**，等生成结束、解析出
     * [ToolCall] 再整条 commit。否则用户会先看一屏 `{"tool":"shell_run","arg…`
     * 乱码，再被工具卡替代 —— 比不流式更难看。
     *
     * 取消语义同 [streamText]：协程取消 → invokeOnClose → native 停止。
     */
    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> = channelFlow {
        // 基线要在加载**之前**取：首次对话的「nativeInit 成功 / 冷启动预热」属于本轮，
        // 用户才能在摘要里看到那十几秒花在哪。库还没加载时返回 0，正好等于「全都要」。
        val roundStartSeq = rememberLogSeq()
        ensureModelLoaded()
        val eff = effectivePrompt(messages, tools)
        val prompt = buildPrompt(eff.messages)
        AgentLogger.llmRequest(currentModelLabel(), eff.messages.size, eff.tools.size)
        val startedAt = System.currentTimeMillis()
        AgentLogger.i(
            "LocalLlama",
            "开始生成：${if (setting.agentMode) "Agent 模式" else "轻聊模式"}，" +
                "prompt ${prompt.length} 字符，上限 ${setting.maxTokens} token，线程 ${setting.nThreads}"
        )

        val acc = StringBuilder()
        var decided = false
        var looksLikeToolCall = false
        val firstPieceAt = java.util.concurrent.atomic.AtomicLong(0L)
        val meter = GenMeter()

        val thread = Thread({
            try {
                engine.generate(prompt, setting.maxTokens) { piece ->
                    meter.onPiece(piece)
                    firstPieceAt.compareAndSet(0L, System.currentTimeMillis())
                    acc.append(piece)
                    if (!decided && acc.isNotBlank()) {
                        decided = true
                        // 只认 `{` 是不够的：模型可能套 ```tool 围栏，或用训练时的
                        // <tool_call> 标签。这些一旦当普通文本上屏，用户先看到的是一屏
                        // 记号，等解析器认出调用后那段字又会被工具卡片顶掉，视觉上像出了 bug。
                        val head = acc.trimStart()
                        looksLikeToolCall = head.startsWith("{") ||
                            head.startsWith("```") ||
                            head.startsWith("<tool_call>", ignoreCase = true) ||
                            head.startsWith("<|tool_call>", ignoreCase = true) ||
                            head.startsWith("tool_call", ignoreCase = true)
                    }
                    if (decided && !looksLikeToolCall) {
                        trySend(ChatStreamEvent.TextDelta(piece))
                    } else if (looksLikeToolCall && acc.length >= TOOL_JSON_HOLD_MAX_CHARS) {
                        // 工具调用原本要「整条 JSON 憋到结束再解析」。问题是这个上限由
                        // 模型决定：它会慢吞吞吐几百 token 的 JSON，期间界面一个字都没有。
                        // 憋到 400 字符还看不出结束的，就当普通文本放行——宁可视觉上出现
                        // 半截 JSON，也不能让用户对着空白等几分钟。
                        looksLikeToolCall = false
                        trySend(ChatStreamEvent.TextDelta(acc.toString()))
                    }
                }
            } catch (t: Throwable) {
                AgentLogger.e("LocalLlama", "流式生成异常", t)
            }
        }, "local-llama-stream")
        thread.start()
        invokeOnClose { engine.stop() }

        // 生成线程是阻塞的，主协程不能只 join() 干等：那样 native 的进度全被关在
        // 环形缓冲里，用户中途停止后日志一片空白（真机 500 秒无输出就是这么来的）。
        // 这里改成「边等边把 native 新日志转进运行日志」，并提供卡死看门狗。
        val tap = NativeProgressTap(roundStartSeq)
        var stalled = false
        var stalledBudget = 0L
        var cancelled = false
        var lastActivity = startedAt
        while (thread.isAlive) {
            // join(timeout) 不是挂起点：协程被取消时这里不会自己抛异常，
            // 必须显式收手，否则「离开对话页」后还会白等一个轮询周期。
            if (!isActive) {
                cancelled = true
                engine.stop()
                thread.join(3000)
                break
            }
            thread.join(POLL_INTERVAL_MS)
            if (tap.drain() > 0) lastActivity = System.currentTimeMillis()
            val idleMs = System.currentTimeMillis() - lastActivity
            val noFirstToken = firstPieceAt.get() == 0L
            val budget = if (noFirstToken) firstTokenBudgetMs(tap.warmupMs) else GEN_STALL_TIMEOUT_MS
            if (idleMs > budget) {
                stalled = true
                stalledBudget = budget
                AgentLogger.e(
                    "LocalLlama",
                    "本地推理 ${idleMs / 1000}s 没有任何进展（未出首字=$noFirstToken，" +
                        "预热 ${tap.warmupMs}ms，prefill 已算 ${tap.prefillDone}），中止本回合。" +
                        "native 诊断：\n${nativeDiagnostics()}"
                )
                engine.stop()
                thread.join(3000)
                break
            }
        }
        tap.drain()

        if (stalled) {
            val tokens = Regex("→ (\\d+) token").find(thisDiag(roundStartSeq))
                ?.groupValues?.get(1)
            // 预热耗时是「这台设备跑不跑得动 3B」最直接的证据：1 个 token 就要好几秒，
            // 换更长的 prompt 不可能变好，只能换模型；反之则多半是这一版的引擎问题，
            // 让用户回传日志比让他换模型更有用。
            val engineTooSlow = tap.warmupMs > WARMUP_SLOW_HINT_MS
            throw LocalInferenceException(
                buildString {
                    append("本地模型 ${stalledBudget / 1000} 秒没有任何进展，已中止")
                    tokens?.let { append("（这一轮 prompt 有 $it token）") }
                    append("。")
                    if (engineTooSlow) {
                        append("引擎光是算 1 个 token 就要 ${tap.warmupMs} ms，这台设备跑 3B 太吃力，")
                        append("到「模型配置 → 本地模型」换 qwen2.5-0.5b 会顺畅得多。")
                    } else {
                        append("详情见「设置 → 数据 → 运行日志」的 LocalLlama 条目")
                        append("（里面已有预热耗时与 prefill 进度，能直接定位卡在哪一步）。")
                    }
                }
            )
        }
        // 用户主动停止不算失败：prefill 阶段被打断时一个字都还没有，
        // 按「空文本」去抛异常会让「我点了停止」变成一条红色报错。
        if (cancelled && acc.isEmpty()) {
            AgentLogger.i("LocalLlama", "用户停止（首字前），本回合不产出内容")
            return@channelFlow
        }

        val text = acc.toString()
        // 空文本 = 用户侧「发了消息毫无反应」。这不可能是「模型说完了」：
        // 结束符不该在一句话都没吐的时候命中。把 native 现场搬进日志并明确失败，
        // 否则这个回合会静默落一条空助手消息，看起来就像卡住/重复上一句。
        if (text.isEmpty()) {
            val diag = nativeDiagnostics()
            // GPU 后端自己炸了：Vulkan 驱动创建计算管线时抛异常，native 侧接住后
            // 本轮一个字都算不出来。流式这里**不重试**——重跑要重开整条 Flow，
            // 用户可能已经看到半截字，重来一遍反而更乱。
            // 钉死 CPU 即可：下一条消息直接跑 CPU，代价只有这一轮。
            val fault = engine.takeGpuFault()
            if (fault.isNotEmpty()) {
                LocalLlamaEngine.disableGpu(fault)
                AgentLogger.e("LocalLlama", "GPU 后端在生成时抛出异常：$fault")
                AgentLogger.w("LocalLlama", "本次进程内已关闭 GPU 加速，下一条消息将改用 CPU（无需重启）")
                throw LocalInferenceException(
                    "GPU 加速在这台机器上用不了：驱动创建计算管线失败（$fault）。" +
                        "已自动改用 CPU，请把这句话再发一次。"
                )
            }
            AgentLogger.e(
                "LocalLlama",
                "本地生成返回空文本（prompt ${prompt.length} 字符）。native 诊断：\n$diag"
            )
            throw LocalInferenceException(localFailureReason(diag))
        }
        AgentLogger.i("LocalLlama", "native 摘要｜${digest(nativeDiagnostics(), roundStartSeq)}")

        var finalText = text
        var message = parseAssistant(finalText, eff.tools)
        // 判定「想调工具」却没解析出 ToolCall：静默重试一次。
        //
        // 3B 最常见的失败不是不会调，而是 JSON 差一个引号、工具名少个下划线、写一半被
        // 长度上限截断。直接把这段乱码当回答吐给用户，等于这一轮白跑。重试的成本很低：
        // 系统提示与历史一个字节都没变（KV 前缀命中），只多算「上一条输出 + 一句纠正」
        // 这几十个 token；而成功的收益是整轮任务能继续。
        //
        // 另一种做法是把纠正提示塞进对话历史、由主循环判断是否重试；这里把它收敛在客户端
        // 内部——AgentLoop 看不到这次失败，也就不必为本地模型单独改一套控制流。
        if (looksLikeToolCall && message.toolCalls.isEmpty()) {
            val reason = LocalToolCallParser.parse(text, eff.tools.map { it.name }.toSet()).error
            AgentLogger.i("LocalLlama", "工具调用解析失败（${reason.orEmpty()}），静默重试一次")
            val retryPrompt = buildPrompt(
                eff.messages + AssistantMessage(finalText.trim()) + UserMessage(TOOL_RETRY_HINT)
            )
            val again = generateBounded(retryPrompt, TOOL_RETRY_BUDGET_MS)
            if (again.isNotBlank()) {
                val m2 = parseAssistant(again, eff.tools)
                // 重试后仍然没调工具，但给出了正常文字：这段比原来的半截 JSON 可读得多
                if (m2.toolCalls.isNotEmpty() || m2.content.isNotBlank()) {
                    finalText = again
                    message = m2
                }
            }
        }
        // 只要这一轮被「憋着没上屏」（判定像工具调用），结束时就补发一次——不论是原输出
        // 还是重试后的文字，都不该让用户对着空白。
        if (looksLikeToolCall && message.toolCalls.isEmpty() && message.content.isNotEmpty()) {
            send(ChatStreamEvent.TextDelta(message.content))
        }
        AgentLogger.llmResponse(
            currentModelLabel(),
            finalText.isNotEmpty(),
            System.currentTimeMillis() - startedAt,
            "流式生成结束（${if (message.toolCalls.isEmpty()) "文本" else "工具 ${message.toolCalls.size} 个"}）${meter.summary()}"
        )
        send(ChatStreamEvent.Completed(message))
    }.flowOn(Dispatchers.IO)

    /**
     * 带硬超时的非流式生成——只给「静默重试」用。
     *
     * 为什么不复用上面那段流式循环：那段同时承担「边生成边把 native 日志搬到运行日志」
     * 「看门狗判卡死」「逐 token 上屏」三件事，重试时前两件没意义（同一轮、只补几十个
     * token），硬套会让控制流变得难以论证。这里只保证一件事：**不会因为重试把用户卡住**
     * ——到点就 `engine.stop()`，拿已经生成的部分。
     */
    private fun generateBounded(prompt: String, timeoutMs: Long): String {
        val out = StringBuilder()
        val worker = Thread({
            runCatching { engine.generate(prompt, setting.maxTokens) { piece -> out.append(piece) } }
                .onFailure { AgentLogger.w("LocalLlama", "重试生成异常：${it.message}") }
        }, "local-llama-retry")
        worker.start()
        // 单调时钟：这里是 join 轮询，墙钟回拨会导致 worker 永远等不到 deadline，
        // 而外层没有 withTimeout 兜底（注释里已说明为什么不用它）——真会卡住。
        val deadline = Monotonic.nowMs() + timeoutMs
        while (worker.isAlive && Monotonic.nowMs() < deadline) worker.join(POLL_INTERVAL_MS)
        if (worker.isAlive) {
            AgentLogger.w("LocalLlama", "工具调用重试超时（${timeoutMs / 1000}s），放弃")
            engine.stop()
            worker.join(3000)
        }
        return out.toString()
    }

    /** 读 native 诊断（库未加载时返回空串，不能让诊断本身把流程搞崩）。 */
    private fun nativeDiagnostics(): String = runCatching {
        if (LlamaJni.available) LlamaJni.nativeDiagnostics() else ""
    }.getOrDefault("")

    /** 取当前诊断序号（作为「本轮 native 日志」的起点）；库不可用时返回 0。 */
    private fun rememberLogSeq(): Long = runCatching {
        if (LlamaJni.available) LlamaJni.nativeLogSeq() else 0L
    }.getOrDefault(0L)

    /** native 诊断行：`#<seq>|<正文>`。seq 为 -1 表示这行没有序号（兜底保留）。 */
    private data class DiagLine(val seq: Long, val text: String)

    private fun parseDiag(raw: String): List<DiagLine> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (line.startsWith("#")) {
                val bar = line.indexOf('|')
                val seq = if (bar > 1) line.substring(1, bar).toLongOrNull() else null
                if (seq != null) DiagLine(seq, line.substring(bar + 1)) else DiagLine(-1L, line)
            } else {
                DiagLine(-1L, line)
            }
        }
        .toList()

    /** 只要 seq > [sinceSeq] 的行（= 本轮 native 日志），拼回纯文本。 */
    private fun thisDiag(sinceSeq: Long): String =
        parseDiag(nativeDiagnostics()).filter { it.seq < 0 || it.seq > sinceSeq }
            .joinToString("\n") { it.text }

    /** 只挑能判断「卡在哪一步」的行，避免把 80 行全塞进运行日志。 */
    private fun digest(diag: String, sinceSeq: Long = 0L): String = parseDiag(diag)
        .filter { it.seq < 0 || it.seq > sinceSeq }
        .map { it.text }
        .filter { line ->
            listOf(
                "nativeInit 成功", "冷启动预热", "prompt 长度", "prefill 开始", "prefill 完成",
                "预热", "生成完成", "命中结束符", "生成为空", "tokenize 失败",
                // W-97：绑核是**每台机器只算一次**的结论，它不进摘要就等于没有——
                // 真机 18:38 那几轮里 prefill 已经显示「线程 5」，可投票的 4 行数值和
                // 结论行一条都看不见，只能靠线程数反推「投票跑过了」。
                "绑核", "CPU 落点"
            ).any { keyword -> line.contains(keyword) }
        }
        .joinToString(" ｜ ")

    /**
     * 把 native 环形缓冲里**本轮新增**的日志转进 App 运行日志。
     *
     * 存在的理由：native 的进度行（prefill 进度 / 生成进度）只在结束时才被上层读到，
     * 用户中途点停止就什么都不剩——真机上表现为「等了 8 分钟、日志里一片空白」。
     * 这里每一小段时间捞一次增量，日志里就能看到「算到第几个 token、多快」。
     *
     * 「本轮」由 [baseSeq] 界定，而不是「见过没见过的文本」：缓冲在进程级累积，
     * 用文本去重的话，每轮开始都会把上一轮的几十行全部重打一遍——真机上因此出现过
     * 「第二轮复读上一轮 13436 ms 预热」的假象，还把看门狗的 warmupMs 污染了。
     *
     * 只转发进度/失败行：native 一次生成会打几十行，全转会把日志淹掉。
     */
    private inner class NativeProgressTap(baseSeq: Long) {
        /** 已消费到的最大序号。缓冲滚动后老行消失，靠它保证不重复也不倒退。 */
        private var lastSeq = baseSeq

        /** 没有序号的行（理论上不该有）退回文本去重，避免无限重复。 */
        private val seenText = LinkedHashSet<String>()

        /** 预热 decode 的 1 token 稳态耗时（-1 = 还没出现在日志里）。 */
        var warmupMs: Long = -1L
            private set

        /** 最近一次 prefill 进度里的「已算 token 数」，用于超时文案。 */
        var prefillDone: Int = 0
            private set

        /** @return 本次是否有新行（用于判断「还在动」） */
        fun drain(): Int {
            val diag = runCatching { nativeDiagnostics() }.getOrDefault("")
            if (diag.isBlank()) return 0
            var fresh = 0
            parseDiag(diag).forEach { (seq, t) ->
                if (seq >= 0) {
                    if (seq <= lastSeq) return@forEach
                    lastSeq = seq
                } else if (!seenText.add(t)) {
                    return@forEach
                }
                fresh++
                // 预热耗时决定看门狗给多少预算：引擎 1 token 只要几百毫秒，
                // 那 158 token 的 prefill 拖过 45 秒就一定是卡住而不是慢。
                //
                // 取最后出现的那一条：日志顺序是「冷启动预热 → 预热 decode → 预热复测
                // → 预热 decode 重试」，越靠后越接近稳态。冷启动那条本来就在基线之前，
                // 正常情况下根本不会进来。
                Regex("预热[^：]*：1 token / (\\d+) ms").find(t)
                    ?.let { warmupMs = it.groupValues[1].toLong() }
                Regex("prefill 进度：(\\d+) /").find(t)
                    ?.let { prefillDone = it.groupValues[1].toInt() }
                if (t.contains("进度") || t.contains("失败") ||
                    t.contains("预热") || t.contains("prefill 开始") ||
                    t.contains("prefill 完成")
                ) {
                    AgentLogger.i("LocalLlama", t)
                }
            }
            if (seenText.size > 600) seenText.clear()
            return fresh
        }
    }

    /**
     * 把 native 诊断翻译成一句用户能看懂、且能直接转述给开发者的话。
     *
     * 分支顺序按「从日志里能确定的最具体原因」排，取不到就退回让用户导出日志。
     */
    private fun localFailureReason(diag: String): String = when {
        diag.contains("tokenize 失败") ->
            "本地模型读不懂这段输入（分词失败），已记录到运行日志"
        diag.contains("llama_decode 失败") ->
            "本地推理的计算核拒绝执行（decode 失败），多半是该模型与当前 native 库不兼容，已记录到运行日志"
        diag.contains("prompt 异常地短") ->
            "对话模板没有渲染出内容（prompt 过短），模型无话可说，已记录到运行日志"
        diag.contains("生成为空") ->
            "模型一个字都没生成（首个 token 就是结束符），已记录到运行日志"
        else ->
            "本地模型没有返回内容，详情见「设置 → 数据 → 运行日志」的 LocalLlama 条目"
    }

    // —— 模型元信息 ————————————————————————————————————————————————
    override suspend fun listModels(): List<ModelInfo> =
        modelStore.catalog().map { ModelInfo(it.fileName, it.displayName, it.approxBytes.toInt()) }

    override suspend fun testPing(): PingResult {
        if (!LlamaJni.ensureLoaded()) {
            return PingResult(0, LlamaJni.unavailableReason ?: "native 库不可用（仅 arm64-v8a）")
        }
        val name = setting.activeModel
        if (name.isBlank()) {
            return PingResult(0, "尚未选择本地模型：请先在「本地模型」页签下载并选用")
        }
        val file = modelStore.modelFile(name)
        if (!file.isFile) {
            return PingResult(0, "模型文件缺失：${name}（可能已被删除）")
        }
        if (!modelStore.isValidGguf(file)) {
            return PingResult(0, "文件不是有效的 GGUF：${name}（下载可能不完整）")
        }
        if (engine.isLoaded) {
            return PingResult(200, "模型已加载：${name}，可直接对话")
        }
        return PingResult(200, "文件就绪：${name}（${file.length() / 1024 / 1024} MB），首次对话时加载")
    }

    // —— prompt 构建 ————————————————————————————————————————————————

    /** 一轮对话真正要喂给模型的材料（消息 + 工具）。 */
    private data class EffectivePrompt(val messages: List<ChatMessage>, val tools: List<AgentTool>)

    /**
     * 决定这一轮喂什么给模型。
     *
     * 真机对照（同一台手机、同一个 3B Q4_K_M）：
     *   - 只带「你好」（7 token）：首字 4.8 秒，生成 4.8 tok/s；
     *   - 带工具协议 + 系统提示 + 设备上下文（1086 token）：180 秒连 prefill 第一块都没算完。
     *
     * 手机 CPU 算力固定，prompt 长 150 倍就是慢 150 倍——所以默认走轻聊档，
     * Agent 模式由用户显式打开。
     */
    private fun effectivePrompt(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): EffectivePrompt =
        if (setting.agentMode) EffectivePrompt(compactAgentMessages(messages, tools), tools)
        else EffectivePrompt(lightMessages(messages), emptyList())

    /**
     * Agent 模式：把「云端用的长系统提示」整条换成本地精简版（[LocalAgentPrompt]）。
     *
     * 必须整条替换而不是追加：上游拼进来的系统提示包含
     * `DEFAULT_SYSTEM_PROMPT`（600 中文字，含一整段界面操作协议）+ 技能 XML 清单
     * + 记忆摘要 + `MEMORY_USAGE_PROMPT`，实测 1650 字符 ≈ 1086 token。3B 在手机上
     * 按 16 tok/s 算，光把这段读进去就要 68 秒——用户看到的就是「发出去没反应」。
     *
     * 精简版只保留 3B 真正用得上的东西：身份一行、规则四条、调用格式一个例子、
     * 每个工具一行。技能清单只留名字（描述等它调用 `use_skill` 时再给）。
     * 目标是 250 token 上下，把 prefill 从 68 秒压到 15 秒以内。
     *
     * 云端 provider 完全不受影响——这段逻辑只活在本类。
     */
    private fun compactAgentMessages(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): List<ChatMessage> {
        val compact = LocalAgentPrompt.build(tools, skillNames(tools))
        // 这行是给「到底还慢不慢」留的证据：上一版 Agent 模式整段 prompt 是 1086 token
        // （约 1650 字符），瘦身后应当落在 1000 字符上下。用户回传日志时一眼能对。
        AgentLogger.i("LocalLlama", "Agent 精简提示：${compact.length} 字符 / 工具 ${tools.size} 个")
        val rest = messages.filterNot { it is SystemMessage }.map { m ->
            // 「用户当前请求：」前面那段设备上下文（最近通知、前台应用…）是给云端看的：
            // 本地模型读了既算不动，又每轮都变——一变就把 KV 前缀打断，整段历史重算。
            if (m is UserMessage) UserMessage(shortenUser(m.content), m.images) else m
        }
        // Gemma-2 这类模板没有 system 通道，系统提示只能并进第一条用户消息。
        // 不处理的话这类模型会一条规则都看不到，表现成「完全不听话」。
        if (!supportsSystemPrompt()) {
            val i = rest.indexOfFirst { it is UserMessage }
            if (i >= 0) {
                val u = rest[i] as UserMessage
                return rest.toMutableList().also {
                    it[i] = UserMessage("$compact\n\n${u.content}", u.images)
                }
            }
            return listOf(UserMessage(compact)) + rest
        }
        return listOf(SystemMessage(compact)) + rest
    }

    /** 当前模型是否支持 system 角色（Gemma-2 为 false，见 [GgufModelStore] 目录）。 */
    private fun supportsSystemPrompt(): Boolean = modelStore.catalog()
        .firstOrNull { it.fileName == setting.activeModel }
        ?.supportsSystemPrompt ?: true

    /**
     * 从 `use_skill` 工具自带的系统提示里抠出技能名。
     *
     * 上游把技能清单以 XML（`<skill><name>…</name><description>…`）塞进工具的
     * [AgentTool.systemPrompt]，一个技能连同描述约 70 token。本地模型只需要知道
     * 「有哪几个名字可以调」，名字之外的信息等它真的 `use_skill` 时再给。
     *
     * 不从构造函数注入 provider 是有意的：这条链路是纯文本解析、失败就返回空表，
     * 不会因为技能模块重构而让本地对话崩掉。
     */
    private fun skillNames(tools: List<AgentTool>): List<String> {
        val raw = tools.firstOrNull { it.name == "use_skill" }?.systemPrompt ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return Regex("<name>(.*?)</name>").findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * 轻聊档的消息裁剪。
     *
     * 1. **系统提示整条换成 [LocalChatPrompt]**，不再截云端提示的前几行。
     *    云端那份写的是「你可以调用工具、可以操作界面」，而轻聊档没有工具——
     *    真机 2026-10-29 15:33 模型据此答出「可以控制智能家居，你有 Alexa 吗」，
     *    连用户点破「你连不了网吧」之后还在继续列并不存在的能力。
     * 2. 用户消息只留「用户当前请求：」之后的部分——前面那段设备上下文是给云端
     *    大模型看的，本地小模型读了只会多算几百 token，还容易答跑题；
     * 3. 历史只留最近 [LIGHT_HISTORY_MESSAGES] 条，控制增量 prefill 的基数。
     */
    private fun lightMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val rest = messages.filterNot { it is SystemMessage }
            .takeLast(LIGHT_HISTORY_MESSAGES)
            .map { m -> if (m is UserMessage) UserMessage(shortenUser(m.content), m.images) else m }
        val sys = LocalChatPrompt.TEXT
        // Gemma-2 这类模板没有 system 通道，只能并进第一条用户消息（否则一条规则都看不到）。
        if (!supportsSystemPrompt()) {
            val i = rest.indexOfFirst { it is UserMessage }
            if (i >= 0) {
                val u = rest[i] as UserMessage
                return rest.toMutableList().also { it[i] = UserMessage("$sys\n\n${u.content}", u.images) }
            }
            return listOf(UserMessage(sys)) + rest
        }
        return listOf(SystemMessage(sys)) + rest
    }

    private fun shortenUser(text: String): String {
        val marker = "用户当前请求："
        return if (text.contains(marker)) text.substringAfter(marker).trim() else text
    }

    /**
     * 把 ChatMessage 列表渲染成模型 prompt。
     *
     * 工具说明**不在这里拼**：Agent 模式由 [LocalAgentPrompt] 一次性写进系统提示
     * （英文、每工具一行），轻聊档根本没有工具表。原来这里还有一份「工具调用模式」的
     * 中文协议——轻聊档永远不生效（工具为空），Agent 模式又和精简提示重复，等于让 3B
     * 同时读两套口径。已随 W-79 删除。
     */
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val roles = ArrayList<String>(messages.size)
        val contents = ArrayList<String>(messages.size)
        messages.forEach { msg ->
            when (msg) {
                is SystemMessage -> { roles.add("system"); contents.add(msg.content) }
                is UserMessage -> { roles.add("user"); contents.add(msg.content) }
                is AssistantMessage -> {
                    roles.add("assistant")
                    // 带工具调用的历史回合：content 里存的就是当时输出的 JSON 原文，直接回放
                    contents.add(msg.content)
                }
                is ToolMessage -> {
                    // 模板角色名用 "tool"：Qwen/Llama 3.1+ 原生支持；其他模板会原样渲染角色名，
                    // 顶多多一行字，不会破坏对话结构。
                    roles.add("tool")
                    contents.add("工具 ${msg.name} 的执行结果：\n${msg.content}")
                }
            }
        }

        var prompt = engine.chatTemplate(roles.toTypedArray(), contents.toTypedArray())
        if (prompt.isBlank()) {
            // 引擎未加载/模板失败：chatTemplate 依赖已加载实例，理论上 chat() 里已 acquire 过。
            // 兜底拼装保证不把空 prompt 发给 native。
            AgentLogger.w("LocalLlama", "对话模板为空，使用 ChatML 兜底拼装")
            prompt = buildString {
                messages.forEachIndexed { i, msg ->
                    val role = roles.getOrElse(i) { "user" }
                    append("$role\n${contents.getOrElse(i) { "" }}\n")
                }
            }
        }
        return prompt
    }

    // —— 输出解析 ————————————————————————————————————————————————

    /**
     * 解析模型输出为 [AssistantMessage]：
     * 先按宽容解析器认工具调用（工具名必须存在于本次可用列表，防误判）；
     * 认不出就整体当普通文本。
     *
     * 3B 的输出几乎不会一次写对 JSON（尾逗号、围栏、`<tool_call>` 标签、工具名拼错、
     * 被长度上限截断……），这些情况**全都是想调工具**。判死当文本，用户看到的就是
     * 「模型回了一屏乱码」，比不调工具更糟。判定细节见 [LocalToolCallParser]。
     */
    private fun parseAssistant(text: String, tools: List<AgentTool>): AssistantMessage {
        if (text.isBlank()) return AssistantMessage("")
        if (tools.isEmpty()) return AssistantMessage(text.trim())
        val parsed = LocalToolCallParser.parse(text, tools.map { it.name }.toSet())
        val name = parsed.name ?: return AssistantMessage(text.trim())
        val call = ToolCall("local_${UUID.randomUUID().toString().take(8)}", name, parsed.args)
        return AssistantMessage(text.trim(), listOf(call))
    }

    private fun currentModelLabel(): String =
        setting.activeModel.ifBlank { config.model.ifBlank { "local-llama" } }

    /**
     * 一次本地生成的计时账：首字延迟（TTFT）+ 字符吞吐。
     *
     * 存在的理由：真机自测时用户手边未必有电脑，`adb logcat -s llama-jni` 里的
     * prefill 耗时、tok/s 全在 native 侧，进不了 App 内「运行日志」页。而「回得快
     * 不快」在体感上只由两个数决定 —— 第一字多久出来、之后每秒出多少字。这里在
     * Kotlin 侧独立计一遍，写进 LLM 响应日志，手机上翻日志就能对比改前改后。
     */
    private class GenMeter {
        private val startAt = System.currentTimeMillis()
        private var firstAt = 0L
        private var chars = 0

        /** 每收到一个 token 片段调用一次（native 线程，只做整数累加）。 */
        fun onPiece(piece: String) {
            if (firstAt == 0L) firstAt = System.currentTimeMillis()
            chars += piece.length
        }

        fun summary(): String {
            val total = System.currentTimeMillis() - startAt
            val ttft = if (firstAt == 0L) -1L else firstAt - startAt
            val cps = if (total > 0) chars * 1000.0 / total else 0.0
            val head = if (ttft < 0) "首字 —" else "首字 ${ttft}ms"
            return String.format(
                Locale.US, "%s / %d 字 / 共 %dms（%.1f 字每秒）", head, chars, total, cps
            )
        }
    }

    private companion object {
        /**
         * 上一次打过的 GPU 结论，用来去重（见 resolveGpuPlan）。
         *
         * **必须是进程级，不能是实例字段。** 这个 client 每轮对话会被建好几次
         * （AgentLoop 的 llmProvider 每次取都现建），实例字段等于每个实例都从 null 重新开始，
         * 去重形同虚设：真机 2026-10-29 16:08 一轮里同一句「GPU 未启用：模型是 Q4_K 量化…」
         * 打了三遍，把真正有用的行挤跑了。和 W-92 的 g_gpuDisabled 是同一个坑。
         */
        @Volatile
        private var lastGpuLogLine: String? = null

        /** 生成线程的轮询间隔：够细能看到进度，又不至于把 CPU 抢去读日志。 */
        const val POLL_INTERVAL_MS = 1500L

        /**
         * 首字预算：不是固定 180 秒，而是看 native 的「预热 decode」结果。
         *
         * 预热把 1 个 token 单独算一遍并计时，这个数字直接说明「引擎本身跑不跑得动」：
         *   - 1 token 只要几百毫秒 → 引擎正常，158 token 的 prefill 拖过 45 秒就是卡住，
         *     再等下去只是让用户干瞪屏幕；
         *   - 1 token 就要好几秒   → 慢在设备/权重布局，给足 150 秒仍可能白等，
         *     但至少不该在「它确实在算」的时候误杀；
         *   - 还没探到              → 首次加载阶段偏慢，给 90 秒。
         */
        fun firstTokenBudgetMs(warmupMs: Long): Long = when {
            warmupMs in 1..WARMUP_SLOW_HINT_MS -> 45_000L
            warmupMs > WARMUP_SLOW_HINT_MS -> 150_000L
            else -> 90_000L
        }

        /** 预热超过这个数（ms）就认定「引擎对这台设备太重」，文案改推小模型。 */
        const val WARMUP_SLOW_HINT_MS = 2_000L

        /**
         * 已经出字之后，两次出字之间的容忍上限。
         *
         * 生成阶段每 token 都要过一次全量权重，3B Q4 在手机上是 1~5 tok/s，
         * 90 秒没有新字已经远超正常抖动，只可能是原地循环或卡在采样。
         */
        const val GEN_STALL_TIMEOUT_MS = 90_000L

        /**
         * 工具调用 JSON 最多憋多少字符。
         *
         * 本地小模型输出 JSON 的速度是每秒几个 token，一条 300 字符的 JSON 要一分多钟，
         * 期间界面全空。超过这个量就当普通文本往屏幕上推。
         */
        const val TOOL_JSON_HOLD_MAX_CHARS = 400

        /**
         * 工具调用写坏后的纠正提示。
         *
         * 用英文而不是中文：这句话要占 prompt，而模型的输出语言由系统提示里的
         * "Answer in Chinese" 决定，纠正提示用哪种语言不影响输出语言。
         * 内容刻意只讲「错在哪、怎么改」，不复述格式——格式在系统提示里已经有一份，
         * 这里再说一遍只会让 3B 在两套措辞之间摇摆。
         */
        const val TOOL_RETRY_HINT =
            "That reply was not a readable tool call. Reply again: either ONE tool call in " +
                "the exact JSON format from the system prompt, or a plain-text answer."

        /**
         * 重试的硬上限。
         *
         * 重试只补算「上一条输出 + 一句提示」，正常几秒；给 45 秒是为了兜住「模型打算
         * 重新生成一整段 JSON」的情况。超过就放弃，把已经拿到的文字显示出来——
         * 再等下去就回到了「发出去几分钟没反应」的老问题。
         */
        const val TOOL_RETRY_BUDGET_MS = 45_000L

        /** 轻聊档保留的历史条数（4 条 = 最近两轮）。 */
        const val LIGHT_HISTORY_MESSAGES = 4
    }
}
