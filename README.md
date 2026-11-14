# PhoneBox

面向普通 Android 手机（主目标：小米 9 / MIUI 12.5 / Android 11）的**无 root 短信网关 + 设备面板**。

- 短信接收、规则匹配、多通道转发
- 网页发短信、双卡选择、发送结果回写
- 电量、温度、信号、CPU/内存/存储/网络状态
- Cloudflare DDNS（WiFi 下 IPv6 直连优先）
- 测试事件、异常告警（本地通知，可选外推到指定通道）
- 面板支持「添加到主屏幕」（PWA-lite：图标、主题色、独立窗口）
- 面板独立登录页：表单会话 Cookie，取代浏览器原生认证弹窗；Basic / Bearer 仍可用于脚本
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
- 双卡本机号码：系统读取或面板手动填写，概览与转发模板共用（0.8.1）
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

**在线升级（0.7.0 起）**：App 首页可检查 GitHub 最新正式 Release，下载后校验 APK 文件大小、SHA-256、包名和版本码，再交由 Android 系统安装器；安装前仍需用户确认。首次安装更新时需在系统设置允许 PhoneBox 安装未知来源应用。GitHub 必须已有正式 Release，且发布 APK 使用同一签名密钥；没有 Release 时 App 会提示检查失败，不会自动下载未校验文件。

**0.8.0 增量**：

- **发送测试事件**：概览页填入号码/正文/SIM，构造一条假短信走完整的「入库 → 去重 → 规则 → 通道」链路，不消耗真短信。正文自动附 `[测试 #HHmmss]`，避免被 90 秒去重窗口合并；接口 `POST /api/test/event`。
- **异常告警**：按配置间隔在独立线程检查低电量、长期满充、高温、断网、服务/心跳异常、投递积压与连续失败；持续判定 + 冷却去抖，复用 `phonebox_alert` 通知渠道。默认**只发本地通知**，在设置页填写通道名才会外推；告警不写短信库、不进重试队列。接口 `POST /api/alerts/test` 可手动触发一条测试告警。
- **PWA-lite**：`manifest.webmanifest` + 192/512 PNG 图标 + 窄屏与安全区适配，手机浏览器「添加到主屏幕」后可用独立图标与独立窗口打开。Service Worker 只在 HTTPS/localhost 可用，面板走明文 HTTP，因此**不做离线缓存，也不安装成 WebAPK**；需要完整 PWA 请自行加 HTTPS 反代。

**0.8.1 增量**：

- **概览显示双卡号码**：新增 `sims` 信息模块，逐卡展示卡槽、运营商、本机号码与来源（系统读取 / 手动填写）。国内 SIM 常不向系统提供号码，可在「设置 → 本机号码」按卡填写；`/api/status` 顶层 `sims` 不受「系统监控」开关影响。
- **转发模板默认带接收号码**：新增 `{sim}`（接收短信那张卡的号码，读不到回退「卡N」）与 `{slot}`（卡槽号）。默认模板已包含，自定义模板没写 `{sim}` 时自动追加一行「接收号码」（设置页可关闭）；Webhook 事件 JSON 同步带 `sim` / `slot`，告警外推不受影响。
- **面板在线更新**：设置页新增「在线更新」卡片，可直接在网页面板检查 GitHub 正式 Release、下载校验并交给系统安装器确认，带进度与状态轮询；与 App 首页共用同一套校验规则。接口 `GET /api/update/status`、`POST /api/update/check`、`POST /api/update/install`（均需鉴权，POST 需 `X-Requested-With: phonebox`）。安装确认后服务会短暂重启。

**0.8.2 增量**：

- **手动上传安装包**：GitHub 打不开或 API 限流（403/429）时，在「设置 → 在线更新」底部选择 APK 上传即可。上传后先校验包名与签名证书 SHA-256（必须与当前应用一致），允许同版本重装；校验通过才可点安装，仍由系统弹窗确认。接口 `POST /api/files/upload?kind=apk`、`GET /api/local-update/status`、`POST /api/local-update/install`。
- **文件收件箱**：新增「传输」标签页，可把任意文件传到手机，直接落在公共「下载/PhoneBox」目录，文件管理器可见；支持列表、鉴权下载回电脑、删除。Android 10+ 走 MediaStore，不需要存储权限；单文件 30 MB、总量 100 MB、保留 30 天。
- **便签**：同一标签页下方，电脑贴一段文本、手机打开面板即可一键复制；两端都能写，5 秒轮询（仅该标签页可见时），复制按钮沿用 HTTP 环境的 `execCommand` 降级。
- 上传是流式落盘，不把整个文件读进内存；HTTP 层对上传单独放宽到 60 秒读超时，其余接口仍保持 1 MiB 文本上限。

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

- 推 `main` / PR：编译 APK 并上传 Actions artifact，不创建 Release
- 推 `v*` 标签：校验标签与 `AndroidManifest.xml` 的 `versionName` 一致后自动创建 Release
- Actions → `build` → `Run workflow`：可选择 `build` 或 `release`；发布时填写标签，如 `v0.8.1`
- `release` 模式和 `v*` 标签都要求签名 Secrets；缺少密钥或版本不匹配会明确失败，不会发布临时签名 APK
- 每次成功的非 PR 构建后自动清理，只保留最近 3 次 Actions 运行（连同其 artifact）和最近 3 个 Release；旧 Release 对应的 Git 标签会保留

在仓库 `Settings → Secrets and variables → Actions` 添加：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/phonebox.jks` 的输出 |
| `KEYSTORE_PASS` | keystore 密码（本地默认 `phonebox`） |
| `KEY_PASS` | key 密码，通常同上 |
| `KEY_ALIAS` | key 别名（本地默认 `phonebox`） |

**推荐通过 Actions 页面发布**：打开 `Actions → build → Run workflow`，选择 `main` 分支，`action` 选 `release`。发布标签可留空，工作流会自动使用 Manifest 的 `versionName` 加 `v` 前缀（当前为 `v0.8.1`）；也可手动填写匹配的标签。运行后工作流会编译并创建 Release/tag。也可以先本地打 tag 再推送，触发自动 Release：

```sh
# 先把 AndroidManifest.xml 的 versionCode / versionName 改好并提交
git tag v0.8.1
git push origin v0.8.1
```

Release 附件包含 APK、`SHA256SUMS` 和 `latest.json`。重新推同一标签前先删除旧标签和 Release：

```sh
gh release delete v0.8.1 --yes
git push origin :refs/tags/v0.8.1
git tag -f v0.8.1 && git push origin v0.8.1
```

> Release 用的签名必须和手机已安装版本一致，否则只能卸载重装。CI 只从 Secrets 读取密钥，
> 不要提交 `keystore/` 或把 base64 写进仓库。

## 安装

```sh
adb install -r dist/com.zhoufla.phonebox-0.8.1.apk
adb shell am start -n com.zhoufla.phonebox/.MainActivity
```

首次打开后：

1. 点击「申请权限」，允许短信、电话状态、通知。
2. 点击「电池优化」，选择「不限制」。
3. 点击「MIUI 自启动」，在系统页面打开自启动；再到「省电策略」选择「无限制」。
4. 在最近任务里把 PhoneBox **锁定**，避免一键清理。
5. App 首页会显示面板地址和随机密码，用同一 WiFi 下的电脑浏览器打开，在登录页输入账号 `admin` 和该密码。

## 面板地址

App 会列出当前所有可用的局域网地址：

```text
http://192.168.x.x:8080
http://[240e:...]:8080
```

手机本机打开用 `http://127.0.0.1:8080`。浏览器打开面板地址会先进入独立登录页 `/login`，默认账号 `admin`，密码在首次启动时随机生成，保存在应用内部 `files/config.json`，App 首页可查看。

登录成功后服务端下发 `phonebox_sid` 会话 Cookie（`HttpOnly`、`SameSite=Lax`），会话只存在进程内存：7 天绝对过期、24 小时无操作过期，App 重启、改账号/密码或点右上角「退出」都会让它失效。连续 5 次密码错误会锁定该来源 60 秒。面板与登录页都是单文件、零外链。

脚本仍可用 Basic 或 Bearer Token 调 API，行为不变（页面本身只认会话 Cookie，避免旧浏览器缓存的 Basic 凭据绕过登录页或让「退出」失效）：

```sh
curl -u admin:<密码> http://192.168.x.x:8080/api/status
curl -H "Authorization: Bearer <token>" http://192.168.x.x:8080/api/status
# 也可以走表单登录拿 Cookie，见 docs/API.md
curl -c /tmp/pb.cookie -H 'X-Requested-With: phonebox' \
     -H 'Content-Type: application/json' \
     -d '{"user":"admin","pass":"<密码>"}' http://192.168.x.x:8080/api/login
```

> 面板默认是 HTTP，且会监听局域网/公网 IPv6，会话 Cookie 因此不带 `Secure`（否则浏览器不会保存）。不要把密码或 Token 截图发出去；需要公网访问时先加 HTTPS 反代，并阅读 [`docs/MIUI-KEEPALIVE.md`](docs/MIUI-KEEPALIVE.md) 和 DDNS 的安全说明。

## 转发通道

在面板「通道」里新增，保存即生效：

| 类型 | 必填 | 说明 |
|---|---|---|
| Webhook | URL | POST 事件 JSON，可选 `X-Token` |
| 钉钉 | URL、Secret | 自定义机器人，支持加签和 text/markdown |
| Telegram | Bot Token、chatId | 直接调用 `sendMessage` |

模板占位符：`{peer}` `{body}` `{code}` `{device}` `{tag}` `{time}` `{sub}` `{sim}` `{slot}`。
`{sim}` 是接收短信那张卡的号码、`{slot}` 是卡槽号。留空使用默认模板（已含接收号码）；
自定义模板没写 `{sim}` 时会自动追加一行「接收号码」，可在「设置 → 本机号码」关闭。
有验证码时自动把验证码放到第一行。

规则在面板「规则」里用 JSON 编辑，保存前会校验正则和通道名。详见 [`docs/API.md`](docs/API.md)。

## 目录

```text
phone-box/
├── .github/workflows/build.yml
├── AndroidManifest.xml
├── build.sh
├── assets/web/index.html
├── assets/web/login.html
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
│   ├── WebSession.java
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
