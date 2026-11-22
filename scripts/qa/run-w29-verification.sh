#!/usr/bin/env bash
# W-26 harness：标题清洗（TitleLogic）+ 备份含 Key 池（BackupArchive 往返）。
# 纯 JVM：不加载 Android，只编 3 个无依赖源文件 + harness。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
source scripts/qa/lib/deps.sh
OUT=/tmp/qa-verify-w29

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
SER=${SER:-$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.6.3" "*.jar")}
# 历史遗留路径：只有缓存完全没有时才用（沙箱里手工放的一次性产物）
[ -n "${SER:-}" ] || SER=/tmp/w7-jars/kotlinx-serialization-json-jvm-1.6.3.jar
SERCORE=${SERCORE:-$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.6.3" "*.jar")}
# 历史遗留路径：只有缓存完全没有时才用（沙箱里手工放的一次性产物）
[ -n "${SERCORE:-}" ] || SERCORE=/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/text/HeaderParsing.kt"
SRC2="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/BalanceParsing.kt"
SRC3="$ROOT/core/src/main/java/com/beichen/polaris/core/text/HeaderParsing.kt"
SRC3B="$ROOT/core/src/main/java/com/beichen/polaris/core/llm/BalanceFailure.kt"
SRC4="$ROOT/scripts/qa/W29Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SER" "$SERCORE" "$SRC1" "$SRC2" "$SRC3B" "$SRC4"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"
DEPS="$STDLIB:$SER:$SERCORE"

echo "[1/2] compiling w26 harness ..."
"$KC_HOME/bin/kotlinc" -Xplugin="$KC_HOME/lib/kotlinx-serialization-compiler-plugin.jar" -classpath "$DEPS" -d "$OUT" "$SRC1" "$SRC2" "$SRC3B" "$SRC4"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" W29VerificationKt
