#!/usr/bin/env bash
# 二维码导入导出验证（W-RT8）：自写编码器 × 现成解码库对拍 + 负载层行为断言。
#
# 起因：零依赖纪律下二维码编码器自写。编码器最怕「自己看着像、真机扫不出」，
# 所以行为断言的考官用现成解码库真解——解不出/解错即红，不给自嗨断言留活路。
# 负载层纪律：Key 绝不进二维码（负载只装非敏感字段），坏输入宽容拒收不抛。
#
# 移植说明：来自 runtime/ssh-backend-w5（旧包名 com.example.agentkit）。
# 三处调整：
#  1. 前缀从 agentkit:// 改为 beichen://（App 已改名，此功能从未发布，无历史码要兼容）；
#  2. 考官包（zxing-core）不再依赖 /tmp 预置，改为「环境变量 QR_ZXING_JAR 优先，
#     否则按 Maven Central → 阿里云镜像顺序现取」，取不到则整段 SKIP 并说明；
#  3. 编译跑法统一成本仓的 kotlinc + -include-runtime jar。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

QR="core/src/main/java/com/beichen/polaris/core/qr/QrCode.kt"
CFG="core/src/main/java/com/beichen/polaris/core/qr/ConfigQr.kt"
SCREEN="app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt"
QRUI="app/src/main/java/com/beichen/polaris/ui/QrConfigSection.kt"

PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }
has() { if grep -qF -- "$3" "$2" 2>/dev/null; then pass "$1"; else fail "$1（缺：$3）"; fi; }

echo "== 静态：纪律与边界 =="

has "负载层不含密钥字段" "$CFG" "data class ConfigPayload"
if grep -q 'apiKey' "$QR" "$CFG" 2>/dev/null; then fail "二维码层零密钥引用"; else pass "二维码层零密钥引用"; fi
has "超容量如实抛出不截断" "$QR" "class QrTooLongException"
has "坏前缀宽容拒收" "$CFG" "if (!raw.startsWith(PREFIX)) return null"
has "坏编码宽容拒收" "$CFG" "IllegalArgumentException"
has "设置页有二维码入口" "$QRUI" "配置二维码"
has "导出弹窗渲染矩阵" "$QRUI" "QrImage(matrix = matrix"
has "导入只回填非敏感字段" "$SCREEN" "baseUrl = parsed.baseUrl"
has "导入失败给出提示" "$QRUI" "这不是本 App 的配置码"
if grep -qF 'parsed.apiKey' "$SCREEN" "$QRUI" 2>/dev/null; then fail "导入不携带 Key"; else pass "导入不携带 Key"; fi
# 前缀与产品名一致：码是给用户看的东西，界面上不存在的名字只会让人以为扫错了。
has "前缀用 beichen://" "$CFG" 'const val PREFIX = "beichen://config?v=1&d="'

echo "== 行为：编码器 × 解码库真解对拍 =="

if ! command -v kotlinc >/dev/null 2>&1; then
    echo "  SKIP  未找到 kotlinc，行为断言跳过（静态断言已全跑）"
else
    ZXING="${QR_ZXING_JAR:-}"
    if [ -z "$ZXING" ] || [ ! -f "$ZXING" ]; then
        ZXING="/tmp/zxing-core-3.5.3.jar"
        if [ ! -f "$ZXING" ]; then
            for u in \
                "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar" \
                "https://maven.aliyun.com/repository/public/com/google/zxing/core/3.5.3/core-3.5.3.jar" \
                "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/com/google/zxing/core/3.5.3/core-3.5.3.jar"
            do
                if curl -sSL --max-time 60 -o "$ZXING" "$u" 2>/dev/null && [ -s "$ZXING" ]; then
                    break
                fi
                rm -f "$ZXING"
            done
        fi
    fi

    if [ ! -f "$ZXING" ]; then
        echo "  SKIP  取不到考官包 zxing-core（离线环境）——编码器未被外部解码器验证"
        echo "        → 补法：QR_ZXING_JAR=/path/to/core-3.5.3.jar bash $0"
    else
        TMP=$(mktemp -d)
        cat > "$TMP/check.kt" <<'EOF'
import com.beichen.polaris.core.qr.ConfigQr
import com.beichen.polaris.core.qr.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.EnumMap

var pass = 0; var fail = 0
fun ok(c: Boolean, n: String) { if (c) { pass++; println("  PASS  $n") } else { fail++; println("  FAIL  $n") } }

/** 自写矩阵 → 解码库真解。解不出返回 null。 */
fun decode(m: QrCode.Matrix): String? = try {
    val w = m.size; val h = m.size
    val px = IntArray(w * h) { i -> if (m[i / w, i % w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    val source = RGBLuminanceSource(w, h, px)
    val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
    hints[DecodeHintType.PURE_BARCODE] = true
    hints[DecodeHintType.TRY_HARDER] = true
    QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text
} catch (e: Exception) { null }

fun levelName(l: Int) = if (l == 0) "L" else "M"

fun roundTrip(text: String, label: String, level: Int = 1) {
    val m = try { QrCode.encode(text, level) } catch (e: Exception) { null }
    val ver = if (m == null) "?" else ((m.size - 17) / 4).toString()
    val decoded = if (m == null) "THROW" else decode(m)
    ok(decoded == text, "对拍：$label（v=$ver, ${levelName(level)}）")
}

fun main() {
// 短文本（v1 最小码）
roundTrip("HELLO", "纯 ASCII 短文本")
// 典型配置负载（中文配置名）
val cfg = ConfigQr.export("openai", "https://api.deepseek.com/v1", "deepseek-chat", "深海")
ok(cfg.startsWith(ConfigQr.PREFIX), "负载：导出带标准前缀")
roundTrip(cfg, "典型配置负载（中文）")
roundTrip(cfg, "同负载 L 档", 0)
// 版本进阶：逼近 v9 边界的大负载
val mid = "A".repeat(180)
roundTrip(ConfigQr.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString(mid.toByteArray()), "大负载 180 字节（v8-v9 段）")
// 10 版本边界：16 位计数头（v10+ 才是 16 位），大负载走 L 档
val big = ConfigQr.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString("u=https://api.example.com/v1\nm=model-x\nn=n\n".toByteArray() + "B".repeat(230).toByteArray())
roundTrip(big, "大负载 250+ 字节（v10 边界，16 位计数头）", 0)
// M 档大负载（260 字节内，贴容量上限）
val mid2 = ConfigQr.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
    .encodeToString("u=https://api.example.com/v1\nm=model-x\nn=n\n".toByteArray() + "C".repeat(180).toByteArray())
roundTrip(mid2, "大负载 210 字节（M 档贴上限）", 1)
// 超容量如实抛
var threw = false
try { QrCode.encode("x".repeat(2000)) } catch (e: QrCode.QrTooLongException) { threw = true }
ok(threw, "超容量抛 QrTooLong 不截断")
// 负载层：往返
val p = ConfigQr.import(ConfigQr.export("claude", "https://api.anthropic.com", "claude-sonnet-4", "工作号"))
ok(p != null && p.providerId == "claude" && p.model == "claude-sonnet-4" && p.name == "工作号",
   "负载：导出导入往返无损")
// 负载层：坏输入宽容拒收
ok(ConfigQr.import("随便什么文本") == null, "负载：非本格式拒收")
ok(ConfigQr.import(ConfigQr.PREFIX + "!!!not-base64!!!") == null, "负载：坏编码拒收")
val broken = ConfigQr.export("openai", "https://x", "m", "n")
ok(ConfigQr.import(broken.substring(0, broken.length / 2)) == null, "负载：重度截断拒收")
ok(ConfigQr.import(ConfigQr.export("", "", "", "")) == null, "负载：空关键字段拒收")
ok(ConfigQr.import("agentkit://config?v=1&d=abc") == null, "负载：旧前缀不收（改名后只认 beichen://）")
println("RESULT: PASS=$pass FAIL=$fail")
if (fail > 0) kotlin.system.exitProcess(1)
}
EOF
        if kotlinc -cp "$ZXING" "$QR" "$CFG" "$TMP/check.kt" \
                -d "$TMP/check.jar" -include-runtime >"$TMP/compile.log" 2>&1; then
            OUT=$(java -cp "$TMP/check.jar:$ZXING" CheckKt 2>&1)
            echo "$OUT"
            echo "$OUT" | grep -q "FAIL=0" && pass "对拍与负载断言全过" || fail "对拍有红"
        else
            echo "编译失败："; grep -a 'error' "$TMP/compile.log" | head -8
            fail "二维码层编译"
        fi
        rm -rf "$TMP"
    fi
fi

echo
echo "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
