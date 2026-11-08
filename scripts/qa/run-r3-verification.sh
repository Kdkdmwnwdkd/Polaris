#!/usr/bin/env bash
# R3（运行资源租约）行为验证。
#
# 与 R5 同类：需要 AGP 编译产物（LeaseRegistry / RuntimeLease 来自主源码），
# 但不依赖网络与设备。空闲阈值在用例里调到 0，整套 2 秒内跑完。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$ROOT/scripts/qa/lib/deps.sh"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
COROUTINES=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "kotlinx-coroutines-core-jvm-*.jar")
SERIALIZATION=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")
OKHTTP=$(qa_find_jar "$CACHE/com.squareup.okhttp3/okhttp" "okhttp-*.jar")
OKIO=$(qa_find_jar "$CACHE/com.squareup.okio/okio-jvm" "okio-jvm-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLINC" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器（$KOTLINC）" >&2
  exit 3
fi

CLASSES=${AGENTKIT_CLASSES:-$(qa_classpath "$ROOT")}
if [ -z "$CLASSES" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺少 Gradle 编译产物：$CLASSES" >&2
  echo "  先执行 ./gradlew :app:compileDebugKotlin" >&2
  exit 3
fi

OUT=/tmp/qa-verify-r3
DEPS=$CLASSES:$STDLIB:$COROUTINES:$SERIALIZATION:$SER_CORE:$OKHTTP:$OKIO

rm -rf "$OUT" && mkdir -p "$OUT"

# R3-15（静态）：容器「借租约」必须发生在会话 start 成功之后。
#
# 这条不进 Kotlin harness：holder 是 Compose object，依赖 Context/Looper，
# JVM 里实例化不了。但它是租约唯一的**失效方式**——若在 start 前 borrow，
# 启动失败的会话会让 activeCount 卡在 1，空闲回收永远等不到归零，
# 表现为「容器页从未打开过，却一直显示有容器会话在运行」。
# 用源码顺序把不变式钉住，改 UI 时不必靠人记得上一次为什么这么排。
python3 - "$ROOT/app/src/main/java/com/beichen/polaris/ui/ContainerScreen.kt" <<'PY'
import sys
path = sys.argv[1]
src = open(path, encoding='utf-8').read()
start_at = src.find('runCatching { s.start() }')
borrow_at = src.find('ContainerLeases.forDistro(')
if start_at < 0 or borrow_at < 0:
    sys.exit('R3-15 失败：ContainerScreen 里找不到 start() 或 forDistro() 调用点'
             '（start=%d borrow=%d）' % (start_at, borrow_at))
if borrow_at < start_at:
    sys.exit('R3-15 失败：借租约出现在 start() 之前——启动失败会让 activeCount 虚高，'
             '空闲回收将永远等不到归零')
if 'refs--' not in src[start_at:]:
    sys.exit('R3-15 失败：start 失败分支没有回滚 refs（会话计数泄漏）')
print('  ok  R3-15 容器租约只在会话 start 成功后才借出（源码顺序 + 失败回滚）')
PY

echo "[1/2] compiling harness ..."
java -cp "$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -Xfriend-paths="${CLASSES//:/,}" \
  -classpath "$DEPS" \
  -d "$OUT" "$ROOT/scripts/qa/R3Verification.kt"

echo "[2/2] running ..."
java -cp "$OUT:$DEPS" R3VerificationKt
