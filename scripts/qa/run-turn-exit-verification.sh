#!/usr/bin/env bash
# R8 回合结束原因 harness：编译 + 运行（纯 JVM，不加载 Android）。
#
# 只编两个零依赖源文件：TurnObserver.kt（事件定义）与 TurnClock.kt（时钟 + TurnExit）。
# 这两个文件刻意保持「零 Android、零协程、零 IO」——就是为了能在没有 Gradle 产物的
# 环境里单独打靶；一旦有人往它们里塞依赖，这个 runner 会立刻编译失败，也算一道闸。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-turn-exit

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  echo "  CI 上由 gate 提供；本地可设 KOTLINC_HOME 指向发行版。" >&2
  exit 1
fi
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling turn-exit harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/journal/RunScope.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/TurnObserver.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/agent/TurnClock.kt" \
  "$ROOT/scripts/qa/TurnExitVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" TurnExitVerificationKt
