# 北辰 · Polaris

> **接手这个仓库？先读 [`HANDOFF.md`](HANDOFF.md)。**
> 那里写清了：现在做到哪、下一步做什么（桌宠一期）、代码红线、环境坑、怎么跑验证。
> 不要凭 README 描述猜进度——README 讲能力，HANDOFF 讲状态。

Android 本地 AI Agent —— Kotlin / Jetpack Compose，原创实现，四层分离架构。

一个跑在手机上的 AI 助手：多轮工具调用、三家大模型协议原生适配、屏幕理解与操控、
终端与容器能力、跨会话长期记忆。**零第三方新增依赖**，视觉与交互按自研规范落地。

> 产品名**北辰**（Polaris）。工程内部沿用的 `AgentKit` 与包名 `com.beichen.polaris` 是历史代号，
> 不出现在桌面、系统设置或 App 内任何位置，仅为兼容已装应用升级链而保留。

> 规模：Kotlin 源码 **360 文件 / 86,759 行**（`app/src/main` + `core/src/main`）｜ QA runner **141 个**（其中 Kotlin harness 103 个）｜ 版本号 = CI 构建号（`1.N`）
>
> 数字按 2026-11-13 的 `1939aa2` 统计，会随提交漂移，别当契约看：`find app/src/main core/src/main -name '*.kt' | wc -l` 自己数一遍更快。
>
> 工程是两个 Gradle 模块：`:core`（内核：Agent 循环 / 工具协议 / 会话存储 / 终端 / 模型客户端 / 技能执行）
> 与 `:app`（界面与装配）。`:core` 不依赖 Compose，也不 import `ui` / `tools`——这条边界由编译器守，
> 不再只是 arch guard 的文本检查。

---

## 文档索引

| 你要找什么 | 看这里 |
|---|---|
| **第一次用** | [使用说明](docs/使用说明.md) · [网页版](https://kdkdmwnwdkd.github.io/Polaris/guide.html) |
| **这个项目现在什么样** | [现状 · 路线图 · 踩坑记录](docs/architecture/AgentKit-现状与路线图-2026-10-16.md)：做了什么 / 在做什么 / **踩过的坑逐条（现象→根因→修法→防复发）** |
| **架构为什么这么切** | [架构方案 v2](docs/architecture/AgentKit-架构方案-v2-2026-11-06.md)：六条评审线的现状结论、缺陷分级与改造顺序 |
| **参考了谁、借了什么** | [参照谱系](docs/architecture/AgentKit-参照谱系-2026-11-05.md) · [第三方声明](THIRD_PARTY_NOTICES.md) |
| **交互 / 视觉规范** | [UX 规范](docs/specs/agentkit-ux-spec-v1.md) · [视觉规范 v3](docs/specs/agentkit-visual-spec-v3.md) |
| **历史周报与交付记录** | [`docs/archive/`](docs/archive/)：W-46~W-87 周报、各轮模拟器实测与交付报告 |

目录总览见 [`docs/README.md`](docs/README.md)。研究材料见 [`docs/research/`](docs/research/)（参照仓库的能力面扫描，只读思路、独立实现）。

---

## 能力一览

- **模型接入**：30+ 家供应商预设（DeepSeek / Kimi / 智谱 / 通义 / 豆包 / 文心 / 混元 / 星火 / MiniMax / 阶跃 / 百川 / Yi / 商汤 / Grok / Mistral / Groq / OpenRouter / 硅基流动 / Ollama / LM Studio / vLLM …），协议三类（OpenAI 兼容 / Claude / Gemini）+ 本地 llama.cpp；未收录端点一律 OpenAI 兼容兜底
- **对话体验**：流式打字机（含**非 SSE 供应商自动降级**）、思考过程折叠（三家推理字段原生解析）、消息复制/朗读/分享/从此处删除、文件与图片附件、Token 用量统计、会话置顶/搜索/草稿/导出 `.md` 真文件
- **会话与记忆**：生成中可切会话（后台续跑）、记忆库（列表/图谱双模式、可编辑、节点可拖动）、跨会话事件记忆沉淀
- **外观**：主题三选 + Material You 动态取色 + **全局照片背景**（浓度 5%–100% 自调，全 App 生效）
- **系统能力**：屏幕捕捉（真机实证）、shell 终端、无障碍 App 控制、本地模型推理、全局备份/恢复（含提示词库）
- **运行成本**：57 个工具**按组加载**——默认只带 `file` 组 3 个，模型用 `__use_tools` 自己开组、开过就一直开着（粘性）；设置页「工具范围」可手动关闭。全量 22360 字符 ≈ 10055 token，默认面 1243 字符 ≈ 525 token（省 94%）
- **稳定性**：JSON 空值安全化、429 专用退避（尊重 `Retry-After`）、断网自动恢复、崩溃日志留存

---

## 构建与验证

```bash
# 编译（干净构建；沙箱/CI 环境建议带 --rerun-tasks 确保完整校验）
bash gradlew :app:compileDebugKotlin --rerun-tasks

# 源码级 QA（主 harness；断言条数会随代码增长，跑完自己看结果行）
bash scripts/qa/run-hotfix11-verification.sh

# 工具面成本审计（真实工具描述，不是 harness 里的假数据）
python3 scripts/tool-surface-audit.py --verbose

# 全套回归（123 个 harness；数字以 scripts/qa/harness-baseline.txt 为准）
bash scripts/qa/run-all.sh
```

> harness 是**纯源码扫描**（无 JUnit、无模拟器），随代码入库；产品变更时同步更新断言并在注释写明理由。

---

## 工程纪律

1. 每轮交付闭环：干净编译 → 主 harness + 全套回归 → 提交（现象/根因/修法/证据）→ 推送 → CI 绿 → 产物**二进制反验**（哈希 · versionName · dex 符号）
2. **零新增外部依赖**（背景图解码、通知、动态取色均自写）
3. **净室重写**：参照仓库只读思路、不抄代码
4. **失败永不崩**：外部数据入口一律降级（JSON 安全访问、分享降级、通知静默降级）
5. **前向兼容**：落盘格式新增字段一律带默认值

---

## 仓库

- 地址：`https://github.com/Kdkdmwnwdkd/Polaris`（分支 `main`）
- APK 产物：Release tag `latest`（CI 自动维护 `app-release.apk`，release 变体，`debuggable=false`）
- 加速直链：`https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/Polaris/releases/download/latest/app-release.apk`
