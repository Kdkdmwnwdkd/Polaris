#!/usr/bin/env bash
# W-26 harness：标题清洗（TitleLogic）+ 备份含 Key 池（BackupArchive 往返）。
# 纯 JVM：不加载 Android，只编 3 个无依赖源文件 + harness。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-w28

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
SER=${SER_JSON_JAR:-/tmp/w7-jars/kotlinx-serialization-json-jvm-1.6.3.jar}
SERCORE=${SER_CORE_JAR:-/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar}

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/text/MentionLogic.kt"
SRC3="$ROOT/core/src/main/java/com/beichen/polaris/core/text/MentionLogic.kt"
SRC4="$ROOT/scripts/qa/W28Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SRC1" "$SRC4"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"
DEPS="$STDLIB:$SER:$SERCORE"

echo "[1/2] compiling w26 harness ..."
"$KC_HOME/bin/kotlinc" -Xplugin="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar" -classpath "$DEPS" -d "$OUT" "$SRC1" "$SRC4"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" W28VerificationKt
