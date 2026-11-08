#!/usr/bin/env bash
# Vulkan（GPU）后端验证：能不能编进去、编进去之后准不准、准了之后会不会静默降级。
#
# 为什么这个脚本比别的都值得写：Vulkan 失败的方式是**安静**的。
# ggml 的 vk 后端在 reg_init 里 try/catch，初始化失败返回 nullptr，注册表直接把
# 这个后端跳过——模型照样加载成功，只是全程跑在 CPU 上。用户看到的是「开了 GPU
# 还是很慢」，我们看到的是「一切正常」。所以这一层必须有三道独立的证据：
#
#   1. 构建期：CMake 真的把 GGML_VULKAN 打开了（而不是被某个默认值关掉）；
#   2. 产物期：.so 里真的带着 libvulkan.so 依赖（编了但没链上等于没编）；
#   3. 运行期：设备判定真的会把该开的机型开起来、该关的关掉（规则反了比不编更坏）。
#
# 第 3 条不能靠 grep —— 静态检查查不出「vulkanPath 把 1.1 判成可用」这种事。
# 所以行为断言全部是真编真跑。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

DIR="core/src/main/java/com/beichen/polaris/core/llm/local"
PROFILE="$DIR/LocalDeviceProfile.kt"
CATALOG="$DIR/LocalBackendCatalog.kt"
PROBE="$DIR/AndroidDeviceProbe.kt"
JNI="core/src/main/java/com/beichen/polaris/core/llm/local/LlamaJni.kt"
CLIENT="$DIR/LocalLlamaClient.kt"
CMAKELISTS="app/src/main/cpp/CMakeLists.txt"
CPP="app/src/main/cpp/llama-jni.cpp"
WORKFLOW=".github/workflows/build.yml"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

has() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then pass "$name"; else fail "$name（缺：$needle）"; fi
}

hasnt() {
    local name="$1" file="$2" needle="$3"
    if grep -qF -- "$needle" "$file" 2>/dev/null; then fail "$name（不该出现：$needle）"; else pass "$name"; fi
}

echo "== 静态：CMake 真的把 Vulkan 打开了 =="

# 曾经的写法是无条件 `set(GGML_VULKAN OFF ... FORCE)`。现在必须走「工具链就绪才开」。
hasnt "没有无条件关闭 Vulkan" "$CMAKELISTS" "set(GGML_VULKAN    OFF CACHE BOOL"
has "工具链就绪时打开" "$CMAKELISTS" "set(GGML_VULKAN ON CACHE BOOL"
# 只认 AK_GLSLC / AK_SPIRV_DIR 两个环境变量：Android Studio 里没有这套工具链，
# 本地构建要能静默退回 CPU-only，否则每个开发者都编不过。
has "glslc 路径来自环境变量" "$CMAKELISTS" 'set(_ak_glslc "$ENV{AK_GLSLC}")'
has "SPIRV-Headers 路径来自环境变量" "$CMAKELISTS" 'set(_ak_spirv "$ENV{AK_SPIRV_DIR}")'
# 跳过 FindVulkan 自己的搜索：交叉编译时它只在 sysroot 里找 glslc，必失败。
has "显式喂 glslc 给 FindVulkan" "$CMAKELISTS" 'Vulkan_GLSLC_EXECUTABLE "${_ak_glslc}"'
has "显式喂 SPIRV-Headers_DIR" "$CMAKELISTS" 'SPIRV-Headers_DIR "${_ak_spirv}"'
# CI 里必须失败而不是静默降级：否则「以为开了 GPU，出的包其实只有 CPU」。
has "CI 可要求必须开 Vulkan" "$CMAKELISTS" "AK_REQUIRE_VULKAN"
has "缺工具链时报错而非降级" "$CMAKELISTS" "Vulkan: 要求开启但工具链缺失"

echo "== 静态：编译期事实一路传到 Kotlin =="

has "编了才定义宏" "$CMAKELISTS" "AK_HAS_VULKAN=1"
# 静态库的 PRIVATE 依赖会以 $<LINK_ONLY> 传播，但这类交叉编译里「理论上够」失败过一次。
has "显式链接 libvulkan" "$CMAKELISTS" "target_link_libraries(agentkit-llama vulkan)"
has "native 回答是否编入" "$CPP" "Java_com_beichen_polaris_core_llm_local_LlamaJni_nativeGpuBackend"
has "Kotlin 侧读编译期事实" "$JNI" "nativeGpuBackend()"
# 这里必须是 runCatching：.so 是旧的（没有这个符号）时返回 false，而不是整页崩掉。
has "读不到就当没编（不崩）" "$JNI" 'runCatching { nativeGpuBackend() }.getOrDefault(false)'

echo "== 静态：运行期能证明「到底上没上 GPU」 =="

has "设备枚举走 ggml 注册表" "$CPP" "ggml_backend_dev_count()"
has "对外暴露设备清单" "$CPP" "Java_com_beichen_polaris_core_llm_local_LlamaJni_nativeGpuProbe"
# 请求了 GPU 却一个 GPU 设备都没有，必须当场报错——这是「以为在用 GPU」的唯一防线。
has "请求 GPU 但无 GPU 设备时报警" "$CPP" "但没有任何 GPU 后端可用"
# Vulkan 版本必须问 loader 自己，不能读 PackageManager 的 FeatureInfo.version：
# 那个字段是 Int 且各版本编码不统一（第一版按 String 读，CI 直接编不过）。
has "版本从 loader 问" "$CPP" 'dlopen("libvulkan.so"'
has "调 vkEnumerateInstanceVersion" "$CPP" '"vkEnumerateInstanceVersion"'
# 注释里可以提（解释为什么不那么做），但不能真的去读它。
hasnt "不再从 PackageManager 读版本" "$PROBE" "packageManager.systemAvailableFeatures"
# IGPU 也是 GPU。漏算的结果是「明明有显卡却说没有」。
has "集显也计入 GPU" "$CPP" "GGML_BACKEND_DEVICE_TYPE_IGPU"

echo "== 静态：装载路径不许再写死 0 层 =="

# 注意 ctx 用的是**压过的** nCtx，不是 setting.nCtx：
# GPU 开启时上下文要被压到 4096（显存和权重共用，装不下就闪退，见 W-84）。
# 这条以前写的是 setting.nCtx，W-84 一改就红了——断言要盯语义，不要盯字面。
has "层数由规则层给" "$CLIENT" "engine.acquire(file.absolutePath, nCtx, setting.nThreads, gpu.layers)"
# W-85 之后入口从 capCtxForGpu 换成了 effectiveCtx（安全模式与 GPU 上限合判）。
# 断言盯语义不盯字面：这里只要求「客户端走的是合判入口」，再单独要求
# 「合判内部仍复用 GPU 上限」——两个前提都在，才不会出现「开了安全模式就不压 GPU 上限」。
has "GPU 开启时上下文先压到安全值" "$CLIENT" "effectiveCtx(setting.nCtx"
has "压安全值之后仍过 GPU 上限" "$PROFILE" "capCtxForGpu(capped, gpuLayers)"
has "两个前提分开判" "$CLIENT" "LlamaJni.gpuBackendAvailable"
has "没画像就退回 CPU" "$CLIENT" "设备画像尚未采集完成"
# GPU 装载失败必须能退回 CPU，且留下原因。少了这条，一台驱动有问题的机器会
# 「点开 GPU 之后本地推理彻底不可用」——比慢得多更糟。
has "GPU 装载失败退回 CPU" "$CLIENT" "退回纯 CPU 重试一次"
has "退回要说明怎么关掉" "$CLIENT" "GPU 加速」里选「关闭」"
# GPU 结论每轮都会重算，日志必须去重，否则十轮对话刷十行同样的内容。
has "GPU 日志去重" "$CLIENT" "if (line != lastGpuLogLine)"
# W-94：去重变量必须是**进程级**（companion）。这个 client 每轮会被建好几次
# （AgentLoop 的 llmProvider 每次取都现建），放实例字段等于每个实例各从 null 开始，
# 真机 16:08 一轮里同一句「GPU 未启用…」打了三遍。和 W-92 的 g_gpuDisabled 同一个坑。
_co=$(grep -n "private companion object" "$CLIENT" | head -1 | cut -d: -f1)
_lg=$(grep -n "private var lastGpuLogLine" "$CLIENT" | head -1 | cut -d: -f1)
if [ -n "$_co" ] && [ -n "$_lg" ] && [ "$_lg" -gt "$_co" ]; then
  pass "GPU 日志去重变量是进程级（companion 之后）"
else
  fail "GPU 日志去重变量是进程级（companion=${_co:-?} 变量=${_lg:-?}）"
fi

echo "== 静态：CI 装齐工具链三件套 =="

has "CI 装 glslc" "$WORKFLOW" "apt-get install"
has "CI 装 glslc 包" "$WORKFLOW" "glslc"
has "CI 装 SPIRV-Headers" "$WORKFLOW" "spirv-headers"
has "CI 导出 AK_GLSLC" "$WORKFLOW" "AK_GLSLC="
has "CI 导出 AK_SPIRV_DIR" "$WORKFLOW" "AK_SPIRV_DIR="
has "CI 要求必须开 Vulkan" "$WORKFLOW" "AK_REQUIRE_VULKAN=1"

echo "== 静态：NDK 不带 vulkan.hpp，要用宿主头但不能污染 bionic =="

# ggml-vulkan-types.h 第一行就 include <vulkan/vulkan.hpp>，NDK sysroot 里没有这个文件。
# 第一次 CI 就是死在这里（fatal error: 'vulkan/vulkan.hpp' file not found）。
has "CI 装 libvulkan-dev（拿 vulkan.hpp）" "$WORKFLOW" "libvulkan-dev"
# 但不能把 /usr/include 整个挂上去：-I 排在 -isystem 前面，glibc 头会顶掉 bionic 的。
has "做成只含 vulkan/ 的暂存目录" "$WORKFLOW" 'ln -sfn /usr/include/vulkan'
has "同时挂 spirv/（spirv.hpp）" "$WORKFLOW" 'ln -sfn /usr/include/spirv'
# vulkan_core.h 直接 include 了 vk_video/*，少挂一个就是 fatal error（第三次 CI 红的）。
has "还挂 vk_video/（core 头的硬依赖）" "$WORKFLOW" 'ln -sfn /usr/include/vk_video'
hasnt "不把 /usr/include 整个塞进 -I" "$WORKFLOW" 'AK_VULKAN_INCLUDE=/usr/include'
has "CI 当场校验 vulkan.hpp 在" "$WORKFLOW" 'test -f "$VKINC/vulkan/vulkan.hpp"'
has "CMake 缺 Vulkan-Hpp 会 FATAL" "$CMAKELISTS" "缺 Vulkan-Hpp"
has "CMake 把暂存目录挂到 ggml-vulkan" "$CMAKELISTS" 'target_include_directories(ggml-vulkan BEFORE PRIVATE "${_ak_vkinc}")'
# bionic 到 API 28 才有 aligned_alloc，minSdk 是 26 —— 不补就是在 ggml-vulkan-buffers.cpp
# 报「unresolved using declaration」。补的声明必须早于 <cstdlib>，所以只能 -include。
has "补 aligned_alloc（API < 28）" "$CMAKELISTS" "vulkan-aligned-compat.h"
has "兼容头按 API 级别自判" "app/src/main/cpp/vulkan-aligned-compat.h" "__ANDROID_API__ < 28"
has "兼容头用 posix_memalign 实现" "app/src/main/cpp/vulkan-aligned-compat.h" "posix_memalign"
# 说明里必须写明「为什么不能用 /usr/include」——否则下一个人会「顺手简化」成它。
has "写明不能直接挂 /usr/include" "$CMAKELISTS" "绝不能直接把 /usr/include 塞进 -I"

echo "== 静态：Vulkan 1.1 入口点要自己补（API 26 的库桩没有） =="

# run 459：402 个目标全编过、shader 全生成，最后死在一个符号上：
#   ld.lld: error: undefined symbol: vkGetPhysicalDeviceFeatures2
# 它是 Vulkan **1.1** 的核心函数，Android 的 libvulkan.so 到 **API 28** 才导出；
# minSdk 是 26，链接时拿的是 android26 的桩，里面只有 1.0。
has "CMake 编入 1.1 入口兜底" "$CMAKELISTS" "vulkan-entry-shim.cpp"
# 兜底文件必须落在 `if (AK_HAS_VULKAN)` 里面。写在 if 外面的话，CPU-only 构建
# （Android Studio 里没有 glslc）会引用一个不存在的 Vulkan 头，本地开发全编不过。
SHIM_LINE=$(grep -n "vulkan-entry-shim.cpp" "$CMAKELISTS" | head -1 | cut -d: -f1)
GUARD_LINE=$(grep -n "^if (AK_HAS_VULKAN)" "$CMAKELISTS" |
    awk -F: -v s="${SHIM_LINE:-0}" '$1 < s {l=$1} END{print l}')
ENDIF_LINE=$(grep -n "^endif()" "$CMAKELISTS" |
    awk -F: -v s="${SHIM_LINE:-0}" '$1 > s {print $1; exit}')
if [ -n "${SHIM_LINE:-}" ] && [ -n "${GUARD_LINE:-}" ] && [ -n "${ENDIF_LINE:-}" ] &&
    [ "$GUARD_LINE" -lt "$SHIM_LINE" ] && [ "$SHIM_LINE" -lt "$ENDIF_LINE" ]; then
    pass "兜底只在开 Vulkan 时才编"
else
    fail "兜底只在开 Vulkan 时才编（不在 if (AK_HAS_VULKAN) 块里）"
fi
has "兜底提供了那个符号" "app/src/main/cpp/vulkan-entry-shim.cpp" "vkGetPhysicalDeviceFeatures2(VkPhysicalDevice physicalDevice"
# 用 VKAPI_ATTR/VKAPI_CALL：armeabi-v7a 上它们是 pcs("aapcs-vfp")，
# 手写签名编译能过、32 位设备一调就崩。
has "兜底用 VKAPI_ATTR 声明" "app/src/main/cpp/vulkan-entry-shim.cpp" "extern \"C\" VKAPI_ATTR void VKAPI_CALL"
# RTLD_DEFAULT 会查到我们自己 → 无限递归。这里只查代码形式（注释里会提到 RTLD_DEFAULT，
# 上一版按裸词匹配把自己注释也判成了违规）。
hasnt "兜底不用 RTLD_DEFAULT（会自递归）" "app/src/main/cpp/vulkan-entry-shim.cpp" "dlsym(RTLD_DEFAULT"
has "兜底对 libvulkan 句柄 dlsym" "app/src/main/cpp/vulkan-entry-shim.cpp" 'dlsym(h, "vkGetPhysicalDeviceFeatures2")'
# 老 loader 上没有这个函数：必须给零值而不是空指针，否则设备枚举阶段直接崩。
has "兜底在无真身时填零" "app/src/main/cpp/vulkan-entry-shim.cpp" "pFeatures->features = VkPhysicalDeviceFeatures{}"
# 不能拿 API 28 的桩去链——Android 8.x 上急绑定会让整个 .so 加载失败，连 CPU 一起废。
has "写明不能改链 API 28 的桩" "app/src/main/cpp/vulkan-entry-shim.cpp" "连纯 CPU 推理一起废掉"

echo "== 行为：真编一遍（规则反了静态 grep 抓不出来） =="

KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
    # 解析成绝对路径。留在 PATH 里的裸命令名会让下面按 dirname 猜 stdlib 时
    # 退化成 "./lib/kotlin-stdlib.jar"——CI 的 shim 在 /tmp/kotlinc/bin，猜出来必空。
    KOTLINC="$(command -v kotlinc)"
elif [ -x "$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc" ]; then
    KOTLINC="$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc"
elif [ -x /opt/kotlinc/bin/kotlinc ]; then
    KOTLINC="/opt/kotlinc/bin/kotlinc"
fi

if [ -z "$KOTLINC" ]; then
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
else
    WORK="$(mktemp -d)"
    cat > "$WORK/VulkanCheck.kt" <<'KOTLIN'
import com.beichen.polaris.core.llm.local.*

private var ok = 0
private var bad = 0

private fun check(name: String, cond: Boolean, hint: String = "") {
    if (cond) { ok++; println("  PASS  $name") }
    else { bad++; println("  FAIL  $name${if (hint.isEmpty()) "" else "（$hint）"}") }
}

private fun p(gl: String?, vk: String?, soc: String? = null, manu: String? = null): LocalDeviceProfile =
    LocalDeviceProfile(
        socModel = soc, socManufacturer = manu, hardware = null,
        glRenderer = gl, totalRamMb = 12288, vulkanVersion = vk,
    )

private fun advice(gl: String?, vk: String?) = LocalDeviceRules.advise(p(gl, vk))

fun main() {
    // ---- vulkanPath：先看版本，再看家族 ----
    val a11 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 660", "1.1"))
    check("Vulkan 1.1 一律不可用", a11.first == GpuPath.NONE && a11.second!!.contains("1.2"), a11.toString())
    val a12 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 660", "1.2"))
    check("Vulkan 1.2 刚好够", a12.first == GpuPath.STABLE, a12.toString())
    val a13 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", "1.3"))
    check("Adreno + 1.3 稳妥", a13.first == GpuPath.STABLE && a13.second == null, a13.toString())
    // 系统给的是 "Vulkan 1.3" 这种带前缀的串，不能解析失败。
    val aPre = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", "Vulkan 1.3"))
    check("带 Vulkan 前缀也能解析", aPre.first == GpuPath.STABLE, aPre.toString())
    val mali = LocalDeviceRules.vulkanPath(p("Immortalis-G720 MC12", "1.3"))
    check("Mali 系是「需验证」不是「不可用」", mali.first == GpuPath.RISKY, mali.toString())
    check("Mali 的原因可解释", mali.second == null, mali.second ?: "null")
    val pvr = LocalDeviceRules.vulkanPath(p("PowerVR Rogue GE8320", "1.3"))
    check("PowerVR 劝退", pvr.first == GpuPath.NONE && pvr.second!!.contains("驱动"), pvr.toString())
    val noVer = LocalDeviceRules.vulkanPath(p("Adreno (TM) 750", null))
    check("没报版本按不支持", noVer.first == GpuPath.NONE && noVer.second!!.contains("未报告"), noVer.toString())
    val noGpu = LocalDeviceRules.vulkanPath(p(null, "1.3"))
    check("没 GPU 型号按不支持", noGpu.first == GpuPath.NONE, noGpu.toString())

    // ---- 版本解码：VK_MAKE_VERSION 是位域，不是十进制 ----
    fun mk(major: Int, minor: Int, patch: Int) = (major shl 22) or (minor shl 12) or patch
    check("1.3.275 解码为 1.3", LocalDeviceRules.formatVulkanVersion(mk(1, 3, 275)) == "1.3")
    check("1.1.0 解码为 1.1", LocalDeviceRules.formatVulkanVersion(mk(1, 1, 0)) == "1.1")
    check("1.2.0 解码为 1.2", LocalDeviceRules.formatVulkanVersion(mk(1, 2, 0)) == "1.2")
    check("0（没有 loader）为 null", LocalDeviceRules.formatVulkanVersion(0) == null)
    // 十进制编码（1.3 → 10003）如果被误当成打包值，major 会是 0 → 必须被判为 null，
    // 而不是「1.0 以下所以可用」。这条挡的是「用错编码却看起来正常」。
    check("脏值不会变成高版本", LocalDeviceRules.formatVulkanVersion(10003) == null,
        LocalDeviceRules.formatVulkanVersion(10003) ?: "null")

    // loader 报 1.0（老机器 / 老 ROM）→ 不可用，且原因要说版本
    val v10 = LocalDeviceRules.vulkanPath(p("Adreno (TM) 640", "1.0"))
    check("Vulkan 1.0 不可用", v10.first == GpuPath.NONE && v10.second!!.contains("1.2"), v10.toString())

    // ---- resolveGpu：两个前提缺一不可 ----
    val stable = advice("Adreno (TM) 750", "1.3")
    val risky = advice("Mali-G715 MC11", "1.3")
    val none = advice("Adreno (TM) 660", "1.1")

    check("自动 + 稳妥 → 开", LocalDeviceRules.resolveGpu(stable, true, GpuMode.AUTO).layers == 99)
    check("自动 + 需验证 → 关", LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).layers == 0)
    check("自动 + 需验证 → 有原因",
        LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).reason?.contains("自动模式") == true,
        LocalDeviceRules.resolveGpu(risky, true, GpuMode.AUTO).reason ?: "null")
    check("强制 + 需验证 → 开", LocalDeviceRules.resolveGpu(risky, true, GpuMode.FORCE).layers == 99)
    // 强制也不越界：版本都不够，开了只会白白多等一次初始化再退回。
    check("强制 + 不可用 → 仍关", LocalDeviceRules.resolveGpu(none, true, GpuMode.FORCE).layers == 0)
    check("关闭 → 关", LocalDeviceRules.resolveGpu(stable, true, GpuMode.OFF).layers == 0)
    // 包没编 Vulkan 时，哪怕设备再强也必须是 0——这一条挡的是「UI 说开了、实际没编」。
    val notCompiled = LocalDeviceRules.resolveGpu(stable, false, GpuMode.AUTO)
    check("包没编 → 关", notCompiled.layers == 0)
    check("包没编 → 原因是「未编入」", notCompiled.reason?.contains("未编入") == true, notCompiled.reason ?: "null")
    check("层数是「全卸」而不是写死模型层数", LocalDeviceRules.GPU_MAX_LAYERS == 99)

    // ---- 后端目录：选项表与规则层同源 ----
    val envOn = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Adreno (TM) 750", "1.3"))
    val optsOn = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOn)
    val gpuOn = optsOn.first { it.backend == LocalBackend.GPU }
    check("编入 + 稳妥 → GPU 可选", gpuOn.available)
    check("GPU 说明带型号与版本",
        gpuOn.detail.contains("Adreno 750") && gpuOn.detail.contains("1.3"), gpuOn.detail)

    val envMali = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Mali-G715 MC11", "1.3"))
    val gpuMali = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envMali)
        .first { it.backend == LocalBackend.GPU }
    check("编入 + 需验证 → 仍灰", !gpuMali.available)
    check("灰掉时告诉用户怎么强开", gpuMali.reason!!.contains("强制开启"), gpuMali.reason!!)

    val envOld = LocalEngineEnvironment(llamaGpuEnabled = true, device = p("Adreno (TM) 660", "1.1"))
    val gpuOld = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOld)
        .first { it.backend == LocalBackend.GPU }
    check("编入 + 版本不够 → 灰", !gpuOld.available)
    check("灰掉原因是版本", gpuOld.reason!!.contains("1.2"), gpuOld.reason!!)

    val envOff = LocalEngineEnvironment(llamaGpuEnabled = false, device = p("Adreno (TM) 750", "1.3"))
    val gpuOff = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOff)
        .first { it.backend == LocalBackend.GPU }
    check("没编 → 灰", !gpuOff.available)
    check("没编时说明外加一句本机情况", gpuOff.reason!!.contains("本机 Adreno 750"), gpuOff.reason!!)
    check("没编时仍承诺 CPU 可用",
        LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envOff)
            .first { it.backend == LocalBackend.CPU }.available)

    // 没有画像但有 hasVulkan：能开，但说明里要承认「不知道是什么卡」。
    val envBare = LocalEngineEnvironment(llamaGpuEnabled = true, hasVulkan = true)
    val gpuBare = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envBare)
        .first { it.backend == LocalBackend.GPU }
    check("无画像 + hasVulkan → 可选", gpuBare.available)
    check("无画像时承认型号未知", gpuBare.detail.contains("未取得"), gpuBare.detail)

    val envNoVk = LocalEngineEnvironment(llamaGpuEnabled = true, hasVulkan = false)
    val gpuNoVk = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", envNoVk)
        .first { it.backend == LocalBackend.GPU }
    check("无画像 + 无 Vulkan → 灰", !gpuNoVk.available)

    // ---- 装载链：GPU 不可用时不能把整条链弄空 ----
    val plan = LocalEngineCatalog.planLoad(LocalBackend.GPU, optsOn)
    check("首选 GPU 时链路从 GPU 起", plan.chain.first() == LocalBackend.GPU, plan.chain.toString())
    val planOld = LocalEngineCatalog.planLoad(LocalBackend.GPU, LocalEngineCatalog.options(
        LocalModelFormat.GGUF, "a.gguf", envOld))
    check("GPU 不可用时降级到 CPU", planOld.chain == listOf(LocalBackend.CPU), planOld.chain.toString())
    check("降级要留话", planOld.notes.any { it.contains("1.2") }, planOld.notes.toString())

    println("SUMMARY $ok $bad")
}
KOTLIN
    # kotlinc 可能是 shim（自己拼 embeddable 编译器），此时它的位置跟 stdlib 毫无关系，
    # 按 dirname 猜必空。stdlb 不在 classpath 上时编译能过、运行期第一句就
    # NoClassDefFoundError: kotlin/NoWhenBranchMatchedException —— 断言一条都没跑，
    # 但脚本照样报绿。所以这里多路找 + 跑不出 SUMMARY 直接判失败。
    STDLIB="${KOTLIN_STDLIB:-}"
    if [ -z "$STDLIB" ]; then
        for cand in \
            "/tmp/kotlinc/lib/kotlin-stdlib.jar" \
            "$HOME/.sdkman/candidates/kotlin/current/lib/kotlin-stdlib.jar" \
            "/usr/share/kotlin/lib/kotlin-stdlib.jar" \
            "/opt/kotlinc/lib/kotlin-stdlib.jar" \
            "/tmp/khome/lib/kotlin-stdlib.jar" \
            "$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"
        do
            [ -f "$cand" ] && STDLIB="$cand" && break
        done
    fi
    "$KOTLINC" -nowarn "$PROFILE" "$CATALOG" "$WORK/VulkanCheck.kt" -d "$WORK/check.jar" 2>"$WORK/err.txt"
    if [ ! -f "$WORK/check.jar" ]; then
        fail "Kotlin 编译失败"
        sed -n '1,15p' "$WORK/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$WORK/check.jar${STDLIB:+:$STDLIB}" VulkanCheckKt 2>&1)"
        echo "$OUT" | grep -v '^SUMMARY'
        BAD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\2/p')"
        GOOD="$(echo "$OUT" | sed -n 's/^SUMMARY \([0-9]*\) \([0-9]*\)$/\1/p')"
        if [ -z "$GOOD" ]; then
            # 没有 SUMMARY = 断言一条都没执行。这比断言失败更糟：看着是全绿。
            fail "行为断言没跑起来（无 SUMMARY 输出）"
            echo "$OUT" | sed -n '1,8p' | sed 's/^/        /'
            [ -n "$STDLIB" ] || echo "        提示：没找到 kotlin-stdlib.jar，可用 KOTLIN_STDLIB=<path> 指定"
        else
            PASS=$((PASS + GOOD))
            FAIL=$((FAIL + ${BAD:-0}))
        fi
    fi
    rm -rf "$WORK"
fi

echo "== 行为：1.1 兜底真的能替调用方补上符号 =="

# run 459 的现场：402 个目标全编过，最后死在
#   ld.lld: error: undefined symbol: vkGetPhysicalDeviceFeatures2
# 这里用「假 loader」把那个场景搬到本机：一个只导出 1.0 符号的 libvulkan.so
# （正是 android26 桩的样子），加上 --no-undefined 复现链接失败；
# 再跑起来验证两条分支——有真身就转发，没有就填零。
if [ ! -f "${VKINC_CHK:-/usr/include}/vulkan/vulkan.h" ] || ! command -v g++ >/dev/null 2>&1; then
    echo "  SKIP  本机没有 Vulkan 头或 g++（CI 里装了 libvulkan-dev，会跑这一段）"
else
    VKINC_CHK="${VKINC_CHK:-/usr/include}"
    VKT="$(mktemp -d)"

    # ① 假 loader：只有 1.0（缺 vkGetPhysicalDeviceFeatures2）
    printf 'void vkGetInstanceProcAddr(void) {}\n' > "$VKT/only10.c"
    gcc -shared -fPIC -o "$VKT/libvulkan.so" "$VKT/only10.c" 2>/dev/null
    cat > "$VKT/main.cpp" <<'CPP'
#include <vulkan/vulkan.h>

extern "C" void vkGetPhysicalDeviceFeatures2(VkPhysicalDevice, VkPhysicalDeviceFeatures2*);

int main() {
    VkPhysicalDeviceFeatures2 f{};
    f.features.robustBufferAccess = VK_TRUE;   // 先置真，看兜底会不会按「不支持」清掉
    vkGetPhysicalDeviceFeatures2(nullptr, &f);
    return f.features.robustBufferAccess == VK_FALSE ? 0 : 1;
}
CPP
    if g++ -std=c++17 -I"$VKINC_CHK" "$VKT/main.cpp" \
        app/src/main/cpp/vulkan-entry-shim.cpp \
        -L"$VKT" -lvulkan -Wl,--no-undefined -o "$VKT/t" 2>"$VKT/err.txt"; then
        pass "1.1 兜底补上了链接期缺失的符号"
    else
        fail "1.1 兜底没补上符号（和 run 459 同一个报错）"
        sed -n '1,6p' "$VKT/err.txt" | sed 's/^/        /'
    fi
    if [ -x "$VKT/t" ] && LD_LIBRARY_PATH="$VKT" "$VKT/t"; then
        pass "loader 无 1.1 入口点时按「全不支持」填零（不崩）"
    else
        fail "loader 无 1.1 入口点时兜底没把特性清掉"
    fi

    # ② 假 loader 带真身：兜底必须原样转发（不能自己造结论）
    cat > "$VKT/with11.c" <<'C'
#include <vulkan/vulkan.h>
void vkGetPhysicalDeviceFeatures2(VkPhysicalDevice pd, VkPhysicalDeviceFeatures2* f) {
    (void) pd;
    f->features.robustBufferAccess = VK_TRUE;   // 只有真身会置真
}
C
    gcc -shared -fPIC -I"$VKINC_CHK" -o "$VKT/libvulkan11.so" "$VKT/with11.c" 2>/dev/null
    mv "$VKT/libvulkan.so" "$VKT/libvulkan-only10.so" 2>/dev/null
    cp "$VKT/libvulkan11.so" "$VKT/libvulkan.so"
    if LD_LIBRARY_PATH="$VKT" "$VKT/t" 2>/dev/null; then
        fail "有真身时兜底没转发（结果仍是填零）"
    else
        pass "loader 有 1.1 入口点时原样转发"
    fi
    rm -rf "$VKT"
fi

echo "== 产物：.so 里真的链上了 libvulkan =="

# 「编了 Vulkan」和「链上了 Vulkan」是两件事。cmake 把 -DGGML_VULKAN=ON 收下、
# 却在最终链接时把 ggml-vulkan 静态库丢掉，产物照样叫成功——只有 readelf 能证伪。
APK=""
for cand in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/app-debug-unsigned.apk; do
    [ -f "$cand" ] && APK="$cand" && break
done

if [ -z "$APK" ]; then
    echo "  SKIP  本机没有构建产物（CI 构建阶段之后会跑这一项）"
elif ! command -v readelf >/dev/null 2>&1; then
    echo "  SKIP  没有 readelf（binutils 未安装）"
else
    TMP="$(mktemp -d)"
    # -PnoNative=true 的构建（无 NDK 的机器上跑 QA）整个 APK 里就没有 lib/ 目录。
    # 这不是「产物不对」，是本次压根没编 native——跳过而不是判红，否则每次都得手工分辨。
    # 有 native 产物时下面的检查一条不少，掩盖不了真问题。
    if ! unzip -l "$APK" 2>/dev/null | grep -q "lib/arm64-v8a/"; then
        echo "  SKIP  APK 不含 arm64-v8a 原生库（本次构建 -PnoNative=true）"
    elif unzip -o -q "$APK" "lib/arm64-v8a/*" -d "$TMP" 2>/dev/null; then
        SO="$TMP/lib/arm64-v8a/libagentkit-llama.so"
        if [ ! -f "$SO" ]; then
            fail "APK 里没有 libagentkit-llama.so"
        else
            # 先把三份输出落盘，再各自 grep。
            #
            # 不能写成 `strings "$SO" | grep -q ...`：脚本开着 pipefail，
            # grep -q 一旦匹配就退出，strings 收到 SIGPIPE（141），整条管道判成失败——
            # 明明符号在里面，却报「没找到」。这个假红跟机器快慢有关，本机必红、CI 偶尔绿，
            # 是最难查的那一类。落盘之后再 grep，管道里就没有提前退出的进程了。
            readelf -d "$SO" > "$TMP/dyn.txt" 2>/dev/null
            strings "$SO" > "$TMP/str.txt" 2>/dev/null
            nm -D --defined-only "$SO" > "$TMP/sym.txt" 2>/dev/null

            if grep -q "libvulkan.so" "$TMP/dyn.txt"; then
                pass "产物依赖 libvulkan.so"
            else
                fail "产物没有链上 libvulkan.so（GGML_VULKAN 开了但后端被链接器丢掉）"
            fi
            # ggml-vulkan.cpp 里的日志前缀。它只可能来自那个翻译单元，
            # 比找 "Vulkan" 这种随处可见的词可靠得多（llama.cpp 自己的报错里也有 Vulkan）。
            if grep -q "ggml_vulkan:" "$TMP/str.txt"; then
                pass "产物含 ggml-vulkan 代码（日志前缀在）"
            else
                fail "产物里找不到 ggml_vulkan: 前缀，Vulkan 后端可能没被链接进去"
            fi
            # 兜底符号必须真的在 .so 里**定义**（而不是留成未定义引用）。
            # 这条能同时证伪两件事：文件没编进目标、以及「改去链 API 28 的桩」。
            if ! command -v nm >/dev/null 2>&1; then
                echo "  SKIP  没有 nm，跳过分组符号检查"
            elif grep -q " vkGetPhysicalDeviceFeatures2$" "$TMP/sym.txt"; then
                pass "产物自己定义了 vkGetPhysicalDeviceFeatures2（1.1 兜底在位）"
            else
                fail "产物没有定义 vkGetPhysicalDeviceFeatures2，Android 8.x 上可能整库加载失败"
            fi
            # 日志串在 .so 里只能用纯 ASCII 找：strings 不认 UTF-8，中文一个字节都取不出来。
            # 上一版拿中文串 grep，把「兜底其实编进去了」判成「没编进去」，白红一次。
            if grep -q "vk11-shim" "$TMP/str.txt"; then
                pass "产物含 1.1 转发日志（走的是运行时转发而不是硬链）"
            else
                fail "产物里没有 vk11-shim 日志前缀，兜底可能没编进去"
            fi
        fi
    else
        fail "APK 解压失败：$APK"
    fi
    rm -rf "$TMP"
fi

echo
echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
