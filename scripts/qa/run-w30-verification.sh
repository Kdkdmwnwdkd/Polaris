#!/usr/bin/env bash
# W-30 harness：聊天体验批纯逻辑（时间/翻译清洗/语音清洗/语言归一/收藏存储）。
# 纯 JVM：不加载 Android，只编 5 个无依赖源文件 + harness。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
OUT=/tmp/qa-verify-w30

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
SER=${SER_JSON_JAR:-/tmp/w7-jars/kotlinx-serialization-json-jvm-1.6.3.jar}
SERCORE=${SER_CORE_JAR:-/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar}

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/text/ChatTime.kt"
SRC2="$ROOT/core/src/main/java/com/beichen/polaris/core/text/ChatAssistLogic.kt"
SRC3="$ROOT/core/src/main/java/com/beichen/polaris/core/text/SyntaxHighlighter.kt"
SRC4="$ROOT/core/src/main/java/com/beichen/polaris/core/BookmarkStore.kt"
SRC4B="$ROOT/core/src/main/java/com/beichen/polaris/core/io/AtomicJsonFile.kt"
SRC5="$ROOT/scripts/qa/W30Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SER" "$SERCORE" "$SRC1" "$SRC2" "$SRC3" "$SRC4" "$SRC4B" "$SRC5"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"
DEPS="$STDLIB:$SER:$SERCORE"

echo "[1/2] compiling w30 harness ..."
"$KC_HOME/bin/kotlinc" -Xplugin="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar" -classpath "$DEPS" -d "$OUT" \
  "$SRC1" "$SRC2" "$SRC3" "$SRC4" "$SRC4B" "$SRC5"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" W30VerificationKt
