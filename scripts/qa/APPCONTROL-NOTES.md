# Phase 1 补口 · 应用操控工具集（AppControlTools）交付说明

> 工程师：寇豆码（Kou） · 任务：app_list / app_launch / app_stop 三件套 · 约 1 人日
> 依赖：T07/T08 终端后端交付后收尾，本批代码先写、编译串行等待（见「构建串行纪律」）

## 1. 交付文件

| 文件 | 说明 |
|---|---|
| `app/src/main/java/com/beichen/polaris/tools/AppControlTools.kt` | 三个工具 + 纯函数解析层 + 注册入口 |
| `scripts/qa/AppControlVerification.kt` | 纯函数 harness（手写断言，离线跑） |
| `scripts/qa/run-appcontrol-verification.sh` | harness 编译 + 运行脚本（离线，friend-paths） |
| `scripts/qa/APPCONTROL-NOTES.md` | 本说明 |

**未改动** `tools/BuiltInTools.kt`（另一批 T07/T08 正在改它的注册处，避免冲突）。

## 2. 能力设计

### app_list（只读，readOnly=true）
- 参数：`query`（名称/包名模糊匹配，可选）、`include_system`（默认 false 过滤系统应用）、`limit`（默认 30，上限 100）。
- 用 `PackageManager.queryIntentActivities(MAIN/LAUNCHER, MATCH_ALL)` 过滤「有 launcher 入口」的应用，按包名去重——只列用户看得见的应用，不列几百个无入口系统包。
- 依赖 Manifest 已声明的 `QUERY_ALL_PACKAGES`（Android 11+ 包可见性），本批**未改 Manifest**。
- 输出紧凑文本 `序号. 名称 (包名)`；超过 `limit` 追加「共 N 个，仅显示前 M 个，请用 query 收窄」提示。

### app_launch（写，readOnly=false）
- 参数：`target`（应用名/包名）、`activity`（可选，指定则按 `ComponentName` 直接启动）。
- 解析策略：精确包名 → 精确/模糊名称。**唯一命中才启动**；歧义返回候选列表（不启动）；无命中返回指向 `app_list` 的自纠 hint。
- 实现零权限零依赖：`getLaunchIntentForPackage` + `startActivity(FLAG_ACTIVITY_NEW_TASK)`。
- 成功后提示「用 `device_foreground_app` 确认是否切到目标」。

### app_stop（写，readOnly=false）
- 参数：`target`（解析同 app_launch）。
- 优先 shell 通道 `am force-stop <pkg>`；**通道不可用或执行失败 → 诚实降级**为「需用户手动停止」失败结果，hint 写清「设置 → 应用 → 强制停止」替代路径，绝不静默成功。

## 3. 关键裁决

1. **shell 通道零耦合**：T07/T08 的终端 API（`TerminalSessionPool` 等）尚在改动中，故 app_stop 不直接依赖它，而是定义 `internal fun interface ShellCommandRunner`（默认 `Unavailable` 恒返回 null）。Phase 2 / T07-T08 就绪后由注册处注入真实实现即可，本文件编译期零耦合。
2. **注册入口独立**：为规避 BuiltInTools 注册处冲突，提供 `fun registerAppControlTools(registry, context, shellRunner)`，注册行由主理人统一补进 `ChatViewModel`（`app` 上下文 + 真实 shellRunner）。
3. **解析抽纯函数**：`resolveAppTarget` / `filterAppEntries` / `formatAppList` / `clampAppListLimit` 及全部 hint 文本均为 internal 纯函数，JVM harness 可脱离 Android 断言。
4. **未改 Manifest**：`QUERY_ALL_PACKAGES` / `KILL_BACKGROUND_PROCESSES` 已存在，无需新增权限。
5. **activity 组件启动**：`activity` 传全类名时用 `ComponentName(pkg, activity)`，零权限。

## 4. harness 覆盖点（纯函数 / 假数据）

- 解析三类结果：精确包名→Unique、精确/模糊名称→Unique、多命中→Ambiguous（含候选）、无命中→NotFound、空 target/空候选→NotFound。
- **歧义不启动**：断言多命中必为 Ambiguous（execute 的 Ambiguous 分支与启动分支代码隔离，绝不启动）。
- **失败 hint 含可用工具提示**：`appNotFoundHint` 含 `app_list`；歧义 hint 列候选+提示用包名；`stopUnavailableHint` 含「强制停止」「设置 → 应用」。
- **app_list 文本格式化 + limit 截断**：格式形如 `1. 快手 (com.smile.gifmaker)`；超限追加收窄提示；`clampAppListLimit` 默认 30 / 上限 100 / 下界 1，300 条经钳制后 ≤ 100 行 + 1 提示行。
- 过滤：`include_system=false` 剔除系统应用；query 匹配名称/包名；空白 query 不过滤；结果按 label 升序稳定。
- 降级默认：`ShellCommandRunner.Unavailable.exec(...)` 恒返回 null。

## 5. 构建串行纪律

前一批 T07/T08 可能正在构建，编译前确认无并发 `assembleDebug` 且 T07/T08 产物就位：
```
ls core/src/main/java/com/beichen/polaris/core/terminal/TerminalSessionPool.kt scripts/qa/T07T08Verification.kt
pgrep -f assembleDebug    # 应无输出
```
不满足则继续代码走读，间隔 2 分钟重查，最多等 40 分钟。

## 6. 回归清单

- `bash scripts/qa/run-appcontrol-verification.sh`（本批 harness）
- `bash scripts/qa/run-verification.sh`（32/32）
- `bash scripts/qa/run-t01-verification.sh`（35/35）
- `bash scripts/qa/run-t01-probe.sh`（11/11）
- `bash scripts/qa/run-t01-probe2.sh`（7/7）

## 7. W-7 收尾批次（P0-1 字典 / P0-3 前台事件配对 / P1-1 陈旧窗口防护）

> 背景：用户真机反馈「跨应用操控不行，打开快手没成」。源码级深挖结论：启动后无沉降、
> 无前台确认、无静态字典（P0-2/P0-4 已由前序提交落地）。本批收尾剩余三项。

### 交付文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/beichen/polaris/tools/AppNameDictionary.kt` | **新增**：~75 条「名称/别名→包名」静态字典 + `looksLikePackageName` + `resolveAppTargetWithDict` 三级解析链（纯 Kotlin，零 Android 依赖） |
| `app/src/main/java/com/beichen/polaris/tools/AppControlTools.kt` | app_launch / app_stop 两处调用点改接 `resolveAppTargetWithDict` |
| `app/src/main/java/com/beichen/polaris/automation/DeviceForegroundAppTool.kt` | **重写**：UsageEvents 事件配对主路径（60s 窗口）+ 未授权自动跳 `ACTION_USAGE_ACCESS_SETTINGS` + launcher 包判定「在桌面」+ 桶法降级 |
| `app/src/main/java/com/beichen/polaris/automation/AgentAccessibilityService.kt` | 新增 `lastWindowSwitchAt` 跟踪 + `getFreshUiTreeSnapshot()` 轮询防护 + 纯函数 `shouldRetryForFreshWindow` |
| `app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt` | ui_tree 改调 `getFreshUiTreeSnapshot()`；kept=0 时文案补「界面可能正在切换」自纠指引（并修复 filtered>0 时消息括号闭合缺失） |
| `scripts/qa/AppControlVerification.kt` | 追加 27 条断言（字典 12 / 事件配对 8 / 窗口防护 7） |

### 设计要点

1. **字典只是加速器**：命中后仍走 `getLaunchIntentForPackage` 真校验，未安装应用得到 `no_launch_intent` 明确失败；字典未收录 → 回落既有候选匹配（歧义语义不变）→ 包名形态兜底直启。歧义绝不擅自启动。
2. **事件配对替代桶法**：桶法按整段聚合，5 分钟窗口常拿不到「刚切到微信」；事件法取时间序最后一个 RESUMED，launcher 包（HOME 意图解析者）判定为「在桌面」而非可操作 App。
3. **陈旧窗口防护只等「刚切换」**：`lastWindowSwitchAt` 为 0 或久远时 elapsed 远超 1.5s 预算，直接抓——无窗口切换发生的场景行为与旧版完全一致；超时后按当前状态抓一次返回，诚实降级不无限等。

### 验收

- 二进制级：五个改动文件经保真 stub 编译通过（本批借此抓修一个真 bug——`DeviceForegroundAppTool.kt` 漏 import `android.app.usage.UsageEvents`）。
- harness：新增 27 条断言对抗真实编译产物全绿（`-Xfriend-paths` 访问 internal 纯函数）。
- 真机复测项（CI 后）：「打开快手」→ 前台确认；ui_tree 在启动后立刻读取不再拿到 `[]`/自身窗口；未授权时 `device_foreground_app` 自动跳设置页。
