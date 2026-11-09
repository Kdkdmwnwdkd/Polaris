#!/usr/bin/env python3
"""R-10 会话分片的结构化守卫判据（集合对账，不是 grep 文本断言）。

行为层由 scripts/qa/R10ShardVerification.kt 真跑覆盖（13 条）。本文件守的是
**行为测不到、但会悄悄把架构退化回旧写法**的那一类回归：

  - 分片 DTO 里又把 messages 写回去（双写复活，行为用例只在小数据下跑，看不出体积）；
  - persist 里又出现 encodeToString(items)（整库全量重写复活，单会话用例照样绿）；
  - 某个写方法忘了标脏（该会话的改动永远不落盘，只有「改了它再重启」才暴露，
    而行为用例未必正好覆盖那个方法）；
  - 绕过 AtomicJsonFile 直接 writeText（原子写纪律破防，正常路径不报错）。

判据解析 Kotlin 源码结构（data class 字段集、函数体、调用点集合），做集合对账，
输出 `A<n> PASS/FAIL ...`，由 run-r10-shard-verification.sh 汇总。
用法：python3 scripts/qa/lib/r10_shard_guard.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent.parent
SRC = ROOT / "core/src/main/java/com/beichen/polaris/core"
STORE = (SRC / "ConversationStore.kt").read_text(encoding="utf-8")
SHARD = (SRC / "ConversationShard.kt").read_text(encoding="utf-8")

results = []


def check(cid, cond, desc):
    results.append((cid, bool(cond), desc))


def class_body(src, cls):
    """取 `data class X( ... )` 的参数区文本（按括号深度配平，注解里有括号也不怕）。"""
    m = re.search(r"data class\s+" + re.escape(cls) + r"\s*\(", src)
    if not m:
        return None
    i = m.end()
    depth = 1
    while i < len(src) and depth:
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
        i += 1
    return src[m.end():i - 1] if depth == 0 else None


def fields(src, cls):
    body = class_body(src, cls)
    if body is None:
        return None
    return set(re.findall(r"\bval\s+(\w+)\s*:", body))


def func(src, name, tail=r"\s*\(", private_only=False):
    """取类内 `fun name(` 到下一个同级 fun 之间的函数体。

    tail/private_only 用来区分重载：文件里同时有公开的 `load(id)` 和私有的
    读盘 `load()`，按名字取会拿错那一个。
    """
    prefix = r"\n    private fun\s+" if private_only else r"\n    (?:private )?fun\s+"
    m = re.search(prefix + re.escape(name) + tail, src)
    if not m:
        return None
    start = m.start()
    nxt = re.search(r"\n    (?:private )?fun\s+", src[start + 1:])
    end = start + 1 + nxt.start() if nxt else len(src)
    return src[start:end]


# ---------------------------------------------------------------- [A1-A3] DTO
shard_fields = fields(SHARD, "ConversationShard")
EXPECTED_SHARD = {"id", "title", "createdAt", "updatedAt", "turns", "pinned", "workspaceDir"}
check("A1", shard_fields == EXPECTED_SHARD and "messages" not in (shard_fields or set()),
      "ConversationShard 字段集精确为 7 个，且不含 messages（双写已消除）")

index_fields = fields(SHARD, "ConversationIndex")
check("A2", index_fields == {"schemaVersion", "ids"},
      "ConversationIndex 只含 schemaVersion + ids，不含任何消息正文")
idx_body = class_body(SHARD, "ConversationIndex") or ""
check("A3", "@EncodeDefault(EncodeDefault.Mode.ALWAYS)" in idx_body,
      "schemaVersion 带 @EncodeDefault(ALWAYS)：不因 encodeDefaults=false 从 index 里缺席")

of_body = func(SHARD, "of") or ""
to_body = func(SHARD, "toRecord") or ""
check("A4", "messages" not in of_body and "ConversationBranch.flatten(turns)" in to_body,
      "of() 丢弃 messages 缓存，toRecord() 用 flatten(turns) 现算")

# ---------------------------------------------------------------- [A5-A8] 落盘层
persist = func(STORE, "persist") or ""
# 私有读盘 load()，不是公开的 load(id)——按名字取会拿错那个重载。
load = func(STORE, "load", tail=r"\(\)", private_only=True) or ""
write_shard = func(STORE, "writeShard") or ""
shard_file = func(STORE, "shardFile") or ""

check("A5", "encodeToString(items" not in persist and "encodeToString(snapshot)" not in persist,
      "persist 不再序列化整库 items/snapshot（O(全库) 已拆掉）")
check("A6", "for (id in batch.dirty)" in persist and "ConversationIndex(ids = batch.records.map { it.id })" in persist,
      "persist 只遍历脏分片，index 只写 id 清单")
check("A7", "ConversationShard.of(rec)" in write_shard and "shardFile(rec.id)" in write_shard,
      "writeShard 只编码分片 DTO，落到单个 <id>.json")
check("A8", "parseIndexOrNull" in load and "parseOrNull" in load and
      "migrateLegacyToShards" in load and "recoverFromShards" in load,
      "load 四路径齐备：新 index / 旧数组迁移 / 一次性拆片 / 索引全坏时扫描重建")

# ---------------------------------------------------------------- [A9] 脏追踪覆盖
# 每个会改 items 的公开写方法都必须标脏，否则该改动永远不落盘。
WRITERS = ["setPinned", "create", "appendMessage", "settlePendingTools", "selectVariant",
           "appendVariant", "truncateAfter", "rename", "setWorkspaceDir", "delete", "replaceAll"]
missing = []
for w in WRITERS:
    body = func(STORE, w) or ""
    if "items[" in body or "items.add(" in body or "items.removeAll" in body or "items.addAll" in body:
        if "dirtyIds" not in body and "removedIds" not in body:
            missing.append(w)
check("A9", not missing, "全部 %d 个写方法都标记了脏会话（漏标：%s）" % (len(WRITERS), missing or "无"))

# ---------------------------------------------------------------- [A10-A12] 纪律
check("A10", STORE.count("AtomicJsonFile.write(") >= 2 and "writeText(" not in STORE,
      "落盘统一走 AtomicJsonFile（分片 + index），未绕过原子写")
# W-RT9 把编码逻辑抽到了 safeStem（分片与归档共用一段，避免两边字符集走偏），
# shardFile 只剩一行调用。断言跟着盯**语义**而不是盯字面：编码这段在谁身上都算数，
# 前提是 shardFile 确实用了它。同一件事的行为断言是 B12（真造非法 id 看落盘文件名），
# 那条一直是对的——文本断言红而行为绿，问题在断言不在代码。
safe_stem = func(STORE, "safeStem", private_only=True) or ""
check("A11",
      ("SAFE_ID.matches(id)" in shard_file and "%02x" in shard_file) or
      ("safeStem(id)" in shard_file and "SAFE_ID.matches(id)" in safe_stem and
       "%02x" in safe_stem),
      "分片文件名经安全编码（路径穿越防护）")
check("A12", "bakFile" not in STORE and "tmpFile" not in STORE and "readTextOrEmpty" not in STORE,
      "整库时代的 bakFile/tmpFile/readTextOrEmpty 已清除")
check("A13", "fun snapshot(): List<ConversationRecord>" in STORE and
      "fun replaceAll(list: List<ConversationRecord>)" in STORE and
      "fun flush()" in STORE and "AppBackgroundHooks.register" in STORE,
      "公开 API 语义保留：snapshot/replaceAll/flush + R-19 后台接线")

# ---------------------------------------------------------------- 输出
for cid, ok, desc in results:
    print(f"  {cid} {'PASS' if ok else 'FAIL'}  {desc}")
p = sum(1 for _, ok, _ in results if ok)
f = len(results) - p
print(f"\nR10 结构判据：{p} 通过 / {f} 失败")
sys.exit(1 if f else 0)
