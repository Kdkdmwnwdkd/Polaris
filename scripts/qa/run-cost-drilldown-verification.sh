#!/usr/bin/env bash
# 费用下钻验证（W-RT7）：归属对、算得对、未知价格不冒充、本地推理不误算。
#
# 起因：多 Key 池和 Token 统计早就有，缺「按模型算钱」。风险全在钱上——
# 错误的价格比没有价格更误导，所以行为断言专钉三件事：未知模型如实标 null、
# 本地推理计 0 不误撞云端表、排序把花钱大头顶到最上面。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# UiItem 已下沉到 :core（P4-1），所以「气泡携带模型归属」的落点从 VM 改到 UiItem.kt；
# 另加一条：投影层必须把 modelId 写进气泡（否则字段有、永远为空，等于没接）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

PRICING="core/src/main/java/com/beichen/polaris/core/llm/ModelPricing.kt"
STORE="core/src/main/java/com/beichen/polaris/core/ConversationStore.kt"
UI="core/src/main/java/com/beichen/polaris/core/UiItem.kt"
VM="app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt"
PROJ="app/src/main/java/com/beichen/polaris/ui/ConversationProjection.kt"
SCREEN="app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }

echo "== 静态：归属与接线 =="

has "落盘记录带模型归属字段" "$STORE" 'val modelId: String = ""'
has "写入侧透传模型归属" "$STORE" "modelId = modelId"
has "还原侧带回模型归属" "$STORE" "modelId = modelId"
has "气泡携带模型归属" "$UI" 'val modelId: String = ""'
has "投影层给气泡打归属" "$PROJ" "modelId = currentModelId()"
has "本地推理统一记归属" "$VM" '"local-llama" else _config.value.model'
has "重生成也记归属" "$VM" "modelId = currentModelLabel()"
has "视图模型有下钻入口" "$VM" "override fun costDrillDown()"
has "设置页有费用参考分区" "$SCREEN" "费用参考（当前会话）"
has "未知价格如实标注" "$SCREEN" "价格未知，未计入合计"

echo "== 行为：价格层真编真跑（有 kotlinc 才跑，CI 里由 gradle 真编） =="

if command -v kotlinc >/dev/null 2>&1; then
    TMP="$(mktemp -d)"
    cat > "$TMP/check.kt" <<'EOF'
import com.beichen.polaris.core.llm.ModelPricing
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun main() {
// 查价
val ds = ModelPricing.priceFor("deepseek-chat")
ok(ds != null && ds.per1MIn == 0.27 && ds.per1MOut == 1.10, "查价：deepseek-chat 入出分开")
ok(ModelPricing.priceFor("DEEPSEEK-REASONER") != null, "查价：大小写不敏感")
ok(ModelPricing.priceFor("claude-sonnet-4") != null, "查价：家族子串命中新版本号")
ok(ModelPricing.priceFor("完全陌生的模型") == null, "查价：陌生模型如实返回 null")
ok(ModelPricing.priceFor("") == null, "查价：空串返回 null")
ok(ModelPricing.priceFor("local-llama")?.per1MIn == 0.0, "查价：本地推理计 0")
// 算钱：100 万入 + 100 万出 deepseek-chat = 0.27 + 1.10 = 1.37
val r = ModelPricing.drillDown(listOf(ModelPricing.UsageEntry("deepseek-chat", 1_000_000, 1_000_000)))
ok(r.size == 1 && kotlin.math.abs(r[0].costUsd!! - 1.37) < 1e-9, "算钱：单价乘 tokens 入出分计")
// 归属聚合：同模型多轮合并，空归属进「未知模型」
val agg = ModelPricing.drillDown(listOf(
    ModelPricing.UsageEntry("deepseek-chat", 100, 200),
    ModelPricing.UsageEntry("deepseek-chat", 300, 400),
    ModelPricing.UsageEntry("", 500, 500)
))
ok(agg.size == 2, "聚合：同模型合并、空归属单列")
ok(agg.any { it.modelId == "未知模型" && it.costUsd == null }, "聚合：无归属如实标未知")
// 排序：花钱大头在最上，未知价格垫底
val sorted = ModelPricing.drillDown(listOf(
    ModelPricing.UsageEntry("gpt-4o", 100_000, 100_000),            // ≈ 1.25
    ModelPricing.UsageEntry("deepseek-chat", 1_000_000, 1_000_000), // 1.37
    ModelPricing.UsageEntry("小众模型xyz", 10_000_000, 10_000_000)   // 未知
))
ok(sorted[0].modelId == "deepseek-chat" && sorted[1].modelId == "gpt-4o", "排序：贵的在前")
ok(sorted.last().costUsd == null, "排序：未知价格垫底")
// 合计：只算已知价格
val total = ModelPricing.totalUsd(sorted)
ok(total != null && kotlin.math.abs(total - (1.37 + 1.25)) < 1e-9, "合计：未知价格不冒充")
ok(ModelPricing.totalUsd(listOf(ModelPricing.ModelCost("x", 1, 1, null))) == null, "合计：全未知返回 null 不装零")
// 空输入不炸
ok(ModelPricing.drillDown(emptyList()).isEmpty(), "空输入：返回空列表")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
    if kotlinc "$PRICING" "$TMP/check.kt" -d "$TMP/check.jar" -include-runtime >/dev/null 2>&1; then
        OUT=$(java -jar "$TMP/check.jar" 2>/dev/null)
        echo "$OUT"
        echo "$OUT" | grep -q "FAIL=0" && pass "行为断言全过" || fail "行为断言有红"
    else
        fail "行为断言编译失败"
    fi
    rm -rf "$TMP"
else
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
fi

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
