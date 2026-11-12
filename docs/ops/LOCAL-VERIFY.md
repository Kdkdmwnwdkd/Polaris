# 本地 / 模拟器验证手册

> 正式包永远由 GitHub Actions 产出。本文只讲「不上 CI、在本机或云服务器上快速验一次」的办法。
> 最后核对：2026-11-12，对照 `ca24eb8`。

## 0. 一句话

```bash
bash polaris-build.sh          # 出 x86 验证包
bash polaris-emu.sh start      # 起模拟器
bash polaris-verify.sh <apk>   # 装包 + 冷启动 + 抓证据
```

## 1. `-PnoNative` 必须带 `=true`

这是最容易踩的坑，且**不报错、只变慢**：

```kotlin
val skipNative = (project.findProperty("noNative") as String?)?.toBoolean() == true
```

Gradle 对无值开关返回空字符串，`"".toBoolean()` 是 `false`。所以：

| 写法 | 实际效果 |
|---|---|
| `-PnoNative` | ❌ 等于没写，照旧编译 arm64 原生库（NDK + CMake 全套） |
| `-PnoNative=true` | ✅ 真的跳过原生库 |

`-Px86Rootfs` 走的是 `project.hasProperty()`，无值也成立，但为了不再混，统一写 `=true`。

失败的典型长相：日志里出现 `-DANDROID_ABI=arm64-v8a` 和 `cmake ... exit value 1`，
在 4 核 / 8G 的机器上还可能直接把内存打满、SSH 被 OOM 踢掉。

## 2. 三个开关各管什么

| 开关 | 作用 | 副作用 |
|---|---|---|
| `-PnoNative=true` | 跳过 llama.cpp / 终端 JNI 的 NDK 编译 | APK 里没有 `lib/*.so`，任何 ABI 都能装 |
| `-Px86Rootfs=true` | 额外打进 x86_64 的 Alpine rootfs | 体积 +30MB 左右 |
| 两者同时 | 走 `cpp/termux-only/CMakeLists.txt`，只编 `agentkit-termux` | 容器页才有 `libagentkit-termux.so` |

只用 `-PnoNative=true` 会连终端 JNI 一起排掉，容器页点下去必然
「libagentkit-termux.so 不可用」。要验容器，必须两个一起给。

## 3. 模拟器：没有 KVM 也能跑，但要知道代价

云容器里 `/dev/kvm` 可能只是个**空的设备节点**：

```
$ emulator -accel-check
Could not open /dev/kvm : No such device
```

此时只能 `-accel off` 纯软件模拟。可接受的代价：

- 冷启动首帧 10s 以上，`am start -W` 常返回 `Status: timeout` —— **这不是崩溃**，
  判据应为「前台包名是 `com.beichen.polaris`」+「crash buffer 无 FATAL/ANR」。
- 建议 `-memory 2048`，并把 Gradle 的 `-Xmx` 压到 1536m，否则 8G 机器会被打满。

## 4. 资源下限

| 项 | 建议 |
|---|---|
| 磁盘 | `/data` 至少留 4G（构建 + 模拟器 + 产物） |
| 内存 | 8G；模拟器 2G + Gradle 1.5G + 系统 1.5G |
| 残留进程 | 每次验完清 Gradle daemon / Kotlin daemon / mock LLM，否则累计 2.5G+ |

## 5. 本机实测（2026-11-12）

- `assembleDebug` 增量 10s，全量约 3–5min
- 冷启动：无 FATAL、无 ANR，引导弹窗正常渲染
- 容器内执行命令在 x86 软件模拟下仍受 proot 限制（`execve("/bin/sh")` ENOENT），
  需 ARM 真机验证
