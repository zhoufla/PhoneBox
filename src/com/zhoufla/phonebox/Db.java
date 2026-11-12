package com.zhoufla.phonebox;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 三张表：
 *   ev   收到的短信本体（hash + 时间窗口去重）
 *   dl   消息 × 通道 的投递状态，各自退避重试
 *   out  从面板发出的短信（含发送结果）
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "phonebox.db";
    private static final int VER = 1;

    private static final String CREATE_EV =
            "CREATE TABLE IF NOT EXISTS ev (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "hash TEXT UNIQUE, ts INTEGER, peer TEXT, body TEXT, code TEXT," +
                    "sub_id INTEGER DEFAULT -1, rule TEXT DEFAULT '', created_at INTEGER)";
    private static final String CREATE_DL =
            "CREATE TABLE IF NOT EXISTS dl (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ev_id INTEGER, ch TEXT, status INTEGER DEFAULT 0, tries INTEGER DEFAULT 0," +
                    "next_at INTEGER DEFAULT 0, err TEXT DEFAULT ''," +
                    "prio INTEGER DEFAULT 0, template TEXT DEFAULT '', UNIQUE(ev_id, ch))";
    private static final String CREATE_OUT =
            "CREATE TABLE IF NOT EXISTS out (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts INTEGER, peer TEXT, body TEXT, parts INTEGER DEFAULT 1," +
                    "sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT 0, err TEXT DEFAULT '')";

    private static volatile Db instance;

    public static Db get(Context c) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) {
                    instance = new Db(c.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private Db(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_EV);
        db.execSQL(CREATE_DL);
        db.execSQL(CREATE_OUT);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dl_due ON dl(status, next_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_ts ON ev(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 刚起步；后续迁移在这里按 oldVersion 递增。
    }

    // ---------- 收信 ----------

    /**
     * 入库。广播路径的 ts 来自短信中心，补收路径来自 provider，可能差几秒，
     * 所以判重必须留时间窗口；同号码 + 同内容 + 同 subId 在窗口内视为同一条。
     */
    public long insertEvent(SmsEvent e, int windowSec) {
        try {
            long near = nearDuplicateId(e.peer, e.body, e.ts, windowSec);
            if (near > 0) {
                return near;
            }
            ContentValues v = new ContentValues();
            v.put("hash", e.hash());
            v.put("ts", e.ts);
            v.put("peer", e.peer);
            v.put("body", e.body);
            v.put("code", e.code);
            v.put("sub_id", e.subId);
            v.put("created_at", System.currentTimeMillis());
            long id = getWritableDatabase().insertWithOnConflict(
                    "ev", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (id > 0) {
                return id;
            }
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id FROM ev WHERE hash=?", new String[]{e.hash()});
            try {
                if (c.moveToFirst()) {
                    return c.getLong(0);
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public long nearDuplicateId(String peer, String body, long ts, int windowSec) {
        long window = Math.max(0, windowSec) * 1000L;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id FROM ev WHERE peer=? AND body=? AND ts BETWEEN ? AND ? " +
                            "ORDER BY id LIMIT 1",
                    new String[]{nz(peer), nz(body),
                            String.valueOf(ts - window), String.valueOf(ts + window)});
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    public void ensureTargets(long evId, List<Router.Target> targets) {
        if (evId <= 0 || targets == null) {
            return;
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            for (Router.Target t : targets) {
                ContentValues v = new ContentValues();
                v.put("ev_id", evId);
                v.put("ch", t.ch);
                v.put("status", 0);
                v.put("tries", 0);
                v.put("next_at", 0);
                v.put("prio", t.prio);
                if (t.template != null && t.template.length() > 0) {
                    v.put("template", t.template);
                }
                db.insertWithOnConflict("dl", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Exception ignored) {
        }
    }

    public void setEventRule(long evId, String rule) {
        if (evId <= 0) {
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put("rule", rule == null ? "" : rule);
            getWritableDatabase().update("ev", v, "id=?",
                    new String[]{String.valueOf(evId)});
        } catch (Exception ignored) {
        }
    }

    public int purgeOrphans() {
        try {
            return getWritableDatabase().delete("dl", "ev_id NOT IN (SELECT id FROM ev)", null);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 投递 ----------

    public List<Deliver> due(int limit) {
        List<Deliver> out = new ArrayList<Deliver>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT d.id,d.ev_id,d.ch,e.ts,e.peer,e.body,e.code,e.sub_id," +
                            "d.tries,d.err,d.prio,d.template " +
                            "FROM dl d JOIN ev e ON e.id=d.ev_id " +
                            "WHERE d.status=0 AND d.next_at<=? " +
                            "ORDER BY d.prio DESC, d.next_at, d.id LIMIT " + Math.max(1, limit),
                    new String[]{String.valueOf(System.currentTimeMillis())});
            while (c.moveToNext()) {
                Deliver d = new Deliver();
                d.dlId = c.getLong(0);
                d.evId = c.getLong(1);
                d.ch = nz(c.getString(2));
                d.ts = c.getLong(3);
                d.peer = nz(c.getString(4));
                d.body = nz(c.getString(5));
                d.code = nz(c.getString(6));
                d.subId = c.getInt(7);
                d.tries = c.getInt(8);
                d.err = nz(c.getString(9));
                d.prio = c.getInt(10);
                d.template = nz(c.getString(11));
                out.add(d);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    public void markOk(long dlId) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", 1);
            v.put("next_at", 0);
            v.put("err", "");
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Exception ignored) {
        }
    }

    public void markFail(long dlId, int tries, String err, int maxTries) {
        try {
            int t = tries + 1;
            ContentValues v = new ContentValues();
            v.put("tries", t);
            v.put("err", clip(err, 300));
            v.put("next_at", System.currentTimeMillis() + Retry.backoffMs(t));
            v.put("status", Retry.giveUp(tries, maxTries) ? 2 : 0);
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Exception ignored) {
        }
    }

    /** 通道暂时不可用（被禁用、未配置好）：推迟重试，不消耗重试次数。 */
    public void defer(long dlId, int minutes, String err) {
        try {
            ContentValues v = new ContentValues();
            v.put("err", clip(err, 300));
            v.put("next_at", System.currentTimeMillis() + Math.max(1, minutes) * 60000L);
            getWritableDatabase().update("dl", v, "id=?", new String[]{String.valueOf(dlId)});
        } catch (Exception ignored) {
        }
    }

    /** 面板「重发」：失败/放弃的投递重置为待发；同时补上新加的通道。 */
    public int retryEvent(long evId, List<Router.Target> targets) {
        ensureTargets(evId, targets);
        try {
            ContentValues v = new ContentValues();
            v.put("status", 0);
            v.put("tries", 0);
            v.put("next_at", 0);
            v.put("err", "");
            return getWritableDatabase().update("dl", v, "ev_id=? AND status<>1",
                    new String[]{String.valueOf(evId)});
        } catch (Exception e) {
            return 0;
        }
    }

    public int pending() {
        return count("SELECT COUNT(*) FROM dl WHERE status=0");
    }

    public int delivered() {
        return count("SELECT COUNT(*) FROM dl WHERE status=1");
    }

    public int failed() {
        return count("SELECT COUNT(*) FROM dl WHERE status=2");
    }

    public int events() {
        return count("SELECT COUNT(*) FROM ev");
    }

    public int countOut() {
        return count("SELECT COUNT(*) FROM out");
    }

    private int count(String sql) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(sql, null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    public String lastError() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT err FROM dl WHERE err<>'' ORDER BY id DESC LIMIT 1", null);
            if (c.moveToFirst()) {
                return nz(c.getString(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "";
    }

    /** 面板分页：最近的收信 + 每个通道的投递状态。 */
    public JSONArray recentJson(int limit, int offset) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,ts,peer,body,code,sub_id,rule FROM ev " +
                            "ORDER BY id DESC LIMIT " + Math.max(1, limit)
                            + " OFFSET " + Math.max(0, offset), null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long id = c.getLong(0);
                long ts = c.getLong(1);
                o.put("id", id);
                o.put("ts", ts);
                o.put("iso", SmsEvent.iso(ts));
                o.put("peer", nz(c.getString(2)));
                o.put("body", nz(c.getString(3)));
                o.put("code", nz(c.getString(4)));
                o.put("subId", c.getInt(5));
                String rule = nz(c.getString(6));
                if (rule.length() > 0) {
                    o.put("rule", rule);
                }
                o.put("deliveries", deliveries(id));
                arr.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    private JSONArray deliveries(long evId) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ch,status,tries,err,next_at FROM dl WHERE ev_id=? ORDER BY id",
                    new String[]{String.valueOf(evId)});
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ch", nz(c.getString(0)));
                o.put("status", c.getInt(1));
                o.put("tries", c.getInt(2));
                o.put("err", nz(c.getString(3)));
                o.put("nextAt", c.getLong(4));
                arr.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    // ---------- 发信 ----------

    public long insertOut(String peer, String body, int parts, int subId) {
        try {
            ContentValues v = new ContentValues();
            v.put("ts", System.currentTimeMillis());
            v.put("peer", peer);
            v.put("body", body);
            v.put("parts", parts);
            v.put("sub_id", subId);
            v.put("status", 0);
            return getWritableDatabase().insert("out", null, v);
        } catch (Exception e) {
            return -1;
        }
    }

    public void markOut(long id, int status, String err) {
        try {
            ContentValues v = new ContentValues();
            v.put("status", status);
            v.put("err", clip(err, 300));
            getWritableDatabase().update("out", v, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception ignored) {
        }
    }

    /**
     * 进程在发送结果回来前被杀：status=0 且超过 5 分钟的记录不再是「发送中」，
     * 标成 3（结果未知）。不自动重发，避免重复短信。
     */
    public int markUnknownOut() {
        try {
            ContentValues v = new ContentValues();
            v.put("status", 3);
            v.put("err", "进程重启，发送结果未知");
            return getWritableDatabase().update("out", v,
                    "status=0 AND ts<?",
                    new String[]{String.valueOf(System.currentTimeMillis() - 5 * 60000L)});
        } catch (Exception e) {
            return 0;
        }
    }

    public JSONArray outboxJson(int limit, int offset) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,ts,peer,body,parts,sub_id,status,err FROM out " +
                            "ORDER BY id DESC LIMIT " + Math.max(1, limit)
                            + " OFFSET " + Math.max(0, offset), null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long ts = c.getLong(1);
                o.put("id", c.getLong(0));
                o.put("ts", ts);
                o.put("iso", iso(ts));
                o.put("peer", nz(c.getString(2)));
                o.put("body", nz(c.getString(3)));
                o.put("parts", c.getInt(4));
                o.put("subId", c.getInt(5));
                o.put("status", c.getInt(6));
                o.put("err", nz(c.getString(7)));
                arr.put(o);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return arr;
    }

    /** 保留天数清理；days<=0 不清理。 */
    public void prune(int days) {
        if (days <= 0) {
            return;
        }
        long before = System.currentTimeMillis() - days * 86400000L;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("dl", "ev_id IN (SELECT id FROM ev WHERE ts<?)",
                    new String[]{String.valueOf(before)});
            db.delete("ev", "ts<?", new String[]{String.valueOf(before)});
            db.delete("out", "ts<?", new String[]{String.valueOf(before)});
        } catch (Exception ignored) {
        }
    }

    private static String iso(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ts));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        s = nz(s);
        return s.length() > max ? s.substring(0, max) : s;
    }
}
