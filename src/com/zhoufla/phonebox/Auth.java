package com.zhoufla.phonebox;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/** 面板认证：HTTP Basic 或 Bearer Token。恒时比较，避免明显的时序侧信道。 */
public final class Auth {

    private Auth() {
    }

    public static boolean ok(Http.Req req, AppConfig cfg) {
        String header = req.header("authorization");
        if (header == null) {
            return false;
        }
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String expected = cfg.webToken();
            if (expected.isEmpty()) {
                return false;
            }
            return constantTimeEquals(header.substring(7).trim(), expected);
        }
        if (header.regionMatches(true, 0, "Basic ", 0, 6)) {
            String expectedPass = cfg.webPass();
            if (expectedPass.isEmpty()) {
                return false;
            }
            try {
                byte[] raw = Base64.decode(header.substring(6).trim(), Base64.DEFAULT);
                String decoded = new String(raw, StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 0) {
                    return false;
                }
                String user = decoded.substring(0, colon);
                String pass = decoded.substring(colon + 1);
                return constantTimeEquals(user, cfg.webUser())
                        && constantTimeEquals(pass, expectedPass);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    public static Http.Resp unauthorized() {
        Http.Resp resp = Http.Resp.json(401, "{\"error\":\"unauthorized\"}");
        resp.headers.put("WWW-Authenticate", "Basic realm=\"PhoneBox\", charset=\"UTF-8\"");
        return resp;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xb = i < x.length ? x[i] : 0;
            int yb = i < y.length ? y[i] : 0;
            diff |= xb ^ yb;
        }
        return diff == 0;
    }
}
