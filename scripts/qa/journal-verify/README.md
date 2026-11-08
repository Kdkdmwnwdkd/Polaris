# journal-verify

`core/agent/journal/` 四件套的独立验证程序。

不依赖 Android、不依赖 Gradle、不依赖 JUnit——只依赖 `kotlin-stdlib` 与
`kotlinx-coroutines-core`。`stubs/` 里是三个最小替身（`AgentLogger`、`ToolCall`、
`ToolCallOutcome`），让 journal 包能脱离其余源码单独编译。

## 为什么单独一套

主 harness（`scripts/qa/run-*.sh`）要求 Android SDK 才能跑。这套只验证纯逻辑，
任何有 JDK 的机器都能执行，改一行跑一次。

## 跑法

```bash
KC=/path/to/kotlin-compiler.jar        # org.jetbrains.kotlin:kotlin-compiler:2.0.21
STD=/path/to/kotlin-stdlib-2.0.21.jar
CO=/path/to/kotlinx-coroutines-core-jvm-1.8.1.jar
CP=$KC:$CO:$TROVE4J_JAR:$ANNOTATIONS_JAR   # 编译器自身依赖

java -cp $CP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath $STD:$CO -d /tmp/vc \
  stubs/AgentLogger.kt stubs/ToolCall.kt stubs/ToolCallOutcome.kt \
  RepairVerification.kt JournalIoVerification.kt TurnJournalVerification.kt \
  ../../core/src/main/java/com/beichen/polaris/core/agent/journal/RunEvent.kt \
  ../../core/src/main/java/com/beichen/polaris/core/agent/journal/RunJournalRepair.kt \
  ../../core/src/main/java/com/beichen/polaris/core/agent/journal/RunJournal.kt \
  ../../core/src/main/java/com/beichen/polaris/core/agent/journal/TurnJournal.kt

java -cp /tmp/vc:$STD:$CO RepairVerificationKt        # 24 条
java -cp /tmp/vc:$STD:$CO JournalIoVerificationKt     #  9 条
java -cp /tmp/vc:$STD:$CO verify.TurnJournalVerificationKt   # 29 条
```

## 断言对应规格

| 用例 | 规格条目 | 测的是 |
|---|---|---|
| U1 | M4 约束 3 | 平衡日志不被触碰（防误伤） |
| U2 | M4 判据 | 已派发→结局未知 / 未派发→可重试，顺序保持声明序 |
| U3 | M4 约束 2 | 修复幂等 |
| U4 | M3 | 步已关、回合未关 → 只补回合结束 |
| U5 | M4 约束 1 | 不往已关闭的步里补结果，改为记异常 |
| U6 | — | 空日志安全 |
| U7 | M2 格式 | 六类事件编解码往返 |
| U8 | M2 容错 | 半行 / 未知版本 → null，不抛 |
| U9 | M2 净化 | 字段含分隔符不破坏行结构 |
| J1–J6 | M2 / M3 | 落盘顺序、坏行丢弃、扫描、**修复写回**、seq 续接、滚动清理 |
| H1–H7 | M1 / M2 | 完整回合七事件、序号连续、类型顺序、修复器判平衡 |
| O1–O2 | M2 约束 2 | `AssistantCommitted` 严格早于 `ToolDispatched` |
| D1–D6 | M3.1 | 写不进去 → 降级不抛、写操作被拦；空间恢复 → 自动解除 |
| P1–P4 | M2 | 20 协程并发写同一文件：不丢、不重号、不出半行 |
| R1–R7 | M4 | 崩溃尾修复 + 写回文件 + 幂等 |
| N1–N2 | M4 两轴 | 未派发判为可安全重试（与 R3 构成对照） |

## 不测什么

- **`RealAgentLoop` 的接线**（六处调用点）不做源码断言——那是 `src.contains(...)` 式的
  假测试，改了注释就飘。它由主 harness 的行为断言（T01–T06）与真机 `force-stop` 验收覆盖。
- **`ChatViewModel` 的启动对账**同理，靠真机验证：生成到一半 `am force-stop`，
  重进后应看到「N 个写操作可能已经生效」的提示，且**没有自动重做**。

## 真机那一半在哪

`scripts/qa/device/`：`r1-interrupt.sh`（执行中被杀 → 重进不重放）、
`r1-approval-window.sh`（审批弹窗期间被杀 → 应判「未派发」而不是「结局未知」）。
2026-11-03 的实测结果与证据写在《AgentKit 云端环境与 R1 验证记录》第四节。
