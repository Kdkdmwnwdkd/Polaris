#!/usr/bin/env bash
# 本地推理「后端目录」验证：格式识别 / 后端可用性 / 能力位 / 降级链。
#
# 两个层次：
#   1. 静态断言（总有）：文件在、关键规则写死在哪、有没有被静默改成 true。
#   2. 行为断言（有 kotlinc 才跑）：把这套纯逻辑真编一遍，喂真实文件名看输出。
#      静态 grep 抓不出 `"${"` 这类语法问题，也抓不出「规则反了」——只能真跑。
#
# 为什么这些规则值得单独立脚本：接第二家引擎时，页面上的「GPU 不可用，因为…」和
# 实际的降级顺序全由这张表决定。表错了不会崩溃，只会让人以为自己在用 GPU。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

CATALOG="core/src/main/java/com/beichen/polaris/core/llm/local/LocalBackendCatalog.kt"
PROFILE="core/src/main/java/com/beichen/polaris/core/llm/local/LocalDeviceProfile.kt"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

# 固定串匹配：正则里的 . ( ) [ ] 在路径与 Kotlin 片段里太多，用 -F 省得互相打。
has() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then pass "$name"; else fail "$name（缺：$needle）"; fi
}

hasnt() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then fail "$name（不该出现：$needle）"; else pass "$name"; fi
}

echo "== 静态：结构 =="

if [ -f "$CATALOG" ]; then pass "后端目录存在"; else fail "后端目录缺失：$CATALOG"; fi

# 纯 Kotlin：这个文件必须能脱开 Android 编。一旦有人 import android.* 进来，行为断言就废了。
hasnt "不依赖 Android 框架" "$CATALOG" "import android."
has   "三档后端：CPU" "$CATALOG" "CPU(\"CPU\")"
has   "三档后端：GPU" "$CATALOG" "GPU(\"GPU\")"
has   "三档后端：NPU" "$CATALOG" "NPU(\"NPU\")"
has   "降级顺序 NPU → GPU → CPU" "$CATALOG" "listOf(NPU, GPU, CPU)"
has   "四种模型格式" "$CATALOG" "QAIRT(\"GenieX / QAIRT\")"

echo "== 静态：不让模型跑在不支持它的后端上 =="

# llama.cpp 没有高通 NPU 后端。这条写成「false + 原因」，不能是「true + 空 detail」。
has "GGUF 的 NPU 明写不可用" "$CATALOG" "llama.cpp 没有高通 NPU 后端"
# GPU 是否可用必须由构建能力决定，而不是默认可用——我们现在的 CMake 只编了 CPU 后端。
# W-83 起 GPU 的三道判据搬进了 gpuOption（构建开关 × 设备能不能跑 × 用户意愿），
# 这里只锁第一条：包没编 Vulkan 就一定不给 GPU，与设备强弱无关。
has "GGUF 的 GPU 受构建开关约束" "$CATALOG" "if (!env.llamaGpuEnabled) {"
has "构建开关默认关闭" "$CATALOG" "val llamaGpuEnabled: Boolean = false"
# LiteRT 的 AOT 模型（文件名带 SoC）只能跑同型号 NPU。
has "识别 NPU 专用编译的模型" "$CATALOG" "npuTarget"
has "AOT 模型禁止回落 CPU" "$CATALOG" "只能在 NPU 上运行"
has "芯片型号要能比对" "$CATALOG" "fun socMatches"

echo "== 静态：能力位（三家的同名参数不等价） =="

has "GGUF 可公共前缀复用 KV" "$CATALOG" "reusesCommonPrefix = true"
has "MNN 不能平移上下文" "$CATALOG" "MNN 的 prompt cache 不支持位置平移"
has "LiteRT 的 minP 不存在" "$CATALOG" "LocalModelFormat.LITERT -> LocalEngineFeatures("
has "NPU 版上下文写死在文件里" "$CATALOG" "fixedContext = npuTarget(fileName)?.let { npuKvCapacity(fileName) }"

echo "== 静态：降级不许静默 =="

has "首选项不可用要留话" "$CATALOG" "上次在这个后端上加载或首次生成时进程崩溃，已跳过"
has "全崩过也要尝试链尾" "$CATALOG" "所有后端都曾崩溃，仍尝试"
has "装载计划带说明" "$CATALOG" "data class LoadPlan("

echo "== 行为：真编一遍 =="

KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="kotlinc"
elif [ -x "$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc" ]; then
    KOTLINC="$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc"
elif [ -x /opt/kotlinc/bin/kotlinc ]; then
    KOTLINC="/opt/kotlinc/bin/kotlinc"
fi

if [ -z "$KOTLINC" ]; then
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
else
    WORK="$(mktemp -d)"
    cat > "$WORK/CatalogCheck.kt" <<'KOTLIN'
import com.beichen.polaris.core.llm.local.*

private var ok = 0
private var bad = 0

private fun check(name: String, cond: Boolean, hint: String = "") {
    if (cond) { ok++; println("  PASS  $name") }
    else { bad++; println("  FAIL  $name${if (hint.isEmpty()) "" else "（$hint）"}") }
}

fun main() {
    // ---- 格式识别 ----
    check("gguf 按扩展名", LocalEngineCatalog.detectFormat("qwen3-1.7b-q4.gguf", false) == LocalModelFormat.GGUF)
    check("litertlm 按扩展名", LocalEngineCatalog.detectFormat("gemma-4-E2B.litertlm", false) == LocalModelFormat.LITERT)
    check(
        "MNN 按目录内容",
        LocalEngineCatalog.detectFormat("Qwen2.5-1.5B", true, setOf("config.json", "llm.mnn")) == LocalModelFormat.MNN,
    )
    check(
        "缺 llm.mnn 的目录不算 MNN",
        LocalEngineCatalog.detectFormat("Qwen2.5-1.5B", true, setOf("config.json")) == null,
    )
    check(
        "QAIRT 按 genie_config.json",
        LocalEngineCatalog.detectFormat("qwen3_qairt", true, setOf("genie_config.json")) == LocalModelFormat.QAIRT,
    )
    check("未知扩展名不猜", LocalEngineCatalog.detectFormat("weights.bin", false) == null)

    // ---- NPU 编译版的文件名规则 ----
    val aot = "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"
    check("取到 NPU 目标芯片", LocalEngineCatalog.npuTarget(aot) == "sm8750", LocalEngineCatalog.npuTarget(aot) ?: "null")
    check("取到 KV 容量", LocalEngineCatalog.npuKvCapacity(aot) == 1280)
    check("没写 ekv 时为 null", LocalEngineCatalog.npuKvCapacity("gemma-4-E2B.litertlm") == null)
    check("SM8750P 与 sm8750 是同一颗", LocalEngineCatalog.socMatches("sm8750", "SM8750P"))
    check("SM8650 不等于 sm8750", !LocalEngineCatalog.socMatches("sm8750", "SM8650"))

    // ---- GGUF：只有 CPU 可用（当前构建没编 GPU 后端）----
    val env = LocalEngineEnvironment()
    val gguf = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", env)
    check("GGUF 的 CPU 可用", gguf.first { it.backend == LocalBackend.CPU }.available)
    check("GGUF 的 GPU 不可用", !gguf.first { it.backend == LocalBackend.GPU }.available)
    check(
        "GGUF 的 GPU 不可用必须带原因",
        !gguf.first { it.backend == LocalBackend.GPU }.reason.isNullOrBlank(),
    )
    check("GGUF 的 NPU 不可用", !gguf.first { it.backend == LocalBackend.NPU }.available)

    // 打开构建开关 + 有 OpenCL 后，GPU 才可用。这条防的是「表里写死 true」。
    val ggufGpu = LocalEngineCatalog.options(
        LocalModelFormat.GGUF, "a.gguf",
        LocalEngineEnvironment(llamaGpuEnabled = true, hasVulkan = true),
    )
    check("开了 Vulkan 后 GPU 才可用", ggufGpu.first { it.backend == LocalBackend.GPU }.available)

    // ---- LiteRT：AOT 模型只能 NPU ----
    val litert = LocalEngineCatalog.options(LocalModelFormat.LITERT, aot, LocalEngineEnvironment(hasLitert = true))
    check("AOT 模型的 CPU 不可用", !litert.first { it.backend == LocalBackend.CPU }.available)
    check(
        "AOT 模型在别的芯片上 NPU 也不可用",
        !litert.first { it.backend == LocalBackend.NPU }.available,
    )
    val litertOk = LocalEngineCatalog.options(
        LocalModelFormat.LITERT, aot,
        LocalEngineEnvironment(hasLitert = true, socModel = "sm8750", npuRuntimeInstalled = true),
    )
    check("同型号 + 运行库就绪时 NPU 可用", litertOk.first { it.backend == LocalBackend.NPU }.available)
    val litertGeneric = LocalEngineCatalog.options(
        LocalModelFormat.LITERT, "gemma-4-E2B.litertlm", LocalEngineEnvironment(hasLitert = true),
    )
    check("通用 litertlm 上不了 NPU", !litertGeneric.first { it.backend == LocalBackend.NPU }.available)

    // ---- 没编进去的引擎：三项全灰，且原因一致 ----
    val mnnMissing = LocalEngineCatalog.options(LocalModelFormat.MNN, "x", LocalEngineEnvironment())
    check("没编 MNN 时三项都不可用", mnnMissing.none { it.available })

    // ---- 降级链 ----
    val planNpu = LocalEngineCatalog.planLoad(LocalBackend.NPU, gguf)
    check("GGUF 从 NPU 起步 → 落到 CPU", planNpu.chain == listOf(LocalBackend.CPU), planNpu.chain.toString())
    check("降级留了原因", planNpu.notes.any { "NPU" in it }, planNpu.notes.toString())

    val planCpu = LocalEngineCatalog.planLoad(LocalBackend.CPU, gguf)
    check("CPU 首选直中", LocalEngineCatalog.isDirectHit(planCpu, LocalBackend.CPU))
    check("直中不产生噪声", planCpu.notes.isEmpty(), planCpu.notes.toString())

    val planCrashed = LocalEngineCatalog.planLoad(LocalBackend.CPU, gguf, crashed = setOf(LocalBackend.CPU))
    check("CPU 崩过仍尝试它", planCrashed.chain == listOf(LocalBackend.CPU), planCrashed.chain.toString())
    check("崩过要说明", planCrashed.notes.any { "崩溃" in it }, planCrashed.notes.toString())

    // ---- 能力位 ----
    val fGguf = LocalEngineCatalog.features(LocalModelFormat.GGUF, "a.gguf")
    check("GGUF 能平移上下文", fGguf.contextShift)
    check("GGUF 能复用公共前缀", fGguf.reusesCommonPrefix)
    val fMnn = LocalEngineCatalog.features(LocalModelFormat.MNN, "x")
    check("MNN 不能平移上下文", !fMnn.contextShift)
    check("MNN 不能复用公共前缀", !fMnn.reusesCommonPrefix)
    val fLitert = LocalEngineCatalog.features(LocalModelFormat.LITERT, aot)
    check("LiteRT 没有 minP", !fLitert.minP)
    check("NPU 版上下文固定为 1280", fLitert.fixedContext == 1280, fLitert.fixedContext?.toString() ?: "null")

    // ---- 引擎路由 ----
    check("GGUF → llama.cpp", LocalEngineCatalog.engineFor(LocalModelFormat.GGUF, LocalBackend.CPU) == "llama.cpp")
    check("MNN → MNN-LLM", LocalEngineCatalog.engineFor(LocalModelFormat.MNN, LocalBackend.CPU) == "MNN-LLM")
    check("LITERT → LiteRT-LM", LocalEngineCatalog.engineFor(LocalModelFormat.LITERT, LocalBackend.NPU) == "LiteRT-LM")

    println("SUMMARY $ok $bad")
}
KOTLIN
    # 只编这一个文件 + 检查入口，不拉全项目（不需要 Android SDK）。
    # 不用 -include-runtime：它要求编译器能找到 <kotlin-home>/lib/kotlin-stdlib.jar，
    # 而 embeddable 形态的编译器（CI / 精简安装）常常没有这套目录。显式给 stdlib 更稳。
    STDLIB="${KOTLIN_STDLIB:-}"
    if [ -z "$STDLIB" ]; then
        GUESS="$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"
        [ -f "$GUESS" ] && STDLIB="$GUESS"
    fi
    # 后端目录现在会引用设备画像（「本机是什么 GPU」要写进不可用原因），
    # 所以两个纯逻辑文件一起编。
    "$KOTLINC" -nowarn "$CATALOG" "$PROFILE" "$WORK/CatalogCheck.kt" -d "$WORK/check.jar" 2>"$WORK/err.txt"
    if [ ! -f "$WORK/check.jar" ]; then
        fail "Kotlin 编译失败"
        sed -n '1,15p' "$WORK/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$WORK/check.jar${STDLIB:+:$STDLIB}" CatalogCheckKt 2>&1)"
        echo "$OUT" | grep -v '^SUMMARY'
        # 行为断言自己已经打印了 PASS/FAIL，这里只把计数并进总数，避免重复记一遍。
        BAD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\2/p')"
        GOOD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\1/p')"
        PASS=$((PASS + ${GOOD:-0}))
        FAIL=$((FAIL + ${BAD:-0}))
    fi
    rm -rf "$WORK"
fi

echo
echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
