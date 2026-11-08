#!/usr/bin/env bash
# 长对话全档保留验证（W-RT9）：裁得对、留得住、读得回、导出带全档。
#
# 起因：会话工作集封顶 200 轮，超出丢最旧——丢掉的轮次此前是**真没了**。
# 风险全在「看起来做了」：只要有一处没接上，用户看到的就是「历史还在」（提示说归档了），
# 导出却是空的。所以这里不只断言「写了归档」，还钉死三件容易假成功的事：
#   ① 归档 JSON 必须紧凑（prettyPrint 写 JSONL = 写进去读不回来）；
#   ② 读归档用同一套紧凑实例（写入/读取口径必须一致）；
#   ③ 删除会话要连带清归档（否则孤儿文件永远没人认领）。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# MAX_MESSAGES 已更名为 MAX_TURNS（单位是轮不是条），断言锚文本随之更新。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ARCH="core/src/main/java/com/beichen/polaris/core/ConversationArchive.kt"
BRANCH="core/src/main/java/com/beichen/polaris/core/ConversationBranch.kt"
STORE="core/src/main/java/com/beichen/polaris/core/ConversationStore.kt"
MD="core/src/main/java/com/beichen/polaris/core/ConversationMarkdown.kt"
VM="app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }

echo "== 静态：裁剪与接线 =="

has "裁剪纯函数存在" "$ARCH" "fun evictOldest"
has "分段读纯函数存在" "$ARCH" "fun page"
has "存储层裁剪带出参" "$STORE" "evicted: MutableList<TurnRecord>"
has "超限逐轮转归档" "$STORE" "while (out.size > MAX_TURNS) evicted.add(out.removeAt(0))"
has "追加落归档（锁外）" "$STORE" "appendArchive(convId, evicted)"
has "归档可分段读" "$STORE" "fun archivedTurns("
has "归档有轮数入口" "$STORE" "fun archivedCount("
has "删除联动清归档" "$STORE" "archiveFile(id).delete()"
has "归档也走 id 安全编码" "$STORE" '${safeStem(convId)}.jsonl'

echo "== 静态：归档 JSONL 口径（假成功的重灾区） =="

has "归档专用紧凑 JSON" "$STORE" "private val archiveJson = Json { ignoreUnknownKeys = true }"
has "写入用紧凑实例" "$STORE" "archiveJson.encodeToString"
has "读取用同一紧凑实例" "$STORE" "archiveJson.decodeFromString"
has "一轮一行（非空行才解析）" "$STORE" 'filter { it.isNotEmpty() }'
# 归档是加菜：写失败绝不能拖垮聊天主流程（appendArchive 整体 runCatching 包住）
if grep -A6 'private fun appendArchive' "$STORE" | grep -q 'runCatching'; then
    pass "归档追加整体容错"
else
    fail "归档追加整体容错"
fi

echo "== 静态：导出与提示口径 =="

has "导出带归档参数" "$MD" "archivedTurns: List<TurnRecord> = emptyList()"
has "导出轮号带总数" "$MD" '## 第 ${index + 1}/$totalTurns 轮'
has "导出含归档说明" "$MD" "轮为归档历史，已不在当前对话工作集内"
has "用量含归档轮" "$MD" "(archivedTurns + record.turns)"
has "VM 导出传归档" "$VM" "conversationToMarkdown(rec, conversationStore.archivedTurns(convId))"
has "提示改口径（不再说删除）" "$VM" "已转入归档"

echo "== 行为：裁剪 / 分页 / 导出真编真跑 =="

KOTLINC="${KOTLINC_JAR:-/tmp/ktcheck/kotlin-compiler-embeddable-1.9.25.jar}"
JARS="${KOTLIN_JARS:-/tmp/ktcheck/kotlin-stdlib-1.9.25.jar:/tmp/ktcheck/trove4j-1.0.20200330.jar:/tmp/ktcheck/annotations-13.0.jar:/tmp/ktcheck/kotlin-script-runtime-1.9.25.jar}"
if [ ! -f "$KOTLINC" ] || ! command -v java >/dev/null 2>&1; then
    echo "  SKIP  无编译器（设 KOTLINC_JAR / KOTLIN_JARS 指向 kotlin-compiler-embeddable 与 stdlib 可本地真跑）"
    echo
    echo "TOTAL: PASS=$PASS FAIL=$FAIL"
    [ "$FAIL" -eq 0 ] || exit 1
    exit 0
fi

WORK="$(mktemp -d)"
cp "$ARCH" "$WORK/Archive.kt"
cp "$BRANCH" "$WORK/Branch.kt"
cp "$MD" "$WORK/Markdown.kt"
# 沙箱没有 kotlinx-serialization jar：给 @Serializable 一个最小替身（只当标记用）。
# 模型类形状从真源**逐字段抄**（抄错会立刻在 CI 的 gradle 真编暴露；
# 本地替身只服务纯逻辑断言，不替代真编）。
cat > "$WORK/ser-stub.kt" <<'EOF'
package kotlinx.serialization

@Target(AnnotationTarget.CLASS)
annotation class Serializable
EOF
cat > "$WORK/models-stub.kt" <<'EOF'
package com.beichen.polaris.core

internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"
internal const val ROLE_TOOL = "tool"

data class MsgRecord(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val reasoning: String = "",
    val timeMs: Long = 0,
    val modelId: String = "",
    val pending: Boolean = false
)

data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MsgRecord> = emptyList(),
    val turns: List<TurnRecord> = emptyList(),
    val pinned: Boolean = false,
    val workspaceDir: String = ""
)

data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedPromptTokens: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    companion object {
        fun sum(usages: List<TokenUsage?>): TokenUsage = TokenUsage(
            promptTokens = usages.sumOf { it?.promptTokens ?: 0L },
            completionTokens = usages.sumOf { it?.completionTokens ?: 0L }
        )
    }
}
EOF
cat > "$WORK/check.kt" <<'EOF'
import com.beichen.polaris.core.ConversationArchive
import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.MsgRecord
import com.beichen.polaris.core.TurnRecord
import com.beichen.polaris.core.conversationToMarkdown

fun rec(text: String) = MsgRecord(id = text, role = "user", content = text)
fun turn(label: String) = TurnRecord(user = rec(label))
var pass = 0; var fail = 0
fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) { pass++; println("  PASS  $name") }
    else { fail++; println("  FAIL  $name（实得 $actual，应得 $expected）") }
}
fun labels(turns: List<TurnRecord>) = turns.map { it.user.content }
fun contains(name: String, haystack: String, needle: String) {
    if (haystack.contains(needle)) { pass++; println("  PASS  $name") }
    else { fail++; println("  FAIL  $name（缺：$needle）") }
}

fun main() {
    // —— 裁剪语义 ——
    val five = (1..5).map { turn("T${it}") }
    eq("等于上限不裁", labels(ConversationArchive.evictOldest(five, 5).evicted), emptyList<String>())
    eq("等于上限原样返回", labels(ConversationArchive.evictOldest(five, 5).kept), listOf("T1","T2","T3","T4","T5"))
    val e73 = ConversationArchive.evictOldest(five, 3)
    eq("超限裁掉最旧两轮", labels(e73.evicted), listOf("T1","T2"))
    eq("超限保留最新三轮", labels(e73.kept), listOf("T3","T4","T5"))
    eq("上限 1 只留最新", labels(ConversationArchive.evictOldest(five, 1).kept), listOf("T5"))
    eq("零上限全进归档", ConversationArchive.evictOldest(five, 0).evicted.size, 5)
    eq("空轮不炸", ConversationArchive.evictOldest(emptyList<TurnRecord>(), 3).evicted.size, 0)

    // —— 分页边界 ——
    val arch = (1..10).map { turn("A${it}") }
    eq("正常一页", labels(ConversationArchive.page(arch, 2, 3)), listOf("A3","A4","A5"))
    eq("末页不越界", labels(ConversationArchive.page(arch, 8, 5)), listOf("A9","A10"))
    eq("offset 越界夹空页", labels(ConversationArchive.page(arch, 99, 3)), emptyList<String>())
    eq("负 offset 夹到头", labels(ConversationArchive.page(arch, -5, 2)), listOf("A1","A2"))
    eq("limit 0 空页", labels(ConversationArchive.page(arch, 0, 0)), emptyList<String>())
    eq("负 limit 空页", labels(ConversationArchive.page(arch, 0, -1)), emptyList<String>())
    eq("空归档不炸", labels(ConversationArchive.page(emptyList<TurnRecord>(), 0, 5)), emptyList<String>())

    // —— 导出含归档渲染 ——
    val sel = MsgRecord(id = "a", role = "assistant", content = "回答", promptTokens = 10, completionTokens = 5)
    val md = conversationToMarkdown(
        ConversationRecord(
            id = "c1", title = "导出测试", createdAt = 0, updatedAt = 0,
            turns = listOf(turn("当前第一轮")).map { it.copy(variants = listOf(sel)) }
        ),
        listOf(
            TurnRecord(user = rec("归档轮甲"), variants = listOf(sel.copy(content = "旧答", promptTokens = 100, completionTokens = 50))),
            turn("归档轮乙")
        )
    )
    contains("导出含归档说明", md, "归档历史")
    contains("轮号连续含归档", md, "## 第 1/3 轮")
    contains("当前轮号正确", md, "## 第 3/3 轮")
    contains("归档轮内容在", md, "归档轮甲")
    contains("用量含归档轮", md, "↑ 110 · ↓ 55")

    // 无归档时：编号退回「第 k/N 轮」且形态与旧版一致，不得多出说明行
    //（标题刻意避开「归档」二字，否则断言会被标题自己骗过）
    val md0 = conversationToMarkdown(
        ConversationRecord(
            id = "c2", title = "老对话", createdAt = 0, updatedAt = 0,
            turns = listOf(turn("第一问"), turn("第二问")).map { it.copy(variants = listOf(sel)) }
        )
    )
    contains("无归档轮号仍连续", md0, "## 第 2/2 轮")
    if (!md0.contains("归档")) { pass++; println("  PASS  无归档输出不变") }
    else { fail++; println("  FAIL  无归档输出不该出现归档字样") }

    // 空会话导出不炸（标题为空也不崩）
    val empty = conversationToMarkdown(ConversationRecord(id = "c3", title = "", createdAt = 0, updatedAt = 0))
    if (empty.isNotEmpty()) { pass++; println("  PASS  空会话导出不炸") }
    else { fail++; println("  FAIL  空会话导出不出内容") }

    println("RESULT: PASS=$pass FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
OUTDIR="$WORK/out"; mkdir -p "$OUTDIR"
if java -cp "$KOTLINC:$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -cp "$JARS" -d "$OUTDIR" \
    "$WORK/ser-stub.kt" "$WORK/models-stub.kt" "$WORK/Branch.kt" "$WORK/Archive.kt" \
    "$WORK/Markdown.kt" "$WORK/check.kt" > "$WORK/compile.log" 2>&1; then
    BEHAVIOR=$(java -cp "$OUTDIR:$JARS" CheckKt 2>&1)
    echo "$BEHAVIOR"
    BP=$(echo "$BEHAVIOR" | grep -o 'PASS=[0-9]*' | tail -1 | cut -d= -f2)
    BF=$(echo "$BEHAVIOR" | grep -o 'FAIL=[0-9]*' | tail -1 | cut -d= -f2)
    PASS=$((PASS + ${BP:-0})); FAIL=$((FAIL + ${BF:-0}))
else
    echo "  FAIL  纯层编译不过"
    grep -a 'error:' "$WORK/compile.log" | head -10
    FAIL=$((FAIL + 1))
fi
rm -rf "$WORK"

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
