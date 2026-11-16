#!/usr/bin/env bash
# 生成 ModelSpec 需要内嵌的 sha256（D-7 的运维动作，不是测试）。
#
# 用法：
#   scripts/tools/model-hashes.sh ~/Downloads/release-assets
#
# 目录里应放 latest Release 上那几个 zip（asr-fast.zip / tts-matcha.zip …）与
# model-manifest.txt。脚本做两件事：
#   1. 算每个 zip 的真实 sha256；
#   2. 与清单里的值比对——**不一致就大声报出来**，因为那意味着清单本身有问题，
#      或者你手上的文件和线上不是同一份。
#
# 输出直接是可以粘进 ModelSpec 的 `sha256 = "…"`。
#
# 为什么要有这个脚本：哈希是「一次性填、长期不管」的数据，手抄 64 位十六进制
# 一定会错一位，而错了的表现是「用户下载到 99% 报校验失败」——最难查的一类故障。
set -uo pipefail

DIR="${1:-}"
if [ -z "$DIR" ] || [ ! -d "$DIR" ]; then
  echo "用法：$0 <存放 release 资产的目录>" >&2
  exit 2
fi

MANIFEST="$DIR/model-manifest.txt"
have_manifest=0
[ -f "$MANIFEST" ] && have_manifest=1

echo "目录：$DIR"
[ "$have_manifest" -eq 1 ] && echo "清单：找到 model-manifest.txt" || echo "清单：未找到（只算哈希，不做比对）"
echo

ok=0
bad=0
none=0

# ModelSpec 里的 zipName 名单。新增模型时这里与 ModelSpec 一起改。
for zip in asr-fast.zip asr-accurate.zip tts-matcha.zip rootfs-alpine.zip shizuku.zip tts-zipvoice.zip; do
  f="$DIR/$zip"
  if [ ! -f "$f" ]; then
    echo "  --  $zip：文件不存在"
    none=$((none + 1))
    continue
  fi
  actual=$(sha256sum "$f" | cut -d' ' -f1)
  size=$(wc -c <"$f" | tr -d ' ')
  if [ "$have_manifest" -eq 1 ]; then
    # 清单每行：资产名 字节 sha256
    line=$(grep -E "^$zip[[:space:]]" "$MANIFEST" | head -1)
    if [ -z "$line" ]; then
      echo "  ??  $zip：清单里没有这一行"
      bad=$((bad + 1))
      continue
    fi
    declared_size=$(echo "$line" | awk '{print $2}')
    declared_sha=$(echo "$line" | awk '{print $3}')
    if [ "$declared_sha" = "$actual" ] && [ "$declared_size" = "$size" ]; then
      echo "  OK  $zip  sha256=\"$actual\""
      ok=$((ok + 1))
    else
      echo "  !!  $zip：清单与文件不一致"
      echo "        清单  size=$declared_size sha=$declared_sha"
      echo "        实际  size=$size sha=$actual"
      bad=$((bad + 1))
    fi
  else
    echo "  --  $zip  sha256=\"$actual\""
    ok=$((ok + 1))
  fi
done

echo
echo "一致 $ok / 不一致 $bad / 缺失 $none"
[ "$bad" -eq 0 ] || {
  echo
  echo "有资产与清单不一致：不要直接填进 ModelSpec。先弄清楚线上到底是哪一份。" >&2
  exit 1
}
