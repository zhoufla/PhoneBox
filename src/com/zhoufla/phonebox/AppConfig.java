package com.zhoufla.phonebox;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用内部配置。
 *
 * 路径：/data/data/com.zhoufla.phonebox/files/config.json
 * 不写 /sdcard 根目录，避免 scoped storage 问题。首次启动生成随机面板密码和 Token。
 */
public final class AppConfig {

    private static final String FILE = "config.json";
    private static final int VERSION = 1;
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private static volatile AppConfig instance;

    private final Context ctx;
    private JSONObject data;

    public static AppConfig get(Context context) {
        if (instance == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = new AppConfig(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AppConfig(Context context) {
        ctx = context;
        load();
    }

    public synchronized JSONObject json() {
        return data;
    }

    public synchronized int webPort() {
        JSONObject web = data.optJSONObject("web");
        return web == null ? 8080 : web.optInt("port", 8080);
    }

    public synchronized boolean webEnabled() {
        JSONObject web = data.optJSONObject("web");
        return web == null || web.optBoolean("enabled", true);
    }

    public synchronized String webUser() {
        JSONObject web = data.optJSONObject("web");
        return web == null ? "admin" : web.optString("user", "admin");
    }

    public synchronized String webPass() {
        JSONObject web = data.optJSONObject("web");
        return web == null ? "" : web.optString("pass", "");
    }

    public synchronized String webToken() {
        JSONObject web = data.optJSONObject("web");
        return web == null ? "" : web.optString("token", "");
    }

    public synchronized String deviceName() {
        String name = data.optString("device", "");
        return name.isEmpty() ? Build.MODEL : name;
    }

    // ---------- 短信 ----------

    public synchronized boolean smsEnabled() {
        JSONObject sms = data.optJSONObject("sms");
        return sms == null || sms.optBoolean("enabled", true);
    }

    public synchronized int dedupWindowSec() {
        JSONObject sms = data.optJSONObject("sms");
        int v = sms == null ? 90 : sms.optInt("dedupWindowSec", 90);
        return Math.max(0, Math.min(600, v));
    }

    public synchronized String rulesDefault() {
        String v = data.optString("rulesDefault", "all");
        return "drop".equals(v) ? "drop" : "all";
    }

    public synchronized boolean monitorEnabled() {
        JSONObject monitor = data.optJSONObject("monitor");
        return monitor == null || monitor.optBoolean("enabled", true);
    }

    // ---------- 异常告警 ----------

    /** 旧配置没有 alerts 段时默认开启（只发本地通知，不外推）。 */
    public synchronized boolean alertsEnabled() {
        JSONObject a = data.optJSONObject("alerts");
        return a == null || a.optBoolean("enabled", true);
    }

    public synchronized JSONObject alerts() {
        JSONObject a = data.optJSONObject("alerts");
        return a == null ? new JSONObject() : a;
    }

    public synchronized int alertsIntervalMin() {
        int v = alerts().optInt("intervalMin", 5);
        return Math.max(1, Math.min(60, v));
    }

    public synchronized int alertsCooldownMin() {
        int v = alerts().optInt("cooldownMin", 30);
        return Math.max(1, Math.min(1440, v));
    }

    public synchronized boolean alertsNotify() {
        return alerts().optBoolean("notify", true);
    }

    /** 为空表示不外推；填通道名才把告警发到指定通道。 */
    public synchronized List<String> alertsChannels() {
        List<String> out = new ArrayList<String>();
        JSONArray arr = alerts().optJSONArray("channels");
        if (arr == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            String n = arr.optString(i, "").trim();
            if (!n.isEmpty()) {
                seen.add(n);
            }
        }
        out.addAll(seen);
        return out;
    }

    // ---------- DDNS ----------

    public synchronized boolean ddnsEnabled() {
        JSONObject ddns = data.optJSONObject("ddns");
        return ddns != null && ddns.optBoolean("enabled", false);
    }

    public synchronized String ddnsToken() {
        JSONObject ddns = data.optJSONObject("ddns");
        return ddns == null ? "" : ddns.optString("token", "");
    }

    public synchronized String ddnsZone() {
        JSONObject ddns = data.optJSONObject("ddns");
        return ddns == null ? "" : ddns.optString("zone", "");
    }

    public synchronized int ddnsIntervalMin() {
        JSONObject ddns = data.optJSONObject("ddns");
        int v = ddns == null ? 10 : ddns.optInt("intervalMin", 10);
        return Math.max(1, Math.min(1440, v));
    }

    public synchronized String ddnsFamily() {
        JSONObject ddns = data.optJSONObject("ddns");
        return ddns != null && "v4".equals(ddns.optString("family", "v6")) ? "v4" : "v6";
    }

    public synchronized List<Ddns.Rec> ddnsRecords() {
        List<Ddns.Rec> out = new ArrayList<Ddns.Rec>();
        JSONObject ddns = data.optJSONObject("ddns");
        JSONArray arr = ddns == null ? null : ddns.optJSONArray("records");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length() && out.size() < Ddns.maxRecords(); i++) {
            Object raw = arr.opt(i);
            String name = "";
            boolean proxied = false;
            int ttl = 120;
            if (raw instanceof String) {
                name = ((String) raw).trim();
            } else if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                name = o.optString("name", "").trim();
                proxied = o.optBoolean("proxied", false);
                ttl = o.optInt("ttl", 120);
            }
            if (name.length() == 0) {
                continue;
            }
            Ddns.Rec rec = new Ddns.Rec();
            rec.name = name;
            rec.proxied = proxied;
            rec.ttl = Ddns.clampTtl(ttl);
            out.add(rec);
        }
        return out;
    }

    public synchronized List<Channel> channels() {
        List<Channel> out = new ArrayList<Channel>();
        JSONArray arr = data.optJSONArray("channels");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                Channel c = Channel.fromJson(o);
                if (c.name.length() > 0) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    public synchronized List<Router.Rule> rules() {
        return Router.parse(data.optJSONArray("rules"));
    }

    public synchronized List<String> channelNames() {
        List<String> out = new ArrayList<String>();
        for (Channel c : channels()) {
            out.add(c.name);
        }
        return out;
    }

    public synchronized List<String> activeChannelNames() {
        List<String> out = new ArrayList<String>();
        for (Channel c : channels()) {
            if (c.valid()) {
                out.add(c.name);
            }
        }
        return out;
    }

    public synchronized Channel findChannel(String name) {
        if (name == null) {
            return null;
        }
        for (Channel c : channels()) {
            if (name.equals(c.name)) {
                return c;
            }
        }
        return null;
    }

    /** 面板/API 返回配置时脱敏。 */
    public synchronized JSONObject publicJson() {
        try {
            JSONObject copy = new JSONObject(data.toString());
            JSONObject web = copy.optJSONObject("web");
            if (web != null) {
                if (!web.optString("pass", "").isEmpty()) {
                    web.put("pass", "******");
                }
                if (!web.optString("token", "").isEmpty()) {
                    web.put("token", "******");
                }
            }
            JSONArray chs = copy.optJSONArray("channels");
            if (chs != null) {
                JSONArray masked = new JSONArray();
                for (int i = 0; i < chs.length(); i++) {
                    JSONObject o = chs.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Channel c = Channel.fromJson(o);
                    masked.put(c.maskedJson());
                }
                copy.put("channels", masked);
            }
            JSONObject ddns = copy.optJSONObject("ddns");
            if (ddns != null && !ddns.optString("token", "").isEmpty()) {
                ddns.put("token", "******");
            }
            return copy;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public synchronized void save(JSONObject obj) {
        data = obj;
        writeQuietly(obj);
    }

    /**
     * 面板保存：只覆盖传入的顶层字段，密钥留空或为 ****** 时保留原值。
     * 保存前做结构校验，失败抛 IllegalArgumentException，旧配置不动。
     */
    public synchronized void update(JSONObject incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("空配置");
        }
        try {
            JSONObject next = new JSONObject(data.toString());

            JSONObject web = incoming.optJSONObject("web");
            if (web != null) {
                int port = web.optInt("port", webPort());
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("端口需在 1-65535");
                }
                JSONObject cur = next.optJSONObject("web");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("web", cur);
                }
                cur.put("port", port);
                cur.put("enabled", web.optBoolean("enabled", true));
                String user = web.optString("user", "").trim();
                if (user.length() > 0) {
                    cur.put("user", user);
                }
                keepSecret(cur, web, "pass");
                keepSecret(cur, web, "token");
            }

            JSONObject sms = incoming.optJSONObject("sms");
            if (sms != null) {
                JSONObject cur = next.optJSONObject("sms");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("sms", cur);
                }
                cur.put("enabled", sms.optBoolean("enabled", true));
                cur.put("dedupWindowSec", Math.max(0,
                        Math.min(600, sms.optInt("dedupWindowSec", 90))));
                if (sms.has("defaultSubId")) {
                    cur.put("defaultSubId", sms.optInt("defaultSubId", -1));
                }
            }

            if (incoming.has("device")) {
                String name = incoming.optString("device", "").trim();
                next.put("device", name.length() == 0 ? Build.MODEL : name);
            }
            if (incoming.has("rulesDefault")) {
                next.put("rulesDefault", "drop".equals(incoming.optString("rulesDefault"))
                        ? "drop" : "all");
            }

            JSONArray chs = incoming.optJSONArray("channels");
            JSONArray effectiveChannels = next.optJSONArray("channels");
            if (chs != null) {
                effectiveChannels = sanitizeChannels(chs);
                JSONArray existingRules = next.optJSONArray("rules");
                if (existingRules != null) {
                    Router.validate(existingRules, channelNames(effectiveChannels));
                }
                next.put("channels", effectiveChannels);
            }

            JSONArray rules = incoming.optJSONArray("rules");
            if (rules != null) {
                Router.validate(rules, channelNames(effectiveChannels));
                next.put("rules", rules);
            }

            JSONObject ddns = incoming.optJSONObject("ddns");
            if (ddns != null) {
                JSONObject cur = next.optJSONObject("ddns");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("ddns", cur);
                }
                cur.put("enabled", ddns.optBoolean("enabled", false));
                cur.put("provider", ddns.optString("provider", "cloudflare"));
                cur.put("zone", ddns.optString("zone", ""));
                cur.put("intervalMin", Math.max(1, Math.min(1440,
                        ddns.optInt("intervalMin", 10))));
                cur.put("family", "v4".equals(ddns.optString("family")) ? "v4" : "v6");
                keepSecret(cur, ddns, "token");
                if (ddns.has("records")) {
                    cur.put("records", sanitizeRecords(ddns.optJSONArray("records")));
                }
            }

            JSONObject monitor = incoming.optJSONObject("monitor");
            if (monitor != null) {
                JSONObject cur = next.optJSONObject("monitor");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("monitor", cur);
                }
                cur.put("enabled", monitor.optBoolean("enabled", true));
            }

            JSONObject alerts = incoming.optJSONObject("alerts");
            if (alerts != null) {
                JSONObject cur = next.optJSONObject("alerts");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("alerts", cur);
                }
                cur.put("enabled", alerts.optBoolean("enabled", true));
                cur.put("notify", alerts.optBoolean("notify", true));
                cur.put("intervalMin", Math.max(1, Math.min(60,
                        alerts.optInt("intervalMin", 5))));
                cur.put("cooldownMin", Math.max(1, Math.min(1440,
                        alerts.optInt("cooldownMin", 30))));
                JSONArray names = alerts.optJSONArray("channels");
                if (names != null) {
                    JSONArray cleaned = new JSONArray();
                    Set<String> seen = new LinkedHashSet<String>();
                    for (int i = 0; i < names.length(); i++) {
                        String n = names.optString(i, "").trim();
                        if (!n.isEmpty()) {
                            seen.add(n);
                        }
                    }
                    for (String n : seen) {
                        cleaned.put(n);
                    }
                    cur.put("channels", cleaned);
                }
                JSONObject t = alerts.optJSONObject("thresholds");
                if (t != null) {
                    JSONObject ct = cur.optJSONObject("thresholds");
                    if (ct == null) {
                        ct = new JSONObject();
                        cur.put("thresholds", ct);
                    }
                    ct.put("lowPct", clampInt(t.optInt("lowPct", 20), 5, 80));
                    ct.put("highPct", clampInt(t.optInt("highPct", 80), 50, 100));
                    ct.put("tempC", clampInt(t.optInt("tempC", 42), 30, 70));
                    ct.put("pending", clampInt(t.optInt("pending", 20), 1, 500));
                    ct.put("heartbeatSec", clampInt(t.optInt("heartbeatSec", 180), 60, 3600));
                    ct.put("failStreak", clampInt(t.optInt("failStreak", 3), 1, 50));
                    ct.put("highSustainMin", clampInt(t.optInt("highSustainMin", 30), 1, 240));
                    ct.put("netSustainMin", clampInt(t.optInt("netSustainMin", 5), 1, 120));
                }
            }

            JSONObject retention = incoming.optJSONObject("retention");
            if (retention != null) {
                JSONObject cur = next.optJSONObject("retention");
                if (cur == null) {
                    cur = new JSONObject();
                    next.put("retention", cur);
                }
                cur.put("days", Math.max(0, Math.min(3650,
                        retention.optInt("days", 30))));
            }

            next.put("version", VERSION);
            data = next;
            writeQuietly(next);
        } catch (JSONException e) {
            throw new IllegalArgumentException("配置 JSON 无效：" + e.getMessage());
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 值为空或 ****** 时保留旧值，其余按键写入。 */
    private static void keepSecret(JSONObject cur, JSONObject incoming, String key)
            throws JSONException {
        if (!incoming.has(key)) {
            return;
        }
        String v = incoming.optString(key, "");
        if (v.length() == 0 || "******".equals(v)) {
            return;
        }
        cur.put(key, v);
    }

    /** 通道校验 + 打码回填：同名通道的 secret/token 传空或 ****** 表示沿用旧值。 */
    private JSONArray sanitizeChannels(JSONArray arr) {
        if (arr == null) {
            return new JSONArray();
        }
        if (arr.length() > 40) {
            throw new IllegalArgumentException("通道最多 40 个");
        }
        Set<String> names = new LinkedHashSet<String>();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                throw new IllegalArgumentException("通道必须是对象");
            }
            Channel c = Channel.fromJson(o);
            if (c.name.length() == 0) {
                throw new IllegalArgumentException("通道缺少 name");
            }
            if (!names.add(c.name)) {
                throw new IllegalArgumentException("通道名重复：" + c.name);
            }
            if (!"webhook".equals(c.type) && !"dingtalk".equals(c.type)
                    && !"telegram".equals(c.type)) {
                throw new IllegalArgumentException("通道 " + c.name + " 的 type 无效");
            }
            if (c.enabled && !c.valid()) {
                throw new IllegalArgumentException("通道 " + c.name + " 未填完整的 URL"
                        + (c.isTelegram() ? " / token / chatId" : ""));
            }
            Channel old = findChannel(c.name);
            if (c.secret.length() == 0 || "******".equals(c.secret)) {
                c.secret = old == null ? "" : old.secret;
            }
            if (c.token.length() == 0 || "******".equals(c.token)) {
                c.token = old == null ? "" : old.token;
            }
            out.put(c.toJson());
        }
        return out;
    }

    /** DDNS 记录：字符串或对象都接受，统一存成 {name,ttl,proxied}。 */
    private static JSONArray sanitizeRecords(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) {
            return out;
        }
        if (arr.length() > Ddns.maxRecords()) {
            throw new IllegalArgumentException("DDNS 记录最多 " + Ddns.maxRecords() + " 条");
        }
        Set<String> names = new LinkedHashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            Object raw = arr.opt(i);
            String name = "";
            boolean proxied = false;
            int ttl = 120;
            if (raw instanceof String) {
                name = ((String) raw).trim();
            } else if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                name = o.optString("name", "").trim();
                proxied = o.optBoolean("proxied", false);
                ttl = Ddns.clampTtl(o.optInt("ttl", 120));
            } else {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条 DDNS 记录必须是字符串或对象");
            }
            if (name.length() == 0) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条 DDNS 记录缺少 name");
            }
            if (name.matches(".*\\s+.*")) {
                throw new IllegalArgumentException("DDNS 记录名不能包含空白：" + name);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("DDNS 记录重复：" + name);
            }
            try {
                JSONObject item = new JSONObject();
                item.put("name", name);
                item.put("ttl", ttl);
                item.put("proxied", proxied);
                out.put(item);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    private static List<String> channelNames(JSONArray arr) {
        List<String> out = new ArrayList<String>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                out.add(o.optString("name", ""));
            }
        }
        return out;
    }

    private void load() {
        File file = new File(ctx.getFilesDir(), FILE);
        if (!file.isFile()) {
            data = defaults();
            writeQuietly(data);
            return;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            data = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            int version = data.optInt("version", 0);
            if (version < VERSION) {
                data.put("version", VERSION);
                writeQuietly(data);
            }
        } catch (Exception e) {
            Logs.add("配置文件损坏，使用默认值：" + e);
            File bad = new File(ctx.getFilesDir(), FILE + ".bad." + System.currentTimeMillis());
            file.renameTo(bad);
            data = defaults();
            writeQuietly(data);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private JSONObject defaults() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("device", Build.MODEL == null ? "Android" : Build.MODEL);

            JSONObject web = new JSONObject();
            web.put("enabled", true);
            web.put("port", 8080);
            web.put("user", "admin");
            web.put("pass", random(12));
            web.put("token", random(40));
            root.put("web", web);

            JSONObject sms = new JSONObject();
            sms.put("enabled", true);
            sms.put("dedupWindowSec", 90);
            sms.put("defaultSubId", -1);
            root.put("sms", sms);

            root.put("channels", new JSONArray());
            root.put("rules", new JSONArray());
            root.put("rulesDefault", "all");

            JSONObject ddns = new JSONObject();
            ddns.put("enabled", false);
            ddns.put("provider", "cloudflare");
            ddns.put("token", "");
            ddns.put("zone", "");
            ddns.put("intervalMin", 10);
            ddns.put("family", "v6");
            ddns.put("records", new JSONArray());
            root.put("ddns", ddns);

            JSONObject monitor = new JSONObject();
            monitor.put("enabled", true);
            root.put("monitor", monitor);

            JSONObject alerts = new JSONObject();
            alerts.put("enabled", true);
            alerts.put("notify", true);
            alerts.put("intervalMin", 5);
            alerts.put("cooldownMin", 30);
            alerts.put("channels", new JSONArray());
            JSONObject th = new JSONObject();
            th.put("lowPct", 20);
            th.put("highPct", 80);
            th.put("tempC", 42);
            th.put("pending", 20);
            th.put("heartbeatSec", 180);
            th.put("failStreak", 3);
            th.put("highSustainMin", 30);
            th.put("netSustainMin", 5);
            alerts.put("thresholds", th);
            root.put("alerts", alerts);

            JSONObject retention = new JSONObject();
            retention.put("days", 30);
            root.put("retention", retention);

            JSONObject log = new JSONObject();
            log.put("level", "info");
            log.put("maxLines", 500);
            root.put("log", log);

            return root;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeQuietly(JSONObject obj) {
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;

            File dst = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(dst)) {
                if (dst.exists() && !dst.delete()) {
                    throw new IOException("无法替换 " + dst);
                }
                if (!tmp.renameTo(dst)) {
                    throw new IOException("无法重命名 " + tmp);
                }
            }
        } catch (Exception e) {
            Logs.add("保存配置失败：" + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String random(int len) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
