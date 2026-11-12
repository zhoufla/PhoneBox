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

    private ServiceState() {
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
