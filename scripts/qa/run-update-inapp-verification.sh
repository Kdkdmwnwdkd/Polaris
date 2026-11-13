#!/usr/bin/env bash
# 应用内更新链路的结构与行为断言（纯 shell，不依赖 kotlinc / 模拟器）。
#
# 为什么用源码断言而不是单元测试：
#   下载器与安装器的输入是 Context + 文件系统 + 系统安装器，纯 JVM 起不来；
#   真机/模拟器端到端由 run 脚本之外的设备验证覆盖（见 docs/ops/DEVICE-VERIFICATION-PENDING.md）。
#   这里守的是「容易在后续重构中被悄悄改回去」的几处关键约定：
#     · 下载地址必须指向 CI 真正产出的资产名（曾经写死 app-debug.apk，CI 换名后 404）
#     · 主路径必须留在应用内（不允许又出现「点更新 → 跳浏览器」）
#     · 校验四层不能少（长度 / ZIP 魔数 / 包名+版本号 / SHA-256 来源认证）
#     · FileProvider 路径必须与下载落盘目录一致（漏登记 = 安装器打不开 content://）
set -uo pipefail
cd "$(dirname "$0")/../.."

APK_DL="core/src/main/java/com/beichen/polaris/core/update/ApkDownloader.kt"
CHECKER="core/src/main/java/com/beichen/polaris/core/update/UpdateChecker.kt"
ABOUT="app/src/main/java/com/beichen/polaris/ui/AboutScreen.kt"
INSTALLER="app/src/main/java/com/beichen/polaris/update/UpdateInstaller.kt"
PATHS="app/src/main/res/xml/file_paths.xml"
MANIFEST="app/src/main/AndroidManifest.xml"

npass=0; fail=0
good() { npass=$((npass+1)); echo "  OK   $1"; }
bad()  { fail=$((fail+1)); echo "  FAIL $1"; }

need_file() {
  [ -f "$1" ] || { bad "缺少文件：$1"; echo "应用内更新：$npass 通过 / $fail 失败"; exit 1; }
}
for f in "$APK_DL" "$CHECKER" "$ABOUT" "$INSTALLER" "$PATHS" "$MANIFEST"; do need_file "$f"; done

echo "[1] 下载地址与资产名"
grep -q 'app-release\.apk' "$CHECKER" && good "默认资产名是 app-release.apk" \
  || bad "默认资产名不是 app-release.apk（CI 产物已改名，写死旧名会 404）"
grep -q 'releases/download/latest/app-debug\.apk' "$CHECKER" \
  && bad "仍残留 app-debug.apk 下载地址（该资产已被 CI 删除）" \
  || good "没有残留 app-debug.apk 地址"
grep -q 'optJSONArray("assets")' "$CHECKER" && good "资产名从 Release 接口读取（可跟随改名）" \
  || bad "资产名仍写死，接口换名后会拿到 404"

echo "[2] 主路径留在应用内"
grep -q 'ApkDownloader\.download(' "$ABOUT" && good "关于页调用应用内下载器" \
  || bad "关于页没有调用 ApkDownloader"
grep -q 'UpdateInstaller\.install(' "$ABOUT" && good "下载完成后直接交给系统安装器" \
  || bad "下载完成后没有调用 UpdateInstaller"
n_open=$(grep -c 'openUrl(ctx, UpdateChecker.DOWNLOAD_URL)' "$ABOUT" || true)
[ "${n_open:-0}" -le 1 ] && good "浏览器下载只保留一处兜底（实际 ${n_open:-0} 处）" \
  || bad "浏览器下载出现 ${n_open} 处，主路径可能又跳出去了"
# 兜底必须挂在 Failed 分支：出现「有新版本」时直接给浏览器入口就算回归
awk '/is UpdateState\.Failed ->/{seen=1} /openUrl\(ctx, UpdateChecker\.DOWNLOAD_URL\)/{if(!seen) exit 1}' "$ABOUT" \
  && good "浏览器兜底只在失败分支出现" \
  || bad "浏览器下载入口不在失败分支（有新版时仍会跳浏览器）"

echo "[3] 下载完整性校验"
grep -q 'written != total' "$APK_DL" && good "按 Content-Length 校验完整性" \
  || bad "缺少长度完整性校验（会接受被截断的包）"
grep -q "'P'.code.toByte()" "$APK_DL" && good "校验 ZIP 魔数（挡 HTML 错误页）" \
  || bad "缺少 ZIP 魔数校验（镜像挂掉时会下到一张网页）"
grep -q 'archive.packageName != context.packageName' "$APK_DL" && good "校验包名与本机一致" \
  || bad "缺少包名校验（可能把别的应用装进来）"
grep -q 'code != expectedVersionCode' "$APK_DL" && good "校验包内 versionCode 与目标一致" \
  || bad "缺少 versionCode 校验（可能复用错版本的缓存）"
grep -q 'MIN_APK_BYTES' "$APK_DL" && good "拦截明显不完整的极小文件" \
  || bad "缺少最小体积下限"

# 前四层都是「内容自证」：包名和 versionCode 用 apktool 就能改，镜像照样能全过。
# 只有 SHA-256 能证明「这份字节就是 CI 发的那份」。
grep -q 'optString("digest")' "$CHECKER" && good "从 Release 接口解析资产 digest" \
  || bad "没解析 digest，无法做来源认证"
grep -q 'substringAfter("sha256:"' "$CHECKER" && good "digest 按 sha256: 前缀解析" \
  || bad "digest 格式解析缺失"
grep -q 'MessageDigest.getInstance("SHA-256")' "$APK_DL" && good "下载时流式计算 SHA-256（不二次读盘）" \
  || bad "没有计算 SHA-256"
grep -q 'equals(expectedSha256' "$APK_DL" && good "哈希不符则拒绝并换源" \
  || bad "缺少哈希比对（下载内容可被镜像替换）"
grep -q 'else listOf("")' "$APK_DL" && good "无 digest 时只允许直连源（不走第三方镜像）" \
  || bad "无 digest 时仍会用镜像，供应链风险未关闭"

echo "[4] 失败可恢复"
grep -q 'MIRROR_PREFIXES' "$APK_DL" && grep -q '"https://ghfast.top/"' "$APK_DL" \
  && good "多下载源，含加速通道" || bad "缺少多下载源回退"
awk '/MIRROR_PREFIXES = listOf/,/^    \)/' "$APK_DL" | grep -qE '^\s*""' \
  && good "保留直连作为最后一跳" || bad "没有直连兜底"
grep -q 'ensureActive()' "$APK_DL" && good "下载循环响应协程取消" \
  || bad "缺少取消检查（点取消后仍会写完整包）"
grep -q 'part.delete()' "$APK_DL" && good "失败/取消清理半成品" || bad "失败后不清理 .part"

echo "[5] 安装链路"
grep -q 'canRequestPackageInstalls' "$INSTALLER" && good "安装前检查「安装未知应用」授权" \
  || bad "缺少未知来源授权检查（O+ 会直接失败）"
grep -q 'ACTION_MANAGE_UNKNOWN_APP_SOURCES' "$INSTALLER" && good "未授权时跳对应设置页" \
  || bad "未授权时没有引导"
grep -q 'application/vnd.android.package-archive' "$INSTALLER" && good "用系统安装器 MIME 拉起" \
  || bad "没有用 package-archive MIME"
grep -q 'cache-path name="updates" path="updates/"' "$PATHS" && good "FileProvider 已登记 updates/ 目录" \
  || bad "FileProvider 未登记下载目录（content:// 会抛 IllegalArgumentException）"
grep -q 'REQUEST_INSTALL_PACKAGES' "$MANIFEST" && good "清单声明 REQUEST_INSTALL_PACKAGES" \
  || bad "清单缺少 REQUEST_INSTALL_PACKAGES"

echo "[6] 不留旧行为"
grep -q 'text = "去下载"' "$ABOUT" && bad "仍存在旧「去下载」按钮文案" \
  || good "旧的「去下载」入口已移除"
grep -q 'UpdateState.Downloading' "$ABOUT" && good "有下载中状态（进度可取消）" \
  || bad "没有下载中状态，用户看不到进度"

echo ""
if [ "$fail" -eq 0 ]; then
  echo "应用内更新：$npass 通过 / 0 失败"
  echo "FAIL=0"
  exit 0
else
  echo "应用内更新：$npass 通过 / $fail 失败"
  exit 1
fi
