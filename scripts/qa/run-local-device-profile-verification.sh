#!/usr/bin/env bash
# 设备画像验证：一台手机该走 NPU / GPU / CPU 哪条路。
#
# 起因：有人问「8 Gen 1 以下的高通、还有天玑用户能不能适配」。
# 这个问题的答案不在代码风格里，在两张表里——厂商能不能上 NPU、GPU 驱动熟不熟。
# 两张表都错了也不会崩，只会让用户在设置里看到一个点了没反应的 GPU 开关。
# 所以这里既查静态（规则有没有被改回乐观值），也真编真跑（规则有没有反）。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

DIR="core/src/main/java/com/beichen/polaris/core/llm/local"
PROFILE="$DIR/LocalDeviceProfile.kt"
PROBE="$DIR/AndroidDeviceProbe.kt"
# LocalBackend 枚举住在后端目录里，画像层只是引用它，编的时候要一起带上。
CATALOG="$DIR/LocalBackendCatalog.kt"
APP="app/src/main/java/com/beichen/polaris/AgentKitApplication.kt"
BOOT="app/src/main/java/com/beichen/polaris/startup/BootSequence.kt"

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

echo "== 静态：结构 =="

if [ -f "$PROFILE" ]; then pass "设备画像存在"; else fail "设备画像缺失：$PROFILE"; fi
if [ -f "$PROBE" ]; then pass "设备采集器存在"; else fail "设备采集器缺失：$PROBE"; fi

# 规则层必须纯 Kotlin：一旦 import android.*，行为断言就编不过、也跑不了。
hasnt "规则层不依赖 Android" "$PROFILE" "import android."
has   "采集层才碰 Android" "$PROBE" "import android.os.Build"
hasnt "采集层不含判定逻辑" "$PROBE" "fun vendorOf"

echo "== 静态：NPU 只认高通 8 系（这是覆盖面的分水岭） =="

# 这条是全篇最容易被「好心」改错的地方：天玑也有 NPU，看起来应该支持。
# 但 LiteRT 要在上面跑必须有 NeuroPilot 的 AOT 转换链，我们没有。
has "非高通一律不上 NPU" "$PROFILE" "if (vendor != SocVendor.QUALCOMM)"
has "天玑的理由写明 NeuroPilot" "$PROFILE" "NeuroPilot"
has "麒麟的理由写明达芬奇" "$PROFILE" "达芬奇"
has "8 Gen 1 是 NPU 门槛" "$PROFILE" "if (n < 8450)"
has "取不到型号时不乐观" "$PROFILE" "无法确认 Hexagon 版本"

echo "== 静态：GPU 分三档，Mali 不默认开 =="

has "Adreno 视为成熟" "$PROFILE" "ADRENO(\"Adreno\", VulkanMaturity.GOOD)"
has "Mali 视为需验证" "$PROFILE" "MALI(\"Mali\", VulkanMaturity.MIXED)"
has "PowerVR 视为不建议" "$PROFILE" "POWERVR(\"PowerVR\", VulkanMaturity.POOR)"
# 低端 Mali 直接给 CPU：不是慢，是内存不够。
has "低端 Mali 落到 CPU" "$PROFILE" "在入门/中低端机上跑大模型容易内存不足"

echo "== 静态：老机型没有 SOC_MODEL 时的兜底 =="

has "反射读 Build 字段" "$PROBE" "Build::class.java.getField(name)"
hasnt "不直接引用 API 31 字段" "$PROBE" "Build.SOC_MODEL"
has "GL_RENDERER 走 EGL 探测" "$PROBE" "eglGetDisplay"
has "探测失败不抛异常" "$PROBE" "设备探测失败不该让应用启动失败"
has "内存读 /proc/meminfo" "$PROBE" "/proc/meminfo"

echo "== 静态：画像在后台采，不许拖慢冷启动 =="

# onCreate 是启动最敏感的路径。EGL 上下文创建哪怕只有 20ms，也不该压在这里。
has "画像在独立线程采集" "$BOOT" "name = \"device-probe\""
has "线程是 daemon（不阻止退出）" "$BOOT" "isDaemon = true"
has "采集整体 runCatching" "$BOOT" "runCatching { AndroidDeviceProbe.profile(app) }"

echo "== 行为：真编一遍 =="

KOTLINC=""
if command -v kotlinc >/dev/null 2>&1; then
    KOTLINC="kotlinc"
elif [ -x "$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc" ]; then
    KOTLINC="$HOME/.sdkman/candidates/kotlin/current/bin/kotlinc"
elif [ -x /opt/kotlinc/bin/kotlinc ]; then
    KOTLINC="/opt/kotlinc/bin/kotlinc"
# CI 的 shim 造在 /tmp/kotlinc/bin 且**没有加进 PATH**（见 workflow「Prepare kotlinc shim」）。
# 少了这一条，行为断言在 CI 里一直 SKIP —— 之前只查 command -v，等于没验。
elif [ -x /tmp/kotlinc/bin/kotlinc ]; then
    KOTLINC="/tmp/kotlinc/bin/kotlinc"
fi

if [ -z "$KOTLINC" ]; then
    echo "  SKIP  未找到 kotlinc，跳过行为断言（CI 里由 gradle 真编）"
else
    WORK="$(mktemp -d)"
    cat > "$WORK/ProfileCheck.kt" <<'KOTLIN'
import com.beichen.polaris.core.llm.local.*

private var ok = 0
private var bad = 0

private fun check(name: String, cond: Boolean, hint: String = "") {
    if (cond) { ok++; println("  PASS  $name") }
    else { bad++; println("  FAIL  $name${if (hint.isEmpty()) "" else "（$hint）"}") }
}

// vk 默认给 "1.3"：绝大多数想验证的场景都是「这台机 Vulkan 够新，只是家族/版本另说」。
// 要验「Vulkan 版本不够」的机型显式传 "1.1"。
private fun p(soc: String? = null, manu: String? = null, hw: String? = null,
              gl: String? = null, ram: Int = 8192, vk: String? = "1.3"): LocalDeviceProfile =
    LocalDeviceProfile(socModel = soc, socManufacturer = manu, hardware = hw, glRenderer = gl,
                       totalRamMb = ram, vulkanVersion = vk)

fun main() {
    // ---- GPU 字符串解析（各厂商写法不统一）----
    val g1 = LocalDeviceRules.gpuOf("Adreno (TM) 740")
    check("Adreno 740 识别", g1.first == GpuFamily.ADRENO && g1.second == "Adreno 740", g1.toString())
    val g2 = LocalDeviceRules.gpuOf("Mali-G77 MC9")
    check("Mali-G77 带空格也要认", g2.first == GpuFamily.MALI && g2.second == "Mali-G77", g2.toString())
    val g3 = LocalDeviceRules.gpuOf("Mali-G610 MC6")
    check("Mali-G610 三位数", g3.second == "Mali-G610", g3.toString())
    val g4 = LocalDeviceRules.gpuOf("Immortalis-G715 MC11")
    check("Immortalis 单列一族", g4.first == GpuFamily.IMMORTALIS, g4.toString())
    val g5 = LocalDeviceRules.gpuOf("PowerVR Rogue GE8320")
    check("PowerVR GE8320", g5.first == GpuFamily.POWERVR && g5.second == "PowerVR GE8320", g5.toString())
    val g6 = LocalDeviceRules.gpuOf("Xclipse 920")
    check("Xclipse 920", g6.first == GpuFamily.XCLIPSE, g6.toString())
    check("null 不猜", LocalDeviceRules.gpuOf(null).first == GpuFamily.UNKNOWN)
    check("短串不崩", LocalDeviceRules.gpuOf("Adreno").first == GpuFamily.ADRENO)

    check("SM8650 是 8 系", LocalDeviceRules.qualcommTier("SM8650") == 8)
    check("SM6375 是 6 系", LocalDeviceRules.qualcommTier("SM6375") == 6)
    check("MT6989 不是骁龙", LocalDeviceRules.qualcommTier("MT6989") == null)

    // ---- 高通 8 系旗舰：NPU ----
    val a = LocalDeviceRules.advise(p("SM8650", "QTI", "qcom", "Adreno (TM) 750", 12288))
    check("8 Gen 3 厂商=高通", a.vendor == SocVendor.QUALCOMM, a.vendor.label)
    check("8 Gen 3 能上 NPU", a.npuUsable, a.npuReason ?: "")
    check("8 Gen 3 首选 NPU", a.preferredPath == LocalBackend.NPU, a.preferredPath.label)

    val a1 = LocalDeviceRules.advise(p("SM8450", "QTI", "qcom", "Adreno (TM) 730", 8192))
    check("8 Gen 1 刚好够 NPU 门槛", a1.npuUsable, a1.npuReason ?: "")

    // ---- 8 Gen 1 以下：NPU 没门，GPU 有门 ----
    val b = LocalDeviceRules.advise(p("SM8350", "QTI", "qcom", "Adreno (TM) 660", 8192))
    check("骁龙 888 上不了 NPU", !b.npuUsable)
    check("888 的理由提到 v69", b.npuReason?.contains("v69") == true, b.npuReason ?: "")
    check("888 首选 GPU", b.preferredPath == LocalBackend.GPU, b.preferredPath.label)

    val b2 = LocalDeviceRules.advise(p("SM8250", "QTI", "qcom", "Adreno (TM) 650", 8192))
    check("骁龙 870 首选 GPU", b2.preferredPath == LocalBackend.GPU, b2.preferredPath.label)

    val b3 = LocalDeviceRules.advise(p("SM6375", "QTI", "qcom", "Adreno (TM) 619", 6144))
    check("骁龙 695 不是 8 系", b3.npuReason?.contains("不是 8 系") == true, b3.npuReason ?: "")
    check("骁龙 695 仍可试 GPU", b3.preferredPath == LocalBackend.GPU, b3.preferredPath.label)
    check("骁龙 695 属中低端", b3.tier == DeviceTier.LOW, b3.tier.label)

    // ---- 天玑：NPU 完全无缘，GPU 先给 CPU ----
    val c = LocalDeviceRules.advise(p("MT6989", "MediaTek", "mt6989", "Immortalis-G720 MC12", 12288))
    check("天玑 9300 厂商=联发科", c.vendor == SocVendor.MEDIATEK, c.vendor.label)
    check("天玑 9300 上不了 NPU", !c.npuUsable)
    check("天玑的理由提到 NeuroPilot", c.npuReason?.contains("NeuroPilot") == true, c.npuReason ?: "")
    check("天玑 9300 首选 CPU", c.preferredPath == LocalBackend.CPU, c.preferredPath.label)
    check("天玑 9300 仍是旗舰档", c.tier == DeviceTier.HIGH, c.tier.label)
    check("天玑 9300 提示可手动试 GPU", c.notes.any { "手动试 GPU" in it }, c.notes.toString())

    val c2 = LocalDeviceRules.advise(p("MT6855", "MediaTek", "mt6855", "Mali-G57 MC2", 6144))
    check("天玑 7050 首选 CPU", c2.preferredPath == LocalBackend.CPU)
    check("天玑 7050 提示内存不足", c2.notes.any { "内存不足" in it }, c2.notes.toString())

    val c3 = LocalDeviceRules.advise(p("MT6893", "MediaTek", "mt6893", "Mali-G77 MC9", 8192))
    check("天玑 1200 属中高端", c3.tier == DeviceTier.MID, c3.tier.label)

    // ---- 麒麟 / 三星 / 紫光：都不在 LiteRT 支持列表 ----
    val d = LocalDeviceRules.advise(p("Hi36A0", "HiSilicon", "kirin9000", "Mali-G78 MP24", 8192))
    check("麒麟 9000 厂商=海思", d.vendor == SocVendor.KIRIN, d.vendor.label)
    check("麒麟的理由提到达芬奇", d.npuReason?.contains("达芬奇") == true, d.npuReason ?: "")

    val e = LocalDeviceRules.advise(p(null, "samsung", "exynos2200", "Xclipse 920", 8192))
    check("Exynos 首选 CPU（Xclipse 算子覆盖不全）", e.preferredPath == LocalBackend.CPU, e.preferredPath.label)

    val f = LocalDeviceRules.advise(p(null, "UNISOC", "ums9230", "Mali-G57 MC2", 6144))
    check("紫光厂商识别", f.vendor == SocVendor.UNISOC, f.vendor.label)

    // ---- 老机型：Android 11 及以下拿不到 SOC_MODEL ----
    // 这批人恰好就是「8 Gen 1 以下」，所以兜底必须靠得住。
    val h = LocalDeviceRules.advise(p(null, null, "qcom", "Adreno (TM) 610", 6144))
    check("只有 hardware=qcom 也认得出高通", h.vendor == SocVendor.QUALCOMM, h.vendor.label)
    check("拿不到型号时 NPU 说不清就不开", !h.npuUsable)
    check("但 GPU 仍可用", h.preferredPath == LocalBackend.GPU, h.preferredPath.label)

    // 只有一个 GL 串、其他全空：广告机 / 白牌机常态。
    val i = LocalDeviceRules.advise(p(null, null, null, "Mali-G52 MC2", 4096))
    check("只剩 GL 串也能给出结论", i.preferredPath == LocalBackend.CPU, i.preferredPath.label)
    check("认清是 Mali 家族", i.gpu == GpuFamily.MALI, i.gpu.label)
    check("4GB 判为入门档", i.tier == DeviceTier.ENTRY, i.tier.label)
    check("入门档要劝小模型", i.notes.any { "不超过 1B" in it }, i.notes.toString())

    // ---- 全空：最保守 ----
    val k = LocalDeviceRules.advise(LocalDeviceProfile())
    check("全空时首选 CPU", k.preferredPath == LocalBackend.CPU)
    check("全空时厂商未知", k.vendor == SocVendor.UNKNOWN)
    check("全空要有说明", k.notes.any { "最保守" in it }, k.notes.toString())
    check("内存未知不乐观", k.tier == DeviceTier.LOW, k.tier.label)

    // ---- 画像接进后端目录：不可用原因要说清「本机是什么」 ----
    val ggufAdreno = LocalEngineCatalog.options(
        LocalModelFormat.GGUF, "a.gguf",
        LocalEngineEnvironment(device = p("SM8650", "QTI", "qcom", "Adreno (TM) 750", 12288)),
    )
    val rAdreno = ggufAdreno.first { it.backend == LocalBackend.GPU }.reason ?: ""
    check("Adreno 的原因带本机型号", rAdreno.contains("Adreno 750"), rAdreno)
    check("Adreno 说明开了就能用", rAdreno.contains("可直接用"), rAdreno)

    val ggufMali = LocalEngineCatalog.options(
        LocalModelFormat.GGUF, "a.gguf",
        LocalEngineEnvironment(device = p("MT6989", "MediaTek", "mt6989", "Immortalis-G720 MC12", 12288)),
    )
    val rMali = ggufMali.first { it.backend == LocalBackend.GPU }.reason ?: ""
    check("Mali 的原因带本机型号", rMali.contains("Immortalis-G720"), rMali)
    check("Mali 说明要按机型验证", rMali.contains("按机型验证"), rMali)

    // 没有画像时文案必须与旧版逐字一致：这一层是增量，不许悄悄改已有文案。
    val ggufPlain = LocalEngineCatalog.options(LocalModelFormat.GGUF, "a.gguf", LocalEngineEnvironment())
    val rPlain = ggufPlain.first { it.backend == LocalBackend.GPU }.reason ?: ""
    check(
        "无画像时文案不变",
        rPlain == "当前构建的 llama.cpp 只编了 CPU 后端（Vulkan / OpenCL 未启用）",
        rPlain,
    )

    // ---- 预算与摘要 ----
    check("8GB 建议 1.5B~3B", LocalDeviceRules.modelBudget(DeviceTier.MID).first.contains("3B"))
    check("入门档预算最小", LocalDeviceRules.modelBudget(DeviceTier.ENTRY).second <
        LocalDeviceRules.modelBudget(DeviceTier.HIGH).second)
    val summary = LocalDeviceRules.summarize(a)
    check("摘要含首选路径", summary.contains("NPU"), summary)
    check("摘要非空且含芯片", summary.contains("高通"), summary)

    println("SUMMARY $ok $bad")
}
KOTLIN
    STDLIB="${KOTLIN_STDLIB:-}"
    if [ -z "$STDLIB" ]; then
        GUESS="$(dirname "$(dirname "$KOTLINC")")/lib/kotlin-stdlib.jar"
        [ -f "$GUESS" ] && STDLIB="$GUESS"
    fi
    "$KOTLINC" -nowarn "$PROFILE" "$CATALOG" "$WORK/ProfileCheck.kt" -d "$WORK/check.jar" 2>"$WORK/err.txt"
    if [ ! -f "$WORK/check.jar" ]; then
        fail "Kotlin 编译失败"
        sed -n '1,15p' "$WORK/err.txt" | sed 's/^/        /'
    else
        OUT="$(java -cp "$WORK/check.jar${STDLIB:+:$STDLIB}" ProfileCheckKt 2>&1)"
        echo "$OUT" | grep -v '^SUMMARY'
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
