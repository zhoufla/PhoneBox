package com.zhoufla.phonebox;

import android.os.SystemClock;

/** 服务与面板的进程内状态。只用于本进程的 UI/API 展示。 */
public final class ServiceState {

    public static volatile boolean running = false;
    public static volatile long startedAtElapsed = 0;
    public static volatile long startedAtWall = 0;
    public static volatile long lastHeartbeatElapsed = 0;
    public static volatile int panelPort = 0;
    public static volatile String panelError = "";

    // DDNS 最近一次结果（进程内）
    public static volatile boolean ddnsRan = false;
    public static volatile boolean ddnsOk = false;
    public static volatile String ddnsIp = "";
    public static volatile String ddnsMessage = "";
    public static volatile String ddnsWarning = "";
    public static volatile long ddnsAt = 0;
    public static volatile int ddnsUpdated = 0;
    public static volatile int ddnsUnchanged = 0;

    private ServiceState() {
    }

    public static void setDdns(Ddns.Result r) {
        if (r == null) {
            return;
        }
        ddnsRan = true;
        ddnsOk = r.ok;
        ddnsIp = r.ip == null ? "" : r.ip;
        ddnsMessage = r.message == null ? "" : r.message;
        ddnsWarning = r.warning == null ? "" : r.warning;
        ddnsAt = r.at;
        ddnsUpdated = r.updated;
        ddnsUnchanged = r.unchanged;
    }

    public static long uptimeSec() {
        if (!running || startedAtElapsed == 0) {
            return 0;
        }
        return Math.max(0, (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000L);
    }

    public static long heartbeatAgeSec() {
        if (lastHeartbeatElapsed == 0) {
            return -1;
        }
        return Math.max(0, (SystemClock.elapsedRealtime() - lastHeartbeatElapsed) / 1000L);
    }
}
