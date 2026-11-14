package com.zhoufla.phonebox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页面板的表单登录会话。
 *
 * - 登录成功后下发 HttpOnly + SameSite=Lax 的 Cookie，会话只存在进程内存，App 重启即失效；
 * - 面板走明文 HTTP，因此不加 Secure（加了浏览器不会保存 Cookie），只应监听可信网络；
 * - 用户名或密码变更后，旧会话立即失效；
 * - 同一来源连续失败会短暂锁定，抵御对随机密码的在线爆破。
 */
public final class WebSession {

    public static final String COOKIE = "phonebox_sid";

    private static final long IDLE_MS = 24L * 3600000L;
    private static final long MAX_MS = 7L * 24L * 3600000L;
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60000L;
    private static final int SID_BYTES = 32;

    private static final ConcurrentHashMap<String, Entry> SESSIONS =
            new ConcurrentHashMap<String, Entry>();
    private static final ConcurrentHashMap<String, Fail> FAILS =
            new ConcurrentHashMap<String, Fail>();
    private static final SecureRandom RND = new SecureRandom();

    private static final class Entry {
        final long createdAt;
        final String fingerprint;
        volatile long lastSeen;

        Entry(String fingerprint, long now) {
            this.createdAt = now;
            this.fingerprint = fingerprint;
            this.lastSeen = now;
        }
    }

    private static final class Fail {
        int count;
        long lastFail;
        long lockUntil;
    }

    private WebSession() {
    }

    /** 校验账号密码并签发会话；凭据错误或处于锁定期返回 null。 */
    public static String login(String user, String pass, String source, AppConfig cfg) {
        String key = source == null || source.length() == 0 ? "unknown" : source;
        long now = System.currentTimeMillis();
        Fail fail = FAILS.get(key);
        if (fail != null && fail.lockUntil > now) {
            return null;
        }
        if (!Auth.credentials(user, pass, cfg)) {
            if (fail == null) {
                fail = new Fail();
                FAILS.put(key, fail);
            }
            synchronized (fail) {
                fail.lastFail = now;
                fail.count++;
                if (fail.count >= MAX_FAILS) {
                    fail.lockUntil = now + LOCK_MS;
                    fail.count = 0;
                }
            }
            return null;
        }
        FAILS.remove(key);
        String sid = randomHex(SID_BYTES);
        SESSIONS.put(sid, new Entry(credentialFingerprint(cfg), now));
        prune(now);
        return sid;
    }

    /** 剩余锁定秒数；0 表示未锁定。 */
    public static long lockRemaining(String source) {
        String key = source == null || source.length() == 0 ? "unknown" : source;
        Fail fail = FAILS.get(key);
        if (fail == null) {
            return 0;
        }
        long left = fail.lockUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : Math.max(1, (left + 999) / 1000);
    }

    /** Cookie 是否有效；顺带做空闲/绝对过期与改密失效检查。 */
    public static boolean valid(Http.Req req, AppConfig cfg) {
        String sid = cookie(req, COOKIE);
        if (sid == null || sid.length() != SID_BYTES * 2) {
            return false;
        }
        Entry entry = SESSIONS.get(sid);
        if (entry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - entry.createdAt > MAX_MS || now - entry.lastSeen > IDLE_MS) {
            SESSIONS.remove(sid);
            return false;
        }
        if (!Auth.constantTimeEquals(entry.fingerprint, credentialFingerprint(cfg))) {
            SESSIONS.remove(sid);
            return false;
        }
        entry.lastSeen = now;
        return true;
    }

    public static void logout(Http.Req req) {
        String sid = cookie(req, COOKIE);
        if (sid != null) {
            SESSIONS.remove(sid);
        }
    }

    public static String setCookie(String sid) {
        return COOKIE + "=" + sid + "; Path=/; Max-Age=" + (MAX_MS / 1000)
                + "; HttpOnly; SameSite=Lax";
    }

    public static String clearCookie() {
        return COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
    }

    /** 从 Cookie 头里取指定键；没有返回 null。 */
    public static String cookie(Http.Req req, String name) {
        String raw = req.header("cookie");
        if (raw == null || raw.length() == 0) {
            return null;
        }
        String[] parts = raw.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq > 0 && name.equals(part.substring(0, eq).trim())) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    // ---------- 内部 ----------

    /** 凭据指纹：只存哈希；改用户名或密码都会让旧会话失效。 */
    private static String credentialFingerprint(AppConfig cfg) {
        return sha256(cfg.webUser() + "\n" + cfg.webPass());
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在 Android 上必然存在；真缺失时所有会话都不可用（安全侧失败）。
            return "";
        }
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        RND.nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** 清理过期会话与失败记录，并在会话数超上限时淘汰最久未用的。 */
    private static synchronized void prune(long now) {
        Iterator<Map.Entry<String, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            Entry v = e.getValue();
            if (now - v.createdAt > MAX_MS || now - v.lastSeen > IDLE_MS) {
                it.remove();
            }
        }
        if (SESSIONS.size() > MAX_SESSIONS) {
            List<Map.Entry<String, Entry>> all =
                    new ArrayList<Map.Entry<String, Entry>>(SESSIONS.entrySet());
            Collections.sort(all, new Comparator<Map.Entry<String, Entry>>() {
                @Override
                public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                    return Long.compare(a.getValue().lastSeen, b.getValue().lastSeen);
                }
            });
            for (int i = 0; i < all.size() - MAX_SESSIONS; i++) {
                SESSIONS.remove(all.get(i).getKey());
            }
        }
        Iterator<Map.Entry<String, Fail>> fit = FAILS.entrySet().iterator();
        while (fit.hasNext()) {
            Map.Entry<String, Fail> e = fit.next();
            if (e.getValue().lastFail < now - 2 * LOCK_MS) {
                fit.remove();
            }
        }
    }
}
