#!/usr/bin/env bash
# W-33 harness：后台命令纯逻辑（命令清洗/日志尾行/耗时与状态文案/重启对账）。
# 纯 JVM：编 JobModels + JobLogic + harness（序列化注解需要 core jar，不需要编译器插件）。
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
source scripts/qa/lib/deps.sh
OUT=/tmp/qa-verify-w33

KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}
STDLIB=$KC_HOME/lib/kotlin-stdlib.jar
SERCORE=${SERCORE:-$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.6.3" "*.jar")}
# 历史遗留路径：只有缓存完全没有时才用（沙箱里手工放的一次性产物）
[ -n "${SERCORE:-}" ] || SERCORE=/tmp/w7-jars/kotlinx-serialization-core-jvm-1.6.3.jar

SRC1="$ROOT/core/src/main/java/com/beichen/polaris/core/job/JobModels.kt"
SRC2="$ROOT/core/src/main/java/com/beichen/polaris/core/job/JobLogic.kt"
SRC3="$ROOT/scripts/qa/W33Verification.kt"

if [ ! -x "$KC_HOME/bin/kotlinc" ]; then
  echo "FATAL: 找不到 kotlinc（$KC_HOME/bin/kotlinc）" >&2
  exit 1
fi
for f in "$STDLIB" "$SERCORE" "$SRC1" "$SRC2" "$SRC3"; do
  [ -f "$f" ] || { echo "FATAL: 缺少 $f" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"
DEPS="$STDLIB:$SERCORE"

echo "[1/2] compiling w33 harness ..."
"$KC_HOME/bin/kotlinc" -classpath "$DEPS" -d "$OUT" "$SRC1" "$SRC2" "$SRC3"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" W33VerificationKt
