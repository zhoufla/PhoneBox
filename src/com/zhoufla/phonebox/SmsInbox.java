package com.zhoufla.phonebox;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.List;

/**
 * 从系统收件箱补收。非默认短信 App 收不到部分短信时，可手动触发一次。
 *
 * 只读 content://sms/inbox，按 date 倒序取最近 N 条；判定口径与广播路径一致，
 * 已转发过的消息不会重复转发。READ_SMS 未授权时返回 -1。
 */
public final class SmsInbox {

    private static final Uri INBOX = Uri.parse("content://sms/inbox");

    private SmsInbox() {
    }

    /** 返回新入库条数；-1 表示无权限或读取失败。 */
    public static int backfill(Context ctx, int limit) {
        int max = Math.max(1, Math.min(200, limit));
        AppConfig cfg = AppConfig.get(ctx);
        if (!cfg.smsEnabled()) {
            return 0;
        }
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(INBOX,
                    new String[]{"address", "body", "date", "subscription_id"},
                    null, null, "date DESC");
        } catch (Exception e) {
            Logs.add("补收读取失败（subscription_id 不可用）：" + e.getMessage());
        }
        if (c == null) {
            try {
                c = cr.query(INBOX,
                        new String[]{"address", "body", "date"},
                        null, null, "date DESC");
            } catch (Exception e) {
                Logs.add("补收读取失败：" + e.getMessage());
                return -1;
            }
        }
        if (c == null) {
            return -1;
        }

        Db db = Db.get(ctx);
        List<Router.Rule> rules = cfg.rules();
        List<String> active = cfg.activeChannelNames();
        int imported = 0;
        int seen = 0;
        try {
            int iAddr = c.getColumnIndex("address");
            int iBody = c.getColumnIndex("body");
            int iDate = c.getColumnIndex("date");
            int iSub = c.getColumnIndex("subscription_id");
            while (c.moveToNext() && seen < max) {
                seen++;
                SmsEvent e = new SmsEvent();
                e.peer = iAddr >= 0 ? nz(c.getString(iAddr)) : "";
                e.body = iBody >= 0 ? nz(c.getString(iBody)) : "";
                e.ts = iDate >= 0 ? c.getLong(iDate) : System.currentTimeMillis();
                e.subId = iSub >= 0 && !c.isNull(iSub) ? c.getInt(iSub) : -1;
                e.code = SmsEvent.extractCode(e.body);

                long near = db.nearDuplicateId(e.peer, e.body, e.ts, cfg.dedupWindowSec());
                if (near > 0) {
                    continue;
                }
                long id = db.insertEvent(e, cfg.dedupWindowSec());
                if (id <= 0) {
                    continue;
                }
                Router.Result r = Router.route(e, rules, active, cfg.rulesDefault());
                if (r.drop) {
                    db.setEventRule(id, r.rule == null ? "drop" : r.rule);
                } else {
                    db.ensureTargets(id, r.targets);
                }
                imported++;
            }
        } catch (Exception e) {
            Logs.add("补收处理异常：" + e);
        } finally {
            c.close();
        }
        if (imported > 0) {
            Logs.add("补收系统收件箱：" + imported + " 条新短信");
            RelayService.start(ctx);
        }
        return imported;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
