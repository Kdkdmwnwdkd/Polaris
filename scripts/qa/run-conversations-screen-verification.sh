#!/usr/bin/env bash
# W-69「全部对话」harness：编译 + 运行。
#
# 两半合在一个 runner 里：
#  - 真跑的一半要编译 app/.../core/text/ConversationFilter.kt（零 Android 依赖的纯函数）；
#  - 源码扫描的一半在同一次编译里一起跑（读 .kt 文本，不需要 Android SDK）。
#
# 构件解析两级：优先 Gradle 缓存（CI / 完整开发机），缺失时回落本机 kotlinc 发行版
# （沙箱环境 /tmp/kotlinc）——两边验证口径完全一致。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-conversations-screen

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
if [ ! -f "${STDLIB:-}" ]; then echo "FATAL: 缺少 kotlin-stdlib：${STDLIB:-<empty>}" >&2; exit 1; fi

SRC_FILTER="$ROOT/core/src/main/java/com/beichen/polaris/core/text/ConversationFilter.kt"
SRC_TEST="$ROOT/scripts/qa/ConversationsScreenVerification.kt"
for f in "$SRC_FILTER" "$SRC_TEST"; do
  [ -f "$f" ] || { echo "FATAL: 缺少源文件 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling conversations-screen harness ..."
set +e
if [ -n "${KOTLINC_BIN:-}" ]; then
  out=$("$KOTLINC_BIN" -classpath "$STDLIB" -d "$OUT" "$SRC_FILTER" "$SRC_TEST" 2>&1)
  rc=$?
else
  kcp="$KOTLINC_JAR:$STDLIB"
  [ -n "${TROVE:-}" ] && kcp=$kcp:$TROVE
  [ -n "${JB_ANNOTATIONS:-}" ] && kcp=$kcp:$JB_ANNOTATIONS
  out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" -d "$OUT" "$SRC_FILTER" "$SRC_TEST" 2>&1)
  rc=$?
fi
set -e
if [ "$rc" -ne 0 ] || echo "$out" | grep -q "error:"; then
  echo "$out"
  echo "FATAL: conversations-screen 编译失败" >&2
  exit 1
fi
echo "$out" | grep -v "^warning:" || true

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" ConversationsScreenVerificationKt
