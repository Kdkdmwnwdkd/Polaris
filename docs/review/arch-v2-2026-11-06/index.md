# 架构评审 v2 · 分线原始报告（2026-11-06）

汇总稿见 [`../../AgentKit-架构方案-v2-2026-11-06.md`](../../AgentKit-架构方案-v2-2026-11-06.md)。
本目录保留六条评审线的**未删改原始报告**，供复核与追溯——汇总稿里的每条判断都能在这里找到对应证据。

| 文件 | 评审线 | 范围 | 行数 |
|---|---|---|---|
| `01-engine.md` | 引擎与回合编排 | `core/agent/`、`core/llm/` | 177 |
| `02-ui.md` | UI 与状态所有权 | `ui/`、`ui/contract/`、投影与运行控制器 | 249 |
| `03-tools.md` | 工具 / 技能 / 能力面 | `core/tool/`、`tools/`、`core/capability/`、`skill/` | 161 |
| `04-runtime.md` | 系统执行与运行时 | `automation/`、`terminal/`、`core/container/`、`runtime/lease/` | 195 |
| `05-data.md` | 数据持久化与安全模型 | 存储 / 备份 / 凭据 / 路径沙箱 | 153 |
| `06-quality.md` | 质量体系 / 构建 / 纪律 | `scripts/qa/`、CI、文档漂移 | 163 |

## 读法

- 每条结论后标 **✔**（逐行核对源码）或 **○**（结构推断、未运行验证）。汇总稿只采纳 ✔ 进入风险登记册，○ 一律显式标注。
- 行号基准 `main@7dd0f88`；后续提交会使行号漂移，复核请按函数名/断言文本定位。
- 六份报告均为只读评审：未改产品代码、未跑 gradle（沙箱无 Android SDK）、未上真机。

## 可复现的现场统计

```bash
find app/src/main -name '*.kt' | wc -l                          # 301
find app/src/main -name '*.kt' -exec cat {} + | wc -l            # 75207
ls scripts/qa/run-*.sh | wc -l                                   # 113（含 run-all.sh）
python3 scripts/qa/assertion-census.py --print                   # 1729 条 / 67 文件
python3 scripts/tool-surface-audit.py --verbose                  # 57 工具 / 21,808 字符
bash scripts/qa/run-arch-guard-verification.sh                   # 5 通过
```

> 2026-11-09 重跑。断言数此前显示 1368 / 53 文件，是因为 `assertion-census.py` 只认
> `expect`/`fixed`/`refute`，漏掉了用 `has`/`hasnt` 写的 361 条；工具数 56 → 57 是新增 `knowledge_search`。
