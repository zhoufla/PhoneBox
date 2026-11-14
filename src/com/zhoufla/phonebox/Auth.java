package com.zhoufla.phonebox;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * 面板认证：网页表单会话 Cookie、HTTP Basic 或 Bearer Token。恒时比较，避免明显的时序侧信道。
 *
 * 这里不再下发 WWW-Authenticate：浏览器不会弹原生 Basic 对话框，未登录的页面请求由
 * WebPanel 302 到独立登录页 /login。脚本主动发送 Basic / Bearer 头仍然照常可用。
 */
public final class Auth {

    private Auth() {
    }

    public static boolean ok(Http.Req req, AppConfig cfg) {
        String header = req.header("authorization");
        if (header != null) {
            if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String expected = cfg.webToken();
                if (!expected.isEmpty()
                        && constantTimeEquals(header.substring(7).trim(), expected)) {
                    return true;
                }
            } else if (header.regionMatches(true, 0, "Basic ", 0, 6)) {
                String expectedPass = cfg.webPass();
                if (!expectedPass.isEmpty()) {
                    try {
                        byte[] raw = Base64.decode(header.substring(6).trim(), Base64.DEFAULT);
                        String decoded = new String(raw, StandardCharsets.UTF_8);
                        int colon = decoded.indexOf(':');
                        if (colon >= 0
                                && constantTimeEquals(decoded.substring(0, colon), cfg.webUser())
                                && constantTimeEquals(decoded.substring(colon + 1), expectedPass)) {
                            return true;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        // 头缺失或凭据不对（例如改过密码后浏览器还缓存着旧 Basic）时，仍允许有效会话登录。
        return WebSession.valid(req, cfg);
    }

    /** 表单登录用的账号密码校验。 */
    public static boolean credentials(String user, String pass, AppConfig cfg) {
        String expected = cfg.webPass();
        return user != null && pass != null && !expected.isEmpty()
                && constantTimeEquals(user, cfg.webUser())
                && constantTimeEquals(pass, expected);
    }

    /** 纯 401，不带 WWW-Authenticate，避免浏览器弹出原生认证框。 */
    public static Http.Resp unauthorized() {
        return Http.Resp.json(401, "{\"error\":\"unauthorized\"}");
    }

    static boolean constantTimeEquals(String a, String b) {
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
