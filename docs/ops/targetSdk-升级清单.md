# targetSdk 升级清单

> 依据 [`全功能架构对照-问题驱动-2026-11-18.md`](../architecture/全功能架构对照-问题驱动-2026-11-18.md) §2、§11。
> 原话：「targetSdk=28 是**有到期日的债**，升级到 34+ 时：`foregroundServiceType` 必须声明、
> `dataSync` 配额要处理、`POST_NOTIFICATIONS` 变成硬要求。现在就把这三条写进升级清单，别等到那天现查。」
>
> 本文就是那份「清单」。**升级 targetSdk 不是改一个数字，是会杀死容器功能的一次结构改动**——先读完第一节再动。

---

## 1. 现状与「为什么是 28」

| 项 | 当前值 | 位置 |
|---|---|---|
| `compileSdk` | **34** | `app/build.gradle.kts:94` |
| `targetSdk` | **28** | `app/build.gradle.kts:125` |
| `minSdk` | 26 | `app/build.gradle.kts:118` |

`targetSdk=28` 是**功能前提**，不是疏忽。Android 10（API 29）起，对
`targetSdk >= 29` 的应用**禁止从应用数据目录调用 `execve`（W^X）**。
本项目容器（proot + busybox + 之后装的 node/python）全部落在 `filesDir`，
proot 一进 guest 就必然 `error=13` / `execve("/bin/sh")` 失败，且**改代码绕不开**。
Termux 至今保持 targetSdk 28，正是同一原因。证据注释在 `app/build.gradle.kts:119-125`。

**结论**：把 targetSdk 从 28 抬到 33/34，容器（`proot` 那条链）会直接不可用。
所以「升级」不是「改版本号 + 处理三条系统要求」，而是**先解决容器执行模型，再谈版本**。

---

## 2. 升级触发条件（什么时候必须动）

| 触发 | 说明 |
|---|---|
| 上架 Google Play | Play 当前要求 `targetSdk >= 33`，低于会被 `ExpiredTargetSdkVersion` 拦（`app/build.gradle.kts:158` 已注释） |
| 需要 Scoped Storage / 分区存储 | 要 `READ_MEDIA_*` 按需授权、不要 `MANAGE_EXTERNAL_STORAGE` 时 |
| 需要 Android 14+ 的前台服务类型强制、精确闹钟等新约束 | 部分厂商 ROM 也会对低 targetSdk 应用降级处理 |

**不触发就不要升**：本项目走 GitHub Release 侧载，不依赖 Play 规则，28 的功能红利
（自由读写 `/sdcard`、数据目录 execve）是刚需。

---

## 3. 升级当天要处理的硬要求（按「会不会挂」排序）

### 3.0 前置：容器执行模型（不解决这个，别的都白搭）

`targetSdk >= 29` 后，从应用数据目录 `execve` 会被 W^X 拦截。可选出路（**未实现，升级前必须选一条并落地**）：

| 方案 | 代价 | 状态 |
|---|---|---|
| 容器 rootfs 挪到外部存储（`/sdcard`） | 依赖 `MANAGE_EXTERNAL_STORAGE`（Play 侧载仍可）；读写性能下降 | 未做 |
| 走 `shizuku` 的 uid 2000 shell 执行，proot 降级为可选 | 需要 Shizuku 常驻；`CapabilityChain` 已有「调试通道」候选，但容器语义不同 | 部分有（`automation/adb`、`ShizukuShell`） |
| 放弃容器，退回「App 内 shell」 | 容器页整个功能退化为下载模式（`assets/containers/alpine.tar` 已内置，删掉即可） | 未做 |

**没有这三条之一，升级 = 容器功能死亡。** 这是 P0 清单 §2/§11 没写进三件套里、
但 `build.gradle.kts:119-123` 已经写明的前置。

### 3.1 `foregroundServiceType` 必须声明

`targetSdk >= 34` 起，启动前台服务必须声明类型，否则 `MissingForegroundServiceTypeException`。

- **已预埋**：`AndroidManifest.xml:14-18` 已声明 `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_SHORT_SERVICE` / `_MICROPHONE` / `_DATA_SYNC` / `_SPECIAL_USE`；
  `:143/:149/:173` 的 service 已带 `android:foregroundServiceType="dataSync"` /
  `"specialUse"`。
- **升级时要核对**：每个 `startForeground` 调用的 service 都声明了对应 type，且 type
  与权限一一对应（`dataSync` 类型对应 `FOREGROUND_SERVICE_DATA_SYNC` 权限，Manifest 已声明）。

### 3.2 `dataSync` 的 6 小时配额

`targetSdk >= 35`（Android 15）对 `dataSync` 类型 FGS 有 **6 小时/24h 配额**，
超时 `Service.onTimeout` 抛 `InternalServiceTimeoutException`，不处理就崩。

- **现状**：`AgentRunService` 是前台服务保活，类注释已写「防不了强杀，进程被杀不自动续跑」。
- **升级时要补**：`onTimeout(int)` 覆写 + 熔断降级（把配额用尽后的行为定义成「降级到
  非 FGS / 提示用户 / 停后台任务」，而不是直接崩）。`dataSync` 配额与 `DataSyncRequest`
  不同，别混——这里是 FGS 类型配额，不是 WorkManager。

### 3.3 `POST_NOTIFICATIONS` 变硬要求

`targetSdk >= 33` 起，发通知必须先申请运行时权限，否则通知静默丢弃（不崩、不报错）。

- **已预埋**：`AndroidManifest.xml:11` 已声明；`SettingsScreen.kt:252-256` 已做运行时
  请求（`notifyPermissionLauncher.launch`）。
- **升级时要核对**：所有「发通知」入口（磁贴、任务完成、后台续跑提醒）都先判断
  `areNotificationsEnabled`，被拒时给降级路径（桌宠磁贴是它的替代入口，HANDOFF §6 已记）。

### 3.4 其余「28 的红利」逐一核销

| 红利（28 才有） | 升级到 34+ 后 | 要补的 |
|---|---|---|
| 自由读写 `/sdcard` | 失效 | `READ_MEDIA_*` 按需 + `MANAGE_EXTERNAL_STORAGE` 权衡（Manifest:25-28 已声明前三个，`:28` 是全文件访问） |
| `requestLegacyExternalStorage` | 失效 | Manifest:59-68 该属性只为 28 服务，升级后删 |
| WebView 旧默认 | 变新默认 | 检查自定义 scheme / 文件访问是否受影响 |
| 包可见性 | 收紧 | 若 `queryIntentActivities` 依赖旧默认，需 `queries` 声明 |

---

## 4. 保持 28 的代价（README 要摊开写）

P0 清单 §11 原话：「必须在 README 摊开写：这不是我们偷懒，是我们选了这条路」。
代价清单（都源自 28）：

| 代价 | 影响 |
|---|---|
| `ExpiredTargetSdkVersion`（低于 Play 要求 33） | 上不了 Google Play，只能侧载（现状即侧载，可接受） |
| 明文全局放开 | 通知/存储旧模式，安全缓解少拿三条（系统版本级） |
| Scoped Storage 失效 | 只能靠 `MANAGE_EXTERNAL_STORAGE` 全文件访问 |
| WebView 旧默认 | 新默认的隐私/安全改进拿不到 |
| FGS 类型不强制 | 省了配额约束，但 Android 15 的配额红利也用不上 |

---

## 5. 反查锚点（升级时逐条对着改）

| 约束 | 代码位置 |
|---|---|
| targetSdk=28 的理由（W^X / execve） | `app/build.gradle.kts:119-125` |
| lint 关闭 + ExpiredTargetSdkVersion 根因 | `app/build.gradle.kts:151-176` |
| FGS 权限声明 | `app/src/main/AndroidManifest.xml:14-18` |
| FGS type 声明 | `app/src/main/AndroidManifest.xml:143,149,173` |
| POST_NOTIFICATIONS 声明 + 运行时请求 | `AndroidManifest.xml:11` + `app/.../ui/SettingsScreen.kt:252-256` |
| requestLegacyExternalStorage（升级后删） | `AndroidManifest.xml:59-68` |
| READ 权限 / 全文件访问 | `AndroidManifest.xml:25-28` |
| AgentRunService 保活边界 | `app/.../AgentRunService.kt`（类注释） |
| CapabilityChain「调试通道」候选（3.0 出路之一） | `app/.../capability/CapabilityChain.kt` |

---

*本文只做「记账」：把散落在 `build.gradle.kts` 注释、Manifest、架构文档里的升级约束
收敛成单一真源。任何一条与代码不一致时，以代码为准并回来改这里。*
