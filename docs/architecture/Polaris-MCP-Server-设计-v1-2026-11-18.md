# Polaris MCP Server 设计 v1

> 状态：设计定稿，**未动代码**（P1 冻结令生效中，落地需解冻）
> 日期：2026-11-18
> 目标：把 Polaris 的 57 个工具以 MCP server 形式暴露给外部 Agent（电脑上的 DSH / Claude Code / Codex），
> 让「手机的手」成为整个 Agent 生态的一等能力，而 Polaris 不必背 Node 运行时。

---

## 0. 一句话

**他们是把电脑装进手机，我们是把手机挂到电脑上。**

DSH 生态已有的手机方案（`DSH-Android`、`DSHA`、`dsh-Go`）都是「把引擎搬进手机」，
代价 110 MB ~ 1 GB；而他们共同缺的是**原生真机控制**——只能靠无障碍 + Shizuku/ADB 硬凑。
Polaris 反过来做 MCP server，让它成为这些客户端的「手」，体积零增长。

---

## 0.1 实现状态（2026-11-18，M1 已落地）

> 本节记录**实际写出来的东西**与上面设计的差异。设计是意图，这里是事实。

**已上线（可编译、31 条断言全绿）**

| 文件 | 作用 |
|---|---|
| `core/mcp/server/McpToolSource.kt` | 工具表抽象（4 个方法），MCP 不直接吃 `ToolRegistry` |
| `core/mcp/server/McpTier.kt` | 三档位 + 硬禁用名单 |
| `core/mcp/server/McpPairing.kt` | 配对、token 摘要、过期、撤销、爆破降级 |
| `core/mcp/server/McpAudit.kt` | JSONL 审计、7 天滚动、参数只存摘要+200 字符 |
| `core/mcp/server/McpServerHost.kt` | 手写 HTTP/1.1 + JSON-RPC 分发 + 档位/审批/守卫/限流 |
| `app/mcp/McpToolset.kt` | 7 个工具的装配（6 只读 + `app_launch`） |
| `app/mcp/RegistryToolSource.kt` | `ToolRegistry` → `McpToolSource` 适配（4 行） |
| `app/mcp/McpHostRuntime.kt` | 进程级持有者（已登记 `AppGraph`） |
| `app/mcp/McpHostService.kt` | 前台服务（`startService` 入口，避开 5 秒窗口） |
| `app/ui/McpHostCard.kt` | MCP 页「对外提供」卡片 |
| `scripts/qa/run-mcp-host-verification.sh` + `McpHostVerification.kt` | 9 条装配断言 + 22 条行为断言 |

**与设计的四处偏差（都是有意的）**

| 设计 | 实际 | 原因 |
|---|---|---|
| 引 NanoHTTPD 2.3.1 | **零依赖**，`ServerSocket` 手写 300 行 | 只要一个路由；省 100 KB 与一个 CVE 面。安全靠「只绑 loopback + Bearer + 体积上限」而非框架 |
| 直接吃 `ToolRegistry` | 新增 `McpToolSource` 抽象 | `ToolRegistry` 牵 `JsonSchema.serializer()`（要序列化编译器插件），harness 在没装插件的机器上会编不过 → 被 SKIP → 安全断言静默消失 |
| `@Serializable` 存配对文件 | 手写 JSON 解析 | 这是**跨版本可读的用户资产**：手写能对缺字段逐项兜底，`@Serializable` 遇到缺字段直接抛，用户看到「客户端全没了」 |
| 只读档 14 个工具 | 实际装配 7 个 | 其余只读工具依赖 `MemoryStore`/`WorkflowStore`/终端池等重装配。M1 只装「零重依赖」的 7 个，快速证明链路 |

**尚未做（M2 及以后）**

- 审批卡片：`McpApprover` 接口已留，`null` 时一律拒绝（不是放行）。
- DANGEROUS 工具与「完全信任」档：UI 里被 `return@forEach` 跳过。
- 局域网 / TLS / 二维码：只在 loopback，跨设备需 `adb reverse`。
- `ToolGuardChain` 真实装配：M1 传 `NONE`；M2 接入写操作时必须换成与本地同一条链。

---

## 1. 现状盘点（代码实测，非估算）

| 事实 | 位置 |
|---|---|
| 工具契约：`name` / `description` / `parameters: JsonSchema` / `needsApproval` / `readOnly` / `awaitsUser` / `timeoutBudgetMs` / `execute()` | `core/AgentContract.kt:18` |
| 注册表：`list()` / `descriptors()` / `execute(ToolCall)` | `core/ToolRegistry.kt:130-240` |
| 元数据单一真源：`CatalogTool(name, group, capability, approval)`，共 **57** 条（SAFE 16 / PRIVILEGED 19 / DANGEROUS 22） | `core/tool/ToolCatalog.kt:104-194` |
| 能力三级：`SAFE` / `PRIVILEGED` / `DANGEROUS` | `core/tool/ToolCatalog.kt:11` |
| 审批归组：`UI_ACTION` / `FILE_WRITE` / `SHELL` / `NETWORK` / `PRIVATE_READ` 等 12 键 | `ToolCatalog.Approval` |
| 已有 MCP **客户端**：JSON-RPC + HTTP POST + SSE 解析，`protocolVersion = 2024-11-05` | `core/mcp/McpClient.kt` |
| 出网硬门禁：`EgressGuard`（读私有内容后外发必须问） | `core/agent/EgressGuard.kt` |

**结论：协议层和工具层都是现成的，缺的只是「服务端」这一侧。**

---

## 2. 传输与配对

### 2.1 为什么不用 stdio / WebSocket

| 方案 | 否决理由 |
|---|---|
| stdio | Android 无法把 stdin/stdout 管道交给外部主机的进程；系统也不允许这样拉起 App |
| WebSocket | 现有 `McpClient` 只实现 POST + SSE 兼容，自造一套等于两边都要改 |
| **Streamable HTTP（POST + 可选 SSE）** | 与现有客户端同语汇，可**自我互测**（Polaris 连自己） |

### 2.2 端点

```
POST http://127.0.0.1:{port}/mcp
Accept: application/json, text/event-stream
Authorization: Bearer <token>
MCP-Protocol-Version: 2024-11-05
```

- 端口：启动时取随机高位端口（49152–65535），写 `files/mcp/host.json`，含 `{port, token, fingerprint, startedAt}`。
- 默认**只绑 127.0.0.1**。同机其他 App 仍可访问 loopback，所以 token 是必需的，不是可选的。
- 局域网模式（默认关）：绑定 `0.0.0.0` 时**强制 TLS**，自签证书 + 首次配对指纹固定（TOFU），拒绝无 TLS 的局域网明文。

### 2.3 配对

二维码内容（一次性、5 分钟过期）：

```
polaris://mcp/pair?host=192.168.1.7&port=51234&token=<256bit>&fp=<sha256>&name=DSH
```

- 用户在主界面「MCP 接入」页点「添加客户端」→ 选权限档 → 出二维码。
- 每客户端独立 token；列表里可见「设备名 / 档位 / 最近调用 / 撤销」。
- token 存 Android Keystore（EncryptedSharedPreferences），不落明文日志。
- 默认 7 天过期，可续期；撤销即时生效（内存表 + 磁盘双写）。

---

## 3. 能力档位（决定暴露哪些工具）

| 档位 | 暴露范围 | 典型用途 |
|---|---|---|
| **只读**（默认，14 个） | `Capability.SAFE` 且 `readOnly = true`：`app_list`、`ui_tree`、`memory_search`、`todo_read`、`calculator`、`github_status`、`read_file`（限工作区） | 让外部 Agent「看一眼手机」 |
| **标准**（+19 个） | 只读 + `PRIVILEGED`：`app_launch`、`write_file`/`edit_file`、`clipboard`、`send_notification`、`web_search`、`ocr_image`、`speak`、`use_skill` | 日常代操作 |
| **完全信任**（+22 个） | 只读 + 标准 + `DANGEROUS`：`ui_tap`/`ui_swipe`/`ui_input`/`ui_key`、`ui_screenshot`、`app_install`、`app_stop`、`shell_run`、`device_notifications`、`github_commit` | 自动化流水线 |

**硬禁用（任何档位都不暴露）**：

| 工具 | 理由 |
|---|---|
| `ask_user` / `plan_propose` | `awaitsUser = true`，语义是「等人回答」。外部客户端的人不在这块屏幕上，暴露等于制造 180 秒挂起 |
| `app_uninstall` | 不可逆且误伤面大；要卸载让用户自己动手 |
| `memory_forget` | 删除用户记忆，外部 Agent 无权代劳 |
| `container_toolchain` / `shell_background` | 提权/长驻副作用，超出「手」的语义 |

> **档位 ≠ 审批，两件事正交。** 档位决定「这个工具能不能被外部看见」，`needsApprovalFor(args)` 决定「看见之后要不要问」。
> 所以标准档里的 `write_file` 依然会弹窗——档位放宽不等于免审。

**schema 映射**：`JsonSchema(type, properties, required)` 直接序列化为 MCP `inputSchema`（结构本就是标准 JSON Schema 子集）。
**annotations 映射**：`readOnlyHint = readOnly`；`destructiveHint = needsApproval && !readOnly`；`idempotentHint` 不填（工具未声明）。
**结果映射**：`ToolPart.Text` → `{type:"text"}`；`ToolPart.Image(url)` → `{type:"image", data:<base64>, mimeType}`（`file://` 读盘转码，超过 1 MB 拒绝并回文本说明）。

---

## 4. 审批：外部调用**不得**绕过宿主

这是整份设计里唯一不能妥协的一条。

```
外部 tools/call
   → 鉴权（token / 档位）
   → 档位过滤（不在档位内 → 直接拒绝，不回落到审批）
   → ToolGuardChain（与本地调用同一条链）
   → needsApprovalFor(args)?
        ├─ 否 → 执行
        └─ 是 → 手机端审批卡片（前台服务通知 / 悬浮卡）
                 ├─ 允许（本次）
                 ├─ 允许（本会话，仅对可记忆的 approval 键生效）
                 └─ 拒绝 / 30 秒超时 / 屏幕锁定 → 拒绝
   → 审计落盘
```

### 4.1 三条铁律

1. **外部客户端不能自带审批。** 客户端说「我已确认」不算数——它就是 `dsh-purge` 拆宿主提示词的那条路。
2. **锁屏 = 拒绝。** 屏幕关着时人不在场，DANGEROUS 一律 deny，PRIVILEGED 也 deny；只有 SAFE 放行。
3. **`EgressGuard` 优先级高于档位。** 「读过私有内容 → 外发」必须弹窗，即使调用方是「完全信任」档，且该次审批**不可记忆**。

### 4.2 审批卡片内容

| 字段 | 来源 |
|---|---|
| 谁在调 | 客户端名 + 档位（`DSH · 完全信任`） |
| 调什么 | 工具名 + 中文动作（`ui_tap · 点击屏幕`） |
| 参数摘要 | `ApprovalPreview.of(args)`（保头保尾 2000 字符） |
| 风险提示 | `capability` + `approval` 键（例如「将写入工作区外文件」） |
| 按钮 | 拒绝 / 仅本次 / 本会话（仅可记忆键显示） |

### 4.3 频率限制

- 单客户端：DANGEROUS ≤ 20 次/分钟，全工具 ≤ 120 次/分钟；超限返回 `429` + `Retry-After`。
- 连续 3 次拒绝 → 该客户端降级为只读，需用户手动恢复（防「弹窗疲劳后误点」）。

---

## 5. 会话、并发、超时

| 项 | 设计 |
|---|---|
| 会话 | 无状态；`Mcp-Session-Id` 仅用于承载「本会话允许」白名单，缺失则每次重问 |
| 并发 | `readOnly` 工具并行（上限 4），写工具串行（复用 `batchHasMutation` 语义） |
| 超时 | 取 `timeoutBudgetMs`，MCP 层 +5 s 余量；无声明则用 `ToolBudget` 分档 |
| 后台 | 前台服务常驻；`EngineIdle` 30 分钟无调用自动停监听（省电），下次 `initialize` 再拉起 |
| 错误 | 协议错误 → JSON-RPC `error`；工具失败 → `result.isError = true` + 文本，**不**伪造成功 |

---

## 6. 审计

落 `files/mcp/audit-YYYYMMDD.jsonl`，单条：

```json
{"ts":1763500000123,"client":"DSH","tier":"full","tool":"ui_tap",
 "argsHash":"sha256:9f2c…","argsPreview":"{\"x\":540,\"y\":1200}",
 "decision":"allow_session","approvalKey":"ui_action","ms":412,"status":"ok"}
```

- 参数只存**摘要 + 前 200 字符**，不存完整内容（可能含隐私）。
- 保留 7 天，滚动删除；用户可在「MCP 接入」页查看与一键清空。
- 审计写失败不阻断调用，但记一条 `audit_failed` 并在页面标红（不能静默丢证据）。

---

## 7. 分阶段落地

| 阶段 | 范围 | 验收 |
|---|---|---|
| **M1 只读桥** | 只读档 + 配对 + 审计 + `initialize`/`tools/list`/`tools/call` | DSH 能 `tools/list` 看到 N 个只读工具；`app_list`、`ui_tree` 端到端成功；无 token 返回 401 |
| **M2 标准档 + 审批卡** | PRIVILEGED + 审批卡片 + 频率限制 | `app_launch` 触发卡片；拒绝后零执行；锁屏时 PRIVILEGED 被拒 |
| **M3 完全信任档** | DANGEROUS + 会话白名单 + 降级机制 | `ui_tap` 连续 3 次拒绝后降级只读；`EgressGuard` 在 full 档仍拦截 |
| **M4 局域网 + TLS** | 自签证书 + 指纹固定 + 二维码跨设备 | 手机与电脑不同网段不可达；指纹不符拒绝连接 |

每阶段新增 QA runner，`arch-baseline` / `assertion-census` 按「只减不增」记账。

---

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 同机恶意 App 扫 loopback 端口 | 256-bit token + 随机端口 + 401 计数熔断（10 次失败锁 5 分钟） |
| 用户误点「本会话允许」 | 只对可记忆键开放；`SHELL_DESTRUCTIVE`/`SHELL_ELEVATED`/`SHELL_UNMEMORIZABLE`/`PRIVATE_READ` 永不记忆 |
| 外部 Agent 高频点屏幕 | 频率限制 + 每次 DANGEROUS 必弹卡 |
| 后台被杀导致客户端悬挂 | `EngineIdle` 主动关监听前发 `notifications/tools/list_changed`? 否——直接断开并让客户端重连；文档写明「不保证长连接」 |
| 与本地 Agent 抢无障碍 | 复用现有 `ApprovalScope` 串行化；MCP 调用与本地调用共享同一把锁 |
| 首版被当成「远程控制后门」 | 默认关闭、默认只读、默认 loopback；UI 首启必须显式勾选风险确认 |

---

## 9. 不做

- 不做「Polaris 作为 MCP 客户端去连 DSH」——DSH 不是 MCP server，方向相反。
- 不做内嵌 Node 跑 Cordis 插件（见 `DSH手机端生态实况-2026-11-18.md` §4）。
- 不做公网穿透（frp/ngrok 内置）。用户要远程，自己配 Tailscale；我们不承担暴露面。

---

*本设计只新增文档，未修改任何 `.kt` 文件。*

---

## 附录 A：M1 施工清单（解冻后直接照做）

### A.1 依赖

当前只有 OkHttp **客户端**（`libs.versions.toml:18`），没有 HTTP 服务端。M1 建议：

```toml
nanohttpd = "2.3.1"
nanohttpd = { module = "org.nanohttpd:nanohttpd", version.ref = "nanohttpd" }
```

选它不选 Ktor 的理由：单 jar、约 100 KB、不引入协程桥接；我们只绑 loopback + Bearer，不需要 Ktor 的完整生态。
若 M4 要 SSE 长连接与 TLS，再换 `ktor-server-cio`（届时评估体积）。

### A.2 新建文件

| 文件 | 职责 |
|---|---|
| `core/mcp/server/McpServerHost.kt` | NanoHTTPD 子类：路由 `/mcp`、鉴权、JSON-RPC 分发、错误码 |
| `core/mcp/server/McpCatalogBridge.kt` | `ToolRegistry.descriptors()` → MCP `Tool[]`；档位过滤；`inputSchema` 透传 |
| `core/mcp/server/McpInvoker.kt` | `tools/call` → `ToolRegistry.execute(ToolCall)`；结果/图片转 `content[]` |
| `core/mcp/server/McpPairing.kt` | token 生成/哈希/撤销、二维码 payload、过期 |
| `core/mcp/server/McpAudit.kt` | JSONL 审计、7 天滚动、写失败标记 |
| `app/.../ui/McpHostScreen.kt` | 「MCP 接入」页：开关、档位、客户端列表、二维码、审计查看 |

### A.3 改动点（尽量只加不改）

| 位置 | 改动 |
|---|---|
| `PetOverlayService` 同级新增 `McpHostService`（前台服务） | 承载监听与常驻通知；**不复用**桌宠服务，避免一处崩两处死 |
| `AppGraph` | 注册 `McpServerHost` 单例与开关状态 |
| `ToolGuardChain` | 不改编排，仅新增一个 `caller: Caller` 上下文参数（默认 `Local`），外部调用时携带客户端名与档位 |

### A.4 契约样例（可直接当 QA 夹具）

`initialize` 请求：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize",
 "params":{"protocolVersion":"2024-11-05","capabilities":{},
           "clientInfo":{"name":"DSH","version":"0.1"}}}
```

响应：

```json
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2024-11-05",
  "capabilities":{"tools":{"listChanged":false}},
  "serverInfo":{"name":"polaris","version":"1.0"}}}
```

`tools/list`（只读档，节选）：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","result":{"tools":[
 {"name":"app_list","description":"列出已安装应用",
  "inputSchema":{"type":"object","properties":{},"required":[]},
  "annotations":{"readOnlyHint":true,"destructiveHint":false}},
 {"name":"ui_tree","description":"读取当前界面控件树",
  "inputSchema":{"type":"object","properties":{"maxDepth":{"type":"integer"}},
                 "required":[]},
  "annotations":{"readOnlyHint":true,"destructiveHint":false}}]}}
```

`tools/call` 被拒（档位不足）：

```json
{"jsonrpc":"2.0","id":3,"error":{"code":-32003,
 "message":"tool 'shell_run' not exposed at tier 'readonly'"}}
```

### A.5 M1 完成判据

- [ ] 无 token → `401`，10 次失败锁 5 分钟
- [ ] `tools/list` 返回数量 = 14（只读档），且不含 `ask_user`/`plan_propose`
- [ ] `app_list` 从电脑端 DSH 调用成功，结果与手机端本地调用逐字一致
- [ ] `ui_tree` 在锁屏时仍可用（readOnly），`app_launch` 在锁屏时被拒
- [ ] 审计 5 个字段齐全，`argsHash` 可复算
- [ ] 关掉「MCP 接入」开关后端口立即关闭（`ss -lnt` 无监听）
