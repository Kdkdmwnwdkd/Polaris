#!/usr/bin/env python3
"""工具面成本审计：直接读源码，量「真实工具的描述与参数」占多少字符 / token。

## 为什么要有这个脚本

`scripts/qa/KernelExposureVerification.kt` 里用的是**假描述**——每个工具统一写一句
约 90 字的模板话。它验的是「分组规则对不对」，这部分没问题；但它的报表里那几个
「全量 22167 字符 / 省 88%」的数字**不是真机的数字**，因为真实工具的 description
长短差得很远：`web_type` 一句话 30 字，`ui_screenshot` 带权限说明有 180 字。

假描述会把「短描述的组」估高、「长描述的组」估低。而 `ui` 组恰好全是长描述，
所以真实节省比例和 harness 报的不一样。

## 它做什么

静态解析 `app/src/main/java` 下所有 `override val name` 的工具，取出
`description`（含 `+` 拼接）与 `parameters`（`JsonSchema.prop` 的三元组），
按 `KernelExposure.groupOf` 的规则分组，然后：

1. 逐组列出工具数 / 字符 / token；
2. 算出各暴露面对应的总额（全量、默认、满载、最坏组合）；
3. 与 harness 的假数据口径对照，指出偏差方向。

token 口径与 `ContextBudget.estimateTokens` 一致：CJK 1 字符 1 token，
ASCII 4 字符 1 token，其余 2 字符 1 token。

## 局限（必须知道）

- 只解析字面量。`buildString {}` 与运行期拼装（`McpToolAdapter`）算不出，按 0 计
  并在报表末尾单列——MCP 工具的体量取决于用户连了哪个服务器，静态不可知。
- `$var` 插值按字面长度计（少算），量级上可忽略。
- 不改任何代码，只读。

用法：python3 scripts/tool-surface-audit.py [--verbose]
"""

import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
# 3.5 拆模块：工具类分布在 :app（tools 包）与 :core（skill / 终端等），两边都要扫。
SRC_APP = os.path.join(ROOT, "app", "src", "main", "java")
SRC_CORE = os.path.join(ROOT, "core", "src", "main", "java")
SRC = SRC_APP

# ---- 与 CapabilityManifest 保持一致的组规则 ---------------------------------
#
# 早前这里有两张**手抄**的表：GROUP_PATCH（前缀推不出来的归组）与 GROUP_NAMES
# （有哪些组）。抄的时候没人拦，于是 P3-3 给清单加了 `mcp` 组、这张表就再也没跟上——
# 与 `LocalAgentPrompt.ORDER` 那次是同一个病：同一份知识，两个副本，一个改一个不改。
#
# 现在直接从 `core/tool/ToolCatalog.kt` 解析：组、成员、顺序、常驻、动态全以目录为准。
# R-13 之前这份数据在 `CapabilityManifest.kt`，那里改成派生视图后，审计必须跟着搬到真源——
# 盯着视图算，等于在量一份已经不由人维护的副本。
# 目录写坏（重复登记、组没定义）时 `run-r13-catalog-verification.sh` 会先红，这里只管照着算。

CATALOG_KT = os.path.join(
    SRC_CORE, "com", "beichen", "polaris", "core", "tool", "ToolCatalog.kt")


def load_manifest(path: str):
    """解析目录 → (顺序化的组名列表, 组→成员, 常驻组集合, 动态组集合)。"""
    src = open(path, encoding="utf-8").read()
    groups, members, pinned, dynamic = [], {}, set(), set()

    # 组名可能是字面量（`"file"`），也可能是常量（`MISC`）。先把 `const val X = "y"` 收进来，
    # 否则兜底桶会被静默跳过——**静默跳过正是这次要消灭的那类 bug**。
    consts = dict(re.findall(r'const val (\w+)\s*(?::\s*String)?\s*=\s*"([a-z_]+)"', src))

    # 逐段切 `CatalogGroup(` 到与之配平的右括号（跳过 `data class CatalogGroup(` 这个声明）
    for m in re.finditer(r'(?<!class )CatalogGroup\(', src):
        if src[max(0, m.start() - 6):m.start()].endswith("class "):
            continue
        i, depth = m.end(), 1
        while i < len(src) and depth:
            depth += {"(": 1, ")": -1}.get(src[i], 0)
            i += 1
        block = src[m.end():i]
        head = re.match(r'\s*(?:"([a-z_]+)"|([A-Z][A-Za-z0-9_]*))\s*,', block)
        if not head:
            raise SystemExit("无法解析 CatalogGroup 的组名（工具面审计的解析器要跟着目录改）：%s"
                             % block[:60].replace("\n", " "))
        g = head.group(1) or consts.get(head.group(2), "")
        if not g:
            raise SystemExit("CatalogGroup 用了未知常量 %s（审计脚本解析不到它的值）" % head.group(2))
        groups.append(g)
        members[g] = []
        if re.search(r'pinned\s*=\s*true', block):
            pinned.add(g)
        if re.search(r'dynamic\s*=\s*true', block):
            dynamic.add(g)

    # 成员从 `CatalogTool("name", "group", ...)` 按出现顺序收集——顺序即工具表顺序，
    # 不能排序：排了就和真实发送给模型的工具表对不上，预算量得再准也没意义。
    for m in re.finditer(r'CatalogTool\(\s*"([a-z0-9_]+)"\s*,\s*"([a-z0-9_]+)"', src):
        members.setdefault(m.group(2), []).append(m.group(1))

    return groups, members, pinned, dynamic


GROUP_ORDER, GROUP_MEMBERS, PINNED, DYNAMIC = load_manifest(CATALOG_KT)

# 工具名 → 组（清单是精确成员表；misc 不在表内，它是兜底）
MEMBER_OF = {n: g for g, ns in GROUP_MEMBERS.items() for n in ns}

# 元工具（`__find_tools` / `__use_tools`）不属于任何业务组，单列
PINNED = PINNED | {"meta"}
MAX_ACTIVE_GROUPS = 2      # KernelExposure.MAX_ACTIVE_GROUPS
TRIM_TO_GROUPS = 1         # KernelExposure.TRIM_TO_GROUPS

# 预算门（--gate）的天花板，单位 token。与架构方案「度量」表同一口径：
# 默认面 ≤525（不退化）。满载按当前实测取整到 3500，留出的余量只够描述微调，
# 不够新塞一个工具——想涨必须显式改这里。
DEFAULT_TOKENS_BUDGET = 525
PEAK_TOKENS_BUDGET = 3500


def group_of(name: str) -> str:
    if name.startswith("__"):
        return "meta"
    if name in MEMBER_OF:
        return MEMBER_OF[name]
    prefix = name.split("_", 1)[0]
    return prefix if prefix in GROUP_MEMBERS else "misc"


def _arg_int(flag: str, default: int) -> int:
    """取 `--flag=123` 形式的整数参数（没有就用默认值）。"""
    for a in sys.argv:
        if a.startswith(flag):
            try:
                return int(a[len(flag):])
            except ValueError:
                raise SystemExit("%s 后面要跟整数，收到 %r" % (flag, a))
    return default


# ---- token 估算（与 ContextBudget.estimateTokens 同口径）---------------------

def estimate_tokens(text: str) -> int:
    cjk = ascii_ = other = 0
    for ch in text:
        o = ord(ch)
        if o < 0x80:
            ascii_ += 1
        elif 0x3000 <= o <= 0x30FF or 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF \
                or 0xAC00 <= o <= 0xD7AF or 0xFF00 <= o <= 0xFFEF:
            cjk += 1
        else:
            other += 1
    return cjk + (ascii_ + 3) // 4 + (other + 1) // 2


# ---- Kotlin 源码里的字面量 --------------------------------------------------

LITERAL = re.compile(r'\s*"((?:[^"\\]|\\.)*)"')
PLUS = re.compile(r'\s*\+')
ESCAPES = {"n": "\n", "t": "\t", '"': '"', "'": "'", "\\": "\\", "$": "$", "r": "\r"}


def unescape(raw: str) -> str:
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            out.append(ESCAPES.get(raw[i + 1], raw[i + 1]))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def read_concat(src: str, pos: int):
    """从 pos 开始读 `"a" + "b" + "c"` 这样的拼接，返回 (文本, 结束位置)。"""
    parts = []
    i = pos
    while True:
        m = LITERAL.match(src, i)
        if not m:
            break
        parts.append(unescape(m.group(1)))
        i = m.end()
        m2 = PLUS.match(src, i)
        if not m2:
            break
        i = m2.end()
    return "".join(parts), i


def schema_json(name: str, description: str, props) -> str:
    """按 OpenAI function-calling 报文结构拼一遍（与 harness 的 payloadChars 同构）。"""
    prop_parts = []
    for key, typ, desc in props:
        prop_parts.append(
            '{"type":"%s","description":"%s"}' % (typ, desc.replace('"', '\\"'))
        )
    return (
        '{"type":"function","function":{"name":"%s","description":"%s","parameters":'
        '{"type":"object","properties":{%s},"required":[%s]}}}'
        % (
            name,
            description.replace('"', '\\"'),
            ",".join('"%s":%s' % (k, v) for (k, _, _), v in zip(props, prop_parts)),
            ",".join('"%s"' % k for k, _, _ in props),
        )
    )


NAME_RE = re.compile(r'override\s+val\s+name\s*(?::\s*String)?\s*=\s*"([^"]*)"')
DESC_RE = re.compile(r'override\s+val\s+description\s*(?::\s*String)?\s*=')
PARAM_RE = re.compile(r'override\s+val\s+parameters\s*=')
SYS_RE = re.compile(r'override\s+val\s+systemPrompt\s*(?::\s*String)?\s*=')
PROP_RE = re.compile(r'JsonSchema\.prop\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*')


def parse_file(path: str):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    tools = []
    marks = [(m.start(), m.group(1)) for m in NAME_RE.finditer(src)]
    for idx, (start, name) in enumerate(marks):
        end = marks[idx + 1][0] if idx + 1 < len(marks) else len(src)
        block = src[start:end]

        description, dynamic_desc = "", False
        dm = DESC_RE.search(block)
        if dm:
            description, _ = read_concat(block, dm.end())
            if not description and "buildString" in block[dm.end():dm.end() + 80]:
                dynamic_desc = True

        # 工具自带的 systemPrompt：AgentLoop.buildSystemPrompt 会把它们拼进 SESSION 段，
        # 是**同一份开销的第二半**。只算 tools JSON 会漏掉这块，量出来偏小。
        hint = ""
        sm = SYS_RE.search(block)
        if sm:
            hint, _ = read_concat(block, sm.end())

        props = []
        pm = PARAM_RE.search(block)
        if pm:
            # 只在 parameters 表达式范围内找 prop（到下一个 override val 为止）
            tail = block[pm.end():]
            stop = re.search(r'override\s+val\s', tail)
            region = tail[:stop.start()] if stop else tail
            for m in PROP_RE.finditer(region):
                key, typ = m.group(1), m.group(2)
                desc, _ = read_concat(region, m.end())
                props.append((key, typ, desc))

        tools.append({
            "name": name,
            "description": description,
            "hint": hint,
            "props": props,
            "dynamic": dynamic_desc,
            "file": os.path.relpath(path, ROOT),
        })
    return tools


def main():
    verbose = "--verbose" in sys.argv

    all_tools = []
    for base in (SRC_APP, SRC_CORE):
        for dirpath, _, files in os.walk(base):
            for fn in files:
                if fn.endswith(".kt"):
                    all_tools.extend(parse_file(os.path.join(dirpath, fn)))

    # 只保留真实工具（MetaTool 的名字在 KernelExposure 里是构造参数，不会命中 NAME_RE）
    seen, uniq = set(), []
    for t in all_tools:
        if t["name"] in seen:
            continue
        seen.add(t["name"])
        uniq.append(t)

    dynamic = [t for t in uniq if t["dynamic"]]
    uniq = [t for t in uniq if not t["dynamic"]]

    by_group = OrderedDict()
    for t in uniq:
        t["payload"] = len(schema_json(t["name"], t["description"], t["props"]))
        t["hint_chars"] = len(t["hint"])
        # 总成本 = 工具表 JSON + 拼进系统提示的 systemPrompt 片段（段间 "\n\n" 计 2 字）
        t["chars"] = t["payload"] + (t["hint_chars"] + 2 if t["hint_chars"] else 0)
        t["tokens"] = estimate_tokens(
            schema_json(t["name"], t["description"], t["props"]) + ("\n\n" + t["hint"] if t["hint"] else ""))
        by_group.setdefault(group_of(t["name"]), []).append(t)

    total_chars = sum(t["chars"] for t in uniq)
    total_tokens = sum(t["tokens"] for t in uniq)
    total_payload = sum(t["payload"] for t in uniq)
    total_hint = sum(t["hint_chars"] for t in uniq)
    print("读取源码：%s + %s" % (os.path.relpath(SRC_APP, ROOT), os.path.relpath(SRC_CORE, ROOT)))
    print("解析到工具：%d 个（另有 %d 个动态构造，未计入）" % (len(uniq), len(dynamic)))
    # 清单是唯一真源：源码里有、清单里没有的工具会落进 misc（仍可见，但组索引里查不到）。
    # Kotlin 侧的 audit() 会报，这里再报一次——一个在启动日志里，一个在成本报表里，
    # 两边都能看见才不会被当成噪声忽略。
    unregistered = [t["name"] for t in uniq if t["name"] not in MEMBER_OF]
    if unregistered:
        print("⚠️  %d 个工具不在清单里（会落进 misc）：%s"
              % (len(unregistered), ", ".join(unregistered)))
    else:
        print("清单覆盖：%d 个工具全部登记（0 个落进 misc）" % len(uniq))
    print("成本构成：工具表 JSON %d 字 / 新增提示片段 %d 字（后者是可省的——不暴露就不拼）\n"
          % (total_payload, total_hint))

    print("%-10s %5s %8s %8s   %s" % ("组", "工具数", "字符", "token", "占全量"))
    print("-" * 62)
    for g, ts in sorted(by_group.items(), key=lambda kv: -sum(t["chars"] for t in kv[1])):
        c = sum(t["chars"] for t in ts)
        k = sum(t["tokens"] for t in ts)
        print("%-10s %5d %8d %8d   %5.1f%%" % (g, len(ts), c, k, c * 100.0 / total_chars))
    print("-" * 62)
    print("%-10s %5d %8d %8d   100%%" % ("合计", len(uniq), total_chars, total_tokens))

    if verbose:
        print("\n逐工具：")
        for g, ts in sorted(by_group.items()):
            for t in sorted(ts, key=lambda x: -x["chars"]):
                print("  %-10s %-22s %5d 字  %s" % (g, t["name"], t["chars"], t["file"]))

    # ---- 暴露面组合 ---------------------------------------------------------
    def surface(groups):
        ts = [t for t in uniq if group_of(t["name"]) in groups]
        return len(ts), sum(t["chars"] for t in ts), sum(t["tokens"] for t in ts)

    def line(label, groups, note=""):
        n, c, k = surface(groups)
        pct = 100 - c * 100.0 / total_chars
        print("  %-26s %3d 个 %7d 字 %6d token  省 %4.1f%%  %s"
              % (label, n, c, k, pct, note))

    removable = [g for g in by_group if g not in PINNED]
    worst = sorted(removable, key=lambda g: -sum(t["chars"] for t in by_group[g]))
    worst_pick = worst[:MAX_ACTIVE_GROUPS]

    print("\n暴露面对照（元工具约 500 字 / 250 token，未计入下列数字）：")
    line("全量（不分组的旧行为）", set(by_group))
    line("默认：file", {"file"})
    line("满载：file + 最重的 2 组", {"file"} | set(worst_pick),
         "= " + ",".join(["file"] + worst_pick))
    line("最坏（开着 misc）", {"file", "misc"} | set(worst_pick))

    # ---------- 预算门（--gate）----------
    #
    # 架构方案「度量」表里写着「工具面默认 token ≈525，目标 ≤525（不退化）」。那张表
    # 之前只是个数字：没人量，也就没人拦得住它退化。加一个工具、把某句描述从 30 字
    # 写成 120 字，默认面会涨，**而所有现有断言都不会响**——它们验的是「分组对不对」，
    # 不是「每轮要付多少钱」。
    #
    # 门槛故意卡在现值上（525 / 3500），不留余量：留了余量就等于允许「慢慢地、每次
    # 一点点」地涨。要涨可以，先改这两个常量，把代价写在提交信息里。
    if "--gate" in sys.argv:
        # 两个 override 只给 harness 用：它要证明「门真的会红」，否则这道门和装饰没区别。
        # 不接受环境变量——CI 里一个手滑的 env 就能把门关掉，那还不如没有。
        budgets = [
            ("默认面（云端只开 file）", {"file"}, _arg_int("--budget-default=", DEFAULT_TOKENS_BUDGET)),
            ("满载（file + 最重的 2 组）", {"file"} | set(worst_pick), _arg_int("--budget-peak=", PEAK_TOKENS_BUDGET)),
        ]
        print("\n预算门（--gate）：")
        bad = 0
        if unregistered:
            print("  ❌ 清单覆盖                                     %d 个工具未登记：%s"
                  % (len(unregistered), ", ".join(unregistered)))
            bad += 1
        for label, groups, budget in budgets:
            _, _, k = surface(groups)
            ok = k <= budget
            bad += 0 if ok else 1
            print("  %s %-28s %5d token ≤ %5d  %s"
                  % ("✅" if ok else "❌", label, k, budget, "" if ok else "（超 %d）" % (k - budget)))
        if bad:
            print("\n工具面预算超了 %d 项。工具表是每轮都要付的成本：要么压缩描述，"
                  "要么把该组从默认暴露面里挪走，要么显式抬高上面的预算常量并说明理由。" % bad)
            sys.exit(1)

    print("\n最重的可淘汰组排名：")
    for i, g in enumerate(worst[:6], 1):
        n, c, k = surface({g})
        print("  %d. %-10s %2d 个 %6d 字 %5d token" % (i, g, n, c, k))

    if dynamic:
        print("\n动态构造（运行时才知道体量）：")
        for t in dynamic:
            print("  %s  (%s)" % (t["name"], t["file"]))


if __name__ == "__main__":
    main()
