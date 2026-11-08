#!/usr/bin/env bash
# P0 权限引导 harness：行为断言（纯函数）+ 少量源码级耦合断言。
#
# 与其它 runner 一致的两级构件解析：优先 Gradle 缓存（CI / 完整开发机），
# 缺失时回落本机 kotlinc 发行版（沙箱）。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-permission-guide

: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
KOTLINC_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-*.jar" 2>/dev/null | grep -v sources | head -1) || true
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9*.jar" 2>/dev/null | grep -v sources | head -1) || true
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name "trove4j-*.jar" 2>/dev/null | head -1) || true
JB_ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name "annotations-*.jar" 2>/dev/null | head -1) || true

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
[ -f "$STDLIB" ] || { echo "FATAL: 缺少 stdlib $STDLIB" >&2; exit 1; }

SRC="
$ROOT/core/src/main/java/com/beichen/polaris/core/capability/PermissionGuide.kt
$ROOT/scripts/qa/PermissionGuideVerification.kt
"

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling permission-guide harness ..."
if [ -n "${KOTLINC_BIN:-}" ]; then
  "$KOTLINC_BIN" -classpath "$STDLIB" -d "$OUT" $SRC
else
  KCP="$KOTLINC_JAR:$STDLIB"
  [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ] && KCP="$KCP:$TROVE"
  [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && KCP="$KCP:$JB_ANNOTATIONS"
  java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" $SRC
fi

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" PermissionGuideVerificationKt
