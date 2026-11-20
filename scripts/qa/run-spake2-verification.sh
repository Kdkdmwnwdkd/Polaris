#!/usr/bin/env bash
# W-51「adb 无线配对的 SPAKE2」QA 行为验证：编译 + 运行（离线，仅用 Gradle 缓存内构件）。
# 形态学 run-fallback-verification.sh：kotlin-compiler-embeddable 编译 harness + java 运行。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}

OUT=/tmp/qa-verify-spake2
rm -rf "$OUT" && mkdir -p "$OUT"

# 这条 harness 曾经长期 SKIP：它只测 Spake2Ed25519 + Ed25519，而这两个类**只依赖
# java.math / java.security 三个 JDK 类**（见文件头 import）。为它去要整个 app 的
# AGP 产物，等于让「15 条密码学判据」在沙箱里永久不跑——2026-11-20 的
# BigInteger.TWO 崩溃就是这么活下来的（编译过、JVM 单测过、这条一直 SKIP、CI 不跑 lint）。
#
# 所以：有 AGP 产物走产物（最接近真实构建），没有就**直接编那几个源文件**。
# 同一编译单元内 internal 天然可见，不需要 -Xfriend-paths。
# 要编的：SPAKE2 实现 + `Spake2` 接口所在文件（AdbPairingPacket.kt，同样只用 JDK 类）。
if [ -n "$CLASSES" ]; then
  DEPS=$CLASSES:$STDLIB
  echo "[1/2] compiling spake2 harness against AGP output ..."
  # -Xfriend-paths：SPAKE2 实现与 Ed25519 群运算是 internal，仅 module 内可见
  java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -Xfriend-paths="${CLASSES//:/,}" \
    -classpath "$DEPS" \
    -d "$OUT" $ROOT/scripts/qa/Spake2Verification.kt
else
  DEPS=$STDLIB
  echo "[1/2] 无 AGP 产物，直接编译 Spake2Ed25519.kt + harness ..."
  java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -classpath "$DEPS" \
    -d "$OUT" \
    $ROOT/app/src/main/java/com/beichen/polaris/automation/adb/Spake2Ed25519.kt \
    $ROOT/app/src/main/java/com/beichen/polaris/automation/adb/AdbPairingPacket.kt \
    $ROOT/scripts/qa/Spake2Verification.kt
fi

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" Spake2VerificationKt
