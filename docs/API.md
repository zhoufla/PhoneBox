# PhoneBox HTTP API

面板和内嵌 API 由 `RelayService` 托管，默认端口 `8080`。

认证方式（三种任选其一）：

- **网页表单会话**：浏览器打开面板地址会跳转到独立登录页 `/login`，输入账号密码后服务端下发 `phonebox_sid` Cookie，后续请求自动携带。这是面板默认方式。
- **HTTP Basic**：`admin` / App 首页显示的随机密码，适合 `curl -u admin:密码`。
- **Bearer Token**：`Authorization: Bearer <token>`，适合脚本。

服务端不再返回 `WWW-Authenticate`，因此浏览器不会弹出原生认证框；未登录访问 `/` 会 302 到 `/login`，未登录调用 API 返回纯 401 JSON，前端据此跳转登录页。脚本主动发送 Basic / Bearer 头的行为不变。

页面（`/`、`/index.html`、`/login`）**只认会话 Cookie**，Basic / Bearer 仅用于 API。这样老版本浏览器缓存的 Basic 凭据不会绕过登录页，「退出登录」也才能真正生效；代价是 `curl -u ... /` 抓首页会拿到 302，抓页面请用会话 Cookie。

无需认证：`/login`、`/login.html`、`/api/login`、`/api/session`、`/api/ping`、`/manifest.webmanifest`、`/icon-192.png`、`/icon-512.png`、`/favicon.ico`。

所有写操作（POST，含 `/api/login`）必须带：

```text
X-Requested-With: phonebox
Content-Type: application/json
```

这是最低限度的 CSRF 防护；面板与登录页会自动带上。

## 认证与登录

### `GET /login`

独立登录页（单文件 HTML，无外链）。已登录时 302 回面板；`?next=` 只接受站内绝对路径，登录后回到该地址，可带 URL hash 以恢复原标签页。`&expired=1` 会显示「登录状态已过期」提示。

### `POST /api/login`

```json
{"user":"admin","pass":"App 首页显示的密码"}
```

成功返回 `{"ok":true}`，并下发：

```text
Set-Cookie: phonebox_sid=<64 位随机串>; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax
```

Cookie 是 `HttpOnly`（JS 读不到）、`SameSite=Lax`（配合 `X-Requested-With` 防 CSRF）。面板走明文 HTTP，因此**不加 Secure**，否则浏览器不会保存；请只在可信网络登录，公网访问建议加 HTTPS 反代。会话只存在应用进程内存：7 天绝对过期、24 小时无操作过期，App 重启后全部失效；修改账号或密码会让已有会话立即失效。

失败返回 401 `{"error":"账号或密码错误"}`。同一 IP 连续 5 次失败会锁定 60 秒，锁定期间返回 429 并带 `Retry-After`。

```sh
curl -c /tmp/phonebox.cookie -H 'X-Requested-With: phonebox' \
     -H 'Content-Type: application/json' \
     -d '{"user":"admin","pass":"随机密码"}' \
     http://192.168.1.23:8080/api/login
curl -b /tmp/phonebox.cookie http://192.168.1.23:8080/api/status
```

### `GET /api/session`

无需认证，供页面判断登录态：

```json
{"authed":true,"session":true}
```

`authed` 表示当前请求通过了任意一种认证（含 Basic / Bearer），`session` 只表示 Cookie 会话有效。

### `POST /api/logout`

需要认证。销毁当前会话并下发清除 Cookie 的 `Set-Cookie`，返回 `{"ok":true}`。面板右上角「退出」按钮调用它。

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
  "version": "0.8.1",
  "running": true,
  "uptimeSec": 123,
  "heartbeatAgeSec": 3,
  "panel": {"running": true, "port": 8080, "error": ""},
  "watchdog": {
    "alarmAt": 1760000900000, "alarmInSec": 845, "alarmExact": true,
    "alarmCount": 12, "lastAlarmAt": 1760000000000,
    "lastJobAt": 1760000000000, "jobCount": 11, "jobScheduled": true,
    "batteryOptimized": false, "workerAlive": true, "serviceRunning": true
  },
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
  "monitor": {
    "ts": 1760000000000,
    "uptimeSec": 266400,
    "battery": {
      "level": 87, "status": "放电中", "plugged": "", "health": "良好",
      "tempC": 31.2, "voltageMv": 4050
    },
    "signal": {
      "operator": "中国移动", "type": "LTE", "level": 4, "dbm": -72,
      "sims": [
        {"slot": 1, "subId": 1, "carrier": "中国移动", "display": "SIM 1",
         "number": "13800138000", "source": "manual", "label": "13800138000",
         "type": "LTE", "level": 4, "dbm": -72}
      ]
    },
    "thermal": {
      "maxC": 42.0,
      "zones": [{"name": "cpu-0-0", "tempC": 42.0}, {"name": "gpu", "tempC": 38.0}]
    },
    "cpu": {
      "cores": 8, "usagePct": 23, "load1": 0.52, "load5": 0.61, "load15": 0.58,
      "freqMhz": [1780, 2840], "maxMhz": 2840
    },
    "memory": {"totalMb": 5700, "availableMb": 2100, "usedPct": 63},
    "storage": {"dataTotalMb": 113600, "dataFreeMb": 82000, "dataUsedPct": 27},
    "network": {
      "connected": true, "type": "wifi", "iface": "wlan0",
      "ipv4": ["192.168.1.23"], "ipv6": ["240e:..."],
      "rxBytes": 3221225472, "txBytes": 536870912
    }
  },
  "ddns": {
    "enabled": true, "family": "v6", "intervalMin": 10, "records": 1,
    "ran": true, "ok": true, "ip": "240e:...",
    "message": "已更新 1 条记录", "warning": "",
    "at": 1760000000000, "updated": 1, "unchanged": 0
  },
  "alerts": {
    "enabled": true, "notify": true, "intervalMin": 5, "cooldownMin": 30,
    "channels": [], "active": [], "lastCheckAt": 1760000000000, "lastError": ""
  },
  "sims": [
    {"slot": 1, "subId": 1, "carrier": "中国移动", "display": "SIM 1",
     "number": "13800138000", "source": "manual", "label": "13800138000"},
    {"slot": 2, "subId": 2, "carrier": "中国移动", "display": "SIM 2",
     "number": "", "source": "", "label": "卡2"}
  ],
  "config": {"web": {"port": 8080, "user": "admin"}}
}
```

`monitor` 在配置 `monitor.enabled=false` 时不会出现。字段可能缺失（权限不足或 ROM 不提供），
前端按“—”处理。`cpu.usagePct` 的第一次调用会补一次 120ms 采样。

`alerts.active` 是当前未恢复的告警，元素形如
`{"key":"battery_high","title":"长期满充","message":"...","at":1760000000000}`；
`channels` 为告警外推通道名列表，空数组表示只发本地通知。

`sims` 是 `/api/status` 顶层的双卡信息（`monitor.enabled=false` 时依然存在），与
`monitor.signal.sims` 同源，后者额外补了逐卡信号。`source` 为 `manual`（面板手动填写）、
`sim`（系统读取）或空（读不到号码）；`label` 是给模板用的显示值，号码读不到时回退「卡N」。

信号和温度都是尽力而为：

- `signal` 依赖 `READ_PHONE_STATE`，双卡时 `sims` 逐卡读取，读不到就只留默认卡
- `thermal` 读 `/sys/class/thermal/thermal_zone*/temp`，兼容毫摄氏度与摄氏度
- `storage` 只统计应用数据分区；`network.rxBytes/txBytes` 是开机以来的累计流量

### `GET /api/config`

返回当前配置。面板密码、API Token、通道 secret/token、Cloudflare Token 均为 `******`。

### `POST /api/config`

只覆盖传入的顶层字段，支持：`device`、`web`、`sms`、`channels`、`rules`、`rulesDefault`、`ddns`、`monitor`、`alerts`、`sims`、`retention`。

`sims` 段字段：`slot1`、`slot2`（手动填写的本机号码，只接受数字，可带 `+`、空格、连字符）、
`autoSimLine`（默认 `true`）。手动号码优先于系统读取值，留空表示清除。

`alerts` 段字段：`enabled`、`notify`、`intervalMin`(1-60)、`cooldownMin`(1-1440)、
`channels`(通道名数组，留空不外推)、`thresholds`（`lowPct`、`highPct`、`tempC`、`pending`、
`heartbeatSec`、`failStreak`、`highSustainMin`、`netSustainMin`）。

密钥字段留空或传 `******` 表示保留原值。

```sh
curl -u admin:PASS -H 'X-Requested-With: phonebox' \
  -H 'Content-Type: application/json' \
  -d '{"sms":{"enabled":true,"dedupWindowSec":90}}' \
  http://192.168.1.23:8080/api/config
```

修改端口返回 `{"ok":true,"restart":true}`，面板会在 0.5 秒后自动重启。

规则校验失败（坏正则、重名、引用不存在的通道）返回 400，旧配置不变。

### 双卡号码与转发模板

概览显示 `sims` 数组里的两张卡：卡槽、运营商、号码及来源。国内 SIM 通常不写 EF_MSISDN，
系统 API（`SubscriptionManager.getPhoneNumber` / `SubscriptionInfo.getNumber` /
`TelephonyManager.getLine1Number`）大概率返回空，建议在设置页手动填写卡 1 / 卡 2 号码。

转发模板新增 `{sim}`（接收短信那张卡的号码，读不到时回退「卡N」）与 `{slot}`（卡槽号）。
默认模板已含 `接收号码：{sim}`；自定义模板没写 `{sim}` 且 `sims.autoSimLine=true` 时，
自动在末尾追加一行。占位符全集：
`{peer}` `{body}` `{code}` `{device}` `{tag}` `{time}` `{sub}` `{sim}` `{slot}`。

Webhook 事件 JSON 同步新增 `sim` / `slot` 字段（有值时才出现）；钉钉、Telegram 走文本模板渲染。
告警外推不附加接收号码。

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

### `POST /api/test/event`

构造一条假短信，走真实的「入库 → 去重 → 规则 → 通道」链路，用来验证转发配置，不消耗 SIM 短信。

```json
{"peer": "10086", "body": "【测试】验证码 123456", "subId": -1}
```

```json
{"test":true,"channels":1,"ok":true,"id":42,"drop":false,
 "rule":"验证码优先","targets":["dingtalk"]}
```

- `peer` 留空默认 `10086`，`body` 留空使用内置测试文本；正文会自动追加 `\n[测试 #HHmmss]`，避免 90 秒去重窗口把连续两次测试合并。
- 测试事件使用 0 秒去重窗口，其余与普通短信完全一致：写 `ev`/`dl` 表、可在短信列表重发、计入统计。
- 转发关闭时返回 `{"ok":false,"warning":"短信转发已关闭，事件未入库"}`；没有启用通道时仍入库，但带 `warning`，不会外发。两种情况均为 HTTP 200，方便面板展示原因。

### `POST /api/alerts/test`

手动触发一条测试告警，绕过阈值与冷却，验证通知与外推链路：

```json
{"message": "测试告警"}
```

```json
{"ok":true,"notified":true,"delivered":0}
```

告警不写短信库、不进重试队列；只有 `alerts.channels` 非空时才外推，推送失败只写日志。

## 面板在线更新

与 App 首页共用 `GithubUpdater`：检查 GitHub 最新正式 Release → 校验 `latest.json` 与 APK 附件 →
下载到应用私有缓存并校验大小、SHA-256、包名与版本码 → 用 `PackageInstaller` 提交安装，
系统安装器仍要求用户确认。不会静默安装。

| 接口 | 说明 |
|---|---|
| `GET /api/update/status` | 返回当前任务状态，纯内存读取，不联网，可轮询 |
| `POST /api/update/check` | 异步发起检查，立即返回 `{ok,started}` |
| `POST /api/update/install` | 异步下载 + 提交安装，立即返回 `{ok,started}` |

三个接口都需要鉴权；两个 POST 还要求 `X-Requested-With: phonebox`。重复发起、无可用更新、
未允许「安装未知应用」时返回 `{"ok":false,"started":false,"warning":"..."}`（HTTP 200）。

```json
{
  "state": "available", "message": "发现新版本 0.8.1（当前版本码 800）",
  "busy": false, "downloaded": 0, "total": 0, "percent": 0, "at": 1760000000000,
  "release": {"version": "0.8.1", "tag": "v0.8.1",
    "apk": "com.zhoufla.phonebox-0.8.1.apk", "code": 801, "installedCode": 800,
    "size": 103241, "sha256": "...", "notes": "...", "updateAvailable": true}
}
```

`state`：`idle` / `checking` / `available` / `up_to_date` / `downloading` / `installing` /
`confirm`（等待用户在系统弹窗确认）/ `installed` / `error` / `need_permission`。
`busy=true` 或 `state=confirm` 时前端每 1.5 秒轮询一次。安装成功后进程重启，面板短暂断开属正常现象。

未授予 `REQUEST_INSTALL_PACKAGES` 时不会尝试安装，状态置为 `need_permission`，
需到系统设置或 App 首页授权一次。安装结果由 `UpdateInstallReceiver` 回写状态并写入运行日志。

## 手动上传安装包

GitHub 打不开或 API 限流（HTTP 403/429）时，可以在设置页直接上传 APK。上传后先校验，再由用户点安装：

| 接口 | 说明 |
|---|---|
| `POST /api/files/upload?name=<文件名>&kind=apk` | 原始字节上传（`Content-Type: application/octet-stream`），暂存并校验 |
| `GET /api/local-update/status` | 待安装包信息与状态，纯内存读取，可轮询 |
| `POST /api/local-update/install` | 交给系统安装器，立即返回 `{ok,started}` |
| `POST /api/local-update/clear` | 丢弃待安装包 |

校验规则：包名必须是 `com.zhoufla.phonebox`，签名证书 SHA-256 必须与已安装应用一致；
允许同版本重装，不要求版本码递增（系统仍会拒绝降级）。校验通过后状态为 `ready`。

```json
{"ok":true,"state":"ready","message":"安装包已就绪：0.8.2（802），签名一致",
 "apk":{"package":"com.zhoufla.phonebox","version":"0.8.2","code":802,
        "installedCode":801,"size":115000,"signer":"9f2c…","samePackage":true,
        "sameSigner":true,"sameVersion":false}}
```

`state`：`idle` / `ready` / `need_permission` / `installing` / `confirm` / `installed` / `error`。
安装结果由 `LocalInstallReceiver` 回写，与在线更新完全独立，互不覆盖状态。

## 文件收件箱

上传的文件直接写入手机公共「下载/PhoneBox」目录（Android 10+ 走 MediaStore，不需要存储权限）。

| 接口 | 说明 |
|---|---|
| `POST /api/files/upload?name=<文件名>&kind=file` | 原始字节上传，返回保存后的条目 |
| `GET /api/files` | 列出已上传文件与总占用 |
| `GET /api/files/download?id=<id>` | 鉴权下载（流式，带 `Content-Disposition`） |
| `POST /api/files/delete` | `{"id":"..."}`，同时删除手机上的文件 |

单文件默认 30 MB、总量 100 MB、保留 30 天，超出时按时间从旧到新清理。
文件名会被清洗（去掉路径分隔符与控制字符），同名文件由系统自动加序号，不会覆盖。

```sh
curl -u admin:密码 -H "X-Requested-With: phonebox" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @report.pdf \
  "http://192.168.1.23:8080/api/files/upload?name=report.pdf&kind=file"
```

## 便签

电脑往面板贴一段文本，手机打开同一页面一键复制。存 `files/notes.json`，最多 100 条、单条 8192 字。

| 接口 | 说明 |
|---|---|
| `GET /api/notes` | `{items:[{id,ts,text,from}],count,maxItems,maxText}`，最新在前 |
| `POST /api/notes` | `{"text":"...","from":"pc" 或 "phone"}` |
| `POST /api/notes/delete` | `{"id":"..."}` |
| `POST /api/notes/clear` | 清空全部 |

`from` 由前端按 User-Agent 判断，仅用于展示「电脑 / 手机」。

## 上传约定

- 上传请求必须声明 `Content-Length`，`Content-Type: application/octet-stream`；
- 其他接口仍只接受 1 MiB 以内的 UTF-8 JSON；上传硬上限 64 MiB，业务上限见 `transfer.maxFileBytes`；
- 所有上传接口都需要鉴权，POST 仍需 `X-Requested-With: phonebox`；超限返回 413。

## 保活

### `POST /api/watchdog/rearm`

撤销并重新布防心跳闹钟与周期任务，同时确保服务在运行：

```json
{
  "alarmAt": 1760000900000,
  "alarmInSec": 900,
  "alarmExact": true,
  "alarmCount": 3,
  "lastAlarmAt": 1760000000000,
  "lastJobAt": 1760000000000,
  "jobCount": 2,
  "jobScheduled": true,
  "batteryOptimized": false,
  "workerAlive": true,
  "serviceRunning": true
}
```

- `alarmExact=false` 表示系统拒绝精确闹钟，已退化为 `setAndAllowWhileIdle`。
- `batteryOptimized=true` 表示尚未加入电池优化白名单，MIUI 上更容易被杀。
- `workerAlive=false` 说明投递线程已退出，服务会在下一次心跳前自动重启。
- 用户主动“停止服务”会撤销闹钟与周期任务，不会自行复活。

## DDNS

### `POST /api/ddns/run`

立即执行一次 Cloudflare 同步，用于保存配置后测试（即使 `ddns.enabled=false` 也会执行）：

```json
{
  "ok": true,
  "skipped": false,
  "ip": "240e:...",
  "family": "v6",
  "message": "已更新 1 条记录",
  "warning": "",
  "at": 1760000000000,
  "updated": 1,
  "unchanged": 0,
  "details": ["home.example.com 已更新 240e:... → 240e:..."]
}
```

失败时 `ok=false`，`message` 给出原因（Token 无权限、Zone 不存在、取不到 IPv6 等），
`details` 保留每条记录的结果。同步串行执行，自动检查与手动点击不会并发写 Cloudflare。

配置在 `POST /api/config` 的 `ddns` 字段：

```json
{
  "ddns": {
    "enabled": true,
    "provider": "cloudflare",
    "token": "留空或 ****** 表示不改",
    "zone": "example.com 或 32 位 Zone ID",
    "intervalMin": 10,
    "family": "v6",
    "records": [
      {"name": "home.example.com", "ttl": 120, "proxied": false}
    ]
  }
}
```

- `family=v6` 写 AAAA，`v4` 写 A。记录数组也接受纯字符串，会被规范成对象。
- 地址优先取 `wlan*`，其次 `eth*`；IPv6 跳过链路本地和 `fc00::/7`。
- 地址与 Cloudflare 现有记录一致时不调用写接口，只计数 `unchanged`。
- IPv4 为内网/CGNAT 时仍会更新，但返回 `warning` 提示无法公网直达。
- Token 只需要 `Zone:DNS:Edit`，建议再限定到单个 Zone；IPv6 直连保持 `proxied=false`。

## 日志

### `GET /api/logs?limit=100`

```json
{"lines":["14:00:00 服务启动","14:00:00 面板已启动：8080"]}
```

`limit` 1–500。日志只保存在内存，重启清空；不记录短信正文和密钥。

### `GET /api/logs/download`

返回最近 500 行纯文本，带 `Content-Disposition: attachment; filename="phonebox-logs.txt"`，
可直接用浏览器或 `curl -OJ` 保存：

```sh
curl -u admin:密码 -OJ http://192.168.1.23:8080/api/logs/download
```

## 约定

- 请求/响应均为 UTF-8 JSON（文件上传用原始字节，见「上传约定」）。
- 成功 200；参数错误 400；未认证 401；CSRF 头缺失 403；不存在 404；请求体过大 413；内部错误 500。
- 错误体：`{"error":"..."}`。
- 默认不发送 CORS 头。
- 面板是 HTTP，公网暴露前请阅读 [`MIUI-KEEPALIVE.md`](MIUI-KEEPALIVE.md) 的安全说明。
