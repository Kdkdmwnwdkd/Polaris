#!/usr/bin/env bash
# SSH 远程 shell 后端验证（W-RT5）：显式路由、仅密钥登录、纯函数层有没有接对。
#
# 起因：identity=ssh 此前只是枚举占位（usable=false，「首期无后端」）。SSH 的全部风险
# 都在两条纪律里——
#   ① 远端机器**绝不进**本机 / 容器的自动降级链：本机 shell 起不来时悄悄跑到别的机器上，
#      pm / settings 的输出会骗过模型，而回执看起来一切正常（最难查的一类故障）；
#   ② 档案里**没有密码字段**（BatchMode）：密码交互会卡死无 PTY 的常驻会话。
# 所以静态断言锁「路由显式 + 降级链不含 ssh」，行为断言真编真跑纯函数层。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# 本仓的 core 已独立成模块，terminal 后端全部在 :core；身份枚举多了 usable 标注，
# schema 由枚举生成——所以「ssh 已实现」这件事必须同时反映在 usable 上，这里加了断言钉住。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

CORE="core/src/main/java/com/beichen/polaris/core"
PROFILES="$CORE/terminal/backend/SshProfiles.kt"
BACKEND="$CORE/terminal/backend/SshBackend.kt"
IFACE="$CORE/terminal/backend/TerminalBackend.kt"
IDENT="$CORE/terminal/backend/TerminalIdentity.kt"
FACTORY="$CORE/terminal/backend/TerminalBackendFactory.kt"
POOL="$CORE/terminal/TerminalSessionPool.kt"
SHELLTOOL="app/src/main/java/com/beichen/polaris/tools/ShellTool.kt"
STORE="$CORE/ConfigStore.kt"
VM="app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt"
SCREEN="app/src/main/java/com/beichen/polaris/ui/SshChannelSection.kt"
PERM="app/src/main/java/com/beichen/polaris/ui/PermissionsScreen.kt"
CONTRACT="app/src/main/java/com/beichen/polaris/ui/contract/SystemContracts.kt"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi }
hasnt() { if grep -qF -- "$3" "$2" 2>/dev/null; then fail "$1（不该出现：$3）"; else pass "$1"; fi }

echo "== 静态：结构与纪律 =="

if [ -f "$PROFILES" ] && [ -f "$BACKEND" ]; then pass "SSH 纯函数层与后端存在"; else fail "SSH 源文件缺失"; fi
hasnt "纯函数层不依赖 Android" "$PROFILES" "import android."
hasnt "后端层不依赖 Android" "$BACKEND" "import android."
has "仅密钥登录（BatchMode）" "$PROFILES" "BatchMode=yes"
has "首次指纹自动接受、变更仍拒绝" "$PROFILES" "StrictHostKeyChecking=accept-new"
has "档案里没有密码字段" "$PROFILES" "keyPath: String"
has "连接超时预算" "$PROFILES" "ConnectTimeout=10"

echo "== 静态：显式路由（降级链不含 ssh） =="

has "来源接口有按后端键取实例" "$IFACE" "fun obtainByKey(backendKey: String): TerminalBackend?"
has "ssh 身份已标记可用" "$IDENT" "Ssh(uid = null, usable = true)"
has "池 open 支持显式后端键" "$POOL" "backendKey: String? = null"
has "显式键不可用给出 SSH 专属指引" "$POOL" "仅支持密钥登录"
has "工厂按 provider 键构造" "$FACTORY" "providers.firstOrNull { it.backendKey == backendKey }"
# 降级链锁死：ssh 必须出现在 known（= 不被追加）名单里，且不在两套 order 表中。
# 这条断言买的是「以后有人把 ssh 加进 order」时立刻变红。
has "ssh 不进候选链（known 名单含它）" "$FACTORY" "TerminalBackend.KEY_SSH
        )"
# 候选顺序（val order = if … 到 val known 之间）里必须没有 ssh。
# 不用 grep -F 塞多行：grep -F 会把换行当「多个模式」，于是单行 KEY_ADB 就能命中，
# 断言看起来红了却什么都没证明（这里踩过一次）。
if awk '/val order = if/,/val known/' "$FACTORY" | grep -qF "TerminalBackend.KEY_SSH"; then
    fail "候选顺序里没有 ssh（order 段出现了 KEY_SSH）"
else
    pass "候选顺序里没有 ssh"
fi
has "SSH 独立池别名" "$FACTORY" "TerminalBackend.KEY_SSH -> ALIAS_SSH"
has "shell 工具 ssh 走显式路由" "$SHELLTOOL" "declaredIdentity == TerminalIdentity.Ssh"
has "shell 工具路由用 KEY_SSH" "$SHELLTOOL" "backendKey = TerminalBackend.KEY_SSH"
has "回执按通道如实报远端身份" "$SHELLTOOL" "it.backendKey == TerminalBackend.KEY_SSH"

echo "== 静态：配置与界面接线 =="

has "配置层有 SSH 四项" "$STORE" "fun setSshProfile(host: String, port: String, user: String, keyPath: String)"
has "视图模型有保存入口" "$VM" "override fun saveSshProfile("
has "保存后清可用性缓存" "$VM" "terminalBackendFactory.clearCache()"
has "装配处注入 SSH 提供者" "$VM" "SshBackendProvider("
has "契约含 SSH 档案读写" "$CONTRACT" "fun saveSshProfile(host: String, port: String, user: String, keyPath: String): String?"
has "权限中心有 SSH 分区" "$PERM" "SshChannelSection(vm)"
has "分区组件存在" "$SCREEN" "internal fun SshChannelSection"

echo "== 行为：纯函数层真编真跑（有编译器才跑，CI 里由 gradle 真编） =="

KOTLINC="${KOTLINC_JAR:-/tmp/ktcheck/kotlin-compiler-embeddable-1.9.25.jar}"
JARS="${KOTLIN_JARS:-/tmp/ktcheck/kotlin-stdlib-1.9.25.jar:/tmp/ktcheck/trove4j-1.0.20200330.jar:/tmp/ktcheck/annotations-13.0.jar:/tmp/ktcheck/kotlin-script-runtime-1.9.25.jar:/tmp/ktcheck/coroutines-core.jar}"
if [ ! -f "$KOTLINC" ] || ! command -v java >/dev/null 2>&1; then
    echo "  SKIP  无编译器（设 KOTLINC_JAR / KOTLIN_JARS 指向 kotlin-compiler-embeddable、stdlib、coroutines 可本地真跑）"
    echo
    echo "TOTAL: PASS=$PASS FAIL=$FAIL"
    [ "$FAIL" -eq 0 ] || exit 1
    exit 0
fi

TMP="$(mktemp -d)"
# 行为断言专用最小桩：AgentLogger 与 AppScope 的真身分别拖整条日志设施与
# android.util.Log，这里只要它们被引用到的几个符号。
cat > "$TMP/stubs.kt" <<'EOF'
package com.beichen.polaris.core

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

object AgentLogger {
    fun i(tag: String, msg: String) {}
    fun w(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
}

object AppScope {
    val io: CoroutineContext =
        SupervisorJob() + Dispatchers.IO + CoroutineName("app-scope") as CoroutineContext
}
EOF
cat > "$TMP/check.kt" <<'EOF'
import com.beichen.polaris.core.terminal.backend.SshBinaryLocator
import com.beichen.polaris.core.terminal.backend.SshBackendProvider
import com.beichen.polaris.core.terminal.backend.SshProfile
import com.beichen.polaris.core.terminal.backend.SshProfileResult
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.TerminalBackendProvider
import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import com.beichen.polaris.core.terminal.backend.buildSshArgs
import com.beichen.polaris.core.terminal.backend.normalizeSshProfile
import java.io.File

var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }

fun main() {
    // —— 档案校验 ——
    val good = normalizeSshProfile(" 192.168.1.10 ", " 2222 ", "root", " /tmp/k ")
    ok(good is SshProfileResult.Ok && good.profile.host == "192.168.1.10" &&
        good.profile.port == 2222 && good.profile.keyPath == "/tmp/k", "校验：trim 后合法")
    ok((normalizeSshProfile("", "22", "root", "") as? SshProfileResult.Invalid)
        ?.reason?.contains("主机") == true, "校验：空主机拒绝")
    ok(normalizeSshProfile("a b", "22", "root", "") is SshProfileResult.Invalid, "校验：主机含空格拒绝")
    ok(normalizeSshProfile("h", "22", "", "") is SshProfileResult.Invalid, "校验：空用户名拒绝")
    ok(normalizeSshProfile("h", "0", "root", "") is SshProfileResult.Invalid, "校验：端口 0 拒绝")
    ok(normalizeSshProfile("h", "65536", "root", "") is SshProfileResult.Invalid, "校验：端口越界拒绝")
    ok(normalizeSshProfile("h", "abc", "root", "") is SshProfileResult.Invalid, "校验：端口非数字拒绝")
    ok((normalizeSshProfile("h", "", "root", "") as SshProfileResult.Ok).profile.port == 22, "校验：空端口按 22")

    // —— argv 拼装 ——
    val args = buildSshArgs(SshProfile("h", 2200, "u", "/tmp/k"), "/bin/ssh")
    ok(args[0] == "/bin/ssh", "拼装：ssh 路径在首位")
    ok(args.windowed(2).contains(listOf("-o", "BatchMode=yes")), "拼装：仅密钥登录")
    ok(args.windowed(2).contains(listOf("-i", "/tmp/k")), "拼装：私钥路径")
    val noKey = buildSshArgs(SshProfile("h", 22, "u", ""), "ssh")
    ok(!noKey.contains("-i"), "拼装：无密钥时不带 -i")
    ok(noKey.windowed(2).contains(listOf("-p", "22")), "拼装：端口进 -p")
    ok(noKey.last() == "u@h", "拼装：目标 user@host")

    // —— 别名分池 ——
    ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_SSH) == "ssh", "别名：SSH 独立占池")
    ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_PROOT) == "proot", "别名：proot 照旧")
    ok(TerminalBackendFactory.defaultAliasFor(TerminalBackend.KEY_USER) == "default", "别名：本机照旧")

    // —— 定位器 ——
    val tmp = File.createTempFile("sshbin", "").let { f ->
        val p = File(f.parentFile, "sshbin-${f.name}")
        p.writeText("#!/bin/sh\n"); p.setExecutable(true); f.delete(); p
    }
    ok(SshBinaryLocator.locate(listOf("/nonexistent/ssh", tmp.absolutePath)) == tmp.absolutePath,
        "定位：跳过缺失取首个可执行")
    ok(SshBinaryLocator.locate(listOf("/nonexistent/ssh")) == null, "定位：全缺失返回 null")
    ok(SshBinaryLocator.defaultCandidates("/rf/usr/bin").contains("/rf/usr/bin/ssh"), "定位：容器候选进清单")
    ok(!SshBinaryLocator.defaultCandidates(null).any { it.startsWith("null") }, "定位：无容器不拼出 null 路径")
    tmp.delete()

    // —— 提供者：探测 = 有 ssh 命令 且 有档案 ——
    var profile: SshProfile? = null
    val provider = SshBackendProvider(sshPathProvider = { "/bin/ssh" }, profileProvider = { profile })
    ok(!provider.probe(), "提供者：未配档案不可用")
    profile = SshProfile("h", 22, "u", "")
    ok(provider.probe(), "提供者：档案就绪即可用")
    ok(provider.create()?.backendKey == TerminalBackend.KEY_SSH, "提供者：构造出的后端键为 ssh")
    profile = null
    ok(provider.create() == null, "提供者：档案撤掉后构造返回 null")
    val noBin = SshBackendProvider(sshPathProvider = { null }, profileProvider = { SshProfile("h", 22, "u", "") })
    ok(!noBin.probe(), "提供者：没有 ssh 命令不可用")

    // —— 工厂显式路由：不进降级链，但 obtainByKey 取得到 ——
    val fake = object : TerminalBackendProvider {
        override val identity = TerminalIdentity.User
        override val backendKey = TerminalBackend.KEY_SSH
        override fun probe() = true
        override fun create() = provider.create()
    }
    val userProvider = object : TerminalBackendProvider {
        override val identity = TerminalIdentity.User
        override val backendKey = TerminalBackend.KEY_USER
        override fun probe() = true
        override fun create(): TerminalBackend? = null
    }
    val factory = TerminalBackendFactory(listOf(fake, userProvider))
    profile = SshProfile("h", 22, "u", "")
    ok(factory.obtainByKey(TerminalBackend.KEY_SSH)?.backendKey == TerminalBackend.KEY_SSH,
        "工厂：显式键可取到 SSH")
    ok(factory.obtainByKey("no-such-key") == null, "工厂：未知键返回 null")
    ok(factory.providersFor(TerminalIdentity.User, true).none { it.backendKey == TerminalBackend.KEY_SSH },
        "工厂：SSH 不在降级链（preferProot=true）")
    ok(factory.providersFor(TerminalIdentity.User, false).none { it.backendKey == TerminalBackend.KEY_SSH },
        "工厂：SSH 不在降级链（preferProot=false）")
    ok(factory.providersFor(TerminalIdentity.Ssh, true).isEmpty(), "工厂：identity=ssh 走不到降级链")

    println("RESULT: PASS=$pass FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
OUTDIR="$TMP/out"; mkdir -p "$OUTDIR"
SRCS="$TMP/stubs.kt $PROFILES $BACKEND $IFACE $IDENT $FACTORY $CORE/terminal/backend/ProcessBackendBase.kt $TMP/check.kt"
if java -cp "$KOTLINC:$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -nowarn -cp "$JARS" -d "$OUTDIR" $SRCS > "$TMP/compile.log" 2>&1; then
    OUT=$(java -cp "$OUTDIR:$JARS" CheckKt 2>&1)
    echo "$OUT"
    BP=$(echo "$OUT" | grep -o 'PASS=[0-9]*' | tail -1 | cut -d= -f2)
    BF=$(echo "$OUT" | grep -o 'FAIL=[0-9]*' | tail -1 | cut -d= -f2)
    PASS=$((PASS + ${BP:-0})); FAIL=$((FAIL + ${BF:-0}))
else
    echo "  FAIL  纯函数层编译不过"
    grep -a 'error:' "$TMP/compile.log" | head -10
    FAIL=$((FAIL + 1))
fi
rm -rf "$TMP"

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
