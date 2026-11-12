package com.zhoufla.phonebox;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * 异常告警：阈值判定 → 持续去抖 → 冷却 → 本地通知 + 可选通道外推。
 *
 * 设计约束：
 * - 只读 Monitor/ServiceState/Db，绝不写 dl 表、不进重试队列，避免告警自激；
 * - 状态存 SharedPreferences，服务重启后不会重复刷屏；
 * - 由 RelayService 的独立线程调用，不能放在主线程（Monitor 首次采样会短睡）。
 */
public final class Alert {

    private static final String PREF = "phonebox_alerts";
    private static final int BASE_NOTIFY_ID = 1003;

    private static final String[] KEYS = {
            "battery_low", "battery_high", "battery_temp",
            "network_down", "service_stale", "delivery_fail", "ddns_failed"
    };

    private static volatile long lastCheckAt = 0;
    private static volatile String lastError = "";
    private static volatile int lastFailedCount = -1;
    private static volatile long lastFailureGrowthAt = 0;

    private Alert() {
    }

    /** 周期检查；由 RelayService.maybeAlerts 在子线程调用。 */
    public static synchronized void check(Context ctx) {
        Context app = ctx.getApplicationContext();
        lastCheckAt = System.currentTimeMillis();
        try {
            AppConfig cfg = AppConfig.get(app);
            if (!cfg.alertsEnabled()) {
                clearAll(app);
                return;
            }

            JSONObject snap = Monitor.snapshot(app);
            JSONObject battery = snap.optJSONObject("battery");
            JSONObject network = snap.optJSONObject("network");
            JSONObject th = cfg.alerts().optJSONObject("thresholds");
            if (th == null) {
                th = new JSONObject();
            }
            long now = System.currentTimeMillis();
            Db db = Db.get(app);

            int level = battery == null ? -1 : battery.optInt("level", -1);
            String status = battery == null ? "" : battery.optString("status", "");
            double temp = battery == null ? Double.NaN : battery.optDouble("tempC", Double.NaN);
            boolean charging = "充电中".equals(status) || "已充满".equals(status);

            evaluate(app, cfg, "battery_low",
                    level >= 0 && !charging && level <= th.optInt("lowPct", 20),
                    "电量偏低", "当前电量 " + level + "%，设备正在放电。", 60000L, now);

            evaluate(app, cfg, "battery_high",
                    level >= th.optInt("highPct", 80) && charging,
                    "长期满充", "电量 " + level + "% 且仍在充电，建议断开电源或定时充电。",
                    th.optInt("highSustainMin", 30) * 60000L, now);

            evaluate(app, cfg, "battery_temp",
                    !Double.isNaN(temp) && temp >= th.optInt("tempC", 42),
                    "电池温度偏高", "电池温度 " + temp + " ℃。", 120000L, now);

            boolean offline = network != null && !network.optBoolean("connected", true);
            evaluate(app, cfg, "network_down", offline, "网络断开",
                    "当前没有可用网络，短信可能无法转发。",
                    th.optInt("netSustainMin", 5) * 60000L, now);

            long hb = ServiceState.heartbeatAgeSec();
            boolean stale = !ServiceState.running || !ServiceState.workerAlive
                    || (hb >= 0 && hb > th.optInt("heartbeatSec", 180))
                    || !WebPanel.isRunning();
            evaluate(app, cfg, "service_stale", stale, "服务状态异常",
                    "心跳 " + hb + " 秒前，worker=" + ServiceState.workerAlive
                            + "，面板=" + WebPanel.isRunning(), 120000L, now);

            int failed = db.failed();
            int pending = db.pending();
            if (lastFailedCount >= 0 && failed - lastFailedCount >= th.optInt("failStreak", 3)) {
                lastFailureGrowthAt = now;
            }
            lastFailedCount = failed;
            boolean failing = false;
            String failMsg = "";
            if (pending >= th.optInt("pending", 20)) {
                failing = true;
                failMsg = "待投递 " + pending + " 条，通道可能不通。";
            } else if (lastFailureGrowthAt > 0 && now - lastFailureGrowthAt < 600000L) {
                failing = true;
                failMsg = "短时间内新增多条投递失败：" + db.lastError();
            }
            evaluate(app, cfg, "delivery_fail", failing, "投递异常", failMsg, 60000L, now);

            // DDNS 失败已有 RelayService 的独立通知，这里只并入状态，不重复弹窗。
            record(app, "ddns_failed", ServiceState.ddnsRan && !ServiceState.ddnsOk,
                    ServiceState.ddnsMessage, now);
            lastError = "";
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Logs.add("告警检查异常：" + t);
        }
    }

    /** 持续满足 sustainMs 才触发；长期异常按冷却周期重复提醒，恢复即取消通知。 */
    private static void evaluate(Context ctx, AppConfig cfg, String key, boolean firing,
                                 String title, String message, long sustainMs, long now) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor ed = p.edit();
        boolean active = p.getBoolean(key + "_active", false);
        long since = p.getLong(key + "_since", 0);

        if (!firing) {
            if (active || since != 0) {
                ed.putBoolean(key + "_active", false).putLong(key + "_since", 0).apply();
                cancelNotice(ctx, key);
                Logs.add("告警恢复：" + title);
            }
            return;
        }
        if (since == 0) {
            ed.putLong(key + "_since", now).putString(key + "_message", message).apply();
            return;
        }
        if (now - since < sustainMs) {
            return;
        }
        long last = p.getLong(key + "_last", 0);
        long cooldown = cfg.alertsCooldownMin() * 60000L;
        if (active && now - last < cooldown) {
            ed.putString(key + "_message", message).apply();
            return;
        }
        ed.putBoolean(key + "_active", true).putLong(key + "_last", now)
                .putString(key + "_message", message).apply();
        boolean notified = false;
        if (cfg.alertsNotify()) {
            notify(ctx, key, "PhoneBox 告警 · " + title, message);
            notified = true;
        }
        int delivered = forward(ctx, cfg, title, message);
        Logs.add("告警触发：" + title + "（通知=" + notified + "，外推="
                + delivered + "）" + message);
    }

    /** 只记录状态、不弹通知，用于已有独立通知的异常（DDNS）。 */
    private static void record(Context ctx, String key, boolean on, String message, long now) {
        SharedPreferences p = prefs(ctx);
        boolean active = p.getBoolean(key + "_active", false);
        String old = p.getString(key + "_message", "");
        if (on == active && (!on || old.equals(message))) {
            return;
        }
        SharedPreferences.Editor ed = p.edit();
        ed.putBoolean(key + "_active", on);
        if (on) {
            ed.putString(key + "_message", message).putLong(key + "_last", now);
        } else {
            ed.putLong(key + "_since", 0);
        }
        ed.apply();
        if (!on) {
            cancelNotice(ctx, key);
        }
    }

    private static void clearAll(Context ctx) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        for (String key : KEYS) {
            ed.putBoolean(key + "_active", false).putLong(key + "_since", 0);
            cancelNotice(ctx, key);
        }
        ed.apply();
    }

    /** 面板「测试告警」：绕过阈值与冷却，直接验证通知与通道链路。 */
    public static JSONObject fireTest(Context ctx, String message) {
        Context app = ctx.getApplicationContext();
        AppConfig cfg = AppConfig.get(app);
        String text = message == null || message.trim().length() == 0
                ? "这是一条测试告警。" : message.trim();
        JSONObject o = new JSONObject();
        try {
            boolean notified = false;
            if (cfg.alertsNotify()) {
                notify(app, "test", "PhoneBox 告警 · 测试", text);
                notified = true;
            }
            int delivered = forward(app, cfg, "测试", text);
            Logs.add("面板触发测试告警（通知=" + notified + "，外推=" + delivered + "）");
            o.put("ok", true);
            o.put("notified", notified);
            o.put("delivered", delivered);
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(e));
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    /** 告警外推：构造伪 Deliver 复用现有 Sender，不写数据库、不进重试。 */
    private static int forward(Context ctx, AppConfig cfg, String title, String message) {
        List<String> names = cfg.alertsChannels();
        if (names.isEmpty()) {
            return 0;
        }
        Deliver d = new Deliver();
        d.peer = "PhoneBox-ALERT";
        d.body = "【告警】" + title + "\n" + message;
        d.ts = System.currentTimeMillis();
        d.subId = -1;
        d.template = "{body}";
        int ok = 0;
        for (Channel ch : cfg.channels()) {
            if (!names.contains(ch.name) || !ch.valid()) {
                continue;
            }
            String err = Sender.send(ch, d, cfg.deviceName());
            if (err == null) {
                ok++;
            } else {
                Logs.add("告警外推失败 " + ch.name + "：" + err);
            }
        }
        return ok;
    }

    // ---------- 通知 ----------

    private static void notify(Context ctx, String key, String title, String text) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifyId(key) + 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(ctx, RelayService.CHANNEL_ALERT);
        } else {
            builder = new Notification.Builder(ctx);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            nm.notify(notifyId(key), builder.build());
        } catch (Exception ignored) {
        }
    }

    private static void cancelNotice(Context ctx, String key) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(notifyId(key));
            }
        } catch (Exception ignored) {
        }
    }

    private static int notifyId(String key) {
        int i = Arrays.asList(KEYS).indexOf(key);
        return BASE_NOTIFY_ID + (i < 0 ? KEYS.length : i);
    }

    // ---------- 状态 ----------

    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AppConfig cfg = AppConfig.get(ctx.getApplicationContext());
            o.put("enabled", cfg.alertsEnabled());
            o.put("notify", cfg.alertsNotify());
            o.put("intervalMin", cfg.alertsIntervalMin());
            o.put("cooldownMin", cfg.alertsCooldownMin());
            JSONArray channels = new JSONArray();
            for (String name : cfg.alertsChannels()) {
                channels.put(name);
            }
            o.put("channels", channels);

            SharedPreferences p = prefs(ctx);
            JSONArray active = new JSONArray();
            for (String key : KEYS) {
                if (!p.getBoolean(key + "_active", false)) {
                    continue;
                }
                JSONObject a = new JSONObject();
                a.put("key", key);
                a.put("title", titleOf(key));
                a.put("message", p.getString(key + "_message", ""));
                a.put("at", p.getLong(key + "_last", 0));
                active.put(a);
            }
            o.put("active", active);
            o.put("lastCheckAt", lastCheckAt);
            o.put("lastError", lastError == null ? "" : lastError);
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e));
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    private static String titleOf(String key) {
        if ("battery_low".equals(key)) return "电量偏低";
        if ("battery_high".equals(key)) return "长期满充";
        if ("battery_temp".equals(key)) return "电池温度偏高";
        if ("network_down".equals(key)) return "网络断开";
        if ("service_stale".equals(key)) return "服务状态异常";
        if ("delivery_fail".equals(key)) return "投递异常";
        if ("ddns_failed".equals(key)) return "DDNS 更新失败";
        return key;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
