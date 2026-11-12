package com.zhoufla.phonebox;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 手动上传安装包的状态机（GitHub 403 / 离线时的兜底通道）。
 *
 * 与 PanelUpdater 完全独立：各自持有状态、各自的 Receiver action，
 * 避免「在线更新」和「手动安装」互相覆盖状态。
 */
public final class LocalUpdater {

    static final String ACTION = "com.zhoufla.phonebox.LOCAL_INSTALL_STATUS";
    static final String DATA_PREFIX = "phonebox://local-install/";

    private static final Object LOCK = new Object();
    private static volatile String state = "idle";
    private static volatile String message = "尚未上传安装包";
    private static volatile boolean busy = false;
    private static volatile long at = 0;
    private static volatile File pending;
    private static volatile String pendingName = "";
    private static volatile JSONObject info;

    private LocalUpdater() {
    }

    public static JSONObject status() {
        JSONObject o = new JSONObject();
        synchronized (LOCK) {
            try {
                o.put("state", state);
                o.put("message", message);
                o.put("busy", busy);
                o.put("at", at);
                o.put("name", pendingName);
                o.put("ready", pending != null && pending.isFile());
                if (info != null) {
                    o.put("apk", new JSONObject(info.toString()));
                }
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    /** 上传后立即校验并暂存；不安装，等用户点按钮。 */
    public static JSONObject stage(Context ctx, File temp, String name) throws Exception {
        synchronized (LOCK) {
            clearLocked();
            File dst = new File(ctx.getFilesDir(), "manual-update.apk");
            if (dst.exists() && !dst.delete()) {
                throw new IllegalStateException("无法替换上一次的安装包");
            }
            if (!temp.renameTo(dst)) {
                copy(temp, dst);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            pending = dst;
            pendingName = name == null ? "PhoneBox.apk" : name;
            at = System.currentTimeMillis();

            ApkInstaller.Info apk;
            try {
                apk = ApkInstaller.inspect(ctx, dst);
            } catch (Exception e) {
                failLocked(brief(e));
                throw e;
            }
            JSONObject detail = new JSONObject();
            detail.put("package", apk.packageName);
            detail.put("version", apk.versionName);
            detail.put("code", apk.versionCode);
            detail.put("installedCode", apk.installedCode);
            detail.put("size", dst.length());
            detail.put("signer", shortHash(apk.signerSha256));
            detail.put("samePackage", apk.samePackage);
            detail.put("sameSigner", apk.sameSigner);
            detail.put("sameVersion", apk.versionCode == apk.installedCode);
            info = detail;

            if (!apk.samePackage) {
                String text = "这不是 PhoneBox 安装包（包名 " + apk.packageName + "）";
                failLocked(text);
                throw new IllegalArgumentException(text);
            }
            if (!apk.sameSigner) {
                String text = "签名与当前应用不一致，已拒绝安装";
                failLocked(text);
                throw new SecurityException(text);
            }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                state = "need_permission";
                message = "安装包校验通过，但尚未允许 PhoneBox「安装未知应用」";
            } else {
                state = "ready";
                message = "安装包已就绪：" + apk.versionName + "（" + apk.versionCode
                        + "），签名一致";
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("state", state);
            out.put("message", message);
            out.put("apk", detail);
            Logs.add("面板上传安装包：" + pendingName + " → " + apk.versionName
                    + "（" + apk.versionCode + "）");
            return out;
        }
    }

    public static JSONObject startInstall(Context ctx) {
        synchronized (LOCK) {
            if (busy) {
                return result(false, "安装任务正在进行中");
            }
            if (pending == null || !pending.isFile()) {
                return result(false, "请先上传 PhoneBox 安装包");
            }
            if ("installed".equals(state)) {
                return result(false, "本次安装已提交，请等待服务重启");
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            synchronized (LOCK) {
                state = "need_permission";
                message = "请先在系统设置里允许 PhoneBox「安装未知应用」，然后重试";
                at = System.currentTimeMillis();
            }
            return result(false, message);
        }

        synchronized (LOCK) {
            busy = true;
            state = "installing";
            message = "正在交给系统安装器…";
            at = System.currentTimeMillis();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int sessionId = ApkInstaller.commit(ctx, pending,
                            LocalInstallReceiver.class, ACTION, DATA_PREFIX);
                    synchronized (LOCK) {
                        // 回调可能比 commit 返回更早，不覆盖最终状态。
                        if (!"installed".equals(state) && !"error".equals(state)) {
                            state = "confirm";
                            message = "请在系统弹窗点「安装」（会话 " + sessionId + "）";
                        }
                    }
                    Logs.add("面板提交手动安装，会话 " + sessionId);
                } catch (Exception e) {
                    fail("手动安装失败：" + brief(e));
                } finally {
                    synchronized (LOCK) {
                        busy = false;
                        at = System.currentTimeMillis();
                    }
                }
            }
        }, "phonebox-local-install");
        t.setDaemon(true);
        t.start();

        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("started", true);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static void onInstallResult(int installStatus, String detail) {
        synchronized (LOCK) {
            if (installStatus == PackageInstaller.STATUS_SUCCESS) {
                state = "installed";
                message = "安装完成，服务即将重启，请稍后刷新页面";
                clearLocked();
            } else if (installStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                state = "confirm";
                message = "请在系统弹窗点「安装」";
            } else {
                state = "error";
                message = detail == null || detail.trim().length() == 0
                        ? "安装失败，请查看运行日志" : detail;
            }
            at = System.currentTimeMillis();
        }
    }

    /** 丢弃待安装包（用户取消或安装成功）。 */
    public static void clear(Context ctx) {
        synchronized (LOCK) {
            clearLocked();
            state = "idle";
            message = "尚未上传安装包";
            at = System.currentTimeMillis();
        }
    }

    private static void clearLocked() {
        if (pending != null) {
            //noinspection ResultOfMethodCallIgnored
            pending.delete();
        }
        pending = null;
        pendingName = "";
        info = null;
    }

    private static void failLocked(String text) {
        clearLocked();
        state = "error";
        message = text;
        at = System.currentTimeMillis();
    }

    private static void fail(String text) {
        synchronized (LOCK) {
            state = "error";
            message = text;
            at = System.currentTimeMillis();
        }
        Logs.add("面板" + text);
    }

    private static JSONObject result(boolean ok, String warning) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            if (warning != null) {
                o.put("warning", warning);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 16 ? hash : hash.substring(0, 16) + "…";
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().length() == 0 ? e.getClass().getSimpleName() : m.trim();
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
