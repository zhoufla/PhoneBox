package com.zhoufla.phonebox;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一条收到的短信。纯数据 + 验证码提取，方便桌面上单独测试。 */
public final class SmsEvent {

    public long id;
    public long ts;
    public String peer = "";
    public String body = "";
    public String code = "";
    public int subId = -1;

    /** 去重键：时间戳 + 号码 + 正文；同一广播重复投递时完全不同判重由 Db 的时间窗口兜底。 */
    public String hash() {
        return md5(ts + "|" + peer + "|" + body + "|" + subId);
    }

    public JSONObject toJson(String device, String tag) {
        JSONObject o = new JSONObject();
        try {
            o.put("device", device == null ? "" : device);
            if (tag != null && tag.length() > 0) {
                o.put("tag", tag);
            }
            o.put("peer", peer == null ? "" : peer);
            o.put("body", body == null ? "" : body);
            o.put("code", code == null ? "" : code);
            o.put("subId", subId);
            o.put("ts", ts);
            o.put("iso", iso(ts));
        } catch (Exception ignored) {
        }
        return o;
    }

    public static String iso(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ts));
    }

    private static final Pattern CODE_CTX = Pattern.compile(
            "(?:验证码|验证密码|动态码|动态密码|校验码|确认码|激活码|提取码|code)[^0-9]{0,10}(\\d{4,8})",
            Pattern.CASE_INSENSITIVE);

    /**
     * 只认上下文明确的验证码，宁可返回空，也不要把订单号、余额当成验证码。
     */
    public static String extractCode(String body) {
        if (body == null || body.length() == 0) {
            return "";
        }
        Matcher m = CODE_CTX.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] raw = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
