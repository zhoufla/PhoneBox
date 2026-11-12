package com.zhoufla.phonebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

        ingest(ctx, e, false);
    }

    /**
     * 真短信与面板测试事件共用：检查开关 → 入库去重 → 规则路由 → 建投递目标 → 唤醒服务。
     *
     * 测试事件不消耗 SIM 短信，但走完全相同的规则、模板与重试链路；
     * 它传 0 秒去重窗口，由调用方在正文中附加短标记保证 hash 唯一。
     */
    static JSONObject ingest(Context ctx, SmsEvent e, boolean test) {
        JSONObject out = new JSONObject();
        try {
            AppConfig cfg = AppConfig.get(ctx);
            List<String> active = cfg.activeChannelNames();
            out.put("test", test);
            out.put("channels", active.size());

            if (!cfg.smsEnabled()) {
                out.put("ok", false);
                out.put("warning", "短信转发已关闭，事件未入库");
                Logs.add((test ? "测试事件" : "短信转发已关闭，丢弃 " + e.peer));
                return out;
            }

            Db db = Db.get(ctx);
            long id = db.insertEvent(e, test ? 0 : cfg.dedupWindowSec());
            if (id <= 0) {
                out.put("ok", false);
                out.put("error", "事件入库失败");
                Logs.add("事件入库失败：" + e.peer);
                return out;
            }

            out.put("ok", true);
            out.put("id", id);
            Router.Result r = Router.route(e, cfg.rules(), active, cfg.rulesDefault());
            out.put("drop", r.drop);
            out.put("rule", r.rule == null ? (r.drop ? "drop" : "") : r.rule);
            JSONArray targets = new JSONArray();
            for (Router.Target t : r.targets) {
                targets.put(t.ch);
            }
            out.put("targets", targets);

            if (r.drop) {
                db.setEventRule(id, r.rule == null ? "drop" : r.rule);
            } else {
                db.ensureTargets(id, r.targets);
            }
            if (active.isEmpty()) {
                out.put("warning", "没有启用的转发通道，事件已入库但不会外发");
            }
            Logs.add((test ? "测试事件 " : "收到 ") + e.peer + "，" + e.body.length()
                    + " 字" + (r.drop ? "，规则丢弃" : "，转发 " + r.targets.size() + " 个通道")
                    + (r.rule == null ? "" : "（规则 " + r.rule + "）"));
            RelayService.start(ctx);
            return out;
        } catch (JSONException ex) {
            Logs.add("事件处理失败：" + ex);
            return out;
        }
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
