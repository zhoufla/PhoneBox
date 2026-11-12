package com.zhoufla.phonebox;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文件收件箱：把面板上传的文件落到手机公共「下载/PhoneBox」目录，便于文件管理器直接看到。
 *
 * - Android 10+ 走 MediaStore（分区存储，不需要任何存储权限）；
 * - Android 8/9 回退到公共目录 + WRITE_EXTERNAL_STORAGE（Manifest 里 maxSdkVersion=28）；
 * - 索引写在应用私有 files/inbox.json，只记录元数据，不复制文件。
 */
public final class WebFiles {

    private static final String INDEX = "inbox.json";
    private static final String SUB_DIR = "PhoneBox";
    private static final int MAX_ITEMS = 200;
    private static final Object LOCK = new Object();

    private WebFiles() {
    }

    /** 保存上传文件；temp 由调用方负责（本方法会删除它）。 */
    public static JSONObject save(Context ctx, File temp, String rawName, String kind,
                                  long maxBytes, long maxTotal, int keepDays) throws Exception {
        long size = temp.length();
        if (size <= 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("文件超过单文件上限 "
                    + bytesText(maxBytes) + "（实际 " + bytesText(size) + "）");
        }
        String name = cleanName(rawName);
        String mime = mimeOf(name);
        String id = System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffff));

        synchronized (LOCK) {
            prune(ctx, keepDays, maxTotal, size);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Uri uri = writePublic(ctx, name, mime, temp, digest);
            String sha = hex(digest.digest());
            //noinspection ResultOfMethodCallIgnored
            temp.delete();

            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("name", name);
            item.put("size", size);
            item.put("ts", System.currentTimeMillis());
            item.put("mime", mime);
            item.put("kind", kind == null || kind.length() == 0 ? "file" : kind);
            item.put("uri", uri.toString());
            item.put("relPath", SUB_DIR + "/" + name);
            item.put("sha256", sha);

            JSONObject index = readIndex(ctx);
            JSONArray items = index.optJSONArray("items");
            if (items == null) {
                items = new JSONArray();
            }
            JSONArray next = new JSONArray();
            next.put(item);
            for (int i = 0; i < items.length() && next.length() < MAX_ITEMS; i++) {
                JSONObject old = items.optJSONObject(i);
                if (old != null) {
                    next.put(old);
                }
            }
            index.put("version", 1);
            index.put("items", next);
            writeIndex(ctx, index);

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("item", item);
            return out;
        }
    }

    public static JSONObject list(Context ctx) throws Exception {
        synchronized (LOCK) {
            JSONObject index = readIndex(ctx);
            JSONArray items = index.optJSONArray("items");
            JSONArray out = new JSONArray();
            long total = 0;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    out.put(item);
                    total += item.optLong("size", 0);
                }
            }
            JSONObject o = new JSONObject();
            o.put("items", out);
            o.put("count", out.length());
            o.put("bytes", total);
            o.put("dir", "下载/" + SUB_DIR);
            return o;
        }
    }

    public static JSONObject find(Context ctx, String id) throws Exception {
        synchronized (LOCK) {
            JSONArray items = readIndex(ctx).optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null && id.equals(item.optString("id"))) {
                        return item;
                    }
                }
            }
            return null;
        }
    }

    public static boolean delete(Context ctx, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject index = readIndex(ctx);
            JSONArray items = index.optJSONArray("items");
            if (items == null) {
                return false;
            }
            boolean found = false;
            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (id.equals(item.optString("id"))) {
                    remove(ctx, item.optString("uri"));
                    found = true;
                } else {
                    next.put(item);
                }
            }
            if (found) {
                index.put("items", next);
                writeIndex(ctx, index);
            }
            return found;
        }
    }

    /** 打开只读流，用于面板把文件下载回电脑；文件已被外部删除时返回 null。 */
    public static InputStream open(Context ctx, JSONObject item) {
        try {
            String uri = item.optString("uri", "");
            if (uri.startsWith("content://")) {
                return ctx.getContentResolver().openInputStream(Uri.parse(uri));
            }
            return new FileInputStream(new File(Uri.parse(uri).getPath()));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 写入公共目录 ----------

    private static Uri writePublic(Context ctx, String name, String mime, File temp,
                                   MessageDigest digest) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            return Api29.write(ctx, name, mime, temp, digest);
        }

        if (ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Android 9 及以下需要存储权限才能写入下载目录");
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), SUB_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File dst = uniqueFile(dir, name);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            copy(temp, out, digest);
        } finally {
            out.close();
        }
        return Uri.fromFile(dst);
    }

    /** API 29+ 的 MediaStore 写入单独放内部类，避免低版本加载时解析不到 Downloads 常量。 */
    private static final class Api29 {
        static Uri write(Context ctx, String name, String mime, File temp,
                         MessageDigest digest) throws Exception {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法在「下载」目录创建文件");
            }
            boolean ok = false;
            try {
                OutputStream out = cr.openOutputStream(uri);
                if (out == null) {
                    throw new IOException("无法写入「下载」目录");
                }
                try {
                    copy(temp, out, digest);
                } finally {
                    out.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                ok = true;
                return uri;
            } finally {
                if (!ok) {
                    try {
                        cr.delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static void copy(File src, OutputStream out, MessageDigest digest) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) {
            return file;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            file = new File(dir, base + " (" + i + ")" + ext);
            if (!file.exists()) {
                return file;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static void remove(Context ctx, String uri) {
        try {
            if (uri == null || uri.length() == 0) {
                return;
            }
            Uri u = Uri.parse(uri);
            if ("content".equals(u.getScheme())) {
                ctx.getContentResolver().delete(u, null, null);
            } else if ("file".equals(u.getScheme())) {
                //noinspection ResultOfMethodCallIgnored
                new File(u.getPath()).delete();
            }
        } catch (Exception e) {
            Logs.add("删除收件箱文件失败：" + e);
        }
    }

    // ---------- 配额 ----------

    private static void prune(Context ctx, int keepDays, long maxTotal, long incoming)
            throws Exception {
        JSONObject index = readIndex(ctx);
        JSONArray items = index.optJSONArray("items");
        if (items == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long expireBefore = keepDays > 0 ? now - keepDays * 86400000L : 0;

        List<JSONObject> keep = new ArrayList<JSONObject>();
        long total = incoming;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (expireBefore > 0 && item.optLong("ts", 0) < expireBefore) {
                remove(ctx, item.optString("uri"));
                continue;
            }
            keep.add(item);
            total += item.optLong("size", 0);
        }

        Collections.sort(keep, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(a.optLong("ts", 0), b.optLong("ts", 0));
            }
        });

        int removed = 0;
        for (int i = 0; i < keep.size(); i++) {
            boolean tooMany = keep.size() - removed > MAX_ITEMS;
            boolean tooBig = maxTotal > 0 && total > maxTotal;
            if (!tooMany && !tooBig) {
                break;
            }
            JSONObject oldest = keep.get(i);
            remove(ctx, oldest.optString("uri"));
            total -= oldest.optLong("size", 0);
            keep.set(i, null);
            removed++;
        }

        JSONArray next = new JSONArray();
        for (int i = keep.size() - 1; i >= 0; i--) {
            if (keep.get(i) != null) {
                next.put(keep.get(i));
            }
        }
        index.put("version", 1);
        index.put("items", next);
        writeIndex(ctx, index);
    }

    // ---------- 索引 ----------

    private static JSONObject readIndex(Context ctx) {
        File file = new File(ctx.getFilesDir(), INDEX);
        if (!file.isFile()) {
            return new JSONObject();
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void writeIndex(Context ctx, JSONObject index) {
        File tmp = new File(ctx.getFilesDir(), INDEX + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(index.toString(2).getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            File dst = new File(ctx.getFilesDir(), INDEX);
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Exception e) {
            Logs.add("保存收件箱索引失败：" + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------- 工具 ----------

    /** 只留文件名本身：去掉路径分隔符与控制字符，限制长度，空名给默认值。 */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 120; i++) {
            char c = name.charAt(i);
            if (c >= 32 && c != 127 && c != ':' && c != '*' && c != '?' && c != '"'
                    && c != '<' && c != '>' && c != '|') {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.length() == 0 || ".".equals(out) || "..".equals(out)) {
            out = "file-" + System.currentTimeMillis();
        }
        return out;
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) {
            return "text/plain";
        }
        if (n.endsWith(".json")) {
            return "application/json";
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".zip")) {
            return "application/zip";
        }
        return "application/octet-stream";
    }

    static String bytesText(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
