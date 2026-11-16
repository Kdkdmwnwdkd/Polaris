#!/usr/bin/env bash
# scripts/qa/gate.sh —— QA 硬门禁
#
# 为什么需要它：`run-all.sh` 把「已知失效」的 harness 归为 XFAIL，**不阻塞构建**。
# 这个设计本身没错（断言陈旧时不该把所有人堵在门口），但副作用是：
# 一个新引入的失效 harness 只要往 known-broken.txt 里加一行，就能长期悄悄挂在 CI 上。
# 本脚本把这类「沉淀」变成**当场报红**。
#
# 门禁六条（任一不满足即失败）：
#   1. known-broken.txt 里不能有非注释条目 —— 清单必须归零
#   2. QA 汇总里 FAIL 必须为 0
#   3. 日志里不能出现 XPASS —— 出现说明清单该更新了（脚本已修好却仍被当失效项）
#   4. harness 覆盖完整性 —— 每个 *Verification.kt 都必须被某个 run-*.sh 引用，
#      且 run-*.sh 的数量必须与日志里实际跑过的数量一致（防止"写了 harness 但没人跑"）
#   5. PASS 数不得低于基线 —— 防止「环境一变全 SKIP」被当成全绿（那是另一种假绿）
#   6. 每个 SKIP 都必须登记在 skip-allowlist.txt，且条目数只减不增 —— 门禁 5 只看
#      总数，挡不住「新增一个永远 SKIP 的 runner」（PASS 不变，谁也看不出来）
#
# 用法：
#   bash scripts/qa/gate.sh [qa日志路径]
#   不传日志路径时，只做第 1 条（清单非空检查），适合本地快速自查。
#
# 逃生阀：环境变量 QA_GATE=warn 时只告警不失败（仅供紧急止血，默认是硬失败）。
set -uo pipefail

cd "$(dirname "$0")/../.."
ROOT=$(pwd)

BROKEN_FILE="scripts/qa/known-broken.txt"
LOG="${1:-}"

rc=0
say() { echo "$*"; }
fail() { echo "GATE FAIL: $*" >&2; rc=1; }

# ---------------------------------------------------------------- 门禁 1
if [ -f "$BROKEN_FILE" ]; then
  # 只取非注释、非空行；字段 1 是脚本名
  entries=$(awk '!/^[[:space:]]*(#|$)/ {print $1}' "$BROKEN_FILE")
  n=$(printf '%s' "$entries" | grep -c . || true)
  if [ "${n:-0}" -gt 0 ]; then
    fail "known-broken.txt 有 ${n} 条已知失效项（应为 0）："
    printf '%s\n' "$entries" | sed 's/^/    - /' >&2
    say "  修好一条就删一条；确实修不好就先修，别让失效项沉淀成常态。"
  else
    say "GATE OK 1/6：known-broken.txt 已归零（0 条）"
  fi
else
  fail "known-broken.txt 不存在（$BROKEN_FILE）——门禁无法判定，请检查文件是否被误删"
fi

# ---------------------------------------------------------------- 门禁 2 & 3
if [ -z "$LOG" ]; then
  partial=1
  say "GATE SKIP 2-6：未提供 QA 日志路径，只做了门禁 1（结论不完整）"
  say "  CI 里本脚本会自动取 /tmp/qa.log；本地可手动传：bash scripts/qa/gate.sh qa-logs/xxx.log"
elif [ ! -f "$LOG" ]; then
  fail "QA 日志不存在：$LOG（QA harness 可能被 QA_HARNESS=off 跳过了）"
else
  summary=$(grep -E '^\s*====\s*汇总：' "$LOG" | tail -1)
  if [ -z "$summary" ]; then
    fail "QA 日志里找不到汇总行（格式应为「==== 汇总：PASS=n SKIP=n XFAIL=n FAIL=n」）"
  else
    f=$(printf '%s' "$summary" | sed -n 's/.*FAIL=\([0-9]\+\).*/\1/p')
    x=$(printf '%s' "$summary" | sed -n 's/.*XFAIL=\([0-9]\+\).*/\1/p')
    p=$(printf '%s' "$summary" | sed -n 's/.*PASS=\([0-9]\+\).*/\1/p')
    say "  汇总：$summary"
    if [ -z "$f" ]; then
      fail "无法从汇总行解析 FAIL 数：$summary"
    elif [ "$f" -ne 0 ]; then
      fail "QA 汇总 FAIL=$f（必须为 0）"
    elif grep -qE '^ENV STALE' "$LOG"; then
      # 产物陈旧不改变 PASS/FAIL 计数，但它让「PASS」的含义变味：
      # 依赖 classpath 的 harness 读的是旧字节码，报错会指向早已改过的源码行。
      # 这种轮次不能算绿——结论不可信比结论为红更糟，因为它看起来是好的。
      fail "编译产物比源码旧（run-all 打了 ENV STALE）：$(grep -E '^ENV STALE' "$LOG" | head -1)"
      say "  先 ./gradlew :app:compileDebugKotlin 再跑，否则「找不到符号」全是假象。"
    else
      say "GATE OK 2/6：FAIL=0（PASS=${p:-?} XFAIL=${x:-?}）"
    fi
  fi

  if grep -qE '^XPASS ' "$LOG"; then
    fail "QA 日志出现 XPASS（脚本已修好但仍列在 known-broken.txt），请从清单移除："
    grep -E '^XPASS ' "$LOG" | sed 's/^/    - /' >&2
  else
    say "GATE OK 3/6：无 XPASS"
  fi

  # ------------------------------------------------------------ 门禁 4
  #
  # 「写了 harness 但没人跑」是比「harness 失效」更隐蔽的问题：
  # 前者在 CI 上完全静默（不报错、不统计），断言等于不存在。
  # 两种漏法：
  #   ① 新增 *Verification.kt 后忘了写 run-*.sh —— 文件在，但 run-all 永远跑不到；
  #   ② run-*.sh 写了但命名不合规 / 被 run-all 的 glob 漏掉 —— 日志里少一行。
  # 这里都堵上：前者查引用，后者比数量。
  orphan=""
  for kt in scripts/qa/*Verification.kt; do
    [ -f "$kt" ] || continue
    base=$(basename "$kt")
    # 允许被任一 run-*.sh 直接点名，或按约定名推导（Foo.kt → run-foo-verification.sh）
    if ! grep -lqF "$base" scripts/qa/run-*.sh 2>/dev/null; then
      stem=$(echo "${base%Verification.kt}" | tr '[:upper:]' '[:lower:]')
      if ! ls scripts/qa/run-*.sh 2>/dev/null | grep -qi "$stem"; then
        orphan="$orphan $base"
      fi
    fi
  done

  # run-*.sh 数量要排除 run-all.sh 本身（它是调度器，自己不会"被跑"）
  runners=$(ls scripts/qa/run-*.sh 2>/dev/null | grep -v '/run-all\.sh$' | wc -l | tr -d ' ')
  # 日志里每个 runner 一行，但两种格式并存：
  #   PASS  scripts/qa/run-xxx.sh          ← 带路径
  #   SKIP  run-apk-verification.sh（…）   ← 不带路径（SKIP 走的是另一条打印分支）
  # 所以前缀要可选，否则 SKIP 的条目会被当成"没跑"。
  ran=$(grep -cE '^(PASS|SKIP|XFAIL|XPASS|FAIL)[[:space:]]+(scripts/qa/)?run-' "$LOG" || true)

  if [ -n "$orphan" ]; then
    fail "以下 harness 没有任何 run-*.sh 引用（等于没跑）："
    for f in $orphan; do echo "    - $f" >&2; done
  elif [ "${ran:-0}" -lt "${runners:-0}" ]; then
    fail "run-*.sh 有 $runners 个，但日志里只跑了 $ran 个（有 runner 没被 run-all 收录）"
  else
    say "GATE OK 4/6：harness 覆盖完整（$runners 个 runner 全部跑到，无孤儿 harness）"
  fi

  # ------------------------------------------------------------ 门禁 5
  #
  # 「全绿」有两种：断言都过了，或者一条都没跑。
  #
  # run-all 把 SKIP 定义为「什么都不算通过」但不阻塞——这对本地是对的（没编 AGP 产物
  # 时不该红）。可在 CI 上它意味着：kotlinc shim 装失败、Gradle 缓存路径变了、某个
  # runner 提前 exit 3，都能让几十个 harness 静默跳过，而构建照样绿。这类事故的唯一
  # 表现就是「PASS 数悄悄变少」，没人会盯着汇总行数看。
  #
  # 所以卡 PASS 下限，只增不减。新增 harness 时脚本会提示把基线调上去。
  BASELINE="scripts/qa/harness-baseline.txt"
  if [ ! -f "$BASELINE" ]; then
    fail "基线文件缺失（$BASELINE）——门禁 5 无法判定"
  else
    # 取**最后**一个非注释行：这份文件是追加式账本，每次调整都在末尾写新数字，
    # 中间那些历史值留着是为了能读懂「为什么从 87 涨到 147」。
    #
    # 修前这里写的是 `{print $1; exit}`——取第一个，于是读到的永远是 2026-11-04
    # 那个 142，之后每一次「往上调一个」都是空写。后果不是红，是**悄悄变松**：
    # 新增了 5 个 harness、其中 5 个被改成 SKIP，PASS 仍 ≥142，门禁照过。
    # 边界比「漏报」更糟的那类错误——它让门禁看起来在工作。
    base=$(awk '!/^[[:space:]]*(#|$)/ {v=$1} END{print v}' "$BASELINE")
    if [ -z "$base" ]; then
      # 不 return：门禁 5 判不了，但后面几条还要跑（它们的结论仍然有用）。
      # fail 已经记账，最终 BLOCKED。
      fail "基线文件里没有数字（$BASELINE）——门禁 5 判不了"
      base=0
    fi
    sk=$(printf '%s' "$summary" | sed -n 's/.*SKIP=\([0-9]\+\).*/\1/p')
    if [ -z "$p" ]; then
      fail "无法从汇总行解析 PASS 数，门禁 5 判不了"
    elif [ "${sk:-0}" -gt 0 ]; then
      # R-17 原文：有 SKIP 时 PASS 下限不可比，改由门禁 6 逐条核对。
      # 那个理由只对了一半：不可比的是「PASS 的绝对值」，可比的是「PASS 有没有被 SKIP 兑掉」。
      # 于是 SKIP>0 变成了整个门禁 5 的免检牌——把 40 个 runner 改成首行 exit 3，
      # PASS 掉到 60 也照样过，只要门禁 6 的名字还都在登记册里。
      # 现在的判据：每个 SKIP 都要从下限里等额扣掉（PASS ≥ 基线 − SKIP）。
      # SKIP 越多，要求越低，但不可能低到「一条都不跑也算过」。
      floor=$(( base - sk ))
      [ "$floor" -lt 0 ] && floor=0
      if [ "$p" -lt "$floor" ]; then
        fail "PASS=$p 低于本轮下限 $floor（基线 $base − SKIP $sk）—— 有 harness 没真跑"
        grep -E '^SKIP ' "$LOG" | head -5 | sed 's/^/    - /' >&2
        say "  环境问题就修环境；确实删掉了 harness，再往下调 $BASELINE 并写清理由。"
      else
        say "GATE OK 5/6：PASS=$p ≥ 下限 $floor（基线 $base − SKIP $sk）"
      fi
    elif [ "$p" -lt "${base:-0}" ]; then
      fail "PASS=$p 低于基线 $base —— 有 harness 没真跑（断言变少不等于变绿）："
      nskip=$(grep -cE '^SKIP ' "$LOG" || true)
      grep -E '^SKIP ' "$LOG" | head -5 | sed 's/^/    - /' >&2
      [ "${nskip:-0}" -gt 5 ] && echo "    …… 共 $nskip 个（完整清单见 $LOG）" >&2
      say "  环境问题就修环境（多半是找不到 kotlinc 或 app/build 产物）；"
      say "  确实删掉了 harness，再往下调 $BASELINE 并写清理由。"
    elif [ "$p" -gt "$base" ]; then
      say "GATE OK 5/6：PASS=$p 高于基线 $base（新增了 harness？把 $BASELINE 里的数字改成 $p）"
    else
      say "GATE OK 5/6：PASS=$p 与基线一致"
    fi
  fi

  # ------------------------------------------------------------ 门禁 6
  #
  # R-17：SKIP 是本项目唯一「什么都不验证也算过」的状态。
  #
  # 门禁 5 用 PASS 下限挡「大面积 SKIP」，但挡不住**新增**：新加一个 runner，
  # 第一行就 `exit 3`，PASS 总数不变、门禁 5 照样绿——而这个 harness 从诞生那天起
  # 就是零覆盖。下限只比总数，看不出「谁在名单里」。
  #
  # 三种收口：
  #   ① 日志里每个 SKIP 都必须在登记册里——把「悄悄零覆盖」变成「有人签字承认」，
  #      并且必须写清缺什么、哪个环境能满足（run-all 已核验路径真实性，见其
  #      skip_claim_is_true）；
  #   ② 条目数只减不增——否则「加一行」就是新的沉淀路径，和 known-broken 一个下场；
  #   ③ 登记的名字必须真实存在——删了 runner 忘删条目，登记册会慢慢变成谎言。
  SKIP_FILE="scripts/qa/skip-allowlist.txt"
  if [ ! -f "$SKIP_FILE" ]; then
    fail "skip-allowlist.txt 不存在（$SKIP_FILE）——门禁 6 无法判定"
  else
    # 取名字要连中文括号一起切掉：run-all 对 apk 那条打印的是
    # `SKIP  run-apk-verification.sh（未传 APK 路径…）`，直接取第二列会带上「（未传」。
    logged_skip=$(grep -E '^SKIP[[:space:]]' "$LOG" | awk '{print $2}' \
      | sed 's/[（(].*//' | sed 's#.*/##' | sort -u)
    reg_skip=$(awk '!/^[[:space:]]*(#|$)/ {print $1}' "$SKIP_FILE" | sed 's#.*/##' | sort -u)
    as_lines() { [ -n "$1" ] && printf '%s\n' "$1"; }

    g6_ok=1
    unreg=$(comm -23 <(as_lines "$logged_skip") <(as_lines "$reg_skip"))
    if [ -n "$unreg" ]; then
      g6_ok=0
      fail "以下 runner 本轮 SKIP，但没有在 skip-allowlist.txt 登记（零覆盖不能默认合法）："
      printf '%s\n' "$unreg" | sed 's/^/    - /' >&2
      say "  要么让它真跑起来，要么在登记册里写清「缺什么、哪个环境能满足」。"
      say "  注意条目的缺件路径必须真实存在缺失——run-all 会 stat 它，不成立就判 FAIL。"
    fi

    zombies=""
    for r in $reg_skip; do
      [ -f "scripts/qa/$r" ] || zombies="$zombies $r"
    done
    if [ -n "$zombies" ]; then
      g6_ok=0
      fail "登记册里有已不存在的 runner（删脚本时忘了删条目）："
      for r in $zombies; do echo "    - $r" >&2; done
    fi

    nreg=$(printf '%s\n' "$reg_skip" | grep -c . || true)
    cap=$(sed -n 's/^#[[:space:]]*条目上限[：:][[:space:]]*\([0-9][0-9]*\).*/\1/p' "$SKIP_FILE" | head -1)
    if [ -z "$cap" ]; then
      fail "skip-allowlist.txt 缺少「# 条目上限：N」行——门禁 6 判不了增减"
    elif [ "${nreg:-0}" -gt "$cap" ]; then
      g6_ok=0
      fail "SKIP 登记册有 $nreg 条，超过上限 $cap —— 新增 harness 不该靠 SKIP 过门禁"
      say "  先让它能在本机真跑（多为补上 kotlinc 或 AGP 产物路径）；"
      say "  确实只能 SKIP，再改 $SKIP_FILE 里的上限数字并写清理由。"
    fi
    # 只在三条都过时才敢说「全部登记在册」——上一版把 say 放在 else 分支里，
    # 结果是「GATE FAIL: 有 runner 未登记」下面紧跟「GATE OK 6/6」，自相矛盾。
    [ "$g6_ok" -eq 1 ] && say "GATE OK 6/6：SKIP 全部登记在册（$nreg/$cap 条，只减不增）"

    # 不失败，只提示：环境补齐后登记册该瘦回来，否则它会变成「永久豁免清单」。
    revived=$((nreg - $(printf '%s\n' "$logged_skip" | grep -c . || true)))
    [ "${revived:-0}" -gt 0 ] && \
      say "  提示：$revived 条登记项本轮没有 SKIP（环境已满足？可考虑从登记册移除）。"
  fi
fi

# ---------------------------------------------------------------- 逃生阀
if [ "${QA_GATE:-}" = "warn" ] && [ "$rc" -ne 0 ]; then
  echo "QA_GATE=warn：以上问题仅告警，不阻塞（紧急止血用）" >&2
  exit 0
fi

if [ "$rc" -eq 0 ]; then
  if [ "${partial:-0}" -eq 1 ]; then
    # 「只跑了门禁 1」不等于「全部通过」。旧版在这里照样打印 GATE PASS，
    # 于是 `bash gate.sh`（不带日志）在 CI 之外永远看起来是绿的——最容易被当成"验过了"。
    echo "GATE PARTIAL：只做了门禁 1，2-6 未跑（请传 QA 日志路径）"
  else
    echo "GATE PASS：全部检查通过"
  fi
else
  echo "GATE BLOCKED：门禁未通过" >&2
fi
exit "$rc"
