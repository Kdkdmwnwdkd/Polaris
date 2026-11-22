#!/usr/bin/env bash
# 审批未通过文案 harness：编译 + 运行（纯 JVM，不加载 Android 类）。
#
# 只编译纯逻辑那一侧：ToolCatalog（工具目录 + 审批归组数据）、
# ApprovalGrouping（归组函数）、DenyMessage（本轮新增的文案）。
# ToolExecutor 调用这三者的那一行改动，靠 app/core 编译与真机验证——裸 JVM 编不了。
#
# 构件解析两级：优先 Gradle 缓存（CI/完整开发机），缺失时回落本机 kotlinc 发行版
# （沙箱环境 /tmp/kotlinc + /tmp/w7-jars）——沙箱无 Gradle 缓存，但验证口径不变。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
source scripts/qa/lib/deps.sh
OUT=/tmp/qa-verify-deny-message

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
if [ ! -f "${STDLIB:-}" ]; then echo "FATAL: 缺少 kotlin-stdlib" >&2; exit 1; fi

DEPS=$STDLIB
if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then DEPS=$DEPS:$JB_ANNOTATIONS; fi

SRC_TOOLCATALOG="$ROOT/core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt"
SRC_GROUPING="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/ApprovalGrouping.kt"
SRC_DENY="$ROOT/core/src/main/java/com/beichen/polaris/core/agent/DenyMessage.kt"
SRC_HARNESS="$ROOT/scripts/qa/DenyMessageVerification.kt"

rm -rf "$OUT" && mkdir -p "$OUT"

compile_ctx() {
  local attempt=$1 out
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$DEPS" -d "$OUT" \
      "$SRC_TOOLCATALOG" "$SRC_GROUPING" "$SRC_DENY" "$SRC_HARNESS" 2>&1) || true
  else
    local kcp="$KOTLINC_JAR:$STDLIB"
    if [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ]; then kcp=$kcp:$TROVE; fi
    if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then kcp=$kcp:$JB_ANNOTATIONS; fi
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" \
      "$SRC_TOOLCATALOG" "$SRC_GROUPING" "$SRC_DENY" "$SRC_HARNESS" 2>&1) || true
  fi
  if echo "$out" | grep -q "error:"; then
    echo "$out"; return 1
  fi
  if echo "$out" | grep -q "BackendException\|NoClassDefFoundError"; then
    echo "  (compile attempt $attempt hit backend annotation race, retrying ...)" >&2
    return 2
  fi
  echo "$out" | grep -v "^warning:" || true
  return 0
}

echo "[1/2] compiling deny-message harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  compile_ctx "$attempt"
  crc=$?
  set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: deny-message compile keeps hitting backend annotation race after 5 attempts" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" DenyMessageVerificationKt
