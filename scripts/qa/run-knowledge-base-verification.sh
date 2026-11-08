#!/usr/bin/env bash
# 本地知识库检索验证（W-RT6）：分块不碎不断、中英查询都能中、无关文档不抢排名。
#
# 起因：知识库是纯增量能力，最大的风险全在检索质量上——块切太碎丢上下文、
# 中文没按二元组切就查不中、BM25 没被文档长度归一就会长文档霸榜。所以行为
# 断言直接钉这三件事；工具接线（目录、注册、参数、权限表）走静态断言。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# 行为段改成本仓的 kotlinc 统一跑法；另加两条断言——ToolCatalog 必须登记
# （fail-closed，漏登记 = 工具整体不可用）、以及「不抢 memory 组描述不给新组」。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

INDEX="core/src/main/java/com/beichen/polaris/core/knowledge/KnowledgeIndex.kt"
TOOL="app/src/main/java/com/beichen/polaris/tools/KnowledgeSearchTool.kt"
VM="app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt"
CATALOG="core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }
hasnt() { if grep -qF -- "$3" "$2" 2>/dev/null; then fail "$1（不该出现：$3）"; else pass "$1"; fi; }

echo "== 静态：结构与接线 =="

has "纯函数层存在" "$INDEX" "object KnowledgeIndex"
hasnt "纯函数层无 Android 导入" "$INDEX" "import android."
has "工具注册进会话" "$VM" "register(KnowledgeSearchTool("
has "知识库目录固定 files/knowledge" "$VM" "java.io.File(app.filesDir, \"knowledge\")"
has "工具声明只读（可并行）" "$TOOL" "override val readOnly = true"
has "空库给出可执行指引" "$TOOL" "no_docs"
has "与对话检索划清分工" "$TOOL" "conversation_search"
has "限流：篇数与单篇字符都设上限" "$TOOL" "const val MAX_FILES = 50"

# fail-closed：能力表里没登记的工具会被当成 DANGEROUS、整体不可用。
# 旧分支 CI 第 4 轮就是栽在这（报告里自称「真漏网」），所以这条单独立断言。
has "已登记进能力表" "$CATALOG" 'CatalogTool("knowledge_search", "memory", Capability.SAFE)'
# 归 memory 组而不是新开一组：两者在用户眼里都是「让 Agent 去查东西」。
hasnt "没有为新工具新开分组" "$CATALOG" 'CatalogGroup("knowledge"'

echo "== 行为：纯层真编真跑（有 kotlinc 才跑，CI 里由 gradle 真编） =="

if command -v kotlinc >/dev/null 2>&1; then
    TMP="$(mktemp -d)"
    cat > "$TMP/check.kt" <<'EOF'
import com.beichen.polaris.core.knowledge.KnowledgeIndex
var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }
fun main() {
// 分块：小段合并、超大段硬切
val short = KnowledgeIndex.chunkDocument("a", "第一段。\n\n第二段。")
ok(short.size == 1 && short[0].text.contains("第一段"), "分块：小段合并成一块")
val long = KnowledgeIndex.chunkDocument("b", (1..40).joinToString("") { "第${it}句话讲的是知识库的检索质量。"})
val longChunks = KnowledgeIndex.chunkDocument("b", "超长段。".repeat(400))
ok(longChunks.all { it.text.length <= 900 }, "分块：超长段被硬切不超限")
ok(longChunks.size > 1, "分块：超长段切成多块")
ok(long.size == 1, "分块：正常长度不误切")
// 分词：中英对齐
val en = KnowledgeIndex.tokenize("Default Router IP")
ok(en.contains("router"), "分词：英文按词小写")
val zh = KnowledgeIndex.tokenize("检索质量")
ok(zh.contains("检索") && zh.contains("索质"), "分词：中文切二元组")
ok(KnowledgeIndex.tokenize("好").contains("好"), "分词：孤字保留一元")
// BM25：相关在前、长文档不霸榜
val docs = listOf(
    "手册" to "本手册介绍路由器配置。Default router IP 192.168.1.1，出厂即用。保修条款见附录。",
    "菜谱" to "红烧肉做法：五花肉焯水，加糖和酱油，小火炖四十分钟。",
    "笔记本" to "今天买了菜，很长的日常流水账。".repeat(30)
)
val built = KnowledgeIndex.build(docs)
val r1 = built.search("路由器 怎么 配置")
ok(r1.isNotEmpty() && r1[0].chunk.doc == "手册", "检索：相关文档排第一")
val r2 = built.search("保修")
ok(r2.isNotEmpty() && r2[0].chunk.doc == "手册", "检索：中文双字词命中")
val r3 = built.search("红烧肉")
ok(r3.isNotEmpty() && r3[0].chunk.doc == "菜谱", "检索：跨文档各回各家")
val r4 = built.search("router")
ok(r4.isNotEmpty(), "检索：英文词命中中英混排")
val r5 = built.search("完全不存在词")
ok(r5.isEmpty(), "检索：无关词不硬凑结果")
val top = built.search("的", 8)
ok(top.size <= 8, "检索：返回数不超上限")
// 块序号必须在同一文档内从 0 递增（工具按 order 展示「第 N 段」，错位会指错地方）
val ordered = KnowledgeIndex.chunkDocument("c", "段落一。\n\n段落二。\n\n段落三。")
ok(ordered.mapIndexed { i, c -> c.order == i }.all { it }, "分块：块序号按文档内顺序")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
    if kotlinc "$INDEX" "$TMP/check.kt" -d "$TMP/check.jar" -include-runtime >/dev/null 2>&1; then
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
