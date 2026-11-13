# 11 · DeepSeek Harness：一切皆插件（2026-11-13）

> 对象：`deepseek-ai/deepseek-harness`（`dsh`），2026-11-11 仍在更新，TypeScript，MIT。
> 定位：开源的 **agent harness（智能体框架）**，不是模型、不是 App。
> 标注：**[码]** 读过源码结构；**[档]** 读过官方文档（含中文版）。
> ⚠️ 官方自述处于 **开发者预览** 阶段，"未来将出现破坏兼容性的变更"。

---

## 0. 一句话

它把"做一个 Agent 产品"从**写循环**变成**组装插件**：模型适配器、工具注册表、会话日志、**连 agent loop 本身**都是插件，都可以被配置替换，没有需要打补丁的特权内核。

---

## 1. 它凭什么"更好做"

底层是 [Cordis](https://github.com/cordiverse/cordis)，设计来自论文 *A Programming Paradigm for Spatiotemporal Composability*（arXiv 2608.25512）。五个概念 **[档]**：

| 概念 | 含义 | 解决什么问题 |
|---|---|---|
| **插件 = 实现 Service 的对象** | 函数带 `inject` + `apply(ctx)`，或 `Service` 子类 | 功能单元有统一生命周期 |
| **上下文是服务容器** | 服务占稳定 `ctx.<key>`（`ctx.tools` / `ctx.llm` / `ctx.sessions`），别人按 key 找，不 import 实现 | 实现可换，调用方不改 |
| **`inject` 声明依赖** | 声明所需服务，等它就绪才启动 | 启动顺序由依赖表达，不手写编排 |
| **类型化事件** | `emit` / `waterfall` / `parallel` / `serial` / `bail` 五种分发 | 观察与拦截分离，语义明确 |
| **注册是可逆副作用** | `ctx.effect()` / `ctx.on()` 安装，reload/teardown 自动撤销 | 热插拔不泄漏 |

### 1.1 五种事件分发（这张表值得抄进我们的设计）

| 模式 | await | 返回值 | 典型用途 |
|---|---|---|---|
| `emit` | 否 | 无 | 通知、日志 |
| `waterfall` | 否 | 有 | **环绕中间件**：可包装、可短路 |
| `parallel` | 是 | 无 | 并行观测（遥测、统计） |
| `serial` | 是 | 有 | 按序执行、链式返回 |
| `bail` | 否 | 有 | **单决策**：首个返回值即停（审批） |

`waterfall` 的语义（原文）：监听器接收 `(...args, next)`；调 `next()` 执行下游并把下游返回值带回来供本层包装；**不调 `next()` 直接返回即短路**。策略监听器拥有决策权时可短路，纯观测监听器必须委托。

> 这就是"加功能不入侵主流程"的正确抽象：不是加 `if`，而是**定义接缝 + 声明分发模式**。

### 1.2 没有特权内核

> "不存在需要打补丁的特权内核：扩展 dsh 的方式是把插件挂载到其他插件旁边，而各项注册都是副作用，会在其插件卸载时撤销。"

对比大多数 Agent 项目：循环里塞 if-else、工具表硬编码、加功能要改核心文件。它把"改核心"变成"挂旁边"。

### 1.3 三层组装：profile / bundle / patch **[档]**

```
profile（web / headless / sdk / sdk-minimal / acp）
  └─ 有序叠加 bundle（dsh-base → dsh-web-app …）
       └─ patch（按 id 定位条目，替换其整个 config）
```

一条 patch 按 id 定位并替换配置，支持 HMR（配置热重载）。`dsh --profile web --dump-config` 打出来的任何条目都能被自己的 patch 替换。

**意义**：同一套代码，五种交付形态（Web / 无头 / SDK / 最小 SDK / ACP），差异全在配置层。

---

## 2. 工具执行流水线（最值得学的一段）**[档]**

```
tool/call 落日志 + UI 待定卡片
 → tools/pre-execute 瀑布（hook / 权限 / 沙箱）
 → 单调守卫（monotonic guards）：只能 deny 或弃权，不能 allow
 → 审批 ctx.approval（一次性；缺失或不可答 = 拒绝）
 → tools/execute 瀑布（超时 / 重试 / 指标，环绕分发）
 → 工具体
 → 文件写意图门（先读后编辑）
 → projectContent（准备好的文本与图片）
 → tools/post-execute 瀑布（接受 / 阻断 / 替换 / 追加上下文）
 → finalizeContent（最后的内容不变式）
 → tools/result（冻结的权威结果，JSON 无损）
 → tool/result 事件 → UI 完成卡片
```

三个设计要点：

1. **单调守卫**：只能拒绝或弃权。防止"后置插件把前面插件的拒绝翻案"。身份相关策略受保护。
2. **审批在守卫之前**，但守卫顺序不可重排。
3. **结果冻结**：注册表对候选结果做无损快照，快照失败先规范化；下游只能看到不可变结果。

**为什么对我们重要**：我们现在的执行侧只有 `SkillExecutor` 的 60 s 超时。一旦加上"控制手机 / 写文件 / 发消息"，就必须有守卫链和审批点，否则一次误调用就是事故。

---

## 3. Agent 轮次生命周期 **[档]**

一个 turn 里有多个 step，每一步三个瀑布：

```
agent/pre-step  waterfall → 返回 reject 或 enter(messages)   // 决定这一步进不进、可改消息
agent/request   waterfall → 可改请求
system-prompt/assemble waterfall → 按本步工具集组装提示词
```

关键细节：**pre-step 被 reject 时，这个 turn 不消耗 step**（"claimed batch stays removed, the open turn spends no step"）。也就是说"要不要花一步"本身是可被策略干预的。

对比常见的 `max_iterations = N` 硬上限，这是"把配额交给策略层"的做法。

---

## 4. 59 个包怎么切（学粒度，不学内容）

`packages/` 共 59 个，几个典型切法：

| 切法 | 例子 | 启示 |
|---|---|---|
| 一个能力拆成"注册表 / 来源 / 暴露"三包 | `skill` / `skill-filesystem` / `skill-office` / `tool-skill` | 我们的 `Skill` 现在是"目录 + 执行"混在一起 |
| 执行器按机制分 | `tool-executor-native` / `tool-executor-ptc` | 同一语义两种实现，用配置选 |
| 横切能力独立成包 | `compaction` / `guard` / `sandbox` / `telemetry` / `spill` | 上下文压缩、守卫、沙箱都是可替换件 |
| 交互面独立 | `interaction` / `deliverables` / `todo` / `plan` / `goal` | 计划与待办是服务，不是 UI 状态 |

**最有启发的一条**：`skill` 家族把"注册表 / 文件发现 / 内置办公 / 工具暴露"拆成四个正交包。我们现在是 `Skill` + `SkillExecutor` 两个类，加一类来源就要改核心。

---

## 5. 我们能不能用

| 方式 | 可行性 | 结论 |
|---|---|---|
| 直接在 App 里跑 dsh | ❌ Node.js + 251 MB monorepo，Android 上不现实 | 不做 |
| 把 dsh 当后端，App 当客户端 | ⚠️ 要用户自建服务器，`sdk` / `acp` profile 可以，但我们的定位是端上闭环 | 不做 |
| 通过 MCP 接它的工具 | ⚠️ 它是宿主不是被调用方，方向反了 | 不做 |
| **借鉴接缝设计与事件语义** | ✅ 纯架构，零依赖 | **做** |

---

## 6. 我们的实现（迁移清单）

| 动作 | 具体做法 | 成本 |
|---|---|---|
| 定义 3 个接缝 | `loop/before-user-message`（waterfall）、`loop/before-tool-call`（bail）、`loop/after-assistant-message`（parallel） | 1 天 |
| 工具执行加守卫链 | `Guard` 接口：`suspend fun check(call): Deny? `，**只允许返回 Deny 或 null**，不允许 Allow | 1 天 |
| 审批点 | 危险工具（写文件 / 发消息 / 控制手机）走一次性确认，超时按拒绝 | 1 天 |
| 结果冻结 | 工具返回后做一次深拷贝快照，下游只读 | 半天 |
| 拆 Skill 粒度 | 拆成 `SkillRegistry`（合并多来源）/ `SkillSource`（目录发现）/ `SkillTool`（暴露给模型） | 2 天 |

**不做**：Cordis 本身、profile/patch/HMR 体系（那是桌面端多形态交付的需求，我们只有一个 App）。

---

## 7. 与 11-06 那份 superagent 调研的区别

| | `dsh-superagent-prototype`（2026-11-06，已入库） | `deepseek-harness`（本文） |
|---|---|---|
| 是什么 | 自主 Agent 系统的原型 | 完整的 agent harness 产品骨架 |
| 学什么 | 单写者表、角色化窄接口、持久副作用队列、并发槽抢占 | **插件接缝、事件分发语义、工具守卫链** |
| 状态 | 原型 | 开发者预览（会破坏兼容） |

两份不重复，可以并读。

---

## 附：核对入口

| 结论 | 位置 |
|---|---|
| 一切皆插件 | `README.zh.md`；`docs/architecture.zh.md` |
| Cordis 五概念 / 五分发模式 | `docs/cordis-primer.zh.md` |
| 工具流水线 + 单调守卫 | `docs/tool-execution-pipeline.zh.md` |
| 轮次与步骤生命周期 | `docs/agent-lifecycle.zh.md` |
| 能力接缝清单 | `docs/capability-seams.zh.md`（786 行，自动生成的依赖图） |
| skill 家族切分 | `packages/skill/README.zh.md` |
