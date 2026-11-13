package com.beichen.polaris

import com.beichen.polaris.automation.AutomationBridge
import com.beichen.polaris.automation.DeviceContextStore
import com.beichen.polaris.automation.adb.AdbCredentials
import com.beichen.polaris.automation.adb.AdbSession
import com.beichen.polaris.automation.shizuku.ShizukuShell
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.browser.BrowserHost
import com.beichen.polaris.pet.PetState
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.CompletionNotifier
import com.beichen.polaris.core.CrashLogger
import com.beichen.polaris.core.agent.AskUserBroker
import com.beichen.polaris.core.agent.PlanBroker
import com.beichen.polaris.core.build.BuildEngine
import com.beichen.polaris.core.llm.local.AndroidDeviceProbe
import com.beichen.polaris.core.llm.local.LlamaJni
import com.beichen.polaris.core.llm.local.LocalLlamaEngine
import com.beichen.polaris.core.telemetry.TraceSink
import com.beichen.polaris.core.time.Monotonic
import com.beichen.polaris.core.workspace.WorkspaceStore
import com.beichen.polaris.runtime.LocalLlmRuntime
import com.beichen.polaris.runtime.lease.LeaseRegistry
import com.beichen.polaris.terminal.TerminalJni
import com.beichen.polaris.ui.AppearanceState
import com.beichen.polaris.ui.MarkdownParser
import com.beichen.polaris.ui.container.ContainerInstallController

/**
 * 应用级全局状态的**唯一清单**。
 *
 * ## 为什么存在
 *
 * 这个应用里有 26 个带可变状态的 `object`：它们是跨层通信的真实通道
 * （ViewModel ↔ 无障碍服务 ↔ 前台服务 ↔ 推理引擎），但依赖关系不写在任何构造参数里。
 * 后果是三条：
 *
 * 1. **测试互相污染**——上一个用例留下的 `AskUserBroker.current` 会让下一个用例
 *    永远拿到 `Busy`，而失败点在别人的用例里，排查方向从第一分钟就是错的；
 * 2. **生命周期无人负责**——`BrowserHost` 由 `MainActivity.onDestroy` 释放，
 *    这条约束此前只存在于一句注释里，Activity 一销毁 web 工具就整体不可用；
 * 3. **新增通道没有地方登记**——只能靠 grep 找，找不全是常态。
 *
 * 这里不引入 Hilt/Koin。依赖注入框架会要求改 21 处构造签名，收益（可测性）需要
 * 一到两周才兑现，而这段时间里每个改动都在碰热路径。把「有哪些全局状态」变成
 * 一份可读、可枚举、可一键清空的清单，成本一天，先把第 1、3 条解决掉。
 *
 * ## 边界（刻意不做的三件事）
 *
 * - **不接管生产生命周期**。`resetAll()` 只在 harness / 诊断里调用；
 *   App 运行期没人调它，所以它不可能改变线上行为。
 * - **不做容器**。`globals` 里存的是 `object` 本身（内容相等），不是新造的单例；
 *   任何调用点继续写 `AskUserBroker.ask(...)`，不需要改成 `graph.askUserBroker.ask(...)`。
 * - **不改可见性**。每个 `reset()` 都是各单例自己声明的，`AppGraph` 只是调用方；
 *   不留后门去反射改 private 字段。
 *
 * ## 怎么保持这份清单是真的
 *
 * `scripts/qa/run-appgraph-verification.sh` 做双向对账：
 * 源码里每个「带可变状态、又不是每个实例独立」的 `object` 必须在这里有登记，
 * 反过来这里的每一条也必须对应一个真实存在且声明了 `reset()` 的对象。
 * 漏登记会让「新增通道没人知道」重新变成常态，所以这条对账必须是硬断言。
 */
object AppGraph {

    /**
     * 一条全局通道。
     *
     * @param name       对象名，与源码里的 `object X` 完全一致（对账靠它）
     * @param packageName 所在包，用于区分同名对象
     * @param link       谁在读写它——这行是「为什么它必须是全局」的答案，
     *                   写不出来就说明它不是通道，只是被误放成了 `object`
     * @param resettable false = 该对象只有**事实性**状态（native 已加载、handler 已挂），
     *                   没有可清理的运行态，`reset()` 是刻意的 no-op
     * @param why        不可重置的原因（`resettable=false` 时必填）
     * @param reset      清空动作；必须幂等，且不得写 SharedPreferences / 删用户文件
     */
    data class Global(
        val name: String,
        val packageName: String,
        val link: String,
        val reset: () -> Unit,
        val resettable: Boolean = true,
        val why: String = ""
    )

    /**
     * 全部全局状态。
     *
     * 用 `by lazy` 而不是字段初始化：这 22 个 `object` 里有会起线程的（`TraceSink`）、
     * 会碰 native 的（`LlamaJni`）、会读 SharedPreferences 的（`AdbCredentials`）。
     * 字段初始化会在 `AppGraph` 第一次被类加载时就把它们全部拉起来，
     * 等于把「有没有人用」变成「一定初始化」——这正是这份清单想避免的事。
     */
    val globals: List<Global> by lazy { buildGlobals() }

    /** 只看名字，供日志与诊断页展示。 */
    fun inventory(): List<String> = globals.map { it.name }

    /**
     * 依次清空全部可重置的全局状态。
     *
     * **顺序有意为之**：先断开对外连接（自动化 / 浏览器 / ADB / Shizuku），
     * 再停日志与遥测，最后清推理与租约——反过来会出现「资源已释放但日志还在写
     * 该资源的句柄」，在 harness 里表现为偶发 NPE。
     *
     * @return 实际执行了清理的条数（不含刻意的 no-op），便于 harness 断言
     */
    fun resetAll(): Int {
        var n = 0
        for (g in globals) {
            if (!g.resettable) continue
            g.reset()
            n++
        }
        return n
    }

    private fun buildGlobals(): List<Global> = buildList {
        // ── 自动化：UI ↔ 无障碍服务 ↔ 设备上下文 ──────────────────────────
        add(
            Global(
                name = "AutomationBridge",
                packageName = "com.beichen.polaris.automation",
                link = "无障碍服务实例 ↔ Agent 工具/设置页开关状态",
                reset = { AutomationBridge.reset() }
            )
        )
        add(
            Global(
                name = "DeviceContextStore",
                packageName = "com.beichen.polaris.automation",
                link = "无障碍/通知监听服务 → 对话层（前台应用与最近通知）",
                reset = { DeviceContextStore.reset() }
            )
        )
        add(
            Global(
                name = "AdbSession",
                packageName = "com.beichen.polaris.automation.adb",
                link = "无线调试连接池 ↔ 终端后端",
                reset = { AdbSession.reset() }
            )
        )
        add(
            Global(
                name = "AdbCredentials",
                packageName = "com.beichen.polaris.automation.adb",
                link = "ADB 密钥对与端点（SharedPreferences 缓存）",
                reset = { AdbCredentials.reset() }
            )
        )
        add(
            Global(
                name = "ShizukuShell",
                packageName = "com.beichen.polaris.automation.shizuku",
                link = "Shizuku UserService 绑定 ↔ 终端/自动化后端",
                reset = { ShizukuShell.reset() }
            )
        )

        // ── 浏览器：一个 WebView 被所有 web_* 工具共用 ────────────────────
        add(
            Global(
                name = "BrowserHost",
                packageName = "com.beichen.polaris.browser",
                link = "MainActivity 创建的后台 WebView ↔ web_open/web_eval 等工具",
                reset = { BrowserHost.reset() }
            )
        )

        // ── 观测：日志、崩溃、遥测三个出口 ────────────────────────────────
        add(
            Global(
                name = "AgentLogger",
                packageName = "com.beichen.polaris.core",
                link = "全应用日志出口（内存环形缓冲 + 落盘目录）",
                reset = { AgentLogger.reset() }
            )
        )
        add(
            Global(
                name = "CrashLogger",
                packageName = "com.beichen.polaris.core",
                link = "进程级未捕获异常出口",
                reset = { CrashLogger.reset() },
                resettable = false,
                why = "UncaughtExceptionHandler 一旦挂到 VM 上就无法安全摘下：卸载会吞掉" +
                    "此后所有崩溃证据，重装会套娃成多层 handler。它记录的是事实，不是运行态。"
            )
        )
        add(
            Global(
                name = "TraceSink",
                packageName = "com.beichen.polaris.core.telemetry",
                link = "回合事件 → NDJSON 流水（后台线程 + 队列）",
                reset = { TraceSink.reset() }
            )
        )
        // 1.594 的报告里没有这一条：`foregroundProbe` 是 1.597 拆 3.2 时才出现的
        // （core 不再 import AgentKitApplication，改用 app 层注入的 lambda）。
        // 对账脚本第一次跑就把这条漏登记抓了出来——这正是这份清单存在的理由：
        // 报告是某一天的快照，代码不是。
        add(
            Global(
                name = "CompletionNotifier",
                packageName = "com.beichen.polaris.core",
                link = "完成提醒的「App 是否在前台」探针（app 层注入，core 只读）",
                reset = { CompletionNotifier.reset() }
            )
        )

        // ── 人机协作：Agent 挂起等用户，UI 侧回答 ─────────────────────────
        add(
            Global(
                name = "AskUserBroker",
                packageName = "com.beichen.polaris.core.agent",
                link = "工具层挂起提问 ↔ 对话页回答卡片",
                reset = { AskUserBroker.reset() }
            )
        )
        add(
            Global(
                name = "PlanBroker",
                packageName = "com.beichen.polaris.core.agent",
                link = "计划审批挂起 ↔ 对话页计划卡片",
                reset = { PlanBroker.reset() }
            )
        )

        // ── 本地推理：native 句柄与设备画像 ───────────────────────────────
        add(
            Global(
                name = "LlamaJni",
                packageName = "com.beichen.polaris.core.llm.local",
                link = "libagentkit-llama.so 的一次性加载结果",
                reset = { LlamaJni.reset() },
                resettable = false,
                why = "System.loadLibrary 不可逆：.so 一旦载入就留在进程里，失败的尝试" +
                    "（ABI 不符 / 缺指令集）重试也是同样结果。这里只有事实，没有可清状态。"
            )
        )
        add(
            Global(
                name = "LocalLlamaEngine",
                packageName = "com.beichen.polaris.core.llm.local",
                link = "已加载的 llama_context 句柄 ↔ 所有本地模型调用",
                reset = { LocalLlamaEngine.reset() }
            )
        )
        add(
            Global(
                name = "LocalLlmRuntime",
                packageName = "com.beichen.polaris.runtime",
                link = "本地模型能力状态 ↔ 设置页/能力链",
                reset = { LocalLlmRuntime.reset() }
            )
        )
        add(
            Global(
                name = "AndroidDeviceProbe",
                packageName = "com.beichen.polaris.core.llm.local",
                link = "设备画像（SoC/内存/GPU）一次采集，多处读取",
                reset = { AndroidDeviceProbe.reset() }
            )
        )

        // ── 资源与文件系统 ────────────────────────────────────────────────
        add(
            Global(
                name = "LeaseRegistry",
                packageName = "com.beichen.polaris.runtime.lease",
                link = "资源租约登记 ↔ 空闲回收器（唯一执行处）",
                reset = { LeaseRegistry.reset() }
            )
        )
        add(
            Global(
                name = "WorkspaceStore",
                packageName = "com.beichen.polaris.core.workspace",
                link = "Agent 工作区根目录（含老目录一次性迁移标记）",
                reset = { WorkspaceStore.reset() }
            )
        )
        add(
            Global(
                name = "TerminalJni",
                packageName = "com.beichen.polaris.terminal",
                link = "libagentkit-termux.so 的一次性加载结果",
                reset = { TerminalJni.reset() },
                resettable = false,
                why = "同 LlamaJni：动态库加载不可逆，失败重试结果不变。"
            )
        )
        add(
            Global(
                name = "ContainerInstallController",
                packageName = "com.beichen.polaris.ui.container",
                link = "容器运行时安装任务 ↔ 跨页面的进度/结果卡片",
                reset = { ContainerInstallController.reset() }
            )
        )
        add(
            Global(
                name = "BuildEngine",
                packageName = "com.beichen.polaris.core.build",
                link = "构建页 ↔ 进程级构建协程（切页面不中断，同一时间只允许一个构建）；" +
                    "harness 里不取消就会带着上一轮的网络请求进入下一个用例",
                reset = { BuildEngine.reset() }
            )
        )

        // ── 音频：进程级单例（对话页 / 后台回合 / 桌宠共用）─────────────────
        add(
            Global(
                name = "AudioGraph",
                packageName = "com.beichen.polaris.audio",
                link = "TTS 引擎与麦克风仲裁器 ↔ 对话页 / 后台回合 / 桌宠。悬浮窗活在 Service 里、" +
                    "没有 Activity，若各自 new 一份就会同时出声、互相看不见对方占着麦克风",
                reset = { AudioGraph.reset() }
            )
        )

        // ── 桌面助手：悬浮窗的开关意图与运行态 ─────────────────────────────
        add(
            Global(
                name = "PetState",
                packageName = "com.beichen.polaris.pet",
                link = "设置页开关 ↔ 悬浮窗 Service 的启停。Service 比任何 ViewModel 活得久，" +
                    "状态寄在 ViewModel 上会出现「服务在跑、开关显示关」的错位",
                reset = { PetState.reset() }
            )
        )

        // ── 外观与解析 ────────────────────────────────────────────────────
        add(
            Global(
                name = "Monotonic",
                packageName = "com.beichen.polaris.core.time",
                link = "全部超时判定共用时钟源（后台任务 / 脚本看门狗 / CI 轮询 / 审批倒计时）；" +
                    "harness 注入假时钟后必须还原",
                reset = { Monotonic.reset() }
            )
        )
        add(
            Global(
                name = "AppearanceState",
                packageName = "com.beichen.polaris.ui",
                link = "主题/强调色/动态取色 ↔ 全部 Compose 界面",
                reset = { AppearanceState.reset() }
            )
        )
        add(
            Global(
                name = "MarkdownParser",
                packageName = "com.beichen.polaris.ui",
                link = "Markdown → AST（纯函数入口，无实例状态）",
                reset = { MarkdownParser.reset() },
                resettable = false,
                why = "解析器把游标放在每次 new 出来的 Parser 实例里，object 本身只有两个" +
                    "纯函数。没有状态可清——登记在这里是为了让「它为什么是 object」有答案。"
            )
        )
    }
}
