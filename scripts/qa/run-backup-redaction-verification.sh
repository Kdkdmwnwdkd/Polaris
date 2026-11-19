#!/usr/bin/env bash
# 备份件脱敏验证（审计 F6 第二条）。
#
# [行为] 编译真实的 BackupRedaction 跑 23 条断言：脱敏（档案 Key / 敏感自定义头 /
#        媒体 Key / 备用 Key 池）与合并（同 id 回填、跨设备保持空、坏包不清空本机）。
# [接线] BackupUseCase / ConfigStore 拖 Android，只静态断言调用点存在——
#        判定写得再对，导出与恢复没接上也是白写。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/qa/lib/deps.sh"

SRC_CORE=core/src/main/java/com/beichen/polaris
IMPL="$SRC_CORE/core/BackupRedaction.kt"
# 硬转换走 JsonSafe（S4 守卫要求），所以它也是被测源的一部分
JSONSAFE="$SRC_CORE/core/llm/JsonSafe.kt"
HARNESS=scripts/qa/BackupRedactionVerification.kt
BACKUP_USE=app/src/main/java/com/beichen/polaris/ui/BackupUseCase.kt
CONFIG_STORE="$SRC_CORE/core/ConfigStore.kt"

CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
SER_JSON=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "kotlinx-serialization-json-jvm-*.jar")
SER_CORE=$(qa_find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" "kotlinx-serialization-core-jvm-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANNOTATIONS=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")

[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${SER_JSON:-}" ] || SER_JSON="$KC_HOME/lib/kotlinx-serialization-json-jvm.jar"
[ -n "${SER_CORE:-}" ] || SER_CORE="$KC_HOME/lib/kotlinx-serialization-core-jvm.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANNOTATIONS:-}" ] || JB_ANNOTATIONS="$KC_HOME/lib/annotations.jar"

need() {
  [ -f "$2" ] || { echo "KS_HARNESS_SKIP $(basename "$0") 缺 $1：$2" >&2; exit 3; }
}
need "Kotlin 编译器" "$KOTLINC"
need "kotlin-stdlib" "$STDLIB"
need "kotlinx-serialization-json-jvm" "$SER_JSON"
need "kotlinx-serialization-core-jvm" "$SER_CORE"

for f in "$IMPL" "$JSONSAFE" "$HARNESS" "$BACKUP_USE" "$CONFIG_STORE"; do
  [ -f "$f" ] || { echo "FAIL 缺少源文件 $f（无法判定，视为失败）"; exit 1; }
done

OUT=/tmp/qa-backup-redaction
RUNTIME="$STDLIB:$SER_JSON:$SER_CORE"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANNOTATIONS"

compile_and_run() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect \
    -cp "$RUNTIME" \
    -d "$OUT" -nowarn \
    "$IMPL" "$JSONSAFE" "$HARNESS" 2>&1 | grep -v '^warning:' | head -30
  [ -d "$OUT/com" ] || return 1
  java -cp "$OUT:$RUNTIME" BackupRedactionVerificationKt
}

BODY=$(compile_and_run)
if [ $? -ne 0 ] || [ -z "$BODY" ]; then
  echo "$BODY"
  echo "FAIL 编译或运行失败（上面是输出）"
  exit 1
fi
echo "$BODY"
JS=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
jp=$(echo "${JS%% 通过*}" | tr -dc '0-9'); jp=${jp:-0}
jf=$(echo "${JS##* / }" | tr -dc '0-9'); jf=${jf:-0}

# ── [G] 接线 ───────────────────────────────────────────────────────────────
gp=0; gf=0
g_bad() { echo "  FAIL  $*"; gf=$((gf + 1)); }
g_good() { echo "  PASS  $*"; gp=$((gp + 1)); }

echo ""
echo "[G] 接线"

# G1：导出侧三处密钥都必须过脱敏。少一处就是一处明文外流。
if grep -q 'redactProfiles(json.encodeToString(config.loadProfiles()))' "$BACKUP_USE" \
   && grep -q 'redactMediaKeys(config.loadAllMediaKeys())' "$BACKUP_USE" \
   && grep -q 'redactKeyPool(keyPool.exportAll())' "$BACKUP_USE"; then
  g_good "G1 导出：档案 / 媒体 Key / 备用 Key 池三处都脱敏"
else
  g_bad "G1 导出侧有密钥没走 BackupRedaction"
fi

# G2：恢复侧必须以「本机现值」为合并基底，否则等于用空 Key 覆盖本机配置。
if grep -q 'mergeKeyPool(poolText, keyPool.exportAll())' "$BACKUP_USE"; then
  g_good "G2 恢复：Key 池按本机现值合并"
else
  g_bad "G2 恢复 Key 池没有合并 —— 恢复一次 Key 全丢"
fi

# G3：档案与媒体 Key 的合并发生在 ConfigStore.restoreFrom（它才知道本机档案）。
if grep -q 'BackupRedaction.mergeProfiles(profilesText, json.encodeToString(loadProfiles()))' "$CONFIG_STORE" \
   && grep -q 'BackupRedaction.mergeMediaKeys(backup.mediaKeys, loadAllMediaKeys())' "$CONFIG_STORE"; then
  g_good "G3 恢复：档案与媒体 Key 都以本机现值为基底合并"
else
  g_bad "G3 ConfigStore.restoreFrom 未合并 —— 空 Key 会覆盖本机"
fi

# G4：用户必须被告知「Key 不在包里」，否则恢复后 401 会被当成 bug 报上来。
if grep -q 'API Key 不随备份导出' "$BACKUP_USE"; then
  g_good "G4 导出结果提示告知 Key 不在包内"
else
  g_bad "G4 导出提示没说 Key 不含在包里"
fi

# G5：反向——不含 redact 的原始写入不许回来（防「顺手改回一行」）。
if grep -q 'profilesJson = json.encodeToString(config.loadProfiles())' "$BACKUP_USE"; then
  g_bad "G5 仍有一处把 loadProfiles() 原样写进备份"
else
  g_good "G5 没有把档案原样写进备份的残留"
fi

# —— 诊断包脱敏的**接线**（第十一轮）——————————————————————
#
# 判据本身搬到了 DiagnosticsRedaction（JVM 26 条断言 + 变异 M35–M42）。
# 这里只验「Android 壳有没有调它」：DiagnosticsExporter import 了
# `android.content.Context`，JVM 上编不过，所以只能静态断言。
#
# 这一段存在的理由就是上一轮那条教训：判据写得再对，没人调它也白写。
DIAG="$SRC_CORE/core/DiagnosticsExporter.kt"

# G6：流水导出必须逐行过脱敏（tool.start 行带 args）。
if grep -Fq 'DiagnosticsRedaction.redactLines' "$DIAG"; then
  g_good "G6 流水导出过脱敏（traceOf → redactLines）"
else
  g_bad "G6 流水没有脱敏 —— tool.start 的 args 会带着 Key 一起出门"
fi

# G7：反向——原样复制不许回来（那是 2026-11-19 之前的写法）。
if grep -Fq 'src.copyTo(' "$DIAG"; then
  g_bad "G7 流水又变回原样复制（traceOf 里还有 src.copyTo）"
else
  g_good "G7 没有原样复制流水的残留"
fi

# G8：配置区的端点也要过——它不走日志那条替换路径，是另行拼的字符串。
if grep -Fq 'DiagnosticsRedaction.redact(config?.baseUrl' "$DIAG"; then
  g_good "G8 端点已过脱敏（自建代理把 Key 塞进 query 的兜底）"
else
  g_bad "G8 端点原样写进诊断包"
fi

# G9：原生崩溃现场（stderr 由推理库自己写，内容不可控）也要过。
if grep -Fq 'crashSnapshot(context, secrets)' "$DIAG" \
   && grep -Fq 'DiagnosticsRedaction.redact(body.take(MAX_CRASH_CHARS), secrets)' "$DIAG"; then
  g_good "G9 崩溃现场（native-stderr）过脱敏"
else
  g_bad "G9 崩溃现场原样写进诊断包"
fi

total_pass=$((jp + gp))
total_fail=$((jf + gf))
echo ""
echo "=== 合计 $total_pass 通过 / $total_fail 失败 ==="
[ "$total_fail" -eq 0 ]
