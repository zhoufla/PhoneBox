package com.zhoufla.phonebox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.List;

/** 唯一常驻服务：前台通知、网页面板、心跳、短信投递 worker。 */
public final class RelayService extends Service {

    public static final String ACTION_STOP = "com.zhoufla.phonebox.STOP";
    public static final String ACTION_WAKE = "com.zhoufla.phonebox.WAKE";

    private static final String CHANNEL_ID = "phonebox_service";
    private static final String CHANNEL_ALERT = "phonebox_alert";
    private static final int NOTIFY_ID = 1001;
    private static final int DDNS_NOTIFY_ID = 1002;

    private final Object lock = new Object();
    private volatile boolean running = false;
    private volatile long lastDdnsAt = 0;
    private volatile boolean ddnsRunning = false;
    private volatile boolean ddnsFailing = false;

    private Handler handler;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private volatile int workerGen = 0;
    private Db db;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ServiceState.lastHeartbeatElapsed = SystemClock.elapsedRealtime();
            if (!WebPanel.isRunning()) {
                WebPanel.start(RelayService.this);
            }
            if (!ServiceState.workerAlive) {
                Logs.add("投递线程已退出，重新拉起");
                startWorker();
            }
            updateNotification();
            if (handler != null) {
                handler.postDelayed(this, 60000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();

        ServiceState.running = true;
        ServiceState.startedAtElapsed = SystemClock.elapsedRealtime();
        ServiceState.startedAtWall = System.currentTimeMillis();
        ServiceState.lastHeartbeatElapsed = ServiceState.startedAtElapsed;

        db = Db.get(this);
        db.purgeOrphans();
        db.markUnknownOut();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonebox:deliver");
            wakeLock.setReferenceCounted(false);
        }

        startForeground(NOTIFY_ID, buildNotification("正在启动…"));
        WebPanel.start(this);
        updateNotification();

        handler = new Handler(Looper.getMainLooper());
        handler.post(tick);

        running = true;
        startWorker();
        Watchdog.scheduleAll(this);

        Logs.add("服务启动");
    }

    private void startWorker() {
        running = true;
        final int gen = ++workerGen;
        ServiceState.workerAlive = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop(gen);
                } finally {
                    if (gen == workerGen) {
                        ServiceState.workerAlive = false;
                    }
                }
            }
        }, "phonebox-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Watchdog.cancelAll(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ServiceState.running) {
            ServiceState.running = true;
            ServiceState.startedAtElapsed = SystemClock.elapsedRealtime();
            ServiceState.startedAtWall = System.currentTimeMillis();
        }
        if (!WebPanel.isRunning()) {
            WebPanel.start(this);
        }
        if (!ServiceState.workerAlive) {
            startWorker();
        }
        Watchdog.scheduleAll(this);
        wakeWorker();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ServiceState.running = false;
        WebPanel.stop();
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler = null;
        }
        wakeWorker();
        stopForeground(true);
        Logs.add("服务停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        start(context, null);
    }

    /** 由闹钟/Job 唤醒：带一个动作便于日志区分，重复启动无副作用。 */
    public static void startWake(Context context) {
        start(context, ACTION_WAKE);
    }

    private static void start(Context context, String action) {
        Intent intent = new Intent(context, RelayService.class);
        if (action != null) {
            intent.setAction(action);
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Logs.add("启动服务失败：" + e);
        }
    }

    public static void stop(Context context) {
        if (!ServiceState.running) {
            Watchdog.cancelAll(context);
            context.stopService(new Intent(context, RelayService.class));
            return;
        }
        // 先让服务自己收尾（撤掉闹钟/Job 再停止），避免停了又被兜底拉起来
        Intent intent = new Intent(context, RelayService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Watchdog.cancelAll(context);
            context.stopService(new Intent(context, RelayService.class));
        }
    }

    /** 有新短信/配置变更/面板操作时立刻踢一轮投递。 */
    private void wakeWorker() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ---------- 投递 ----------

    private void loop(int gen) {
        int idle = 0;
        long lastPrune = 0;
        while (running && gen == workerGen) {
            int sent = 0;
            try {
                AppConfig cfg = AppConfig.get(this);
                List<Channel> channels = cfg.channels();
                int active = cfg.activeChannelNames().size();

                if (cfg.smsEnabled() && active > 0) {
                    List<Deliver> list = db.due(10);
                    if (!list.isEmpty()) {
                        acquireWakeLock();
                        try {
                            for (Deliver d : list) {
                                sent += deliverOne(cfg, channels, d);
                            }
                        } finally {
                            releaseWakeLock();
                        }
                    }
                }

                if (System.currentTimeMillis() - lastPrune > 6 * 3600000L) {
                    lastPrune = System.currentTimeMillis();
                    int days = 30;
                    JSONObject retention = cfg.json().optJSONObject("retention");
                    if (retention != null) {
                        days = retention.optInt("days", 30);
                    }
                    db.prune(days);
                }

                maybeDdns(cfg);
            } catch (Throwable t) {
                Logs.add("投递循环异常：" + t);
            }

            updateNotification();
            idle = sent > 0 ? 0 : idle + 1;
            long wait = sent > 0 ? 1500 : (idle > 6 ? 20000 : 5000);
            synchronized (lock) {
                try {
                    lock.wait(wait);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /** 返回 1 表示成功投递。 */
    private int deliverOne(AppConfig cfg, List<Channel> channels, Deliver d) {
        Channel ch = null;
        for (Channel c : channels) {
            if (c.name.equals(d.ch)) {
                ch = c;
                break;
            }
        }
        if (ch == null) {
            db.markFail(d.dlId, 10, "通道已删除: " + d.ch, 10);
            Logs.add("放弃 " + d.ch + "：通道已删除");
            return 0;
        }
        if (!ch.valid()) {
            db.defer(d.dlId, 30, "通道已禁用或配置不完整: " + d.ch);
            return 0;
        }

        String err = Sender.send(ch, d, cfg.deviceName());
        if (err == null) {
            db.markOk(d.dlId);
            Logs.add("送达 " + ch.name + " ← " + d.peer);
            return 1;
        }
        db.markFail(d.dlId, d.tries, err, ch.maxTries);
        Logs.add("失败 " + ch.name + " 第 " + (d.tries + 1) + "/" + ch.maxTries
                + " 次：" + err);
        return 0;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null) {
                wakeLock.acquire(60000);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- DDNS ----------

    /** 到点就在独立线程跑一次，避免 Cloudflare 网络超时阻塞短信投递。 */
    private void maybeDdns(AppConfig cfg) {
        if (!cfg.ddnsEnabled()) {
            if (ddnsFailing) {
                ddnsFailing = false;
                cancelDdnsNotice();
            }
            return;
        }
        if (ddnsRunning) {
            return;
        }
        long interval = cfg.ddnsIntervalMin() * 60000L;
        long now = System.currentTimeMillis();
        if (lastDdnsAt > 0 && now - lastDdnsAt < interval) {
            return;
        }
        lastDdnsAt = now;
        ddnsRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Ddns.Result r = Ddns.run(RelayService.this, false);
                    ServiceState.setDdns(r);
                    if (r.skipped) {
                        return;
                    }
                    if (r.ok) {
                        if (ddnsFailing) {
                            ddnsFailing = false;
                            cancelDdnsNotice();
                        }
                        if (r.updated > 0) {
                            Logs.add("DDNS 已更新 " + r.updated + " 条 → " + r.ip);
                        }
                    } else {
                        Logs.add("DDNS 失败：" + r.message);
                        if (!ddnsFailing) {
                            ddnsFailing = true;
                            notifyDdns(r.message);
                        }
                    }
                } catch (Throwable t) {
                    Logs.add("DDNS 异常：" + t);
                } finally {
                    ddnsRunning = false;
                }
            }
        }, "phonebox-ddns");
        t.setDaemon(true);
        t.start();
    }

    private void notifyDdns(String text) {
        if (notificationManager == null) {
            return;
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ALERT);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DDNS 更新失败")
                .setContentText(text == null ? "" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
                .setAutoCancel(true)
                .setContentIntent(openIntent);
        try {
            notificationManager.notify(DDNS_NOTIFY_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelDdnsNotice() {
        try {
            if (notificationManager != null) {
                notificationManager.cancel(DDNS_NOTIFY_ID);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- 通知 ----------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "常驻服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("短信网关与网页面板");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
            NotificationChannel alert = new NotificationChannel(
                    CHANNEL_ALERT, "异常告警", NotificationManager.IMPORTANCE_DEFAULT);
            alert.setDescription("DDNS 更新失败等需要处理的异常");
            alert.setShowBadge(true);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(alert);
            }
        }
    }

    private void updateNotification() {
        if (notificationManager == null) {
            return;
        }
        String text;
        try {
            int pending = db == null ? 0 : db.pending();
            int channels = AppConfig.get(this).activeChannelNames().size();
            StringBuilder sb = new StringBuilder();
            if (pending > 0) {
                sb.append("待发送 ").append(pending).append(" · ");
            }
            sb.append("通道 ").append(channels);
            if (ServiceState.panelPort > 0) {
                sb.append(" · 面板 :").append(ServiceState.panelPort);
            } else if (ServiceState.panelError != null && !ServiceState.panelError.isEmpty()) {
                sb.append(" · 面板未启动");
            }
            text = sb.toString();
        } catch (Throwable t) {
            text = "运行中";
        }
        try {
            notificationManager.notify(NOTIFY_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RelayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("PhoneBox")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openIntent);
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent).build());
        return builder.build();
    }
}
