package com.zhoufla.phonebox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 短信规则引擎。纯 JDK + org.json，可离线测试。
 *
 * 规则按数组顺序求值；一条规则内字段 AND，同字段列表内 OR：
 *   peer    发件人正则列表（空 = 不限）
 *   body    正文正则列表（空 = 不限）
 *   from/to 生效时段 "HH:mm"，支持跨零点
 *
 * action:
 *   relay（默认）把 channels 并入投递目标（同通道取最高 priority）
 *   drop        整条消息丢弃：仍入库，面板可人工重发
 * stop=true 命中后不再看后续规则。
 *
 * 所有规则都没攒下任何目标时按 unmatched：
 *   all（默认）投递到全部通道 / drop 丢弃。
 */
public final class Router {

    public static final int MAX_RULES = 50;
    public static final int MAX_PRIORITY = 1000;

    public static final class Target {
        public final String ch;
        public final int prio;
        public final String template;

        public Target(String ch, int prio, String template) {
            this.ch = ch;
            this.prio = prio;
            this.template = template;
        }
    }

    public static final class Result {
        public boolean drop;
        public String rule;
        public final List<String> matched = new ArrayList<String>();
        public final List<Target> targets = new ArrayList<Target>();

        Result(boolean drop, String rule) {
            this.drop = drop;
            this.rule = rule;
        }
    }

    public static final class Rule {
        public String name = "";
        public boolean enabled = true;
        public List<String> peerRaw = new ArrayList<String>();
        public List<String> bodyRaw = new ArrayList<String>();
        public List<Pattern> peer = new ArrayList<Pattern>();
        public List<Pattern> body = new ArrayList<Pattern>();
        public List<String> channels = new ArrayList<String>();
        public int priority = 0;
        public boolean stop = false;
        public boolean dropAction = false;
        public String template = "";
        public int fromMin = -1;
        public int toMin = -1;
        public String fromRaw = "";
        public String toRaw = "";
    }

    private Router() {
    }

    // ---------- 解析 ----------

    public static List<Rule> parse(JSONArray arr) {
        List<Rule> out = new ArrayList<Rule>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length() && out.size() < MAX_RULES; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Rule r = new Rule();
            r.name = o.optString("name", "").trim();
            if (r.name.length() == 0) {
                r.name = "rule" + (i + 1);
            }
            r.enabled = o.optBoolean("enabled", true);
            r.dropAction = "drop".equalsIgnoreCase(o.optString("action", "relay"));
            r.priority = clampPriority(o.optInt("priority", 0));
            r.stop = o.optBoolean("stop", false);
            r.template = o.optString("template", "");
            fillPatterns(o, "peer", r.peerRaw, r.peer);
            fillPatterns(o, "body", r.bodyRaw, r.body);
            fillChannels(o, r.channels);
            r.fromRaw = o.optString("from", "").trim();
            r.toRaw = o.optString("to", "").trim();
            r.fromMin = r.fromRaw.length() == 0 ? -1 : parseHHmm(r.fromRaw);
            r.toMin = r.toRaw.length() == 0 ? -1 : parseHHmm(r.toRaw);
            if (r.fromMin < 0 || r.toMin < 0) {
                r.fromMin = -1;
                r.toMin = -1;
                r.fromRaw = "";
                r.toRaw = "";
            }
            out.add(r);
        }
        return out;
    }

    private static void fillPatterns(JSONObject o, String key,
                                     List<String> raws, List<Pattern> pats) {
        JSONArray a = o.optJSONArray(key);
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                addPattern(a.optString(i, ""), raws, pats);
            }
        } else {
            addPattern(o.optString(key, ""), raws, pats);
        }
    }

    private static void addPattern(String s, List<String> raws, List<Pattern> pats) {
        s = s == null ? "" : s.trim();
        if (s.length() == 0) {
            return;
        }
        try {
            pats.add(Pattern.compile(s));
            raws.add(s);
        } catch (PatternSyntaxException ignored) {
            // 手改配置可能写出坏正则：跳过，避免服务起不来
        }
    }

    private static void fillChannels(JSONObject o, List<String> out) {
        JSONArray a = o.optJSONArray("channels");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                addChannel(a.optString(i, ""), out);
            }
        } else {
            String[] parts = o.optString("channels", "").split("[,，;；\\s]+");
            for (String p : parts) {
                addChannel(p, out);
            }
        }
    }

    private static void addChannel(String s, List<String> out) {
        s = s == null ? "" : s.trim();
        if (s.length() > 0 && !out.contains(s)) {
            out.add(s);
        }
    }

    /** 面板保存前严格校验，坏正则/重名/未知通道直接拒绝。 */
    public static void validate(JSONArray arr, List<String> channelNames) {
        if (arr == null) {
            return;
        }
        if (arr.length() > MAX_RULES) {
            throw new IllegalArgumentException("规则最多 " + MAX_RULES + " 条");
        }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条规则必须是对象");
            }
            String name = o.optString("name", "").trim();
            if (name.length() == 0) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条规则缺少 name");
            }
            if (names.contains(name)) {
                throw new IllegalArgumentException("规则名重复：" + name);
            }
            names.add(name);

            String action = o.optString("action", "relay");
            if (!"relay".equals(action) && !"drop".equals(action)) {
                throw new IllegalArgumentException("规则 " + name + " 的 action 只能是 relay 或 drop");
            }
            int prio = o.optInt("priority", 0);
            if (prio < 0 || prio > MAX_PRIORITY) {
                throw new IllegalArgumentException("规则 " + name + " 的 priority 需在 0-" + MAX_PRIORITY);
            }
            checkPatterns(o, "peer", name);
            checkPatterns(o, "body", name);

            boolean hasFrom = o.optString("from", "").trim().length() > 0;
            boolean hasTo = o.optString("to", "").trim().length() > 0;
            if (hasFrom != hasTo) {
                throw new IllegalArgumentException("规则 " + name + " 的 from/to 必须成对出现");
            }
            if (hasFrom && (parseHHmm(o.optString("from", "")) < 0
                    || parseHHmm(o.optString("to", "")) < 0)) {
                throw new IllegalArgumentException("规则 " + name + " 的 from/to 需为 HH:mm");
            }

            List<String> chs = new ArrayList<String>();
            fillChannels(o, chs);
            if ("relay".equals(action) && chs.isEmpty()) {
                throw new IllegalArgumentException("规则 " + name + " 是转发规则但 channels 为空");
            }
            for (String ch : chs) {
                if (channelNames != null && !channelNames.contains(ch)) {
                    throw new IllegalArgumentException("规则 " + name + " 引用了不存在的通道：" + ch);
                }
            }
        }
    }

    private static void checkPatterns(JSONObject o, String key, String name) {
        JSONArray a = o.optJSONArray(key);
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                compile(a.optString(i, ""), key, name);
            }
        } else {
            compile(o.optString(key, ""), key, name);
        }
    }

    private static void compile(String s, String key, String name) {
        s = s == null ? "" : s.trim();
        if (s.length() == 0) {
            return;
        }
        try {
            Pattern.compile(s);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("规则 " + name + " 的 " + key + " 正则无效：" + s);
        }
    }

    static int parseHHmm(String s) {
        if (s == null) {
            return -1;
        }
        s = s.trim();
        if (!s.matches("\\d{1,2}:\\d{2}")) {
            return -1;
        }
        int i = s.indexOf(':');
        int h = Integer.parseInt(s.substring(0, i));
        int m = Integer.parseInt(s.substring(i + 1));
        if (h > 23 || m > 59) {
            return -1;
        }
        return h * 60 + m;
    }

    private static int clampPriority(int p) {
        return Math.max(0, Math.min(MAX_PRIORITY, p));
    }

    // ---------- 求值 ----------

    public static Result route(SmsEvent e, List<Rule> rules,
                               List<String> allChannels, String unmatched) {
        return route(e, rules, allChannels, unmatched, System.currentTimeMillis());
    }

    public static Result route(SmsEvent e, List<Rule> rules,
                               List<String> allChannels, String unmatched, long now) {
        Result res = new Result(false, null);
        for (int i = 0; rules != null && i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (!r.enabled || !match(r, e, now)) {
                continue;
            }
            res.matched.add(r.name);
            if (r.dropAction) {
                Result drop = new Result(true, r.name);
                drop.matched.addAll(res.matched);
                return drop;
            }
            if (res.rule == null) {
                res.rule = r.name;
            }
            merge(res.targets, r.channels, r.priority, r.template);
            if (r.stop) {
                break;
            }
        }
        if (res.targets.isEmpty()) {
            if ("drop".equals(unmatched)) {
                Result drop = new Result(true, null);
                drop.matched.addAll(res.matched);
                return drop;
            }
            merge(res.targets, allChannels, 0, null);
        }
        return res;
    }

    /** 人工动作：不经规则，投全部通道。 */
    public static Result routeAll(List<String> allChannels) {
        Result res = new Result(false, null);
        merge(res.targets, allChannels, 0, null);
        return res;
    }

    public static Result preview(String peer, String body, List<Rule> rules,
                                 List<String> allChannels, String unmatched) {
        SmsEvent e = new SmsEvent();
        e.peer = peer == null ? "" : peer;
        e.body = body == null ? "" : body;
        e.ts = System.currentTimeMillis();
        e.code = SmsEvent.extractCode(e.body);
        return route(e, rules, allChannels, unmatched);
    }

    private static boolean match(Rule r, SmsEvent e, long now) {
        if (r.peer.size() > 0 && !any(r.peer, e.peer)) {
            return false;
        }
        if (r.body.size() > 0 && !any(r.body, e.body)) {
            return false;
        }
        return r.fromMin < 0 || inWindow(now, r.fromMin, r.toMin);
    }

    private static boolean any(List<Pattern> pats, String s) {
        s = s == null ? "" : s;
        for (Pattern p : pats) {
            if (p.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean inWindow(long now, int fromMin, int toMin) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (fromMin <= toMin) {
            return cur >= fromMin && cur <= toMin;
        }
        return cur >= fromMin || cur <= toMin;
    }

    private static void merge(List<Target> acc, List<String> chs, int prio, String template) {
        if (chs == null) {
            return;
        }
        for (String ch : chs) {
            if (ch == null || ch.length() == 0) {
                continue;
            }
            Target exist = null;
            for (Target t : acc) {
                if (t.ch.equals(ch)) {
                    exist = t;
                    break;
                }
            }
            if (exist == null) {
                acc.add(new Target(ch, prio, template));
            } else if (prio > exist.prio) {
                acc.set(acc.indexOf(exist), new Target(ch, prio, template));
            }
        }
    }
}
