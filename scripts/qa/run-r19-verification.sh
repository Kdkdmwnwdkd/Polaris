#!/usr/bin/env bash
# R-19「契约方法零调用」验证。
#
# [为什么不是行为 harness] 本条缺陷的事实本身就是「没有调用点」，行为测试测不到
# 一个不存在的调用。判据只能是源码扫描——但**扫描必须够硬**，否则就是自欺：
#   - DELETED  要求「全仓再也找不到这个定义」，不是「我记得删了」；
#   - WIRED    要求「台账里那条调用片段真实存在」，不是「名字出现在某处」；
#   - RESERVED 要求「现在仍然零调用」，接上线却没更新台账时这里会红。
#
# [为什么冻结条目数] 台账最容易腐烂的方式不是写错，而是**悄悄删行**：
# 红了一条，删掉一行，门禁就绿了，缺陷还在。所以总数写死在下面，
# 减行必须改这个数——改动会出现在 diff 里，评审看得见。
#
# 纯 bash + grep，不依赖 kotlinc：这条扫描在任何环境都能真跑，不会 SKIP。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

INV=scripts/qa/contract-inventory.txt
SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
EXPECTED_ENTRIES=14

# 台账的 FILE 列写的是相对包根的路径（如 core/ConversationStore.kt）。
# 3.5 拆模块后同一个相对路径可能落在 :app 也可能落在 :core，先找 app 再找 core。
src_of() {
  local f="$1"
  if [ -f "$SRC/$f" ]; then printf '%s' "$SRC/$f"; else printf '%s' "$SRC_CORE/$f"; fi
}

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

echo "[A] 台账结构"

if [ -f "$INV" ]; then
  pass "A1 台账存在（$INV）"
else
  fail "A1 台账缺失：$INV"
  echo ""
  echo "0 通过 / 1 失败"
  exit 1
fi

# 只数数据行：非空、非 # 开头
mapfile -t rows < <(grep -vE '^[[:space:]]*(#|$)' "$INV")
n=${#rows[@]}

if [ "$n" -eq "$EXPECTED_ENTRIES" ]; then
  pass "A2 条目数 $n == 冻结值 $EXPECTED_ENTRIES"
else
  fail "A2 条目数 $n != 冻结值 $EXPECTED_ENTRIES（删行必须同步改脚本里的数字，让 diff 说话）"
fi

bad_fields=0
for row in "${rows[@]}"; do
  # 5 个字段：4 个分隔符
  sep=$(printf '%s' "$row" | tr -cd '|' | wc -c | tr -d ' ')
  [ "$sep" = "4" ] || bad_fields=$((bad_fields + 1))
done
if [ "$bad_fields" -eq 0 ]; then
  pass "A3 每行都是 5 字段（STATUS|SYMBOL|FILE|EXPECT|REASON）"
else
  fail "A3 有 $bad_fields 行字段数不对（多半是 REASON 里写了竖线）"
fi

bad_status=0
for row in "${rows[@]}"; do
  st=$(printf '%s' "$row" | cut -d'|' -f1 | xargs)
  case "$st" in WIRED|DELETED|RESERVED) ;; *) bad_status=$((bad_status + 1)) ;; esac
done
if [ "$bad_status" -eq 0 ]; then
  pass "A4 状态只有 WIRED / DELETED / RESERVED 三种"
else
  fail "A4 有 $bad_status 行状态非法"
fi

missing_file=0
for row in "${rows[@]}"; do
  file=$(printf '%s' "$row" | cut -d'|' -f3 | xargs)
  [ -f "$(src_of "$file")" ] || { echo "        缺文件：$SRC/$file"; missing_file=$((missing_file + 1)); }
done
if [ "$missing_file" -eq 0 ]; then
  pass "A5 每条登记的文件都还在（台账没有指路指到坟头）"
else
  fail "A5 有 $missing_file 条指向不存在的文件——文件被挪走时台账必须跟着改"
fi

echo ""
echo "[B] DELETED：声称删掉的，源码里必须真的没有"

for row in "${rows[@]}"; do
  st=$(printf '%s' "$row" | cut -d'|' -f1 | xargs)
  [ "$st" = "DELETED" ] || continue
  sym=$(printf '%s' "$row" | cut -d'|' -f2 | xargs)
  file=$(printf '%s' "$row" | cut -d'|' -f3 | xargs)
  # 只在**登记的那个文件**里找：`stop` / `unregister` 这种名字全仓有一堆同名方法
  # （服务、蓝牙发现、TTS 都有 stop），跨文件 grep 会把别人的方法算成「没删干净」。
  # 这条判据的语义是「AgentLoop.kt 里不再有 stop」，不是「全宇宙没有 stop」。
  hits=$(grep -c "fun ${sym}(" "$(src_of "$file")" 2>/dev/null || true)
  hits=${hits:-0}
  if [ "$hits" = "0" ]; then
    pass "B $sym 在 ${file} 已无定义（台账说删了，就真删了）"
  else
    fail "B $sym 在 ${file} 仍有 $hits 处定义——台账写 DELETED 但代码还在（登记不等于修复）"
    grep -n "fun ${sym}(" "$(src_of "$file")" | head -3 | sed 's/^/        /'
  fi
  # 方法删了，它自己文件里残留的 [sym] KDoc 链接就是死链：IDE 会淡化，
  # 读代码的人却会以为这个方法还在。
  docref=$(grep -c "\[${sym}\]" "$(src_of "$file")" 2>/dev/null || true)
  docref=${docref:-0}
  if [ "$docref" = "0" ]; then
    pass "B $sym 所在文件无 KDoc 死链"
  else
    fail "B ${file} 仍有 $docref 处指向已删方法的 KDoc 链接（[${sym}]）"
  fi
  # 跨文件死链更隐蔽：别的文件写 `[AgentLoop.stop]`，读的人以为方法还在，
  # IDE 只把链接淡化成灰字，不会报错。类名从文件名推（AgentLoop.kt → AgentLoop）。
  cls=$(basename "$file" .kt)
  cross=$(grep -rn "\[${cls}\.${sym}\]" --include='*.kt' "$SRC" "$SRC_CORE" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$cross" = "0" ]; then
    pass "B 无跨文件死链（[${cls}.${sym}]）"
  else
    fail "B 有 $cross 处跨文件 KDoc 指向已删的 ${cls}.${sym}"
    grep -rn "\[${cls}\.${sym}\]" --include='*.kt' "$SRC" | head -3 | sed 's/^/        /'
  fi
done

echo ""
echo "[C] WIRED：声称接线的，必须有那条调用"

for row in "${rows[@]}"; do
  st=$(printf '%s' "$row" | cut -d'|' -f1 | xargs)
  [ "$st" = "WIRED" ] || continue
  sym=$(printf '%s' "$row" | cut -d'|' -f2 | xargs)
  expect=$(printf '%s' "$row" | cut -d'|' -f4 | xargs)
  if [ -z "$expect" ] || [ "$expect" = "-" ]; then
    fail "C $sym 是 WIRED 但 EXPECT 列没写调用片段——这条等于没判据"
    continue
  fi
  # 固定串匹配：EXPECT 里有括号和点，正则会被它们坑。
  hits=$(grep -rF "$expect" --include='*.kt' "$SRC" "$SRC_CORE" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$hits" -ge 1 ]; then
    pass "C $sym 的调用片段存在（$hits 处）：$expect"
  else
    fail "C $sym 找不到调用片段：$expect（接线被删了，或台账写着好听的话）"
  fi
done

echo ""
echo "[D] RESERVED：保留可以，但必须仍然零调用"

for row in "${rows[@]}"; do
  st=$(printf '%s' "$row" | cut -d'|' -f1 | xargs)
  [ "$st" = "RESERVED" ] || continue
  sym=$(printf '%s' "$row" | cut -d'|' -f2 | xargs)
  file=$(printf '%s' "$row" | cut -d'|' -f3 | xargs)
  # 跨文件引用 = 「接线了却没更新台账」。定义文件内部的调用不算（那还是内部实现）。
  hits=$(grep -rn "${sym}\s*(" --include='*.kt' "$SRC" "$SRC_CORE" 2>/dev/null \
         | grep -v "/${file}:" | grep -v "fun ${sym}(" | wc -l | tr -d ' ')
  if [ "$hits" = "0" ]; then
    pass "D $sym 仍然零生产调用（保留理由成立）"
  else
    fail "D $sym 已有 $hits 处跨文件引用——接线了就该改成 WIRED，别让台账撒谎"
    grep -rn "${sym}\s*(" --include='*.kt' "$SRC" | grep -v "/${file}:" | head -3 | sed 's/^/        /'
  fi
done

echo ""
echo "[E] 反向：本轮修复的两个入口必须真的在跑"

# E1：切后台必须能触发（Application 里接了，不是只写了 object）
if grep -q "AppBackgroundHooks.fireAll" "$SRC/AgentKitApplication.kt"; then
  pass "E1 Application 在前台计数归零时调用 fireAll"
else
  fail "E1 Application 没有接 fireAll——AppBackgroundHooks 会变成下一个死契约"
fi

# E2：存储层必须注册。没有注册方，广播就是空转。
if grep -q "AppBackgroundHooks.register" "$SRC_CORE/core/ConversationStore.kt"; then
  pass "E2 ConversationStore 在 init 里注册了 flush"
else
  fail "E2 ConversationStore 没有注册——flush 仍然是死的"
fi

# E3：register 的 lambda 不许捕获 store 实例。捕获了就意味着弱引用白做：
# hooks 表里的闭包会强引用 store，实例被 ViewModel 丢弃后仍然活着。
if grep -qE "register\(this\) \{ *[a-zA-Z_]+ *->" "$SRC_CORE/core/ConversationStore.kt"; then
  pass "E3 注册闭包用参数接收 owner（不捕获 this，弱引用语义成立）"
else
  fail "E3 注册闭包疑似捕获 this（写成 { flush() } 会让弱引用失效）"
fi

echo ""
echo "[F] 行为：真跑 AppBackgroundHooks（不是静态扫描）"

source "$ROOT/scripts/qa/lib/deps.sh"
CACHE="$QA_GRADLE_CACHE"
KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
STDLIB=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" "kotlin-stdlib-*.jar")
TROVE=$(qa_find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" "trove4j-*.jar")
JB_ANN=$(qa_find_jar "$CACHE/org.jetbrains/annotations" "annotations-*.jar")
[ -n "${KOTLINC:-}" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
[ -n "${STDLIB:-}" ] || STDLIB="$KC_HOME/lib/kotlin-stdlib.jar"
[ -n "${TROVE:-}" ] || TROVE="$KC_HOME/lib/trove4j.jar"
[ -n "${JB_ANN:-}" ] || JB_ANN="$KC_HOME/lib/annotations.jar"

if [ ! -f "$KOTLINC" ] || [ ! -f "$STDLIB" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 Kotlin 编译器：$KOTLINC" >&2
  exit 3
fi

OUT=/tmp/qa-r19
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILER_CP="$KOTLINC:$STDLIB:$TROVE:$JB_ANN"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$STDLIB" -d "$OUT" -nowarn \
  "$SRC_CORE/core/lifecycle/AppBackgroundHooks.kt" scripts/qa/R19HooksVerification.kt \
  2>&1 | grep -v '^warning:' | head -20

if [ ! -d "$OUT/com" ]; then
  fail "F 编译失败（上面是输出）——行为断言没跑成，不能算通过"
else
  BODY=$(java -cp "$OUT:$STDLIB" R19HooksVerificationKt)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
  # 没有结果行 = harness 没跑完（崩了 / 被 kill / 编译产物缺失）。
  # 这一条是变异测试 N1 逼出来的：进程崩掉时上面所有 grep 都不命中，
  # 若默认「没失败就是通过」，整个 F 段会静默消失，而日志里看起来只有 24 条 PASS。
  if [ -z "$line" ]; then
    fail "F 段没有结果行（harness 中途退出，rc=$rc）——未跑完不算通过"
  else
    fp=$(echo "${line%% 通过*}" | tr -dc '0-9'); fp=${fp:-0}
    ff=$(echo "${line##* / }" | tr -dc '0-9'); ff=${ff:-0}
    p=$((p + fp)); f=$((f + ff))
    [ "$ff" -eq 0 ] || echo "        F 段有 $ff 条行为断言失败"
  fi
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
