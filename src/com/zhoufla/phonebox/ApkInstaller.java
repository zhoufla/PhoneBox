package com.zhoufla.phonebox;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * APK 校验与安装：手动上传与 GitHub 更新共用。
 *
 * 手动上传没有 Release 清单，因此用「包名 + 签名证书」作为唯一硬校验：
 * 面板是明文 HTTP，若不在安装前比对签名，等于给中间人留了换包的口子。
 */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    public static final class Info {
        public String packageName = "";
        public String versionName = "";
        public long versionCode = 0;
        public String signerSha256 = "";
        public String installedSignerSha256 = "";
        public long installedCode = 0;
        public boolean samePackage = false;
        public boolean sameSigner = false;
    }

    /** 读取档案信息并与已安装应用比对包名、签名。 */
    public static Info inspect(Context ctx, File apk) throws Exception {
        if (apk == null || !apk.isFile()) {
            throw new IllegalArgumentException("APK 文件不存在");
        }
        PackageManager pm = ctx.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), signFlags());
        if (archive == null || archive.packageName == null) {
            throw new IllegalArgumentException("无法解析这个 APK，文件可能已损坏");
        }
        Info info = new Info();
        info.packageName = archive.packageName;
        info.versionName = archive.versionName == null ? "" : archive.versionName;
        info.versionCode = Build.VERSION.SDK_INT >= 28
                ? archive.getLongVersionCode() : archive.versionCode;
        info.samePackage = ctx.getPackageName().equals(archive.packageName);

        Signature[] archiveSigs = signatures(archive);
        PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), signFlags());
        Signature[] installedSigs = signatures(installed);
        info.signerSha256 = archiveSigs.length > 0 ? sha256(archiveSigs[0].toByteArray()) : "";
        info.installedSignerSha256 = installedSigs.length > 0
                ? sha256(installedSigs[0].toByteArray()) : "";
        info.installedCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        info.sameSigner = info.signerSha256.length() > 0
                && info.signerSha256.equals(info.installedSignerSha256);
        return info;
    }

    /** 交给系统安装器；返回 sessionId，结果通过 receiver 回调。 */
    public static int commit(Context ctx, File apk,
                             Class<? extends BroadcastReceiver> receiver,
                             String action, String dataPrefix) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        boolean committed = false;
        try {
            PackageInstaller.Session session = installer.openSession(sessionId);
            InputStream in = new FileInputStream(apk);
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                session.fsync(out);
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

            Intent status = new Intent(ctx, receiver);
            status.setAction(action);
            status.setData(Uri.parse(dataPrefix + sessionId));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                // PackageInstaller 必须能把状态码和确认 Intent 填入回调。
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            session.close();
            committed = true;
            return sessionId;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static int signFlags() {
        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        }
        return flags;
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            Signature[] apk = Api28.sigs(info);
            if (apk != null && apk.length > 0) {
                return apk;
            }
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    /** API 28+ 的 SigningInfo 单独放内部类，避免低版本解析不到字段。 */
    private static final class Api28 {
        static Signature[] sigs(PackageInfo info) {
            SigningInfo signing = info.signingInfo;
            return signing == null ? null : signing.getApkContentsSigners();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(data);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
