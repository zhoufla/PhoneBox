package com.zhoufla.phonebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/覆盖安装后拉起常驻服务。 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Logs.add("收到 " + action + "，启动服务");
            Watchdog.scheduleAll(context);
            RelayService.start(context);
        }
    }
}
