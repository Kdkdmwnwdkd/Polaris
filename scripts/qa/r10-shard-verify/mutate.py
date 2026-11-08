#!/usr/bin/env python3
"""R-10 会话分片守卫的变异测试：把修好的分片结构逐个改坏，看守卫是否靶向变红。

判据自己也可能是摆设——只有「改坏必红、红在对的那组判据、恢复必绿」才算真判据。

## 为什么分两种探针
runner（run-r10-shard-verification.sh）由两段组成：
  - [A] 结构判据 `lib/r10_shard_guard.py`：纯 Python 读源码做集合对账，毫秒级；
  - [B] 行为 harness：kotlinc 自编译 + 真跑临时目录，约 17 秒。
A 段变异只与「源码长什么样」有关，跑 A 段的**正式执行体** guard.py 即可验证
（CI 里 runner 就是 `python3 r10_shard_guard.py` 调它）；B 段变异必须跑完整 runner，
因为要验的是运行时行为。19 组若全跑完整 runner 需约 11 分钟，混合后约 4 分钟，
且每组验的都还是 CI 上真正会跑的那条链路。

所有变异都刻意保持 Kotlin 可编译——否则 B 段会以「自编译失败」整体崩掉，
变成一次笼统的 B0 红，反而看不出是哪条判据在起作用。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))))
STORE = 'core/src/main/java/com/beichen/polaris/core/ConversationStore.kt'
SHARD = 'core/src/main/java/com/beichen/polaris/core/ConversationShard.kt'
HARNESS = 'scripts/qa/R10ShardVerification.kt'
GUARD = 'scripts/qa/lib/r10_shard_guard.py'
RUNNER = 'scripts/qa/run-r10-shard-verification.sh'

A_IDS = ['A%d' % i for i in range(1, 14)]
B_IDS = ['B%d' % i for i in range(1, 15)]


def run_guard():
    r = subprocess.run([sys.executable, GUARD], cwd=ROOT,
                       capture_output=True, text=True)
    return r.stdout + r.stderr, r.returncode


def run_full():
    r = subprocess.run(['bash', RUNNER], cwd=ROOT,
                       capture_output=True, text=True)
    return r.stdout + r.stderr, r.returncode


PROBES = {'guard': run_guard, 'full': run_full}


def a_only(want):
    """结构判据变异：只有目标判据该红，其余 A 判据都不该被牵连。"""
    return [x for x in A_IDS if x != want]


# (名字, 探针, [(文件, old, new, 期望出现次数)], 必须命中的 FAIL 判据, 不该红的判据)
MUTS = [
    # ---------------- [A] 结构判据，每组只该红一条 ----------------
    (
        'M1  分片 DTO 加回 messages 字段（双写复活）', 'guard',
        [(SHARD,
          '    val turns: List<TurnRecord> = emptyList(),\n    val pinned: Boolean = false,',
          '    val turns: List<TurnRecord> = emptyList(),\n    val messages: List<MsgRecord> = emptyList(),\n    val pinned: Boolean = false,', 1)],
        ['A1'], a_only('A1'),
    ),
    (
        'M2  index 塞进消息正文字段', 'guard',
        [(SHARD,
          '    val ids: List<String> = emptyList()\n) {',
          '    val ids: List<String> = emptyList(),\n    val content: String = ""\n) {', 1)],
        ['A2'], a_only('A2'),
    ),
    (
        'M3  去掉 @EncodeDefault(ALWAYS)（版本号不再写进 index）', 'guard',
        [(SHARD,
          '    @EncodeDefault(EncodeDefault.Mode.ALWAYS)\n    val schemaVersion: Int = SCHEMA_VERSION,',
          '    val schemaVersion: Int = SCHEMA_VERSION,', 1)],
        ['A3'], a_only('A3'),
    ),
    (
        'M4  toRecord 不再 flatten（messages 读回现算失效）', 'guard',
        [(SHARD,
          '        messages = ConversationBranch.flatten(turns),',
          '        messages = emptyList(),', 1)],
        ['A4'], a_only('A4'),
    ),
    (
        'M5  persist 又序列化整库 snapshot（O(全库) 复活）', 'guard',
        [(STORE,
          '        val byId = batch.records.associateBy { it.id }',
          '        val snapshot = batch.records\n'
          '        @Suppress("UNUSED_VARIABLE") val ignored = json.encodeToString(snapshot)\n'
          '        val byId = batch.records.associateBy { it.id }', 1)],
        ['A5'], a_only('A5'),
    ),
    (
        'M6  persist 遍历全库而非脏集（只写脏会话失效）', 'guard',
        [(STORE,
          '        for (id in batch.dirty) {',
          '        for (id in batch.records.map { it.id }) {', 1)],
        ['A6'], a_only('A6'),
    ),
    (
        'M7  writeShard 直接编码整条 ConversationRecord', 'guard',
        [(STORE,
          'json.encodeToString(ConversationShard.of(rec))',
          'json.encodeToString(rec)', 1)],
        ['A7'], a_only('A7'),
    ),
    (
        'M8  load 去掉索引全坏时的分片重建', 'guard',
        [(STORE,
          '            recoverFromShards()\n            return',
          '            return', 1)],
        ['A8'], a_only('A8'),
    ),
    (
        'M9  setPinned 漏标脏会话（改动永不落盘）', 'guard',
        [(STORE,
          '            items[idx] = items[idx].copy(pinned = pinned)\n            dirtyIds.add(convId)\n        }\n        schedulePersist()',
          '            items[idx] = items[idx].copy(pinned = pinned)\n        }\n        schedulePersist()', 1)],
        ['A9'], a_only('A9'),
    ),
    (
        'M10 writeShard 绕过原子写（writeText）', 'guard',
        [(STORE,
          '        return if (AtomicJsonFile.write(shardFile(rec.id), payload)) payload.toByteArray(Charsets.UTF_8).size else 0',
          '        return if (runCatching { shardFile(rec.id).writeText(payload); true }.getOrDefault(false)) payload.toByteArray(Charsets.UTF_8).size else 0', 1)],
        ['A10'], a_only('A10'),
    ),
    (
        'M11 shardFile 去掉非法 id 的安全编码', 'guard',
        [(STORE,
          '        val safe = if (SAFE_ID.matches(id)) id\n'
          '        else "h-" + id.toByteArray(Charsets.UTF_8)\n'
          '            .joinToString("") { "%02x".format(it.toInt() and 0xff) }',
          '        val safe = id', 1)],
        ['A11'], a_only('A11'),
    ),
    (
        'M12 整库时代的 bakFile 字段复活', 'guard',
        [(STORE,
          '    private val shardDir = File(file.parentFile, file.nameWithoutExtension + SHARD_DIR_SUFFIX)',
          '    private val shardDir = File(file.parentFile, file.nameWithoutExtension + SHARD_DIR_SUFFIX)\n'
          '    private val bakFile = File(file.parentFile, file.name + ".bak")', 1)],
        ['A12'], a_only('A12'),
    ),
    (
        'M13 去掉 R-19 的切后台 flush 接线', 'guard',
        [(STORE,
          '        AppBackgroundHooks.register(this) { owner -> (owner as ConversationStore).flush() }',
          '        // R-19 接线被移除（变异）', 1)],
        ['A13'], a_only('A13'),
    ),

    # ---------------- [B] 行为判据，跑完整 runner ----------------
    # forbidden 用全部 A 判据：行为变异不该改动任何结构字面量。
    (
        'M14 旧整库迁移不写分片（B4）', 'full',
        [(STORE,
          '        for (rec in records) writeShard(rec)',
          '        for (rec in records) Unit', 1)],
        ['B4'], A_IDS,
    ),
    (
        'M15 去抖失效：每次追加立即写（B8）', 'full',
        [(STORE,
          '        runCatching {\n'
          '            val next = persistScheduler.schedule(\n'
          '                { flushNow() },\n'
          '                PERSIST_DEBOUNCE_MS,\n'
          '                java.util.concurrent.TimeUnit.MILLISECONDS\n'
          '            )\n'
          '            pendingPersist.getAndSet(next)?.cancel(false)\n'
          '        }',
          '        runCatching { persistScheduler.execute { flushNow() } }', 1)],
        ['B8'], A_IDS,
    ),
    (
        'M16 删除会话不登记 removedIds（分片成孤儿，B5）', 'full',
        [(STORE,
          '            items.removeAll { it.id == convId }\n            dirtyIds.remove(convId)\n            removedIds.add(convId)',
          '            items.removeAll { it.id == convId }\n            dirtyIds.remove(convId)', 1)],
        ['B5'], A_IDS,
    ),
    (
        'M17 replaceAll 不清理旧分片（B10）', 'full',
        [(STORE,
          '            removedIds.addAll(oldIds - newIds)',
          '            // 旧分片清理被移除（变异）', 1)],
        ['B10'], A_IDS,
    ),
    (
        'M18 索引全坏重建后不写回 index（B13）', 'full',
        [(STORE,
          '        runCatching { AtomicJsonFile.write(file, payload) }',
          '        runCatching { payload.length }', 1)],
        ['B13'], A_IDS,
    ),
    (
        'M19 flush 丢掉幂等 + 条件计数（空 flush 也计数，B9）', 'full',
        [(STORE,
          '        if (!persistDirty.getAndSet(false)) return',
          '        persistDirty.getAndSet(false)', 1),
         (STORE,
          '            if (persist(mutations)) persistCount++',
          '            persist(mutations)\n            persistCount++', 1)],
        ['B9'], A_IDS,
    ),
]


def red_ids(out):
    # 输出带前导空格（"  A1 FAIL"）；含 G0/B0 兜底判据，
    # 守卫整体崩溃时不能误读成「判据没红」。
    return set(re.findall(r'^\s*([A-Z][0-9]+) FAIL', out, re.M))


def main():
    base, brc = run_full()
    if brc != 0 or red_ids(base):
        print('!!! 基线不绿，变异测试无意义：')
        print(base)
        return 1
    print('基线全绿（%s）' % base.strip().splitlines()[-1])

    ok = True
    for name, probe, edits, wants, forbidden in MUTS:
        run = PROBES[probe]
        originals = {}
        applied = True
        # 先整体校验锚点，避免「改了一半才发现第 2 个锚点找不到」留下脏源码
        for path, old, new, cnt in edits:
            if path not in originals:
                originals[path] = open(os.path.join(ROOT, path), encoding='utf-8').read()
            seen = originals[path].count(old)
            if seen != cnt:
                print(f'{name}: !!! 锚点出现 {seen} 次，期望 {cnt}（变异未生效）: {old[:60]!r}')
                applied = False
                break
        if applied:
            for path, old, new, cnt in edits:
                p = os.path.join(ROOT, path)
                s = open(p, encoding='utf-8').read()
                open(p, 'w', encoding='utf-8').write(s.replace(old, new))
        # 无论中途出什么错，先恢复再决定是否继续
        if not applied:
            for path, orig in originals.items():
                open(os.path.join(ROOT, path), 'w', encoding='utf-8').write(orig)
            ok = False
            continue

        out, rc = run()
        for path, orig in originals.items():
            open(os.path.join(ROOT, path), 'w', encoding='utf-8').write(orig)
        back, brc2 = run()

        red = red_ids(out)
        hit = [w for w in wants if w in red]
        spill = [x for x in forbidden if x in red]
        targeted = bool(hit) and not spill and rc != 0
        restored = brc2 == 0 and not red_ids(back)
        good = targeted and restored
        print(f'{name}: {"靶向精确且恢复全绿" if good else "!!! 未达标"}'
              f'  (红={sorted(red)}, 命中={hit}, 串味={spill}, rc={rc}, 恢复rc={brc2})')
        if not good:
            ok = False
            lines = [l for l in out.splitlines() if 'FAIL' in l][:8]
            print('\n'.join(lines))

    print('全部 %d 组变异均被捕获且恢复全绿' % len(MUTS) if ok else '有变异漏网')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
