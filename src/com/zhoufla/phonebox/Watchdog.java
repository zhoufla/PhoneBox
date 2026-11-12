package com.zhoufla.phonebox;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * 保活兜底。
 *
 * 无 root 不可能对抗 MIUI 强制停止，这里只做系统允许范围内的恢复：
 *   - AlarmManager 每 15 分钟一次心跳；能用精确闹钟就用，否则退化为
 *     setAndAllowWhileIdle（Doze 下仍可唤醒）
 *   - JobScheduler 周期任务（15 分钟、persisted）在应用被系统回收后把服务拉起来
 *   - 电池优化白名单状态只做展示，跳转由 App 首页负责
 *
 * 闹钟和 Job 都只负责“叫醒并重新布防”，不做业务；DDNS、投递仍在
 * RelayService 的 worker 里完成，避免广播里跑网络。
 */
public final class Watchdog {

    static final String ACTION_HEARTBEAT = "com.zhoufla.phonebox.HEARTBEAT";
    static final long INTERVAL_MS = 15 * 60 * 1000L;
    static final int JOB_ID = 2101;

    private static final String PREFS = "phonebox_watchdog";

    private Watchdog() {
    }

    /** 闹钟和周期任务一起布防；服务启动、开机、面板“重新布防”都调用这里。 */
    public static void scheduleAll(Context ctx) {
        scheduleAlarm(ctx);
        scheduleJob(ctx);
    }

    static PendingIntent alarmIntent(Context ctx) {
        Intent intent = new Intent(ctx, HeartbeatReceiver.class);
        intent.setAction(ACTION_HEARTBEAT);
        return PendingIntent.getBroadcast(ctx, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void scheduleAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long at = System.currentTimeMillis() + INTERVAL_MS;
        boolean exact = true;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                exact = false;
            }
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx));
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx));
            }
        } catch (Throwable t) {
            exact = false;
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx));
            } catch (Throwable t2) {
                Logs.add("设置心跳闹钟失败：" + t2);
                return;
            }
            Logs.add("精确闹钟不可用，改用允许空闲唤醒：" + t);
        }
        prefs(ctx).edit()
                .putLong("alarm_at", at)
                .putBoolean("alarm_exact", exact)
                .apply();
    }

    public static void scheduleJob(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        try {
            if (js.getPendingJob(JOB_ID) != null) {
                prefs(ctx).edit().putBoolean("job_ok", true).apply();
                return;
            }
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                    new ComponentName(ctx, WatchdogJob.class))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true);
            int result = js.schedule(builder.build());
            prefs(ctx).edit().putBoolean("job_ok", result == JobScheduler.RESULT_SUCCESS).apply();
            if (result != JobScheduler.RESULT_SUCCESS) {
                Logs.add("周期任务布防失败：" + result);
            }
        } catch (Throwable t) {
            Logs.add("周期任务布防异常：" + t);
        }
    }

    /** 用户主动停止服务时撤掉所有兜底，避免“停了又自己起来”。 */
    public static void cancelAll(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(alarmIntent(ctx));
            }
        } catch (Throwable ignored) {
        }
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                js.cancel(JOB_ID);
            }
        } catch (Throwable ignored) {
        }
        prefs(ctx).edit()
                .putLong("alarm_at", 0)
                .putBoolean("job_ok", false)
                .apply();
    }

    static void noteAlarm(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit()
                .putInt("alarm_count", p.getInt("alarm_count", 0) + 1)
                .putLong("last_alarm_at", System.currentTimeMillis())
                .apply();
    }

    static void noteJob(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit()
                .putInt("job_count", p.getInt("job_count", 0) + 1)
                .putLong("last_job_at", System.currentTimeMillis())
                .apply();
    }

    /** 面板展示用的快照；任何一项异常都只影响展示，不影响服务。 */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = prefs(ctx);
            long alarmAt = p.getLong("alarm_at", 0);
            o.put("alarmAt", alarmAt);
            o.put("alarmInSec", alarmAt <= 0 ? -1
                    : Math.max(0, (alarmAt - System.currentTimeMillis()) / 1000));
            o.put("alarmExact", p.getBoolean("alarm_exact", false));
            o.put("alarmCount", p.getInt("alarm_count", 0));
            o.put("lastAlarmAt", p.getLong("last_alarm_at", 0));
            o.put("lastJobAt", p.getLong("last_job_at", 0));
            o.put("jobCount", p.getInt("job_count", 0));
            o.put("jobScheduled", jobScheduled(ctx));
            o.put("batteryOptimized", !ignoringBattery(ctx));
            o.put("workerAlive", ServiceState.workerAlive);
            o.put("serviceRunning", ServiceState.running);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static boolean jobScheduled(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            return js != null && js.getPendingJob(JOB_ID) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ignoringBattery(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
