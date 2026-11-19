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
    python3 scripts/qa/mutation-gate.py [--verbose] [--strict] [--only <id 子串>]

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

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

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
}

# 被测源文件。刻意只挑这一小片：审批链拿得出 30 秒内跑完的 JVM 断言的只有它，
# 更广的覆盖面应该先写断言再谈变异，否则只是在测「grep 匹不匹配」。
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
    """
    for pat in patterns:
        hits = [
            p for p in glob.glob(pat, recursive=True)
            if os.path.isfile(p) and "-sources" not in p
        ]
        if hits:
            return sorted(hits)[-1]
    return None


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
        print("      → 本脚本什么都没验证。装好 kotlinc 或用 KOTLINC_HOME/QA_GRADLE_CACHE 指路后重跑。")
        return None

    compiler_cp = ":".join(x for x in [compiler, stdlib, reflect, annot, coro, trove] if x)
    # coro 同时要给运行时：守卫链（ToolGuardChainTest）里 runBlocking / delay 是
    # 被测代码的一部分，只在编译器 classpath 上会让 JUnit 报 NoClassDefFoundError。
    lib_cp = ":".join(x for x in [stdlib, coro, ser_core, ser_json, junit, hamcrest] if x)
    plugin = ["-Xplugin=" + ser_plugin] if ser_plugin else []
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

    run = subprocess.run(
        [java, "-cp", f"{outdir}:{lib_cp}", "org.junit.runner.JUnitCore", *classes],
        capture_output=True, text=True,
    )
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
    print(f"变异门禁：{len(rows)} 条全部被捕获")
    return 0


if __name__ == "__main__":
    sys.exit(main())
