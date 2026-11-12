package com.zhoufla.phonebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** 手动上传安装包的 PackageInstaller 回调；与在线更新使用独立的 action。 */
public final class LocalInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    LocalUpdater.onInstallResult(status, "");
                } catch (Exception e) {
                    Logs.add("打开系统安装确认页失败：" + e);
                    LocalUpdater.onInstallResult(PackageInstaller.STATUS_FAILURE,
                            "打开系统安装确认页失败，请打开 PhoneBox App 首页重试");
                    Toast.makeText(context, "请返回 PhoneBox 再试一次安装", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Logs.add("PhoneBox 手动安装成功");
            LocalUpdater.onInstallResult(status, "");
            Toast.makeText(context, "PhoneBox 安装完成", Toast.LENGTH_LONG).show();
            return;
        }

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String error = "安装失败" + (message == null || message.length() == 0
                ? "" : "：" + message);
        Logs.add(error);
        LocalUpdater.onInstallResult(status, error);
        Toast.makeText(context, error, Toast.LENGTH_LONG).show();
    }
}
