#!/usr/bin/env bash
# R9 坑图 harness：编译 + 运行（纯 JVM，不加载 Android）。
#
# 只编三个源文件：AtomicJsonFile（原子写）、PitBook（坑图）、FailureGuard（熔断 + 单向接入）。
# 这三个文件刻意保持「零 Android」——坑图与熔断都属于 core 的纯逻辑，
# 一旦有人往它们里塞 Context，这个 runner 会立刻编译失败，也算一道闸。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-pitbook

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
# 序列化插件 + 运行期 jar（PitBook 用 @Serializable 落盘）
SER_PLUGIN=$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar
SERIALIZATION=$(ls /tmp/w7-jars/kotlinx-serialization-json-jvm-*.jar 2>/dev/null | head -1)
SER_CORE=$(ls /tmp/w7-jars/kotlinx-serialization-core-jvm-*.jar 2>/dev/null | head -1)
for j in "$SER_PLUGIN" "$SERIALIZATION" "$SER_CORE"; do
  if [ ! -f "$j" ]; then echo "FATAL: 缺少依赖 $j" >&2; exit 1; fi
done
DEPS=$STDLIB:$SERIALIZATION:$SER_CORE

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling pitbook harness ..."
"$KC_HOME/bin/kotlinc" -Xplugin="$SER_PLUGIN" -classpath "$DEPS" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/io/AtomicJsonFile.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/pit/PitBook.kt" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/FailureGuard.kt" \
  "$ROOT/scripts/qa/PitBookVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" PitBookVerificationKt
