#!/usr/bin/env bash
# 源码级不变量验证（纯静态，不需要 kotlinc、不需要 AGP 产物）
#
# 为什么单独一个 runner：
# 下面这些不变量一旦被改回去，**照样编译、照样跑**，只是行为静默退化——
# 死会话被当成可复用（白等 20 分钟）、审批通道悄悄打开、守卫链被绕开。
# 它们没有可断言的返回值，只能看结构；所以这个脚本必须在任何环境都能跑，
# 不许因为「缺编译器」而 SKIP——SKIP 等于没有。
#
# 判据约定：每条都要能**被反向验证**（把实现改回去，这条必须红）。
# 加断言时请先做一次反向测试，否则很容易写出恒真的假绿。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

ADB=app/src/main/java/com/beichen/polaris/automation/adb/AdbTerminalBackend.kt
PTY=core/src/main/java/com/beichen/polaris/terminal/TerminalSession.kt
CATALOG=core/src/main/java/com/beichen/polaris/core/tool/ToolCatalog.kt
SCOPE=core/src/main/java/com/beichen/polaris/core/agent/ApprovalScope.kt
LOADSKILL=app/src/main/java/com/beichen/polaris/tools/LoadSkillTool.kt
FETCHER=app/src/main/java/com/beichen/polaris/tools/SkillZipFetcher.kt
VM=app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt

p=0; f=0
# 注意：p / f 是计数器。断言里要取行号请用别的变量名（pin / dwn / g / e…）——
# 第一版在 I7c 里写成 `p=$(grep -n …)`，汇总行就变成了「84 通过」而实际是 31 条。
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }

# 取某个属性 getter 的那一行（属性声明行之后、第一处 get() 所在行）
getter_of() {
  awk -v key="$2" '$0 ~ key { f = 1 } f && /get\(\)/ { print; exit }' "$1"
}

echo "[I1] 会话存活必须与退出信号挂钩"
# 池的复用判据是 entry.alive → backend.isAlive。若 isAlive 只看 stream/handle，
# shell 已经退出但字段还没清，这条会话就会被判成"可复用"，下一条命令写进死管道，
# 一路等到工具预算（20 分钟）烧完才报错。
if getter_of "$ADB" 'isAlive: Boolean' | grep -q 'exited'; then
  pass "I1a ADB 后端的 isAlive 计入 exited"
else
  fail "I1a ADB 的 isAlive 没看 exited —— 死 shell 会被复用，单条命令白等 20 分钟"
fi

if grep -qE '^\s*exited = true' "$ADB"; then
  pass "I1b ADB 读循环退出时置 exited（否则 isAlive 永远是 true）"
else
  fail "I1b ADB 读循环没置 exited —— isAlive 没有可观测的死亡信号"
fi

if getter_of "$PTY" 'isAlive: Boolean' | grep -q 'exited'; then
  pass "I1c pty 会话的 isAlive 计入 exited"
else
  fail "I1c pty 的 isAlive 没看 exited —— shell 自己退出后仍被判活"
fi

if grep -qE '^\s*exited = true' "$PTY"; then
  pass "I1d pty 读循环退出时置 exited"
else
  fail "I1d pty 读循环没置 exited"
fi

echo ""
echo "[I2] 技能包安装必须过一次人（D-1）"
# 修前这条通道零弹窗：目录标 SAFE、工具类没声明 needsApproval，模型可以从任意 https
# 地址装一个包进来。技能包的指令正文会常驻系统提示（buildSkillSystemPrompt），
# 所以这是提示注入唯一能长期存活的落地点——不是「多一个工具」，是「多一个入口」。
if grep -qE 'CatalogTool\("load_skill",\s*"skill",\s*Capability\.DANGEROUS,\s*Approval\.SKILL_INSTALL\)' "$CATALOG"; then
  pass "I2a 目录把 load_skill 归到 SKILL_INSTALL 且升到 DANGEROUS"
else
  fail "I2a load_skill 在目录里没有审批归组/仍是 SAFE —— 装包通道零弹窗，且技能可自举安装"
fi

if grep -q 'override val needsApproval = true' "$LOADSKILL"; then
  pass "I2b LoadSkillTool 声明了 needsApproval"
else
  fail "I2b LoadSkillTool 没声明 needsApproval —— 目录里的归组是死元数据，永远不弹窗"
fi

if grep -q 'const val ACTION_LIST = "list"' "$LOADSKILL" &&
   grep -qE 'return action != ACTION_LIST' "$LOADSKILL"; then
  pass "I2c action=list 免审批（枚举已装技能不写任何东西）"
else
  fail "I2c needsApprovalFor 没给 list 开口子（或常量漂移）—— 只读枚举也会逐次弹窗"
fi

if grep -q 'const val LOAD_SKILL_TOOL = "load_skill"' "$SCOPE" &&
   grep -q 'toolName == LOAD_SKILL_TOOL' "$SCOPE"; then
  pass "I2d 审批作用域认识 load_skill（否则弹窗只能按裸工具名分组）"
else
  fail "I2d ApprovalScope 没有 load_skill 分支 —— 内联包/卸载会落到「可记忆」的默认档"
fi

# 内联 base64 没有来源可归因，记住等于「以后任意内联包都放行」。
if grep -A3 'ACTION_INSTALL_BASE64 ->' "$SCOPE" | grep -q 'memorizable = false'; then
  pass "I2e install_base64 不可记忆"
else
  fail "I2e install_base64 落进了可记忆档 —— 一次同意 = 以后任意内联包免问"
fi

echo ""
echo "[I3] 技能包下载必须逐跳校验（D-1 配套）"
# 共享的 LlmHttp.short 默认自动跟随重定向，于是 validateUrl 只校验了第一跳：
# 一个 302 到 http://内网/evil.zip 就能同时绕过「https」与「公网」两条前提。
if grep -q 'followRedirects(false)' "$FETCHER"; then
  pass "I3a 下载器关掉了自动重定向"
else
  fail "I3a 下载器还在自动跟随重定向 —— 第二跳起完全不受校验"
fi

if grep -q 'LoadSkillLogic.validateUrl(next)' "$FETCHER"; then
  pass "I3b 每一跳都重新过 validateUrl"
else
  fail "I3b 重定向目标没过 validateUrl —— 可被跳到内网或明文 http"
fi

# 委托必须真的搬走了：ViewModel 里若还留着 newCall，说明两条下载路径并存，
# 修好的是没人走的那条。
if ! sed -n '/private suspend fun downloadSkillZip/,/^$/p' "$VM" | grep -q 'newCall'; then
  pass "I3c ViewModel 只剩委托（没有并存的第二条下载路径）"
else
  fail "I3c ChatViewModel 里还在自己 newCall 下 zip —— 修复没生效"
fi

echo ""
echo "[I4] 守卫链必须真的接上（D-3）"
# 修前的状态很特别：接缝③的语义（单调裁决、崩溃=拒绝、3000ms 超时）在 ToolGuard.kt 里
# 写得清清楚楚，harness 也验过——但生产上一次都没跑过，因为三处 RealAgentLoop 全走
# 默认 AgentSeams.NONE，productionSeams 零调用方。形状对了，内容没有。
GUARD=core/src/main/java/com/beichen/polaris/core/agent/PlanModeGuard.kt
SUB=core/src/main/java/com/beichen/polaris/core/agent/SubAgentRunner.kt
SKILLEX=core/src/main/java/com/beichen/polaris/skill/SkillExecutor.kt
PET=app/src/main/java/com/beichen/polaris/pet/PetAgentSession.kt

# 注意 `class PlanModeGuard\(`：不能只写 `class PlanModeGuard`——那是子串匹配，
# 把类改名成 PlanModeGuardX 也照样命中（反向测试就是这么漏过去的）。
if grep -qE 'class PlanModeGuard\(' "$GUARD" && grep -qE '\)\s*:\s*ToolGuard' "$GUARD"; then
  pass "I4a 存在一个真的 ToolGuard 实现（PlanModeGuard）"
else
  fail "I4a 没有任何 ToolGuard 实现 —— 守卫链是死的，装不装都一样"
fi

# 主对话 + 技能桥共用同一个实例，所以至少两处传参。
n=$(grep -c 'seams = agentSeams' "$VM")
if [ "$n" -ge 2 ]; then
  pass "I4b ChatViewModel 把 seams 传给了 loop 与 skillExecutor（$n 处）"
else
  fail "I4b ChatViewModel 只传了 $n 处 seams —— 技能桥或主 loop 有一条没装守卫"
fi

if grep -q 'seams = seams' "$SUB"; then
  pass "I4c 子代理的 loop 也装了守卫（不是绕过守卫的后门）"
else
  fail "I4c 子代理 loop 没装 seams —— 计划模式下派个子代理就能写"
fi

if grep -q 'PlanModeGuard(' "$PET"; then
  pass "I4d 桌宠会话也装了守卫"
else
  fail "I4d 桌宠 loop 没装守卫（当前工具全只读，但这是事实不是契约）"
fi

# 技能桥：守卫必须在 executor 之前。反了就等于「先执行、后检查」，
# 而且 DISP 已经写下去了，崩溃恢复会把被拒的调用判成「结局未知」。
g=$(grep -n 'seams.checkTool(' "$SKILLEX" | head -1 | cut -d: -f1)
e=$(grep -n 'executor.execute(call, descriptor)' "$SKILLEX" | head -1 | cut -d: -f1)
if [ -n "$g" ] && [ -n "$e" ] && [ "$g" -lt "$e" ]; then
  pass "I4e 技能桥的守卫在 executor.execute 之前"
else
  fail "I4e 技能桥守卫位置不对（guard=$g, exec=$e）—— 先执行后检查，检查等于没做"
fi

# 属性初始化顺序：registry 的 apply 块里要构造 SubAgentRunner，所以 agentSeams
# 必须声明在 registry 之前；反过来会在冷启动时读到 null 直接 NPE。
a=$(grep -n 'private val agentSeams' "$VM" | head -1 | cut -d: -f1)
r=$(grep -n 'private val registry = ToolRegistry(' "$VM" | head -1 | cut -d: -f1)
if [ -n "$a" ] && [ -n "$r" ] && [ "$a" -lt "$r" ]; then
  pass "I4f agentSeams 声明在 registry 之前（否则子代理构造时读到 null）"
else
  fail "I4f agentSeams 声明在 registry 之后（$a vs $r）—— registry 初始化期会读到 null"
fi

# 关着的时候必须弃权。写成 `if (planMode())`（少了取反）的后果是所有工具在**非**计划
# 模式下全被拒——那是最容易被误判成"模型坏了"的一种坏法，且编译期完全看不出来。
if grep -qE 'if \(!planMode\(\)\) return GuardDecision\.Abstain' "$GUARD"; then
  pass "I4g 计划模式关闭时守卫弃权（不是恒拒）"
else
  fail "I4g 守卫没在关闭时弃权 —— 非计划模式下所有工具都会被拒"
fi

echo ""
echo "[I5] 审批授权必须落到「发起它的那个会话」（D-4）"
# 审计 F3 已经把白名单按会话分桶了，但取桶的时间点是错的：requestApproval 在**发起时**
# 读一次，approveAlways 在**用户点按钮时**又读一次 `_activeConversationId`。这中间隔着
# 一次用户交互——用户切了会话，授权就写进了另一个会话的桶里。
# 后果不对称：A 会话下次还问（烦），B 会话凭空多了一条免弹窗授权（危险）。
CONTRACTS=app/src/main/java/com/beichen/polaris/ui/ChatUiContracts.kt
STORE=app/src/main/java/com/beichen/polaris/ui/ApprovalWhitelistStore.kt

if grep -qE '^\s*val convId: String' "$CONTRACTS"; then
  pass "I5a ApprovalRequest 带 convId（发起时的会话）"
else
  fail "I5a ApprovalRequest 没有 convId —— 审批跨交互期间无法还原归属会话"
fi

if grep -q 'ApprovalWhitelistStore.of(pending.convId)' "$VM"; then
  pass "I5b approveAlways 写进 pending.convId 的桶"
else
  fail "I5b approveAlways 没按 pending.convId 写桶 —— 授权会落到用户此刻正在看的会话"
fi

# 反向：approveAlways 里若还残留 `of(_activeConversationId`，说明修复没改到那一处。
if sed -n '/override fun approveAlways/,/^    }/p' "$VM" | grep -q '_activeConversationId'; then
  fail "I5c approveAlways 仍读当前活跃会话 —— 与 I5b 互斥的旧写法还在"
else
  pass "I5c approveAlways 不读当前活跃会话"
fi

if grep -q 'convId = convId' "$VM"; then
  pass "I5d 发起审批时把会话 id 冻结进请求"
else
  fail "I5d 发起审批时没冻结会话 id —— convId 只能是空串，全落同一个桶"
fi

if grep -q '按对话隔离' "$STORE"; then
  pass "I5e 白名单存储仍按会话分桶（没被改成进程级）"
else
  fail "I5e 白名单存储不再按会话隔离 —— 回到审计 F3 之前的状态"
fi

echo ""
echo "[I6] 待发附件必须跟着会话走（D-5）"
# 修前是两个进程级 StateFlow：在 A 会话挑的图，切到 B 会话还在输入框里，
# 发出去就成了 B 的附件。泄漏的是**内容**（相册照片、剪贴板导出），比审批白名单更直接。
BUCKETS=app/src/main/java/com/beichen/polaris/ui/ConversationBuckets.kt

# 同 I4a：必须用 `[<(]` 收尾，否则改个名字成 ConversationBucketsX 也照样命中。
if [ -f "$BUCKETS" ] && grep -qE 'class ConversationBuckets[<(]' "$BUCKETS"; then
  pass "I6a 存在按会话分桶的附件存储"
else
  fail "I6a 没有 ConversationBuckets —— 附件仍是进程级单例"
fi

if ! grep -q '_pendingImages\|_pendingFiles' "$VM"; then
  pass "I6b ChatViewModel 里没有进程级 pending 附件字段了"
else
  fail "I6b 仍有 _pendingImages/_pendingFiles —— 与分桶存储并存，读写的可能不是同一份"
fi

if grep -q 'pendingImageBuckets.activate(id)' "$VM" && grep -q 'pendingFileBuckets.activate(id)' "$VM"; then
  pass "I6c 切会话时同步切换附件视图"
else
  fail "I6c loadConversation 没切换附件视图 —— UI 上会挂着上个会话的图"
fi

# take 必须是原子的：`of(...)` 再 `set(empty)` 是两步，两条发送路径交错时会一边发出重复附件。
if grep -q 'pendingImageBuckets.take(owner)' "$VM" && grep -q 'fun take(' "$BUCKETS"; then
  pass "I6d 发送时原子取走附件（take）"
else
  fail "I6d 发送路径不是原子 take —— 并发发送可能重复带附件或带空"
fi

echo ""
echo "[I7] 模型下载的信任根必须在 APK 里（D-7）"
# 修前的校验链自洽但不独立：清单说 sha256=X，下载器核对结果是不是 X。清单与 zip 在
# 同一个 Release，能换掉其中一个的人也能换掉另一个。内嵌哈希才是独立的那一端。
SPEC=core/src/main/java/com/beichen/polaris/model/ModelSpec.kt
DL=core/src/main/java/com/beichen/polaris/model/ModelDownloader.kt

if grep -qE '^\s*val sha256: String\? = null' "$SPEC"; then
  pass "I7a ModelSpec 有内嵌哈希字段"
else
  fail "I7a ModelSpec 没有 sha256 —— 信任根只能落在服务端清单上"
fi

if grep -q 'pinned.equals(entry.sha256' "$DL"; then
  pass "I7b 下载器把内嵌哈希与清单交叉校验"
else
  fail "I7b 下载器没比对内嵌哈希 —— 换了资产也照样算校验通过"
fi

# 位置很要紧：这段必须在 downloadTo(...) 之前。写在后面就是「先下载再检查」，
# 既白费流量，也会把「资产被换过」伪装成网络错误。
#
# 变量名不能用 p / d / f：p 与 f 是本脚本的通过/失败计数器，覆盖它们会让最后的
# 汇总数字变成行号（第一版就踩了：显示「84 通过」而实际是 31）。
pin=$(grep -n 'pinned.equals(entry.sha256' "$DL" | head -1 | cut -d: -f1)
dwn=$(grep -n 'downloadTo(entry, zip, onProgress)' "$DL" | head -1 | cut -d: -f1)
if [ -n "$pin" ] && [ -n "$dwn" ] && [ "$pin" -lt "$dwn" ]; then
  pass "I7c 交叉校验在下载之前（不一致就不浪费流量）"
else
  fail "I7c 交叉校验排在下载之后（$pin vs $dwn）—— 先下 196MB 再说不合格"
fi

echo ""
echo "$p 通过 / $f 失败"
[ "$f" -eq 0 ] || exit 1
