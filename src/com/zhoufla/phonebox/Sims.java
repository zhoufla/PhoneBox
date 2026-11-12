package com.zhoufla.phonebox;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 双卡信息：卡槽、订阅 ID、运营商、本机号码。
 *
 * 国内 SIM 通常不写 EF_MSISDN，系统 API 大概率返回空，所以号码优先级是
 * 「面板手动填写 &gt; 系统自动读取」。自动读取依次尝试
 * SubscriptionManager.getPhoneNumber（API 33+）、SubscriptionInfo.getNumber、
 * TelephonyManager.getLine1Number；每一步单独容错，读不到只影响该字段。
 *
 * 结果缓存 60 秒，保存配置后由 WebPanel 调 clearCache() 立即刷新。
 * 结构与 /api/status 的 sims 一致，也用于模板 {sim}/{slot}。
 */
public final class Sims {

    private static final long TTL_MS = 60000L;
    private static final Object LOCK = new Object();
    private static JSONArray cache;
    private static long cacheAt;

    private Sims() {
    }

    public static void clearCache() {
        synchronized (LOCK) {
            cache = null;
            cacheAt = 0;
        }
    }

    /** 活跃 SIM 卡列表；无订阅信息时也返回手动填写的卡 1 / 卡 2。 */
    public static JSONArray list(Context ctx) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (cache == null || now - cacheAt >= TTL_MS) {
                cache = read(ctx);
                cacheAt = now;
            }
            return cache;
        }
    }

    /** 模板 {sim}：接收短信那张卡的号码，读不到时回退成「卡N」，未知卡返回空。 */
    public static String labelFor(Context ctx, int subId) {
        JSONArray a = list(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null && s.optInt("subId", -1) == subId && subId >= 0) {
                return s.optString("label", "");
            }
        }
        return subId > 0 ? "卡" + subId : "";
    }

    /** 模板 {slot}：接收短信那张卡的卡槽号（1 / 2），未知返回空。 */
    public static String slotFor(Context ctx, int subId) {
        JSONArray a = list(ctx);
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s != null && s.optInt("subId", -1) == subId && subId >= 0) {
                int slot = s.optInt("slot", 0);
                return slot > 0 ? String.valueOf(slot) : "";
            }
        }
        return "";
    }

    private static JSONArray read(Context ctx) {
        JSONArray out = new JSONArray();
        AppConfig cfg = AppConfig.get(ctx);
        SubscriptionManager sm = null;
        TelephonyManager tm = null;
        try {
            sm = (SubscriptionManager)
                    ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Throwable ignored) {
        }

        List<SubscriptionInfo> subs = null;
        try {
            if (sm != null) {
                subs = sm.getActiveSubscriptionInfoList();
            }
        } catch (Throwable ignored) {
            // 无 READ_PHONE_STATE 授权或 ROM 限制
        }

        if (subs == null || subs.isEmpty()) {
            for (int slot = 1; slot <= 2; slot++) {
                String manual = cfg.simNumber(slot);
                if (manual.isEmpty()) {
                    continue;
                }
                JSONObject s = new JSONObject();
                try {
                    s.put("slot", slot);
                    s.put("subId", -1);
                    s.put("carrier", "");
                    s.put("display", "");
                    s.put("number", manual);
                    s.put("source", "manual");
                    s.put("label", manual);
                } catch (Exception ignored) {
                }
                out.put(s);
            }
            return out;
        }

        for (SubscriptionInfo info : subs) {
            if (info == null) {
                continue;
            }
            JSONObject s = new JSONObject();
            try {
                int slot = info.getSimSlotIndex() + 1;
                int subId = info.getSubscriptionId();
                String manual = cfg.simNumber(slot);
                String auto = readNumber(sm, tm, info, subId);
                String number = manual.length() > 0 ? manual : auto;
                s.put("slot", slot);
                s.put("subId", subId);
                s.put("carrier", text(info.getCarrierName()));
                s.put("display", text(info.getDisplayName()));
                s.put("number", number);
                s.put("source", manual.length() > 0 ? "manual"
                        : (auto.length() > 0 ? "sim" : ""));
                s.put("label", number.length() > 0 ? number : "卡" + slot);
            } catch (Exception ignored) {
            }
            out.put(s);
        }
        return out;
    }

    private static String readNumber(SubscriptionManager sm, TelephonyManager tm,
                                     SubscriptionInfo info, int subId) {
        if (Build.VERSION.SDK_INT >= 33 && sm != null) {
            try {
                String n = clean(sm.getPhoneNumber(subId));
                if (n.length() > 0) {
                    return n;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String n = clean(info.getNumber());
            if (n.length() > 0) {
                return n;
            }
        } catch (Throwable ignored) {
        }
        if (tm != null) {
            try {
                String n = clean(tm.createForSubscriptionId(subId).getLine1Number());
                if (n.length() > 0) {
                    return n;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /** 只接受 + 与数字，过滤空格、括号、连字符；明显无效的短串丢弃。 */
    static String clean(String raw) {
        if (raw == null || raw.trim().length() == 0 || raw.length() > 40) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == '+' && sb.length() == 0) {
                sb.append(c);
            } else if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '\t') {
                continue;
            } else {
                return "";
            }
        }
        String v = sb.toString();
        return v.replace("+", "").length() >= 6 ? v : "";
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }
}
