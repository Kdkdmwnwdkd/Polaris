# Agent「界面操作协议」提示词强化 · 详细说明（PROMPT-NOTES.md）

> 真机第四轮 Bug 修正 —— 强化系统提示 + 工具提示，纠正「用 shell 绕路操控界面」的策略错误。
> 配套 harness：`scripts/qa/PromptProtocolVerification.kt` + `scripts/qa/run-prompt-protocol-verification.sh`（离线，17/17）。

## 1. 背景：真机日志暴露的策略错误

指令「打开设置，把字体大小调到最大」，真机日志（08:27-08:28）：

```
app_launch 设置                ✅ ok
ui_tree                        ✅ ok（12433 字符）
ui_click {"text":"显示和亮度"}  ❌ click_not_found
ui_tap {x:546,y:1115}          ✅ ok
ui_tree                        ✅ ok
→ 之后乱来：
shell_run dumpsys accessibility ❌ Permission Denial
shell_run am start ...Settings$ ❌ SecurityException
ui_screenshot                   ❌ 屏幕捕获未开启
→ 放弃，回复用户「请你手动核对」
```

**根因**：无障碍 `ui_tree + ui_tap` 组合明明是通的，模型却在 `ui_click` 失败后转投
**shell 绕路**（dumpsys / am start / settings），而本机 user shell **没有**这些权限，
必然失败，最终任务失败并把活推回用户。这是**策略/提示缺失**，不是工具能力缺失。

## 2. 改法总览（文件清单）

| 文件 | 改动 |
|------|------|
| `core/src/main/java/com/beichen/polaris/core/AgentLoop.kt` | **A** `DEFAULT_SYSTEM_PROMPT` 新增「界面操作协议」段；新增 `SYSTEM_PROMPT_MAX_CHARS = 4000` 预算常量 |
| `app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt` | **B** `ui_tree` 描述/系统提示、`ui_tap` 描述、`ui_click` 描述 + 失败 hint 同步 |
| `app/src/main/java/com/beichen/polaris/tools/ShellTool.kt` | **B** `shell_run` 描述 + 系统提示：「不能操控界面」 |
| `app/src/main/java/com/beichen/polaris/automation/UiScreenshotTool.kt` | **B** 失败返回改为可行动指引常量 `SCREEN_CAPTURE_OFF_GUIDANCE`（「权限中心 → 屏幕捕获 → 去开启」） |
| `scripts/qa/PromptProtocolVerification.kt`（新增） | harness：17 条断言 |
| `scripts/qa/run-prompt-protocol-verification.sh`（新增） | 离线编译 + 运行（形态学 `run-t01-probe2.sh`） |

## 3. A · 系统提示「界面操作协议」段（实际文本）

```
【界面操作协议】
操控界面只能用无障碍工具（唯一可行通道）：先 ui_tree 看结构，再用 ui_tap/ui_click/ui_input/ui_swipe/ui_key 操作。
禁止用 shell_run 操控界面：dumpsys、am start、settings put/get、input tap 都会因权限不足失败，不要尝试。
shell_run 只用于文件读写、命令执行、查看本机信息；要「已安装应用列表」用 app_list，要启动应用用 app_launch。
ui_click 失败就自纠：① 看返回的候选列表，用最接近的候选重试一次；② 仍失败改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击；③ 坐标也失败才向用户求助。
每次操作后界面已变，必须重新 ui_tree；同一界面内容未变时不要重复 ui_tree。
不要过早放弃：任务未完成前不要输出「请你手动核对」这类把活推回用户的话，至少按上面规则自纠两轮。
```

对应需求 6 条：① 唯一通道 ② 禁 shell 操控 ③ shell 用途收敛（app_list/app_launch）
④ 失败自纠三步 ⑤ 操作后重读 / 未变不重复 ⑥ 不要过早放弃。

**预算**：整段 6 句、段落本身约 8 行；`DEFAULT_SYSTEM_PROMPT` 总长 **553 字符 ≤ 4000**（harness 实测）。

## 4. B · 工具侧提示同步（要点）

- `ui_click` 描述新增「定位失败时会返回屏幕上最接近的候选列表，请用最接近的候选重试一次；
  仍失败再改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击」；失败 hint 前置候选列表 + 自纠三步。
- `ui_tap` 描述改为「坐标必须取自 ui_tree 返回元素的 bounds 中心点：(left+right)/2,(top+bottom)/2；
  当 ui_click 找不到元素时用它兜底」。
- `ui_tree` 描述/系统提示强调「唯一可行通道」「操作后重新读取、未变不重复」。
- `shell_run` 描述 + 系统提示追加「不能用来操控界面（dumpsys/am start/settings 都会因权限不足失败）；
  操控界面用 ui_* 工具」。
- `ui_screenshot` 失败 message 用常量 `SCREEN_CAPTURE_OFF_GUIDANCE`（含「权限中心 / 去开启」），
  hint 补「无法由 Agent 自助开启、请引导用户、不要用 shell 绕过」。

## 5. 关键裁决 / 与任务书偏差（据实记录）

1. **`DEFAULT_SYSTEM_PROMPT` 实际位于 `core/AgentLoop.kt`，不在 `core/agent/RealAgentLoop.kt`。**
   任务书要求改 `RealAgentLoop.kt` 的 `DEFAULT_SYSTEM_PROMPT`，但该常量定义在
   `core/AgentLoop.kt`（`RealAgentLoop` 仅 `import` 引用，两个 `AgentLoop` 类都以其为
   构造默认值）。为遵守「单一真源」并让系统提示改动全链路生效，改在 `core/AgentLoop.kt`。
   这是唯一新增触及的文件（任务书授权范围的合理外延）。
2. **`shell_run` 描述在 `tools/ShellTool.kt`，`ui_screenshot` 在 `automation/UiScreenshotTool.kt`。**
   任务书把工具侧改动笼统归到 `automation/UiAutomationTools.kt`，但这两个工具的实现分别在
   上述文件。按其**真实归属**修改，未在 `UiAutomationTools.kt` 里重复定义。
3. **可测性抽取常量**：`SYSTEM_PROMPT_MAX_CHARS` 与 `SCREEN_CAPTURE_OFF_GUIDANCE` 提为顶层
   `const val`，使 harness 无需启动 Android 服务即可对「文案契约」做断言（形态学既有
   `CAPABILITY_FALLBACK_HINT` 的做法）。
4. **harness 只读描述字符串、绝不调 `execute()`**：`ui_*`/`ui_screenshot` 的 `execute()` 依赖
   Android 运行时（无障碍服务 / 屏幕捕获），JVM harness 只校验 `description`/`systemPrompt`
   与文案常量，保证离线可跑、零 Android 依赖。
5. **零新增依赖、未新增 JUnit**：全部沿用 `kotlin-compiler-embeddable` + `java` 手写断言。

## 6. 验收证据

- 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → **BUILD SUCCESSFUL**。
- 新 harness：`run-prompt-protocol-verification.sh` → **17 passed, 0 failed**。
- 回归：十五套全绿（见 README/回传汇总；`run-t01-probe2.sh` R9 为已知竞态，重跑即过，未改）。
