#!/usr/bin/env bash
# W-20 harness：edit_file 决策纯函数 + web_search 解析纯函数（纯 JVM，不加载 Android）。
#
# 构件解析两级：优先 Gradle 缓存（CI/完整开发机），缺失时回落本机 kotlinc 发行版
# （沙箱环境 /tmp/kotlinc）——沙箱无 Gradle 缓存，但验证口径不变。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-key-pool

# W-41：缓存根按环境推导（CI 是 /home/runner/.gradle），可被 AGENTKIT_GRADLE_CACHE 覆盖
: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
KOTLINC_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-*.jar" 2>/dev/null | grep -v sources | head -1) || true
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9*.jar" 2>/dev/null | grep -v sources | head -1) || true

# 沙箱回落：完整 kotlinc 发行版
if [ -z "${KOTLINC_JAR:-}" ] || [ -z "${STDLIB:-}" ]; then
  KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
  if [ -x "$KC_HOME/bin/kotlinc" ]; then
    KOTLINC_BIN="$KC_HOME/bin/kotlinc"
    STDLIB=${STDLIB:-$KC_HOME/lib/kotlin-stdlib.jar}
  fi
fi

if [ -z "${KOTLINC_BIN:-}" ] && [ -z "${KOTLINC_JAR:-}" ]; then
  echo "FATAL: 找不到 kotlinc（Gradle 缓存与本机发行版均缺失）" >&2
  exit 1
fi

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/KeyPoolPolicy.kt"
SRC2="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/KeyPoolRuntime.kt"
SRC3="$ROOT/scripts/qa/KeyPoolVerification.kt"
for s in "$SRC1" "$SRC2" "$SRC3"; do
  [ -f "$s" ] || { echo "FATAL: 缺少源文件 $s" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling edit/websearch harness ..."
if [ -n "${KOTLINC_BIN:-}" ]; then
  "$KOTLINC_BIN" -classpath "$STDLIB" -d "$OUT" "$SRC1" "$SRC2" "$SRC3"
else
  TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name "trove4j-*.jar" 2>/dev/null | head -1) || true
  JB_ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name "annotations-*.jar" 2>/dev/null | head -1) || true
  KCP="$KOTLINC_JAR:$STDLIB"
  [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ] && KCP="$KCP:$TROVE"
  [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && KCP="$KCP:$JB_ANNOTATIONS"
  java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" "$SRC1" "$SRC2" "$SRC3"
fi

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" KeyPoolVerificationKt
