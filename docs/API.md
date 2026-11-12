# PhoneBox HTTP API

面板和内嵌 API 由 `RelayService` 托管，默认端口 `8080`。

认证方式：

- HTTP Basic：`admin` / App 首页显示的随机密码
- Bearer Token：`Authorization: Bearer <token>`
- `/api/ping` 无需认证

所有写操作（POST）必须带：

```text
X-Requested-With: phonebox
Content-Type: application/json
```

这是最低限度的 CSRF 防护；面板页面会自动带上。

## 公共

### `GET /api/ping`

无需认证，用于探活：

```json
{"ok":true,"app":"phonebox"}
```

### `GET /api/health`

需要认证：

```json
{"ok":true,"running":true,"panel":true,"pending":0,"heartbeatAgeSec":3}
```

## 状态与配置

### `GET /api/status`

```json
{
  "app": "phonebox",
  "version": "0.2.0",
  "running": true,
  "uptimeSec": 123,
  "heartbeatAgeSec": 3,
  "panel": {"running": true, "port": 8080, "error": ""},
  "device": {"model": "MI 9", "android": "11", "sdk": 30},
  "addresses": [
    {"iface": "wlan0", "ip": "192.168.1.23", "url": "http://192.168.1.23:8080"},
    {"iface": "wlan0", "ip": "240e:...", "url": "http://[240e:...]:8080"}
  ],
  "sms": {
    "enabled": true,
    "pending": 0,
    "delivered": 12,
    "failed": 1,
    "events": 20,
    "outbox": 3,
    "lastError": "",
    "channels": ["dingtalk", "telegram"]
  },
  "config": {"web": {"port": 8080, "user": "admin"}}
}
```

### `GET /api/config`

返回当前配置。面板密码、API Token、通道 secret/token、Cloudflare Token 均为 `******`。

### `POST /api/config`

只覆盖传入的顶层字段，支持：`device`、`web`、`sms`、`channels`、`rules`、`rulesDefault`、`ddns`、`monitor`、`retention`。

密钥字段留空或传 `******` 表示保留原值。

```sh
curl -u admin:PASS -H 'X-Requested-With: phonebox' \
  -H 'Content-Type: application/json' \
  -d '{"sms":{"enabled":true,"dedupWindowSec":90}}' \
  http://192.168.1.23:8080/api/config
```

修改端口返回 `{"ok":true,"restart":true}`，面板会在 0.5 秒后自动重启。

规则校验失败（坏正则、重名、引用不存在的通道）返回 400，旧配置不变。

## 短信

### `GET /api/messages?limit=20&offset=0`

```json
{
  "total": 42,
  "items": [
    {
      "id": 42,
      "ts": 1760000000000,
      "iso": "2026-11-12 14:00:00",
      "peer": "10086",
      "body": "验证码 123456",
      "code": "123456",
      "subId": 1,
      "rule": "验证码优先",
      "deliveries": [
        {"ch": "dingtalk", "status": 1, "tries": 0, "err": "", "nextAt": 0}
      ]
    }
  ]
}
```

投递状态：`0` 待发送 / `1` 已送达 / `2` 放弃。

### `POST /api/messages/retry`

把某条消息的失败/放弃投递重新排队，并补上当前所有启用通道：

```json
{"id": 42}
```

```json
{"ok":true,"queued":2}
```

### `GET /api/outbox?limit=20&offset=0`

```json
{
  "total": 3,
  "items": [
    {"id": 3, "iso": "2026-11-12 14:05:00", "peer": "10086",
     "body": "CXLL", "parts": 1, "subId": 1, "status": 1, "err": ""}
  ]
}
```

发件状态：`0` 发送中 / `1` 已发送 / `2` 失败 / `3` 结果未知（进程重启）。

### `POST /api/send`

```json
{"to": "10086", "body": "CXLL", "subId": 1}
```

`subId` 省略或 `-1` 使用配置里的默认卡。

```json
{"ok":true,"id":3}
```

注意：`ok` 只表示已提交给系统短信栈，最终结果看 `/api/outbox`。

### `POST /api/inbox/import`

只读系统收件箱，补收最近 N 条（默认 50，最大 200）。已转发过的消息按时间窗口去重：

```json
{"ok":true,"imported":2}
```

读取失败（如 READ_SMS 被 ROM 限制）返回 `{"ok":false,"imported":-1,"error":"..."}`。

### `POST /api/rules/test`

```json
{"peer": "10086", "body": "【示例】验证码 123456"}
```

```json
{"drop":false,"rule":"验证码优先","matched":["验证码优先"],
 "targets":["dingtalk"],"code":"123456"}
```

## 日志

### `GET /api/logs?limit=100`

```json
{"lines":["14:00:00 服务启动","14:00:00 面板已启动：8080"]}
```

`limit` 1–500。日志只保存在内存，重启清空；不记录短信正文和密钥。

## 约定

- 请求/响应均为 UTF-8 JSON。
- 成功 200；参数错误 400；未认证 401；CSRF 头缺失 403；不存在 404；内部错误 500。
- 错误体：`{"error":"..."}`。
- 默认不发送 CORS 头。
- 面板是 HTTP，公网暴露前请阅读 [`MIUI-KEEPALIVE.md`](MIUI-KEEPALIVE.md) 的安全说明。
