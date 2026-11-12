package com.zhoufla.phonebox;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;

/** 面板发短信：分卡、分片，逐片回写 out 表。 */
public final class SmsSender {

    public static final String EXTRA_ID = "out_id";
    public static final String ACTION_SENT = "com.zhoufla.phonebox.SENT";
    private static final int MAX_LEN = 1000;

    private SmsSender() {
    }

    public static long send(Context ctx, String to, String body, int subId) throws Exception {
        String num = to == null ? "" : to.trim();
        String text = body == null ? "" : body;
        if (num.length() == 0) {
            throw new IllegalArgumentException("号码不能为空");
        }
        if (text.length() == 0) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (text.length() > MAX_LEN) {
            throw new IllegalArgumentException("内容过长（上限 " + MAX_LEN + " 字）");
        }

        SmsManager sm = manager(ctx, subId);
        if (sm == null) {
            throw new IllegalStateException("SmsManager 不可用");
        }

        ArrayList<String> parts = sm.divideMessage(text);
        if (parts == null || parts.isEmpty()) {
            parts = new ArrayList<String>();
            parts.add(text);
        }

        Db db = Db.get(ctx);
        long id = db.insertOut(num, text, parts.size(), subId);
        if (id <= 0) {
            throw new IllegalStateException("写入发件记录失败");
        }

        SendReceiver.expect(id, parts.size());
        try {
            if (parts.size() == 1) {
                sm.sendTextMessage(num, null, parts.get(0), sentIntent(ctx, id, 0), null);
            } else {
                ArrayList<PendingIntent> sent = new ArrayList<PendingIntent>();
                for (int i = 0; i < parts.size(); i++) {
                    sent.add(sentIntent(ctx, id, i));
                }
                sm.sendMultipartTextMessage(num, null, parts, sent, null);
            }
        } catch (Exception e) {
            SendReceiver.forget(id);
            db.markOut(id, 2, "发送失败：" + e.getMessage());
            throw e;
        }

        Logs.add("发送短信 → " + num + "（" + parts.size() + " 片，SIM "
                + (subId <= 0 ? "默认" : String.valueOf(subId)) + "）id=" + id);
        return id;
    }

    /**
     * subId < 0 或无效时用默认 SmsManager。
     * Android 12+ 用 createForSubscriptionId，旧系统走兼容 API。
     */
    private static SmsManager manager(Context ctx, int subId) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                SmsManager base = ctx.getSystemService(SmsManager.class);
                if (base != null) {
                    if (subId > 0) {
                        return base.createForSubscriptionId(subId);
                    }
                    return base;
                }
            }
            if (subId > 0 && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager sm = SmsManager.getSmsManagerForSubscriptionId(subId);
                if (sm != null) {
                    return sm;
                }
            }
        } catch (Exception ignored) {
        }
        return SmsManager.getDefault();
    }

    private static PendingIntent sentIntent(Context ctx, long id, int part) {
        Intent it = new Intent(ctx, SendReceiver.class);
        it.setAction(ACTION_SENT);
        it.putExtra(EXTRA_ID, id);
        it.putExtra("part", part);
        int code = (int) ((id * 31 + part) & 0x7fffffff);
        return PendingIntent.getBroadcast(ctx, code, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
