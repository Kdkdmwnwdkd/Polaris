#!/usr/bin/env bash
# W-54「技能脚本的网络出口」验证（审计遗留项：agent.http 恒返回 script_approval_denied）。
#
# [行为] 编译真实的 HostAllowlist / RedirectPolicy / SkillNetGrant 跑 32 条断言：
#        声明校验（裸 * / 带协议 / 前缀通配）、主机匹配的点边界、跳转判定的跨域与降级、
#        以及授权三道（来源 / 白名单 / 公网明文）必须同时满足。
# [接线] SkillExecutor 与 BuiltInTools 拖 Android / OkHttp，只能静态断言：
#        桥与执行器用的是**同一个**判据、重定向真的关掉了自动跟随。
#
# 为什么值得单独一个 runner：这两组判据决定「一个装了第三方技能的人，会不会在
# 无任何弹窗的情况下把设备内容发去任意域名」。全是纯函数，可以在无 Android 环境下逐条对拍。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
HARNESS=scripts/qa/SkillNetAllowlistVerification.kt

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-serialization-json-jvm" "$SER_JSON"

OUT=/tmp/qa-skill-net
RUNTIME="$STDLIB:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

SOURCES=(
  "$SRC_CORE/core/net/EndpointPolicy.kt"
  "$SRC_CORE/core/net/HostAllowlist.kt"
  "$SRC_CORE/core/net/RedirectPolicy.kt"
  "$SRC_CORE/core/tool/ToolCatalog.kt"
  "$SRC_CORE/skill/SkillSecurity.kt"
  "$SRC_CORE/skill/SkillNetGrant.kt"
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
  java -cp "$OUT:$RUNTIME" SkillNetAllowlistVerificationKt
}

BODY=$(compile_and_run); rc=$?
# 编译不过与断言不过必须分开报：混成一句会让真回归看起来像环境问题。
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
JS=$(echo "$BODY" | grep -oE 'NetAllowlist: [0-9]+/[0-9]+ PASS' | tail -1)
jp=$(echo "$JS" | grep -oE '[0-9]+/[0-9]+' | cut -d/ -f1); jp=${jp:-0}
jf=0

# ── [W] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
w_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
w_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[W] 接线"
EXECUTOR="$SRC_CORE/skill/SkillExecutor.kt"
TOOLS=app/src/main/java/com/beichen/polaris/tools/BuiltInTools.kt
PROTOCOL="$SRC_CORE/skill/SkillProtocol.kt"

# W1：桥的第三道门必须真的调判据。只加纯函数不接，等于什么都没修。
if grep -q 'SkillNetGrant.evaluate' "$EXECUTOR"; then
  w_good "W1 脚本桥的审批门走 SkillNetGrant.evaluate"
else
  w_bad "W1 脚本桥没有接 SkillNetGrant —— agent.http 仍然恒返回 script_approval_denied"
fi

# W2：执行器的第二道网必须是**同一个**判据，不能还是恒 false，
# 否则桥放行了、执行器又拒成 approval_denied，表现为「声明了域名也没用」。
if grep -q 'SkillNetGrant.allows' "$EXECUTOR"; then
  w_good "W2 执行器 approval 与桥共用同一判据"
else
  w_bad "W2 执行器 approval 未接同一判据 —— 声明了域名的请求会被执行器二次拒掉"
fi

# W3：拒绝码要能区分「没声明域名」与「需要逐次确认」，否则技能作者不知道该改哪里。
if grep -q 'script_net_denied' "$EXECUTOR"; then
  w_good "W3 网络拒绝有独立错误码 script_net_denied"
else
  w_bad "W3 网络拒绝仍混用 script_approval_denied —— 作者会以为只能回主对话手工调"
fi

# W4：OkHttp 必须关掉自动跟随。开着的话，一次 302 就能绕过域名级授权。
if grep -q 'followRedirects(false)' "$TOOLS"; then
  w_good "W4 http_request 关闭自动重定向"
else
  w_bad "W4 http_request 仍自动跟随重定向 —— 302 一次即可跨域带走数据"
fi

# W5：关掉自动跟随后必须手动逐跳判定，否则所有 3xx 都变成「原地失败」。
if grep -q 'RedirectPolicy.decide' "$TOOLS"; then
  w_good "W5 逐跳交给 RedirectPolicy 判定"
else
  w_bad "W5 没有逐跳判定 —— 同源跳转（补尾斜杠）会全部失败"
fi

# W6：manifest 必须有这个字段，否则技能作者无处声明。
if grep -q 'val net_hosts' "$PROTOCOL"; then
  w_good "W6 SkillManifest 有 net_hosts 字段"
else
  w_bad "W6 SkillManifest 缺 net_hosts —— 技能无法声明网络范围"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
