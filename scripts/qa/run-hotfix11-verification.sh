#!/usr/bin/env bash
# hotfix11「对话页模型快速切换」QA 源码级验证：
# 当前模型指示条 + 一键切换弹窗 + VM 即时生效根基。
# 形态学 scripts/qa/run-capture-path-verification.sh（纯源码扫描，不加载 Android 类）。
set -euo pipefail

# W-39：仓库根目录由脚本位置推导，不再硬编码 /workspace/AgentKit
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains/annotations" "annotations-*.jar")

# W-40：Gradle 缓存缺失时回落本机 kotlinc 发行版（沙箱/净机器都能跑）
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
# W-41：编译器两条路都没有 → 明确 SKIP（exit 3），不让它变成一堆「找不到类」
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}
# 本 harness 是**纯源码扫描**（全文件只 import java.io.File），不加载任何 Android 类。
# 旧写法在缺 AGP 产物时直接 SKIP——于是净机器 / 沙箱上这 40 条断言永久不跑，而它们
# 恰恰是改 ChatViewModel 时唯一能本地验证的东西（改了断言却没人跑过，等于没改）。
# 现在回退到 Kotlin 标准库编译：真跑不通就红，不再用「环境不具备」把断言藏起来。
if [ -z "$CLASSES" ]; then
  echo "WARN  缺少 AGP 产物，按纯源码扫描模式编译（只需 kotlin-stdlib）"
fi
OUT=/tmp/qa-verify-hotfix11
DEPS=${CLASSES:+$CLASSES:}$STDLIB

rm -rf "$OUT" && mkdir -p "$OUT"

echo "[1/2] compiling harness with kotlin-compiler-embeddable ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$DEPS" \
  -d "$OUT" $ROOT/scripts/qa/Hotfix11Verification.kt

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" \
  Hotfix11VerificationKt
