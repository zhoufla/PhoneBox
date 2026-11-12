package com.zhoufla.phonebox;

import android.util.Log;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 进程内环形日志。当前只保存在内存，重启清空；后续阶段再加落盘轮转。 */
public final class Logs {

    private static final int MAX = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Logs() {
    }

    public static void add(String msg) {
        if (msg == null) {
            return;
        }
        String ts;
        synchronized (TIME) {
            ts = TIME.format(new Date());
        }
        String line = ts + " " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) {
                LINES.removeFirst();
            }
        }
        Log.i("PhoneBox", msg);
    }

    public static String tail(int limit) {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            int n = 0;
            for (String line : LINES) {
                if (n++ < LINES.size() - limit) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static JSONArray json(int limit) {
        JSONArray arr = new JSONArray();
        synchronized (LINES) {
            int skip = Math.max(0, LINES.size() - limit);
            int i = 0;
            for (String line : LINES) {
                if (i++ >= skip) {
                    arr.put(line);
                }
            }
        }
        return arr;
    }
}
