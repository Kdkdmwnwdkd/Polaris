#!/usr/bin/env bash
# =============================================================================
# 拉取 x86_64 容器测试套件（仅本地/模拟器调试用，不进 git）
#
# 为什么需要：正式包只带 arm64 的 rootfs + proot，x86_64 模拟器上容器页
# 一进去就是「proot 不可用」，容器相关改动只能靠 JVM 断言，端到端永远验不了。
#
# 产出（都在 app/src/debugX86/assets，已被 .gitignore 排除）：
#   containers/alpine-x86_64.tar   Alpine 3.20.3 minirootfs（x86_64）
#   bin/x86_64/proot               Termux 官方构建（Bionic 链接，可在 Android 跑）
#   bin/x86_64/libtalloc.so.2      proot 的 NEEDED
#   bin/x86_64/libandroid-shmem.so proot 的 NEEDED
#
# 用法：
#   ./scripts/fetch-x86-testkit.sh
#   /opt/gradle-8.9/bin/gradle -PnoNative=true -Px86Rootfs=true :app:assembleDebug
#
# 注意：即使套件齐全，proot 在 x86_64 **模拟器**上仍可能 execve 失败
#（Termux 构建的 proot 对模拟器内核/可执行文件上下文敏感）。这套件主要用于
# 验证 UI 与「发行版识别 / 版本判定 / 解压」链路；容器真正跑命令仍需 arm64 真机。
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
DEST=app/src/debugX86/assets
MIRROR="${ALPINE_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/alpine}"
ALPINE_VER=3.20.3
TERMUX=https://packages.termux.dev/apt/termux-main

# proot 的包名带版本号，上游一升级旧名就 404——2026-11 就踩过（.100 → .102），
# 表现是脚本第一行 curl 失败、`set -e` 直接退出，看起来像网络问题。
# 改成先列目录取最新；列不出来再回退到一个已知可用的版本。
PROOT_VER="${PROOT_VER:-$(curl -fsSL --max-time 30 "$TERMUX/pool/main/p/proot/" 2>/dev/null \
  | grep -oE 'proot_[0-9.]+_x86_64\.deb' | sed 's/^proot_//; s/_x86_64\.deb$//' \
  | sort -V | tail -1)}"
PROOT_VER="${PROOT_VER:-5.1.107.102}"
PROOT_DEB="pool/main/p/proot/proot_${PROOT_VER}_x86_64.deb"
TALLOC_DEB=pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb
SHMEM_DEB=pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb
echo "proot 版本：$PROOT_VER"

mkdir -p "$DEST/containers" "$DEST/bin/x86_64"

echo "[1/3] Alpine $ALPINE_VER minirootfs (x86_64)"
if [ -f "$DEST/containers/alpine-x86_64.tar" ]; then
  echo "  已存在，跳过"
else
  tmp=$(mktemp)
  curl -fsSL --max-time 300 \
    "$MIRROR/v3.20/releases/x86_64/alpine-minirootfs-$ALPINE_VER-x86_64.tar.gz" -o "$tmp"
  gunzip -c "$tmp" > "$DEST/containers/alpine-x86_64.tar"
  rm -f "$tmp"
  echo "  $(du -h "$DEST/containers/alpine-x86_64.tar" | cut -f1)"
fi

echo "[2/3] Termux proot / libtalloc / libandroid-shmem (x86_64)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for spec in "proot:$PROOT_DEB" "talloc:$TALLOC_DEB" "shmem:$SHMEM_DEB"; do
  name=${spec%%:*}; path=${spec#*:}
  [ -f "$work/$name.deb" ] || curl -fsSL --max-time 180 "$TERMUX/$path" -o "$work/$name.deb"
  mkdir -p "$work/$name"
  (cd "$work/$name" && ar x "../$name.deb" && tar -xf data.tar.xz)
done

cp "$work/proot/data/data/com.termux/files/usr/bin/proot" "$DEST/bin/x86_64/proot"
cp "$work/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0" "$DEST/bin/x86_64/libtalloc.so.2"
cp "$work/shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" "$DEST/bin/x86_64/libandroid-shmem.so"
chmod 755 "$DEST/bin/x86_64/proot"

echo "[3/3] 校验"
file "$DEST/bin/x86_64/proot" | grep -q "x86-64" || { echo "proot 架构不对" >&2; exit 1; }
tar -xOf "$DEST/containers/alpine-x86_64.tar" ./etc/alpine-release | sed 's/^/  alpine-release: /'
echo "  完成：$DEST"
