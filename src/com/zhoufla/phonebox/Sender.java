package com.zhoufla.phonebox;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 通道投递：只做 HTTP，不碰数据库，方便离线测试 payload 构造。 */
public final class Sender {

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 25000;

    private Sender() {
    }

    /** 兼容调用（告警外推）：不附加接收号码。 */
    public static String send(Channel ch, Deliver d, String device) {
        return send(ch, d, device, "", "", false);
    }

    /**
     * 投递一条消息。成功返回 null，失败返回可读错误（会写进 dl.err）。
     * sim/slot 是接收短信那张卡的信息：文本模板用 {sim}/{slot}，Webhook JSON 也会带 sim/slot。
     */
    public static String send(Channel ch, Deliver d, String device,
                              String sim, String slot, boolean autoSimLine) {
        try {
            SmsEvent e = d.toEvent();
            e.sim = sim == null ? "" : sim;
            e.slot = slot == null ? "" : slot;
            String key = d.template != null && d.template.length() > 0 ? d.template : ch.template;
            String text = Tpl.render(key, e, ch, device, SmsEvent.iso(e.ts),
                    e.sim, e.slot, autoSimLine);

            if (ch.isTelegram()) {
                return sendTelegram(ch, text);
            }
            if (ch.isDingTalk()) {
                return sendDingTalk(ch, text);
            }
            return sendWebhook(ch, e, device);
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private static String sendWebhook(Channel ch, SmsEvent e, String device) throws Exception {
        String body = e.toJson(device, ch.nzTag()).toString();
        HttpURLConnection c = open(ch.url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "PhoneBox/1");
        if (ch.token.length() > 0) {
            c.setRequestProperty("X-Token", ch.token);
        }
        Resp r = post(c, body);
        return r.code >= 200 && r.code < 300 ? null : "HTTP " + r.code + " " + r.body;
    }

    private static String sendDingTalk(Channel ch, String text) throws Exception {
        String url = ch.url;
        if (ch.secret.length() > 0) {
            long ts = System.currentTimeMillis();
            String sign = URLEncoder.encode(hmacBase64(ts + "\n" + ch.secret, ch.secret), "UTF-8");
            url += (url.indexOf('?') >= 0 ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign;
        }

        JSONObject payload = new JSONObject();
        if (ch.isMarkdown()) {
            JSONObject md = new JSONObject();
            md.put("title", ch.title.length() > 0 ? ch.title : "PhoneBox 短信");
            md.put("text", text);
            payload.put("msgtype", "markdown");
            payload.put("markdown", md);
        } else {
            JSONObject tx = new JSONObject();
            tx.put("content", text);
            payload.put("msgtype", "text");
            payload.put("text", tx);
        }

        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "PhoneBox/1");
        Resp r = post(c, payload.toString());
        if (r.code < 200 || r.code >= 300) {
            return "HTTP " + r.code + " " + r.body;
        }
        JSONObject o = new JSONObject(r.body);
        int errcode = o.optInt("errcode", 0);
        return errcode == 0 ? null : "钉钉错误 " + errcode + "：" + o.optString("errmsg", "");
    }

    private static String sendTelegram(Channel ch, String text) throws Exception {
        String base = ch.url.length() > 0 ? ch.url : "https://api.telegram.org";
        String url = base.replaceAll("/+$", "") + "/bot" + ch.token + "/sendMessage";

        JSONObject payload = new JSONObject();
        payload.put("chat_id", ch.chatId);
        payload.put("text", text);
        payload.put("disable_web_page_preview", true);

        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "PhoneBox/1");
        Resp r = post(c, payload.toString());
        if (r.code < 200 || r.code >= 300) {
            return "HTTP " + r.code + " " + r.body;
        }
        JSONObject o = new JSONObject(r.body);
        if (!o.optBoolean("ok", false)) {
            return "Telegram 错误：" + o.optString("description", r.body);
        }
        return null;
    }

    // ---------- HTTP ----------

    private static final class Resp {
        int code;
        String body = "";
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static Resp post(HttpURLConnection c, String body) throws Exception {
        try {
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(raw.length);
            OutputStream out = c.getOutputStream();
            try {
                out.write(raw);
            } finally {
                out.close();
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = read(in, 8192);
            if (r.body.length() > 500) {
                r.body = r.body.substring(0, 500);
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in, int max) throws Exception {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
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

    static String hmacBase64(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return android.util.Base64.encodeToString(
                mac.doFinal(data.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
    }
}
