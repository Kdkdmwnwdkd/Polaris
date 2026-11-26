#!/usr/bin/env bash
# 云机三修判据 harness（纯 JVM，不加载 Android 类）。
#
# 编进去的东西刻意很少：能力链（纯函数）、A11yState（纯枚举+函数）、CapabilityReadiness
# （只用到 coroutines 的 StateFlow）。UI 那一侧带 Compose，裸 JVM 编不了，靠 [C] 组源码断言兜。
#
# 构件解析两级：优先 Gradle 缓存（CI/完整开发机），缺失时回落本机 kotlinc 发行版。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-cloud-fixes

: "${QA_GRADLE_CACHE:=${AGENTKIT_GRADLE_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1}}"
CACHE="$QA_GRADLE_CACHE"
KOTLINC_JAR=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name "kotlin-compiler-embeddable-*.jar" 2>/dev/null | grep -v sources | head -1) || true
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name "kotlin-stdlib-1.9*.jar" 2>/dev/null | grep -v sources | head -1) || true
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name "trove4j-*.jar" 2>/dev/null | head -1) || true
JB_ANNOTATIONS=$(find "$CACHE/org.jetbrains/annotations" -name "annotations-*.jar" 2>/dev/null | head -1) || true
COROUTINES=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name "kotlinx-coroutines-core-jvm-*.jar" 2>/dev/null | grep -v sources | head -1) || true

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
if [ ! -f "${STDLIB:-/nonexistent}" ]; then
  echo "FATAL: 缺少 kotlin-stdlib" >&2
  exit 1
fi
# 本地 Gradle 缓存缺失时退回 /tmp/gcache（容器里预置的依赖目录，与其余 QA 脚本同源）
[ -n "${COROUTINES:-}" ] || COROUTINES=$(find /tmp/gcache -name "kotlinx-coroutines-core-jvm-*.jar" 2>/dev/null | head -1) || true

if [ -z "${COROUTINES:-}" ] || [ ! -f "${COROUTINES:-/nonexistent}" ]; then
  echo "FATAL: 缺少 kotlinx-coroutines-core-jvm（CapabilityReadiness 要它）" >&2
  exit 1
fi

DEPS=$STDLIB:$COROUTINES
if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then DEPS=$DEPS:$JB_ANNOTATIONS; fi

rm -rf "$OUT" && mkdir -p "$OUT"

# 只编纯函数：CapabilityChain 本体带 AgentLogger → LogRedaction → ToolCatalog 一整条链，
# 拖进来就编不动了。指纹算法已下沉到 ResolutionFingerprint.kt（纯 Kotlin），够用。
SRC_CHAIN="$ROOT/core/src/main/java/com/beichen/polaris/core/capability/CapabilityChain.kt"
SRC_A11Y="$ROOT/core/src/main/java/com/beichen/polaris/core/capability/A11yStatus.kt"
SRC_FP="$ROOT/core/src/main/java/com/beichen/polaris/core/capability/ResolutionFingerprint.kt"
SRC_HARNESS="$ROOT/scripts/qa/CloudFixesVerification.kt"

echo "[1/2] compiling cloud-fixes harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$DEPS" -d "$OUT" \
      "$SRC_CHAIN" "$SRC_A11Y" "$SRC_FP" "$SRC_HARNESS" 2>&1)
  else
    kcp="$KOTLINC_JAR:$STDLIB"
    if [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ]; then kcp=$kcp:$TROVE; fi
    if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then kcp=$kcp:$JB_ANNOTATIONS; fi
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -classpath "$DEPS" -d "$OUT" \
      "$SRC_CHAIN" "$SRC_A11Y" "$SRC_FP" "$SRC_HARNESS" 2>&1)
  fi
  crc=$?
  set -e
  if grep -q "error:" <<<"$out"; then echo "$out"; exit 1; fi
  if [ $crc -eq 0 ]; then rc=0; break; fi
  echo "  (compile attempt $attempt failed, retrying ...)" >&2
  rm -rf "$OUT" && mkdir -p "$OUT"
done
if [ $rc -ne 0 ]; then
  echo "FATAL: cloud-fixes 编译连续失败" >&2
  exit 1
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" CloudFixesVerificationKt
