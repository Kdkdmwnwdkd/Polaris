#!/usr/bin/env bash
# AppGraph harness（静态结构对账，不依赖 Android SDK）。
#
# 盯的是「全局状态清单是不是真的」：`AppGraph.globals` 声称自己列出了应用里
# 全部 24 条跨层通道。这个声称有两个方向的失效方式，且都不会报错：
#
#   ① 漏登记——新增一个带可变状态的 object，清单没跟上，于是下次有人
#      「照着 AppGraph 清场」时那条通道带着上一轮的值进入下一轮；
#   ② 假登记——清单里写了个名字，但源码里没有对应 object，或那个 object
#      根本没声明 reset()。看着齐全，resetAll 一跑才发现清不掉。
#
# 两个方向都必须硬断言。审查报告里「新增一条通道没有地方登记，只能靠 grep 找」
# 这句判断，只有在反查成立时才真的被修掉。
#
# 另外三条**语义**约束（防止 reset 变成第二个 bug 源）：
#   C2 reset 不得写 SharedPreferences（一次测试清场抹掉用户设置）
#   C3 reset 不得删文件（清理 ≠ 删除用户数据）
#   C1 每个 reset 的 KDoc 必须交代用途（空方法不留理由，下一个人只能猜）
set -uo pipefail
cd "$(dirname "$0")/../.."

python3 - <<'PY'
import re
import sys
import pathlib

# 3.5 拆模块：登记的对象分布在 :app 与 :core 两个模块，扫描必须覆盖两边。
# 路径归一化统一走 rel()，否则 relative_to 会因为根不同而抛 ValueError。
APP_ROOT = pathlib.Path("app/src/main/java")
CORE_ROOT = pathlib.Path("core/src/main/java")
ROOTS = [APP_ROOT, CORE_ROOT]
GRAPH = APP_ROOT / "com/beichen/polaris/AppGraph.kt"


def rel(f):
    for r in ROOTS:
        try:
            return f.relative_to(r)
        except ValueError:
            continue
    return f

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}" + (f" → {detail}" if detail else ""))


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


print("=== AppGraph：全局状态清单对账 ===")

# ── 清单解析 ──────────────────────────────────────────────────────────────
src = read(GRAPH)
chunks = re.split(r'\bname\s*=\s*"', src)[1:]
entries = []
for c in chunks:
    name = c.split('"', 1)[0]
    pkg = re.search(r'packageName\s*=\s*"([^"]+)"', c)
    link = re.search(r'link\s*=\s*"([^"]*)"', c)
    why = re.search(r'why\s*=\s*"((?:[^"\\]|\\.)*)"', c, re.S)
    entries.append({
        "name": name,
        "pkg": pkg.group(1) if pkg else "",
        "link": link.group(1) if link else "",
        "resettable": not re.search(r'resettable\s*=\s*false', c),
        "why": why.group(1).strip() if why else "",
    })

print(f"\n[A] 清单本体（解析到 {len(entries)} 条）")

# 26 = 25 + 1：桌面助手引入 PetState（设置页开关 ↔ 悬浮窗 Service 的启停）。
# 数字变化必须有对应新增，不能为了让它变绿而调数字。
check("A1 清单条数为 26", len(entries) == 26, f"实际 {len(entries)}")

names = [e["name"] for e in entries]
dups = sorted({n for n in names if names.count(n) > 1})
check("A2 清单内无重名", not dups, f"重复：{dups}")

bad_link = [e["name"] for e in entries if len(e["link"]) < 8]
check("A3 每条都写了「谁在读写它」", not bad_link, f"空/过短：{bad_link}")

bad_why = [e["name"] for e in entries if not e["resettable"] and not e["why"]]
check("A4 resettable=false 必须写明理由", not bad_why, f"缺理由：{bad_why}")

# ── 源码索引：object 声明位置 + 是否带可变成员 ────────────────────────────
obj_re = re.compile(r'^object\s+([A-Za-z_]\w*)\s*[:{]')
member_var_re = re.compile(
    r'^(?:\s{4})(?:@\w+\s+)*'
    r'(?:(?:private|internal|protected)\s+)?'
    r'(?:(?:@\w+\s+)*)'
    r'(?:lateinit\s+)?var\s+([A-Za-z_]\w*)'
)

objects = {}       # name -> {"file": Path, "has_var": bool}
for f in (p for r in ROOTS for p in r.rglob("*.kt")):
    lines = read(f).splitlines()
    i = 0
    while i < len(lines):
        m = obj_re.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        depth = lines[i].count("{") - lines[i].count("}")
        j = i + 1
        has_var = False
        while j < len(lines) and depth > 0:
            depth += lines[j].count("{") - lines[j].count("}")
            if member_var_re.match(lines[j]) and depth >= 1:
                has_var = True
            j += 1
        objects[name] = {"file": f, "has_var": has_var}
        i = j

print(f"\n[B] 双向对账（源码里带可变成员的 object：{sum(1 for o in objects.values() if o['has_var'])} 个）")

missing_obj = [e["name"] for e in entries if e["name"] not in objects]
check("B1 每条登记都对应真实 object", not missing_obj, f"源码找不到：{missing_obj}")

wrong_pkg = []
for e in entries:
    o = objects.get(e["name"])
    if not o:
        continue
    actual = rel(o["file"].parent).as_posix().replace("/", ".")
    if e["pkg"] and actual != e["pkg"]:
        wrong_pkg.append(f"{e['name']}: 写 {e['pkg']}，实际 {actual}")
check("B2 packageName 与实际路径一致", not wrong_pkg, "; ".join(wrong_pkg))

no_reset = []
for e in entries:
    o = objects.get(e["name"])
    if not o:
        continue
    if not re.search(r'^\s*fun reset\s*\(', read(o["file"]), re.M):
        no_reset.append(e["name"])
check("B3 每条登记的对象都声明了 reset()", not no_reset, f"缺 reset：{no_reset}")

registered = set(names)
unregistered = sorted(n for n, o in objects.items() if o["has_var"] and n not in registered)
check("B4 源码里带可变成员的 object 全部已登记", not unregistered, f"漏登记：{unregistered}")

# ── 边界：清单不得接管生产生命周期 ────────────────────────────────────────
print("\n[C] 边界（不接管生产）")

refs = []
for f in (p for r in ROOTS for p in r.rglob("*.kt")):
    if f == GRAPH:
        continue
    for ln, line in enumerate(read(f).splitlines(), 1):
        stripped = line.strip()
        # 注释里提到 AppGraph 是**说明**，不是引用。KDoc 链接 `[AppGraph.resetAll]`
        # 正是我们要求每个 reset 都写的，把它当违规会让这条断言自我矛盾。
        if stripped.startswith(("*", "//", "/*")):
            continue
        if re.search(r'\bAppGraph\b', line):
            refs.append(f"{rel(f)}:{ln}")
check("C1 生产代码零引用 AppGraph（只有清单自己）", not refs, f"引用点：{refs[:5]}")

check("C2 AppGraph 不在 core/ 下（否则分层守卫会红）",
      "core" not in GRAPH.relative_to(APP_ROOT).parts)

only_in_graph = [
    str(f) for f in (p for r in ROOTS for p in r.rglob("*.kt"))
    if f != GRAPH and re.search(r'\bAppGraph\s*\.\s*resetAll\s*\(', read(f))
]
check("C3 resetAll 只在清单内部被调用", not only_in_graph, f"调用点：{only_in_graph}")

# ── 语义约束：reset 不得有副作用 ──────────────────────────────────────────
print("\n[D] reset 的语义约束")

prefs_write = []
del_files = []
no_doc = []
for e in entries:
    o = objects.get(e["name"])
    if not o:
        continue
    text = read(o["file"])
    m = re.search(r'(/\*\*(?:[^*]|\*(?!/))*\*/\s*)?fun reset\s*\(\s*\)\s*\{', text)
    if not m:
        continue
    # KDoc：匹配到的可选组；没有 KDoc 时这个组是 None
    doc = m.group(1) or ""
    if "AppGraph" not in doc and "no-op" not in doc:
        no_doc.append(e["name"])
    # 函数体：从 { 开始配平
    start = m.end() - 1
    depth = 0
    end = start
    for k in range(start, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                end = k
                break
    body = text[start:end + 1]
    # 去掉注释行再判：reset 里写「这里刻意不写回 prefs」是说明，不是违规。
    # 不排除的话，一句解释就会被判成「真的写了盘」。
    code = "\n".join(
        ln for ln in body.splitlines()
        if not ln.strip().startswith(("*", "//", "/*"))
    )
    if re.search(r'\.edit\s*\(\s*\)|putString\s*\(|putBoolean\s*\(|putInt\s*\(', code):
        prefs_write.append(e["name"])
    if re.search(r'deleteRecursively\s*\(|\.delete\s*\(\s*\)', code):
        del_files.append(e["name"])

check("D1 每个 reset 都有交代用途的 KDoc", not no_doc, f"缺注释：{no_doc}")
check("D2 reset 不写 SharedPreferences", not prefs_write, f"有写盘：{prefs_write}")
check("D3 reset 不删除任何文件", not del_files, f"有删除：{del_files}")

print(f"\n结果：{passed} 通过 / {failed} 失败")
sys.exit(1 if failed else 0)
PY
