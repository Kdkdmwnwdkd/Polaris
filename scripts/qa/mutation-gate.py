#!/usr/bin/env python3
"""审批判定的变异门禁（L0）。

## 解决的问题

仓库里 150 个 `run-*.sh` 中，约 575 处是 `grep -q` 文本断言。它们能证明「源码里有这行字」，
不能证明「这行字起了作用」——删掉实现、反转判断，grep 照样绿。2026-11-18 的实测里，
5 个关键守卫的变异全部存活（或整段 SKIP）。

本脚本用最原始也最可靠的办法回答「断言有没有判别力」：**把缺陷注入回去，看测试红不红**。
只覆盖已经迁到 JVM 的判定（审批 / 存储 / 动作回执 / 出站守卫），因为变异测试的前提是
「有能在 30 秒内跑完的真断言」——grep 型断言参与变异只会得到噪声。

## 与 run-*.sh 的关系

故意不以 `run-` 开头：`run-all.sh` 用 `scripts/qa/run-*.sh` 通配收集 runner，
本脚本要真编译十几个源文件，不适合塞进每轮全量（会拖慢 3~5 分钟）。
接入 CI 时单独一个 job，或作为 `docs/ops` 里记录的本地检查项。

## 判定

- 变异被**期望的那条断言**捕获 → CAUGHT（有判别力）
- 变异存活（测试仍全绿）→ SURVIVED（断言无判别力，退出码 1）
- 编译失败 → INVALID（变异破坏了语法，是脚本定义的错，不是判定的错）
- 缺编译器 / JUnit → SKIP（什么都没验证，**不算通过**）
  CI 上要传 `--strict`：缺环境直接失败。否则「缺构件 → 退出码 0」会让一个
  什么都没跑的 job 显示绿色，正是本项目最忌讳的那种绿。

用法：
    python3 scripts/qa/mutation-gate.py [--verbose] [--strict] [--only <id 子串>] [--group <组名>]

先跑基线（三个组的未变异断言必须全绿），再逐条注入变异。基线红时直接退出：
基线红的时候每一条变异都会「CAUGHT」，那是假报告。
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# JUnit 运行超时（秒）。runtime 组引入了协程并发测试，一个死锁的变异（比如把
# 两级并发压成全局一把锁）会让某个 await 永远挂起——不设超时，门禁自己卡死，
# 结论出不来，比假绿更糟。120 秒对「最多 delay 200ms + withTimeout 5s」的断言
# 绰绰有余，死锁变异会以超时形式被判 INVALID（可定位到具体变异），而不是拖垮全量。
JUNIT_TIMEOUT_SEC = 120

# 按「最小编译闭包」分组：一次变异只重编它自己那条链上的文件。
# 全放一起编（20+ 文件）会让每条变异都慢一倍，而变异点分布在这两条互不相干的链上。
GROUPS = {
    "approval": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/agent/ShellCommandRules.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ApprovalTier.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/NotificationScope.kt",
            "core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt",
            "core/src/main/java/com/beichen/polaris/core/llm/JsonSafe.kt",
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ToolDescriptor.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ToolGuard.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/ShellCommandRulesTest.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/ApprovalTierTest.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/EgressDowngradeTest.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/EgressGuardTest.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/ToolGuardChainTest.kt",
            "core/src/test/java/com/beichen/polaris/core/tool/ToolCatalogTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.ShellCommandRulesTest",
            "com.beichen.polaris.core.agent.ApprovalTierTest",
            "com.beichen.polaris.core.agent.EgressDowngradeTest",
            "com.beichen.polaris.core.agent.EgressGuardTest",
            "com.beichen.polaris.core.agent.ToolGuardChainTest",
            "com.beichen.polaris.core.tool.ToolCatalogTest",
        ],
    },
    # 脱敏判据（第十一轮）。单独成组是因为它的源码闭包与审批组几乎不重叠：
    # 它要 TurnEvent/toNdjson/TraceSink 才能断言「流水行里的 Key 被抹掉」，
    # 而这三个文件不该拖进审批组（那会让每次跑 approval 多编 1500 行）。
    "redaction": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/TurnObserver.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/TurnEventNdjson.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/journal/RunScope.kt",
            "core/src/main/java/com/beichen/polaris/core/telemetry/TraceSink.kt",
            "core/src/test/java/com/beichen/polaris/core/DiagnosticsRedactionTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.DiagnosticsRedactionTest",
        ],
    },
    # 本地模型的工具调用解析（第十二轮）。零依赖组：这个文件只 import kotlinx.serialization，
    # 源码闭包就它自己一个——编译 360 行，跑一遍不到一秒，是全场最便宜的一组。
    "parser": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
            "core/src/test/java/com/beichen/polaris/core/llm/local/LocalToolCallParserTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.llm.local.LocalToolCallParserTest",
        ],
    },
    # 二维码编码（本轮）。零依赖组：QrCode 只用 stdlib，解码器与 fixture 都在测试侧，
    # 编 380 行不到两秒。它的判据是「解一遍」——比任何 grep 都强，但也就意味着
    # 变异必须落在**编码**侧，落在解码器上只会证明解码器错了。
    "qrcode": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
            "core/src/test/java/com/beichen/polaris/core/qr/QrDecoder.kt",
            "core/src/test/java/com/beichen/polaris/core/qr/QrFixtures.kt",
            "core/src/test/java/com/beichen/polaris/core/qr/QrCodeTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.qr.QrCodeTest",
        ],
    },
    "storage": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/ConversationStore.kt",
            "core/src/main/java/com/beichen/polaris/core/UiItem.kt",
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/main/java/com/beichen/polaris/core/ConversationBranch.kt",
            "core/src/main/java/com/beichen/polaris/core/ConversationShard.kt",
            "core/src/main/java/com/beichen/polaris/core/llm/JsonSafe.kt",
            "core/src/main/java/com/beichen/polaris/core/io/AtomicJsonFile.kt",
            "core/src/main/java/com/beichen/polaris/core/lifecycle/AppBackgroundHooks.kt",
            # 目录会在编译前展开成 *.kt
            "core/src/main/java/com/beichen/polaris/core/telemetry",
            "core/src/test/java/com/beichen/polaris/core/ConversationTruncationTest.kt",
            "core/src/test/java/com/beichen/polaris/core/ConversationShardRecoveryTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.ConversationTruncationTest",
            "com.beichen.polaris.core.ConversationShardRecoveryTest",
        ],
    },
    # app 模块里唯一一段「不 import android.*」的逻辑：动作回执的判定。
    # 单独成组是因为它和审批/存储两条链毫无关系，混编只会让每条变异都慢。
    "automation": {
        "sources": [
            "app/src/main/java/com/beichen/polaris/automation/ScreenChangeReceipt.kt",
            "app/src/test/java/com/beichen/polaris/automation/ScreenChangeReceiptTest.kt",
        ],
        "classes": ["com.beichen.polaris.automation.ScreenChangeReceiptTest"],
    },
    # 运行时并发原语（P1 第一轮）。零依赖组：只 import kotlinx.coroutines（classpath
    # 已有），不 import android.*——两级并发（(session,source) 互斥 + backend 许可）
    # 与代次号都是纯机制，脱离 Android 在 JVM 上逐条验证。这就是 v4 §3.2 的「地基」。
    "runtime": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
            # 2026-11-23：Quota 从 AgentRuntime.kt 搬到独立文件（零 import），
            # TaskSpec 仍引用它，所以这个闭包必须带上——否则整个组编译失败。
            "core/src/main/java/com/beichen/polaris/core/runtime/Quota.kt",
            # P1 第四轮：执行权收口。RuntimeBoundAgentLoop 依赖 AgentLoop 接口
            # （→ ChatMessage / ToolRegistry），所以编译闭包从「三个文件」扩到
            # 「接口 + 契约 + registry」。多出来的文件都是纯 JVM、零 Android。
            "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
            "core/src/main/java/com/beichen/polaris/core/AgentLoop.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/AgentLoop.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ToolDescriptor.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolRegistry.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolLoadProfiler.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolLimits.kt",
            # TaskSpec 的 consent 字段是 Consent 类型，Consent 依赖 AgentTool（AgentContract）
            # 与 ToolResult——三个文件一起编译，与 run-consent-verification.sh 同一闭包。
            "core/src/main/java/com/beichen/polaris/core/Consent.kt",
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/test/java/com/beichen/polaris/core/runtime/AgentRuntimeTest.kt",
            "core/src/test/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoopTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.runtime.AgentRuntimeTest",
            "com.beichen.polaris.core.runtime.RuntimeBoundAgentLoopTest",
        ],
    },
    # 重试退避的纯数值排期（2026-11-22）。**全场最便宜的一组**：只编两个文件、零依赖。
    #
    # 值一提的是它以前进不来：数值原本住在 RetryBackoffPolicy 里，而那个对象带着
    # `networkAware(waiter: NetworkRecoveryWaiter)`，NetworkRecoveryWaiter 定义在
    # NetworkRecovery.kt（import android.net）——想编它就得有 android.jar。
    # 把数值拆到 RetrySchedule 之后，这一组才成立。
    "retry": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/agent/RetrySchedule.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/RetryScheduleTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.RetryScheduleTest",
        ],
    },
    # 回合时钟（2026-11-22，A1 第一块）。零依赖组：TurnClock + TurnEvent 契约 + RunScope，
    # 编 300 行不到两秒。它证明的是「事件信封」不会悄悄错位——四个字段（turnId / step /
    # elapsed / scope）任一漂了，时间线就不能用来定位「卡在哪个 tool.start」。
    "trace": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/CallIdShort.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/TurnObserver.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/journal/RunScope.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/TurnClockTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.TurnClockTest",
        ],
    },
    # 语音入口的分支判定（2026-11-23）。零依赖组：判定文件没有一行 import，
    # 所以它能进这个只认 kotlinc + JUnit 的门禁——这正是当初抽它的理由。
    # 注意源文件在 app/ 下，但**不含 Compose**：ui 是包名，不是「要编界面」。
    "ui": {
        "sources": [
            "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
            "app/src/test/java/com/beichen/polaris/ui/VoiceInputDecisionTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.ui.VoiceInputDecisionTest",
        ],
    },
    # 回合配额换算（2026-11-23）。零依赖组：Quota 是纯数据类、QuotaResolver 零 import。
    # 兜底值从参数传而不是写常量，正是为了让这组留在 kotlinc + JUnit 的闭包里。
    # 聊天时间显示（2026-11-23 真机 bug）。零依赖组：ChatTime 一行 import 都没有
    # （时区走 java.util.TimeZone，JDK 自带，不是 Android）。
    #
    # 它守的是一条「看着没问题、实际全错」的规则：partsOf 若忘了加 offsetMs，
    # 代码照样跑、测试照样绿（只要断言也只按 UTC 写），而用户看到的是
    # 21:45 显示成 13:45。真机上就是这么被发现的。
    "chattime": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/text/ChatTime.kt",
            "core/src/test/java/com/beichen/polaris/core/text/ChatTimeTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.text.ChatTimeTest",
        ],
    },
    "quota": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/runtime/Quota.kt",
            "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
            "core/src/test/java/com/beichen/polaris/core/runtime/QuotaResolverTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.runtime.QuotaResolverTest",
        ],
    },
    # 系统提示的分段决策（2026-11-23）。零依赖组：PromptSegments + SystemPromptPlan
    # 一行 import 都没有，编 300 行不到两秒。
    #
    # 它守的是一条**错了不会报错、只会变慢**的规则：记忆段若混进 FROZEN/SESSION，
    # 每轮记忆一变整段前缀作废，端侧重新 prefill，日志里只有一条 INFO。把它编进
    # 门禁，才能在有人「顺手调一下段序」时立刻红。
    "prompt": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/PromptSegments.kt",
            "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
            "core/src/test/java/com/beichen/polaris/core/SystemPromptPlanTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.SystemPromptPlanTest",
        ],
    },
    # 请求视图的编排（2026-11-23）。ContextBudget 的三个纯函数各有 harness 判据，
    # 但「它们怎么连起来」没有——先剥图还是先压历史，只有连起来才看得出对错。
    # 闭包比 ContextBudget 的 harness 小一圈：不要 ui / tools，只要契约 + 预算 + 编排。
    "request": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolLimits.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ContextBudget.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/RequestViewTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.RequestViewTest",
        ],
    },
    # 暴露面 → 回合级工具视图（2026-11-23）。规划本身由 KernelExposure 负责、
    # 有 harness 判据；这一组只守「拿规划造视图」那两步，两个错法都不报错：
    # 　视图不收紧（被裁工具照常执行，裁剪白做且本该开组的调用被静默放行）、
    # 　视图收紧过头（连元工具一起滤掉，模型再也开不了组）。
    #
    # 闭包是这一组里最大的一个：ToolRegistry 连带 ToolDescriptor / ToolCatalog /
    # CapabilityManifest 全要编进来，还要序列化插件（ToolRegistry.descriptors 用
    # JsonSchema.serializer）。实测仍不到 5 秒，值得——它守的是一条安全路径。
    "toolscope": {
        "sources": [
            "core/src/main/java/com/beichen/polaris/core/AgentContract.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolResult.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolLimits.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolLoadProfiler.kt",
            "core/src/main/java/com/beichen/polaris/core/ToolRegistry.kt",
            "core/src/main/java/com/beichen/polaris/core/ExposureTypes.kt",
            "core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt",
            "core/src/main/java/com/beichen/polaris/core/capability/CapabilityManifest.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ToolDescriptor.kt",
            "core/src/main/java/com/beichen/polaris/core/KernelExposure.kt",
            "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/ToolScopeTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.ToolScopeTest",
        ],
    },
    # 输入区状态收口（2026-11-24，批 1）。
    #
    # 零依赖组：ComposerState.kt 只 import kotlinx.coroutines——PendingFile 特意与它同文件
    # 定义，就是为了让这个闭包不带 ChatUiTypes 那条 ApprovalGrant 链。编两个文件不到两秒。
    #
    # 为什么值得单独成组：它守的两条判据（原子读改写 / 取走即清空）用 grep 完全测不出来。
    # 非原子的 `edit` 在单线程下与原子版**逐字节相同**，只有并发才暴露；而它一旦退回去，
    # W-31（界面本地副本与真实值不同步）就会以「刚说的话没了」复发。
    "composer": {
        "sources": [
            "app/src/main/java/com/beichen/polaris/ui/ComposerState.kt",
            "app/src/test/java/com/beichen/polaris/ui/ComposerStateTest.kt",
        ],
        "classes": ["com.beichen.polaris.ui.ComposerStateTest"],
    },
}

# 被测源文件。收录判据只有一条：**这段判定有能在 30 秒内跑完的真断言**。
# 覆盖面按组推进（审批 → 脱敏 → 解析 → 二维码 → 存储 → 动作回执 → 运行时 → 退避），
# 先写断言再谈变异；没有断言的代码放进来只会测出「grep 匹不匹配」。
MUTATIONS = [
    {
        "id": "M1 相对路径不解析（= P1-1 修前行为）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ShellCommandRules.kt",
        "old": '                val normalized = resolveAgainst(dir, raw) ?: continue',
        "new": '                val normalized = (if (raw.startsWith("/")) normalizeAbsolute(raw) else null) ?: continue',
        # 期望这几条断言红。少一条都说明覆盖有洞。
        "expect": ["A5b", "A5c", "A5d"],
    },
    {
        "id": "M2 只读白名单失效（一切命令算只读）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ShellCommandRules.kt",
        "old": "    private fun isReadOnly(segment: String): Boolean {\n",
        "new": "    private fun isReadOnly(segment: String): Boolean {\n        if (segment.isNotEmpty()) return true\n",
        "expect": ["E1"],
    },
    {
        "id": "M3 可记忆判定失效（rm/重定向都能记住）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ShellCommandRules.kt",
        "old": "    fun isMemorizable(command: String): Boolean {\n        val trimmed = command.trim()",
        "new": "    fun isMemorizable(command: String): Boolean {\n        if (command.isNotEmpty()) return true\n        val trimmed = command.trim()",
        "expect": ["D1", "D2", "D3"],
    },
    {
        "id": "M4 不可记忆的授权也能命中白名单",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
        "old": "        grant.memorizable && grant.key in sessionAllowed",
        "new": "        grant.key in sessionAllowed",
        # 这条改动会让「不可记忆」退化成「可记忆」，属于安全降级。
        "expect": ["C5"],
    },
    {
        "id": "M5 STRICT 档失效（白名单在逐步确认下也放行）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalTier.kt",
        "old": "        STRICT -> false",
        "new": "        STRICT -> sessionAllowed",
        "expect": ["STRICT 任何组合都弹窗"],
    },
    {
        "id": "M6 AUTO 档全放行（连 shell 也不问）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalTier.kt",
        "old": "        AUTO -> sessionAllowed || group in AUTO_ALLOW_GROUPS",
        "new": "        AUTO -> true",
        "expect": ["AUTO 高风险组仍按白名单判"],
    },
    {
        "id": "M7 STANDARD 档失效（未授权也放行）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalTier.kt",
        "old": "        else -> sessionAllowed",
        "new": "        else -> true",
        "expect": ["STANDARD 只看白名单"],
    },
    {
        "id": "M8 读回重写清零截断计数（第一版的真实 bug）",
        "file": "core/src/main/java/com/beichen/polaris/core/ConversationStore.kt",
        "group": "storage",
        "old": "        truncatedChars = truncatedChars + droppedChars(text)\n    )\n\n    is UiItem.Assistant",
        "new": "        truncatedChars = droppedChars(text)\n    )\n\n    is UiItem.Assistant",
        "expect": ["读回重写不清零"],
    },
    {
        "id": "M9 推理过程截断不计数",
        "file": "core/src/main/java/com/beichen/polaris/core/ConversationStore.kt",
        "group": "storage",
        "old": "        truncatedChars = truncatedChars +\n            droppedChars(text) + droppedChars(reasoning.orEmpty())",
        "new": "        truncatedChars = truncatedChars + droppedChars(text)",
        "expect": ["多个被截断字段"],
    },
    {
        # 恢复时把「文件名 stem」当成会话 id 写回索引。UUID 场景下两者相同，测试全绿；
        # 只有脏 id（含 `/` 等）才会分叉——而备份导入恰恰最容易带进这种 id。
        "id": "M10 恢复时索引写文件名 stem 而非真实 id",
        "file": "core/src/main/java/com/beichen/polaris/core/ConversationStore.kt",
        "group": "storage",
        "old": "        val ids = pairs.map { (stem, rec) -> rec?.id ?: stem }.distinct()",
        "new": "        val ids = pairs.map { (stem, rec) -> stem }.distinct()",
        "expect": ["恢复写出的索引记录的是会话真实 id"],
    },
    {
        "id": "M11 回退 .bak 后不自愈主分片",
        "file": "core/src/main/java/com/beichen/polaris/core/io/AtomicJsonFile.kt",
        "group": "storage",
        "old": "            if (healBackup) runCatching { write(file, backup, keepBackup = false) }",
        "new": "            // 变异：去掉自愈",
        "expect": ["回退备份后主分片应被自愈"],
    },
    {
        "id": "M12 分片有效性判定形同虚设（坏内容也算可读）",
        "file": "core/src/main/java/com/beichen/polaris/core/ConversationStore.kt",
        "group": "storage",
        "old": "        ) { text -> parseShardOrNull(text) != null } ?: return null",
        "new": "        ) { text -> text.isNotBlank() } ?: return null",
        "expect": ["单个分片主文件损坏时回退其备份"],
    },
    {
        # fail-closed 是工具目录唯一的安全兜底：未知工具默认 DANGEROUS。
        # 改成 SAFE 不会有任何编译错误，也不会有任何 harness 红（改前确实没有）。
        "id": "M13 未登记工具按 SAFE 处理（fail-open）",
        "file": "core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt",
        "old": "fun capabilityOf(name: String): Capability = byName[name]?.capability ?: Capability.DANGEROUS",
        "new": "fun capabilityOf(name: String): Capability = byName[name]?.capability ?: Capability.SAFE",
        "expect": ["未登记工具的能力按 DANGEROUS 处理"],
    },
    {
        # MCP 工具名来自外部服务器。按前缀并组会让 web_eval_helper 落进 web 组，
        # 继承用户勾过的「不再询问网页操作」。
        "id": "M14 审批归组按前缀并组（外部工具继承静态组白名单）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt",
        "old": "fun groupOf(toolName: String): String = ToolCatalog.approvalOf(toolName) ?: toolName",
        "new": "fun groupOf(toolName: String): String = ToolCatalog.approvalOf(toolName) ?: ToolCatalog.groupOf(toolName)",
        "expect": ["外部工具名以已声明组开头时不会并进那个组"],
    },
    {
        "id": "M15 目录自检漏掉「有目录没实现」",
        "file": "core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt",
        "old": '''        (catalog - impl).sorted().forEach {
            add("$it 在目录里但没有实现（模型照目录调用会拿到 unknown_tool）")
        }''',
        "new": "        // 变异：去掉「有目录没实现」检查",
        "expect": ["目录里有的工具没有实现时要报出来"],
    },
    {
        # lethal trifecta 的第三道闸。改成「永不降级」不会有任何编译错误，
        # 而且这条路径在 JVM 上本来就测不到（原来只存在于 ChatViewModel 里）。
        "id": "M16 读过私有内容后仍可记忆（永不降级）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
        "old": "        taintedNow && ApprovalGrouping.groupOf(toolName) in EGRESS_GROUPS",
        "new": "        taintedNow && ApprovalGrouping.groupOf(toolName) in emptySet<String>()",
        "expect": [
            "污染后网络外发降级为不可记忆",
            "污染后 GitHub 写操作降级为不可记忆",
        ],
    },
    {
        # 一刀切降级：所有审批都变「每次问」。看着更安全，实际是把机制废掉——
        # 用户点一天之后就会闭眼点。
        "id": "M17 一刀切降级（不看是不是外发组）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
        "old": "        if (!isEgressTurn(toolName, taintedNow)) return grant",
        "new": "        if (!taintedNow) return grant",
        "expect": [
            "污染后普通 shell 不降级",
            "污染后文件写入不降级",
        ],
    },
    {
        # 只改文案、不改 memorizable：弹窗里写着「本回合读过私有内容」，
        # 用户照样能点「始终允许」并写进白名单。最阴的一种假修。
        "id": "M18 降级只改文案不改可记忆性",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
        "old": "            memorizable = false,\n            label = grant.label",
        "new": "            memorizable = grant.memorizable,\n            label = grant.label",
        "expect": ["污染后网络外发降级为不可记忆"],
    },
    {
        # key 跟着变：干净回合里点过的「始终允许」永远命中不了，域名记忆整段废掉。
        "id": "M19 降级时换掉授权键",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt",
        "old": "            memorizable = false,\n            label = grant.label",
        "new": "            key = grant.key + \"#egress\",\n            memorizable = false,\n            label = grant.label",
        "expect": ["降级不改 key"],
    },
    {
        # fail-open：守卫崩溃被当成「没意见」。这条链从「限制」变成「限制的假象」，
        # 而且装得越多越危险（没人会怀疑一个装着三个守卫的系统）。
        "id": "M20 守卫崩溃当成弃权（fail-open）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolGuard.kt",
        "old": '''                log("守卫 #${index + 1} 执行失败（${e::class.java.simpleName}: ${e.message}），按拒绝处理")
                return GuardDecision.Deny(
                    code = "guard_failed",
                    message = "工具调用被策略层拦下：第 ${index + 1} 个守卫执行失败（${e::class.java.simpleName}）。",
                    hint = "这不是参数问题，重试同样会失败。请告知用户检查策略设置。",
                )''',
        "new": "                log(\"变异：崩溃当成弃权\")\n                return GuardDecision.Abstain",
        "expect": ["守卫抛异常按拒绝处理", "守卫抛 Error 也按拒绝处理"],
    },
    {
        # 去掉「取消要穿透」：用户点停止，界面显示「工具执行失败」。
        "id": "M21 取消被兜成拒绝",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolGuard.kt",
        "old": '''            } catch (cancel: CancellationException) {
                // 取消不是拒绝：点「停止」要能立刻退出，不能被守卫兜成一条错误结果
                throw cancel''',
        "new": "            } catch (cancel: CancellationException) {",
        "expect": ["CancellationException 不被兜成拒绝"],
    },
    {
        # 链按默认 3 秒算：需要问用户的守卫永远等不到用户回答。
        "id": "M22 链忽略守卫自己的超时上限",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolGuard.kt",
        "old": "            val budget = guard.timeoutMs",
        "new": "            val budget = GUARD_TIMEOUT_MS",
        "expect": ["超时用守卫自己的上限而不是链默认值"],
    },
    {
        # 守卫拒绝了，但返回值被丢掉。这是整条链最致命的一种退化：
        # 每个守卫都「跑过了」，日志里也有拒绝记录，工具照样执行。
        "id": "M23 守卫的拒绝被丢弃",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolGuard.kt",
        "old": "                return decision",
        "new": "                // 变异：拒绝被丢弃",
        "expect": ["首个拒绝即终止，后面的守卫不再被询问", "拒绝原样透传，不被改写"],
    },
    {
        # 误报「生效了」：模型以为点到了，实际被弹窗挡住，于是不再重试，
        # 任务在无人知晓的地方断掉。比不检测更糟。
        "id": "M24 超时当成「已变化」",
        "file": "app/src/main/java/com/beichen/polaris/automation/ScreenChangeReceipt.kt",
        "group": "automation",
        "old": "    } ?: false",
        "new": "    } ?: true",
        "expect": ["界面一直不变时超时返回 false"],
    },
    {
        # 恒轮询到超时：界面明明变了也报 no_effect，模型开始重复点同一位置。
        "id": "M25 变化了也不退出轮询",
        "file": "app/src/main/java/com/beichen/polaris/automation/ScreenChangeReceipt.kt",
        "group": "automation",
        "old": "        while (sense() == before) delay(pollMs)",
        "new": "        while (true) delay(pollMs)",
        "expect": ["界面已变化时立即返回 true", "第 N 次轮询时才变化也算检测到"],
    },
    {
        # 去掉 delay = 忙等。而且忙等循环里没有挂起点，withTimeoutOrNull 检测不到超时，
        # 函数永远不会返回——比「慢」严重得多。
        "id": "M26 轮询不退让（忙等）",
        "file": "app/src/main/java/com/beichen/polaris/automation/ScreenChangeReceipt.kt",
        "group": "automation",
        "old": "        while (sense() == before) delay(pollMs)",
        "new": "        while (sense() == before) Unit",
        "expect": ["轮询有间隔，不是忙等"],
    },
    {
        # 窗口为 0 时当作「已变化」：调用方用它显式关掉回执（不该等、也不该判没变），
        # 改成 true 就等于「关掉回执」反而变成「永远报生效」。
        "id": "M27 零窗口被当成「已变化」",
        "file": "app/src/main/java/com/beichen/polaris/automation/ScreenChangeReceipt.kt",
        "group": "automation",
        "old": "    if (timeoutMs <= 0) return false",
        "new": "    if (timeoutMs <= 0) return true",
        "expect": ["超时窗口为零时直接判没变化且不取指纹", "负超时按零处理"],
    },
    # ---------------- 出站守卫（2026-11-19）：这批变异是为了钉住「求值顺序」 ----------------
    {
        # 恢复 2026-11-17 之前的写法的等价物：敏感分支先返回。
        # 后果不是「多拦」也不是「少拦」，而是四条 web_* 外发通道**整段架空**——
        # 而 11-17 那版的注释仍然写着「比 web_search 更静默」，读代码的人不会怀疑。
        "id": "M28 双身份工具被敏感分支提前返回（架空的 web_open）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": "        if (name !in egressTools) return GuardDecision.Abstain",
        "new": "        if (name !in egressTools || name in sensitiveTools) return GuardDecision.Abstain",
        "expect": ["读过文件之后打开网页也会被拦下", "双身份的外发工具一个都不能被敏感分支架空"],
    },
    {
        # 用「当前」而不是「进入前」判：干净回合里 web_open 先把自己标脏，
        # 于是被自己拦住——浏览网页需要点一次「允许」，之后每次都要点。
        "id": "M29 用当前污染状态判外发（自己拦自己）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": "        if (!wasTainted) return GuardDecision.Abstain",
        "new": "        if (!tainted.get()) return GuardDecision.Abstain",
        "expect": ["干净回合里打开网页不算外发"],
    },
    {
        # 忘记标脏：读完文件再搜索照旧放行。这是原 bug 的反面——
        # 修了「拦不住 web_open」却把「read_file → web_search」放掉了。
        "id": "M30 敏感工具不标脏（整条守卫失效）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": """        if (name in sensitiveTools) {
            // 先标记再判：读本身不是危险动作，危险的是读完之后的那一次外发。
            tainted.set(true)
        }""",
        "new": """        if (name in sensitiveTools) {
            // 变异：忘记标脏
        }""",
        "expect": ["读过文件之后搜索会被拦下", "敏感工具一个都不能漏标"],
    },
    {
        # 用户点了「允许」也照拦：弹窗变成摆设，用户会以为按钮坏了。
        "id": "M31 忽略用户同意（永远拒绝）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": "        if (consent(name, ctx.call.arguments)) return GuardDecision.Abstain",
        "new": "        if (consent(name, ctx.call.arguments) && false) return GuardDecision.Abstain",
        "expect": ["用户同意后放行且拿到原始参数"],
    },
    {
        # 新回合不清账：上个回合读过的东西会一路拦到会话结束。
        # 用户重新发一句「查一下天气」也被拦，且原因完全不可见。
        "id": "M32 新回合不清账",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": """    override fun onTurnStart() {
        tainted.set(false)
    }""",
        "new": """    override fun onTurnStart() {
        // 变异：新回合不清账
    }""",
        "expect": ["新回合清账"],
    },
    {
        # 用链默认的 3 秒：弹窗还在、守卫先超时，用户点「允许」却拿到 guard_timeout。
        "id": "M33 问人的守卫沿用链默认超时",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": "    override val timeoutMs: Long get() = CONSENT_TIMEOUT_MS",
        "new": "    override val timeoutMs: Long get() = ToolGuardChain.GUARD_TIMEOUT_MS",
        "expect": ["守卫自己的超时不能被链的 3 秒掐掉"],
    },
    {
        # 反向错法：污染之后连「继续读」也拦。看着更保守，实际让模型无法取证——
        # 而「保守」是最容易被当成正确的错法，所以要有一条断言专门钉它。
        "id": "M34 污染后连敏感工具一起拦（过度拦截）",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/EgressGuard.kt",
        "old": "        if (name !in egressTools) return GuardDecision.Abstain",
        "new": """        if (name in sensitiveTools && wasTainted) return GuardDecision.Deny(code = "over", message = "over")
        if (name !in egressTools) return GuardDecision.Abstain""",
        "expect": ["污染之后继续读文件不被拦"],
    },
    {
        "id": "M35 替换不按长度降序（短 Key 是长 Key 前缀时漏尾巴）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "            .sortedByDescending { it.length }",
        "new": "            .sortedBy { it.length }",
        "expect": ["按长度降序排列", "短 Key 是长 Key 前缀时不留尾巴"],
    },
    {
        "id": "M36 去掉形状兜底（只认配置里那几把）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "    private val PATTERNS: List<Rule> = listOf(",
        "new": """    private val PATTERNS: List<Rule> = emptyList()

    private val UNUSED_PATTERNS: List<Rule> = listOf(""",
        "expect": ["OpenAI 形状的 Key 被抹掉", "URL query 里的 token 被抹掉"],
    },
    {
        "id": "M37 八位 Key 不整体抹（take4+takeLast4 等于原样输出）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "        if (k.length <= MIN_SECRET_LEN) return MASK",
        "new": "        if (k.length < MIN_SECRET_LEN) return MASK",
        "expect": ["八位及以下整体抹掉"],
    },
    {
        "id": "M38 不 trim（空白参与首尾截取）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "        val k = key.trim()",
        "new": "        val k = key",
        "expect": ["首尾空白不参与遮蔽", "空 Key 显示为未配置"],
    },
    {
        "id": "M39 短值也当凭据（把「short」这种普通词一起抹）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "            .filter { it.length >= MIN_SECRET_LEN }",
        "new": "            .filter { it.isNotEmpty() }",
        "expect": ["短值不当作凭据"],
    },
    {
        "id": "M40 只替换第一次出现（同一把 Key 的后续出现漏掉）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "            if (s.isNotEmpty() && out.contains(s)) out = out.replace(s, maskKey(s))",
        "new": "            if (s.isNotEmpty() && out.contains(s)) out = out.replaceFirst(s, maskKey(s))",
        "expect": ["同一把 Key 出现多次全部替换"],
    },
    {
        "id": "M41 JSON 字段连名一起抹（排障看不出往哪带了 token）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": "        ) { it.groupValues[1] + MASK + it.groupValues[3] },",
        "new": "        ) { MASK },",
        "expect": ["JSON 字段只抹值不抹名"],
    },
    {
        # 反向变异：只防「抹不干净」会让实现越来越宽，最后把诊断包抹成一片星号。
        # 排障时最需要的恰是参数——过度脱敏与不脱敏一样让这件工具失去意义。
        "id": "M42 形状放宽到「任何 8 位字母数字」（过度脱敏）",
        "group": "redaction",
        "file": "core/src/main/java/com/beichen/polaris/core/DiagnosticsRedaction.kt",
        "old": '''        Rule(Regex("""\\bsk-[A-Za-z0-9_\\-]{16,}""")) { MASK },''',
        "new": '''        Rule(Regex("""[A-Za-z0-9]{8,}""")) { MASK },''',
        "expect": ["UUID 与时间戳不被误伤", "普通 JSON 参数不被误伤"],
    },
    # —— 第十二轮：本地模型的工具调用解析 ————————————————————————————
    {
        "id": "P1 嵌套形态只认名字不给参数（= 修前行为）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        argsIn(target.holder)?.let { return it }
        val dropKey = if (target.nested) "name" else target.nameKey""",
        "new": """        argsIn(target.holder)?.takeIf { !target.nested }?.let { return it }
        val dropKey = target.nameKey""",
        "expect": ["function 形态要带出 arguments"],
    },
    {
        "id": "P2 不认 tool_calls 数组",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        val calls = obj["tool_calls"] as? JsonArray
        if (calls != null) {
            for (e in calls) {
                val fn = (e as? JsonObject)?.get("function") as? JsonObject ?: continue
                nameIn(fn, available)?.let { (nk, n) -> return Target(fn, nk, n, nested = true) }
            }
        }""",
        "new": """        val calls: JsonArray? = null""",
        "expect": ["tool_calls 数组取第一个"],
    },
    {
        "id": "P3 不认 function_call 形态",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": '''    private val NEST_KEYS = listOf("function", "function_call")''',
        "new": '''    private val NEST_KEYS = listOf("function")''',
        "expect": ["function_call 形态"],
    },
    {
        "id": "P4 尾逗号修复不区分字符串内外（= 修前行为，改内容）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        t = replaceOutsideStrings(t, TRAILING_COMMA) { it.groupValues[1] }""",
        "new": """        t = t.replace(TRAILING_COMMA) { it.groupValues[1] }""",
        "expect": [
            "内容里的 逗号加右花括号 不被当成尾逗号",
            "内容里的 逗号加右方括号 不被当成尾逗号",
        ],
    },
    {
        "id": "P5 Python 字面量修复不区分字符串内外（= 修前行为，改内容）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        t = replaceOutsideStrings(t, PY_TRUE) { "true" }""",
        "new": """        t = t.replace(PY_TRUE, "true")""",
        "expect": ["内容里的 True 不被改成小写"],
    },
    {
        "id": "P6 只取第一个花括号（= 修前行为，解说里的括号让调用作废）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """            out.add(text.substring(i, end + 1))
            i = end + 1""",
        "new": """            out.add(text.substring(i, end + 1))
            break""",
        "expect": ["先解释再调用时取到的是 JSON 而不是解说里的括号"],
    },
    {
        "id": "P7 单引号不统一成双引号（值里带引号）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        if (!t.contains('"')) t = t.replace('\\'', '"')""",
        "new": """        if (!t.contains('"') && false) t = t.replace('\\'', '"')""",
        "expect": ["整段用单引号定界时换成双引号"],
    },
    {
        # 反向变异：只防「丢参数」会让实现越来越激进，把合法的参数名也剔掉。
        "id": "P8 平铺兜底把 name 一律剔掉（丢 use_skill 的技能名）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        val dropKey = if (target.nested) "name" else target.nameKey""",
        "new": '''        val dropKey = "name"''',
        "expect": ["参数平铺时其余顶层键都算参数"],
    },
    {
        "id": "P9 名字里的括号不截断（read_file(path=...) 认不出）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """            val cleaned = raw.substringBefore('(').substringBefore(' ').trim()""",
        "new": """            val cleaned = raw.trim()""",
        "expect": ["混写 名字带括号 时括号后面的丢掉"],
    },
    {
        "id": "P10 表外名字照单全收（模型瞎编什么就执行什么）",
        "group": "parser",
        "file": "core/src/main/java/com/beichen/polaris/core/llm/local/LocalToolCallParser.kt",
        "old": """        if (raw in available) return raw""",
        "new": """        return raw""",
        "expect": ["表外的名字一律不返回"],
    },
    # —— 二维码（本轮）：每条都对应一个「编错了也不会抛异常」的位置 ——
    {
        "id": "Q1 格式信息第二份按 8+8 格写（= 修前行为，位 7 无家可归）",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        for (i in 0..7) m.set(8, size - 1 - i, bits[14 - i] == 1)
        for (i in 8..14) m.set(size - 15 + i, 8, bits[14 - i] == 1)""",
        "new": """        for (i in 0..7) m.set(size - 1 - i, 8, bits[i] == 1)
        for (i in 8..14) m.set(8, size - 15 + i, bits[i] == 1)""",
        "expect": ["编码结果的两份格式信息逐位相同", "编码结果与参考实现逐格相同"],
    },
    {
        "id": "Q3 容量按 dataCodewords 减 2（把 20 位头当 16 位）",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        val spec = VERSIONS.last().spec(level)
        val headerBits = 4 + 16
        return (spec.dataCodewords * 8 - headerBits) / 8""",
        "new": """        return VERSIONS.last().spec(level).dataCodewords - 2""",
        "expect": ["容量按 16 位计数头算"],
    },
    {
        "id": "Q4 异常消息写死 L 档",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        Exception("payload $bytes bytes exceeds max capacity (v13/${if (level == 0) "L" else "M"})")""",
        "new": """        Exception("payload $bytes bytes exceeds max capacity (v13/L)")""",
        "expect": ["异常里的档位与真实档位一致"],
    },
    {
        "id": "Q5 暗模块写成浅色（定位基准丢了）",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        m.set(size - 8, 8, true) // 暗模块恒深""",
        "new": """        m.set(size - 8, 8, false) // 暗模块恒深""",
        "expect": ["暗模块恒为深", "编码结果与参考实现逐格相同"],
    },
    {
        "id": "Q6 时序图形奇偶取反",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """                m.set(6, i, i % 2 == 0)
                m.set(i, 6, i % 2 == 0)""",
        "new": """                m.set(6, i, i % 2 == 1)
                m.set(i, 6, i % 2 == 1)""",
        "expect": ["时序图形逐格交替", "编码结果与参考实现逐格相同"],
    },
    {
        "id": "Q7 对齐图形不跳过与定位图形重叠的三个角",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """                if ((r == 6 && c == 6) || (r == 6 && c == centers.last()) ||
                    (r == centers.last() && c == 6)) continue""",
        "new": """                if (false) continue""",
        "expect": ["编码结果与参考实现逐格相同"],
    },
    {
        "id": "Q8 格式信息位序整体反转（MSB/LSB 搞错）",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        val bits = IntArray(15) { fmt shr (14 - it) and 1 }""",
        "new": """        val bits = IntArray(15) { fmt shr it and 1 }""",
        "expect": ["编码结果与参考实现逐格相同"],
    },
    {
        "id": "Q9 交织时块顺序反转",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """            blocks.forEach { blk -> if (i < blk.size) interleaved[p++] = blk[i] }""",
        "new": """            blocks.reversed().forEach { blk -> if (i < blk.size) interleaved[p++] = blk[i] }""",
        "expect": ["每个版本的块结构都能被独立解码器还原"],
    },
    {
        "id": "Q10 总选最后一个版本（永远 v13）",
        "group": "qrcode",
        "file": "core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt",
        "old": """        val ver = VERSIONS.firstOrNull { v ->""",
        "new": """        val ver = VERSIONS.lastOrNull { v ->""",
        "expect": ["长度一变化就选到能装下的最小版本", "编码结果与参考实现逐格相同"],
    },
    # —— 运行时并发原语（P1 第一轮）：每条都对应一个「许可/互斥/代次号静默失效」 ——
    {
        "id": "R1 同键互斥去掉（同一个人能同时说两句话）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """        val gate = turnGates.computeIfAbsent(key(session, source)) { Semaphore(1) }
        gate.acquire()  // 同键第二个在这里挂起""",
        "new": """        val gate = turnGates.computeIfAbsent(key(session, source)) { Semaphore(1) }
        // gate.acquire() 变异：同键不互斥""",
        "expect": ["同 session 同 source 的第二个回合挂起"],
    },
    {
        "id": "R2 backend 并发上限改成无限（本地推理可被交错 prefill）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """            permits[backend] = Semaphore(limit)""",
        "new": """            permits[backend] = Semaphore(Int.MAX_VALUE)""",
        "expect": ["local 上限 1 第二个挂起", "cloud 上限 2 第三个挂起"],
    },
    {
        "id": "R3 cancel 不自增代次号（旧事件不会过期）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """        gen.incrementAndGet()""",
        "new": """        gen.get()""",
        "expect": ["代次号从 0 起 cancel 自增"],
    },
    {
        "id": "R4 未知 backend 静默放行（拼错的 backend 名不报错）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """        val sem = permits[backend]
            ?: throw IllegalArgumentException(
                "未知 backend：$backend（已登记：${permits.keys.sorted().joinToString()}）")
        return sem.withPermit { block() }""",
        "new": """        val sem = permits[backend] ?: Semaphore(1)
        return sem.withPermit { block() }""",
        "expect": ["未知 backend 直接抛异常"],
    },
    {
        "id": "R5 lease 归还时计数漏减（观察面永远以为有回合在跑）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """                if (released.compareAndSet(false, true)) {
                    active.decrementAndGet()
                    gate.release()
                }""",
        "new": """                if (released.compareAndSet(false, true)) {
                    // active.decrementAndGet() 变异：归还时计数漏减
                    gate.release()
                }""",
        "expect": ["activeTurns 随 acquire 与 close 增减"],
    },
    {
        "id": "R6 所有键共用一个门（异键也串行，把两级并发压回单线程）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """        val gate = turnGates.computeIfAbsent(key(session, source)) { Semaphore(1) }""",
        "new": """        val gate = turnGates.computeIfAbsent("__global__") { Semaphore(1) }""",
        "expect": ["不同 session 不互相阻塞", "同 session 不同 source 也不互相阻塞"],
    },
    {
        "id": "R7 shared 每次都 new（进程级单例被拆成各自为政）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/AgentRuntime.kt",
        "old": """        fun shared(): AgentRuntime = shared

        private val shared: AgentRuntime by lazy { of() }""",
        "new": """        fun shared(): AgentRuntime = of()

        private val shared: AgentRuntime by lazy { of() }""",
        "expect": ["shared 返回同一实例——进程内许可池只有一个真源"],
    },
    # —— 执行权收口（P1 第四轮）：每条都对应一类「接到 runtime 上但接错了」 ——
    {
        "id": "R8 send 拿到租约立刻归还（队列层对生产路径失效）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
        "old": """        val held = runtime.acquireTurn(session, source)
        gate.bind(held)""",
        "new": """        val held = runtime.acquireTurn(session, source).also { it.close() }
        gate.bind(held)""",
        "expect": ["同 session 的第二个 send 排队"],
    },
    {
        "id": "R9 事件不按代次过滤（旧回合的迟到事件污染新回合）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
        "old": """        val stale = runtime.generation(session) != held.generation
        if (stale && !isCloseout(event)) return""",
        "new": """        // 变异：不按代次过滤，事件一律透传""",
        "expect": ["cancel 后旧回合的开始类事件被丢弃"],
    },
    {
        "id": "R10 收尾事件也按代次丢弃（取消后工具卡永久执行中，破 T05）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
        "old": """        if (stale && !isCloseout(event)) return""",
        "new": """        if (stale) return""",
        "expect": ["cancel 后旧回合的收尾类事件仍放行"],
    },
    {
        "id": "R11 send 结束不归还租约（一次失败永久锁死该入口）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
        "old": """            gate.bind(null)
            held.close()""",
        "new": """            gate.bind(null)""",
        "expect": ["delegate 抛异常仍归还回合"],
    },
    {
        "id": "R12 未绑定回合时事件照样透传（send 之外的事件流无人管）",
        "group": "runtime",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/RuntimeBoundAgentLoop.kt",
        "old": """        val held = lease ?: return""",
        "new": """        val held = lease ?: run { sink?.invoke(event); return }""",
        "expect": ["未绑定回合时事件不转发"],
    },
    # —— 重试退避（2026-11-22）：三条都对应「静默烧钱 / 静默卡死」 ——
    {
        "id": "M43 通用退避越界不封顶（返回 0，回合会在同一步骤里无限重试）",
        "group": "retry",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RetrySchedule.kt",
        "old": "        return BACKOFF_SEQUENCE_MS.getOrElse(index) { MAX_MS }.coerceAtLeast(0L)",
        "new": "        return BACKOFF_SEQUENCE_MS.getOrElse(index) { 0L }.coerceAtLeast(0L)",
        "expect": ["B2 越界取末项封顶"],
    },
    {
        "id": "M44 限流退避忽略服务端 Retry-After（服务端说要等，它照旧撞上去）",
        "group": "retry",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RetrySchedule.kt",
        "old": "        return maxOf(base, serverAsk).coerceAtMost(MAX_RATE_LIMIT_MS)",
        "new": "        return base.coerceAtMost(MAX_RATE_LIMIT_MS)",
        "expect": ["R2 服务端要得更久时取大值"],
    },
    {
        "id": "M45 限流退避不封顶（坏 Retry-After 能把回合卡死几分钟）",
        "group": "retry",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RetrySchedule.kt",
        "old": "        return maxOf(base, serverAsk).coerceAtMost(MAX_RATE_LIMIT_MS)",
        "new": "        return maxOf(base, serverAsk).coerceAtMost(Long.MAX_VALUE)",
        "expect": ["R3 限流退避封顶 60s"],
    },
    {
        "id": "M46 限流序列退化成通用序列（= hotfix10 修前行为，连着再挨 429）",
        "group": "retry",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RetrySchedule.kt",
        "old": "    val RATE_LIMIT_SEQUENCE_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L)",
        "new": "    val RATE_LIMIT_SEQUENCE_MS: List<Long> = listOf(3_000L, 10_000L, 25_000L)",
        "expect": ["R1 限流序列每一档都比通用序列更久"],
    },
    {
        "id": "M47 turn.end 的 steps 少算一步（末步号当步数，时间线尾部少一格）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "                turnId, currentStep(), elapsed(), status, currentStep() + 1, scope",
        "new": "                turnId, currentStep(), elapsed(), status, currentStep(), scope",
        "expect": ["T3"],
    },
    {
        "id": "M48 turn.end 丢掉 scope（子代理的事件被记到主代理名下）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "                turnId, currentStep(), elapsed(), status, currentStep() + 1, scope",
        "new": "                turnId, currentStep(), elapsed(), status, currentStep() + 1, \"\"",
        "expect": ["T3"],
    },
    {
        "id": "M49 step() 返回旧步号（调用方拿到的步号与已记录的不一致）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "    fun step(n: Int): Int {\n        lastStep = n\n        return n\n    }",
        "new": "    fun step(n: Int): Int {\n        val prev = lastStep\n        lastStep = n\n        return prev\n    }",
        "expect": ["T2"],
    },
    {
        "id": "M50 观察者异常穿透 emit（观测方一抛，回合就跟着炸）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "        runCatching { observer.onEvent(event) }",
        "new": "        observer.onEvent(event)",
        "expect": ["T5"],
    },
    {
        "id": "M51 回合状态默认值变成 failed（没设过状态时把成功回合记成失败）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "    var value: String = \"completed\"",
        "new": "    var value: String = \"failed\"",
        "expect": ["T6"],
    },
    {
        "id": "M52 取消路径的时长退回 0L（用户等了多久又变成不可见）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt",
        "old": "        return (System.currentTimeMillis() - began).coerceAtLeast(0L)",
        "new": "        return 0L",
        "expect": ["T7"],
    },
    {
        "id": "M53 日志短 id 退回 6 位（同批并行调用又全部打印成 call_0）",
        "group": "trace",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/CallIdShort.kt",
        "old": "    if (id.isEmpty()) \"-\" else id.take(SHORT_CALL_ID_LEN)",
        "new": "    if (id.isEmpty()) \"-\" else id.take(6)",
        "expect": ["T8"],
    },
    {
        "id": "M54 时间拆解丢掉时区偏移（21:45 又显示成 13:45）",
        "group": "chattime",
        "file": "core/src/main/java/com/beichen/polaris/core/text/ChatTime.kt",
        "old": "        val totalSec = Math.floorDiv(ms + offsetMs, 1000L)",
        "new": "        val totalSec = Math.floorDiv(ms, 1000L)",
        "expect": ["CT1", "CT2", "CT3"],
    },
    {
        "id": "M55 周几按 UTC 天数算（跨日那一晚标错星期）",
        "group": "chattime",
        "file": "core/src/main/java/com/beichen/polaris/core/text/ChatTime.kt",
        "old": "        val days = Math.floorDiv(ms + offsetMs, DAY)",
        "new": "        val days = Math.floorDiv(ms, DAY)",
        "expect": ["CT3"],
    },
    {
        "id": "U1 未装模型不再优先（没下模型的人被推去授权，授完还是用不了）",
        "group": "ui",
        "file": "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
        "old": "        !installed -> VoiceInputNext.TellInstallModel\n        granted -> VoiceInputNext.OpenPanel",
        "new": "        granted -> VoiceInputNext.OpenPanel\n        !installed -> VoiceInputNext.TellInstallModel",
        "expect": ["V1"],
    },
    {
        "id": "U2 问过之后再点仍弹框（系统不再弹，用户永远拿不到权限）",
        "group": "ui",
        "file": "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
        "old": "        asked -> VoiceInputNext.GoSettings",
        "new": "        asked -> VoiceInputNext.RequestPermission",
        "expect": ["V4"],
    },
    {
        "id": "U3 已授权却送去设置页（能用了还折腾用户）",
        "group": "ui",
        "file": "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
        "old": "        granted -> VoiceInputNext.OpenPanel",
        "new": "        granted -> VoiceInputNext.GoSettings",
        "expect": ["V2"],
    },
    {
        "id": "U4 首次点击不弹框直接跳设置（等于把授权流程整个跳过）",
        "group": "ui",
        "file": "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
        "old": "        else -> VoiceInputNext.RequestPermission",
        "new": "        else -> VoiceInputNext.GoSettings",
        "expect": ["V3"],
    },
    {
        "id": "U5 未装模型时去开面板（面板里只剩一行红字，就是旧实现的死路）",
        "group": "ui",
        "file": "app/src/main/java/com/beichen/polaris/ui/VoiceInputDecision.kt",
        "old": "        !installed -> VoiceInputNext.TellInstallModel",
        "new": "        !installed -> VoiceInputNext.OpenPanel",
        "expect": ["V5"],
    },
    {
        "id": "QT1 0 步被当成合法上限（repeat(0) 一步不跑，回合直接空转）",
        "group": "quota",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
        "old": "            maxSteps = if (quota.maxSteps > 0) quota.maxSteps else fallbackSteps,",
        "new": "            maxSteps = if (quota.maxSteps >= 0) quota.maxSteps else fallbackSteps,",
        "expect": ["QT1"],
    },
    {
        "id": "QT2 兜底步数写死 60（换了入口仍按主界面的上限跑）",
        "group": "quota",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
        "old": "            maxSteps = if (quota.maxSteps > 0) quota.maxSteps else fallbackSteps,",
        "new": "            maxSteps = if (quota.maxSteps > 0) quota.maxSteps else 60,",
        "expect": ["QT1"],
    },
    {
        "id": "QT3 0 预算不落兜底（回合一到点就被判超时）",
        "group": "quota",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
        "old": "            deadlineMs = if (quota.deadlineMs > 0L) quota.deadlineMs else fallbackDeadlineMs,",
        "new": "            deadlineMs = if (quota.deadlineMs >= 0L) quota.deadlineMs else fallbackDeadlineMs,",
        "expect": ["QT1"],
    },
    {
        "id": "QT4 预算的兜底借了步数的值（两项串线，7 毫秒就超时）",
        "group": "quota",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
        "old": "            deadlineMs = if (quota.deadlineMs > 0L) quota.deadlineMs else fallbackDeadlineMs,",
        "new": "            deadlineMs = if (quota.deadlineMs > 0L) quota.deadlineMs else fallbackSteps.toLong(),",
        "expect": ["QT1"],
    },
    {
        "id": "QT5 兜底非正不再拦（调用方传 0，静默变成一步不跑）",
        "group": "quota",
        "file": "core/src/main/java/com/beichen/polaris/core/runtime/QuotaResolver.kt",
        "old": "        require(fallbackSteps > 0) {",
        "new": "        require(true) {",
        "expect": ["QT6"],
    },
    # ---------------------------------------------------------------- prompt 组
    {
        "id": "PT1 记忆段被标成 FROZEN（记忆一变，整段前缀作废）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "            out += Section(KEY_MEMORY, summary, Stability.TURN)",
        "new": "            out += Section(KEY_MEMORY, summary, Stability.FROZEN)",
        "expect": ["P7", "P6"],
    },
    {
        "id": "PT2 没记忆也拼 memory_usage（白占上下文，分走小模型注意力）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "        if (summary.isNotEmpty()) {",
        "new": "        if (true) {",
        "expect": ["P5"],
    },
    {
        "id": "PT3 工具段标成 TURN（工具面变了指纹却不变，缓存喂旧工具）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": '            out += Section(KEY_TOOLS, hints.joinToString("\\n\\n"), Stability.SESSION)',
        "new": '            out += Section(KEY_TOOLS, hints.joinToString("\\n\\n"), Stability.TURN)',
        "expect": ["P9", "P2"],
    },
    {
        "id": "PT4 不过滤空白提示（空段留下连续空行，前缀逐字节不同）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "        val hints = toolHints.filter { it.isNotBlank() }",
        "new": "        val hints = toolHints",
        "expect": ["P3"],
    },
    {
        "id": "PT5 身份不判空（空白身份也占一段）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "        if (identity.isNotBlank()) {",
        "new": "        if (true) {",
        "expect": ["P4"],
    },
    {
        "id": "PT6 记忆不 trim（纯空白记忆被当成有记忆）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "        val summary = memorySummary?.trim().orEmpty()",
        "new": "        val summary = memorySummary.orEmpty()",
        "expect": ["P5"],
    },
    {
        "id": "PT7 记忆引导被抬进 SESSION（引导随身一变，前缀跟着作废）",
        "group": "prompt",
        "file": "core/src/main/java/com/beichen/polaris/core/SystemPromptPlan.kt",
        "old": "            out += Section(KEY_MEMORY_USAGE, memoryUsagePrompt, Stability.TURN)",
        "new": "            out += Section(KEY_MEMORY_USAGE, memoryUsagePrompt, Stability.SESSION)",
        "expect": ["P6"],
    },
    # --------------------------------------------------------------- request 组
    {
        "id": "RV1 先压历史再剥图（按 900 token 的图算体积，白丢一步结果）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": """        val pruned = ContextBudget.pruneImages(
            messages,
            keepLast = keepRecentImages,
            anchorIndex = anchorIndex
        )
        val fitted = ContextBudget.fitToBudget(
            pruned,
            budgetTokens = budgetTokens,
            maxMessages = maxMessages
        )
        return RequestView(
            messages = fitted,
            // 条数比较用 pruned 而不是 messages：剥图不改条数，拿 messages 比也成立，
            // 但用 pruned 能把「上一段真的动了手」这件事留在比较里，语义更直白。
            dropped = fitted.size < pruned.size,""",
        "new": """        val preFitted = ContextBudget.fitToBudget(
            messages,
            budgetTokens = budgetTokens,
            maxMessages = maxMessages
        )
        val fitted = ContextBudget.pruneImages(
            preFitted,
            keepLast = keepRecentImages,
            anchorIndex = anchorIndex
        )
        return RequestView(
            messages = fitted,
            dropped = fitted.size < messages.size,""",
        "expect": ["V3"],
    },
    {
        "id": "RV2 锚点没透传（退回首条 UserMessage，本轮输入的图被剥）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": "            anchorIndex = anchorIndex",
        "new": "            anchorIndex = null",
        "expect": ["V4"],
    },
    {
        "id": "RV3 dropped 恒 false（丢了历史却报没丢，日志与 UI 都静默）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": "            dropped = fitted.size < pruned.size,",
        "new": "            dropped = false,",
        "expect": ["V2"],
    },
    {
        "id": "RV4 条数上限不透传（maxMessages 形同虚设）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": "            maxMessages = maxMessages",
        "new": "            maxMessages = Int.MAX_VALUE",
        "expect": ["V6"],
    },
    {
        "id": "RV5 folded 恒 false（折叠了却说是丢弃，排障看错方向）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": "            folded = fitted.any { it is UserMessage && it.content.startsWith(ContextBudget.FOLD_MARK) }",
        "new": "            folded = false",
        "expect": ["V5"],
    },
    {
        "id": "RV6 保留张数写死 2（配置 0 也不剥，预算照爆）",
        "group": "request",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/RequestView.kt",
        "old": "            keepLast = keepRecentImages,",
        "new": "            keepLast = 2,",
        "expect": ["V3"],
    },
    {
        "id": "TS1 视图永不收紧（被裁工具照常执行，裁剪白做）",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        if (!plan.trimmed) return full",
        "new": "        if (true) return full",
        "expect": ["S2", "S4"],
    },
    {
        "id": "TS2 裁剪判定反转（该收紧时给全量，该给全量时反而收紧）",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        if (!plan.trimmed) return full",
        "new": "        if (plan.trimmed) return full",
        "expect": ["S1", "S4"],
    },
    {
        "id": "TS3 视图里滤掉元工具（模型再也开不了组）",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        return ToolRegistry().apply { plan.visible.forEach { register(it) } }",
        "new": "        return ToolRegistry().apply { plan.visible.filterNot { it.name.startsWith(\"__\") }.forEach { register(it) } }",
        "expect": ["S3"],
    },
    {
        "id": "TS4 新建视图改成就地塞进全量（视图不收紧且污染全量）",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        return ToolRegistry().apply { plan.visible.forEach { register(it) } }",
        "new": "        return full.apply { plan.visible.forEach { register(it) } }",
        "expect": ["S2", "S4"],
    },
    {
        "id": "TS5 裁剪与否从 visible 数量反推，不信 plan.trimmed",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        if (!plan.trimmed) return full",
        "new": "        if (plan.visible.size >= full.names().size) return full",
        "expect": ["S6"],
    },
    {
        "id": "TS6 只注册第一个可见工具（其余被裁工具一起消失）",
        "group": "toolscope",
        "file": "core/src/main/java/com/beichen/polaris/core/agent/ToolScope.kt",
        "old": "        return ToolRegistry().apply { plan.visible.forEach { register(it) } }",
        "new": "        return ToolRegistry().apply { plan.visible.take(1).forEach { register(it) } }",
        "expect": ["S2", "S3"],
    },
    {
        # 这是 W-31 的原始形态：读到当前值、在调用方算好、整体写回。单线程下完全等价，
        # 并发下丢掉交错期间别人的写入——用户看到「刚说的话没了」，且没有任何报错。
        "id": "CS1 edit 退化成非原子读改写（W-31 复发）",
        "group": "composer",
        "file": "app/src/main/java/com/beichen/polaris/ui/ComposerState.kt",
        "old": "        _input.casUpdate(transform)",
        "new": "        _input.value = transform(_input.value)",
        "expect": ["并发 edit 不丢更新"],
    },
    {
        # 只读不清：发送时「拿走了」但输入区还留着，附件会随下一次发送重复带上。
        "id": "CS2 takeAttachments 只读不清（附件留在输入区、下次重复发）",
        "group": "composer",
        "file": "app/src/main/java/com/beichen/polaris/ui/ComposerState.kt",
        "old": "        images = _pendingImages.drain(),",
        "new": "        images = _pendingImages.value,",
        "expect": ["takeAttachments 取走并清空"],
    },
    {
        # 与 CS1 同构，但走图片路径：PDF 转图与相册选图并发时「选了三张只进去两张」。
        "id": "CS3 addImages 退回两步写（并发选图丢图）",
        "group": "composer",
        "file": "app/src/main/java/com/beichen/polaris/ui/ComposerState.kt",
        "old": "        _pendingImages.casUpdate { it + urls }",
        "new": "        _pendingImages.value = _pendingImages.value + urls",
        "expect": ["并发 addImages 不丢图片"],
    },
]


# ------------------------------------------------------------------ 依赖探测

def _first(paths):
    hits = [p for p in paths if p and os.path.isfile(p) and "-sources" not in p]
    return hits[-1] if hits else None


def _glob_jar(*patterns):
    """按 pattern 的先后顺序取第一个命中的 jar，命中内部按名字取最新。

    不能把所有 pattern 的结果混在一起排序取最后一个：`/tmp/gcache/...` 与
    `/tmp/ci1925/...` 是两套不同版本的 Kotlin 生态，字典序会跨档选中 gcache 里的
    coroutines-1.10（元数据 2.2），用 1.9 编译器去读就报
    `module was compiled with an incompatible version of Kotlin`——
    错误文本以 jar 路径开头，截断后看起来像「文件不存在」，极难定位。

    ## 为什么要验「这真的是个 jar」（2026-11-19）

    CI 上 `curl -sSL` 少了 `-f`：HTTP 429/502 的错误页照样被写进 `.jar` 文件，
    于是「文件在、内容不是 jar」。编译器不会说「jar 坏了」，它说

        AgentContract.kt:3:16: error: unresolved reference: serialization

    ——一条看着像**源码写错了**的报错。实测代价：一次 CI 失败从头查到尾，
    先怀疑测试、再怀疑 classpath 顺序，最后才发现那个「jar」是 1KB 的 HTML。

    所以候选必须先过 `is_zipfile`；不合格的记进 [_BROKEN_JARS]，
    由 [find_classpath] 统一打出来——**跳过它但说出原因**，不静默。
    """
    for pat in patterns:
        hits = [
            p for p in glob.glob(pat, recursive=True)
            if os.path.isfile(p) and "-sources" not in p
        ]
        good = [p for p in hits if zipfile.is_zipfile(p)]
        _BROKEN_JARS.extend(p for p in hits if p not in good)
        if good:
            return sorted(good)[-1]
    return None


# 候选里「文件存在、但不是合法 zip」的路径。见 [_glob_jar] 的说明。
_BROKEN_JARS: list[str] = []


def _describe_broken(path):
    """给坏构件一句能直接定位的话：多大、开头几个字节像什么。"""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            head = f.read(24)
    except OSError as e:
        return f"{path}（读不了：{e}）"
    sniff = head[:5].decode("utf-8", "replace").replace("\n", " ")
    hint = "看起来是 HTML/错误页（下载时没加 -f）" if sniff.lstrip().startswith("<") else "不是 zip"
    return f"{path}（{size} 字节，开头 {sniff!r}，{hint}）"


def find_classpath():
    """返回 (编译器 classpath, 运行时 classpath, 额外编译参数)。找不到就 None（调用方 SKIP）。"""
    cache = os.environ.get("QA_GRADLE_CACHE") or os.path.join(
        os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1"
    )
    kc = os.environ.get("KOTLINC_HOME") or "/tmp/kc"

    compiler = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "**", "kotlin-compiler-embeddable-*.jar"),
        os.path.join(kc, "lib", "kotlin-compiler*.jar"),
    )
    stdlib = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlin", "kotlin-stdlib", "**", "kotlin-stdlib-[0-9]*.jar"),
        os.path.join(kc, "lib", "kotlin-stdlib.jar"),
    )
    reflect = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlin", "kotlin-reflect", "**", "kotlin-reflect-*.jar"),
        os.path.join(kc, "lib", "kotlin-reflect.jar"),
    )
    annot = _glob_jar(
        os.path.join(cache, "org.jetbrains", "annotations", "**", "annotations-*.jar"),
        os.path.join(kc, "lib", "annotations.jar"),
    )
    coro = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "**", "kotlinx-coroutines-core-jvm-*.jar"),
        # 官方 kotlinc 发行版自带这个 jar（CI 走这一档）；漏掉它时守卫链测试会在
        # 编译阶段报 unresolved reference: runBlocking，看起来像测试写错了。
        os.path.join(kc, "lib", "kotlinx-coroutines-core-jvm*.jar"),
        "/tmp/gcache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*.jar",
        os.path.join(os.path.expanduser("~"), ".sdkman", "candidates", "gradle", "*", "lib", "kotlinx-coroutines-core-jvm-*.jar"),
    )
    ser_core = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "**", "*.jar"),
        os.path.join(kc, "lib", "kotlinx-serialization-core-jvm.jar"),
    )
    ser_json = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "**", "*.jar"),
        os.path.join(kc, "lib", "kotlinx-serialization-json-jvm.jar"),
    )
    # trove4j：1.9.x 的 kotlin-compiler-embeddable 在编译器**自己的 classpath** 上需要它
    # （K2 的集合实现用到 gnu.trove.THashMap）。缺了不会说「缺 jar」，而是
    #   NoClassDefFoundError: gnu/trove/THashMap
    # 并且整段错误以源码最后一行开头（`Tool.kt:167)`），看起来像语法错。
    # 官方 kotlinc 发行版的 lib/ 里自带，Gradle 缓存里在 org.jetbrains.intellij.deps 下。
    trove = _glob_jar(
        os.path.join(cache, "org.jetbrains.intellij.deps", "trove4j", "**", "trove4j-*.jar"),
        os.path.join(kc, "lib", "trove4j.jar"),
        os.path.join(os.path.expanduser("~"), ".sdkman", "candidates", "gradle", "*", "lib", "trove4j-*.jar"),
    )
    # 插件的版本必须与编译器一致（同一处坑见 run-r10-shard 的注释）：先查缓存里
    # embeddable 那一档，再退 KOTLINC_HOME。反过来（kc 优先）会拿 2.x 的插件去喂
    # 1.9 的编译器，报的是 `KotlinFrontEndException: Front-end Internal error`，
    # 完全不提版本。
    ser_plugin = _glob_jar(
        os.path.join(cache, "org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "**", "*.jar"),
        os.path.join(kc, "lib", "kotlinx-serialization-compiler-plugin*.jar"),
    )
    junit = _glob_jar(
        os.path.join(cache, "junit", "junit", "**", "junit-*.jar"),
        "/tmp/junit/junit-*.jar",
    )
    hamcrest = _glob_jar(
        os.path.join(cache, "org.hamcrest", "hamcrest-core", "**", "*.jar"),
        "/tmp/junit/hamcrest-core-*.jar",
    )

    missing = [
        name for name, val in [
            ("kotlin-compiler-embeddable", compiler), ("kotlin-stdlib", stdlib),
            ("kotlinx-serialization-json", ser_json), ("junit", junit),
        ] if not val
    ]
    if missing:
        print(f"SKIP  缺少构件：{', '.join(missing)}")
        if _BROKEN_JARS:
            # 这一档最容易被误诊：文件在、路径对，报错却是「unresolved reference: serialization」，
            # 看着像源码写错。所以坏构件必须点名。
            print("      其中以下候选**文件在但不是合法 jar**（已跳过）：")
            for p in _BROKEN_JARS:
                print(f"        - {_describe_broken(p)}")
            print("      → 重新下载（用 `curl -fL`，别让错误页冒充 jar）。")
        print("      → 本脚本什么都没验证。装好 kotlinc 或用 KOTLINC_HOME/QA_GRADLE_CACHE 指路后重跑。")
        return None

    compiler_cp = ":".join(x for x in [compiler, stdlib, reflect, annot, coro, trove] if x)
    # coro 同时要给运行时：守卫链（ToolGuardChainTest）里 runBlocking / delay 是
    # 被测代码的一部分，只在编译器 classpath 上会让 JUnit 报 NoClassDefFoundError。
    lib_cp = ":".join(x for x in [stdlib, coro, ser_core, ser_json, junit, hamcrest] if x)
    plugin = ["-Xplugin=" + ser_plugin] if ser_plugin else []
    # 把「这一轮到底用的哪几个 jar」打出来。CI 日志里没有这一行时，任何失败都要
    # 从头猜 classpath；有这一行，跨版本串档 / 少一个 jar 一眼可见。
    print("构件：" + " ".join(
        f"{os.path.basename(p)}({os.path.getsize(p) // 1024}KB)"
        for p in [compiler, stdlib, ser_json, ser_core, coro, junit, ser_plugin] if p
    ))
    return compiler_cp, lib_cp, plugin


# ------------------------------------------------------------------- 编译执行

def expand_sources(entries, overrides=None):
    """目录展开成 *.kt，文件原样保留（顺序稳定，便于复现）。

    overrides 把「工作区里的真实路径」映射到「临时副本」，用于变异：
    编译器只认路径，所以副本可以放在任何目录——包名由文件里的 package 声明决定，
    与目录结构无关。
    """
    overrides = overrides or {}
    out = []
    for e in entries:
        full = os.path.join(ROOT, e)
        if os.path.isdir(full):
            # 目录展开出来的每一项也要过 overrides：有变异点落在目录项里时
            # （如 telemetry），只替换文件项会让那条变异静默失效——
            # old 能匹配上、编译的却是原文件，于是测试全绿、结论 SURVIVED。
            for f in sorted(glob.glob(os.path.join(full, "*.kt"))):
                out.append(overrides.get(f, f))
        else:
            out.append(overrides.get(full, full))
    return out


def compile_and_run(compiler_cp, lib_cp, plugin, outdir, sources, classes,
                    overrides=None) -> tuple[str, str]:
    """返回 (编译错误, JUnit 输出)。编译失败时第二项为空。"""
    java = shutil.which("java") or "java"
    cmd = [
        java, "-cp", compiler_cp,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", *plugin,
        "-cp", lib_cp, "-d", outdir,
        *expand_sources(sources, overrides),
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        errs = [l for l in (r.stdout + r.stderr).splitlines() if re.search(r"\berror:", l)]
        return ("\n".join(errs[:5]) or (r.stdout + r.stderr)[-500:]), ""

    try:
        run = subprocess.run(
            [java, "-cp", f"{outdir}:{lib_cp}", "org.junit.runner.JUnitCore", *classes],
            capture_output=True, text=True, timeout=JUNIT_TIMEOUT_SEC,
        )
    except subprocess.TimeoutExpired:
        # 测试死锁（而不是红）：判为 INVALID，报告指向「这条变异让测试挂起」。
        # 不能让超时拖垮全量门禁——一条死锁变异卡 10 分钟，其余变异全白跑。
        return "JUnit 超时（测试疑似死锁，见 JUNIT_TIMEOUT_SEC）", ""
    return "", run.stdout + run.stderr


def parse_failures(out: str) -> tuple[int, int, list[str]]:
    """解析 JUnitCore 输出。全绿时**没有** `Tests run:` 行，只有 `OK (N tests)`。

    这个区别曾经让「变异存活」被报成 `INVALID: JUnit 没有输出 Tests run 行`——
    结论同样是失败，但理由指向「测试没跑起来」，而真相是「测试跑了、全绿、
    所以没人抓到缺陷」。看报告的人会去修环境，而不是去补断言。
    """
    m = re.search(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", out)
    if m:
        names = re.findall(r"^\d+\)\s+(.+?)\(com\.", out, re.MULTILINE)
        return int(m.group(1)), int(m.group(2)), names
    ok = re.search(r"^OK \((\d+) tests?\)", out, re.MULTILINE)
    if ok:
        return int(ok.group(1)), 0, []
    return (-1, -1, [])


def main() -> int:
    verbose = "--verbose" in sys.argv
    strict = "--strict" in sys.argv
    # --only <子串>：只跑 id 命中的变异。改一条变异定义时不必等全量六分钟，
    # 但**基线照跑**——只挑一条跑的时候更容易忘记「基线红 = 结论全假」。
    only = None
    if "--only" in sys.argv:
        idx = sys.argv.index("--only")
        only = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else ""
    # --group <组名>：只跑一组。基线与变异一起收窄——只为调一个新组时，
    # 没必要把五组的编译都过一遍。**收窄了就必须说出收窄了**：结尾那行汇总
    # 会打印「只跑了 X 组」，免得把一份片面的报告当成全量结论。
    only_group = None
    if "--group" in sys.argv:
        idx = sys.argv.index("--group")
        only_group = sys.argv[idx + 1] if idx + 1 < len(sys.argv) else ""
        if only_group not in GROUPS:
            print(f"没有这个组：{only_group}（可选：{', '.join(sorted(GROUPS))}）")
            return 2
    deps = find_classpath()
    if deps is None:
        if strict:
            print("变异门禁：严格模式下缺构件视为失败（本次没有验证任何东西）")
            return 1
        return 0  # SKIP 不失败：缺环境不该阻塞本地开发，但输出已明确说「什么都没验证」
    compiler_cp, lib_cp, plugin = deps

    # ---------------------------------------------------------------- 基线
    #
    # 必须先跑一遍**未变异**的代码，且必须全绿。
    #
    # 没有这一步时，一条本来就会红的断言（编译通过、跑起来失败）会让**每一条**变异
    # 都显示 CAUGHT——门禁报「34/34 全部被捕获」，而结论在撒谎。它比不跑更坏：
    # 给出的是一份看起来可信的假报告。这与 `--strict` 是同一件事的两面：
    # 缺环境不装绿，坏基线也不装绿。
    for name in sorted(GROUPS):
        if only_group is not None and name != only_group:
            continue
        group = GROUPS[name]
        outdir = tempfile.mkdtemp(prefix="mut-base-")
        try:
            err, out = compile_and_run(compiler_cp, lib_cp, plugin, outdir, group["sources"], group["classes"])
        finally:
            shutil.rmtree(outdir, ignore_errors=True)
        if err:
            print(f"基线编译失败（{name} 组）—— 什么都没验证：")
            print(err)
            return 1
        total, nfail, names = parse_failures(out)
        if total < 0:
            print(f"基线没有输出 Tests run 行（{name} 组）—— 什么都没验证")
            return 1
        if nfail:
            print(f"基线就是红的（{name} 组：{nfail}/{total} 失败）：{', '.join(names[:5])}")
            print("先修红再看变异结论——基线红时每一条变异都会「CAUGHT」，那是假的。")
            return 1
        print(f"基线 OK（{name}）：{total} 条断言全绿")

    rows, failed = [], 0
    for mut in MUTATIONS:
        if only_group is not None and mut.get("group", "approval") != only_group:
            continue
        if only is not None and only not in mut["id"]:
            continue
        target = os.path.join(ROOT, mut["file"])
        original = open(target, encoding="utf-8").read()
        if original.count(mut["old"]) != 1:
            rows.append((mut["id"], "INVALID", f"匹配 {original.count(mut['old'])} 处（应恰好 1 处）"))
            failed += 1
            continue
        try:
            # 变异写在**临时副本**上，工作区零改动。
            #
            # 此前是直接改原文件、靠 finally 恢复。finally 挡不住 SIGKILL：超时被
            # `timeout` 杀、Ctrl-C 按两下、CI 取消 job，都会把变异留在工作区。
            # 而它留下的是一种**最坏的脏**——注入的缺陷看起来是正常代码，下一轮
            # commit 顺手推上去，门禁全绿（断言本来就是对着变异版本写的），
            # 直到真机上那条防护失效才被发现。
            sandbox = tempfile.mkdtemp(prefix="mut-src-")
            patched = os.path.join(sandbox, os.path.basename(target))
            with open(patched, "w", encoding="utf-8") as f:
                f.write(original.replace(mut["old"], mut["new"]))
            outdir = tempfile.mkdtemp(prefix="mut-")
            group = GROUPS[mut.get("group", "approval")]
            err, out = compile_and_run(
                compiler_cp, lib_cp, plugin, outdir, group["sources"], group["classes"],
                overrides={target: patched},
            )
            shutil.rmtree(outdir, ignore_errors=True)
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)

        if err:
            rows.append((mut["id"], "INVALID", err.splitlines()[0][:90]))
            failed += 1
            continue

        total, nfail, names = parse_failures(out)
        if total < 0:
            rows.append((mut["id"], "INVALID", "JUnit 没有输出 Tests run 行"))
            failed += 1
            continue

        expect = mut["expect"]
        caught = [e for e in expect if any(n.startswith(e) for n in names)]
        if not expect:
            # 没有登记期望 → 只报告，不判失败；这是「已知空洞」的显式记账
            status = "NO-EXPECT" if nfail == 0 else "CAUGHT"
            detail = f"红 {nfail} 条：{', '.join(names[:4]) or '无'}"
        elif nfail == 0:
            status, detail = "SURVIVED", f"一条都没红（期望 {', '.join(expect)}）"
            failed += 1
        elif len(caught) < len(expect):
            status = "PARTIAL"
            detail = f"只红 {', '.join(caught) or '无关项'}（缺 {', '.join(e for e in expect if e not in caught)}）"
            failed += 1
        else:
            status, detail = "CAUGHT", f"{total} 条中红 {nfail} 条：{', '.join(names[:4])}"

        rows.append((mut["id"], status, detail))
        if verbose:
            print(f"\n--- {mut['id']} ---\n{out[-1500:]}")

    print()
    print(f"{'变异':<42} {'结论':<10} 说明")
    print("-" * 110)
    for rid, status, detail in rows:
        print(f"{rid:<42} {status:<10} {detail}")

    print()
    if failed:
        print(f"变异门禁：{failed} 条未通过（断言没有判别力，或变异定义失效）")
        return 1
    if only_group is not None or only is not None:
        # 收窄跑必须自报收窄，否则「N 条全部被捕获」会被读成全量结论
        scope = f"组 {only_group}" if only_group else f"id 含「{only}」"
        print(f"变异门禁：{len(rows)} 条全部被捕获（**只跑了{scope}，不是全量**）")
        return 0
    print(f"变异门禁：{len(rows)} 条全部被捕获")
    return 0


if __name__ == "__main__":
    sys.exit(main())
