package com.zhoufla.phonebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.List;

/**
 * 收短信：SMS_RECEIVED → 去重入库 → 规则路由 → 建投递记录 → 唤醒服务。
 *
 * 不申请默认短信 App，只监听 framework 的 legacy 广播；MIUI 上如果收不到，
 * 再考虑兼容模式（docs/PLAN.md）。
 */
public final class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            handle(context, intent);
        } catch (Throwable t) {
            Logs.add("短信接收失败：" + t);
        }
    }

    private static void handle(Context ctx, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) {
            return;
        }

        StringBuilder body = new StringBuilder();
        String peer = "";
        long ts = 0;
        for (SmsMessage m : parts) {
            if (m == null) {
                continue;
            }
            String b = m.getMessageBody();
            if (b != null) {
                body.append(b);
            }
            if (peer.length() == 0) {
                String a = m.getOriginatingAddress();
                if (a != null) {
                    peer = a;
                }
            }
            if (ts == 0) {
                ts = m.getTimestampMillis();
            }
        }

        SmsEvent e = new SmsEvent();
        e.peer = peer;
        e.body = body.toString();
        e.ts = ts > 0 ? ts : System.currentTimeMillis();
        e.code = SmsEvent.extractCode(e.body);
        e.subId = subscriptionId(intent);

        AppConfig cfg = AppConfig.get(ctx);
        if (!cfg.smsEnabled()) {
            Logs.add("短信转发已关闭，丢弃 " + e.peer);
            return;
        }

        Db db = Db.get(ctx);
        long id = db.insertEvent(e, cfg.dedupWindowSec());
        if (id <= 0) {
            Logs.add("短信入库失败：" + e.peer);
            return;
        }

        List<Router.Rule> rules = cfg.rules();
        Router.Result r = Router.route(e, rules, cfg.activeChannelNames(), cfg.rulesDefault());
        if (r.drop) {
            db.setEventRule(id, r.rule == null ? "drop" : r.rule);
            Logs.add("收到 " + e.peer + "，" + e.body.length() + " 字，规则丢弃"
                    + (r.rule == null ? "" : "：" + r.rule));
        } else {
            db.ensureTargets(id, r.targets);
            Logs.add("收到 " + e.peer + "，" + e.body.length() + " 字，转发 "
                    + r.targets.size() + " 个通道"
                    + (r.rule == null ? "" : "（规则 " + r.rule + "）"));
        }

        RelayService.start(ctx);
    }

    /** framework 把订阅 ID 放在 "subscription" extra，不同 ROM 可能是 int / long / String。 */
    private static int subscriptionId(Intent intent) {
        try {
            Object v = intent.getExtras() == null ? null : intent.getExtras().get("subscription");
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
            if (v != null) {
                return Integer.parseInt(String.valueOf(v).trim());
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
