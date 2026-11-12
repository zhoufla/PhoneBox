package com.zhoufla.phonebox;

/**
 * 转发模板。
 *
 * 占位符：{peer} 发件人、{body} 正文、{code} 验证码、{device} 设备名、
 * {tag} 通道标签、{time} 收信时间、{sub} SIM 卡槽。
 */
public final class Tpl {

    public static final String DEFAULT_TEMPLATE = "{peer}\n{body}\n设备：{device}";
    public static final String DEFAULT_CODE_TEMPLATE = "【{code}】{peer}\n{body}\n设备：{device}";

    private Tpl() {
    }

    public static String render(String tpl, SmsEvent e, Channel ch, String device, String time) {
        String t = (tpl == null || tpl.trim().length() == 0)
                ? (e.code != null && e.code.length() > 0 ? DEFAULT_CODE_TEMPLATE : DEFAULT_TEMPLATE)
                : tpl;
        String sim = e.subId <= 0 ? "" : String.valueOf(e.subId);
        t = t.replace("{code}", nz(e.code))
                .replace("{peer}", nz(e.peer))
                .replace("{body}", nz(e.body))
                .replace("{device}", nz(device))
                .replace("{tag}", ch == null ? "" : nz(ch.nzTag()))
                .replace("{time}", nz(time))
                .replace("{sub}", sim);
        return tidy(t);
    }

    /** 去行尾空白、压掉连续空行、去掉首尾空行。 */
    static String tidy(String s) {
        String[] lines = nz(s).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : lines) {
            String l = rtrim(line);
            if (l.length() == 0) {
                blank++;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
                if (blank > 0) {
                    sb.append('\n');
                }
            }
            sb.append(l);
            blank = 0;
        }
        return sb.toString();
    }

    private static String rtrim(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == ' ' || s.charAt(n - 1) == '\t')) {
            n--;
        }
        return s.substring(0, n);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
