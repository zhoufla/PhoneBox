# PhoneBox

面向普通 Android 手机（主目标：小米 9 / MIUI 12.5 / Android 11）的**无 root 短信网关 + 设备面板**。

- 短信接收、规则匹配、多通道转发
- 网页发短信、双卡选择、发送结果回写
- 电量、温度、信号、CPU/内存/存储/网络状态
- Cloudflare DDNS（WiFi 下 IPv6 直连优先）
- App 只做启动器和状态页，配置全部在网页完成

项目参考 [msm8916-box](https://github.com/zhoufla/msm8916-box) 的数据层、规则引擎和内嵌 Web 面板，
但 Android 生命周期、权限、保活和系统信息采集全部按 Android 11+ 重写。没有 root，不使用
`su`、不写 `/system`、不做静默安装。

## 当前进度

**M1 短信主链路**：

- 监听 `SMS_RECEIVED`，多段短信合并，90 秒窗口去重，SQLite 入库
- 规则引擎：号码/正文正则、时段、优先级、`drop`、`stop`、模板覆盖
- 转发通道：Webhook、钉钉（加签 + text/markdown）、Telegram
- 退避重试 1/2/4/8/16/30 分钟，每个通道独立状态
- 网页面板：短信列表、重发、发短信、发件记录、通道编辑、规则编辑/测试、日志、设置
- 前台服务投递 worker，短信到达立即唤醒

**M3 系统监控**：

- 电量、电池温度/电压、充电状态与健康度
- `/sys/class/thermal` 芯片温度（取最高值，兼容毫摄氏度）
- 双卡信号：运营商、网络制式、等级、dBm
- CPU 核数、占用率、负载、当前频率；内存、数据分区、网络类型与流量

**M4 Cloudflare DDNS**：

- WiFi 公网 IPv6 优先，自动枚举网卡，跳过链路本地和 ULA
- 定时同步 AAAA（或 A）记录，地址未变化不调用写接口
- 失败发系统通知并写入面板；支持手动“立即更新”
- Token 只保存在应用私有配置，面板接口永远返回 `******`

**M5 保活加固**：

- 15 分钟心跳闹钟，优先精确闹钟，被拒时退化为允许空闲唤醒
- 15 分钟 `JobScheduler` 周期任务（persisted）在被系统回收后拉起服务
- 面板与 App 均显示闹钟/任务状态，可一键“重新布防”
- 投递线程和面板线程死亡后自动重启；主动停止服务会撤销兜底
- 提供 `tools/soak-test.sh` 做 72 小时浸泡采样

**面板细节（0.6.0）**：

- 标签页写入 URL hash，刷新/收藏后停留在原页面
- 点击访问地址直接复制，短信支持按号码/正文/验证码过滤
- 短信正文一键复制；日志支持自动刷新（5 秒）与下载 `phonebox-logs.txt`
- 面板仍是零外链单文件，所有复制都带 HTTP 环境降级

**首页滚动修复（0.6.1）**：定时刷新仅在内容变化时更新 TextView，避免重复设置可选中文本导致滚动位置跳动。

**在线升级（0.7.0）**：App 首页可检查 GitHub 最新正式 Release，下载后校验 APK 文件大小、SHA-256、包名和版本码，再交由 Android 系统安装器；安装前仍需用户确认。首次安装更新时需在系统设置允许 PhoneBox 安装未知来源应用。GitHub 必须已有正式 Release，且发布 APK 使用同一签名密钥；没有 Release 时 App 会提示检查失败，不会自动下载未校验文件。

## 目标平台

| 项 | 值 |
|---|---|
| 主设备 | 小米 9（cepheus），MIUI 12.5 / Android 11 |
| minSdk | 26（Android 8） |
| targetSdk | 33 |
| compileSdk | 34 |
| 语言 | Java 8（无 Gradle、无 AndroidX、无第三方依赖） |
| 面板 | 内嵌 `ServerSocket` HTTP/1.1，单页 HTML，无外链 CDN |

选 `targetSdk 33` 是为了避开 Android 14 对前台服务类型、精确闹钟的硬性要求。本应用是侧载自用，
不依赖 Google Play。以后要上架再升 target 并补前台服务类型。

## 构建

依赖：

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`
- `aapt`、`d8`、`zipalign`、`apksigner`

```sh
export ANDROID_SDK=$HOME/android-sdk
export JAVA_HOME=$HOME/android-tools/jdk-17.0.13+11
sh build.sh
```

产物：

```text
dist/com.zhoufla.phonebox-<version>.apk
dist/latest.json
```

首次构建会在 `keystore/phonebox.jks` 生成自用签名（密码 `phonebox`）。**请备份这个文件**，
后续覆盖安装必须使用同一签名。也可以显式指定：

```sh
KEYSTORE=/path/to/phonebox.jks KEYSTORE_PASS=... KEY_ALIAS=... sh build.sh
```

## GitHub Actions 云编译与发布

工作流：[`.github/workflows/build.yml`](.github/workflows/build.yml)

- 推 `main` / PR / 手动触发：编译 APK，上传 Actions artifact，不创建 Release
- 推 `v*` 标签：校验标签与 `AndroidManifest.xml` 的 `versionName` 一致，编译并创建 GitHub Release
- 未配置签名密钥时仍会构建，但使用临时签名，只上传 artifact，不发布 Release

在仓库 `Settings → Secrets and variables → Actions` 添加：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/phonebox.jks` 的输出 |
| `KEYSTORE_PASS` | keystore 密码（本地默认 `phonebox`） |
| `KEY_PASS` | key 密码，通常同上 |
| `KEY_ALIAS` | key 别名（本地默认 `phonebox`） |

生成并推送发布标签：

```sh
# 先把 AndroidManifest.xml 的 versionCode / versionName 改好并提交
git tag v0.7.0
git push origin v0.7.0
```

Release 附件包含 APK、`SHA256SUMS` 和 `latest.json`。重新推同一标签前先删除旧标签和 Release：

```sh
gh release delete v0.7.0 --yes
git push origin :refs/tags/v0.7.0
git tag -f v0.7.0 && git push origin v0.7.0
```

> Release 用的签名必须和手机已安装版本一致，否则只能卸载重装。CI 只从 Secrets 读取密钥，
> 不要提交 `keystore/` 或把 base64 写进仓库。

## 安装

```sh
adb install -r dist/com.zhoufla.phonebox-0.7.0.apk
adb shell am start -n com.zhoufla.phonebox/.MainActivity
```

首次打开后：

1. 点击「申请权限」，允许短信、电话状态、通知。
2. 点击「电池优化」，选择「不限制」。
3. 点击「MIUI 自启动」，在系统页面打开自启动；再到「省电策略」选择「无限制」。
4. 在最近任务里把 PhoneBox **锁定**，避免一键清理。
5. App 首页会显示面板地址和随机密码，用同一 WiFi 下的电脑浏览器打开。

## 面板地址

App 会列出当前所有可用的局域网地址：

```text
http://192.168.x.x:8080
http://[240e:...]:8080
```

手机本机打开用 `http://127.0.0.1:8080`。默认账号 `admin`，密码在首次启动时随机生成，
保存在应用内部 `files/config.json`，App 首页可查看。也可以通过 Bearer Token 调 API：

```sh
curl -H "Authorization: Bearer <token>" http://192.168.x.x:8080/api/status
```

> 面板默认是 HTTP，且会监听局域网/公网 IPv6。不要把密码或 Token 截图发出去；需要公网访问时
> 先阅读 [`docs/MIUI-KEEPALIVE.md`](docs/MIUI-KEEPALIVE.md) 和 DDNS 的安全说明。

## 转发通道

在面板「通道」里新增，保存即生效：

| 类型 | 必填 | 说明 |
|---|---|---|
| Webhook | URL | POST 事件 JSON，可选 `X-Token` |
| 钉钉 | URL、Secret | 自定义机器人，支持加签和 text/markdown |
| Telegram | Bot Token、chatId | 直接调用 `sendMessage` |

模板占位符：`{peer}` `{body}` `{code}` `{device}` `{tag}` `{time}` `{sub}`。留空使用默认模板，
有验证码时自动把验证码放到第一行。

规则在面板「规则」里用 JSON 编辑，保存前会校验正则和通道名。详见 [`docs/API.md`](docs/API.md)。

## 目录

```text
phone-box/
├── .github/workflows/build.yml
├── AndroidManifest.xml
├── build.sh
├── assets/web/index.html
├── config/config.example.json
├── res/drawable/ic_launcher.xml
├── src/com/zhoufla/phonebox/
│   ├── MainActivity.java
│   ├── RelayService.java
│   ├── BootReceiver.java
│   ├── HeartbeatReceiver.java
│   ├── WatchdogJob.java
│   ├── SmsReceiver.java
│   ├── SmsInbox.java
│   ├── Monitor.java
│   ├── Ddns.java
│   ├── Watchdog.java
│   ├── GithubUpdater.java
│   ├── UpdateInstallReceiver.java
│   ├── SmsSender.java
│   ├── SendReceiver.java
│   ├── Db.java
│   ├── SmsEvent.java
│   ├── Deliver.java
│   ├── Router.java
│   ├── Tpl.java
│   ├── Retry.java
│   ├── Channel.java
│   ├── Sender.java
│   ├── AppConfig.java
│   ├── Auth.java
│   ├── Http.java
│   ├── WebPanel.java
│   ├── NetInfo.java
│   ├── ServiceState.java
│   └── Logs.java
├── docs/
│   ├── PLAN.md
│   ├── MIUI-KEEPALIVE.md
│   └── API.md
└── tools/
    ├── smoke-test.sh
    └── soak-test.sh
```

## 文档

- [开发方案](docs/PLAN.md)
- [MIUI 保活与限制](docs/MIUI-KEEPALIVE.md)
- [HTTP API](docs/API.md)

## 边界

- 不做默认短信 App，只监听 `SMS_RECEIVED` 并用 `READ_SMS` 做可选补收。
- 不承诺“杀不死”。MIUI 强制停止、用户 force-stop 后无法自恢复。
- 不保证能读到 CPU/SoC 温度；读不到时显示热状态/电池温度，不伪造数据。
- IPv4 DDNS 在 NAT/CGNAT 下通常不可达；DDNS 主推 WiFi 下 IPv6 直连。
