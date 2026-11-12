package com.zhoufla.phonebox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 便签/留言板：电脑往面板贴一段文本，手机端打开同一页面一键复制。
 *
 * 单独存 files/notes.json，不动 SQLite（Db.VER=1 且 onUpgrade 为空，加表要写迁移）。
 */
public final class Notes {

    private static final String FILE = "notes.json";
    private static final Object LOCK = new Object();
    public static final int MAX_ITEMS = 100;
    public static final int MAX_TEXT = 8192;

    private Notes() {
    }

    public static JSONObject list(Context ctx) throws Exception {
        synchronized (LOCK) {
            JSONObject index = read(ctx);
            JSONArray items = index.optJSONArray("items");
            JSONObject out = new JSONObject();
            out.put("items", items == null ? new JSONArray() : items);
            out.put("count", items == null ? 0 : items.length());
            out.put("maxItems", MAX_ITEMS);
            out.put("maxText", MAX_TEXT);
            return out;
        }
    }

    public static JSONObject add(Context ctx, String text, String from, int maxItems)
            throws Exception {
        String body = text == null ? "" : text.trim();
        if (body.length() == 0) {
            throw new IllegalArgumentException("内容为空");
        }
        if (body.length() > MAX_TEXT) {
            throw new IllegalArgumentException("单条不超过 " + MAX_TEXT + " 字");
        }
        String source = "phone".equals(from) ? "phone" : "pc";
        int cap = Math.max(10, Math.min(500, maxItems));
        synchronized (LOCK) {
            JSONObject index = read(ctx);
            JSONArray items = index.optJSONArray("items");
            JSONArray next = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("id", System.currentTimeMillis() + "-"
                    + Integer.toHexString((int) (Math.random() * 0xffff)));
            item.put("ts", System.currentTimeMillis());
            item.put("text", body);
            item.put("from", source);
            next.put(item);
            if (items != null) {
                for (int i = 0; i < items.length() && next.length() < cap; i++) {
                    JSONObject old = items.optJSONObject(i);
                    if (old != null) {
                        next.put(old);
                    }
                }
            }
            index.put("version", 1);
            index.put("items", next);
            write(ctx, index);

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("item", item);
            out.put("count", next.length());
            return out;
        }
    }

    public static boolean delete(Context ctx, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject index = read(ctx);
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
                    found = true;
                } else {
                    next.put(item);
                }
            }
            if (found) {
                index.put("items", next);
                write(ctx, index);
            }
            return found;
        }
    }

    public static int clear(Context ctx) throws Exception {
        synchronized (LOCK) {
            JSONObject index = read(ctx);
            JSONArray items = index.optJSONArray("items");
            int n = items == null ? 0 : items.length();
            index.put("version", 1);
            index.put("items", new JSONArray());
            write(ctx, index);
            return n;
        }
    }

    private static JSONObject read(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
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

    private static void write(Context ctx, JSONObject index) {
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(index.toString(2).getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            File dst = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Exception e) {
            Logs.add("保存便签失败：" + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
