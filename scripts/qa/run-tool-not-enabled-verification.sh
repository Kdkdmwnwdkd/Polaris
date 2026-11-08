#!/usr/bin/env bash
# 「工具存在但未启用」路径的反向验证。
#
# ## 为什么需要这个脚本
#
# 2026-11-04 真机验证发现：模型直接调 `calculator`（登记在 sys 组、默认未激活）时，
# 回执说的是「未知工具」——而工具**确实存在**，模型**没记错名字**，只是少开了一个组。
# 统一说「未知工具」会让模型以为自己凭空编了个工具，转而去猜别的名字，
# 把一次「开组」能解决的往返变成若干次瞎猜 + 失败回填。
#
# 修法：新增 `tool_not_enabled` 失败码，回执指出所属组并教模型调 `__use_tools`。
#
# ## 这个脚本验的是什么
#
# **不是**「功能在不在」——那是 `run-localmodel-verification.sh` 干的。
# 这里验的是：**那条新断言真的在测东西**。
#
# 一条断言如果怎么改产品代码都绿，它就是装饰。唯一的证法是**把修复回退，
# 看它是否变红**。所以这个脚本会：
#
#   1. 先确认当前代码下断言是绿的（前提成立）；
#   2. 临时把 `fullRegistry` 注入回退掉（恢复成「一律按未知工具处理」）；
#   3. 重跑断言 —— **必须变红**，否则说明断言没测到点子上；
#   4. 恢复原代码 —— **必须变绿**，否则说明它测的是残留状态。
#
# 第 4 步同样重要：只做「回退→变红」而不恢复，等于把仓库留在坏状态；
# 而「恢复后仍红」说明断言本身不稳定（依赖顺序/状态），那种断言也不可信。
set -uo pipefail
cd "$(dirname "$0")/../.."

# ---------------------------------------------------------------------------
# 安全闸：默认只做「只读体检」，**不改任何源码**。
#
# 为什么要这个闸：这个脚本的核心动作是**临时改源码**（把 fullRegistry 注入删掉
# 再恢复）。而 `run-all.sh` 会自动扫描并执行 `scripts/qa/run-*.sh`——如果不加闸，
# 每次跑全量 harness 都会改一遍源码再改回来。万一中途被杀（CI 超时、机器重启），
# 仓库就留在「回退态」——那是一个**看起来正常但功能有缺陷**的状态，比直接失败更危险。
#
# 所以：
#   - 默认（无 AK_ALLOW_SOURCE_MUTATION=1）：只跑第 1 步体检 + 静态检查，不碰源码；
#   - 显式开关打开：才执行完整的「回退→变红→恢复→变绿」。
#
# 静态检查仍然有意义：它能确认「注入点确实存在」（即这个脚本还测得到东西），
# 以及「断言文案确实在断言区分两种情形」——这两条不碰源码就能查。
# ---------------------------------------------------------------------------
ALLOW_MUTATION="${AK_ALLOW_SOURCE_MUTATION:-0}"

CORE="core/src/main/java/com/beichen/polaris/core/agent/ToolExecutor.kt"
LOOP="core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt"
HARNESS="scripts/qa/run-localmodel-verification.sh"
# 断言**源码**在 .kt 里（runner 只是编译并执行它）——查错文件会误报。
ASSERT_SRC="scripts/qa/LocalModelVerification.kt"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  ✅ $1"; }
bad() { fail=$((fail+1)); echo "  ❌ $1"; }

[ -f "$CORE" ] || { echo "FAIL: 找不到 $CORE"; exit 1; }
[ -f "$LOOP" ] || { echo "FAIL: 找不到 $LOOP"; exit 1; }
[ -f "$HARNESS" ] || { echo "FAIL: 找不到 $HARNESS"; exit 1; }
[ -f "$ASSERT_SRC" ] || { echo "FAIL: 找不到 $ASSERT_SRC"; exit 1; }

run_harness() {
  # 需要 AGP 编译产物（集成型 harness）。缺产物就明确 SKIP，不假装通过。
  # 拆模块后（:app + :core）要拼两个目录；只用 app 会让 harness 找不到 core 的类
  local classes=${AGENTKIT_CLASSES:-}
  if [ -z "$classes" ]; then
    [ -d app/build/tmp/kotlin-classes/debug ] && classes=app/build/tmp/kotlin-classes/debug
    [ -d core/build/tmp/kotlin-classes/debug ] && classes="${classes:+$classes:}core/build/tmp/kotlin-classes/debug"
  fi
  if [ -z "$classes" ]; then
    echo "  (缺少 AGP 编译产物 $classes，跳过)"
    return 3
  fi
  AGENTKIT_CLASSES="$classes" bash "$HARNESS" 2>&1
}

# ---------- 只读体检（任何情况下都跑，且不碰源码） ----------
echo "[1/4] 只读体检：注入点存在 + 断言在区分两种情形"
if grep -q "fullRegistry = registry" "$LOOP"; then
  ok "注入点存在（RealAgentLoop 确实把全量 registry 传给了 executor）"
else
  bad "注入点不存在——这个脚本已经测不到东西了（代码结构变了？）"
fi
if grep -q "tool_not_enabled" "$ASSERT_SRC"; then
  ok "断言在断言 tool_not_enabled（不是只看 unknown_tool）"
else
  bad "断言里没有 tool_not_enabled——它分不清「未启用」与「不存在」"
fi
if grep -q "unknown_tool" "$ASSERT_SRC"; then
  ok "断言同时保留了 unknown_tool 情形（真不存在的工具仍是它）"
else
  bad "断言不再覆盖 unknown_tool——两种情形没被区分开"
fi

if [ "$ALLOW_MUTATION" != "1" ]; then
  echo
  echo "  (跳过源码变异：未设 AK_ALLOW_SOURCE_MUTATION=1。"
  echo "   完整反向验证会临时改源码，为避免中途被杀留下脏仓库，默认不开。"
  echo "   需要时显式执行：AK_ALLOW_SOURCE_MUTATION=1 bash $0)"
  echo
  echo "反向验证（只读）：$pass 通过 / $fail 失败"
  [ $fail -ne 0 ] && exit 1
  echo "PASS 「工具存在但未启用」路径有断言守着（只读体检全部通过）"
  exit 0
fi

# ---------- 以下会改源码，仅在显式开关打开时执行 ----------
echo
echo "  ⚠️ 已启用源码变异模式（AK_ALLOW_SOURCE_MUTATION=1）"

# 备份要还原的两处（注入点 + 回退点）。
BAK_CORE=$(mktemp); BAK_LOOP=$(mktemp)
cp "$CORE" "$BAK_CORE"; cp "$LOOP" "$BAK_LOOP"
# restore 允许被调用多次（显式调用 + EXIT trap 兜底）：
# 第二次调用时备份已删，直接返回而不是报错刷屏。
restore() {
  [ -f "$BAK_CORE" ] && cp "$BAK_CORE" "$CORE"
  [ -f "$BAK_LOOP" ] && cp "$BAK_LOOP" "$LOOP"
  rm -f "$BAK_CORE" "$BAK_LOOP"
}
trap restore EXIT

echo "[2/4] 当前代码下断言应为绿"
out=$(run_harness); rc=$?
if [ $rc -eq 3 ]; then
  # R-17：走统一协议并带上绝对路径——run-all 会去 stat 这个目录，
  # 若它其实存在，本轮就不是「环境不具备」，而是判定坏了。
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 AGP 编译产物：$(pwd)/app/build/tmp/kotlin-classes/debug" >&2
  exit 3
fi
if [ $rc -eq 0 ]; then
  ok "当前实现下断言通过（前提成立）"
else
  bad "当前实现下断言就红——先修实现再谈反向验证"
  echo "$out" | tail -20 | sed 's/^/    /'
  exit 1
fi

echo "[3/4] 回退修复：不再注入 fullRegistry，断言必须变红"
# 回退方式：把注入点删掉（而不是改实现内部逻辑）——
# 这样恢复后字节级一致，不会引入「手工复原写错了」的隐患。
python3 - "$LOOP" <<'PY'
import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
before = s
# 去掉 fullRegistry = registry 这一行（含其上一行注释里的注入说明）
s = re.sub(r'\n[^\n]*fullRegistry = registry\n', '\n', s)
if s == before:
    print("MUTATION_FAILED: 没找到 fullRegistry = registry 注入点")
    sys.exit(2)
open(p, 'w', encoding='utf-8').write(s)
print("MUTATED")
PY
mut_rc=$?
if [ $mut_rc -ne 0 ]; then
  bad "变异失败：注入点没找到（代码结构变了？）"
  exit 1
fi
# 变异后必须重新编译，否则跑的还是旧产物。
./gradlew :app:compileDebugKotlin --console=plain >/tmp/ak-mutate-build.log 2>&1
if [ $? -ne 0 ]; then
  bad "变异后编译失败"
  tail -15 /tmp/ak-mutate-build.log | sed 's/^/    /'
  restore
  exit 1
fi
out2=$(run_harness); rc2=$?
if [ $rc2 -eq 0 ]; then
  bad "回退后断言仍然绿——这条断言没测到点子上（是装饰）"
  exit 1
elif echo "$out2" | grep -q "tool_not_enabled"; then
  ok "回退后断言变红（exit=$rc2）——断言确实在测这条路径"
else
  bad "回退后变红了，但红的不是预期的断言（exit=$rc2）"
  echo "$out2" | tail -20 | sed 's/^/    /'
fi

echo "[4/4] 恢复原代码后必须重新变绿"
restore
./gradlew :app:compileDebugKotlin --console=plain >/tmp/ak-restore-build.log 2>&1
if [ $? -ne 0 ]; then
  bad "恢复后编译失败"
  tail -15 /tmp/ak-restore-build.log | sed 's/^/    /'
  exit 1
fi
out3=$(run_harness); rc3=$?
if [ $rc3 -eq 0 ]; then
  ok "恢复后重新变绿——断言稳定，不依赖残留状态"
else
  bad "恢复后仍然红（exit=$rc3）——断言不稳定或恢复不干净"
  echo "$out3" | tail -20 | sed 's/^/    /'
  exit 1
fi

echo
echo "反向验证：$pass 通过 / $fail 失败"
[ $fail -ne 0 ] && exit 1
echo "PASS 「工具存在但未启用」路径有断言守着（回退必红，全部通过）"
exit 0
