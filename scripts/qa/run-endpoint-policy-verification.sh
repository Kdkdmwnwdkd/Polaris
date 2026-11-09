#!/usr/bin/env bash
# 端点明文策略验证（审计 F6）。
#
# [行为] 编译真实的 EndpointPolicy 跑 16 条断言：https / 私网明文 / 公网明文 / 畸形
#        四档分类、IPv4 四类私网段 + CGNAT + 链路本地、IPv6 回环/ULA/链路本地/映射、
#        名字后缀与单标签主机名、以及「有凭据才拒」这条语义。
# [接线] LLMClient / ModelListFetcher 拖 OkHttp，只静态断言调用点确实存在——
#        判据写得再对，没人调它也是白写。
#
# 为什么值得单独一个 runner：这条判据决定「API Key 会不会明文过公网」，
# 而它的实现是纯函数，可以在无 Android 环境下逐条对拍。放在别处会稀释。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
POLICY="$SRC_CORE/core/net/EndpointPolicy.kt"
HARNESS=scripts/qa/EndpointPolicyVerification.kt

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

for f in "$POLICY" "$HARNESS"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

OUT=/tmp/qa-endpoint-policy
RUNTIME="$STDLIB"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    -d "$OUT" -nowarn \
    "$POLICY" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" EndpointPolicyVerificationKt
}

BODY=$(compile_and_run)
if [ $? -ne 0 ] || [ -z "$BODY" ]; then
  echo "$BODY"
  echo "FAIL 编译或运行失败（上面是输出）"
  exit 1
fi
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线"
CLIENT="$SRC_CORE/core/LLMClient.kt"
FETCHER="$SRC_CORE/core/ModelListFetcher.kt"

# G1：发对话请求前必须过一遍判定。只在 UI 拦等于没拦（技能/子代理走的是同一条链路）。
if grep -q 'EndpointPolicy.refusalFor(url, carriesCredential)' "$CLIENT"; then
  g_good "G1 LLMClient 在发请求前调用 EndpointPolicy"
else
  g_bad "G1 LLMClient 没有调用 EndpointPolicy —— 公网明文 + Key 仍会放行"
fi

# G2：凭据判定必须包含自定义头。中转站要求 X-Api-Key 时，Key 不在 apiKey 字段里。
if grep -q 'config.customHeaders.isNotEmpty()' "$CLIENT"; then
  g_good "G2 自定义请求头也计入凭据判定"
else
  g_bad "G2 只看 apiKey，X-Api-Key 形态的中转站会漏"
fi

# G3：判定必须在 requireUsableEndpoint 之后跑。它的 MALFORMED 分支负责「端点未配置」，
# 顺序反了会让空端点报成「公网明文」，用户按提示改也改不好。
if [ "$(grep -n 'private fun requireUsableEndpoint' "$CLIENT" | cut -d: -f1)" -lt \
     "$(grep -n 'EndpointPolicy.refusalFor(url, carriesCredential)' "$CLIENT" | cut -d: -f1)" ]; then
  g_good "G3 判定在「端点未配置」分支之后（畸形端点仍报原话）"
else
  g_bad "G3 判定插在了端点未配置分支之前，空端点会被误报"
fi

# G4：拉模型列表也要过同一把尺子——否则用户能在「选择模型」里把 Key 明文送出去。
if grep -q 'EndpointPolicy.refusalFor(base, key.isNotBlank())' "$FETCHER"; then
  g_good "G4 ModelListFetcher 用同一判据"
else
  g_bad "G4 ModelListFetcher 未接入 —— 拉模型列表仍会明文发 Key"
fi

# G5：拒绝必须是 400 且不重试。5xx/429 才进退避，400 会把同一串 Key 再发三次。
if grep -q 'carriesCredential)?.let { reason ->' "$CLIENT" && grep -q 'code = 400' "$CLIENT"; then
  g_good "G5 拒绝走 400（不可重试），不进退避"
else
  g_bad "G5 拒绝没有落在 400 上"
fi

# G6：余额接口也带 Bearer，同一条规则；不能只在对话链上修。
BALANCE="$SRC_CORE/core/llm/BalanceService.kt"
if grep -q 'EndpointPolicy.refusalFor(url, apiKey.isNotBlank())' "$BALANCE"; then
  g_good "G6 BalanceService 用同一判据"
else
  g_bad "G6 BalanceService 未接入 —— 查余额仍会明文发 Key"
fi

# G7：媒体 provider 的 baseUrl 与 LLM 配置同源（MediaRegistry 构造时传入），
# 同为「用户可填的公网明文」入口。
MEDIA="$SRC_CORE/core/media/providers/OpenAIMediaProvider.kt"
if grep -q 'EndpointPolicy.refusalFor(baseUrl, apiKey.isNotBlank())' "$MEDIA" \
   && grep -q 'requireSafeEndpoint()' "$MEDIA"; then
  g_good "G7 媒体 provider 在 submit 前校验端点"
else
  g_bad "G7 媒体 provider 未接入 —— 出图请求仍会明文发 Key"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
