# MIUI 12.5 保活与限制

无 root 的 Android 应用无法对抗 MIUI 的强制停止/一键清理，只能把系统允许的机制用到最大，
并让用户知道哪些设置必须手工打开。

## 必做设置

按顺序操作一遍：

1. **通知**
   - 设置 → 应用设置 → 应用管理 → PhoneBox → 通知 → 允许通知。
   - 常驻通知建议设为“重要”或至少“默认”，不要关闭。

2. **自启动**
   - 设置 → 应用设置 → 应用管理 → PhoneBox → 自启动 → 打开。
   - 或在 App 首页点「MIUI 自启动」跳转。

3. **省电策略**
   - 设置 → 应用设置 → 应用管理 → PhoneBox → 省电策略 → **无限制**。
   - 设置 → 省电与电池 → 应用智能省电 → PhoneBox → 无限制。

4. **电池优化白名单**
   - App 首页点「电池优化」→ 允许。
   - 或：设置 → 应用设置 → 特殊应用权限 → 电池优化 → PhoneBox → 不优化。

5. **锁定后台**
   - 打开最近任务 → 长按 PhoneBox 卡片 → 锁定。
   - 一键清理时不要清掉它。

6. **关闭“神隐模式”/后台限制**
   - MIUI 版本不同入口不同：设置 → 省电与电池 → 应用智能省电 → 无限制。
   - 如果系统有“后台弹出界面”“后台运行”开关，也一并允许。

## 不要做的事

- 不要在最近任务里上滑关闭 PhoneBox。
- 不要在应用信息页点「强行停止」。
- 不要用“一键清理/手机加速”清后台。
- 不要用“省电模式/超级省电”长期运行。

**force-stop 后应用进入 stopped 状态，静态广播、闹钟、JobScheduler 都不会再触发，
任何无 root 应用都无法自恢复，只能手动重新打开 App。**

## App 内的兜底

M1 已实现：

- 前台服务 + 常驻通知 + `START_STICKY`
- 开机广播 `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `MY_PACKAGE_REPLACED`
- 60 秒心跳（面板/首页可看）
- 面板 socket 探活，线程死了就地重启
- 短信 `SMS_RECEIVED` 唤醒（先落库再投递，服务起不来也不会丢）
- 投递 worker：每 5–20 秒轮询，失败按 1/2/4/8/16/30 分钟退避

M5 计划加入：

- `AlarmManager` 15 分钟心跳检查
- `JobScheduler` 周期兜底
- 服务异常/面板失败告警（通过转发通道）
- 启动原因记录（开机/手动/看门狗）

## 验证命令

```sh
# 服务是否在跑
adb shell dumpsys activity services com.zhoufla.phonebox

# Doze 白名单
adb shell dumpsys deviceidle whitelist | grep phonebox
adb shell dumpsys deviceidle whitelist +com.zhoufla.phonebox

# 电池统计
adb shell dumpsys batterystats --charged com.zhoufla.phonebox

# 强制进入 Doze（测试短信是否仍能收到）
adb shell dumpsys deviceidle force-idle

# 退出 Doze
adb shell dumpsys deviceidle unforce

# 模拟进程被杀（不是 force-stop）
adb shell am kill com.zhoufla.phonebox
```

## 已知限制

| 限制 | 说明 |
|---|---|
| force-stop 不可恢复 | Android 机制决定，无 root 无解 |
| 内核冻结/系统卡死 | 任何应用层保活都无效 |
| 精确闹钟 | Android 12+ 需用户授权；被拒时退化为非精确闹钟 |
| 后台启动前台服务 | Android 12+ 有限制；短信 receiver 先落库/直投，失败交给 Job |
| 温度 | 公开 API 没有 CPU 温度；sysfs 可能被 SELinux 拒绝 |
| 端口 | 不能监听 80/443；面板使用 8080 等高位端口 |
| 静默安装 | 无 root 做不到；更新只能弹系统安装器 |

## 目标指标

- 息屏 + WiFi 常连，空闲耗电 < 3%/天。
- 短信到达后 10 秒内完成转发（正常网络）。
- 开机后 1 分钟内服务恢复（自启动已开）。
- 非 force-stop 的进程回收后，15 分钟内自动恢复。
