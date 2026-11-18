#!/usr/bin/env bash
# MCP 对外提供的**真机端到端**验证（半自动）。
#
# 为什么是半自动：
#   配对令牌只在手机屏幕上出现一次（生成后只存 SHA-256），端口也是系统随机分配的。
#   让脚本去 uiautomator dump 里捞这两个值，在 MIUI/ColorOS/原生三种 ROM 上是三种不同的
#   布局，脚本会比功能本身更脆。所以这里只把「不该手工的部分」自动化：
#   端口转发、协议握手、档位可见性、越档拒绝、审计字段。
#
# 用法：
#   1. 手机装上 APK，进「设置 → MCP → 对外提供」，打开开关
#   2. 点「生成访问令牌」，记下令牌；卡片上会显示监听端口（形如 127.0.0.1:41234）
#   3. 跑：
#        ADB=adb bash scripts/qa/device/mcp-e2e.sh --port 41234 --token 'xxxxx'
#
#   多设备：加 --serial <serial>
#
#   不接设备时（例如已经做了端口映射到局域网），可以用 --url 直连：
#        bash scripts/qa/device/mcp-e2e.sh --url http://192.168.1.7:41234/mcp --token 'xxxxx'
#   这条路径也是本脚本的自测方式：拿 harness 起的假宿主跑一遍协议断言，
#   证明脚本本身没写错（否则真机跑失败时分不清是手机坏了还是脚本坏了）。
#
# 退出码：0 全绿；1 有断言失败；2 环境不齐（没有 adb / 没有设备）。

set -uo pipefail

PORT=""
TOKEN=""
SERIAL=""
URL=""
LOCAL_PORT=18080
ADB="${ADB:-adb}"

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --local-port) LOCAL_PORT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "未知参数：$1" >&2; usage ;;
  esac
done

[ -n "$TOKEN" ] || { echo "缺 --token" >&2; usage; }
[ -n "$URL" ] || [ -n "$PORT" ] || { echo "缺 --port 或 --url" >&2; usage; }

ADB_ARGS=()
[ -n "$SERIAL" ] && ADB_ARGS=(-s "$SERIAL")

if [ -z "$URL" ]; then
  command -v "$ADB" >/dev/null || { echo "SKIP 没有 adb" >&2; exit 2; }
  if ! "$ADB" "${ADB_ARGS[@]}" get-state >/dev/null 2>&1; then
    echo "SKIP 没有在线设备（adb devices 为空 / 未授权）" >&2
    exit 2
  fi
fi

pass=0
fail=0
ok() { # ok 描述 条件 备注
  if [ "$2" = "1" ]; then
    echo "  PASS  $1"
    pass=$((pass + 1))
  else
    echo "  FAIL  $1 ${3:-}"
    fail=$((fail + 1))
  fi
}

cleanup() {
  [ -n "$URL" ] && return 0
  "$ADB" "${ADB_ARGS[@]}" forward --remove "tcp:$LOCAL_PORT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -n "$URL" ]; then
  BASE="$URL"
  echo "== 直连 $BASE（跳过 adb forward）=="
else
  echo "== 端口转发 tcp:$LOCAL_PORT → 设备 tcp:$PORT =="
  cleanup
  if ! "$ADB" "${ADB_ARGS[@]}" forward "tcp:$LOCAL_PORT" "tcp:$PORT" >/dev/null; then
    echo "FAIL 无法建立 adb forward（端口被占？服务没起？）" >&2
    exit 1
  fi
  BASE="http://127.0.0.1:$LOCAL_PORT/mcp"
fi

# post '{"jsonrpc":...}' [token]  → 输出 "HTTP码<TAB>响应体"
post() {
  local body="$1" token="${2:-$TOKEN}"
  local resp code
  resp=$(curl -sS -m 10 -w $'\n%{http_code}' -X POST "$BASE" \
    -H 'Content-Type: application/json' \
    ${token:+-H "Authorization: Bearer $token"} \
    --data-binary "$body" 2>&1) || true
  code=$(printf '%s' "$resp" | tail -1)
  printf '%s\t%s' "$code" "$(printf '%s' "$resp" | sed '$d')"
}

# 从 JSON 里取字段（软转换，不硬转）：jget <json> <点路径>
jget() {
  python3 - "$1" "$2" <<'PY'
import json, sys
raw, path = sys.argv[1], sys.argv[2]
try:
    cur = json.loads(raw)
except Exception:
    print(""); raise SystemExit
for part in path.split("."):
    if isinstance(cur, dict) and part in cur:
        cur = cur[part]
    elif isinstance(cur, list):
        try: cur = cur[int(part)]
        except Exception: print(""); raise SystemExit
    else:
        print(""); raise SystemExit
print(cur if isinstance(cur, (str, int, float)) else json.dumps(cur, ensure_ascii=False))
PY
}

echo "== 协议 =="
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e","version":"1"}}}'
r=$(post "$INIT")
ok "initialize 返回 200" "$([ "$(printf '%s' "$r" | cut -f1)" = "200" ] && echo 1 || echo 0)" "实际 $(printf '%s' "$r" | cut -f1)"
body=$(printf '%s' "$r" | cut -f2-)
ok "协议版本回显 2024-11-05" "$([ "$(jget "$body" result.protocolVersion)" = "2024-11-05" ] && echo 1 || echo 0)" "实际 $(jget "$body" result.protocolVersion)"
ok "serverInfo.name = polaris" "$([ "$(jget "$body" result.serverInfo.name)" = "polaris" ] && echo 1 || echo 0)"

echo "== 鉴权 =="
r=$(post "$INIT" "definitely-wrong-token")
ok "错 token → 401" "$([ "$(printf '%s' "$r" | cut -f1)" = "401" ] && echo 1 || echo 0)" "实际 $(printf '%s' "$r" | cut -f1)"

echo "== 工具可见性（真机上的工具表，不是假工具）=="
r=$(post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
body=$(printf '%s' "$r" | cut -f2-)
names=$(jget "$body" result.tools)
ok "tools/list 非空" "$([ "$names" != "" ] && [ "$names" != "[]" ] && echo 1 || echo 0)" "实际 $names"
has_applist=$(printf '%s' "$names" | grep -c '"app_list"' || true)
ok "含 app_list（只读档应可见）" "$([ "$has_applist" -ge 1 ] && echo 1 || echo 0)"
has_shell=$(printf '%s' "$names" | grep -c '"shell_run"' || true)
ok "不含 shell_run" "$([ "$has_shell" -eq 0 ] && echo 1 || echo 0)" "出现了说明档位过滤失效"
has_uninstall=$(printf '%s' "$names" | grep -c '"app_uninstall"' || true)
ok "不含 app_uninstall（硬禁用）" "$([ "$has_uninstall" -eq 0 ] && echo 1 || echo 0)"

echo "== 真实调用 =="
r=$(post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"app_list","arguments":{}}}')
body=$(printf '%s' "$r" | cut -f2-)
ok "app_list 调用成功" "$([ "$(printf '%s' "$r" | cut -f1)" = "200" ] && [ "$(jget "$body" error.code)" = "" ] && echo 1 || echo 0)" "体 $(printf '%s' "$body" | head -c 160)"
text=$(jget "$body" result.content.0.text)
ok "app_list 返回了非空文本" "$([ -n "$text" ] && [ "$text" != "null" ] && echo 1 || echo 0)" "体 $(printf '%s' "$text" | head -c 120)"

echo "== 越档与未知方法 =="
r=$(post '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"shell_run","arguments":{"cmd":"id"}}}')
body=$(printf '%s' "$r" | cut -f2-)
code=$(jget "$body" error.code)
ok "shell_run 被拒（-32003）" "$([ "$code" = "-32003" ] && echo 1 || echo 0)" "实际 code=$code"

r=$(post '{"jsonrpc":"2.0","id":5,"method":"nope/nope"}')
body=$(printf '%s' "$r" | cut -f2-)
ok "未知方法 → -32601" "$([ "$(jget "$body" error.code)" = "-32601" ] && echo 1 || echo 0)"

echo "== 审计落盘（从设备侧读）=="
if [ -n "$URL" ]; then
  echo "  SKIP  直连模式，读不到设备文件系统"
  pkg=""
else
  pkg=$("$ADB" "${ADB_ARGS[@]}" shell pm list packages 2>/dev/null | grep -i polaris | head -1 | sed 's/package://' | tr -d '\r')
fi
if [ -z "$pkg" ]; then
  echo "  SKIP  没找到包名，跳过审计检查"
else
  audit_lines=$("$ADB" "${ADB_ARGS[@]}" shell "run-as $pkg cat files/mcp/audit/audit-*.jsonl 2>/dev/null | wc -l" 2>/dev/null | tr -d '\r')
  ok "审计文件有记录（release 包 run-as 会失败，视为 SKIP）" "$([ "${audit_lines:-0}" -gt 0 ] && echo 1 || echo 0)" "行数 ${audit_lines:-0}"
  audit_raw=$("$ADB" "${ADB_ARGS[@]}" shell "run-as $pkg cat files/mcp/audit/audit-*.jsonl 2>/dev/null | tail -3" 2>/dev/null | tr -d '\r')
  ok "审计含 argsHash 与 decision" "$(printf '%s' "$audit_raw" | grep -q 'argsHash' && printf '%s' "$audit_raw" | grep -q 'decision' && echo 1 || echo 0)"
fi

echo
echo "== 结果：$pass 通过 / $fail 失败 =="
[ "$fail" -eq 0 ] || exit 1
