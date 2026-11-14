#!/usr/bin/env python3
"""R-14 使用覆盖：每个契约成员是否真被使用、每个屏幕是否只拿到它用的。

两个方向都要看：
  - `closure - used`（过度暴露）：接口给了，没有任何屏幕用 → 接口在长胖。
  - `used - closure`（越界使用）：屏幕用了接口没给的 → 编译应该已经拦下；
    静态再报一次是为了在「编译不过」时能直接指出是哪个文件哪个成员。

参数名不写死 `vm`：ChatScreen 的权限参数叫 `permission`，
按 `名字: 契约类型` 识别，避免只认 `vm:` 的盲区。
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(ROOT, 'scripts/qa/lib'))
UI = os.path.join(ROOT, 'app/src/main/java/com/beichen/polaris/ui')
CONTRACTS = os.path.join(UI, 'contract')

# 这些顶层类后面的 `xxx.yyy` 是 SDK 常量访问，不是契约成员访问。
# 例：`Manifest.permission.RECORD_AUDIO` 里的 `permission.RECORD_AUDIO`。
SDK_OWNERS = {
    'Manifest', 'Build', 'Settings', 'Intent', 'Bundle', 'Uri', 'Context',
    'Activity', 'Application', 'View', 'ViewGroup', 'Log', 'Color', 'ColorKt',
    'Text', 'String', 'Toast', 'AlertDialog', 'Dialog', 'Notification',
    'NotificationChannel', 'NotificationManager', 'ClipboardManager', 'ClipData',
}


def load():
    out = subprocess.check_output([sys.executable,
                                   os.path.join(ROOT, 'scripts/qa/lib/r14_contracts.py'),
                                   CONTRACTS])
    d = json.loads(out)
    return d['interfaces'], d['closure']


# 复用 r14_contracts 的字符扫描版：正则版 `/*...*/` 在遇到 KDoc 里出现的
# `/*` 示例或含斜杠的字符串时会跨段吞代码——实测把 ChatScreen 的
# `vm.send()` 整段吃掉，导致整批成员被误判成「没人用」。
from r14_contracts import strip_comments  # noqa: E402


def main():
    interfaces, closure = load()
    names = set(interfaces)

    # 文件 → [(参数名, 契约类型)]；同文件里可能有两个契约参数（ChatScreen 的 vm + permission）
    params = {}
    for fn in sorted(os.listdir(UI)):
        if not fn.endswith('.kt'):
            continue
        src = strip_comments(open(os.path.join(UI, fn), encoding='utf-8').read())
        found = []
        for m in re.finditer(r'(\w+)\s*:\s*(' + '|'.join(sorted(names)) + r')\b', src):
            found.append((m.group(1), m.group(2)))
        if found:
            params[fn] = sorted(set(found))

    def ancestors(name, acc=None):
        acc = acc if acc is not None else set()
        if name in acc:
            return acc
        acc.add(name)
        for p in interfaces.get(name, {}).get('parents', []):
            ancestors(p, acc)
        return acc

    over, under, per_iface = {}, {}, {}
    for fn, ps in params.items():
        src = strip_comments(open(os.path.join(UI, fn), encoding='utf-8').read())
        for pname, itype in ps:
            # `vm::send` 与 `vm.send()` 一样是使用：方法引用在 Compose 回调里很常见，
            # 只认点号会把整批「通过 :: 传下去」的成员误判成死成员。
            #
            # **必须排除带包名前缀的访问**：`Manifest.permission.RECORD_AUDIO` 里的
            # `permission.RECORD_AUDIO` 会被正则当成「用了 permission 的 RECORD_AUDIO
            # 成员」——而它其实是 Android SDK 的常量访问，跟契约成员毫无关系。
            # 两条同名，误报的诊断信息（"屏幕越界使用 RECORD_AUDIO"）会把人引到
            # 完全错误的方向上。
            #
            # 判据：成员名的**前一个标识符**是 `Manifest` / `Build` / `Settings` 这类
            # 顶层类名时，说明访问来自 SDK 而非契约参数。
            used = set()
            for m in re.finditer(r'\b([A-Za-z_]\w*)\s*\.\s*' + re.escape(pname) +
                                 r'(?:\.|::)\s*([A-Za-z_]\w*)', src):
                # 前一个标识符是顶层类名 → 这是 SDK 常量访问，不是契约成员
                if m.group(1) in SDK_OWNERS:
                    continue
                used.add(m.group(2))
            # 裸用法（`vm.xxx`，前面不是 `某个类名.`）
            for m in re.finditer(r'\b' + re.escape(pname) + r'(?:\.|::)\s*([A-Za-z_]\w*)', src):
                prefix = src[:m.start()]
                tail = prefix.rstrip()
                if tail.endswith('.'):
                    owner = re.search(r'([A-Za-z_]\w*)\s*\.$', tail)
                    if owner and owner.group(1) in SDK_OWNERS:
                        continue
                used.add(m.group(1))
            allowed = set(closure[itype])
            under[(fn, itype)] = sorted(used - allowed)
            # 屏幕声明的是组合接口时，成员使用要沿继承链回填给每个子接口——
            # 否则 ChatStream 这类「只被组合接口引用」的接口会被判成整条死光。
            for sub in ancestors(itype):
                # 契约里写了未定义的 parent 时，ancestors 会把它收进来（图本身允许
                # 出现悬空边，交给 guard 的 B4 报可读诊断），但 closure 里没有这个 key。
                # 早先这里直接 closure[sub] 会 KeyError，把整条守卫链弄崩——
                # 表现是「一条判据都没输出」，比「B4 报红」难查得多。
                per_iface.setdefault(sub, set()).update(used & set(closure.get(sub, ())))

    # 过度暴露按接口聚合后算：同一接口被多个屏幕共用时，只要有一个用了就不算死。
    dead = {k: sorted(set(closure[k]) - per_iface.get(k, set())) for k in names
            if closure[k] and set(closure[k]) - per_iface.get(k, set())}
    over = dead

    print(json.dumps({
        'params': {k: v for k, v in params.items()},
        'over': {k: sorted(v) for k, v in over.items() if v},
        'under': {f'{k[0]}::{k[1]}': v for k, v in under.items() if v},
        'dead': dead,
    }, ensure_ascii=False, indent=1, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
