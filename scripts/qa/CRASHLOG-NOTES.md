# 真机第四轮：崩溃捕获落盘（CrashLogger）+ 屏幕捕捉再加固 — 工程师自检笔记

> 作者：寇豆码（Engineer） · 范围：只写代码与自检，**未推送、未联网**
> 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`

---

## 0. 一句话结论

**崩溃证据链已建立**：进程死之前同步 `flush + fsync` 落盘到 `filesDir/crash/crash-<yyyyMMdd-HHmmss>.txt`，
下次启动日志页「最近崩溃」区块直接列出并可展开全文、复制、清空；
屏幕捕捉侧把**全流程每一个**可能抛异常的系统调用都兜住了，任何异常走同一条「安全停止序列」，绝不上抛。

新 harness `CrashLogVerification.kt` **47/47 全绿**；十四套回归全绿（见 §4）。

---

## 1. 文件清单

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `core/src/main/java/com/beichen/polaris/core/CrashLogger.kt` | **新增** | 全局崩溃捕获 + 同步落盘 + 保留策略 + 纯函数 |
| `app/src/main/java/com/beichen/polaris/AgentKitApplication.kt` | **新增** | Application 入口，只做一件事：最早时机 `CrashLogger.install(this)` |
| `app/src/main/AndroidManifest.xml` | 改 1 处 | `<application android:name=".AgentKitApplication" ...>` |
| `app/src/main/java/com/beichen/polaris/automation/ScreenshotBridge.kt` | 改 | 新增纯函数对象 `ScreenCaptureGuard`（尺寸防御 + 清理序列 + `guardValue`/`guard`）；新增 `ScreenshotBridge.clearError()` |
| `app/src/main/java/com/beichen/polaris/automation/ScreenCaptureService.kt` | 改（重写） | 全流程兜底：7 步建立链路每步单独 guard；`failSafe()` 唯一失败出口；`MediaProjection.Callback` |
| `app/src/main/java/com/beichen/polaris/ui/LogsScreen.kt` | 改 | 新增「最近崩溃」区块（列表 + 展开全文 + 复制 + 清空），进页面回放崩溃到 AgentLogger |
| `scripts/qa/CrashLogVerification.kt` | **新增** | QA harness，47 条手写断言（A12 / B7 / C6 / D12 / E10） |
| `scripts/qa/run-crashlog-verification.sh` | **新增** | 离线 runner（kotlin-compiler-embeddable + `-Xfriend-paths`） |
| `scripts/qa/CRASHLOG-NOTES.md` | **新增** | 本文件 |

零新增外部依赖、零新增 JUnit。

---

## 2. A 项：崩溃捕获落盘

### 2.1 关键设计

- **同步落盘**：`FileOutputStream → write → flush → fd.sync()`。
  只 flush 到 OS 页缓存，进程被 SIGKILL 时仍可能丢；`fsync` 是「崩溃留证」能成立的前提。
- **绝不吞崩溃**：写完**无条件** `defaultHandler?.uncaughtException(thread, throwable)`。
  派发顺序被 harness C6 断言为 `write → handler`。
- **自身绝不抛**：`dispatch()` 三段都 `runCatching`——写盘抛异常、`formatReport` 抛异常
  （测例 C5 用「`message` getter 本身抛异常」的毒 Throwable 验证）、原 handler 抛异常，全部吞掉。
- **保留最近 10 份**：`retentionPlan(names, keep)` 按文件名降序（文件名 `crash-<yyyyMMdd-HHmmss>.txt`
  字典序 = 时间序），保留最新 N、删其余；每次写完立即裁剪。同一秒内二次崩溃写 `-2` 后缀，不互相覆盖。
- **设备信息快照**：`install()` 时读一次 `Build.* / PackageInfo` 缓存，崩溃时不再碰 PackageManager
  （崩溃现场做 IPC 是危险的）。

### 2.2 报告内容

```
========== AgentKit 崩溃报告 ==========
发生时间：2024-05-01 12:00:00.123
时间戳(ms)：1714543200123
线程名：main
异常类型：java.lang.IllegalStateException
异常消息：屏幕捕捉炸了
崩溃摘要：java.lang.IllegalStateException: 屏幕捕捉炸了
设备信息：
  厂商：Google / 机型：Pixel 8 / Android SDK：34
  App 版本名：1.94 / App 版本号：194 / 包名：com.beichen.polaris
---------- 完整堆栈 ----------
java.lang.IllegalStateException: 屏幕捕捉炸了
	at ...（含 Caused by 链）
========== 报告结束 ==========
```

### 2.3 可读性（日志页）

- 日志页顶部（有记录时才出现）「最近崩溃（N）」分区卡：
  描述行 = `检测到 N 次崩溃记录：<最新文件名> 位于 <filesDir>/crash/`（满足最低限度要求）；
- 每条：时间 + 异常类型（短名）+ 摘要 2 行，**点击展开全文**，展开后可「复制全文」；
- 底部「清空崩溃记录」；
- 进页面 `LaunchedEffect` 调 `CrashLogger.replayPendingIntoLogger(ctx)`：把崩溃记录以 WARN 打进
  AgentLogger（Application 阶段日志目录尚未就绪，这里补一次，崩溃内容才会随当日日志真正落盘）。

---

## 3. B 项：屏幕捕捉再加固

### 3.1 为什么上一轮没修好

上一轮只修了「取 token → 进前台」的**顺序**与 `startForegroundSafely` 的 try/catch。
顺序之外仍有一串**裸调用**：`createVirtualDisplay` / `ImageReader.newInstance` /
`setOnImageAvailableListener` / `acquireLatestImage` / `stopSelf`，
以及 `displayMetrics.widthPixels/heightPixels` 为 0 时直接拿去 `newInstance`（必崩）。
异常从这些点逃出去 → 系统杀进程 → 用户看到「还是闪退」。

### 3.2 本轮做法

1. **每一步单独兜底**：`initProjection` 拆成 7 步（尺寸防御 → 取 token → ImageReader →
   帧回调 → VirtualDisplay → 投影 Callback → 状态就绪），每步用
   `ScreenCaptureGuard.guardValue/guard` 包住，失败即 `failSafe()`。
2. **尺寸防御**（纯函数 `sanitizeSize`）：宽/高 ≤ 0 → 直接放弃（不再拿 0 建 ImageReader）；
   密度 ≤ 0 → 回退 320；任一边 > 4096 → **等比缩放**保宽高比（4096 是 ImageReader/合成器常见上限）。
3. **唯一失败出口 `failSafe(stage, reason, cause)`**：按 `cleanupPlan(held)` 逐步执行，
   每步单独 `runCatching`，任何一步失败都不阻止后续步骤。
   序列（顺序敏感，harness D6 断言）：
   `RELEASE_VIRTUAL_DISPLAY → CLOSE_IMAGE_READER → STOP_PROJECTION → CLEAR_STATE →
    REPORT_LAST_ERROR → LOG_ERROR → STOP_SELF`
   - 未持有的资源不产生对应释放步骤（D7/D8 断言），避免 NPE 与重复释放；
   - **先上报 `lastError` 再写日志**（D10 断言）：保证 UI 一定看得到原因；
   - `CLEAR_STATE` 只清 `activeInstance === this` 的实例，不误伤另一个正在运行的实例。
4. **`activeInstance` 时机**：**仅在链路完全就绪后**赋值（半成品实例是 NPE 与泄漏源头），
   失败/销毁用 `===` 判定后清空。
5. **`MediaProjection.Callback.onStop`**（零依赖，顺手加）：用户从控制中心停止投屏时系统会回调，
   不注册就收不到 → 服务挂着前台通知却抓不到帧。回调里同样走 `failSafe(PROJECTION_STOPPED)`。
6. **`captureBitmap` 绝不抛**：整体 `try/catch(Throwable)`，`Thread.sleep` 被打断时恢复中断标志；
   `imageToBitmap` 对 `pixelStride/rowStride/width/height ≤ 0` 显式抛 `IllegalStateException`
   交给外层兜底（避免 `ArithmeticException` / `IllegalArgumentException` 逃出去）。
7. **`onDestroy`** 同样逐步 `runCatching`，并注销 Callback。

---

## 4. 自检证据

### 4.1 编译

```
/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks
> Task :app:compileDebugKotlin
> Task :app:packageDebug
BUILD SUCCESSFUL in 57s
39 actionable tasks: 39 executed
```

（仅存量 warning，无新增 error；`Icons.Outlined.Error` 属 material-icons-extended，已验证可用。）

### 4.2 新 harness

```
bash scripts/qa/run-crashlog-verification.sh
[A] 崩溃报告格式化（纯函数）      12/12  PASS
[B] 崩溃文件保留策略（纯函数）     7/7   PASS
[C] 崩溃派发：必调用原 handler     6/6   PASS
[D] 屏幕捕捉加固（尺寸 + 序列）   12/12  PASS
[E] 接线扫描（源码级）            10/10  PASS
PASS=47  FAIL=0   ALL GREEN
```

覆盖本次要求的四条断言：
- 报告含异常类型 / 消息 / 堆栈首行 / 线程名 / 版本信息（A1–A6）；
- 保留策略超过 N 份保留最新 N 份（B3/B4）；
- 捕获后**一定**调用原 handler（C1–C6，假 handler 断言恰好一次，含写盘失败与 handler 自抛两种极端）；
- 写文件失败仍调用原 handler、自身不抛（C2/C3/C5）。

### 4.3 回归十四套（全绿）

| # | 脚本 | 结果 |
| --- | --- | --- |
| 1 | `run-verification.sh` | 32/32 PASS |
| 2 | `run-t01-verification.sh` | 35/35 PASS |
| 3 | `run-t01-probe.sh` | 11/11 PASS |
| 4 | `run-t01-probe2.sh` | 7/7 PASS（已知竞态 R9 本轮未复现） |
| 5 | `run-appcontrol-verification.sh` | 31/31 PASS |
| 6 | `run-t07t08-verification.sh` | 35/35 PASS |
| 7 | `run-t07t08-adversarial.sh` | 17/17 PASS |
| 8 | `run-markdown-verification.sh` | 45/45 PASS |
| 9 | `run-branch-verification.sh` | 20/20 PASS |
| 10 | `run-retry-verification.sh` | 9/9 PASS |
| 11 | `run-localmodel-verification.sh` | 19/19 PASS |
| 12 | `run-fallback-verification.sh` | 16/16 PASS |
| 13 | `run-hotfix-verification.sh` | 17/17 PASS |
| 14 | `run-hotfix3-verification.sh` | 全过 |
| + | `run-crashlog-verification.sh`（新） | 47/47 PASS |

---

## 5. 关键裁决（含与原始要求的偏差说明）

1. **新建 `AgentKitApplication` 而非改 MainActivity**：项目此前**没有** Application 子类。
   崩溃捕获必须早于任何业务初始化（连初始化阶段的崩溃都要能留证），故新建最小 Application
   并在 Manifest 声明；`onCreate` 里**只**装 CrashLogger，保持它自身尽量不出错。
2. **`CrashLogger.install` 幂等**：多进程 / 热重启会各调一次 `onCreate`，不做幂等就会把 handler 套娃
   （原 handler 变成我们自己 → 递归）。
3. **fsync 而不是只 flush**：进程马上被杀，页缓存不可靠。`fd.sync()` 抛 `SyncFailedException`
   也被 `runCatching` 吞掉，不影响「必回传原 handler」。
4. **保留 10 份**：`MAX_KEEP = 10`，写完立即裁剪（崩溃可能连续发生，不能等下次启动）。
5. **capture 阶段异常也 `stopSelf`**（按需求原文「任何异常 → … + stopSelf」）：
   一帧取不到只是 `null`（不算异常，只 WARN）；真正抛异常的都是致命态（OOM / stride 非法 / Surface 失效），
   此时让服务收摊、UI 可见地重试，比挂着一个坏链路更可控。
6. **`DESTROY` 阶段不 `stopSelf`**：服务已经在销毁路径上，再调 `stopSelf` 无意义（harness D9 断言）。
7. **超大分辨率走「等比缩放」而非放弃**：直接放弃会让高刷/大屏机型完全用不了截图通道，
   等比缩放（≤4096，保宽高比）是可用性与安全性的折中。
8. **日志页改动做了完整版（不是最低限度）**：区块 + 展开全文 + 复制 + 清空都做了，
   因为「只能看到一行文件名」不足以定位崩溃点，而成本只有 ~110 行 Compose。
9. **未触碰 `ChatViewModel`**：回放崩溃到 AgentLogger 的动作放在日志页 `LaunchedEffect` 里
   （此刻日志目录已就绪，崩溃内容才会真正落盘），避免动 ViewModel 的初始化时序。

---

## 6. 遗留 / 风险

1. **崩溃文件只能靠 App 内日志页或 `adb shell run-as` 读取**：`filesDir` 不对外可读。
   真机排查时若用户无法导出，需 `adb exec-out run-as com.beichen.polaris cat files/crash/xxx.txt`。
   下一步可考虑加「导出崩溃报告」到 Download（需要存储权限，本轮不做，避免扩权限面）。
2. **同一秒内多次崩溃的排序**：第二份文件名是 `crash-<ts>-2.txt`，字典序上略早于
   `crash-<ts>.txt`，极端情况下保留策略可能先删后者。**只影响同秒多次崩溃的保留次序**，
   不影响「保留 N 份」这个总量约束。
3. **`ScreenshotBridge.reportFailure` 会把 `isCapturing` 置 false**：`failSafe` 的 `CLEAR_STATE`
   步在它之前执行，两者一致，无冲突。
4. **ANR / SIGKILL（LMK 杀进程）不会走 UncaughtExceptionHandler**：本轮只覆盖「未捕获异常」类崩溃。
   若用户现象是进程被系统直接杀掉（无 tombstone），仍需 `logcat` / `dropbox` 侧证。
5. **`ui_screenshot` 工具层未改**：本轮只加固服务与桥；工具通过 `ScreenshotBridge.isCapturing`
   与 `activeInstance` 判定，链路失败时拿到 `null` 并走既有失败文案。
6. **未做 `NOTES` 之外的文档更新**，未推送、未联网，工作区等主理人快照。

---

## 7. 全局一致性自检（跨文件）

- `CrashLogger`（core）不依赖 ui / automation；`LogsScreen` 单向依赖 core ✅
- `ScreenCaptureGuard`（automation）只依赖同文件的 `ScreenCaptureStartPolicy`（纯 Kotlin）✅
- `ScreenshotBridge.clearError()` 新增，被 `ScreenCaptureService` 在链路就绪时调用 ✅
- `CrashRecord` 为 public data class（core 顶层），供 ui 直接渲染 ✅
- Manifest 新增 `android:name=".AgentKitApplication"`，类路径 `com.beichen.polaris.AgentKitApplication` 与包声明一致 ✅
- 无重复实现：`guard/guardValue` 只有一处（`ScreenCaptureGuard`），`guard` 委托既有
  `ScreenCaptureStartPolicy.guard`，未复制兜底逻辑 ✅
- 无循环依赖、无未解析引用（编译通过即证）✅

**IS_PASS: YES**
