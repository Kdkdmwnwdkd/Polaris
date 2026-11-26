#!/usr/bin/env bash
# gate.sh 的自测：门禁本身也要被断言覆盖，否则门禁的 bug 没人会发现。
#
# 做法：在临时目录造出 4 种「仓库状态」副本，分别跑 gate.sh，断言它的退出码与输出。
# 全部只动临时目录，绝不碰真实仓库。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
GATE="$ROOT/scripts/qa/gate.sh"

pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1 -> $2"; fail=$((fail+1)); }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 造一个最小「仓库」：gate.sh + 三个清单文件。
#
# 四样缺一不可，否则红会落在「文件缺失」而不是被测的那一条：
#   门禁 5 读 harness-baseline.txt，门禁 6 读 skip-allowlist.txt。
# 注意**不要**直接复制真实登记册：里面列的是真实 runner，临时仓库没有那些文件，
# 会先被僵尸检查拦下。这里写一份空的（上限 0），让门禁 6 静默通过。
mk_repo() {
  local d="$WORK/$1"
  mkdir -p "$d/scripts/qa"
  cp "$GATE" "$d/scripts/qa/gate.sh"
  cp "$ROOT/scripts/qa/harness-baseline.txt" "$d/scripts/qa/harness-baseline.txt"
  printf '%s\n' "$2" > "$d/scripts/qa/known-broken.txt"
  { echo "# 测试用空登记册"; echo "# 条目上限：0"; } > "$d/scripts/qa/skip-allowlist.txt"
  echo "$d"
}

# 门禁 6 的专用仓库：runner 文件真的存在（否则会被判僵尸），登记册内容可定制。
mk_skip_repo() {
  local d="$WORK/$1" cap="$2" regs="$3" runners="$4"
  mkdir -p "$d/scripts/qa"
  cp "$GATE" "$d/scripts/qa/gate.sh"
  cp "$ROOT/scripts/qa/harness-baseline.txt" "$d/scripts/qa/harness-baseline.txt"
  printf '# 空清单\n' > "$d/scripts/qa/known-broken.txt"
  { echo "# 条目上限：$cap"; printf '%s\n' "$regs"; } > "$d/scripts/qa/skip-allowlist.txt"
  for r in $runners; do
    printf '#!/bin/sh\necho "=== 结果：1 通过 / 0 失败 ===\n"\n' > "$d/scripts/qa/$r"
  done
  echo "$d"
}

# 真实基线值：沙盒日志的 PASS 数要与之一致，否则「全绿应通过」的场景会被门禁 5 拦下。
BASE=$(awk '!/^[[:space:]]*(#|$)/ {print $1; exit}' "$ROOT/scripts/qa/harness-baseline.txt")

# ---------------------------------------------------------------- 场景 1
echo "== 场景 1：清单归零 + 日志全绿 → 应 PASS =="
R=$(mk_repo ok "\
# 注释行，忽略
# run-foo.sh	历史记录
")
cat > "$WORK/ok.log" <<EOF
PASS  scripts/qa/run-a.sh
PASS  scripts/qa/run-b.sh
==== 汇总：PASS=$BASE SKIP=0 XFAIL=0 FAIL=0
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -eq 0 ]; then ok "退出码 0"
else bad "退出码 0" "实际 $rc：$out"; fi
case "$out" in *"GATE PASS"*) ok "输出 GATE PASS";; *) bad "输出 GATE PASS" "$out";; esac

# ---------------------------------------------------------------- 场景 2
echo "== 场景 2：清单非空 → 应失败并列出条目 =="
R=$(mk_repo broken "\
run-broken-a.sh	编译不过
run-broken-b.sh	签名对不上
")
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"run-broken-a.sh"*) ok "输出列出条目 a";; *) bad "输出列出条目 a" "$out";; esac
case "$out" in *"run-broken-b.sh"*) ok "输出列出条目 b";; *) bad "输出列出条目 b" "$out";; esac
case "$out" in *"GATE BLOCKED"*) ok "输出 GATE BLOCKED";; *) bad "输出 GATE BLOCKED" "$out";; esac

# ---------------------------------------------------------------- 场景 3
echo "== 场景 3：汇总 FAIL>0 → 应失败 =="
R=$(mk_repo failq "\
# 空清单
")
cat > "$WORK/fail.log" <<'EOF'
PASS  scripts/qa/run-a.sh
FAIL  scripts/qa/run-b.sh（exit=1）
==== 汇总：PASS=47 SKIP=0 XFAIL=0 FAIL=1
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/fail.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"FAIL=1"*) ok "输出提到 FAIL=1";; *) bad "输出提到 FAIL=1" "$out";; esac

# ---------------------------------------------------------------- 场景 4
echo "== 场景 4：出现 XPASS → 应失败（清单该更新了） =="
cat > "$WORK/xpass.log" <<'EOF'
XPASS scripts/qa/run-c.sh（已修复！请从 known-broken.txt 移除）
==== 汇总：PASS=48 SKIP=0 XFAIL=0 FAIL=0
EOF
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/xpass.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi
case "$out" in *"XPASS"*) ok "输出提到 XPASS";; *) bad "输出提到 XPASS" "$out";; esac

# ---------------------------------------------------------------- 场景 5
echo "== 场景 5：逃生阀 QA_GATE=warn → 只告警不失败 =="
R=$(mk_repo warn "\
run-still-broken.sh	还没修
")
out=$(cd "$R" && QA_GATE=warn bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -eq 0 ]; then ok "退出码 0"
else bad "退出码 0" "实际 $rc：$out"; fi
case "$out" in *"仅告警"*) ok "输出提示仅告警";; *) bad "输出提示仅告警" "$out";; esac

# ---------------------------------------------------------------- 场景 6
echo "== 场景 6：清单文件缺失 → 应失败（不能默默放行） =="
R="$WORK/missing"
mkdir -p "$R/scripts/qa"
cp "$GATE" "$R/scripts/qa/gate.sh"
{ echo "# 条目上限：0"; } > "$R/scripts/qa/skip-allowlist.txt"
out=$(cd "$R" && bash scripts/qa/gate.sh "$WORK/ok.log" 2>&1); rc=$?
if [ $rc -ne 0 ]; then ok "退出码非 0"
else bad "退出码非 0" "实际 0：$out"; fi

# ---------------------------------------------------------------- 场景 7
echo "== 场景 7：真实仓库的清单必须归零 =="
if [ -f "$ROOT/scripts/qa/known-broken.txt" ]; then
  n=$(awk '!/^[[:space:]]*(#|$)/ {print $1}' "$ROOT/scripts/qa/known-broken.txt" | grep -c . || true)
  if [ "${n:-0}" -eq 0 ]; then ok "真实清单为 0 条"
  else bad "真实清单为 0 条" "实际 $n 条"; fi
else
  bad "真实清单文件存在" "scripts/qa/known-broken.txt 不存在"
fi

# ---------------------------------------------------------------- 场景 8
# 门禁 4：runner 数 > 日志里实际跑过的行数 → 说明有 runner 没被 run-all 收录。
# 构造方法：日志只写 1 行 PASS，但仓库里有几十个 run-*.sh，必然对不上。
echo "== 场景 8：runner 数多于日志行数 → 应失败（有 runner 没跑到） =="
LOG8="$WORK/qa8.log"
{
  echo "PASS  scripts/qa/run-fake-one.sh"
  echo "==== 汇总：PASS=1 SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG8"
set +e
OUT8=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG8" 2>&1)
RC8=$?
set -e
if [ "$RC8" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 $RC8"; fi
if grep -q "只跑了" <<<"$OUT8"; then ok "输出说明有 runner 没跑到"
else bad "输出说明有 runner 没跑到" "$(printf '%s' "$OUT8" | tail -3)"; fi

# ---------------------------------------------------------------- 场景 9
# 门禁 4 的正向：真实仓库 + 真实日志必须通过（防止门禁 4 自己误伤正常构建）
echo "== 场景 9：真实日志应通过门禁 4 =="
LOG9="$WORK/qa9.log"
{
  for s in $(ls "$ROOT"/scripts/qa/run-*.sh | grep -v 'run-all\.sh$'); do
    echo "PASS  ${s#"$ROOT"/}"
  done
  echo "==== 汇总：PASS=$(ls "$ROOT"/scripts/qa/run-*.sh | grep -vc 'run-all\.sh$') SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG9"
set +e
OUT9=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG9" 2>&1)
RC9=$?
set -e
if [ "$RC9" -eq 0 ]; then ok "全跑时门禁放行"
else bad "全跑时门禁放行" "$(printf '%s' "$OUT9" | grep 'GATE FAIL' | head -2)"; fi

# ---------------------------------------------------------------- 场景 10
# 门禁 5：环境齐备（SKIP=0）时，PASS 不得低于基线。
#
# 它防的不是「环境退化」（那种情况全是 SKIP，从 R-17 起由门禁 6 逐条核对），
# 而是**悄悄删 harness**：删掉一个 run-*.sh，runner 总数跟着变小，门禁 4 的
# 「数量对得上」依然满足——只有 PASS 掉到基线以下才看得出来。
# 这是本项目最省力的「修红办法」：门禁红了就删脚本，比修断言快得多。
echo "== 场景 10：SKIP=0 但 PASS 低于基线 → 应失败（删 harness 也是假绿） =="
LOG10="$WORK/qa10.log"
{
  # 106 个 runner 只跑 105 个，汇总写 105 —— 模拟「删了一个脚本、基线没动」
  for s in $(ls "$ROOT"/scripts/qa/run-*.sh | grep -v 'run-all\.sh$' | head -105); do
    echo "PASS  ${s#"$ROOT"/}"
  done
  echo "==== 汇总：PASS=105 SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG10"
set +e
OUT10=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG10" 2>&1)
RC10=$?
set -e
if [ "$RC10" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 0"; fi
if grep -q "低于基线" <<<"$OUT10"; then ok "输出说明 PASS 低于基线"
else bad "输出说明 PASS 低于基线" "$(printf '%s' "$OUT10" | tail -3)"; fi

# ---------------------------------------------------------------- 场景 10b
# R-17：有 SKIP 时门禁 5 必须**主动让位**，不能继续硬比基线。
# 否则本机（PASS=64 / SKIP=42）与 CI（PASS=106 / SKIP=0）永远有一个是红的，
# 而长期红的门禁等于没有门禁。让位之后由门禁 6 接手：谁没跑、缺件是否真缺、
# 登记册有没有变长——三把更细的尺子。
echo "== 场景 10b：有 SKIP 时门禁 5 让位（不比基线，也不静默） =="
LOG10B="$WORK/qa10b.log"
{
  for s in $(ls "$ROOT"/scripts/qa/run-*.sh | grep -v 'run-all\.sh$'); do
    echo "SKIP  ${s#"$ROOT"/}"
  done
  echo "==== 汇总：PASS=0 SKIP=106 XFAIL=0 FAIL=0"
} > "$LOG10B"
set +e
OUT10B=$(bash "$ROOT/scripts/qa/gate.sh" "$LOG10B" 2>&1)
set -e
if grep -q "PASS 下限不可比" <<<"$OUT10B"; then ok "输出说明不可比（让位）"
else bad "输出说明不可比（让位）" "$(printf '%s' "$OUT10B" | grep 'GATE' | head -4)"; fi
if grep -q "PASS=0 低于基线" <<<"$OUT10B"; then
  bad "不再拿 PASS 跟基线比" "仍然报了低于基线"
else
  ok "不再拿 PASS 跟基线比"
fi

# ---------------------------------------------------------------- 场景 11
# 产物陈旧：PASS 数够、FAIL=0，但结论不可信 —— 依赖 classpath 的 harness 读的是旧字节码，
# 报错会指向早已改过的源码行。这类轮次看起来是绿的，实际什么都没验证对。
echo "== 场景 11：日志带 ENV STALE（产物陈旧）→ 应失败 =="
R=$(mk_repo stale "\
# 空清单
")
LOG11="$WORK/qa11.log"
{
  for s in $(ls "$ROOT"/scripts/qa/run-*.sh | grep -v 'run-all\.sh$'); do
    echo "PASS  ${s#"$ROOT"/}"
  done
  echo "==== 汇总：PASS=$(ls "$ROOT"/scripts/qa/run-*.sh | grep -vc 'run-all\.sh$') SKIP=0 XFAIL=0 FAIL=0"
  echo "ENV STALE classes 比源码旧（app/src/main/java/.../Foo.kt）"
} > "$LOG11"
set +e
OUT11=$(cd "$R" && bash scripts/qa/gate.sh "$LOG11" 2>&1)
RC11=$?
set -e
if [ "$RC11" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 0"; fi
if grep -q "产物比源码旧" <<<"$OUT11"; then ok "输出说明产物陈旧"
else bad "输出说明产物陈旧" "$(printf '%s' "$OUT11" | grep 'GATE' | head -3)"; fi
# 红必须来自产物陈旧，不是别的门禁顺带触发（否则断言指向了别的原因）
if grep -q "GATE OK 5/5" <<<"$OUT11"; then ok "门禁 5 仍放行（红的是产物陈旧）"
else bad "门禁 5 仍放行（红的是产物陈旧）" "$(printf '%s' "$OUT11" | grep 'GATE' | head -5)"; fi

# ---------------------------------------------------------------- 场景 12
# 门禁 6（R-17）：SKIP 是唯一「什么都不验证也算过」的状态，而 run-all 只比 PASS 总数。
# 新加一个 runner、第一行就 exit 3，PASS 不变、门禁 5 照样绿 —— 这个 harness 从诞生
# 那天起就是零覆盖。这里要求「谁 SKIP 谁登记」，把悄悄消失变成有人签字。
echo "== 场景 12：SKIP 但未登记 → 应失败（零覆盖不能默认合法） =="
R=$(mk_skip_repo sk-unreg 0 "" "run-fake-a.sh")
LOG12="$WORK/qa12.log"
{
  echo "SKIP  scripts/qa/run-fake-a.sh"
  echo "==== 汇总：PASS=$BASE SKIP=1 XFAIL=0 FAIL=0"
} > "$LOG12"
set +e
OUT12=$(bash "$R/scripts/qa/gate.sh" "$LOG12" 2>&1)
RC12=$?
set -e
if [ "$RC12" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 0"; fi
if grep -q "没有在 skip-allowlist.txt 登记" <<<"$OUT12"; then ok "输出说明未登记"
else bad "输出说明未登记" "$(printf '%s' "$OUT12" | grep 'GATE' | head -3)"; fi
# 红必须来自门禁 6：门禁 4 满足、门禁 5 让位，否则断言指向了别的原因
if grep -q "GATE OK 4/5" <<<"$OUT12"; then ok "门禁 4 仍放行（红的是门禁 6）"
else bad "门禁 4 仍放行（红的是门禁 6）" "$(printf '%s' "$OUT12" | grep 'GATE' | head -5)"; fi

# ---------------------------------------------------------------- 场景 13
# 正向对照：登记了就放行。没有这条，把门禁 6 写成「有 SKIP 就红」也能过场景 12。
echo "== 场景 13：SKIP 已登记且未超上限 → 应 PASS =="
R=$(mk_skip_repo sk-reg 1 "run-fake-a.sh  缺 AGP 产物" "run-fake-a.sh")
set +e
OUT13=$(bash "$R/scripts/qa/gate.sh" "$LOG12" 2>&1)
RC13=$?
set -e
if [ "$RC13" -eq 0 ]; then ok "退出码 0"
else bad "退出码 0" "实际 $RC13：$(printf '%s' "$OUT13" | grep 'GATE' | head -3)"; fi
if grep -q "GATE OK 6/6" <<<"$OUT13"; then ok "输出 GATE OK 6/6"
else bad "输出 GATE OK 6/6" "$(printf '%s' "$OUT13" | grep 'GATE' | head -4)"; fi

# ---------------------------------------------------------------- 场景 14
# 只减不增：允许登记，但不允许「加一行就过」。上限钉在文件里，涨要人工改数字。
echo "== 场景 14：登记册条目超过上限 → 应失败（防止 SKIP 沉淀） =="
R=$(mk_skip_repo sk-over 1 "run-fake-a.sh  缺 X
run-fake-b.sh  缺 Y" "run-fake-a.sh run-fake-b.sh")
LOG14="$WORK/qa14.log"
{
  echo "SKIP  scripts/qa/run-fake-a.sh"
  echo "SKIP  scripts/qa/run-fake-b.sh"
  echo "==== 汇总：PASS=$BASE SKIP=2 XFAIL=0 FAIL=0"
} > "$LOG14"
set +e
OUT14=$(bash "$R/scripts/qa/gate.sh" "$LOG14" 2>&1)
RC14=$?
set -e
if [ "$RC14" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 0"; fi
if grep -q "超过上限" <<<"$OUT14"; then ok "输出说明超上限"
else bad "输出说明超上限" "$(printf '%s' "$OUT14" | grep 'GATE' | head -3)"; fi

# ---------------------------------------------------------------- 场景 15
# 僵尸条目：runner 删了、登记还留着。不查的话，登记册会慢慢变成「历史上谁曾经
# SKIP 过」的编年史 —— 一份没人敢信的文件比没有这份文件更糟。
echo "== 场景 15：登记了不存在的 runner → 应失败（僵尸条目） =="
R=$(mk_skip_repo sk-zombie 2 "run-gone.sh  已经删掉了" "run-fake-a.sh")
LOG15="$WORK/qa15.log"
{
  echo "PASS  scripts/qa/run-fake-a.sh"
  echo "==== 汇总：PASS=$BASE SKIP=0 XFAIL=0 FAIL=0"
} > "$LOG15"
set +e
OUT15=$(bash "$R/scripts/qa/gate.sh" "$LOG15" 2>&1)
RC15=$?
set -e
if [ "$RC15" -ne 0 ]; then ok "退出码非 0"; else bad "退出码非 0" "实际 0"; fi
if grep -q "已不存在的 runner" <<<"$OUT15"; then ok "输出说明条目已失效"
else bad "输出说明条目已失效" "$(printf '%s' "$OUT15" | grep 'GATE' | head -3)"; fi

echo
echo "=== 汇总: $pass passed, $fail failed ==="
[ "$fail" -eq 0 ] || exit 1
