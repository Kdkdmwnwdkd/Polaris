# AGENTS.md

本仓库的**状态入口**是 [`HANDOFF.md`](HANDOFF.md)。任何自动化助手在改动代码前先读它：
它写了现在做到哪、下一步做什么、环境坑在哪、以及怎么判断「改对了」。

## 三条硬约束（细节见 HANDOFF §4）

1. `:core` 不许 import `ui` / `tools` / Compose / `MainActivity`。
2. `scripts/qa/arch-baseline.txt` 与 `scripts/qa/harness-baseline.txt` **只减不增**。
3. 工具守卫的裁决只有 `Abstain` / `Deny`，**不许加 `Allow`**——只能拦不能放行，是这套设计的安全性质。

## 改动前先确认基线是绿的

```bash
bash scripts/qa/run-all.sh > /tmp/qa-full.log 2>&1; echo "exit=$?"
grep '^==== 汇总' /tmp/qa-full.log     # 期望 PASS=103 SKIP=38 XFAIL=0 FAIL=0
bash scripts/qa/gate.sh /tmp/qa-full.log # 期望 GATE PASS
```

汇总里出现 FAIL 时，先看是不是环境问题（HANDOFF §5 两个坑：CA 证书缺失、kotlinc 缺 `-kotlin-home`），
**不要**直接改代码去迁就失败。
