# PhoneBox 开发方案

> 目标：把一台普通 Android 手机（小米 9 / MIUI 12.5 / Android 11）变成无 root 的短信网关，
> 通过网页完成短信转发、短信发送、系统监控和 Cloudflare DDNS 配置。
>
> 已确认：Java + 无 Gradle；通道 Webhook / 钉钉 / Telegram；不做默认短信 App；
> DDNS 主要使用 WiFi 下获取的 IPv6 直连。

## 1. 技术基线

| 项 | 决定 |
|---|---|
| 设备 | 小米 9（cepheus），MIUI 12.5，Android 11 |
| minSdk / targetSdk / compileSdk | 26 / 33 / 34 |
| 语言与构建 | Java 8，`build.sh`（aapt → javac → d8 → zipalign → apksigner） |
| 依赖 | 仅 Android 框架 API + `org.json`；无 AndroidX、无 Gradle、无第三方库 |
| 面板 | 纯 Java `ServerSocket` HTTP/1.1 + 单页 HTML，无 CDN |
| 数据 | `SQLiteOpenHelper`（后续阶段），配置文件在应用内部存储 |
| 短信身份 | 非默认短信 App；监听 `SMS_RECEIVED`，可选 `READ_SMS` 补收 |
| 远程访问 | WiFi IPv6 直连 + Cloudflare AAAA；IPv4 仅在有公网 + 端口映射时可选 |

`targetSdk 33` 的原因：侧载自用，不需要满足 Play 政策；33 可以避开 Android 14 对
前台服务类型和精确闹钟的强制要求。后续如需上架，再升 target 34/35 并补
`foregroundServiceType`、`FOREGROUND_SERVICE_*` 权限。

## 2. 总体架构

```text
MainActivity（只显示地址/状态/权限检查）
        │ startForegroundService
RelayService（唯一常驻）
  ├─ SmsReceiver（M1） → Db → Deliver → Webhook / 钉钉 / Telegram
  ├─ SmsSender（M1） ← WebPanel /api/send
  ├─ WebPanel（Http + 路由 + 静态页 + JSON API）
  ├─ Monitor（Battery / Signal / Thermal / Net，M3）
  ├─ Ddns（Cloudflare AAAA/A，M4）
  └─ Watchdog（心跳 / Alarm / Job / 通知，M5）
```

### 模块来源

| 直接复用/小改 msm8916-box | 说明 |
|---|---|
| `Http` / `WebPanel` | 内嵌 HTTP、静态资源、Basic/Bearer 鉴权 |
| `Db` | `ev` / `dl` / `out` 三表，补 `sub_id`、索引和迁移 |
| `Channel` / `Deliver` / `Tpl` / `Retry` | 多通道、模板、退避重试 |
| `AlertLogic` / `DdnsLogic` / `Logs` | 告警翻转、DDNS 变更判断、环形日志 |
| `SmsReceiver` / `SmsSender` | 改现代 `Telephony.Sms.Intents`、分卡 `SmsManager`、PendingIntent 标志 |
| `build.sh` / 离线测试脚本 | 改包名、SDK 版本、target 33 |

| 必须重写/移除 | 原因 |
|---|---|
| `RelayService` 生命周期 | 4.4 的保活方式不适用于 Android 11+ |
| `SysInfo` / `Radio` / `CpuFreq` | 4.4 的 `dumpsys`/`getprop` 路径，改为现代 API |
| `AppConfig` | `/sdcard` 根目录在 scoped storage 下不可写 |
| `Net` | 现代 Android 直接用 `HttpsURLConnection` |
| `Root` / `Maint` / `WifiCtl` / `MobileKeepalive` | 无 root，全部移除 |
| `Hostname` | `WRITE_SETTINGS` 在新系统不可行 |

## 3. 保活设计

无 root 不可能“杀不死”，目标是**系统允许范围内最大化 + 可观测 + 可恢复**。

| 层 | 实现 |
|---|---|
| 常驻 | 前台服务 + 低优先级常驻通知 + `START_STICKY` + `stopWithTask=false` |
| 开机 | `BOOT_COMPLETED`、`QUICKBOOT_POWERON`、`MY_PACKAGE_REPLACED` |
| 定时 | `AlarmManager` 每 15 分钟心跳检查；优先精确闹钟，拒绝则 `setAndAllowWhileIdle` |
| 兜底 | `JobScheduler` 周期任务（15 分钟，persisted）做 DDNS、清理、心跳检查 |
| 进程内 | 心跳时间戳 + 面板 socket 探活；WebPanel 线程死了就地重启 |
| 系统事件 | 网络变化、电池 sticky、`TelephonyCallback`、热状态监听 |
| 短信唤醒 | `SMS_RECEIVED` 高优先级广播；receiver 先落库，能直投就直投，否则入队 |
| MIUI | 自启动、省电策略“无限制”、锁定后台、允许通知、忽略电池优化 |

关键约束：

- Android 12+ 后台启动前台服务有限制，不能假设短信广播一定能拉起 FGS。
- `force-stop` 后应用处于 stopped 状态，静态广播/闹钟/Job 全部失效，只能用户手动打开。
- 不持有永久 `PARTIAL_WAKE_LOCK`；短信本身会唤醒 CPU，面板/DDNS 按需唤醒。
- 目标空闲耗电 < 3%/天（息屏 + WiFi 常连）。

## 4. 短信链路

### 接收

- 静态注册 `SMS_RECEIVED`，`priority=999`，权限 `RECEIVE_SMS`。
- `Telephony.Sms.Intents.getMessagesFromIntent()` 合并多段短信，取发件人、正文、时间戳、`subscriptionId`。
- 去重：号码 + 正文 + 90 秒窗口（可配置），另加 `sub_id`。
- 入库后立即返回；投递由常驻服务/Job 处理。
- 非默认短信 App 也能收到 `SMS_RECEIVED`；MIUI 上如遇 ROM 限制，再评估兼容模式。

### 转发

- 通道：通用 Webhook、钉钉机器人（含加签）、Telegram Bot。
- 规则：号码正则、正文正则、验证码提取、生效时段、优先级、`stop`、`drop`、模板覆盖。
- 模板占位符：`{peer}` `{body}` `{code}` `{device}` `{tag}` `{time}` `{sub}`。
- 退避：1/2/4/8/16/30 分钟；成功后不再重发；每个通道独立状态。
- 通道密钥面板只显示掩码，留空保存表示保持原值。

### 发送

- `SmsManager.getSmsManagerForSubscriptionId(subId)`，双卡选择。
- `sendMultipartTextMessage` 分段，每段独立 `PendingIntent`；Android 12+ 用 `FLAG_IMMUTABLE`。
- 状态：排队 / 发送中 / 成功 / 失败 / 结果待确认。
- 超时不等于成功，不自动盲目重发；由用户确认后再发。
- 提供 Bearer Token 鉴权的 `/api/send`，方便外部脚本/Home Assistant 调用。

### 补收

- `READ_SMS` 查询 `content://sms/inbox`，按去重键补入队列。
- 部分新 ROM 可能收紧 `READ_SMS`；失败时降级为只收广播，不影响主链路。

## 5. Web 面板

- 纯 Java HTTP/1.1，默认 8080，可配置；端口占用明确报错。
- HTTP Basic + Bearer Token；首次启动生成随机密码；恒时比较；失败限速。
- 默认关闭 CORS；POST 校验来源/自定义头防 CSRF。
- 单页 HTML，移动端优先；5 秒轮询刷新（SSE 留到后续需要时再加）。
- 页面：概览、短信、发送、通道、规则、监控、DDNS、日志、设置、关于。
- App 首页只显示服务状态、面板地址、账号密码、权限/保活检查清单和跳转按钮。

### API 规划

```text
GET  /api/status          服务/队列/设备概览
GET  /api/sys             电量/信号/温度/CPU/内存/网络
GET  /api/messages        转发记录分页
GET  /api/outbox          发件记录分页
GET  /api/ddns            DDNS 状态
GET  /api/logs            日志
GET  /api/config          当前配置（密钥脱敏）
GET  /api/health          健康探针
POST /api/send            发短信
POST /api/messages/retry  重发
POST /api/inbox/import    补收系统收件箱
POST /api/rules/test      规则匹配测试
POST /api/config          保存配置
POST /api/ddns/now        立即更新 DDNS
```

## 6. 系统监控

| 项 | 实现 | 限制 |
|---|---|---|
| 电量/充电 | `ACTION_BATTERY_CHANGED` sticky + `BatteryManager` | 无 |
| 电池温度 | `EXTRA_TEMPERATURE`（0.1℃） | 不是 CPU 温度 |
| 信号 | `TelephonyCallback` / `PhoneStateListener`，按 subId 分卡 | 需要 `READ_PHONE_STATE` |
| 热状态 | `PowerManager.getCurrentThermalStatus()` | 只有等级，没有度数 |
| CPU/SoC 温度 | 尽力读 `/sys/class/thermal/thermal_zone*/type|temp` | SELinux 可能拒绝；读不到显示“系统限制” |
| CPU/内存/存储 | `/proc/stat`、`ActivityManager.MemoryInfo`、`StatFs` | 面板打开时采样 |
| 网络 | `TrafficStats`、`NetworkInterface`、`ConnectivityManager` | WiFi SSID 新系统需要定位权限，可选 |

采样原则：监听类常驻，计算类按需。不要在后台定时采样 CPU/内存。

## 7. DDNS（Cloudflare）

- API：Cloudflare v4，Token 仅授予目标 Zone 的 `DNS Edit`，不用 Global Key。
- 记录：多条 `domain + iface + family`，A/AAAA 分开。
- IPv6：枚举 `NetworkInterface` 全球单播地址，排除 `fe80::/10`、`fc00::/7`、`::1`；
  尽力读 `/proc/net/if_inet6` 排除 temporary/deprecated 地址。
- IPv4：`https://www.cloudflare.com/cdn-cgi/trace` 或 `api.ipify.org` 查公网 IP；
  检测到 NAT/CGNAT 时提示“需要端口映射，否则 A 记录不可达”。
- 更新：变更才 PATCH/POST，TTL 60～300，`proxied=false`；5～10 分钟巡检 + 网络切换触发。
- 安全：Token 用 Android Keystore AES-GCM 加密；日志/导出脱敏。

现实限制：

- WiFi 下公网 IPv4 是路由器的；没有端口映射访问不到。
- 蜂窝 IPv4 基本是 CGNAT。
- 主推 WiFi IPv6 直连 + 高位端口（如 8080），需要运营商/路由器放行入站。
- Cloudflare 橙云代理对源站端口有限制，手机不能监听 80/443；DDNS 记录建议 DNS-only。
- 若要稳定 HTTPS，Tailscale / Cloudflare Tunnel 比 DDNS 更合适。

## 8. 配置、存储与安全

- 配置：`files/config.json`，原子写入（tmp + rename），带 `version` 字段和迁移。
- 数据：SQLite 内部存储；不写 `/sdcard` 根目录。
- 导出：`Android/data/com.zhoufla.phonebox/files/export/`，方便 `adb pull`。
- 备份：`allowBackup=false`，避免短信库进入云备份；手动导出需提示敏感信息。
- 密钥：面板密码哈希、Cloudflare Token 用 Keystore 加密；日志不记录短信正文和 Token。
- 面板：默认 HTTP，长随机密码 + 限速；公网暴露前必须阅读安全说明。

## 9. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 骨架 | 新仓库、build.sh、Manifest、MainActivity、前台服务、面板 hello | APK 能装到 Mi 9，面板能打开，服务 1 小时不掉 |
| M1 短信主链路 | 收信、去重、入库、规则、Webhook/钉钉/Telegram、发短信 | 息屏下短信 10 秒内转发；发送状态正确；重复短信不重发 |
| M2 面板 | 页面、鉴权、日志、配置编辑、规则测试 | 手机/电脑浏览器可用；未认证 401；改配置立即生效 |
| M3 监控 | 电量、信号、温度、CPU/内存/存储/网络 | 数据与系统设置对得上；面板关闭后不持续采样 |
| M4 DDNS | Cloudflare AAAA/A、多记录、变更检测、失败告警 | IPv6 变化后 2 分钟内更新；地址不变不调 API |
| M5 保活/发布 | 开机、闹钟、Job、看门狗、MIUI 引导、72h 浸泡、CI | 息屏 72h 可用；重启自启；异常恢复；耗电 <3%/天 |

## 10. 主要风险

| 风险 | 应对 |
|---|---|
| MIUI 杀后台 | 自启动 + 无限制 + 锁定后台 + 电池白名单 + 看门狗；只能提高概率 |
| force-stop | 无法自恢复；文档明确，App 内提示不要强制停止/一键清理 |
| Android 12+ 前台服务限制 | target 33；短信 receiver 先落库/直投；Alarm/Job 兜底 |
| CPU 温度读不到 | 热状态 + 电池温度兜底；sysfs 尽力读，不伪造 |
| IPv4 不可达 | 明确提示 NAT/CGNAT；A 记录可选；主推 IPv6 |
| 面板公网暴露 | 长 Token、限速、默认警告；建议 Tailscale/Tunnel |
| READ_SMS 被 ROM 限制 | 补收可选；失败降级，不影响主收信 |
| 耗电 | 事件驱动、无永久 WakeLock、计算类按需采样 |

## 11. 测试计划

### 离线

- 纯 JVM 测试：规则匹配、模板、去重、Cloudflare 请求构造、IPv6 地址筛选、配置迁移。
- `javac -Xlint`、`git diff --check`。

### 真机（Mi 9 / MIUI 12.5）

```sh
adb install -r dist/com.zhoufla.phonebox-<ver>.apk
adb shell dumpsys deviceidle whitelist +com.zhoufla.phonebox
adb shell dumpsys activity services com.zhoufla.phonebox
adb shell dumpsys batterystats --charged com.zhoufla.phonebox
```

- 息屏 8 小时/72 小时，短信是否及时转发。
- 重启手机，服务是否自启。
- WiFi/蜂窝切换、飞行模式恢复。
- Doze：`adb shell dumpsys deviceidle force-idle`。
- 低电量、热状态、断网、Cloudflare 失败。
- 双卡发送、长短信、中文/emoji、多段短信。
- 面板在手机/电脑浏览器打开，未认证 401，Token API 可用。

## 12. 当前状态

- [x] 方案确认
- [x] M0 骨架（前台服务、HTTP 面板、状态页、权限入口）
- [x] M1 短信主链路（接收、去重、入库、规则、Webhook/钉钉/Telegram、发送、退避重试）
- [x] M2 面板（短信、发送、通道、规则、日志、设置；轮询刷新，未做 SSE）
- [x] M3 系统监控（电量、电池/芯片温度、双卡信号、CPU、内存、存储、网络）
- [ ] M4 Cloudflare DDNS
- [ ] M5 保活加固与发布（Alarm/Job 兜底、72h 浸泡）
