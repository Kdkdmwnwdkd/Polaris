import java.io.File

/**
 * W-46 harness：本批次六项修复的**源码级**回归断言。
 *
 * 为什么是源码扫描而不是行为测试：
 *  - 历史注入的「有没有把历史塞进请求」在纯 JVM 里看不到（要跑通整个 Android VM 链），
 *    但它是一个**结构约束**——`messages.addAll(priorMessages)` 与返回值 `drop(prefixCount)`
 *    两处必须同时存在，缺一个就会「历史重复落盘」或「历史根本没发」。
 *  - 明文 HTTP、超时、@Volatile 这类配置项，本质也是「源码里有没有这一行」。
 *
 * 断言随产品变更同步更新：改动上述实现时，先改这里再改代码（否则回归面直接失效）。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

fun main() {
    println("== W-46 harness：历史注入 / 明文端点 / 超时 / 并发 / 终端会话 ==")

    val chatVm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
    val loop = read("core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt")
    val manifest = read("app/src/main/AndroidManifest.xml")
    val nsc = read("app/src/main/res/xml/network_security_config.xml")
    val llmHttp = read("core/src/main/java/com/beichen/polaris/core/llm/LlmHttp.kt")
    val oa = read("core/src/main/java/com/beichen/polaris/core/LLMClient.kt")
    val claude = read("core/src/main/java/com/beichen/polaris/core/llm/ClaudeClient.kt")
    val gemini = read("core/src/main/java/com/beichen/polaris/core/llm/GeminiClient.kt")
    val pool = read("core/src/main/java/com/beichen/polaris/core/terminal/TerminalSessionPool.kt")
    val builtIn = read("app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt")

    check("H1 历史被摊平并随请求下发（send 路径）") {
        assertTrue("缺 buildPriorMessages 定义", "private fun buildPriorMessages(" in chatVm)
        // R1 起 priorMessages 多了一条「恢复提示」分支：
        //   val priorMessages = if (recoveryNote.isNullOrBlank()) buildPriorMessages(owner)
        //                       else buildPriorMessages(owner) + AssistantMessage(recoveryNote)
        // 行为没变（都取自 buildPriorMessages(owner)），只是不再是单行字面量。
        // 因此这里断言**语义**而非字面量：历史确实来自 buildPriorMessages 且传给了 loop。
        assertTrue("send 未取历史", "buildPriorMessages(owner)" in chatVm)
        assertTrue(
            "send 未把历史传给 loop",
            "loop.send(fullInput, images, priorMessages)" in chatVm
        )
    }

    check("H2 重新生成带历史但排除最后一轮（否则会复读旧答案）") {
        assertTrue(
            "regenerate 未排除最后一轮",
            "buildPriorMessages(owner, excludeLastTurn = true)" in chatVm
        )
    }

    check("H3 历史插在系统提示之后、当前输入之前") {
        // 只匹配到 buildSystemPrompt( 为止：具体参数会随实现调整
        // （exposure.tools → exposure0.visible 就变过一次），断言要验的是"顺序"不是"拼写"。
        val sysIdx = loop.indexOf("messages.add(SystemMessage(buildSystemPrompt(")
        val priorIdx = loop.indexOf("messages.addAll(priorMessages)")
        // 只认到 `UserMessage(` 为止，不看实参：接缝①接入后实参从 `userInput, images`
        // 变成了 `seamInput.text, seamInput.images`（可改写），改的是内容不是位置。
        // 断言的对象是「当前输入排在历史之后」这个顺序，绑实参只会让接缝一动就假红。
        val userIdx = loop.indexOf("messages.add(UserMessage(", priorIdx)
        assertTrue("三处顺序缺失", sysIdx >= 0 && priorIdx > sysIdx && userIdx > priorIdx)
    }

    check("H4 返回值剔除历史前缀（否则调用方会把历史重复落盘）") {
        assertTrue(
            "缺 turnResult()",
            "fun turnResult(): List<ChatMessage> = messages.drop(priorMessages.size)" in loop
        )
        // 所有 return 都必须走 turnResult()，不允许再出现裸 return messages
        assertTrue("仍有裸 return messages", !loop.contains("return messages"))
    }

    check("H5 契约与生产实现直连（适配层已移除）") {
        assertTrue("接口未扩参", "priorMessages: List<ChatMessage> = emptyList()" in
            read("core/src/main/java/com/beichen/polaris/core/agent/AgentLoop.kt"))
        // 生产链路必须直接构造 RealAgentLoop：曾经的薄适配层 `core.AgentLoop` 把 20 个参数
        // 原样转发，却因「两处默认值必须一致」靠人记，先后导致 maxSteps=16 截断任务、
        // providerRetry=2 让 429 第三档退避永不触发。删掉层比维护同步更可靠。
        assertTrue("ChatViewModel 未直连 RealAgentLoop", "private val loop = RealAgentLoop(" in chatVm)
        assertTrue("适配层又回来了", "class AgentLoop(" !in
            read("core/src/main/java/com/beichen/polaris/core/AgentLoop.kt"))
    }

    check("C1 明文端点：Manifest 引用了 networkSecurityConfig") {
        assertTrue("Manifest 未引用", "android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
        assertTrue("未放开 cleartext", "cleartextTrafficPermitted=\"true\"" in nsc)
    }

    check("T1 LLM 通道不再用 OkHttp 默认 10s 读超时") {
        // R-13 起超时是参数化的（build(connect, read, write, call)），不能再断言某个字面量调用；
        // 断言的是「预设值」与「调用点走了 build」，两者任一丢失都说明收口被绕开。
        assertTrue("LlmHttp 缺 readTimeout", ".readTimeout(read, TimeUnit.SECONDS)" in llmHttp)
        assertTrue("LlmHttp 缺参数化构造", "private fun build(connect: Long, read: Long" in llmHttp)
        assertTrue("llm 预设 read 不是 180s", "read = 180" in llmHttp)
        assertTrue("short 预设 read 不是 30s", "read = 30" in llmHttp)
        assertTrue("download 预设 read 不是 120s", "read = 120" in llmHttp)
        assertTrue("LlmHttp 缺 callTimeout=0", ".callTimeout(call, TimeUnit.SECONDS)" in llmHttp)
        assertTrue("llm 预设不再不限整通时长", "call = 0" in llmHttp)
        assertTrue("OpenAI 兼容通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in oa)
        assertTrue("Claude 通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in claude)
        assertTrue("Gemini 通道未切换", "private val http: OkHttpClient = LlmHttp.shared" in gemini)
        assertTrue("仍有裸 OkHttpClient()", "= OkHttpClient()" !in oa)
    }

    check("S1 http_request 不再谎称只读（POST 与 GET 不能并发）") {
        val section = builtIn.substring(builtIn.indexOf("class HttpRequestTool"))
        assertTrue("仍是 readOnly = true", "override val readOnly = false" in section)
    }

    check("S2 重试次数单一真源（429 第三档生效）") {
        // R10：次数真源从「RealAgentLoop 的构造参数默认值」搬到 RetryProfile。
        // 默认 -1 表示「跟随档位」，档位默认 3——仍然只有一处定义次数，
        // 只是那处现在住在档位里（35 家供应商可以各自覆盖，不用改循环）。
        assertTrue(
            "providerRetry 默认不是 -1（应跟随档位）",
            "private val providerRetry: Int = -1" in loop
        )
        val retry = read("core/src/main/java/com/beichen/polaris/core/llm/RetryProfile.kt")
        assertTrue("官方档 maxRetries 不是 3", "maxRetries = 3" in retry)
        assertTrue("循环没有跟随档位", "else profile.maxRetries" in loop)
    }

    check("V1 owner 会话 id 跨线程可见") {
        // P4-4：运行态搬进 GenerationRunController，@Volatile 也必须跟着搬——
        // 它守的是「写点在主线程、读点在 IO 线程」这件事，与文件位置无关。
        val generation = read("app/src/main/java/com/beichen/polaris/ui/GenerationRunController.kt")
        assertTrue(
            "owner 缺 @Volatile",
            "@Volatile\n    var owner: String? = null" in generation
        )
        assertTrue("VM 仍在直接读 owner", "generation.owner" in chatVm)
    }

    check("P1 终端输出边收边截（不再整段堆内存）") {
        assertTrue("缺 MAX_BUFFER_CHARS", "const val MAX_BUFFER_CHARS" in pool)
        assertTrue("收流处未截断", "if (builder.length > MAX_BUFFER_CHARS)" in pool)
        assertTrue("溢出未标注", "droppedChars" in pool)
    }

    check("P2 超时会话标脏，禁止静默复用") {
        assertTrue("缺 desynced 字段", "var desynced: Boolean = false" in pool)
        assertTrue("run 未拒绝脏会话", "code = \"session_desynced\"" in pool)
        assertTrue("超时未中断", "entry.backend.send(\"\\u0003\")" in pool)
    }

    println("\n== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
