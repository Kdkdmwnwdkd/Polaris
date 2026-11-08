#!/usr/bin/env bash
# 本地参数输入框 harness（上下文 / 线程 / 单次上限）。
#
# 起因：2026-10-29 真机录屏——上下文卡在 16384，怎么删都删不掉、打什么都跳回原值，
# 三个参数连同 GPU 一起没法调，整台机器测不了。
#
# 根因不是输入法，是「受控输入 + setter coerceIn」：输入框 value 直连设置层，
# 每按一个键就把半截数字送进 setter，再拿 coerce 后的值覆盖输入框。
# 于是打「2」被抬成 512、打「2048」变成 5120……永远拼不出目标值。
#
# 这类 bug 不崩、编译期完全静默，只能靠结构断言锁住；再补一段机理复算，
# 证明「逐字符回写」必然拼不出目标值——防止以后有人「顺手简化」回受控写法。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

SCREEN=app/src/main/java/com/beichen/polaris/ui/ModelConfigScreen.kt
SETTING=core/src/main/java/com/beichen/polaris/core/llm/local/LocalLlamaSetting.kt

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

for f in "$SCREEN" "$SETTING"; do
    [ -f "$f" ] || { echo "  FAIL  缺文件 $f"; exit 1; }
done

# 只取函数体：从函数头打印到第一个「行首」右括号。
# 函数体内部的括号都带缩进，不会误停；这样断言不会命中文件里别处的同名写法。
BODY=$(awk '/^private fun LocalParamField\(/{f=1} f{print} f&&/^}$/{exit}' "$SCREEN")
if [ -z "$BODY" ]; then
    echo "  FAIL  没找到 LocalParamField（改名了？断言要跟着改）"
    exit 1
fi

has() { printf '%s' "$1" | grep -qF -- "$2" && pass "$3" || fail "$3（缺：$2）"; }
hasnt() { printf '%s' "$1" | grep -qF -- "$2" && fail "$3（不该有：$2）" || pass "$3"; }
# 直接读文件。超过 ~100KB 的源文件塞进变量再进管道会丢尾巴（本轮踩过：
# 文案明明在文件里，断言却报缺），文件小的时候看不出差别，所以一律走这个。
hasf() { grep -qF -- "$2" "$1" && pass "$3" || fail "$3（$1 里缺：$2）"; }

echo "== 静态：输入框的编辑模型 =="

has "$BODY" 'remember { mutableStateOf(value) }' \
    '文本态放在本地，不直接受控'
has "$BODY" 'LaunchedEffect(value, focused)' \
    '外部值（含 coerce 结果）靠 LaunchedEffect 回流'
has "$BODY" 'if (!focused && text != value)' \
    '编辑中不覆盖输入框（这是打断输入的元凶）'
has "$BODY" 'if (digits.isNotEmpty()) onValue(digits)' \
    '输入过程实时落盘，不用等失焦'
has "$BODY" 'onFocusChanged' \
    '离开字段时校正显示'
has "$BODY" 'state.hasFocus' \
    '父节点看 hasFocus（它自己的 isFocused 恒为 false）'
hasnt "$BODY" 'value = value,' \
    '没有把 value 直连给 AkTextField（受控写法）'
has "$BODY" 'it.isDigit()' \
    '输入过滤非数字'
hasnt "$BODY" 'Text(' \
    'label 只由 AkTextField 画一次（原来画了两遍，真机可见重复标题）'

echo "== 静态：范围约束与文案 =="

hasf "$SETTING" 'coerceIn(512, 16384)' '上下文仍限制 512~16384'
hasf "$SETTING" 'coerceIn(1, 8)' '线程仍限制 1~8'
hasf "$SETTING" 'coerceIn(64, 8192)' '单次上限仍限制 64~8192'

CODES=$(grep -c 'LocalParamField(' "$SCREEN")
[ "$CODES" = "4" ] && pass '三个参数行都在用 LocalParamField（1 处定义 + 3 处调用）' \
    || fail "LocalParamField 调用点数量变了：$CODES（应为 4）"

hasf "$SCREEN" '下一条消息发出时自动重载生效' \
    'GPU 文案改说「下一条消息」而不是「下次加载」'

echo "== 行为：逐字符回写为什么拼不出 2048 =="

coerce_ctx() {
    local v=$1
    [ "$v" -lt 512 ] && v=512
    [ "$v" -gt 16384 ] && v=16384
    printf '%s' "$v"
}

# 旧写法：从 16384 开始，用户想改成 2048。先删空——但空串被 toIntOrNull 丢弃，
# 输入框仍是 16384，于是下一个键变成「追加」。四个键全变成追加。
old=16384
for c in 2 0 4 8; do
    old=$(coerce_ctx $((old * 10 + c)))
done
if [ "$old" = 16384 ]; then
    pass '旧写法：输入 2048 后仍停在 16384（复现真机现象）'
else
    fail "旧写法复现失败：得到 $old"
fi

# 新写法：本地累积到 2048，一次性提交。
new=$(coerce_ctx 2048)
[ "$new" = 2048 ] && pass '新写法：一次提交得到 2048' || fail "新写法没得到 2048：$new"

# 改了输入方式，不能顺带绕过范围约束。
[ "$(coerce_ctx 100)" = 512 ] && pass '新写法仍把 100 纠正成 512' || fail '100 没被纠正成 512'
[ "$(coerce_ctx 99999)" = 16384 ] && pass '新写法仍把 99999 截到 16384' || fail '99999 没被截断'

echo
echo "SUMMARY：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
