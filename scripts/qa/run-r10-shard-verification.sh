#!/usr/bin/env bash
# R-10「会话分片」守卫：结构判据 + 自编译行为 harness。
#
# [修的是什么] 修前 conversations.json 是整库单文件：每追加一条消息都要序列化
# 全部会话 → tmp → fsync → 整库复制成 .bak → rename，且 messages/turns 两份正文
# 一起写。上百会话后每次落盘 O(全库)，写放大 2× + 整库 .bak 再 2×。
# 现在主文件只存 id 清单，每个会话一个分片、只写脏会话、只存 turn 真源。
#
# [为什么分两段] 行为层（B 段）真跑临时目录，能证明「改 1 个会话时另外 19 个
# 分片字节没动」；但有一类回归行为测不到——把分片 DTO 里重新加回 messages，
# 小数据用例照样全绿、体积却悄悄翻倍。A 段用结构对账钉住这类退化（形态学
# R-14 的 lib/r14_guard.py）。两段都有对应变异用例（r10-shard-verify/mutate.py）。
#
# [为什么不依赖 AGP 产物] 这批判据是纯 core 的：ConversationStore 的依赖闭包
# 全在 JVM 内（AtomicJsonFile / AppBackgroundHooks / TraceSink / UiItem …），
# 用 kotlinc 自编译即可真跑，不必等 :app:compileDebugKotlin。新 harness 必须
# 能在干净机器真跑，不能一上来就登记进 skip-allowlist。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

echo "== R-10 会话分片守卫 =="
echo ""

# ---------------------------------------------------------------- 依赖解析
KC="${KOTLINC_HOME:-/tmp/kotlinc}"
KOTLINC_BIN="$KC/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || KOTLINC_BIN=$(command -v kotlinc || true)
if [ -z "$KOTLINC_BIN" ] || [ ! -x "$KOTLINC_BIN" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器：$KC/bin/kotlinc" >&2
  exit 3
fi

# kotlinx.serialization 编译器插件：@Serializable 的 serializer 由它生成，
# 少了它 encodeToString/decodeFromString 会在运行时抛 SerializationException，
# 而代码里用 runCatching 兜着 —— 表现是「文件一直没生成」而不是「编译报错」。
PLUGIN=$(qa_find_jar_or_local \
  "$QA_GRADLE_CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" \
  "kotlin-serialization-compiler-plugin-embeddable-*.jar" \
  "kotlinx-serialization-compiler-plugin.jar" 2>/dev/null || true)
if [ -z "$PLUGIN" ] || [ ! -f "$PLUGIN" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 kotlinx-serialization 编译器插件：$KC/lib/kotlinx-serialization-compiler-plugin.jar" >&2
  exit 3
fi

SER_JSON=$(qa_find_jar_or_local \
  "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" \
  "kotlinx-serialization-json-jvm-*.jar" "kotlinx-serialization-json-jvm.jar" 2>/dev/null || true)
SER_CORE=$(qa_find_jar_or_local \
  "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" \
  "kotlinx-serialization-core-jvm-*.jar" "kotlinx-serialization-core-jvm.jar" 2>/dev/null || true)
STDLIB="$KC/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || STDLIB=$(find "$(dirname "$KOTLINC_BIN")/../lib" -name 'kotlin-stdlib.jar' 2>/dev/null | head -1)
if [ -z "$SER_JSON" ] || [ -z "$SER_CORE" ] || [ -z "$STDLIB" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺序列化运行库或 stdlib：$KC/lib/kotlinx-serialization-json-jvm.jar" >&2
  exit 3
fi
CP="$SER_JSON:$SER_CORE"

# ---------------------------------------------------------------- [A] 结构判据
echo "[A] 结构对账（分片 DTO / 落盘层 / 脏追踪 / 纪律）"
A_OUT=$(python3 "$ROOT/scripts/qa/lib/r10_shard_guard.py" 2>&1)
arc=$?
echo "$A_OUT"
echo ""

# ---------------------------------------------------------------- [B] 行为 harness
echo "[B] 行为：自编译真跑（临时目录 + 真文件 + 真重启实例）"
SRC="$ROOT/app/src/main/java/com/beichen/polaris"
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE="$ROOT/core/src/main/java/com/beichen/polaris"
OUT=/tmp/qa-r10-shard
COMPILE_LOG=/tmp/qa-r10-shard-compile.log
rm -rf "$OUT"; mkdir -p "$OUT"

"$KOTLINC_BIN" -nowarn -Xplugin="$PLUGIN" -cp "$CP" -d "$OUT" \
  "$SRC_CORE/core/ConversationStore.kt" \
  "$SRC_CORE/core/ConversationShard.kt" \
  "$SRC_CORE/core/ConversationBranch.kt" \
  "$SRC_CORE/core/UiItem.kt" \
  "$SRC_CORE/core/AgentContract.kt" \
  "$SRC_CORE/core/ToolResult.kt" \
  "$SRC_CORE/core/llm/JsonSafe.kt" \
  "$SRC_CORE/core/io/AtomicJsonFile.kt" \
  "$SRC_CORE/core/lifecycle/AppBackgroundHooks.kt" \
  "$SRC_CORE/core/telemetry/StoreWriteTrace.kt" \
  "$SRC_CORE/core/telemetry/TraceSink.kt" \
  "$ROOT/scripts/qa/R10ShardVerification.kt" \
  > "$COMPILE_LOG" 2>&1

B_OUT=""
if [ ! -d "$OUT/com" ]; then
  B_OUT="  B0 FAIL  自编译失败（日志：$COMPILE_LOG）——行为断言没跑成，不能算通过"
  grep -v '^warning:' "$COMPILE_LOG" | head -12 | sed 's/^/        /'
else
  B_OUT=$(java -cp "$OUT:$CP:$STDLIB" R10ShardVerificationKt 2>&1)
  brc=$?
  # 没有结果行 = harness 中途崩了（R-19 的 N1 教训：进程崩掉时 grep 全不命中，
  # 若默认「没失败就是通过」，整段会静默消失）。
  if ! echo "$B_OUT" | grep -q 'R10 行为结果：'; then
    B_OUT="$B_OUT"$'\n'"  B0 FAIL  行为 harness 没有产出结果行（rc=$brc）"
  fi
fi
echo "$B_OUT"
echo ""

# ---------------------------------------------------------------- 汇总
ALL="$A_OUT"$'\n'"$B_OUT"
p=$(printf '%s\n' "$ALL" | grep -cE '^[[:space:]]*[AB][0-9]+ PASS' || true)
f=$(printf '%s\n' "$ALL" | grep -cE '^[[:space:]]*[AB][0-9]+ FAIL' || true)
p=${p:-0}; f=${f:-0}

# 两段各自都得产出判据：任一段整体崩掉时，另一段的 PASS 不能把它盖过去。
a_pass=$(printf '%s\n' "$A_OUT" | grep -cE '^[[:space:]]*A[0-9]+ PASS' || true)
b_pass=$(printf '%s\n' "$B_OUT" | grep -cE '^[[:space:]]*B[0-9]+ PASS' || true)
a_pass=${a_pass:-0}; b_pass=${b_pass:-0}
if [ "$a_pass" -eq 0 ]; then
  echo "  G0 FAIL  A 段没有产出任何 PASS 判据（结构解析器可能崩了，rc=$arc）"
  f=$((f + 1))
fi
if [ "$b_pass" -eq 0 ]; then
  echo "  G0 FAIL  B 段没有产出任何 PASS 判据（自编译或运行崩了）"
  f=$((f + 1))
fi

echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] && [ "$p" -ge 27 ] && [ "$arc" -eq 0 ] || exit 1
