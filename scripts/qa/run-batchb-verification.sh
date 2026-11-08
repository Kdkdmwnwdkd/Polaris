#!/usr/bin/env bash
# B 批 harness：信任等级 fail-closed + 自动备份 + 前台服务/运行态 + 容器运行时（纯 JVM）。
#
# 与其它 runner 一致的两级构件解析：优先 Gradle 缓存，缺失时回落本机 kotlinc 发行版。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-batchb

: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
KOTLINC_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
SERIALIZATION=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" -name "*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
SER_CORE=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" -name "*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
SER_PLUGIN=$(find "$CACHE/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable" -name "*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name "trove4j-*.jar" 2>/dev/null | sort -V | tail -1) || true
COROUTINES=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name "*.jar" 2>/dev/null | grep -v sources | sort -V | tail -1) || true
JB_ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name "annotations-*.jar" 2>/dev/null | sort -V | tail -1) || true

if [ -z "${KOTLINC_JAR:-}" ] || [ -z "${STDLIB:-}" ] || [ -z "${SERIALIZATION:-}" ]; then
  KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
  if [ -x "$KC_HOME/bin/kotlinc" ]; then
    KOTLINC_BIN="$KC_HOME/bin/kotlinc"
    STDLIB=${STDLIB:-$KC_HOME/lib/kotlin-stdlib.jar}
    SER_PLUGIN=${SER_PLUGIN:-$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar}
    SERIALIZATION=${SERIALIZATION:-/tmp/w7-jars/kotlinx-serialization-json-jvm-1.6.3.jar}
    SER_CORE=${SER_CORE:-/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar}
  fi
fi

if [ -z "${KOTLINC_BIN:-}" ] && [ -z "${KOTLINC_JAR:-}" ]; then
  echo "FATAL: 找不到 kotlinc（Gradle 缓存与本机发行版均缺失）" >&2
  exit 1
fi
for j in "$STDLIB" "$SERIALIZATION" "$SER_CORE"; do
  [ -f "${j:-}" ] || { echo "FATAL: 缺少依赖 $j" >&2; exit 1; }
done

DEPS="$STDLIB:$SERIALIZATION:$SER_CORE"
[ -n "${COROUTINES:-}" ] && [ -f "${COROUTINES:-}" ] && DEPS="$DEPS:$COROUTINES"
[ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && DEPS="$DEPS:$JB_ANNOTATIONS"

SRC="$ROOT/app/src/main/java/com/beichen/polaris"
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE="$ROOT/core/src/main/java/com/beichen/polaris"
SRCS=(
  "$SRC_CORE/core/io/AtomicJsonFile.kt"
  "$SRC_CORE/core/agent/AgentRunStateStore.kt"
  "$SRC_CORE/core/agent/ApprovalGrouping.kt"
  "$SRC_CORE/core/AgentContract.kt"
  "$SRC_CORE/core/ToolResult.kt"
  "$SRC_CORE/core/capability/CapabilityManifest.kt"
  "$SRC_CORE/core/container/ToolchainInstaller.kt"
  "$SRC_CORE/core/workspace/FileBackupStore.kt"
  "$SRC_CORE/core/workspace/TextDiff.kt"
  "$SRC_CORE/core/tool/ToolCatalog.kt"
  "$SRC_CORE/skill/SkillSecurity.kt"
  "$ROOT/scripts/qa/BatchBVerification.kt"
)

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling batch-b harness ..."
if [ -n "${KOTLINC_BIN:-}" ]; then
  "$KOTLINC_BIN" -classpath "$DEPS" -Xplugin="${SER_PLUGIN:-}" -d "$OUT" "${SRCS[@]}"
else
  KCP="$KOTLINC_JAR:$STDLIB"
  [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ] && KCP="$KCP:$TROVE"
  [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ] && KCP="$KCP:$JB_ANNOTATIONS"
  java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -Xplugin="${SER_PLUGIN:-}" -classpath "$DEPS" -d "$OUT" "${SRCS[@]}"
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" BatchBVerificationKt
