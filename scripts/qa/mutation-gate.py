#!/usr/bin/env python3
"""审批判定的变异门禁（L0）。

## 解决的问题

仓库里 150 个 `run-*.sh` 中，约 575 处是 `grep -q` 文本断言。它们能证明「源码里有这行字」，
不能证明「这行字起了作用」——删掉实现、反转判断，grep 照样绿。2026-11-18 的实测里，
5 个关键守卫的变异全部存活（或整段 SKIP）。

本脚本用最原始也最可靠的办法回答「断言有没有判别力」：**把缺陷注入回去，看测试红不红**。
只覆盖已经迁到 JVM 的审批判定（`ShellCommandRulesTest`），因为变异测试的前提是
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
    python3 scripts/qa/mutation-gate.py [--verbose] [--strict]
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
            "core/src/test/java/com/beichen/polaris/core/agent/ShellCommandRulesTest.kt",
            "core/src/test/java/com/beichen/polaris/core/agent/ApprovalTierTest.kt",
            "core/src/test/java/com/beichen/polaris/core/tool/ToolCatalogTest.kt",
        ],
        "classes": [
            "com.beichen.polaris.core.agent.ShellCommandRulesTest",
            "com.beichen.polaris.core.agent.ApprovalTierTest",
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
]


# ------------------------------------------------------------------ 依赖探测

def _first(paths):
    hits = [p for p in paths if p and os.path.isfile(p) and "-sources" not in p]
    return hits[-1] if hits else None


def _glob_jar(*patterns):
    out = []
    for pat in patterns:
        out.extend(glob.glob(pat, recursive=True))
    out = [p for p in out if "-sources" not in p]
    return sorted(out)[-1] if out else None


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
    ser_plugin = _glob_jar(os.path.join(kc, "lib", "kotlinx-serialization-compiler-plugin*.jar"))
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

    compiler_cp = ":".join(x for x in [compiler, stdlib, reflect, annot, coro] if x)
    lib_cp = ":".join(x for x in [stdlib, ser_core, ser_json, junit, hamcrest] if x)
    plugin = ["-Xplugin=" + ser_plugin] if ser_plugin else []
    return compiler_cp, lib_cp, plugin


# ------------------------------------------------------------------- 编译执行

def expand_sources(entries):
    """目录展开成 *.kt，文件原样保留（顺序稳定，便于复现）。"""
    out = []
    for e in entries:
        full = os.path.join(ROOT, e)
        if os.path.isdir(full):
            out.extend(sorted(glob.glob(os.path.join(full, "*.kt"))))
        else:
            out.append(full)
    return out


def compile_and_run(compiler_cp, lib_cp, plugin, outdir, sources, classes) -> tuple[str, str]:
    """返回 (编译错误, JUnit 输出)。编译失败时第二项为空。"""
    java = shutil.which("java") or "java"
    cmd = [
        java, "-cp", compiler_cp,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", *plugin,
        "-cp", lib_cp, "-d", outdir,
        *expand_sources(sources),
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
    m = re.search(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", out)
    if not m:
        return (-1, -1, [])
    names = re.findall(r"^\d+\)\s+(.+?)\(com\.", out, re.MULTILINE)
    return int(m.group(1)), int(m.group(2)), names


def main() -> int:
    verbose = "--verbose" in sys.argv
    strict = "--strict" in sys.argv
    deps = find_classpath()
    if deps is None:
        if strict:
            print("变异门禁：严格模式下缺构件视为失败（本次没有验证任何东西）")
            return 1
        return 0  # SKIP 不失败：缺环境不该阻塞本地开发，但输出已明确说「什么都没验证」
    compiler_cp, lib_cp, plugin = deps

    rows, failed = [], 0
    for mut in MUTATIONS:
        target = os.path.join(ROOT, mut["file"])
        original = open(target, encoding="utf-8").read()
        if original.count(mut["old"]) != 1:
            rows.append((mut["id"], "INVALID", f"匹配 {original.count(mut['old'])} 处（应恰好 1 处）"))
            failed += 1
            continue
        try:
            open(target, "w", encoding="utf-8").write(original.replace(mut["old"], mut["new"]))
            outdir = tempfile.mkdtemp(prefix="mut-")
            group = GROUPS[mut.get("group", "approval")]
            err, out = compile_and_run(
                compiler_cp, lib_cp, plugin, outdir, group["sources"], group["classes"]
            )
            shutil.rmtree(outdir, ignore_errors=True)
        finally:
            open(target, "w", encoding="utf-8").write(original)

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
