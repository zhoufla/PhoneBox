#!/bin/sh
# PhoneBox 浸泡测试：按固定间隔采样 /api/status，记录 CSV 并标出异常。
#
# 用法：
#   adb forward tcp:8080 tcp:8080
#   sh tools/soak-test.sh http://127.0.0.1:8080 admin:随机密码 72
#
# 参数：BASE AUTH [HOURS]；间隔用 INTERVAL 秒覆盖，默认 60。
# 输出：soak-YYYYmmdd-HHMMSS.csv 与同名 .alerts.log（当前目录，可用 OUT 指定前缀）。
# 依赖：curl、python3。
set -u

BASE="${1:-http://127.0.0.1:8080}"
AUTH="${2:-admin:admin}"
HOURS="${3:-72}"
INTERVAL="${INTERVAL:-60}"
PREFIX="${OUT:-soak-$(date +%Y%m%d-%H%M%S)}"
CSV="$PREFIX.csv"
ALERTS="$PREFIX.alerts.log"

case "$INTERVAL" in
    ''|*[!0-9]*) echo "INTERVAL 必须是整数秒" >&2; exit 2 ;;
esac
case "$HOURS" in
    ''|*[!0-9]*) echo "HOURS 必须是整数" >&2; exit 2 ;;
esac

command -v curl >/dev/null 2>&1 || { echo "缺少 curl" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3" >&2; exit 2; }

echo "time,running,uptimeSec,heartbeatAgeSec,panel,worker,pending,ddnsOk,ddnsUpdated,battery,alarmCount,jobCount" > "$CSV"
: > "$ALERTS"

END=$(( $(date +%s) + HOURS * 3600 ))
SAMPLES=0
FAILS=0

echo "开始浸泡：$BASE，每 ${INTERVAL}s 一次，计划 ${HOURS}h"
echo "CSV：$CSV"
echo "异常：$ALERTS"

while [ "$(date +%s)" -lt "$END" ]; do
    BODY=$(curl -sS -m 15 -u "$AUTH" "$BASE/api/status" 2>/dev/null)
    NOW=$(date '+%Y-%m-%d %H:%M:%S')

    if [ -z "$BODY" ]; then
        FAILS=$((FAILS + 1))
        echo "$NOW 请求失败（面板无响应）" >> "$ALERTS"
        echo "$NOW,down,,,,,,,,,," >> "$CSV"
        sleep "$INTERVAL"
        continue
    fi

    ROW=$(printf '%s' "$BODY" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("badjson,,,,,,,,,,")
    raise SystemExit
m = d.get("monitor") or {}
b = (m.get("battery") or {}).get("level")
dd = d.get("ddns") or {}
w = d.get("watchdog") or {}
print(",".join(str(x) for x in [
    1 if d.get("running") else 0,
    d.get("uptimeSec", ""),
    d.get("heartbeatAgeSec", ""),
    1 if (d.get("panel") or {}).get("running") else 0,
    1 if w.get("workerAlive") else 0,
    (d.get("sms") or {}).get("pending", ""),
    ("" if not dd.get("ran") else (1 if dd.get("ok") else 0)),
    dd.get("updated", ""),
    "" if b is None else b,
    w.get("alarmCount", ""),
    w.get("jobCount", ""),
]))
')

    if [ "${ROW#badjson}" != "$ROW" ]; then
        FAILS=$((FAILS + 1))
        echo "$NOW 返回内容不是 JSON" >> "$ALERTS"
        echo "$NOW,$ROW" >> "$CSV"
    else
        SAMPLES=$((SAMPLES + 1))
        echo "$NOW,$ROW" >> "$CSV"
        RUNNING=$(printf '%s' "$ROW" | cut -d, -f1)
        HEARTBEAT=$(printf '%s' "$ROW" | cut -d, -f3)
        PANEL=$(printf '%s' "$ROW" | cut -d, -f4)
        WORKER=$(printf '%s' "$ROW" | cut -d, -f5)
        DDNS=$(printf '%s' "$ROW" | cut -d, -f7)
        if [ "$RUNNING" != "1" ]; then
            FAILS=$((FAILS + 1))
            echo "$NOW 服务未运行：$ROW" >> "$ALERTS"
        elif [ "$HEARTBEAT" -gt 180 ] 2>/dev/null; then
            FAILS=$((FAILS + 1))
            echo "$NOW 心跳过期 ${HEARTBEAT}s：$ROW" >> "$ALERTS"
        elif [ "$PANEL" != "1" ]; then
            FAILS=$((FAILS + 1))
            echo "$NOW 面板未运行：$ROW" >> "$ALERTS"
        elif [ "$WORKER" != "1" ]; then
            FAILS=$((FAILS + 1))
            echo "$NOW 投递线程未运行：$ROW" >> "$ALERTS"
        elif [ "$DDNS" = "0" ]; then
            echo "$NOW DDNS 失败：$ROW" >> "$ALERTS"
        fi
    fi

    sleep "$INTERVAL"
done

echo "结束：采样 $SAMPLES 次，异常 $FAILS 次"
[ -s "$ALERTS" ] && echo "请查看 $ALERTS"
exit 0
