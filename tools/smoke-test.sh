#!/bin/sh
# PhoneBox 冒烟测试
#
# 用法：
#   sh tools/smoke-test.sh http://192.168.1.23:8080 admin:随机密码
#   adb forward tcp:8080 tcp:8080
#   sh tools/smoke-test.sh http://127.0.0.1:8080 admin:随机密码
#   SMOKE_TEST_EVENT=1 sh tools/smoke-test.sh ...   # 额外触发测试事件/告警（会真实转发到启用通道）
set -e

BASE="${1:-http://127.0.0.1:8080}"
AUTH="${2:-admin:admin}"
H='X-Requested-With: phonebox'

echo "== ping（无需认证）"
curl -fsS "$BASE/api/ping"
echo

echo "== 未认证应为 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/status")
if [ "$CODE" != "401" ]; then
    echo "错误：未认证请求返回 $CODE，期望 401" >&2
    exit 1
fi
echo "OK 401"

echo "== 未认证访问首页应 302 到登录页"
curl -s -o /dev/null -D - "$BASE/" | grep -qi '^Location: /login' || {
    echo "错误：未认证首页没有跳转到 /login" >&2
    exit 1
}
echo "OK 302 /login"

echo "== 登录页（无需认证）"
curl -fsS "$BASE/login" | grep -q '登录设备面板' || {
    echo "错误：登录页内容异常" >&2
    exit 1
}
echo "OK /login"

echo "== 表单登录：错误密码应为 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -H 'Content-Type: application/json' \
    -d '{"user":"admin","pass":"definitely-wrong"}' "$BASE/api/login")
if [ "$CODE" != "401" ]; then
    echo "错误：错误密码返回 $CODE，期望 401" >&2
    exit 1
fi
echo "OK 401"

USER="${AUTH%%:*}"
PASS="${AUTH#*:}"
COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

echo "== 表单登录：正确密码 → 会话 Cookie"
curl -fsS -c "$COOKIE_JAR" -H "$H" -H 'Content-Type: application/json' \
    -d "{\"user\":\"$USER\",\"pass\":\"$PASS\"}" "$BASE/api/login"
echo

echo "== 会话状态"
curl -fsS -b "$COOKIE_JAR" "$BASE/api/session"
echo

echo "== Cookie 可访问需要认证的接口"
curl -fsS -b "$COOKIE_JAR" "$BASE/api/health"
echo

echo "== 带会话 Cookie 的首页"
curl -fsS -b "$COOKIE_JAR" "$BASE/" | grep -q 'class="tabs"' || {
    echo "错误：带会话 Cookie 没有拿到面板首页" >&2
    exit 1
}
echo "OK /"

echo "== 退出登录后 Cookie 失效"
curl -fsS -b "$COOKIE_JAR" -H "$H" -H 'Content-Type: application/json' \
    -d '{}' "$BASE/api/logout"
echo
CODE=$(curl -s -o /dev/null -w '%{http_code}' -b "$COOKIE_JAR" "$BASE/api/status")
if [ "$CODE" != "401" ]; then
    echo "错误：退出后请求返回 $CODE，期望 401" >&2
    exit 1
fi
echo "OK 401"

echo "== health"
curl -fsS -u "$AUTH" "$BASE/api/health"
echo

echo "== status"
curl -fsS -u "$AUTH" "$BASE/api/status"
echo

echo "== config（密钥脱敏）"
curl -fsS -u "$AUTH" "$BASE/api/config"
echo

echo "== messages"
curl -fsS -u "$AUTH" "$BASE/api/messages?limit=5"
echo

echo "== outbox"
curl -fsS -u "$AUTH" "$BASE/api/outbox?limit=5"
echo

echo "== 规则测试"
curl -fsS -u "$AUTH" -H "$H" -H 'Content-Type: application/json' \
    -d '{"peer":"10086","body":"【测试】验证码 123456"}' \
    "$BASE/api/rules/test"
echo

echo "== logs"
curl -fsS -u "$AUTH" "$BASE/api/logs?limit=20"
echo

echo "== index（Basic 不直接放行页面，应跳登录页）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$BASE/")
if [ "$CODE" != "302" ]; then
    echo "错误：Basic 抓首页返回 $CODE，期望 302" >&2
    exit 1
fi
echo "OK 302 /login"

echo "== 双卡号码（status.sims）"
curl -fsS -u "$AUTH" "$BASE/api/status" | python3 -c \
    'import sys,json; d=json.load(sys.stdin); print("顶层 sims 字段", "sims" in d, "卡数", len(d.get("sims", [])))'

echo "== 面板在线更新状态（只读取，不触发下载/安装）"
curl -fsS -u "$AUTH" "$BASE/api/update/status"
echo

echo "== 手动安装包状态（只读取）"
curl -fsS -u "$AUTH" "$BASE/api/local-update/status"
echo

echo "== 文件收件箱（只读取）"
curl -fsS -u "$AUTH" "$BASE/api/files"
echo

echo "== 便签（写入 → 读取 → 删除临时条目）"
NOTE=$(curl -fsS -u "$AUTH" -H "$H" -H 'Content-Type: application/json' \
    -d '{"text":"PhoneBox 冒烟测试便签","from":"pc"}' "$BASE/api/notes")
echo "$NOTE"
NOTE_ID=$(echo "$NOTE" | python3 -c \
    'import sys,json; print(json.load(sys.stdin).get("item",{}).get("id",""))')
if [ -n "$NOTE_ID" ]; then
    curl -fsS -u "$AUTH" -H "$H" -H 'Content-Type: application/json' \
        -d "{\"id\":\"$NOTE_ID\"}" "$BASE/api/notes/delete"
    echo
fi

echo "== manifest（无需认证）"
curl -fsS "$BASE/manifest.webmanifest" | head -5

if [ "${SMOKE_TEST_EVENT:-0}" = "1" ]; then
    echo "== 测试事件（会真实转发到启用通道）"
    curl -fsS -u "$AUTH" -H "$H" -H 'Content-Type: application/json' \
        -d '{"peer":"10086","body":"【PhoneBox 测试】验证码 123456"}' \
        "$BASE/api/test/event"
    echo
    echo "== 测试告警"
    curl -fsS -u "$AUTH" -H "$H" -H 'Content-Type: application/json' \
        -d '{"message":"冒烟测试告警"}' "$BASE/api/alerts/test"
    echo
fi

echo
echo "OK：$BASE"
