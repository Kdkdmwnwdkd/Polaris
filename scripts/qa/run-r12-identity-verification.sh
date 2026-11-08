#!/usr/bin/env bash
# R-12「终端身份维度」验证。
#
# [修的是什么] 修前 `TerminalBackend.identity` 七处实现全是 `User`——包括以 uid 2000
# 跑的 Shizuku 与无线调试后端。于是「请求 user / 实际 uid 2000 / 报告 user」三件事
# 只有前两个是真的，下游据此判断权限，得出错误结论。
#
# [为什么必须真跑会话池] 这条缺陷最后落在「复用与冲突的判定」上，而那是十几个分支
# 交织的运行时行为：假池只能验签名，验不出「uid 2000 的会话能不能被 user 请求复用」。
# 真池 + 假后端（回放哨兵）能跑完整路径，且零 Android 依赖。
#
# [为什么还要源码守卫] 有一类回归是行为测不到的：把 `satisfies` 换回 `==` 之后，
# 大多数用例仍然绿（因为 fake 的档位恰好一致），只有「请求 user、会话 uid 2000」
# 那条会红。守卫直接钉住谓词本身，让这种「悄悄地退化成语义更弱的写法」无所遁形。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SRC=app/src/main/java/com/beichen/polaris
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE=core/src/main/java/com/beichen/polaris
STUBS=scripts/qa/r12-verify/stubs

p=0; f=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

# ---------------------------------------------------------------- [A] 结构
echo "[A] 文件结构"

[ -f "$SRC_CORE/core/terminal/backend/TerminalIdentity.kt" ] \
  && pass "A1 TerminalIdentity.kt 存在" \
  || fail "A1 缺 TerminalIdentity.kt"

[ -f "$STUBS/AgentLogger.kt" ] && [ -f "$STUBS/AppScope.kt" ] \
  && pass "A2 JVM 桩齐（AgentLogger / AppScope）" \
  || fail "A2 桩文件缺失：$STUBS"

# ---------------------------------------------------------------- [B] 编译并真跑
echo ""
echo "[B] 行为：真跑工厂 + 会话池（假后端回放哨兵，零 Android 依赖）"

source "$ROOT/scripts/qa/lib/deps.sh"
KC=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || KOTLINC_BIN=$(command -v kotlinc || true)

if [ -z "${KOTLINC_BIN:-}" ] || [ ! -x "$KOTLINC_BIN" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器：$KC/bin/kotlinc" >&2
  exit 3
fi

# coroutines：终端池的读流/锁/超时全靠它。从 Gradle 缓存按版本序取最新，
# 解析不到就 SKIP——**绝不拿一个不存在的路径去编译**（那会红成「代码坏了」的假象）。
COROUTINES=$(qa_find_jar "$QA_GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" \
  "kotlinx-coroutines-core-jvm-*.jar")
if [ -z "${COROUTINES:-}" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 缺 kotlinx-coroutines-core-jvm jar（Gradle 缓存：$QA_GRADLE_CACHE）" >&2
  exit 3
fi

OUT=/tmp/qa-r12-identity
rm -rf "$OUT"; mkdir -p "$OUT"
COMPILE_LOG=/tmp/qa-r12-compile.log
"$KOTLINC_BIN" -nowarn -cp "$COROUTINES" -d "$OUT" \
  "$STUBS/AgentLogger.kt" \
  "$STUBS/AppScope.kt" \
  "$SRC_CORE/core/ToolLimits.kt" \
  "$SRC_CORE/core/terminal/ToolOutputTruncator.kt" \
  "$SRC_CORE/core/terminal/backend/TerminalIdentity.kt" \
  "$SRC_CORE/core/terminal/backend/TerminalBackend.kt" \
  "$SRC_CORE/core/terminal/backend/TerminalBackendFactory.kt" \
  "$SRC_CORE/core/terminal/TerminalSessionPool.kt" \
  scripts/qa/R12IdentityVerification.kt \
  > "$COMPILE_LOG" 2>&1

if [ ! -d "$OUT/com" ]; then
  fail "B 编译失败（日志：$COMPILE_LOG）——行为断言没跑成，不能算通过"
  grep -v '^warning:' "$COMPILE_LOG" | head -15 | sed 's/^/        /'
else
  STD=$(find "$KC/lib" "$(dirname "$KOTLINC_BIN")/../lib" -name 'kotlin-stdlib.jar' 2>/dev/null | head -1)
  BODY=$(java -cp "$OUT:$COROUTINES:$STD" -Dagentkit.src="$SRC" -Dagentkit.core.src="$SRC_CORE" R12IdentityVerificationKt 2>&1)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
  # 没有结果行 = harness 没跑完（崩了 / 被 kill）。这一条是 R-19 的 N1 变异逼出来的：
  # 进程崩掉时 grep 全不命中，若默认「没失败就是通过」，整段会静默消失。
  if [ -z "$line" ]; then
    fail "B 段没有结果行（harness 中途退出，rc=$rc）——未跑完不算通过"
    echo "$BODY" | tail -5 | sed 's/^/        /'
  else
    bp=$(echo "${line%% 通过*}" | tr -dc '0-9'); bp=${bp:-0}
    bf=$(echo "${line##* / }" | tr -dc '0-9'); bf=${bf:-0}
    p=$((p + bp)); f=$((f + bf))
  fi
fi

# ---------------------------------------------------------------- [C] 一致性
echo ""
echo "[C] 一致性：真实现与假 provider 不能各说各话"

# 假 provider 的档位若与真实实现不一致，行为断言就变成了「自证」。
# 这一条把两者的对应关系钉死：凡是 KEY_SHIZUKU / KEY_ADB，两侧都必须是 uid 2000 档。
mismatch=$(python3 - "$SRC" <<'PY'
import os, re, sys
src = sys.argv[1]
want = {"KEY_SHIZUKU": "Shizuku", "KEY_ADB": "Shizuku", "KEY_PROOT": "User", "KEY_USER": "User"}
bad = []
for dp, _, fs in os.walk(src):
    for fn in fs:
        if not fn.endswith(".kt"):
            continue
        p = os.path.join(dp, fn)
        t = open(p, encoding="utf-8").read()
        # 每个 `override val identity: TerminalIdentity = TerminalIdentity.X` 后面 200 字符内
        # 找它配的 backendKey（源码里两者总是相邻）。
        for m in re.finditer(r'override val identity: TerminalIdentity = TerminalIdentity\.(\w+)', t):
            seg = t[m.end():m.end() + 260]
            k = re.search(r'KEY_(PROOT|USER|SHIZUKU|ADB)\b', seg)
            if not k:
                continue
            key = "KEY_" + k.group(1)
            if want[key] != m.group(1):
                bad.append("%s: %s 声明 %s（应为 %s）" % (p.replace(src + "/", ""), key, m.group(1), want[key]))
print("\n".join(bad))
PY
)
if [ -z "$mismatch" ]; then
  pass "C1 所有 provider 的身份声明与通道档位一致（uid 2000 的通道不再自称 User）"
else
  fail "C1 有 provider 声明错档："
  echo "$mismatch" | sed 's/^/        /'
fi

# Shizuku 后端本体走基类构造参数（没有 override val），单列一条，避免上面漏掉。
if grep -q 'ProcessBackendBase(identity = TerminalIdentity.Shizuku)' \
     "$SRC/automation/shizuku/ShizukuTerminalBackend.kt"; then
  pass "C2 ShizukuTerminalBackend 本体把身份传给了基类"
else
  fail "C2 ShizukuTerminalBackend 仍以默认（User）身份构造"
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
