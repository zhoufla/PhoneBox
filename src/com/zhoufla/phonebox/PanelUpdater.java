package com.zhoufla.phonebox;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 面板在线更新：把 GithubUpdater 的「检查 → 下载校验 → PackageInstaller」链路
 * 包成可轮询的异步任务，与 App 首页共用同一套校验规则。
 *
 * 网络下载可能几十秒，全部放后台线程；HTTP 接口只返回即时状态。
 * 安装仍由系统安装器要求用户确认，不会静默安装，也不会写系统分区。
 */
public final class PanelUpdater {

    private static final Object LOCK = new Object();
    private static volatile String state = "idle";
    private static volatile String message = "尚未检查更新";
    private static volatile boolean busy = false;
    private static volatile long downloaded = 0;
    private static volatile long total = 0;
    private static volatile long at = 0;
    /** 最近一次成功检查到的 Release，安装时再重新校验一次元数据。 */
    private static volatile JSONObject release;

    private PanelUpdater() {
    }

    public static JSONObject status() {
        JSONObject o = new JSONObject();
        synchronized (LOCK) {
            try {
                o.put("state", state);
                o.put("message", message);
                o.put("busy", busy);
                o.put("downloaded", downloaded);
                o.put("total", total);
                o.put("percent", total > 0
                        ? (int) Math.min(100, downloaded * 100L / total) : 0);
                o.put("at", at);
                if (release != null) {
                    o.put("release", new JSONObject(release.toString()));
                }
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    /** 启动异步检查；已有任务在跑时只返回提示，不改变状态。 */
    public static JSONObject startCheck(Context ctx) {
        synchronized (LOCK) {
            if (busy) {
                return result(false, "已有更新任务在进行中");
            }
            busy = true;
            state = "checking";
            message = "正在连接 GitHub 检查最新正式 Release…";
            downloaded = 0;
            total = 0;
            at = System.currentTimeMillis();
        }
        startThread(ctx, true);
        return result(true, null);
    }

    /** 启动异步下载 + 安装；无可用更新或未拿到「安装未知应用」权限时给出明确提示。 */
    public static JSONObject startInstall(Context ctx) {
        synchronized (LOCK) {
            if (busy) {
                return result(false, "已有更新任务在进行中");
            }
            if (release == null) {
                return result(false, "请先检查更新");
            }
            if (!release.optBoolean("updateAvailable", false)) {
                return result(false, "当前已是最新正式版本");
            }
            if ("installed".equals(state)) {
                return result(false, "本次更新已提交安装，请等待服务重启");
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            String hint = "请先在系统设置里允许 PhoneBox「安装未知应用」"
                    + "（或打开 App 首页点一次更新授权），然后回来重试";
            synchronized (LOCK) {
                state = "need_permission";
                message = hint;
                at = System.currentTimeMillis();
            }
            return result(false, hint);
        }
        synchronized (LOCK) {
            busy = true;
            state = "downloading";
            message = "准备下载更新…";
            downloaded = 0;
            total = 0;
            at = System.currentTimeMillis();
        }
        startThread(ctx, false);
        return result(true, null);
    }

    /** UpdateInstallReceiver 回写最终状态（成功 / 待确认 / 失败）。 */
    public static void onInstallResult(int installStatus, String detail) {
        synchronized (LOCK) {
            if (installStatus == PackageInstaller.STATUS_SUCCESS) {
                state = "installed";
                message = "安装完成，服务即将重启，请稍后刷新页面";
            } else if (installStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                state = "confirm";
                message = "请在系统弹窗点「安装」";
            } else {
                state = "error";
                message = detail == null || detail.trim().length() == 0
                        ? "更新安装失败，请查看运行日志" : detail;
            }
            at = System.currentTimeMillis();
        }
    }

    private static void startThread(final Context ctx, final boolean checkOnly) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    GithubUpdater.Release r = GithubUpdater.check(ctx);
                    JSONObject info = releaseJson(r);
                    if (checkOnly) {
                        synchronized (LOCK) {
                            release = info;
                            state = r.updateAvailable() ? "available" : "up_to_date";
                            message = r.updateAvailable()
                                    ? "发现新版本 " + r.version + "（当前版本码 " + r.installedCode + "）"
                                    : "当前已是最新正式版本 " + r.version;
                        }
                        Logs.add("面板检查更新：" + message);
                        return;
                    }

                    if (!r.updateAvailable()) {
                        fail("当前已是最新正式版本，取消安装");
                        return;
                    }
                    synchronized (LOCK) {
                        release = info;
                        state = "downloading";
                        message = "正在下载并校验 " + r.version + "…";
                        total = r.size;
                        downloaded = 0;
                    }
                    File apk = GithubUpdater.download(ctx, r, new GithubUpdater.Progress() {
                        @Override
                        public void onProgress(long done, long all) {
                            synchronized (LOCK) {
                                downloaded = done;
                                total = all;
                            }
                        }
                    });
                    synchronized (LOCK) {
                        state = "installing";
                        message = "APK 校验通过，正在交给 Android 安装器…";
                    }
                    int sessionId = GithubUpdater.install(ctx, apk, r.code);
                    synchronized (LOCK) {
                        // 安装回调可能比 install() 返回更早，不覆盖已写入的最终状态。
                        if (!"installed".equals(state) && !"error".equals(state)) {
                            state = "confirm";
                            message = "请在系统弹窗点「安装」（会话 " + sessionId + "）";
                        }
                    }
                    Logs.add("面板提交更新安装，会话 " + sessionId);
                } catch (Exception e) {
                    fail("更新失败：" + brief(e));
                } finally {
                    synchronized (LOCK) {
                        busy = false;
                        at = System.currentTimeMillis();
                    }
                }
            }
        }, checkOnly ? "phonebox-panel-update-check" : "phonebox-panel-update-install");
        t.setDaemon(true);
        t.start();
    }

    private static void fail(String text) {
        synchronized (LOCK) {
            state = "error";
            message = text;
            at = System.currentTimeMillis();
        }
        Logs.add("面板" + text);
    }

    private static JSONObject releaseJson(GithubUpdater.Release r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", r.version);
        o.put("tag", r.tag);
        o.put("apk", r.apkName);
        o.put("code", r.code);
        o.put("installedCode", r.installedCode);
        o.put("size", r.size);
        o.put("sha256", r.sha256);
        o.put("notes", r.notes);
        o.put("updateAvailable", r.updateAvailable());
        return o;
    }

    private static JSONObject result(boolean started, String warning) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", started);
            o.put("started", started);
            if (warning != null) {
                o.put("warning", warning);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().length() == 0 ? e.getClass().getSimpleName() : m.trim();
    }
}
