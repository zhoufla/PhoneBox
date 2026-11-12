package com.zhoufla.phonebox;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * App 首页只做三件事：看服务状态、拿面板地址/凭据、打开面板。
 * 所有配置都在网页里完成。
 */
public final class MainActivity extends Activity {

    private static final int REQ_INSTALL_PERMISSION = 101;

    private AppConfig cfg;
    private TextView statusView;
    private TextView urlView;
    private TextView credView;
    private TextView permView;
    private TextView updateView;
    private Button updateCheckButton;
    private Button updateInstallButton;
    private GithubUpdater.Release availableUpdate;
    private File pendingInstallFile;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = AppConfig.get(this);
        buildUi();
        ensurePermissions();
        Watchdog.scheduleAll(this);
        RelayService.start(this);
        handler.post(refreshTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            boolean denied = false;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    denied = true;
                }
            }
            if (denied) {
                toast("部分权限未授予，对应功能会不可用");
            }
            refreshUi();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_INSTALL_PERMISSION || pendingInstallFile == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
            File apk = pendingInstallFile;
            pendingInstallFile = null;
            submitInstall(apk);
        } else {
            updateView.setText("尚未允许 PhoneBox 安装未知来源应用；允许后可重新下载更新。");
            //noinspection ResultOfMethodCallIgnored
            pendingInstallFile.delete();
            pendingInstallFile = null;
            updateCheckButton.setEnabled(true);
            updateInstallButton.setEnabled(availableUpdate != null
                    && availableUpdate.updateAvailable());
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("PhoneBox");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(17, 17, 17));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("无 root 短信网关 · MIUI 12.5");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(110, 110, 110));
        subtitle.setPadding(0, dp(2), 0, dp(2));
        root.addView(subtitle);

        LinearLayout statusCard = addCard(root);
        statusCard.addView(label("服务状态"));
        statusView = body("正在读取…", 17, Color.rgb(20, 20, 20));
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(statusView);

        urlView = body("", 12, Color.rgb(55, 55, 55));
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(0, dp(8), 0, 0);
        statusCard.addView(urlView);

        credView = body("", 12, Color.rgb(55, 55, 55));
        credView.setTypeface(Typeface.MONOSPACE);
        credView.setTextIsSelectable(true);
        credView.setPadding(0, dp(8), 0, 0);
        statusCard.addView(credView);

        addButton(statusCard, "打开面板", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPanel();
            }
        });
        addButton(statusCard, "复制地址与凭据", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyPanel();
            }
        });

        LinearLayout controlCard = addCard(root);
        controlCard.addView(label("服务控制"));
        addButton(controlCard, "启动服务", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RelayService.start(MainActivity.this);
                refreshUi();
            }
        });
        addButton(controlCard, "停止服务", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RelayService.stop(MainActivity.this);
                refreshUi();
            }
        });
        addButton(controlCard, "重新布防后台", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Watchdog.cancelAll(MainActivity.this);
                Watchdog.scheduleAll(MainActivity.this);
                RelayService.start(MainActivity.this);
                toast("已重新布防心跳闹钟和周期任务");
                refreshUi();
            }
        });

        LinearLayout updateCard = addCard(root);
        updateCard.addView(label("应用更新"));
        updateView = body("当前版本 " + installedVersion(), 13, Color.rgb(50, 50, 50));
        updateCard.addView(updateView);
        TextView updateHint = body(
                "从 GitHub 最新正式 Release 检查更新。下载后会校验 SHA-256，并由 Android 安装器确认；不会静默安装。",
                12, Color.rgb(120, 120, 120));
        updateHint.setPadding(0, dp(6), 0, 0);
        updateCard.addView(updateHint);
        updateCheckButton = addButton(updateCard, "检查更新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });
        updateInstallButton = addButton(updateCard, "下载并安装", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadUpdate();
            }
        });
        updateInstallButton.setEnabled(false);

        LinearLayout permCard = addCard(root);
        permCard.addView(label("保活与权限"));
        permView = body("", 13, Color.rgb(50, 50, 50));
        permView.setTextIsSelectable(true);
        permCard.addView(permView);
        addButton(permCard, "申请权限", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensurePermissions();
            }
        });
        addButton(permCard, "电池优化", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBattery();
            }
        });
        addButton(permCard, "MIUI 自启动", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutostart();
            }
        });

        TextView note = body(
                "短信接收 / 规则转发 / 网页发短信 / 系统监控 / Cloudflare DDNS 已接入。\n"
                        + "心跳闹钟与周期任务会自动把服务拉回来；点“停止服务”会撤掉兜底。\n\n"
                        + "无 root 无法对抗 MIUI 强制停止。请打开自启动、省电策略选“无限制”、"
                        + "在最近任务里锁定本应用，并且不要点“强行停止”。",
                12, Color.rgb(120, 120, 120));
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void refreshUi() {
        if (statusView == null) {
            return;
        }
        int port = ServiceState.panelPort > 0 ? ServiceState.panelPort : cfg.webPort();

        StringBuilder status = new StringBuilder();
        if (ServiceState.running) {
            status.append("运行中");
            long age = ServiceState.heartbeatAgeSec();
            status.append(" · 心跳 ").append(age < 0 ? "等待中" : age + " 秒前");
            status.append(" · 面板 ").append(ServiceState.panelPort > 0
                    ? ":" + ServiceState.panelPort : "启动中");
            try {
                int pending = Db.get(this).pending();
                if (pending > 0) {
                    status.append(" · 待发 ").append(pending);
                }
                status.append(" · 通道 ").append(cfg.activeChannelNames().size());
            } catch (Exception ignored) {
            }
        } else {
            status.append("未运行");
        }
        if (ServiceState.panelError != null && !ServiceState.panelError.isEmpty()) {
            status.append('\n').append(ServiceState.panelError);
        }
        setTextIfChanged(statusView, status.toString());

        List<NetInfo.Addr> addresses = NetInfo.addresses(port);
        if (addresses.isEmpty()) {
            setTextIfChanged(urlView, "未检测到可用地址（请连接 WiFi 或数据网络）");
        } else {
            StringBuilder urls = new StringBuilder("面板地址：\n");
            for (NetInfo.Addr a : addresses) {
                urls.append(a.url).append("  [").append(a.iface).append("]\n");
            }
            setTextIfChanged(urlView, urls.toString().trim());
        }

        setTextIfChanged(credView, "账号 " + cfg.webUser()
                + "\n密码 " + cfg.webPass()
                + "\nToken " + cfg.webToken()
                + "\n\n本机打开 http://127.0.0.1:" + port);

        setTextIfChanged(permView, permissionSummary());
    }

    /** 避免 2 秒轮询对未变化的可选中文本重复 setText，重置焦点/选择并带动 ScrollView 跳动。 */
    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (view != null && !TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private String permissionSummary() {
        StringBuilder b = new StringBuilder();
        appendPermission(b, "短信接收", Manifest.permission.RECEIVE_SMS);
        appendPermission(b, "短信发送", Manifest.permission.SEND_SMS);
        appendPermission(b, "读取短信", Manifest.permission.READ_SMS);
        appendPermission(b, "电话状态", Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            appendPermission(b, "通知", Manifest.permission.POST_NOTIFICATIONS);
        }
        b.append("电池优化：")
                .append(isIgnoringBatteryOptimizations() ? "已忽略" : "未忽略")
                .append('\n');
        JSONObject w = Watchdog.snapshot(this);
        b.append("心跳闹钟：")
                .append(w.optInt("alarmInSec", -1) >= 0
                        ? "约 " + Math.round(w.optInt("alarmInSec", 0) / 60.0) + " 分钟后" : "未布防")
                .append(w.optBoolean("alarmExact", false) ? "（精确）" : "（允许空闲）")
                .append('\n');
        b.append("周期任务：")
                .append(w.optBoolean("jobScheduled", false) ? "已布防" : "未布防")
                .append(" · 已执行 ").append(w.optInt("jobCount", 0)).append(" 次")
                .append('\n');
        b.append("开机自启：需在 MIUI 自启动中手动开启");
        return b.toString();
    }

    private void appendPermission(StringBuilder b, String label, String permission) {
        b.append(label).append("：")
                .append(granted(permission) ? "已授权" : "未授权")
                .append('\n');
    }

    private void ensurePermissions() {
        List<String> need = new ArrayList<String>();
        addIfMissing(need, Manifest.permission.RECEIVE_SMS);
        addIfMissing(need, Manifest.permission.SEND_SMS);
        addIfMissing(need, Manifest.permission.READ_SMS);
        addIfMissing(need, Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(need, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[need.size()]), 100);
        }
    }

    private void addIfMissing(List<String> list, String permission) {
        if (!granted(permission) && !list.contains(permission)) {
            list.add(permission);
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openPanel() {
        int port = ServiceState.panelPort > 0 ? ServiceState.panelPort : cfg.webPort();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + port)));
        } catch (ActivityNotFoundException e) {
            toast("没有可用浏览器");
        }
    }

    private void copyPanel() {
        int port = ServiceState.panelPort > 0 ? ServiceState.panelPort : cfg.webPort();
        StringBuilder sb = new StringBuilder();
        List<NetInfo.Addr> addresses = NetInfo.addresses(port);
        if (addresses.isEmpty()) {
            sb.append("http://127.0.0.1:").append(port);
        } else {
            for (NetInfo.Addr a : addresses) {
                sb.append(a.url).append('\n');
            }
        }
        sb.append("账号：").append(cfg.webUser()).append('\n');
        sb.append("密码：").append(cfg.webPass()).append('\n');
        sb.append("Token：").append(cfg.webToken());

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PhoneBox", sb.toString()));
            toast("已复制面板地址和凭据");
        }
    }

    private void openBattery() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    private void openAutostart() {
        Intent miui = new Intent("miui.intent.action.OP_AUTO_START");
        miui.addCategory(Intent.CATEGORY_DEFAULT);
        miui.putExtra("extra_pkgname", getPackageName());
        if (tryStart(miui)) {
            return;
        }

        Intent editor = new Intent("miui.intent.action.APP_PERM_EDITOR");
        editor.putExtra("extra_package_name", getPackageName());
        if (tryStart(editor)) {
            return;
        }
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开应用设置");
        }
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private void checkForUpdate() {
        updateCheckButton.setEnabled(false);
        updateInstallButton.setEnabled(false);
        updateView.setText("正在连接 GitHub 检查最新正式 Release…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final GithubUpdater.Release release = GithubUpdater.check(MainActivity.this);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            availableUpdate = release.updateAvailable() ? release : null;
                            if (release.updateAvailable()) {
                                StringBuilder text = new StringBuilder();
                                text.append("发现新版本 ").append(release.version)
                                        .append("（当前 ").append(installedVersion()).append("）");
                                if (release.notes.length() > 0) {
                                    text.append("\n\n").append(release.notes);
                                }
                                updateView.setText(text.toString());
                                updateInstallButton.setEnabled(true);
                            } else {
                                updateView.setText("当前已是最新正式版本：" + installedVersion());
                            }
                            updateCheckButton.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            availableUpdate = null;
                            updateView.setText("检查更新失败：" + e.getMessage());
                            updateCheckButton.setEnabled(true);
                            updateInstallButton.setEnabled(false);
                        }
                    });
                }
            }
        }, "phonebox-update-check");
        t.setDaemon(true);
        t.start();
    }

    private void downloadUpdate() {
        final GithubUpdater.Release release = availableUpdate;
        if (release == null || !release.updateAvailable()) {
            toast("请先检查更新");
            return;
        }
        updateCheckButton.setEnabled(false);
        updateInstallButton.setEnabled(false);
        updateView.setText("准备下载 " + release.version + "…");
        Thread t = new Thread(new Runnable() {
            private int lastPercent = -1;

            @Override
            public void run() {
                try {
                    File apk = GithubUpdater.download(MainActivity.this, release,
                            new GithubUpdater.Progress() {
                                @Override
                                public void onProgress(final long downloaded, final long total) {
                                    if (total <= 0) {
                                        return;
                                    }
                                    final int percent = (int) Math.min(100,
                                            downloaded * 100L / total);
                                    if (percent == lastPercent) {
                                        return;
                                    }
                                    lastPercent = percent;
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            updateView.setText("正在下载并校验：" + percent + "%");
                                        }
                                    });
                                }
                            });
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            beginInstall(apk);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateView.setText("下载更新失败：" + e.getMessage());
                            updateCheckButton.setEnabled(true);
                            updateInstallButton.setEnabled(availableUpdate != null
                                    && availableUpdate.updateAvailable());
                        }
                    });
                }
            }
        }, "phonebox-update-download");
        t.setDaemon(true);
        t.start();
    }

    private void beginInstall(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstallFile = apk;
            updateView.setText("请在系统设置中允许 PhoneBox 安装未知来源应用，返回后将继续安装。");
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_INSTALL_PERMISSION);
            } catch (Exception e) {
                pendingInstallFile = null;
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                updateView.setText("无法打开未知来源安装设置：" + e.getMessage());
                updateCheckButton.setEnabled(true);
                updateInstallButton.setEnabled(true);
            }
            return;
        }
        submitInstall(apk);
    }

    private void submitInstall(final File apk) {
        final GithubUpdater.Release release = availableUpdate;
        if (release == null) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return;
        }
        updateView.setText("已校验 APK，正在交给 Android 安装器…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    GithubUpdater.install(MainActivity.this, apk, release.code);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateView.setText("系统安装器已打开，请确认安装。更新后应用会自动重启。");
                            updateCheckButton.setEnabled(true);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateView.setText("启动系统安装器失败：" + e.getMessage());
                            updateCheckButton.setEnabled(true);
                            updateInstallButton.setEnabled(true);
                        }
                    });
                }
            }
        }, "phonebox-update-install");
        t.setDaemon(true);
        t.start();
    }

    private LinearLayout addCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(130, 130, 130));
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView body(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(button, lp);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
