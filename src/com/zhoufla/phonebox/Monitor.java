package com.zhoufla.phonebox;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统监控快照：电量、电池/芯片温度、信号、CPU、内存、存储、网络。
 *
 * 全部使用公开 API 与 /proc、/sys，不需要 root。任何一项读不到就留空字段，
 * 不影响其它项；调用方永远拿到一个 JSON 对象。
 *
 * 结构见 docs/API.md 的 /api/status 示例。
 */
public final class Monitor {

    private static final int MAX_ZONES = 12;

    /** 上一次 /proc/stat 采样，用于算 CPU 占用；首次调用会补一次短采样。 */
    private static long sLastTotal = -1;
    private static long sLastIdle = -1;

    private Monitor() {
    }

    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", System.currentTimeMillis());
            o.put("uptimeSec", SystemClock.elapsedRealtime() / 1000);
        } catch (Exception ignored) {
        }
        put(o, "battery", battery(ctx));
        put(o, "signal", signal(ctx));
        put(o, "thermal", thermal());
        put(o, "cpu", cpu());
        put(o, "memory", memory());
        put(o, "storage", storage());
        put(o, "network", network(ctx));
        return o;
    }

    private static void put(JSONObject o, String key, JSONObject value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
    }

    // ---------- 电量 ----------

    private static JSONObject battery(Context ctx) {
        JSONObject o = new JSONObject();
        Intent it = null;
        try {
            it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) {
        }
        if (it == null) {
            return o;
        }
        try {
            int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                o.put("level", Math.round(level * 100f / scale));
            }
            int temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) {
                o.put("tempC", round1(temp / 10.0));
            }
            int mv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                o.put("voltageMv", mv);
            }
            o.put("status", batteryStatus(it.getIntExtra(BatteryManager.EXTRA_STATUS, 1)));
            o.put("plugged", plugged(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));
            o.put("health", batteryHealth(it.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String batteryStatus(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "充电中";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "放电中";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "未充电";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "已充满";
            default:
                return "未知";
        }
    }

    private static String plugged(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "电源";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "无线";
            default:
                return "";
        }
    }

    private static String batteryHealth(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "良好";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "过热";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "过冷";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "过压";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "失效";
            default:
                return "未知";
        }
    }

    // ---------- 信号 ----------

    private static JSONObject signal(Context ctx) {
        JSONObject o = new JSONObject();
        TelephonyManager tm;
        try {
            tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Exception e) {
            return o;
        }
        if (tm == null) {
            return o;
        }
        try {
            o.put("operator", nz(tm.getNetworkOperatorName()));
        } catch (Exception ignored) {
        }
        fillSignal(tm, o);

        JSONArray sims = new JSONArray();
        try {
            // 与 /api/status 的 sims 同源：卡槽、运营商、号码（自动 + 手动），再补逐卡信号。
            JSONArray list = Sims.list(ctx);
            for (int i = 0; i < list.length(); i++) {
                JSONObject src = list.optJSONObject(i);
                if (src == null) {
                    continue;
                }
                JSONObject s = new JSONObject(src.toString());
                int subId = s.optInt("subId", -1);
                if (subId >= 0) {
                    try {
                        fillSignal(tm.createForSubscriptionId(subId), s);
                    } catch (Exception ignored) {
                    }
                }
                sims.put(s);
            }
        } catch (Exception ignored) {
            // 无 READ_PHONE_STATE 授权或 ROM 限制：只保留默认卡的信号
        }
        try {
            o.put("sims", sims);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void fillSignal(TelephonyManager tm, JSONObject o) {
        if (tm == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) {
                    o.put("level", clamp(ss.getLevel(), 0, 4));
                    List<CellSignalStrength> cells = ss.getCellSignalStrengths();
                    int best = Integer.MIN_VALUE;
                    if (cells != null) {
                        for (CellSignalStrength c : cells) {
                            int dbm = c.getDbm();
                            if (dbm <= -1 && dbm > -300 && dbm > best) {
                                best = dbm;
                            }
                        }
                    }
                    if (best != Integer.MIN_VALUE) {
                        o.put("dbm", best);
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 28) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) {
                    o.put("level", clamp(ss.getLevel(), 0, 4));
                    int asu = ss.getGsmSignalStrength();
                    if (asu >= 0 && asu <= 31) {
                        o.put("dbm", -113 + 2 * asu);
                    }
                }
            }
            o.put("type", networkType(tm.getDataNetworkType()));
        } catch (Exception ignored) {
        }
    }

    private static String networkType(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "H+";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "WiFi 通话";
            default:
                return type == TelephonyManager.NETWORK_TYPE_UNKNOWN ? "" : String.valueOf(type);
        }
    }

    // ---------- 温度 ----------

    private static JSONObject thermal() {
        return thermal(new File("/sys/class/thermal"));
    }

    /** base 可注入，便于离线测试；生产环境固定 /sys/class/thermal。 */
    static JSONObject thermal(File base) {
        JSONObject o = new JSONObject();
        File[] dirs = base == null ? null : base.listFiles();
        if (dirs == null) {
            return o;
        }
        Arrays.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        List<JSONObject> zones = new ArrayList<JSONObject>();
        double max = Double.NEGATIVE_INFINITY;
        for (File dir : dirs) {
            if (!dir.getName().startsWith("thermal_zone")) {
                continue;
            }
            double c = readTempC(new File(dir, "temp"));
            if (Double.isNaN(c)) {
                continue;
            }
            String name = readText(new File(dir, "type"));
            name = name == null || name.trim().isEmpty() ? dir.getName() : name.trim();
            try {
                JSONObject z = new JSONObject();
                z.put("name", name);
                z.put("tempC", round1(c));
                zones.add(z);
            } catch (Exception ignored) {
            }
            if (c > max) {
                max = c;
            }
        }
        Collections.sort(zones, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(b.optDouble("tempC", -999), a.optDouble("tempC", -999));
            }
        });

        JSONArray arr = new JSONArray();
        for (int i = 0; i < zones.size() && i < MAX_ZONES; i++) {
            arr.put(zones.get(i));
        }
        try {
            o.put("zones", arr);
            if (max > Double.NEGATIVE_INFINITY) {
                o.put("maxC", round1(max));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 兼容毫摄氏度（30000）和摄氏度（30）两种写法，非法值返回 NaN。 */
    private static double readTempC(File f) {
        String s = readText(f);
        if (s == null) {
            return Double.NaN;
        }
        try {
            double v = Double.parseDouble(s.trim());
            double abs = Math.abs(v);
            if (abs >= 1000) {
                v /= 1000.0;
            } else if (abs > 200) {
                v /= 100.0;
            }
            if (v < -40 || v > 150) {
                return Double.NaN;
            }
            return v;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ---------- CPU ----------

    private static JSONObject cpu() {
        JSONObject o = new JSONObject();
        try {
            o.put("cores", Runtime.getRuntime().availableProcessors());
        } catch (Exception ignored) {
        }

        JSONArray freqs = new JSONArray();
        File cpuDir = new File("/sys/devices/system/cpu");
        File[] dirs = cpuDir.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (File dir : dirs) {
                if (!dir.getName().matches("cpu\\d+")) {
                    continue;
                }
                long khz = readLong(new File(dir, "cpufreq/scaling_cur_freq"));
                if (khz <= 0) {
                    khz = readLong(new File(dir, "cpufreq/cpuinfo_cur_freq"));
                }
                if (khz > 0) {
                    freqs.put(khz / 1000);
                }
            }
            long maxKhz = readLong(new File(cpuDir, "cpu0/cpufreq/cpuinfo_max_freq"));
            if (maxKhz > 0) {
                try {
                    o.put("maxMhz", maxKhz / 1000);
                } catch (Exception ignored) {
                }
            }
        }
        try {
            o.put("freqMhz", freqs);
        } catch (Exception ignored) {
        }

        String load = readText(new File("/proc/loadavg"));
        if (load != null) {
            try {
                String[] p = load.trim().split("\\s+");
                if (p.length >= 3) {
                    o.put("load1", round2(Double.parseDouble(p[0])));
                    o.put("load5", round2(Double.parseDouble(p[1])));
                    o.put("load15", round2(Double.parseDouble(p[2])));
                }
            } catch (Exception ignored) {
            }
        }

        int usage = cpuUsagePct();
        if (usage >= 0) {
            try {
                o.put("usagePct", usage);
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private static synchronized int cpuUsagePct() {
        long[] a = cpuStat();
        if (a == null) {
            return -1;
        }
        if (sLastTotal < 0) {
            // 进程启动后第一次调用：补一次 120ms 采样，避免返回空值
            sLastTotal = a[0];
            sLastIdle = a[1];
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
            a = cpuStat();
            if (a == null) {
                return -1;
            }
        }
        long dt = a[0] - sLastTotal;
        long di = a[1] - sLastIdle;
        sLastTotal = a[0];
        sLastIdle = a[1];
        if (dt <= 0) {
            return -1;
        }
        int pct = (int) Math.round(100.0 * (dt - di) / dt);
        return clamp(pct, 0, 100);
    }

    /** /proc/stat 第一行：total = user..steal 之和，idle = idle + iowait。 */
    private static long[] cpuStat() {
        String line = readText(new File("/proc/stat"));
        if (line == null || !line.startsWith("cpu")) {
            return null;
        }
        try {
            String[] p = line.trim().split("\\s+");
            if (p.length < 6) {
                return null;
            }
            long total = 0;
            int last = Math.min(p.length - 1, 8);
            for (int i = 1; i <= last; i++) {
                total += parseLong(p[i], 0);
            }
            long idle = parseLong(p[4], 0) + parseLong(p[5], 0);
            return new long[]{total, idle};
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 内存 ----------

    private static JSONObject memory() {
        JSONObject o = new JSONObject();
        Map<String, Long> m = new HashMap<String, Long>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String[] p = line.substring(i + 1).trim().split("\\s+");
                if (p.length > 0) {
                    m.put(line.substring(0, i), parseLong(p[0], 0));
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(r);
        }
        long total = value(m, "MemTotal");
        long avail = value(m, "MemAvailable");
        if (avail <= 0) {
            avail = value(m, "MemFree") + value(m, "Buffers") + value(m, "Cached");
        }
        if (total <= 0) {
            return o;
        }
        try {
            o.put("totalMb", total / 1024);
            o.put("availableMb", Math.max(0, avail) / 1024);
            o.put("usedPct", clamp((int) Math.round(100.0 * (total - avail) / total), 0, 100));
            long swapTotal = value(m, "SwapTotal");
            if (swapTotal > 0) {
                o.put("swapTotalMb", swapTotal / 1024);
                o.put("swapFreeMb", value(m, "SwapFree") / 1024);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------- 存储 ----------

    private static JSONObject storage() {
        JSONObject o = new JSONObject();
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            if (total > 0) {
                o.put("dataTotalMb", total / 1048576L);
                o.put("dataFreeMb", free / 1048576L);
                o.put("dataUsedPct", clamp((int) Math.round(100.0 * (total - free) / total), 0, 100));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------- 网络 ----------

    private static JSONObject network(Context ctx) {
        JSONObject o = new JSONObject();
        ConnectivityManager cm;
        try {
            cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Exception e) {
            return o;
        }
        if (cm == null) {
            return o;
        }
        try {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            o.put("connected", caps != null);
            o.put("type", transport(caps));
            if (active != null) {
                LinkProperties lp = cm.getLinkProperties(active);
                if (lp != null) {
                    o.put("iface", nz(lp.getInterfaceName()));
                    JSONArray v4 = new JSONArray();
                    JSONArray v6 = new JSONArray();
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la == null ? null : la.getAddress();
                        if (addr == null || addr.isLoopbackAddress()
                                || addr.isLinkLocalAddress() || addr.isMulticastAddress()) {
                            continue;
                        }
                        String ip = nz(addr.getHostAddress());
                        int zone = ip.indexOf('%');
                        if (zone >= 0) {
                            ip = ip.substring(0, zone);
                        }
                        if (addr instanceof Inet4Address) {
                            v4.put(ip);
                        } else if (addr instanceof Inet6Address) {
                            v6.put(ip);
                        }
                    }
                    o.put("ipv4", v4);
                    o.put("ipv6", v6);
                }
            }
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx != TrafficStats.UNSUPPORTED) {
                o.put("rxBytes", rx);
                o.put("txBytes", tx);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String transport(NetworkCapabilities caps) {
        if (caps == null) {
            return "none";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ethernet";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "vpn";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            return "bluetooth";
        }
        return "other";
    }

    // ---------- 工具 ----------

    private static String readText(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(r);
        }
    }

    private static long readLong(File f) {
        String s = readText(f);
        return s == null ? -1 : parseLong(s.trim(), -1);
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static long value(Map<String, Long> m, String key) {
        Long v = m.get(key);
        return v == null ? 0 : v;
    }

    private static void closeQuietly(BufferedReader r) {
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
