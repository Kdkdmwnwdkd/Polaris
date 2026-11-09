# 质量体系 / 构建 / 工程纪律 · 只读评审（HEAD 7dd0f88，2026-11-06 18:26）

> 评审线：质量体系 / 构建 / 工程纪律。**未改仓库文件，未跑 gradle**（沙箱无 Android SDK）。
> 所有数字现场统计，不取文档值。可执行的部分实跑了：`assertion-census.py`、`tool-surface-audit.py`、
> `run-runall-verification.sh`、`run-gate-verification.sh`、`run-arch-guard-verification.sh`、`gate.sh`（喂合成日志）。

---

## 结论摘要

1. **量级**：`app/src/main` 下 301 个 `.kt` / 75,207 行；`scripts/qa` 下 87 个 `.kt` / 27,538 行（其中 84 个 `*Verification.kt`）；`run-*.sh` **113 个，减去 `run-all.sh` 后 112 个执行体**。
2. **断言形态**：112 个 runner 里 **92 个真调 Kotlin 编译器并 `java -cp` 执行**，7 个用 Python 做结构化集合对账，13 个纯 bash（其中 2 个是门禁链自测、11 个是真源码文本扫描）。**确实存在验证行为的断言**（`R10ShardVerification.kt:102` 真写 20 个会话数写盘次数；`R19HooksVerification.kt:141` 100 线程并发注册），但文本断言仍占 1368 条，且 **626 条（45.8%）集中在 `Hotfix11Verification.kt` 一个文件**。
3. **门禁链**：`run-all.sh` 已改成 sed 整数解析（`run-all.sh:228` 汇总行），但文档承诺的 `QA_RESULT pass=N fail=N` **没有落地**（全仓 `grep QA_RESULT` 仅命中 docs 两处）。R-17 的 `KS_HARNESS_SKIP` + 缺件 stat 核验确实堵了「单个 runner 靠 `exit 3` 永久消失」，但**留下一个 37.5% 的静默盲区**——我合成 `PASS=70 SKIP=42` 的日志喂给真实 `gate.sh`，结论是 `GATE PASS`（rc=0）。
4. **架构守卫**：只扫 `core/` 一个包、只断言 5 条（实跑输出「架构守卫：5 通过 / 0 失败」）；`arch-baseline.txt` 有 3 条 `layer` + 5 条 `lines`，5 个超标文件的 `wc -l` 与基线**逐字节一致**（无余量）。「只减不增」**没有任何强制**：新增一条 `layer` 豁免会直接变绿，文档里的 `ARCH-EXEMPT:` 协议全仓 0 处实现。
5. **变异测试**：只有 3 个手动 `mutate.py`（r10/r12/r14），**没有任何 workflow 调用它们**；`.github/workflows/` 只有 `build.yml` 一个文件，无 `schedule`。文档 §10「每周注入 1 处」仍是目标态。
6. **CI**：13 个 step，唯一 `actions/cache` 只缓存 llama.cpp 源码（`build.yml:65`）；SDK/NDK/Vulkan 工具链三处供应商下载全程无缓存，任一处抖动即整条红。发布物是 **debug APK**（`build.gradle.kts:60`），直接推 GitHub Release `latest`。
7. **文档漂移**：`README.md:8` 的规模数字全错（252/66,658/69 → 301/75,207/112）。

**核心判断（对「代码继续拆分时会怎样失效」的回答）**：这套体系的失效不是「行数到 100k」，而是**断言的锚点是文件路径、编译闭包是手写清单**这两件事同时成立时，一次拆分会产生两类噪声——编译红（响、可修）和断言红（哑、会被盲改）。临界规模不是总行数，是**单个被拆文件的 fan-in**：`ChatViewModel.kt` 当前 fan-in = 8 个 runner 点名编译 + 8 个 harness 引用 + `Hotfix11Verification.kt` 内 18 处 `readSource("ui/ChatViewModel.kt")` 辐射出的 400+ 条子串断言。详见 §结构性问题 A2/A3。

---

## 关键实证

| 结论 | 证据（现场命令 / file:line） | 判定 |
|---|---|---|
| 应用源码 301 文件 / 75,207 行 | `find app/src -name '*.kt' \| wc -l`；`-exec cat {} + \| wc -l` | ✔ 已复核 |
| harness 侧 87 个 `.kt` / 27,538 行，84 个 `*Verification.kt` | `ls scripts/qa/*.kt \| wc -l`；`ls *Verification.kt \| wc -l` | ✔ 已复核 |
| runner 112 个执行体（+`run-all.sh` = 113 个文件） | `ls scripts/qa/run-*.sh \| wc -l` = 113 | ✔ 已复核 |
| 92 个 runner 真编译 Kotlin 并执行 | 脚本内出现 `KOTLINC`/`kotlinc` 的计数 92；`run-r19-verification.sh:210,218` 为 `K2JVMCompiler` + `java -cp ... R19HooksVerificationKt` | ✔ 已复核 |
| 7 个用 Python 结构对账 | `run-apk / assertion-census / local-agent-prompt / r14-contract / tool-not-enabled / tool-surface / trace-metrics` | ✔ 已复核 |
| 13 个纯 bash（11 源码扫描 + 2 门禁自测） | `run-runall-verification.sh`、`run-gate-verification.sh` 实跑 `run-all.sh`/`gate.sh`；其余如 `run-title-owner-verification.sh:10-17` 直接 `read` 源文件比对 | ✔ 已复核 |
| 文本断言 1368 条 / 53 文件 | `python3 scripts/qa/assertion-census.py --print` → `# 合计 1368 条 / 53 个文件` | ✔ 已复核；**11-09 修正口径**：`has`/`hasnt` 漏计 361 条，实际 1729 / 67 文件 |
| 单文件集中度 45.8% | `Hotfix11Verification.kt` 1722 行内 `assertContains` 602 处，census 计 626 | ✔ 已复核 |
| 行为断言确实存在 | `R10ShardVerification.kt:102`「20 会话改 1 个只写 1 个分片」；`R19HooksVerification.kt:141` 100 owner 并发 | ✔ 已复核 |
| 行为断言调用点量级 ~2.1k | `check(` 1847 处（含每文件 1 处定义，约 1.76k 调用点）、`eq(` 383 处 | ✔ 已复核 |
| 编译闭包硬编码 | runner 内 `.kt` 点名 415 次 / 293 个唯一文件；49 个 runner 显式列 ≥3 个源文件；仅 1 个 runner 用 `find` 全树 | ✔ 已复核 |
| `run-all` 已整数解析，但无 `QA_RESULT` | `run-all.sh:228` `==== 汇总：PASS=$pass SKIP=$skip XFAIL=$xfail FAIL=$fail`；`gate.sh:63` `sed -n 's/.*FAIL=\([0-9]\+\).*/\1/p'` | ✔ 已复核 |
| R-17 exit3 收紧生效 | `run-all.sh:144-182` 三档：无 claim→FAIL / claim 不成立→FAIL / 成立→SKIP；`run-runall-verification.sh` 实跑 40 passed 含场景 17-19 | ✔ 已复核 |
| **但 42/112 全暗仍判绿** | 合成 `/tmp/qa-sim/qa.log`（`PASS=70 SKIP=42`）→ `gate.sh` 输出 `GATE OK 6/6（42/42）` + `GATE PASS`，rc=0 | ✔ 已复核 |
| 缺件 stat 只核「存不存在」，不核「是否必要」 | `run-all.sh:91-99`：抽出所有 `/[A-Za-z0-9_./+-]+` 片段，全部 `! -e` 即认定理由成立；无路径则直接放行 | ✔ 已复核 |
| 架构基线 3 layer + 5 lines | `grep -c '^layer'` = 3；`grep -c '^lines'` = 5 | ✔ 已复核 |
| 5 个超标文件与基线零余量 | `wc -l` = 3869/3157/2309/1516/1504，与 `arch-baseline.txt` 冻结值完全相同 | ✔ 已复核 |
| 守卫本体只有 5 条断言 | 实跑 `run-arch-guard-verification.sh` → `架构守卫：5 通过 / 0 失败` | ✔ 已复核 |
| 豁免可自由新增 | `run-arch-guard-verification.sh:43-48` 命中基线即 `continue`；`[D]` 只校验旧条目仍违规，不校验新增 | ✔ 已复核 |
| `ARCH-EXEMPT:` 未实现 | `grep -rn ARCH-EXEMPT .` 仅 2 处，均在 `docs/architecture/AgentKit-架构方案-2026-11-05.md:135,422` | ✔ 已复核 |
| 变异仅手动 | `scripts/qa/{r10-shard-verify,r12-verify,r14-verify}/mutate.py` 3 个；`grep -rn 'mutate.py'` 仅 2 条注释；`.github/workflows/` 仅 `build.yml` | ✔ 已复核 |
| 5 个 runner 有 opt-in 变异 | `AK_ALLOW_SOURCE_MUTATION=1`：`run-r07/r08/r09/r20/tool-not-enabled` | ✔ 已复核 |
| CI 13 步、单缓存、无 Gradle 缓存 | `build.yml` 13 个 `- name:`；`actions/cache` 仅 `:65`（llama 源码） | ✔ 已复核 |
| 发行物是 debug 包 | `build.gradle.kts:60` `signingConfig = signingConfigs.getByName("debug")`；`:62`「不产出 release 包」；`build.yml:285` 推 `latest` | ✔ 已复核 |
| 依赖 17 条 `implementation` | `grep -cE '^    implementation\('` = 17 | ✔ 已复核 |
| 工具 56 个 / 21,808 字 / 9,761 token | `python3 scripts/tool-surface-audit.py --verbose` 末段 | ✔ 已复核 |
| CI 时长 5–6 分钟 | `docs/ops/网络通道与加速指南.md:220` 自述，无实测 | ○ 推断 |

**112 个执行体的构成（逐脚本静态特征分类）**

| 类型 | 数量 | 占比 | 代表性脚本 | 判据强度 |
|---|---|---|---|---|
| 真编译 Kotlin + `java -cp` 执行 | 92 | 82.1% | `run-r19-verification.sh`（`:210` 编译、`:218` 跑） | 可验行为，但多数同时夹带源码扫描段 |
| Python 结构化集合对账 | 7 | 6.3% | `run-r14-contract-verification.sh`（`lib/r14_guard.py` 做集合差） | 结构断言，比 grep 强，仍非行为 |
| 纯 bash 源码文本扫描 | 11 | 9.8% | `run-title-owner-verification.sh`、`run-output-token-limit-verification.sh` | 只验「代码长什么样」 |
| bash 门禁链自测（真执行 bash） | 2 | 1.8% | `run-runall-verification.sh`、`run-gate-verification.sh` | 真行为，但验的是工具不是产品 |

**1,368 条文本断言的构成**

| 载体 | 条数 | 说明 |
|---|---|---|
| `Hotfix11Verification.kt` | 626（45.8%） | 单文件集中度极高，是「拆分即假红」的最大单点 |
| 其余 52 个文件合计 | 742 | 平均 14.3 条/文件 |
| 其中 bash 侧（`expect/fixed/refute`） | 185 | 13.5% |
| 其中 Kotlin 侧（`assertContains` 857 + 字面量 `.contains("` 326） | 1,183 | 86.5% |

---

## 结构性问题

### A 级（系统性、会随拆分放大）

**A1 · SKIP 登记册把「零覆盖」制度化了，门禁 6 只防「新增」不防「兑付」**
`skip-allowlist.txt` 42 条 / 上限 42 条（`gate.sh:212-220`）。门禁 6 只做三件事：本轮每个 SKIP 必须在册、条目不能超上限、条目指向的 runner 必须存在。它**不检查本轮 SKIP 了多少**。而门禁 5 在 `SKIP>0` 时主动让位（`gate.sh:145-151`）。
实测后果：42 条全部 SKIP、70 条 PASS 的一轮，`gate.sh` 判 **GATE PASS**。也就是这套体系的真实 PASS 下限是 **70，不是 112**；37.5% 的 harness 可以在任何一轮静默暗着，而所有门禁亮绿。R-17 把「悄悄消失」变成了「登记后消失」，但消失本身仍然合法——只是加了一行字。这就是「永久 SKIP」的合规化，不是堵住。

**A2 · 文本断言 45.8% 押在单文件，且按「哪个文件里有什么字」锚定**
`Hotfix11Verification.kt` 1722 行、602 条 `assertContains`、18 处 `readSource("ui/ChatViewModel.kt")`、14 处 `readSource("ui/ChatScreen.kt")`。断言形如 `assertContains("G3", chat, "val profiles by vm.profiles.collectAsStateWithLifecycle()")`，还有 `indexOf(...)` 取位置再断言「其后紧跟 X」（G2）。
**这就是拆分时的失效机制**：`ChatViewModel.kt`（3869 行）按 P1 计划拆成 5 个文件后，这 18 个 `readSource` 全指向空气 → 600 条里一大片变红，但**行为一条没坏**。团队的处理方式必然是「把 readSource 改到新文件」或「改成全树 grep」——前者只挪位置，后者彻底失去局部性（从此这段文字出现在仓库任何地方都算过）。**一次重构，把 600 条断言的判别力换成 600 条「存在性检查」。** 这才是真正的失效，不是红，是绿得没有意义。

**A3 · 编译闭包是手写清单，拆分必红但红在错误的地方**
49 个 runner 显式列出 ≥3 个源文件，全仓 runner 共点名 293 个唯一 `.kt`（占 301 个应用文件的 97%），累计 415 次。只有 1 个 runner 用 `find` 扫全树。
拆 `ChatViewModel.kt` → 8 个 runner 的 `kotlinc ... ChatViewModel.kt` 立刻 `unresolved reference`，错误信息指向新文件不存在，而不是「行为变了」。配合 `run-all.sh` 的 ENV STALE 检查，这种红会被误诊为「编译产物过期」（这类误诊在 `docs/ops/QA-GATE-CHAIN-HARDENING.md` 事故表里已记录过一次）。
**临界规模**：设文件 f 的 fan-in `F(f) = runner 点名数 + harness 引用数`。拆分 f 的净收益 ≈ 减少的行数（150–420 行/步）；净成本 ≈ `F(f)` 次编译清单修补 + 锚定断言重写。以 `ChatViewModel` 计 `F ≈ 8 + 8 + 18 = 34`，一次拆 5 步 ≈ 170 个接触点。**当 `F(f) > 20` 时，一次拆分的验证修补量已经超过被拆代码本身**；当前有 4 个文件达到或接近该线（`ChatViewModel` 34、`ChatScreen` 5+3+14=22、`AppScaffold` 5+1=6、`RealAgentLoop` 3+2=5）。真正先崩的不是 100k 行，是**第一个 `F>20` 的文件被拆的那一天**。

### B 级（结构性，可局部绕开）

**B1 · 架构守卫的覆盖面与判据强度都不足**
只扫 `core/` 对 `ui`/`tools`/`MainActivity` 三处 import，`automation`(30 文件)、`memory`、`skill`、`runtime`、`startup`、`terminal` 完全不在网内。5 条断言覆盖 301 个文件。且 `layer` 豁免一旦写进基线就永远通过——`[D]` 只验证「旧条目仍然违规」，不验证「新增条目为什么必须存在」。文档 §10 的 `ARCH-EXEMPT:` 理由行、D5 的 diff 比对均未实现。**「只减不增」目前是注释，不是代码。**

**B2 · 变异测试零自动化**
3 个 `mutate.py` 质量很高（`r10-shard-verify/mutate.py` 19 组，分 A 段跑 guard.py、B 段跑完整 runner，还刻意保证变异后仍可编译，避免「整体崩成一次笼统红」）。但：没有任何 runner 调用它们、没有 workflow 调用、`grep -c schedule` = 0。最近一次有记录的变异是 `harness-baseline.txt` 里 2026-11-06 的 R-10（19 组）。**能力在，触发不在。** 变异测试的价值是「证明判据有判别力」，只在人记得跑的时候成立，等于没有。

**B3 · 结果协议仍是文本匹配，格式即契约**
`run-all` 与 `gate` 的唯一契约是 `==== 汇总：` 这一行（`gate.sh:59`）。改一个空格、换成英文，全链 `fail "找不到汇总行"`。`QA_RESULT pass=N fail=N` 未落地。`nonzero_failures()` 只看最后一行非空输出（`run-all.sh:118-125`），这是被自测逼出来的妥协（「描述失败的文字被当成失败本身」），副作用是任何在结论后再打印一行的 runner 会绕过该判据，只剩 rc 兜底。

**B4 · CI 的供应商下载单点无缓冲**
`build.yml` 四处外部依赖：① `:40-62` `dl.google.com` 拉 cmdline-tools + sdkmanager 装 NDK 26.3(~1GB)/cmake，无缓存；② `:75` `fetch-llama.sh git` 拉 llama.cpp，仅此一处有 cache；③ `:123` `apt-get install glslc spirv-headers libvulkan-dev`，无缓存；④ `:168` kotlinc shim 从 maven 拉序列化插件（`continue-on-error`）。①③②任一失败 → `assembleDebug` 直接红，无降级。而 `~/.gradle` 完全没有缓存，每次 push 重新解析全部依赖——这是「每次 push 都构建」策略下最贵的隐性成本。

**B5 · 编译产物陈旧与 ENV STALE 只做了一半**
`run-all.sh:37-43` 检测到源码比 `.class` 新就打印 `ENV STALE`，`gate.sh:71-76` 据此判红。但 `deps.sh` 的 `qa_classes_stale` 依赖 `source` 成功；`run-all.sh:41` 用 `2>/dev/null` 吞掉了 source 失败的所有细节，只打印一行 WARN 就继续跑（注释里自己承认「检查器自己失效时，表现是一切正常」）。这是同一个「验证器失效静默」的模式，只是挪到了新位置。

### C 级（账务与表述）

**C1** 文档 8 项漂移，见下一节。
**C2** `gate.sh` 头注释写「门禁六条」，输出却是 `GATE OK 1/5 … 6/6` 混排，第一次读的人会以为少跑一条。
**C3** `run-all.sh:46` 的 `known-broken.txt` 归零是硬门禁（`gate.sh:31-52`），但清单文件里保留了大量历史注释（40 行注释 / 0 条有效项），实际文件 47 行里有效信息为 0——历史记录放在门禁文件里，每次改动都要过一遍 diff 噪声。

---

## 与 v1.0 文档的差异

v1.0 = `docs/architecture/AgentKit-架构方案-2026-11-05.md`。逐条机械核对：

| # | 文档说（出处） | 实际是（今日实测） | 差 |
|---|---|---|---|
| 1 | Kotlin 源码 **252 文件**（`README.md:8`） | 301（`app/src`） | **+49 / +19.4%** |
| 2 | **66,658 行**（`README.md:8`） | 75,207 | **+8,549 / +12.8%** |
| 3 | **69 个 harness**（`README.md:8`、`:50`） | 112 执行体 / 113 文件 | **+43 / +44** |
| 4 | 全量 **22,327 字符 ≈ 10,064 token**（`README.md:33`） | 21,808 字 / 9,761 token | **−519 / −303** |
| 5 | **1370 条文本断言**（§10 表格） | 1,368 条（基线 `__TOTAL__` 1,369） | **−2，基线留 1 条余量** |
| 6 | 「**94 个 runner** 全量跑」（§10 判断段） | 112 | **+18** |
| 7 | 「**81 个 runner** 各自硬编码源文件清单」（§10 判断段） | 49 个显式列 ≥3 个 `.kt`；68 个直接引用 `app/src` 路径（171 行） | 口径不同，但**数值均不成立** |
| 8 | 结果协议改 `QA_RESULT pass=N fail=N`（§10 表格、§9 `run-all.sh:132`） | 仍为 `==== 汇总：PASS=…`（`run-all.sh:228`）；且 `run-all.sh:132` 现在是 APK SKIP 分支 | **目标未落地 + 行号锚点失效** |
| 9 | 豁免必须带 `ARCH-EXEMPT:` 理由（§10 表格、D5） | 全仓 0 处实现，仅 docs 提及 | **未落地** |
| 10 | 变异测试「每周 schedule 注入 1 处」（§10） | 无 schedule，仅 3 个手动 `mutate.py` | **未落地** |
| 11 | 架构守卫「5 文件 + 3 包名」（§10 现状列） | 5 条 `lines` + 3 条 `layer` | ✔ 一致 |
| 12 | B 批报告「48 个 harness、1308 条断言全绿」（`docs/archive/reports/AgentKit-B批交付与验证报告-2026-10-22.md:233`） | 当时口径；今日 112 / 1,368 | 增量 +64 runner / +60 文本断言（说明「新断言必须行为化」这条真的在生效） |

补充事实：`README.md:10` 自己写了「这两个数字会随提交漂移，别当契约看」——等于提前免责，但 `:8` 仍是读者第一眼看到的规模，且 `:54` 的「harness 是纯源码扫描」已被 92/112 的真编译执行推翻。

---

## 建议

| # | 落点 | 验收判据（可机械执行） | 量级 |
|---|---|---|---|
| R1 | `gate.sh` 门禁 6 增「SKIP 预算」 | 新建 `scripts/qa/skip-baseline.txt` 记录「允许的 SKIP 数」=42，且**只减不增**（比对上一版文件，新增行即红）。同时恢复「有 SKIP 时 PASS 仍不得低于 `112 − SKIP`」：合成 `PASS=70 SKIP=42` 的日志必须 **BLOCKED**，本轮实测的 `GATE PASS` 必须变成红。 | ~40 行 bash + 1 文件；自测 +3 场景 |
| R2 | 拆 `Hotfix11Verification.kt` | 先做 G 区（12 组）：`switchProfile` 同步 `_config`、`hostOf` 域名提取两项改为真调 VM 的行为断言（`R10ShardVerification.kt` 已是可抄的范式：临时目录 + 真对象）。验收：`assertion-census.py` 的 `__TOTAL__` 从 1369 降至 ≤900，`Hotfix11Verification.kt` 单文件 ≤300；每条替换断言附 1 次变异记录（改坏必红）。 | 2–3 人日 |
| R3 | `scripts/qa/lib/deps.sh` 增 `qa_compile_closure()` | 用 `find app/src/main/java -name '*.kt'` 或统一走 `app/build/tmp/kotlin-classes/debug` + `-Xfriend-paths`，替换 49 个 runner 的手写清单。验收：把 `ChatViewModel.kt` 任意切一个 200 行的纯函数文件出来，**0 个 runner 需要改编译行**且全量仍绿。 | ~120 行 + 49 处替换 |
| R4 | `run-arch-guard-verification.sh` 加三条 | ① 新增 `layer`/`lines` 条目必须同 commit 带 `ARCH-EXEMPT: <理由>` 行，否则红（用 `git diff` 取新增行，无 git 时退化为「条目数 > 常数即红」）；② 扫描 `automation`/`memory`/`skill`/`runtime`/`terminal`/`ui` 反向依赖；③ 每包行数总量预算。验收：往基线加 1 条 `layer` 必须红；把 `core/` 里某个文件 import `ui.` 必须红。 | ~60 行 bash，断言 5 → ~15 |
| R5 | 新建 `.github/workflows/mutation.yml` | `schedule: cron 每周一次`，从 `r10/r12/r14` 的 `mutate.py` 里随机取 1 组跑，另加 3 个高频 runner 的单点变异（删 `coerceIn`、常量改值）。验收：注入必红且只红对应判据、恢复必绿；连续 4 周记录进 `docs/`。 | 1 workflow + 30 行 |
| R6 | `run-all.sh` 末行协议 | 输出 `QA_RESULT pass=N fail=N skip=N xfail=N`，`gate.sh` 双读兼容一版后切死。验收：注入「10 失败」「0 通过/0 失败」「exit 3 无理由」三种日志，全部判红且不再依赖中文标点。 | ~50 行 + 3 条自测 |
| R7 | CI 缓存与分发（E3） | 加 `actions/cache` 缓存 `~/.gradle/caches` 与 `$ANDROID_HOME`；把「debug 包推 latest」改成「debug 包标 EXPERIMENTAL + 另出一条 release 签名包」。验收：冷构建时长下降可测；Release 页面不再直接提供 `debuggable=true` 的安装包。 | ~20 行 yml + 一个 keystore |

---

## 证据边界

- **未跑 gradle、未跑任何 kotlinc harness**（沙箱无 Android SDK / 无 Gradle 缓存）。因此「断言执行总数」「CI 实际 PASS 数」无法给出——`qa-logs/` 不存在，仓库里没有任何一轮的真实日志。文中 1,368 是**静态文本断言计数**，~2.1k 是 `check()/eq()` **调用点计数**，二者都不是执行次数。
- 92 / 7 / 13 的分类依据是**脚本内是否出现 `KOTLINC` / `python3`** 的静态特征，不是执行结果；`run-apk-verification.sh` 归入 Python 类（它做 APK 静态反验），`run-adb-*` 归入编译类（它们编译的是协议解析器，不真连设备）。
- 实跑并可信的三条：`run-runall-verification.sh` **40 passed / 0 failed**、`run-gate-verification.sh` **33 passed / 0 failed**、`run-arch-guard-verification.sh` **5 passed / 0 failed**。其余 runner 在本环境会 `exit 3`，未纳入统计。
- `GATE PASS`（PASS=70/SKIP=42）是**合成日志**喂给真实 `gate.sh` 的结果，不是真实一轮；目的是证明判据的可达性，不是指控某轮 CI 作弊。
- debug APK 的 `android:debuggable="true"` 是 AGP 对 debug buildType 的默认注入（`build.gradle.kts:56-63` 未覆盖），**未解包实测**，标 ○推断。
- CI 时长取自 `docs/ops/网络通道与加速指南.md:220` 自述「~5~6 分钟」，无运行记录，○推断。
- 任务描述里的 `docs/AgentKit-A批/B批交付与验证报告-2026-10-22.md` **不存在**；实为两份独立文件 `AgentKit-A批交付与验证报告-2026-10-22.md` 与 `AgentKit-B批交付与验证报告-2026-10-22.md`。
- 本报告只读；仓库 `git status` 未产生任何改动（所有模拟文件写在 `/tmp`）。
