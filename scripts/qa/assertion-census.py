#!/usr/bin/env python3
"""断言体检：统计每个 harness 的**文本断言**条数，与基线比对（只减不增）。

## 为什么要有这个

架构方案 P0 立过一条规矩：「新断言必须调用被测函数，禁止新增 `src.contains`」。
但规矩没有执行者——这几轮重构（P4 拆 ChatViewModel、R11 schema 单点、R12 原子写）
每改一次实现，就有十几条 `assertContains("...", vm, "...")` 变红，
修法都是「把断言改到新位置」。它们守的其实不是行为，而是**某段代码长什么样**。

后果不是「红」，是**假绿**：把某段实现从 A 文件搬到 B 文件，断言跟着搬过去仍然绿——
它从来没验证过任何行为。更糟的是它把「重构」的代价抬到「改 20 条断言」，
于是没人愿意重构。

这里不追求清零（存量 1300 多条，清完要两周且风险高于收益），
只做一件事：**冻结**。每个文件的文本断言数只准往下。

## 什么算文本断言

对源码字符串做匹配，而不是调用被测函数：

- bash：`expect` / `fixed` / `refute` / `has` / `hasnt`（本质都是 grep 源码）
- Kotlin：`assertContains(...)`、`xxx.contains("字面量")`

行为断言（`eq(` / `check(cond)` 里真调了函数）不计数。

## 精度

`\.contains\("` 会把 harness 里对**集合**的 contains 也算进来（比如
`activated.contains("misc")`）。这类误算存在，但它是**恒定**的：
基线按同一把尺子量，只增不减的判定不受影响。要更精确就得做语法分析，
收益不抵成本——这个脚本的目的是拦住趋势，不是给每行代码定性。

## 用法

    python3 scripts/qa/assertion-census.py            # 比对基线，超了就退出 1
    python3 scripts/qa/assertion-census.py --print    # 打印当前值（改基线时用）

基线文件：`scripts/qa/assertion-baseline.txt`
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
QA = ROOT / "scripts/qa"
BASELINE = QA / "assertion-baseline.txt"

# bash：断言辅助函数的调用行（函数定义 `expect() {` 不匹配，因为没有引号参数）
# `has` / `hasnt` 是后几轮 harness 引入的同义辅助函数，早先漏在统计之外：
# 它们同样只 grep 源码字面量，不调用被测函数。漏计不是「少写了断言」，
# 是「尺子量不到」，会让这些文件的增量完全不受冻结约束。
BASH_TEXT = re.compile(r"^\s*(expect|fixed|refute|has|hasnt)\s+['\"]", re.M)
# Kotlin：assertContains(...) 与 带字面量的 .contains("...")
KT_TEXT = re.compile(r"""assertContains\(|\.contains\(""")


def count_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix == ".sh":
        return len(BASH_TEXT.findall(text))
    if path.suffix == ".kt":
        return len(KT_TEXT.findall(text))
    return 0


def census() -> dict[str, int]:
    """相对仓库根的文件名 → 文本断言数（只为有计数的文件留条目）。"""
    out: dict[str, int] = {}
    for p in sorted(QA.glob("*.sh")) + sorted(QA.glob("*.kt")):
        # 把自己排除掉：注释里引用了 expect / assertContains 会自增，很荒唐
        if p.name == "assertion-census.py":
            continue
        n = count_file(p)
        if n:
            out[str(p.relative_to(ROOT))] = n
    return out


def load_baseline() -> dict[str, int]:
    caps: dict[str, int] = {}
    if not BASELINE.exists():
        return caps
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) == 3 and parts[0] == "text":
            caps[parts[1]] = int(parts[2])
    return caps


def main(argv: list[str]) -> int:
    now = census()

    if "--print" in argv:
        total = sum(now.values())
        for path, n in sorted(now.items(), key=lambda kv: -kv[1]):
            print(f"text {path} {n}")
        print(f"# 合计 {total} 条 / {len(now)} 个文件", file=sys.stderr)
        return 0

    caps = load_baseline()
    if not caps:
        print("❌ 缺少基线文件 scripts/qa/assertion-baseline.txt", file=sys.stderr)
        print("   先跑 `python3 scripts/qa/assertion-census.py --print` 生成。", file=sys.stderr)
        return 1

    problems: list[str] = []

    # 1) 单文件不得超过基线
    for path, n in sorted(now.items()):
        cap = caps.get(path)
        if cap is None:
            problems.append(
                f"{path}: 新增文件带 {n} 条文本断言 → 新 harness 必须写行为断言。"
                f"确实只能静态检查的（比如「某文件不得再出现 X」），"
                f"先把它减到 0 条再申请加基线条目。"
            )
        elif n > cap:
            problems.append(
                f"{path}: {n} 条 > 基线 {cap} 条（+{n - cap}）→ "
                f"要么改成调用被测函数，要么说明为什么只能看源码，再抬基线。"
            )

    # 2) 总量：防止「把 625 条拆成两个文件」绕过单文件上限
    total_cap = caps.get("__TOTAL__")
    total_now = sum(now.values())
    if total_cap is not None and total_now > total_cap:
        problems.append(f"总量 {total_now} 条 > 基线 {total_cap} 条（+{total_now - total_cap}）")

    # 3) 基线里记着、但文件里已经变少的：提示可以下调（不算失败）
    lowered = [
        (p, caps[p], now[p])
        for p in caps
        if p != "__TOTAL__" and p in now and now[p] < caps[p]
    ]

    print(f"文本断言：{total_now} 条 / {len(now)} 个文件")
    if lowered:
        print("可下调（不影响结果，顺手改一下基线能让上限跟着收紧）：")
        for p, cap, n in sorted(lowered):
            print(f"  {p}: {cap} → {n}")
    stale = [p for p in caps if p != "__TOTAL__" and p not in now]
    if stale:
        print("基线里有、文件已不存在或已清零（同样建议删条目）：")
        for p in sorted(stale):
            print(f"  {p}")

    if problems:
        print(f"\n❌ {len(problems)} 处超标：")
        for p in problems:
            print("  - " + p)
        return 1

    print("✅ 文本断言只减不增")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
