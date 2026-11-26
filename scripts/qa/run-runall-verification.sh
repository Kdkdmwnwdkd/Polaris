#!/usr/bin/env bash
# run-all.sh 的自测：门禁链的**源头**。
#
# run-gate-verification.sh 测的是 gate.sh —— 那是读日志的一方。而日志是 run-all.sh
# 写的：run-all 若把失败的脚本判成 PASS，gate 读到的就是一份假日志，两级门禁一起绿。
# 判定逻辑在源头，却一直没有断言覆盖。
#
# 做法：造一批「剧本明确」的假 runner（每条只做一件事），让**真实的** run-all.sh 去跑，
# 断言它给出的分类与退出码。放临时目录里跑，绝不碰真实仓库与真实日志。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1 -> $2"; fail=$((fail+1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# run-all.sh 会 cd 到脚本的 ../..，所以复制一份到临时仓库里运行：
# known-broken.txt / 日志目录都落在这份副本上，真实仓库不被触碰。
REPO="$WORK/repo"
mkdir -p "$REPO/scripts/qa/lib"
cp "$ROOT/scripts/qa/run-all.sh" "$REPO/scripts/qa/run-all.sh"
# lib/ 必须一起复制：run-all 靠它做产物陈旧检查。第一次漏了，检查器没定义、一声不响，
# 场景 12 因此挂着「打了 ENV STALE」一直红——这红是对的，红的是测试环境搭错了。
cp "$ROOT/scripts/qa/lib/deps.sh" "$REPO/scripts/qa/lib/deps.sh"
RA="$REPO/scripts/qa/run-all.sh"

reset() {
  rm -rf "$REPO/runners" "$WORK/logs"
  mkdir -p "$REPO/runners" "$WORK/logs"
  printf '# 空清单\n' > "$REPO/scripts/qa/known-broken.txt"
}

# 造一个假 runner：文件名 + 脚本体
mk() { printf '%s\n' "$2" > "$REPO/runners/$1"; chmod +x "$REPO/runners/$1"; }

run_all() {
  ( cd "$REPO" && QA_RUNNER_GLOB="$REPO/runners/*.sh" QA_LOG_DIR="$WORK/logs" \
      bash "$RA" 2>&1 )
}

# 从汇总行里取一个计数。取不到返回空串（调用方据此判「汇总行都没了」）。
num() { printf '%s' "$1" | sed -n "s/.*$2=\([0-9]\+\).*/\1/p" | tail -1; }

# ---------------------------------------------------------------- 场景 1
# 正向基线：两个正常脚本必须都能判 PASS，否则后面的失败场景「红了」也可能只是
# run-all 根本跑不起来。没有这条，其它断言全是假阳性。
echo "== 场景 1：两种通过写法都应判 PASS =="
reset
mk r-a.sh 'echo "=== 结果：20 通过 / 0 失败 ==="'
mk r-b.sh 'echo "PASS: 9  FAIL: 0  TOTAL: 9"'
out=$(run_all); rc=$?
[ "$(num "$out" PASS)" = "2" ] && ok "PASS=2" || bad "PASS=2" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" FAIL)" = "0" ] && ok "FAIL=0" || bad "FAIL=0" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 2
echo "== 场景 2：断言失败（exit 1）必须判 FAIL =="
reset
mk r-bad.sh 'echo "2 failed"; exit 1'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -ne 0 ] && ok "退出码非 0" || bad "退出码非 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 3
# 「跑完了但什么都没说」——最常见的假绿形态：脚本 exit 0，输出里没有通过标记。
echo "== 场景 3：exit 0 但无通过标记 → FAIL（不是 PASS） =="
reset
mk r-silent.sh 'echo "done"; exit 0'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" PASS)" = "0" ] && ok "PASS=0" || bad "PASS=0" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 4
# exit 3 是约定的「环境不具备」，但只有脚本自己说了才算。
# 否则真实错误恰好返回 3 时会被静默吞成 SKIP —— 表现是「少一个 PASS」，
# 而新增失败 runner 时总数不变，门禁 5 也看不出来。
echo "== 场景 4：exit 3 但没说明 SKIP 理由 → FAIL =="
reset
mk r-boom.sh 'echo "internal compiler error"; exit 3'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" SKIP)" = "0" ] && ok "SKIP=0（没被吞成跳过）" || bad "SKIP=0（没被吞成跳过）" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 5
echo "== 场景 5：exit 3 且自报 SKIP → 判 SKIP（不阻塞） =="
reset
mk r-nokc.sh 'echo "KS_HARNESS_SKIP r-nokc.sh 找不到 Kotlin 编译器" >&2; exit 3'
out=$(run_all); rc=$?
[ "$(num "$out" SKIP)" = "1" ] && ok "SKIP=1" || bad "SKIP=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" FAIL)" = "0" ] && ok "FAIL=0" || bad "FAIL=0" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 6
# 核心：一条断言都没跑。旧规则里 `0 失败` 是「通过」的说法，于是双零被判 PASS ——
# 空集合满足全称命题，这是本项目反复踩的同一个坑。
echo "== 场景 6：0 通过 / 0 失败（空跑）→ FAIL =="
reset
mk r-empty.sh 'echo "=== 结果：0 通过 / 0 失败 ==="'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" PASS)" = "0" ] && ok "PASS=0（没被当成空跑通过）" || bad "PASS=0（没被当成空跑通过）" "$(printf '%s' "$out" | tail -3)"
grep -q "0 条断言" <<<"$out" && ok "失败原因写明「0 条断言」" \
  || bad "失败原因写明「0 条断言」" "$(printf '%s' "$out" | grep FAIL | head -2)"

# ---------------------------------------------------------------- 场景 7
# 双零检测的左边界：`100 通过 / 0 失败` 含子串 `0 通过`，若不加边界会被误杀。
# 这条断的是「修 bug 时别把正常输出一起杀掉」。
echo "== 场景 7：100 通过 / 0 失败 不应被当成空跑（左边界） =="
reset
mk r-hundred.sh 'echo "=== 结果：100 通过 / 0 失败 ==="'
out=$(run_all); rc=$?
[ "$(num "$out" PASS)" = "1" ] && ok "PASS=1" || bad "PASS=1" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 8
echo "== 场景 8：清单内的失败脚本 → XFAIL（不阻塞） =="
reset
mk r-known.sh 'echo "boom"; exit 1'
printf 'r-known.sh\t历史失效\n' > "$REPO/scripts/qa/known-broken.txt"
out=$(run_all); rc=$?
[ "$(num "$out" XFAIL)" = "1" ] && ok "XFAIL=1" || bad "XFAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" FAIL)" = "0" ] && ok "FAIL=0" || bad "FAIL=0" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 9
echo "== 场景 9：清单内的脚本通过了 → 报 XPASS（提示该删清单） =="
reset
mk r-revived.sh 'echo "=== 结果：5 通过 / 0 失败 ==="'
printf 'r-revived.sh\t已修好但忘了删\n' > "$REPO/scripts/qa/known-broken.txt"
out=$(run_all); rc=$?
grep -q "^XPASS " <<<"$out" && ok "输出 XPASS 行" || bad "输出 XPASS 行" "$(printf '%s' "$out" | grep -E 'PASS|XFAIL' | head -3)"
[ "$(num "$out" PASS)" = "1" ] && ok "PASS=1（不因在清单里就扣掉）" || bad "PASS=1" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 10
# 每个 runner 都要留一份完整日志：失败时只看 tail 定不了位（编译器内部错误的关键行在前头）。
echo "== 场景 10：每个 runner 都落完整日志 =="
reset
mk r-log-a.sh 'echo "line1"; echo "line2"; exit 1'
out=$(run_all)
# 日志名由 runner 文件名去掉 .sh 得来（run-all 里 `$(basename "$s" .sh).log`）
if [ -s "$WORK/logs/r-log-a.log" ] && grep -q "line1" "$WORK/logs/r-log-a.log"; then
  ok "日志含完整输出"
else
  bad "日志含完整输出" "文件缺失或为空：$WORK/logs/r-log-a.log"
fi

# ---------------------------------------------------------------- 场景 11
# 真实事故：本脚本自己的用例标题里写着「0 通过 / 0 失败」，空跑检测上线后立刻把
# **这个自测**判成空跑，整轮 FAIL。检测器不能吞掉「讨论这个现象的文字」——
# 否则任何诊断脚本、任何把失败样例贴进日志的行为，都会造成假红。
# 代价是不能只认裸计数，得要求同一行里有「结果 / 汇总 / TOTAL」这类上下文词。
echo "== 场景 11：讨论空跑的文本不应被判成空跑（不误杀） =="
reset
mk r-talk.sh 'echo "== 场景 6：0 通过 / 0 失败（空跑）→ FAIL =="; echo "=== 汇总: 22 passed, 0 failed ==="'
out=$(run_all); rc=$?
[ "$(num "$out" PASS)" = "1" ] && ok "PASS=1（讨论文字没被当成结果）" \
  || bad "PASS=1（讨论文字没当成结果）" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 12
# 产物陈旧是「绿色的假象」：PASS 数照旧、FAIL=0，但依赖 classpath 的 harness 读的是
# 旧字节码，报错指向早已改过的源码行。run-all 只负责打标记，由 gate.sh 判为不可信。
echo "== 场景 12：产物比源码旧 → 汇总后打 ENV STALE =="
reset
mkdir -p "$REPO/app/src/main/java/x" "$REPO/app/build/tmp/kotlin-classes/debug"
printf 'x\n' > "$REPO/app/build/tmp/kotlin-classes/debug/X.class"
sleep 1                      # 秒级 mtime 精度，必须拉开
printf 'fun x() {}\n' > "$REPO/app/src/main/java/x/X.kt"
mk r-ok.sh 'echo "=== 结果：5 通过 / 0 失败 ==="'
out=$(run_all)
grep -q "^ENV STALE" <<<"$out" && ok "打了 ENV STALE" \
  || bad "打了 ENV STALE" "$(printf '%s' "$out" | tail -3)"
grep -q "^WARN  编译产物陈旧" <<<"$out" && ok "开头有 WARN 提示" \
  || bad "开头有 WARN 提示" "$(printf '%s' "$out" | head -4)"

# ---------------------------------------------------------------- 场景 13
# 负向对照：产物比源码新时不能打标记。没有这条，场景 12 只要「永远打标记」就能过。
echo "== 场景 13：产物比源码新 → 不应打 ENV STALE =="
reset
rm -rf "$REPO/app"
mkdir -p "$REPO/app/src/main/java/x" "$REPO/app/build/tmp/kotlin-classes/debug"
printf 'fun x() {}\n' > "$REPO/app/src/main/java/x/X.kt"
sleep 1
printf 'x\n' > "$REPO/app/build/tmp/kotlin-classes/debug/X.class"
mk r-ok.sh 'echo "=== 结果：5 通过 / 0 失败 ==="'
out=$(run_all)
if grep -q "^ENV STALE" <<<"$out"; then
  bad "不打 ENV STALE" "产物较新却仍打了标记：$(printf '%s' "$out" | grep '^ENV' | head -1)"
else
  ok "不打 ENV STALE"
fi

# ---------------------------------------------------------------- 场景 14
# R-04：`10 失败` 里含子串 `0 失败`，旧正则会把它判成 PASS。
# 这类脚本的 rc 还是 0（末尾是 echo，没有 set -e），所以两条证据同时失效——
# 只能靠「失败优先于通过」这条顺序来堵。
echo "== 场景 14：10 失败 不得被当成 0 失败（子串陷阱） =="
reset
mk r-ten.sh 'echo "=== 结果：11 通过 / 10 失败 ==="; exit 0'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" PASS)" = "0" ] && ok "PASS=0" || bad "PASS=0" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 15
# 更难的一种：子步骤打了 FAIL=0，总结果却是失败。两行都在，谁先匹配到全看顺序。
echo "== 场景 15：子步骤 FAIL=0 + 总结果 3 失败 → FAIL（失败优先） =="
reset
mk r-mixed.sh 'echo "步骤 1：FAIL=0"; echo "步骤 2：FAIL=0"; echo "=== 汇总：7 通过 / 3 失败 ==="; exit 0'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 16
# 负向对照：没有 14/15 的对照，把「永远判 FAIL」写进 run-all 也能全过。
echo "== 场景 16：正常汇总 + 含数字的说明文字 → 仍应 PASS（不误杀） =="
reset
mk r-ok.sh 'echo "=== 结果：22 通过 / 0 失败 ==="; echo "1 个用例重试后成功"; exit 0'
out=$(run_all); rc=$?
[ "$(num "$out" PASS)" = "1" ] && ok "PASS=1" || bad "PASS=1" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

# ---------------------------------------------------------------- 场景 17
# R-17：SKIP 的判据一直由 runner 自述——说一句「缺东西」就能整段消失。
# 这里把理由变成可核验的事实：声称缺的路径要真的不存在，否则就不是环境问题，
# 是 runner 的判据坏了（或者它在撒谎）。这是「零覆盖能不能过门禁」的源头。
echo "== 场景 17：SKIP 声称缺的东西其实在 → FAIL（理由不成立） =="
reset
mkdir -p "$REPO/present-dir"          # 声称缺的目录，实际好好地在这儿
mk r-liar.sh "echo \"KS_HARNESS_SKIP r-liar.sh 缺 AGP 产物：$REPO/present-dir\" >&2; exit 3"
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" SKIP)" = "0" ] && ok "SKIP=0（没被当成环境跳过）" || bad "SKIP=0" "$(printf '%s' "$out" | tail -3)"
grep -q "理由不成立" <<<"$out" && ok "失败原因写明「理由不成立」" \
  || bad "失败原因写明「理由不成立」" "$(printf '%s' "$out" | grep FAIL | head -2)"

# ---------------------------------------------------------------- 场景 18
# 裸的 skip 字样不算数：真实错误信息里带 skip 太常见（`skipping optimization`、
# 段内提示行）。协议不统一时，「是不是 SKIP」全凭运气。
echo "== 场景 18：exit 3 + 裸 skip 字样，但没有 KS_HARNESS_SKIP → FAIL =="
reset
mk r-vague.sh 'echo "skipping: no compiler available"; exit 3'
out=$(run_all); rc=$?
[ "$(num "$out" FAIL)" = "1" ] && ok "FAIL=1" || bad "FAIL=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" SKIP)" = "0" ] && ok "SKIP=0" || bad "SKIP=0" "$(printf '%s' "$out" | tail -3)"

# ---------------------------------------------------------------- 场景 19
# 负向对照：不给这条，把「SKIP 一律判 FAIL」写进 run-all 也能过 17/18。
echo "== 场景 19：理由诚实（路径确实不存在）→ 仍判 SKIP =="
reset
mk r-honest.sh "echo \"KS_HARNESS_SKIP r-honest.sh 缺 AGP 产物：$REPO/nowhere/debug\" >&2; exit 3"
out=$(run_all); rc=$?
[ "$(num "$out" SKIP)" = "1" ] && ok "SKIP=1" || bad "SKIP=1" "$(printf '%s' "$out" | tail -3)"
[ "$(num "$out" FAIL)" = "0" ] && ok "FAIL=0" || bad "FAIL=0" "$(printf '%s' "$out" | tail -3)"
[ "$rc" -eq 0 ] && ok "退出码 0" || bad "退出码 0" "实际 $rc"

echo
echo "=== 汇总: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ] || exit 1