#!/usr/bin/env bash
# W-13 配对层（纯 JVM 部分）harness：编译 + 运行，无 Android 依赖。
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-adb-pairing
# W-41：缓存根按环境推导（CI 是 /home/runner/.gradle），可被 AGENTKIT_GRADLE_CACHE 覆盖
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
[ -f "$STDLIB" ] || { echo "FATAL: 缺少 kotlin-stdlib" >&2; exit 1; }
SRC="$ROOT/app/src/main/java/com/beichen/polaris/automation/adb/AdbPairingPacket.kt $ROOT/scripts/qa/AdbPairingVerification.kt"
rm -rf "$OUT" && mkdir -p "$OUT"
compile_pairing() {
  local attempt=$1 out
  if [ -n "${KOTLINC_BIN:-}" ]; then
    out=$("$KOTLINC_BIN" -classpath "$STDLIB" -d "$OUT" $SRC 2>&1) || true
  else
    local kcp="$KOTLINC_JAR:$STDLIB"
    if [ -n "${TROVE:-}" ] && [ -f "${TROVE:-}" ]; then kcp=$kcp:$TROVE; fi
    if [ -n "${JB_ANNOTATIONS:-}" ] && [ -f "${JB_ANNOTATIONS:-}" ]; then kcp=$kcp:$JB_ANNOTATIONS; fi
    out=$(java -cp "$kcp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
      -classpath "$STDLIB" -d "$OUT" $SRC 2>&1) || true
  fi
  if grep -q "error:" <<<"$out"; then echo "$out"; return 1; fi
  if grep -q "BackendException\|NoClassDefFoundError" <<<"$out"; then return 2; fi
  echo "$out" | grep -v "^warning:" || true
  return 0
}
echo "[1/2] compiling pairing harness ..."
rc=1
for attempt in 1 2 3 4 5; do
  set +e; compile_pairing "$attempt"; crc=$?; set -e
  if [ "$crc" -eq 0 ]; then rc=0; break; fi
  if [ "$crc" -eq 1 ]; then exit 1; fi
  rm -rf "$OUT" && mkdir -p "$OUT"
done
[ $rc -eq 0 ] || { echo "FATAL: pairing compile failed" >&2; exit 1; }
echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" AdbPairingVerificationKt
