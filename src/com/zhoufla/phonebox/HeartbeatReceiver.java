package com.zhoufla.phonebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 15 分钟心跳闹钟。
 *
 * 只负责重新布防并把服务叫起来；进程被系统回收后，这个广播会在新进程里执行。
 * Android 12+ 后台启动前台服务可能被拒，失败时写入日志，等下一次 Job/闹钟再试。
 */
public final class HeartbeatReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Watchdog.noteAlarm(context);
        Watchdog.scheduleAlarm(context);
        Watchdog.scheduleJob(context);
        if (!ServiceState.running) {
            Logs.add("心跳闹钟拉起服务");
            RelayService.startWake(context);
        }
    }
}
