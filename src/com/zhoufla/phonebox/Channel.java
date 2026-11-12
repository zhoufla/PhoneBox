package com.zhoufla.phonebox;

import org.json.JSONObject;

/**
 * 一条转发通道。多个通道并行投递，各自独立重试。
 *
 * type:
 *   webhook  —— POST 事件 JSON，可选 X-Token
 *   dingtalk —— 钉钉自定义机器人，支持加签和 markdown
 *   telegram —— Telegram Bot，token + chatId
 */
public final class Channel {

    public String name = "";
    public String type = "webhook";
    public String url = "";
    public String secret = "";      // 钉钉加签密钥 SEC…
    public String token = "";       // webhook 的 X-Token / Telegram bot token
    public String chatId = "";      // Telegram chat id
    public String template = "";
    public String format = "text";  // 钉钉：text | markdown
    public String title = "";       // 钉钉 markdown 标题
    public String tag = "";         // 多设备区分标签
    public int maxTries = 10;
    public boolean enabled = true;

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.name = o.optString("name", "").trim();
        c.type = o.optString("type", "webhook").trim().toLowerCase();
        c.url = o.optString("url", "").trim();
        c.secret = o.optString("secret", "");
        c.token = o.optString("token", "");
        c.chatId = o.optString("chatId", "").trim();
        c.template = o.optString("template", "");
        c.format = o.optString("format", "text").trim();
        c.title = o.optString("title", "");
        c.tag = o.optString("tag", "").trim();
        c.maxTries = Retry.clampTries(o.optInt("maxTries", 10));
        c.enabled = o.optBoolean("enabled", true);
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("type", type);
            if (url.length() > 0) {
                o.put("url", url);
            }
            if (secret.length() > 0) {
                o.put("secret", secret);
            }
            if (token.length() > 0) {
                o.put("token", token);
            }
            if (chatId.length() > 0) {
                o.put("chatId", chatId);
            }
            if (template.length() > 0) {
                o.put("template", template);
            }
            if ("dingtalk".equals(type)) {
                o.put("format", format);
                if (title.length() > 0) {
                    o.put("title", title);
                }
            }
            if (tag.length() > 0) {
                o.put("tag", tag);
            }
            if (maxTries != 10) {
                o.put("maxTries", maxTries);
            }
            o.put("enabled", enabled);
        } catch (Exception ignored) {
        }
        return o;
    }

    public boolean isDingTalk() {
        return "dingtalk".equals(type);
    }

    public boolean isTelegram() {
        return "telegram".equals(type);
    }

    public boolean isMarkdown() {
        return "markdown".equals(format) || "card".equals(format);
    }

    public boolean valid() {
        if (!enabled) {
            return false;
        }
        if (isTelegram()) {
            return token.length() > 0 && chatId.length() > 0;
        }
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /** 面板展示用：配置中的密钥一律打码。 */
    public JSONObject maskedJson() {
        JSONObject o = toJson();
        try {
            if (o.has("secret")) {
                o.put("secret", "******");
            }
            if (o.has("token")) {
                o.put("token", "******");
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public String nzTag() {
        return tag == null ? "" : tag.trim();
    }
}
