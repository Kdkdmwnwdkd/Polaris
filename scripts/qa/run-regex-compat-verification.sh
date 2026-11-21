#!/usr/bin/env bash
# Android 正则兼容性判据（ICU 花括号严格性）。
#
# 为什么需要它：`core/PromptVariables.kt` 的占位正则原来写成
#   Regex("""\{\{\s*([^{}\n]+?)\s*}}""")
# 这在 JVM 上完全合法——OpenJDK 的 Pattern 把孤立的 `}` 当字面量。core 的单测
# 因此全绿。但 Android 的 java.util.regex.Pattern 底下是 ICU4C，对花括号配对
# 要求严格：孤立的 `}` 直接抛 PatternSyntaxException。
#
# 而它写在文件顶层 `val` 的初始化式里，异常被包成 ExceptionInInitializerError，
# **类加载即崩**，try/catch 接不住。2026-11-21 真机上点「快捷指令」闪退就是它。
#
# 实测（API 30，dalvikvm 探针，见 docs/ops/DEVICE-VERIFICATION-PENDING.md）：
#   \{\{...\s*}}   → PatternSyntaxException: Syntax error near index 23
#   \{\{...\s*\}\} → OK
#   a{             → Error in {min,max} interval
#   a]             → OK（右方括号不严格）
#   a{2,3}         → OK
#   x[}]y          → OK（字符类内的花括号安全）
#
# 所以规则只有一条，但必须精确：**字符类外，`{` 必须是合法量词的开头或已转义，
# `}` 必须与这样一个 `{` 配对或已转义。**
#
# 这条判据是静态的（不需要 Android 运行时），因此能进 CI 防回归。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1
ROOT=$(pwd)

p=0; f=0; s=0
pass() { echo "  PASS  $*"; p=$((p + 1)); }
fail() { echo "  FAIL  $*"; f=$((f + 1)); }
skip() { echo "  SKIP  $*"; s=$((s + 1)); }

echo "[A] 提取：仓库里所有 Regex 字面量"

# 提取与判定都放在 Python 里：字符串拼接（"""...""" + """..."""）与字符类追踪
# 用 sed/grep 做不干净，误报会让人不信这条判据。
OUT=$(python3 - <<'PY'
import os, re, json, sys

ROOTS = ['core/src/main', 'app/src/main']
LIT = r'"""(?:.*?)"""|"(?:[^"\\]|\\.)*"'
CALL = re.compile(r'Regex\(\s*((?:%s)(?:\s*\+\s*(?:%s))*)' % (LIT, LIT), re.S)
PART = re.compile(LIT, re.S)

def unescape(s, raw):
    if raw:
        return s
    out = []
    i = 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            c = s[i + 1]
            out.append({'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\', '$': '$'}.get(c, c))
            i += 2
        else:
            out.append(s[i]); i += 1
    return ''.join(out)

def bad_braces(pat):
    """模拟 ICU 的花括号规则；返回问题描述或 None。"""
    i, n, in_class = 0, len(pat), False
    while i < n:
        c = pat[i]
        if c == '\\':
            i += 2
            continue
        if in_class:
            if c == ']':
                in_class = False
            i += 1
            continue
        if c == '[':
            in_class = True
            i += 1
            continue
        if c == '{':
            m = re.match(r'\{\d+(,\d*)?\}', pat[i:])
            if not m:
                return "未转义的 '{' 在 index %d（ICU 要求 {n,m} 量词或 \\{）" % i
            i += m.end()
            continue
        if c == '}':
            return "未转义的 '}' 在 index %d（ICU 不接受孤立的右花括号）" % i
        i += 1
    return None

found, skipped, bad = [], [], []
for root in ROOTS:
    for dp, _, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.kt'):
                continue
            path = os.path.join(dp, fn)
            src = open(path, encoding='utf-8').read()
            for m in CALL.finditer(src):
                body = m.group(1)
                pieces, tpl = [], False
                for pm in PART.finditer(body):
                    raw = pm.group(0).startswith('"""')
                    text = pm.group(0)[3:-3] if raw else pm.group(0)[1:-1]
                    if '$' in text:
                        tpl = True
                    pieces.append(unescape(text, raw))
                line = src[:m.start()].count('\n') + 1
                if tpl:
                    skipped.append((path, line))
                    continue
                pat = ''.join(pieces)
                found.append((path, line, pat))
                why = bad_braces(pat)
                if why:
                    bad.append((path, line, pat, why))

print("COUNT %d %d" % (len(found), len(skipped)))
for path, line, pat, why in bad:
    print("BAD\t%s:%d\t%s\t%s" % (path, line, why, pat.replace('\n', '\\n')[:100]))
PY
)

N=$(echo "$OUT" | awk '/^COUNT/{print $2}')
NSKIP=$(echo "$OUT" | awk '/^COUNT/{print $3}')
BADLINES=$(echo "$OUT" | grep '^BAD' || true)

if [ -z "${N:-}" ]; then
  fail "A0 提取器没有输出（Python 不可用或脚本损坏）"
else
  pass "A1 扫描到 $N 条正则可静态判定（$NSKIP 条含 \$ 模板拼接，需人工看）"
fi

echo ""
echo "[B] ICU 花括号规则：字符类外必须成对或已转义"

if [ -z "$BADLINES" ]; then
  pass "B1 $N 条全部通过（无孤立花括号）"
else
  echo "$BADLINES" | while IFS=$'\t' read -r _ loc why sample; do
    echo "  FAIL  B2 $loc —— $why"
    echo "        正则：$sample"
  done
  f=$((f + $(echo "$BADLINES" | wc -l)))
fi

echo ""
echo "[C] 反向自检：判据本身不能永远绿"

# 判据必须能抓住「已知坏样本」。没有这一段，正则写错（比如把 in_class 逻辑写反）
# 会让整条判据永远 PASS，而真机照崩——这类「永远绿的门禁」比没有门禁更糟。
CHECK=$(python3 - <<'PY'
import re
BAD = [
    r'\{\{\s*([^{}\n]+?)\s*}}',   # 真实事故：PromptVariables 原写法
    r'a{',                        # 裸左花括号（ICU: Error in {min,max} interval）
    r'a}b',                       # 裸右花括号
]
GOOD = [
    r'\{\{\s*([^{}\n]+?)\s*\}\}',  # 修复写法
    r'a{2,3}',                     # 合法量词
    r'\d{2,}',                     # 开区间量词
    r'x[}]y',                      # 字符类内的花括号安全
    r'a\]',                        # 右方括号不严格
]

def bad_braces(pat):
    i, n, in_class = 0, len(pat), False
    while i < n:
        c = pat[i]
        if c == '\\':
            i += 2
            continue
        if in_class:
            if c == ']':
                in_class = False
            i += 1
            continue
        if c == '[':
            in_class = True
            i += 1
            continue
        if c == '{':
            m = re.match(r'\{\d+(,\d*)?\}', pat[i:])
            if not m:
                return "brace"
            i += m.end()
            continue
        if c == '}':
            return "brace"
        i += 1
    return None

bad_ok = sum(1 for x in BAD if bad_braces(x) is not None)
good_ok = sum(1 for x in GOOD if bad_braces(x) is None)
print("%d %d %d %d" % (bad_ok, len(BAD), good_ok, len(GOOD)))
PY
)
read -r BOK BTOT GOK GTOT <<< "$CHECK"

if [ "${BOK:-0}" = "$BTOT" ] && [ "$BTOT" -gt 0 ]; then
  pass "C1 已知坏样本必须判红（$BOK/$BTOT）"
else
  fail "C1 判据抓不住已知坏样本（$BOK/$BTOT）—— 这条判据本身坏了"
fi

if [ "${GOK:-0}" = "$GTOT" ] && [ "$GTOT" -gt 0 ]; then
  pass "C2 已知好样本必须判绿（$GOK/$GTOT）"
else
  fail "C2 判据误伤已知好样本（$GOK/$GTOT）—— 会逼人放宽规则直到形同虚设"
fi

echo ""
echo "[D] 回归锁：这次的事故点原文还在不在"

if grep -qF 'Regex("""\{\{\s*([^{}\n]+?)\s*\}\}""")' core/src/main/java/com/beichen/polaris/core/PromptVariables.kt; then
  pass "D1 PromptVariables 的占位正则已转义右花括号"
else
  fail "D1 PromptVariables 的占位正则被改回去了（或又变回未转义写法）"
fi

echo ""
if [ "$f" -eq 0 ]; then
  echo "正则兼容：$p 通过 / 0 失败${s:+（$s 跳过）}"
  exit 0
else
  echo "正则兼容：$p 通过 / $f 失败"
  exit 1
fi
