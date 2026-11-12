package com.zhoufla.phonebox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * Cloudflare DDNS。
 *
 * 手机没有公网 IPv4 的常见场景下，DDNS 主要维护 WiFi 拿到的公网 IPv6（AAAA）。
 * 每次检查先读 Cloudflare 现有记录，地址一致就跳过写接口，避免无意义的 API 调用。
 *
 * 只用 Cloudflare API v4：
 *   GET   /zones?name=            Zone 名称 → ID
 *   GET   /zones/{id}/dns_records 查现有记录
 *   POST  /zones/{id}/dns_records 新建
 *   PATCH /zones/{id}/dns_records/{recordId} 更新
 *
 * Token 只需要 Zone:DNS:Edit（限定单个 Zone）。所有网络操作都会被面板/服务捕获，
 * 失败不会影响短信主链路。
 */
public final class Ddns {

    static final String DEFAULT_BASE = "https://api.cloudflare.com/client/v4";

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 25000;
    private static final int DEFAULT_TTL = 120;
    private static final int MAX_RECORDS = 20;

    private Ddns() {
    }

    /** 一条要维护的记录。配置里也可以直接写字符串。 */
    public static final class Rec {
        public String name = "";
        public boolean proxied;
        public int ttl = DEFAULT_TTL;
    }

    /** 一次同步的结果，直接序列化给面板。 */
    public static final class Result {
        public boolean ok;
        public boolean skipped;
        public String ip = "";
        public String family = "v6";
        public String message = "";
        public String warning = "";
        public long at;
        public int updated;
        public int unchanged;
        public final List<String> details = new ArrayList<String>();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("skipped", skipped);
                o.put("ip", ip == null ? "" : ip);
                o.put("family", family);
                o.put("message", message == null ? "" : message);
                if (warning != null && warning.length() > 0) {
                    o.put("warning", warning);
                }
                o.put("at", at);
                o.put("updated", updated);
                o.put("unchanged", unchanged);
                JSONArray arr = new JSONArray();
                for (String s : details) {
                    arr.put(s);
                }
                o.put("details", arr);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    // ---------- 入口 ----------

    /**
     * 执行一次同步。manual=true 时即使配置里关闭了 DDNS 也会跑（用于面板测试）。
     * 串行执行，避免自动检查与手动点击同时写 Cloudflare。
     */
    public static synchronized Result run(Context ctx, boolean manual) {
        Result r = new Result();
        r.at = System.currentTimeMillis();

        AppConfig cfg = AppConfig.get(ctx);
        r.family = cfg.ddnsFamily();
        if (!manual && !cfg.ddnsEnabled()) {
            r.skipped = true;
            r.message = "DDNS 未启用";
            return r;
        }

        String token = cfg.ddnsToken().trim();
        String zone = cfg.ddnsZone().trim();
        List<Rec> records = cfg.ddnsRecords();
        if (token.length() == 0) {
            return fail(r, "未配置 Cloudflare API Token");
        }
        if (zone.length() == 0) {
            return fail(r, "未配置 Zone 名称或 ID");
        }
        if (records.isEmpty()) {
            return fail(r, "未配置任何 DNS 记录");
        }

        String ip = pickAddress(r.family);
        if (ip.length() == 0) {
            return fail(r, "v4".equals(r.family)
                    ? "未获取到可用 IPv4 地址"
                    : "未获取到公网 IPv6 地址，请检查 WiFi");
        }
        r.ip = ip;
        if ("v4".equals(r.family) && isPrivateV4(ip)) {
            r.warning = "本机 IPv4 属于内网或 CGNAT，公网访问需要公网 IP 或端口映射";
        }

        Cf cf = new Cf(token);
        String zoneId;
        try {
            zoneId = cf.zoneId(zone);
        } catch (Exception e) {
            return fail(r, "解析 Zone 失败：" + message(e));
        }

        String type = "v4".equals(r.family) ? "A" : "AAAA";
        int failed = 0;
        for (Rec rec : records) {
            try {
                Cf.Record existing = cf.find(zoneId, type, rec.name);
                if (existing != null && ip.equals(existing.content)) {
                    r.unchanged++;
                    r.details.add(rec.name + " 未变化");
                    continue;
                }
                if (existing == null) {
                    cf.create(zoneId, type, rec.name, ip, rec.ttl, rec.proxied);
                    r.updated++;
                    r.details.add(rec.name + " 已创建 " + ip);
                } else {
                    cf.update(zoneId, existing.id, type, rec.name, ip, rec.ttl, rec.proxied);
                    r.updated++;
                    r.details.add(rec.name + " 已更新 " + nz(existing.content) + " → " + ip);
                }
            } catch (Exception e) {
                failed++;
                r.details.add(rec.name + " 失败：" + message(e));
            }
        }

        if (failed > 0) {
            r.ok = false;
            r.message = failed + " 条记录更新失败（成功 " + r.updated + " 条）";
            for (String d : r.details) {
                if (d.contains("失败：")) {
                    r.message = d;
                    break;
                }
            }
            return r;
        }
        r.ok = true;
        r.message = r.updated > 0 ? "已更新 " + r.updated + " 条记录" : "地址未变化";
        return r;
    }

    private static Result fail(Result r, String msg) {
        r.ok = false;
        r.message = msg;
        return r;
    }

    private static String message(Exception e) {
        String m = e == null ? "" : e.getMessage();
        return m == null || m.length() == 0 ? String.valueOf(e) : m;
    }

    // ---------- 地址选择 ----------

    /** v6 优先 WiFi/以太网的公网 IPv6；v4 只取普通地址，内网地址由调用方加提示。 */
    static String pickAddress(String family) {
        List<String> list = candidates(family);
        return list.isEmpty() ? "" : list.get(0);
    }

    static List<String> candidates(String family) {
        final boolean v6 = !"v4".equals(family);
        List<String[]> found = new ArrayList<String[]>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return new ArrayList<String>();
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr == null || addr.isAnyLocalAddress()
                            || addr.isLoopbackAddress() || addr.isMulticastAddress()
                            || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    if (v6) {
                        if (!(addr instanceof Inet6Address)) {
                            continue;
                        }
                        String ip = bare(addr.getHostAddress());
                        if (ip.isEmpty() || isUniqueLocalV6(ip)) {
                            continue;
                        }
                        found.add(new String[]{ni.getName(), ip});
                    } else {
                        if (!(addr instanceof Inet4Address)) {
                            continue;
                        }
                        String ip = bare(addr.getHostAddress());
                        if (!ip.isEmpty()) {
                            found.add(new String[]{ni.getName(), ip});
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logs.add("枚举 DDNS 地址失败：" + e);
        }

        Collections.sort(found, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                int sa = ifaceScore(a[0]);
                int sb = ifaceScore(b[0]);
                if (sa != sb) {
                    return sa - sb;
                }
                return a[0].compareTo(b[0]);
            }
        });

        List<String> out = new ArrayList<String>();
        for (String[] item : found) {
            if (!out.contains(item[1])) {
                out.add(item[1]);
            }
        }
        return out;
    }

    /** wlan 最优先，其次有线，蜂窝最后。 */
    private static int ifaceScore(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("ap")) {
            return 0;
        }
        if (n.startsWith("eth")) {
            return 1;
        }
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("wwan")) {
            return 3;
        }
        return 2;
    }

    private static String bare(String ip) {
        String s = ip == null ? "" : ip.trim();
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** fc00::/7，局域网地址，不能写到公网 AAAA。 */
    static boolean isUniqueLocalV6(String ip) {
        String s = ip == null ? "" : ip.toLowerCase();
        return s.startsWith("fc") || s.startsWith("fd");
    }

    static boolean isPrivateV4(String ip) {
        if (ip == null) {
            return false;
        }
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return false;
        }
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (a == 10 || a == 127 || a == 0) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        // 100.64.0.0/10 运营商级 NAT
        return a == 100 && b >= 64 && b <= 127;
    }

    // ---------- Cloudflare API ----------

    /** 极简 Cloudflare 客户端；base 可注入，便于离线测试。 */
    static final class Cf {

        static final class Record {
            String id = "";
            String content = "";
        }

        private final String base;
        private final String token;

        Cf(String token) {
            this(DEFAULT_BASE, token);
        }

        Cf(String base, String token) {
            this.base = base == null ? DEFAULT_BASE : base.replaceAll("/+$", "");
            this.token = token == null ? "" : token;
        }

        String zoneId(String zone) throws Exception {
            String z = zone == null ? "" : zone.trim();
            if (z.matches("[0-9a-fA-F]{32}")) {
                return z;
            }
            JSONObject o = request("GET", "/zones?name=" + enc(z) + "&per_page=1", null);
            JSONArray arr = o.optJSONArray("result");
            if (arr == null || arr.length() == 0) {
                throw new IllegalArgumentException("Zone 不存在或 Token 无权限：" + z);
            }
            String id = arr.getJSONObject(0).optString("id", "");
            if (id.length() == 0) {
                throw new IllegalArgumentException("Zone 未返回 ID：" + z);
            }
            return id;
        }

        Record find(String zoneId, String type, String name) throws Exception {
            JSONObject o = request("GET", "/zones/" + zoneId + "/dns_records?type="
                    + enc(type) + "&name=" + enc(name) + "&per_page=1", null);
            JSONArray arr = o.optJSONArray("result");
            if (arr == null || arr.length() == 0) {
                return null;
            }
            JSONObject item = arr.getJSONObject(0);
            Record rec = new Record();
            rec.id = item.optString("id", "");
            rec.content = item.optString("content", "");
            return rec.id.length() == 0 ? null : rec;
        }

        void create(String zoneId, String type, String name, String content,
                    int ttl, boolean proxied) throws Exception {
            request("POST", "/zones/" + zoneId + "/dns_records",
                    body(type, name, content, ttl, proxied).toString());
        }

        void update(String zoneId, String recordId, String type, String name,
                    String content, int ttl, boolean proxied) throws Exception {
            // HttpURLConnection 在普通 JDK 上不支持 PATCH，PUT 同为全量更新，Android/桌面端都可用
            request("PUT", "/zones/" + zoneId + "/dns_records/" + recordId,
                    body(type, name, content, ttl, proxied).toString());
        }

        private JSONObject body(String type, String name, String content,
                                int ttl, boolean proxied) {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type);
                o.put("name", name);
                o.put("content", content);
                o.put("ttl", ttl <= 0 ? DEFAULT_TTL : ttl);
                o.put("proxied", proxied);
            } catch (Exception ignored) {
            }
            return o;
        }

        private JSONObject request(String method, String path, String body) throws Exception {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "PhoneBox/1");
                if (body != null) {
                    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setFixedLengthStreamingMode(raw.length);
                    OutputStream out = c.getOutputStream();
                    try {
                        out.write(raw);
                    } finally {
                        out.close();
                    }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = read(in, 65536);
                JSONObject o;
                try {
                    o = new JSONObject(text);
                } catch (Exception e) {
                    throw new IOException("Cloudflare 返回非 JSON（HTTP " + code + "）：" + clip(text));
                }
                if (!o.optBoolean("success", false)) {
                    throw new IOException("Cloudflare 错误：" + errors(o) + "（HTTP " + code + "）");
                }
                return o;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }

        private static String errors(JSONObject o) {
            JSONArray arr = o.optJSONArray("errors");
            if (arr == null || arr.length() == 0) {
                return "未知错误";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length() && i < 4; i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(e.optInt("code", 0)).append(' ').append(e.optString("message", ""));
            }
            return sb.length() == 0 ? "未知错误" : sb.toString();
        }

        private static String read(InputStream in, int max) throws Exception {
            if (in == null) {
                return "";
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) {
                        break;
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        }

        private static String clip(String s) {
            String v = s == null ? "" : s.trim();
            return v.length() > 200 ? v.substring(0, 200) : v;
        }

        private static String enc(String s) {
            try {
                return URLEncoder.encode(s == null ? "" : s, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    static int clampTtl(int ttl) {
        if (ttl == 1) {
            return 1; // Cloudflare 的 automatic
        }
        return Math.max(60, Math.min(86400, ttl <= 0 ? DEFAULT_TTL : ttl));
    }

    static int maxRecords() {
        return MAX_RECORDS;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
