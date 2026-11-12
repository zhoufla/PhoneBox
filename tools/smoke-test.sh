#!/bin/sh
# PhoneBox 冒烟测试
#
# 用法：
#   sh tools/smoke-test.sh http://192.168.1.23:8080 admin:随机密码
#   adb forward tcp:8080 tcp:8080
#   sh tools/smoke-test.sh http://127.0.0.1:8080 admin:随机密码
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

echo "== index"
curl -fsS -u "$AUTH" "$BASE/" | head -5

echo
echo "OK：$BASE"
