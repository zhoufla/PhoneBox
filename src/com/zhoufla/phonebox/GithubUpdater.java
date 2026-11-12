package com.zhoufla.phonebox;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** GitHub Releases 更新检查、下载校验与用户确认式安装。 */
public final class GithubUpdater {

    private static final String RELEASE_API =
            "https://api.github.com/repos/zhoufla/PhoneBox/releases/latest";
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 60000;
    private static final long MAX_APK_BYTES = 60L * 1024L * 1024L;

    private GithubUpdater() {
    }

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    public static final class Release {
        public String version = "";
        public String tag = "";
        public String apkName = "";
        public String apkUrl = "";
        public String sha256 = "";
        public String notes = "";
        public long code;
        public long size;
        public long installedCode;

        public boolean updateAvailable() {
            return code > installedCode;
        }
    }

    /** 检查 GitHub 最新正式 Release，并确认附件清单、APK 名称与版本元数据一致。 */
    public static Release check(Context context) throws Exception {
        JSONObject releaseJson = new JSONObject(getText(RELEASE_API, 1024 * 1024));
        String tag = releaseJson.optString("tag_name", "").trim();
        if (tag.length() == 0) {
            throw new IllegalStateException("GitHub Release 没有 tag_name");
        }

        JSONArray assets = releaseJson.optJSONArray("assets");
        if (assets == null || assets.length() == 0) {
            throw new IllegalStateException("最新 Release 没有附件");
        }
        JSONObject manifestAsset = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && "latest.json".equals(asset.optString("name", ""))) {
                manifestAsset = asset;
                break;
            }
        }
        if (manifestAsset == null) {
            throw new IllegalStateException("Release 缺少 latest.json，无法校验更新包");
        }

        String manifestUrl = manifestAsset.optString("browser_download_url", "");
        if (!isReleaseAssetUrl(manifestUrl)) {
            throw new IllegalStateException("Release 的 latest.json 没有下载地址");
        }
        JSONObject manifest = new JSONObject(getText(manifestUrl, 64 * 1024));
        String version = manifest.optString("version", "").trim();
        String apkName = manifest.optString("apk", "").trim();
        String digest = manifest.optString("sha256", "").trim().toLowerCase();
        long code = manifest.optLong("code", -1);
        if (version.length() == 0 || apkName.length() == 0 || code <= 0
                || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("latest.json 缺少有效版本号、APK 名称、版本码或 SHA-256");
        }
        String cleanTag = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!cleanTag.equals(version)) {
            throw new IllegalStateException("Release 标签与 latest.json 版本不一致");
        }

        JSONObject apkAsset = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && apkName.equals(asset.optString("name", ""))) {
                apkAsset = asset;
                break;
            }
        }
        if (apkAsset == null) {
            throw new IllegalStateException("Release 中找不到 APK：" + apkName);
        }
        String apkUrl = apkAsset.optString("browser_download_url", "");
        long size = apkAsset.optLong("size", 0);
        if (!isReleaseAssetUrl(apkUrl) || size <= 0 || size > MAX_APK_BYTES) {
            throw new IllegalStateException("APK 下载地址无效或文件大小超出限制");
        }

        PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0);
        long installedCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;

        Release result = new Release();
        result.version = version;
        result.tag = tag;
        result.apkName = apkName;
        result.apkUrl = apkUrl;
        result.sha256 = digest;
        result.code = code;
        result.size = size;
        result.installedCode = installedCode;
        result.notes = releaseJson.optString("body", "").trim();
        return result;
    }

    private static boolean isReleaseAssetUrl(String value) {
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && "github.com".equalsIgnoreCase(url.getHost())
                    && url.getPath().startsWith("/zhoufla/PhoneBox/releases/download/");
        } catch (Exception e) {
            return false;
        }
    }

    /** 下载到应用私有缓存并流式校验大小与 SHA-256。 */
    public static File download(Context context, Release release, Progress progress)
            throws Exception {
        if (release == null || release.apkUrl.length() == 0) {
            throw new IllegalArgumentException("没有可下载的更新");
        }
        HttpURLConnection connection = null;
        File file = File.createTempFile("phonebox-update-", ".apk", context.getCacheDir());
        boolean keep = false;
        try {
            connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "PhoneBox-Updater/1");
            connection.setRequestProperty("Accept", "application/octet-stream");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("下载 APK 失败（HTTP " + code + "）");
            }

            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES || (total > 0 && release.size > 0 && total != release.size)) {
                throw new IllegalStateException("APK 大小与 Release 附件信息不符");
            }
            if (total <= 0) {
                total = release.size;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = connection.getInputStream();
            OutputStream out = new FileOutputStream(file);
            long count = 0;
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n;
                    if (count > MAX_APK_BYTES) {
                        throw new IllegalStateException("APK 超过 60 MB 限制");
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    if (progress != null) {
                        progress.onProgress(count, total);
                    }
                }
                out.flush();
            } finally {
                try { in.close(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }

            if (release.size > 0 && count != release.size) {
                throw new IllegalStateException("下载文件大小不符：预期 "
                        + release.size + "，实际 " + count);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(release.sha256)) {
                throw new SecurityException("APK SHA-256 校验失败");
            }
            keep = true;
            return file;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (!keep) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** 将已校验 APK 写入 PackageInstaller；系统安装界面仍要求用户确认。 */
    public static int install(Context context, File apk, long expectedCode) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("下载包不是 PhoneBox APK");
        }
        long archiveCode = Build.VERSION.SDK_INT >= 28
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveCode != expectedCode) {
            throw new SecurityException("APK 版本码与 Release 清单不一致");
        }

        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
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
                try { in.close(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
            }

            Intent status = new Intent(context, UpdateInstallReceiver.class);
            status.setAction("com.zhoufla.phonebox.UPDATE_INSTALL_STATUS");
            status.setData(Uri.parse("phonebox://install/" + sessionId));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                // PackageInstaller 必须能把状态码和确认 Intent 填入回调。
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            session.close();
            committed = true;
            return sessionId;
        } finally {
            if (!committed) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
            }
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    private static String getText(String url, int maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(READ_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "PhoneBox-Updater/1");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("访问 GitHub 失败（HTTP " + code + "）");
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > maxBytes) {
                        throw new IllegalStateException("GitHub 响应超过大小限制");
                    }
                    out.write(buffer, 0, n);
                }
            } finally {
                in.close();
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
