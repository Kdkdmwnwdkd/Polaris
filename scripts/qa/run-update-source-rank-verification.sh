#!/usr/bin/env bash
# 下载线路择优 harness：编译 + 运行（纯 JVM，不加载 Android）。
#
# 只编 GitHubMirror.kt：它刻意保持「零 Android、零协程、零 IO」——排序是纯函数，
# 所以能被这里单独打靶。一旦有人往它里塞 OkHttp/Context，这个 runner 会立刻编译失败，
# 也算一道闸（探测与续传的逻辑在 ApkDownloader，那句真机验）。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-update-rank

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  echo "  CI 上由 gate 提供；本地可设 KOTLINC_HOME 指向发行版。" >&2
  exit 1
fi
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling update-rank harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$STDLIB" -d "$OUT" \
  "$ROOT/core/src/main/java/com/beichen/polaris/core/net/GitHubMirror.kt" \
  "$ROOT/scripts/qa/UpdateSourceRankVerification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$STDLIB" UpdateSourceRankVerificationKt
