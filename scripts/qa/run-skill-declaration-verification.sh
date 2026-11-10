#!/usr/bin/env bash
# 「技能声明的可见性」验证（审计遗留项：安装页看不见 net_hosts）。
#
# [行为] 编译真实的 SkillDeclarations / HostAllowlist / EndpointPolicy 跑 24 条断言：
#        归一与去重、非法项单列（裸 * / 协议 / 路径 / 前缀通配 / 端口）、四种文案互不顶替、
#        警告互斥、以及一条不变量——**UI 列出的域名 ⟺ 运行时放行的域名**。
# [接线] 三处 UI 与一处落盘顺序只能静态断言（Compose 与 Android 拖不进纯 JVM）：
#        市场安装是否真的「确认后才落盘」、技能中心是否真的展示声明、授权框是否真的列出域名。
#
# 为什么值得单独一个 runner：上一轮把「脚本能联网」做成了实现（判定成立），
# 却没做成体验（用户看不见那串域名）。这条差距只有断言能钉住——
# 判据写得再对，没展示就等于用户没同意。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/SkillDeclarationVerification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"

OUT=/tmp/qa-skill-decl
RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

SOURCES=(
  "$SRC_CORE/core/net/EndpointPolicy.kt"
  "$SRC_CORE/core/net/HostAllowlist.kt"
  "$SRC_CORE/skill/SkillDeclaration.kt"
)

for f in "${SOURCES[@]}" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    -d "$OUT" -nowarn \
    "${SOURCES[@]}" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" SkillDeclarationVerificationKt
}

BODY=$(compile_and_run); rc=$?
# 两种失败分开报：编译不过（无产物）与断言不过（有产物、退出码非 0）。
if [ ! -d "$OUT/com" ]; then
  echo "$BODY"
  echo "FAIL 编译失败（上面是编译器输出）"
  exit 1
fi
if [ "$rc" -ne 0 ]; then
  echo "$BODY"
  echo "FAIL 行为断言未通过（见上方 FAIL 行）"
  exit 1
fi
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [W] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
w_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[W] 接线"
FACADE=app/src/main/java/com/beichen/polaris/ui/MarketFacade.kt
MARKET_UI=app/src/main/java/com/beichen/polaris/ui/SkillMarketScreen.kt
SKILLS_UI=app/src/main/java/com/beichen/polaris/ui/SkillsScreen.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt

# W1：确认必须发生在落盘之前。若 installZip 在设置 pending 之前，用户点「安装」
# 那一刻包已经解压完了，确认框只是个事后通知。
PENDING_LINE=$(grep -n '_marketPending.value = MarketInstallPreview(' "$FACADE" | head -1 | cut -d: -f1)
INSTALL_LINE=$(grep -n 'importRepo.installZip(bytes).getOrThrow()' "$FACADE" | head -1 | cut -d: -f1)
if [ -n "$PENDING_LINE" ] && [ -n "$INSTALL_LINE" ] && [ "$PENDING_LINE" -lt "$INSTALL_LINE" ]; then
  w_good "W1 市场安装先设置待确认、后落盘（installZip 在确认之后）"
else
  w_bad "W1 下载完就落盘了 —— 确认框变成事后通知"
fi

# W2：声明必须来自包内 manifest，不能来自索引的 permissions（远端可随意填写）。
if grep -q 'SkillDeclarations.of(manifest.net_hosts, manifest.entry, manifest.tools)' "$FACADE"; then
  w_good "W2 市场确认用的是包内 skill.json 的声明"
else
  w_bad "W2 市场确认没读包内 manifest —— 展示的可能是索引里的营销文案"
fi

# W3：市场页必须真的渲染等待中的安装（有状态没 UI 等于没有确认框）。
if grep -q 'vm.confirmMarketInstall()' "$MARKET_UI" && grep -q 'vm.cancelMarketInstall()' "$MARKET_UI"; then
  w_good "W3 市场页挂了确认/取消两个动作"
else
  w_bad "W3 市场页没有确认入口 —— pending 永远悬着"
fi

# W4：技能中心的授权框必须列出域名（授权正是 net_hosts 生效的那一刻）。
if grep -q 'details = { SkillDeclarationDetails(skill.declaration) }' "$SKILLS_UI"; then
  w_good "W4 授权脚本确认框列出联网域名"
else
  w_bad "W4 授权框没列域名 —— 用户批准的是他没看过的东西"
fi

# W5：卡片必须常驻展示声明（授权之后还得能查「它当初要连哪里」）。
if grep -q 'SkillDeclarations.hostLine(skill.declaration)' "$SKILLS_UI"; then
  w_good "W5 技能卡展示声明行"
else
  w_bad "W5 技能卡没有声明行"
fi

# W6：UI 数据必须由 manifest 计算，而不是空实现（空声明 = 所有技能都显示「未声明」）。
if grep -q 'declaration = SkillDeclarations.of(sk.net_hosts, sk.entry, sk.tools)' "$VM"; then
  w_good "W6 SkillUiInfo 的声明由 manifest 计算"
else
  w_bad "W6 SkillUiInfo 没有接上声明计算 —— 卡片会恒显示「未声明域名」"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
