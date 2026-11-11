# 签名与密钥管理

> 这份文件只记录**公开身份**，不含任何私钥或口令。
> 私钥只存在于两处：GitHub Actions Secrets（`POLARIS_KEYSTORE_B64` 等四个）和离线备份。
> 判断标准很简单：**这里写的东西，贴到公告栏上也不会有损失**；写不了的，就不该出现在 git 里。

## 一、当前签名身份

| 项 | 值 |
|---|---|
| 包名 | `com.beichen.polaris` |
| 密钥别名 | `polaris` |
| 密钥库格式 | PKCS12（`polaris-release.jks`） |
| 密钥算法 | RSA 4096 / `SHA384withRSA` |
| 证书主题 | `CN=Beichen Polaris, OU=Polaris, O=Beichen, C=CN` |
| 有效期 | 2026-11-07 → 2054-03-25 |
| 证书 SHA-256 | `09:7F:9C:B0:D2:7D:58:50:43:A0:17:4D:58:A2:4C:27:A5:FA:BD:B6:61:C3:CC:6F:00:3C:AC:64:E7:DE:94:39` |

指纹是核对「某个 APK 是不是本项目的包」的唯一凭据，**公开它不会削弱安全性**——
它只能用来验签，不能用来签名。

```bash
keytool -printcert -jarfile app-debug.apk | grep SHA256
# 或
apksigner verify --print-certs app-debug.apk
```

## 二、轮转历史

| 时期 | 密钥 | 状态 |
|---|---|---|
| 2026-10-12 → 2026-11-07 | `app/debug.keystore`（口令 `android`，别名 `androiddebugkey`） | **已作废**。曾以 git 跟踪形式提交在公开仓库，且被当作发布签名使用 |
| 2026-11-07 → 现在 | `polaris-release.jks`（别名 `polaris`） | 在用。私钥不入库，CI 从 Secrets 解到 `$RUNNER_TEMP` |

作废理由：旧密钥公开可下载，任何人都能签出「包名与证书一致、被系统视为正常升级」的 APK，
覆盖安装还会继承已授予的无障碍与通知监听权限。旧证书留在 git 历史里不构成风险——
它已经签不出任何被系统认可的升级包，因此**不需要重写历史**。

代价：2026-11-07 之前安装的包无法直接升级到新签名的包，必须卸载重装一次
（数据、无障碍授权、通知监听、Shizuku 授权都会清空；容器文件不会自动迁移）。
此后永久正常覆盖升级。

## 三、绝不入库清单

| 东西 | 为什么 |
|---|---|
| `*.jks` / `*.keystore` / `*.p12` | 私钥本体。泄露 = 任何人可冒充发布者 |
| `storePassword` / `keyPassword` | 与私钥合在一起才是完整凭证，但别赌「只泄露口令没事」 |
| 密钥库的 base64 | 等价于私钥本体，只是换了编码 |
| `local.properties` | 上述值的常见落点，已被 `.gitignore` 排除 |
| CI 日志里的 `keytool -list -v` 输出 | 只允许出现指纹和有效期，不允许出现口令（现有 workflow 已按此裁剪） |

`.gitignore` 已封 `*.jks`、`*.keystore`、`local.properties`。新增密钥文件时**先确认
`git status` 里看不到它**，再谈别的。

## 四、升级契约：什么变了会导致装不上

Android 允许覆盖安装，只认三件事，**三件同时满足**才行：

1. `applicationId` 相同（本项目 `com.beichen.polaris`）
2. 签名证书相同（同一把钥匙）
3. `versionCode` 不小于已装版本

任何一条不满足，系统只会弹一句「应用未安装」，不告诉你为什么。用 `adb install` 才看得到具体原因：

| 错误码 | 含义 | 处置 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 证书不同 | 卸载重装；或事先用 `apksigner rotate` 做 lineage |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | versionCode 比已装的小 | 抬 versionCode（CI 用 run number，天然递增） |
| `INSTALL_FAILED_ALREADY_EXISTS` | 包名撞了、签名不同 | 同上，本质是证书不同 |
| 解析包时出错 | APK 损坏或签名块被破坏（常见于先签名后 zipalign） | 重新构建，别手工签名 |

改动的影响面：

| 动了什么 | 后果 |
|---|---|
| 换密钥 | 所有老用户装不上，必须卸载重装 |
| 改 `applicationId` | 变成另一个应用，老应用还在、数据不迁移。**一般不要改** |
| versionCode 变小 | 拒绝安装 |
| 只改 versionName | 无影响 |
| 调高 minSdk | 低版本设备装不上 |
| 增删权限 | 可覆盖装；新增的运行时权限要重新授权 |

> 想换包名来「绕过」签名不符，是反向的：包名和证书是两件独立的事，换包名只会
> 让老应用变成孤儿，签名问题原样保留，还多一套要维护的身份。

## 五、CI 侧契约

`.github/workflows/build.yml` 的 `Decode signing key` 步骤：

1. 从 `POLARIS_KEYSTORE_B64` 解出 jks 到 `$RUNNER_TEMP/polaris-release.jks`（工作树之外，
   打包上传也带不走）；
2. 用 `keytool -list` 先探一次，口令错/文件截断在这一步就红，而不是等到构建末尾；
3. 把四个值写进 `$GITHUB_ENV`，并置 `POLARIS_REQUIRE_RELEASE_KEY=1`；
4. 指纹打进日志，方便日后核对「这个包是哪把钥匙签的」。

任一 Secret 缺失 → 构建失败，**不会**静默回退 debug 证书。这是有意为之：
一个「能装、但以后永远升不了级」的包，比一次红的构建贵得多。

构建完成后还有一道 `Verify APK identity (包名 + 签名指纹)`：

- 用 `apksigner verify --print-certs` 读**产物本身的**证书指纹，与本文件第一节比对，
  不一致直接红——防的是「Secrets 被换成另一把钥匙但没人发现」；
- 用 `aapt2 dump badging` 读 `applicationId`，不等于 `com.beichen.polaris` 直接红——
  防的是「有人改了包名，老用户全部装不上」。

两道判据都在构建阶段，不依赖用户装机时才发现。

## 六、本地出包

`app/build.gradle.kts` 的取值优先级：**环境变量 > 仓库根 `local.properties`**。

```properties
# local.properties（已 gitignore）
polaris.storeFile=~/.polaris/polaris-release.jks
polaris.storePassword=……
polaris.keyAlias=polaris
polaris.keyPassword=……
polaris.versionCode=600
```

规则：

- 四项一个都不配 → 回退本机 debug 证书（能装能跑，但覆盖不了已发布的包，**不报错**）；
- 配了一部分或路径写错 → 构建立刻失败并列出缺项（不替使用者猜）；
- 四项齐全且文件可读 → 用发布证书签。

`polaris.versionCode` 不是可选项：线上版本号来自 CI run number（已 500+），
本地默认回退值只有 14，不抬就会被系统以「版本回退」拒绝安装。

## 七、多个应用怎么办

不要为签名单独开一个 git 仓库。私有仓库同样会经过：协作者的每个 clone、fork、
CI 缓存、误设为 public 的那一次、以及删除后仍留在历史里的每一次提交。
**「放进仓库」和「公开」之间只差一次误操作**，而这个项目已经付过一次学费。

按用途分开存：

| 存什么 | 放哪 |
|---|---|
| 私钥文件（多应用可放同一个加密归档） | 密码管理器附件 / 加密 7z + 私有云盘，两处异地 |
| 口令 | 密码管理器条目，**与私钥分开** |
| 每个应用的公开身份（包名、别名、指纹、有效期） | 各项目仓库里的 `docs/ops/SIGNING.md`，就是本文件 |
| CI 用的 base64 + 口令 | 各仓库的 Actions Secrets |

多应用时，本文件的「一、当前签名身份」就是模板：一个应用一张表，指纹公开、私钥离线。
需要跨项目总览，就再维护一张只有「应用名 / 仓库 / Secrets 名称 / 指纹」的索引表——
索引表同样不含秘密，放哪都行，但**没有必须放进 git 的理由**。

## 八、给下一个接手的人（或 AI）的 30 秒摘要

换新对话、换人接手时，**不需要重新要一遍包名和签名信息**，读完这一节就够：

```
应用        Polaris（北辰），Android 本地 AI Agent 运行时
包名        com.beichen.polaris        ← 改了老用户全部装不上
密钥别名    polaris
证书指纹    09:7F:9C:B0:D2:7D:58:50:43:A0:17:4D:58:A2:4C:27:A5:FA:BD:B6:61:C3:CC:6F:00:3C:AC:64:E7:DE:94:39
有效期      2026-11-07 → 2054-03-25
私钥在哪    GitHub Actions Secrets 四个变量 + 用户离线备份。不在仓库，不要索取，不要外发
出包        推 main → CI 构建 → Release latest 的 app-debug.apk
装不上先看  签名不符（换过钥匙）还是版本回退（versionCode 变小）
```

红线：私钥、口令、base64 一律不进仓库、不进聊天、不进日志。除此之外的东西
（包名、指纹、别名、有效期）都可以公开，公开了也不削弱安全。
