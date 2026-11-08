#!/usr/bin/env bash
# R-13（工具元数据单一真源）：ToolCatalog 的验证。
#
# 注意脚本名：`run-r13-verification.sh` 已被「会话落盘去抖」占用（早期 R13 批次），
# 本文件是缺陷编号 R-13，另起一名以免覆盖旧 harness。
#
# 三段：
#   [A] 静态：旧的三张表**真的拆掉了**——不是「新表加了」，而是「旧表没了」
#   [B] 静态：56 个工具类 ↔ 目录双向对账；needsApproval 的工具必须有审批归组
#   [C] 行为：真跑 ToolCatalog / ApprovalGrouping / CapabilityRegistry（零第三方依赖）
#
# 为什么 [A] 必须是「旧表没了」：R-13 的病根是同一份事实写四遍。只加一张新表、
# 把旧表留着，等于变成五遍——比修之前更糟。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
SRC="$ROOT/app/src/main/java/com/beichen/polaris"
# 3.5 拆模块：core/skill/terminal 已移到 :core 模块
SRC_CORE="$ROOT/core/src/main/java/com/beichen/polaris"
CATALOG="$SRC_CORE/core/tool/ToolCatalog.kt"

p=0; f=0
pass() { p=$((p+1)); echo "  PASS  $1"; }
fail() { f=$((f+1)); echo "  FAIL  $1" >&2; }

echo "== R-13 工具元数据单一真源 =="

# ---------------------------------------------------------------- [A] 旧表拆净
echo ""
echo "[A] 旧表拆净：能力分级 / 审批归组 / 组成员 三张手写表都不在了"

# A1：CapabilityRegistry 不该再有 `"tool" to Capability.X` 的表项
n=$(grep -cE '"[a-z0-9_]+"[[:space:]]+to[[:space:]]+Capability\.' "$SRC_CORE/skill/SkillSecurity.kt" || true)
n=${n:-0}
if [ "$n" -eq 0 ]; then
  pass "A1 SkillSecurity 不再持有能力分级表（0 条表项）"
else
  fail "A1 SkillSecurity 仍有 $n 条手写分级表项——旧表没拆，等于多一份副本"
  grep -nE '"[a-z0-9_]+"[[:space:]]+to[[:space:]]+Capability\.' "$SRC_CORE/skill/SkillSecurity.kt" | head -3 | sed 's/^/        /'
fi

# A2：ApprovalGrouping 不该再有「工具名 → 组」的 when 分支
n=$(grep -cE '^[[:space:]]*"[a-z0-9_]+"(,[[:space:]]*"[a-z0-9_]+")*[[:space:]]*->' "$SRC_CORE/core/agent/ApprovalGrouping.kt" || true)
n=${n:-0}
if [ "$n" -eq 0 ]; then
  pass "A2 ApprovalGrouping 不再持有工具名 → 归组表"
else
  fail "A2 ApprovalGrouping 仍有 $n 行工具名 → 归组分支"
fi

# A3：CapabilityManifest 里不该再出现任何已登记工具名的字面量。
# 判据细则见 lib/r13_manifest_members.py：只排除注释行，按「名字命中」算而不是按行首排版，
# 因为写回 `listOf("read_file", "write_file")` 与一行一个名字一样是第二份名单。
if python3 "$ROOT/scripts/qa/lib/r13_manifest_members.py" "$SRC" \
     "$SRC_CORE/core/capability/CapabilityManifest.kt"; then
  p=$((p+1))
else
  f=$((f+1))
fi

# A4：三条派生链必须真的指向目录（不是碰巧一致）
if grep -q 'ToolCatalog.membersOf' "$SRC_CORE/core/capability/CapabilityManifest.kt" &&
   grep -q 'ToolCatalog.groupOf' "$SRC_CORE/core/capability/CapabilityManifest.kt" &&
   grep -q 'ToolCatalog.approvalOf' "$SRC_CORE/core/agent/ApprovalGrouping.kt" &&
   grep -q 'ToolCatalog.capabilityOf' "$SRC_CORE/skill/SkillSecurity.kt"; then
  pass "A4 四条派生链（组/成员/审批/能力）都从 ToolCatalog 取值"
else
  fail "A4 有派生链没指向 ToolCatalog——表还在各写各的"
fi

# ---------------------------------------------------------------- [B] 双向对账
echo ""
echo "[B] 工具类 ↔ 目录：两边必须一一对应"

# 3.5 拆模块：工具类分布在 :app（tools 包）与 :core（skill 包等），两边都要扫
names=$(grep -rhoE 'override val name[[:space:]]*=[[:space:]]*"[a-z0-9_]+"' --include='*.kt' "$SRC" "$SRC_CORE" \
  | sed -E 's/.*"([a-z0-9_]+)"/\1/' | sort -u)
nnames=$(printf '%s\n' "$names" | grep -c . || true)
ncat=$(grep -cE '^        CatalogTool\("' "$CATALOG" || true)

if [ "${nnames:-0}" -eq "${ncat:-0}" ]; then
  pass "B1 数量一致：工具类 $nnames 个 / 目录 $ncat 条"
else
  fail "B1 数量不一致：工具类 $nnames 个，目录 $ncat 条（加工具只改了一边）"
fi

missing=""
for t in $names; do
  grep -q "CatalogTool(\"$t\"" "$CATALOG" || missing="$missing $t"
done
if [ -z "$missing" ]; then
  pass "B2 每个工具类都在目录里有记录（漏登记 = 组/能力/审批全查不到）"
else
  fail "B2 目录漏登记：$missing"
fi

extra=""
for t in $(grep -oE 'CatalogTool\("[a-z0-9_]+"' "$CATALOG" | sed 's/.*"\(.*\)"/\1/'); do
  printf '%s\n' "$names" | grep -qx "$t" || extra="$extra $t"
done
if [ -z "$extra" ]; then
  pass "B3 目录里没有幽灵条目（每个名字都真有实现）"
else
  fail "B3 目录里有没实现的工具：$extra（模型照目录调用会拿到 unknown_tool）"
fi

# B4：needsApproval = true 的工具必须在目录里声明审批归组——R-13 里最容易漏的一处：
# 漏了不报错，只在弹窗上表现为「本次会话不再询问 read_file」。
if python3 - "$SRC" "$CATALOG" <<'PY'
import re, sys, os
src, catalog = sys.argv[1], sys.argv[2]
need = []
for dp, _, fs in os.walk(src):
    for fn in fs:
        if not fn.endswith(".kt"):
            continue
        t = open(os.path.join(dp, fn), encoding="utf-8").read()
        ms = list(re.finditer(r'override val name\s*=\s*"([a-z0-9_]+)"', t))
        for i, m in enumerate(ms):
            end = ms[i + 1].start() if i + 1 < len(ms) else len(t)
            if re.search(r'override val needsApproval\s*=\s*true', t[m.end():end]):
                need.append(m.group(1))
cat = open(catalog, encoding="utf-8").read()
have = set(re.findall(r'CatalogTool\("([a-z0-9_]+)"[^)]*Approval\.\w+', cat))
bad = sorted(set(need) - have)
if bad:
    print("  FAIL  B4 要审批但没声明审批归组（弹窗会显示英文工具名）：%s" % " ".join(bad))
    sys.exit(1)
print("  PASS  B4 %d 个需审批工具全部有审批归组" % len(set(need)))
PY
then p=$((p+1)); else f=$((f+1)); fi

# ---------------------------------------------------------------- [C] 行为
echo ""
echo "[C] 行为：真跑 ToolCatalog（不是源码扫描）"

source "$ROOT/scripts/qa/lib/deps.sh"
KC=${KOTLINC_HOME:-/tmp/kotlinc}
KOTLINC_BIN="$KC/bin/kotlinc"
[ -x "$KOTLINC_BIN" ] || KOTLINC_BIN=$(command -v kotlinc || true)

if [ -z "${KOTLINC_BIN:-}" ] || [ ! -x "$KOTLINC_BIN" ]; then
  echo "KS_HARNESS_SKIP $(basename "$0") 找不到 Kotlin 编译器：$KC/bin/kotlinc" >&2
  exit 3
fi

OUT=/tmp/qa-r13-catalog
rm -rf "$OUT"; mkdir -p "$OUT"
"$KOTLINC_BIN" -nowarn -d "$OUT" \
  "$SRC_CORE/core/tool/ToolCatalog.kt" \
  "$SRC_CORE/core/agent/ApprovalGrouping.kt" \
  "$SRC_CORE/skill/SkillSecurity.kt" \
  scripts/qa/R13CatalogVerification.kt 2>&1 | grep -v '^warning:' | head -20

if [ ! -d "$OUT/com" ]; then
  fail "C 编译失败（上面是输出）——行为断言没跑成，不能算通过"
else
  STD=$(find "$KC/lib" "$(dirname "$KOTLINC_BIN")/../lib" -name 'kotlin-stdlib.jar' 2>/dev/null | head -1)
  BODY=$(java -cp "$OUT:$STD" R13CatalogVerificationKt 2>&1)
  rc=$?
  echo "$BODY" | grep -E '^  (PASS|FAIL)' || true
  line=$(echo "$BODY" | grep -oE '[0-9]+ 通过 / [0-9]+ 失败' | tail -1)
  if [ -z "$line" ]; then
    fail "C 段没有结果行（harness 中途退出，rc=$rc）——未跑完不算通过"
  else
    cp_=$(echo "${line%% 通过*}" | tr -dc '0-9'); cp_=${cp_:-0}
    cf=$(echo "${line##* / }" | tr -dc '0-9'); cf=${cf:-0}
    p=$((p + cp_)); f=$((f + cf))
  fi
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
