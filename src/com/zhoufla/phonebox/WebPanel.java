package com.zhoufla.phonebox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 内嵌 Web 面板：HTTP 服务 + 静态页 + JSON API。 */
public final class WebPanel {

    private static volatile Http http;
    private static final Map<String, byte[]> ASSETS = new HashMap<String, byte[]>();

    private WebPanel() {
    }

    public static synchronized void start(Context context) {
        if (http != null && http.isRunning()) {
            return;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        final Context ctx = context.getApplicationContext();
        final AppConfig cfg = AppConfig.get(ctx);

        if (!cfg.webEnabled()) {
            ServiceState.panelError = "面板已在配置中禁用";
            return;
        }
        int port = cfg.webPort();
        if (port <= 0 || port > 65535) {
            ServiceState.panelError = "面板端口无效：" + port;
            return;
        }

        Http server = new Http(port, new Http.Handler() {
            @Override
            public Http.Resp serve(Http.Req req) throws Exception {
                return route(ctx, cfg, req);
            }
        }, ctx.getCacheDir());
        try {
            server.start();
            http = server;
            ServiceState.panelPort = server.getPort();
            ServiceState.panelError = "";
            Logs.add("面板已启动：" + ServiceState.panelPort);
            return;
        } catch (Exception e) {
            server.stop();
            Logs.add("面板启动失败（端口 " + port + "）：" + e.getMessage());
            if (port != 8080) {
                Http fallback = new Http(8080, new Http.Handler() {
                    @Override
                    public Http.Resp serve(Http.Req req) throws Exception {
                        return route(ctx, cfg, req);
                    }
                }, ctx.getCacheDir());
                try {
                    fallback.start();
                    http = fallback;
                    ServiceState.panelPort = fallback.getPort();
                    ServiceState.panelError = "配置端口 " + port + " 不可用，临时使用 8080";
                    Logs.add(ServiceState.panelError);
                    return;
                } catch (Exception e2) {
                    fallback.stop();
                }
            }
            ServiceState.panelPort = 0;
            ServiceState.panelError = "面板启动失败：" + e.getMessage();
            Logs.add(ServiceState.panelError);
        }
    }

    public static synchronized void stop() {
        Http server = http;
        http = null;
        if (server != null) {
            server.stop();
            Logs.add("面板已停止");
        }
        ServiceState.panelPort = 0;
        ServiceState.panelError = "";
    }

    /** 端口变更后延迟重启，避免在请求线程里关掉自己的 socket。 */
    static void restartAsync(final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                stop();
                start(ctx);
            }
        }, "phonebox-panel-restart");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isRunning() {
        Http server = http;
        return server != null && server.isRunning();
    }

    // ---------- 路由 ----------

    private static Http.Resp route(Context ctx, AppConfig cfg, Http.Req req) {
        if ("/api/ping".equals(req.path)) {
            return Http.Resp.json(200, "{\"ok\":true,\"app\":\"phonebox\"}");
        }
        if ("/favicon.ico".equals(req.path)) {
            return Http.Resp.text(204, "");
        }
        // PWA 静态资源不含敏感数据，放在鉴权之前，浏览器拉 manifest/图标时不会 401。
        if ("GET".equals(req.method) && "/manifest.webmanifest".equals(req.path)) {
            return staticAsset(ctx, "web/manifest.webmanifest",
                    "application/manifest+json; charset=utf-8");
        }
        if ("GET".equals(req.method) && ("/icon-192.png".equals(req.path)
                || "/icon-512.png".equals(req.path))) {
            return staticAsset(ctx, "web" + req.path, "image/png");
        }
        if (!Auth.ok(req, cfg)) {
            return Auth.unauthorized();
        }

        boolean get = "GET".equals(req.method);
        boolean post = "POST".equals(req.method);

        if (get && ("/".equals(req.path) || "/index.html".equals(req.path))) {
            try {
                return Http.Resp.bytes(200, "text/html; charset=utf-8",
                        asset(ctx, "web/index.html"));
            } catch (IOException e) {
                return Http.Resp.text(500, "面板资源缺失：" + e.getMessage());
            }
        }

        if (!get && !post) {
            return err(405, "method not allowed");
        }
        if (post && !"phonebox".equals(req.header("x-requested-with"))) {
            return err(403, "缺少 X-Requested-With: phonebox");
        }

        try {
            if (get && "/api/status".equals(req.path)) {
                return Http.Resp.json(200, status(ctx, cfg));
            }
            if (get && "/api/update/status".equals(req.path)) {
                return Http.Resp.json(200, PanelUpdater.status().toString());
            }
            if (get && "/api/local-update/status".equals(req.path)) {
                return Http.Resp.json(200, LocalUpdater.status().toString());
            }
            if (get && "/api/files".equals(req.path)) {
                return Http.Resp.json(200, WebFiles.list(ctx).toString());
            }
            if (get && "/api/files/download".equals(req.path)) {
                return fileDownload(ctx, req);
            }
            if (get && "/api/notes".equals(req.path)) {
                return Http.Resp.json(200, Notes.list(ctx).toString());
            }
            if (get && "/api/config".equals(req.path)) {
                return Http.Resp.json(200, cfg.publicJson().toString());
            }
            if (get && "/api/logs".equals(req.path)) {
                JSONObject o = new JSONObject();
                o.put("lines", Logs.json(intQ(req, "limit", 100, 1, 500)));
                return Http.Resp.json(200, o.toString());
            }
            if (get && "/api/logs/download".equals(req.path)) {
                Http.Resp r = Http.Resp.text(200, Logs.tail(500));
                r.headers.put("Content-Disposition", "attachment; filename=\"phonebox-logs.txt\"");
                r.headers.put("Cache-Control", "no-store");
                return r;
            }
            if (get && "/api/messages".equals(req.path)) {
                JSONObject o = new JSONObject();
                o.put("total", Db.get(ctx).events());
                o.put("items", Db.get(ctx).recentJson(
                        intQ(req, "limit", 20, 1, 100), intQ(req, "offset", 0, 0, 100000)));
                return Http.Resp.json(200, o.toString());
            }
            if (get && "/api/outbox".equals(req.path)) {
                JSONObject o = new JSONObject();
                o.put("total", Db.get(ctx).countOut());
                o.put("items", Db.get(ctx).outboxJson(
                        intQ(req, "limit", 20, 1, 100), intQ(req, "offset", 0, 0, 100000)));
                return Http.Resp.json(200, o.toString());
            }
            if (get && "/api/health".equals(req.path)) {
                JSONObject o = new JSONObject();
                o.put("ok", ServiceState.running && isRunning());
                o.put("running", ServiceState.running);
                o.put("panel", isRunning());
                o.put("pending", Db.get(ctx).pending());
                o.put("heartbeatAgeSec", ServiceState.heartbeatAgeSec());
                return Http.Resp.json(200, o.toString());
            }

            if (post && "/api/send".equals(req.path)) {
                return send(ctx, cfg, req);
            }
            if (post && "/api/messages/retry".equals(req.path)) {
                return retry(ctx, cfg, req);
            }
            if (post && "/api/inbox/import".equals(req.path)) {
                int n = SmsInbox.backfill(ctx, intQ(req, "limit", 50, 1, 200));
                JSONObject o = new JSONObject();
                o.put("ok", n >= 0);
                o.put("imported", n);
                if (n < 0) {
                    o.put("error", "读取系统收件箱失败，请检查短信权限");
                }
                return Http.Resp.json(200, o.toString());
            }
            if (post && "/api/watchdog/rearm".equals(req.path)) {
                Watchdog.cancelAll(ctx);
                Watchdog.scheduleAll(ctx);
                RelayService.start(ctx);
                Logs.add("面板重新布防后台心跳与周期任务");
                return Http.Resp.json(200, Watchdog.snapshot(ctx).toString());
            }
            if (post && "/api/ddns/run".equals(req.path)) {
                Ddns.Result r = Ddns.run(ctx, true);
                ServiceState.setDdns(r);
                Logs.add("DDNS 手动更新" + (r.ok ? "：" + r.message : "失败：" + r.message)
                        + (r.ip.length() > 0 ? "（" + r.ip + "）" : ""));
                return Http.Resp.json(200, r.toJson().toString());
            }
            if (post && "/api/rules/test".equals(req.path)) {
                return testRules(cfg, req);
            }
            if (post && "/api/test/event".equals(req.path)) {
                return testEvent(ctx, req);
            }
            if (post && "/api/alerts/test".equals(req.path)) {
                return testAlert(ctx, req);
            }
            if (post && "/api/update/check".equals(req.path)) {
                return Http.Resp.json(200, PanelUpdater.startCheck(ctx).toString());
            }
            if (post && "/api/update/install".equals(req.path)) {
                return Http.Resp.json(200, PanelUpdater.startInstall(ctx).toString());
            }
            if (post && "/api/files/upload".equals(req.path)) {
                return upload(ctx, cfg, req);
            }
            if (post && "/api/files/delete".equals(req.path)) {
                return fileDelete(ctx, req);
            }
            if (post && "/api/local-update/install".equals(req.path)) {
                return Http.Resp.json(200, LocalUpdater.startInstall(ctx).toString());
            }
            if (post && "/api/local-update/clear".equals(req.path)) {
                LocalUpdater.clear(ctx);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return Http.Resp.json(200, o.toString());
            }
            if (post && "/api/notes".equals(req.path)) {
                return noteAdd(ctx, cfg, req);
            }
            if (post && "/api/notes/delete".equals(req.path)) {
                return noteDelete(ctx, req);
            }
            if (post && "/api/notes/clear".equals(req.path)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("removed", Notes.clear(ctx));
                return Http.Resp.json(200, o.toString());
            }
            if (post && "/api/config".equals(req.path)) {
                return saveConfig(ctx, cfg, req);
            }
        } catch (IllegalArgumentException e) {
            return err(400, e.getMessage());
        } catch (JSONException e) {
            return err(400, "JSON 无效：" + e.getMessage());
        } catch (Exception e) {
            Logs.add("面板接口异常 " + req.path + "：" + e);
            return err(500, "内部错误");
        }

        return err(404, "not found");
    }

    // ---------- 接口实现 ----------

    private static Http.Resp send(Context ctx, AppConfig cfg, Http.Req req)
            throws Exception {
        JSONObject body = new JSONObject(req.body);
        String to = body.optString("to", "").trim();
        String text = body.optString("body", "");
        int subId = body.optInt("subId", -1);
        if (subId < 0) {
            try {
                JSONObject sms = cfg.json().optJSONObject("sms");
                subId = sms == null ? -1 : sms.optInt("defaultSubId", -1);
            } catch (Exception ignored) {
            }
        }
        try {
            long id = SmsSender.send(ctx, to, text, subId);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            return Http.Resp.json(200, o.toString());
        } catch (IllegalArgumentException e) {
            return err(400, e.getMessage());
        } catch (Exception e) {
            Logs.add("发送短信失败：" + e);
            return err(400, "发送失败：" + e.getMessage());
        }
    }

    private static Http.Resp retry(Context ctx, AppConfig cfg, Http.Req req)
            throws Exception {
        long id = new JSONObject(req.body).optLong("id", -1);
        if (id <= 0) {
            return err(400, "缺少消息 id");
        }
        Router.Result r = Router.routeAll(cfg.activeChannelNames());
        int n = Db.get(ctx).retryEvent(id, r.targets);
        RelayService.start(ctx);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("queued", n);
        return Http.Resp.json(200, o.toString());
    }

    private static Http.Resp testRules(AppConfig cfg, Http.Req req) throws Exception {
        JSONObject body = new JSONObject(req.body);
        String peer = body.optString("peer", "");
        String text = body.optString("body", "");
        Router.Result r = Router.preview(peer, text, cfg.rules(),
                cfg.activeChannelNames(), cfg.rulesDefault());
        JSONObject o = new JSONObject();
        o.put("drop", r.drop);
        o.put("rule", r.rule == null ? "" : r.rule);
        o.put("matched", new JSONArray(r.matched));
        JSONArray targets = new JSONArray();
        for (Router.Target t : r.targets) {
            targets.put(t.ch);
        }
        o.put("targets", targets);
        o.put("code", SmsEvent.extractCode(text));
        return Http.Resp.json(200, o.toString());
    }

    /**
     * 发送测试事件：构造一条假短信，走 SmsReceiver 的真实入库/规则/投递链路。
     * 正文附短时标，避免 90 秒去重窗口把连点两次合并成同一条。
     */
    private static Http.Resp testEvent(Context ctx, Http.Req req) throws Exception {
        JSONObject body = new JSONObject(req.body);
        String peer = body.optString("peer", "").trim();
        if (peer.length() == 0) {
            peer = "10086";
        }
        String text = body.optString("body", "");
        if (text.trim().length() == 0) {
            text = "【PhoneBox 测试】验证码 123456，用于检查转发通道。";
        }
        String stamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
        text += "\n[测试 #" + stamp + "]";

        SmsEvent e = new SmsEvent();
        e.peer = peer;
        e.body = text;
        e.ts = System.currentTimeMillis();
        e.code = SmsEvent.extractCode(text);
        e.subId = body.optInt("subId", -1);

        JSONObject o = SmsReceiver.ingest(ctx, e, true);
        Logs.add("面板发送测试事件：" + peer + "（" + text.length() + " 字）");
        // 统一 200，让面板能读到 warning（转发关闭 / 无通道）而不是只看到 HTTP 400。
        return Http.Resp.json(200, o.toString());
    }

    /** 手动触发一条测试告警，绕过阈值与冷却，用于验证通知与通道链路。 */
    private static Http.Resp testAlert(Context ctx, Http.Req req) throws Exception {
        String text = new JSONObject(req.body).optString("message", "").trim();
        return Http.Resp.json(200, Alert.fireTest(ctx, text).toString());
    }

    // ---------- 文件与便签 ----------

    private static Http.Resp upload(Context ctx, AppConfig cfg, Http.Req req) throws Exception {
        if (!cfg.transferEnabled()) {
            return err(403, "文件上传已在配置中关闭");
        }
        if (req.uploadFile == null) {
            return err(400, "缺少文件内容（Content-Type 需为 application/octet-stream）");
        }
        String name = req.q("name", "upload.bin");
        String kind = req.q("kind", "file");
        try {
            if ("apk".equals(kind)) {
                if (!cfg.transferAllowInstall()) {
                    return err(403, "手动安装已在配置中关闭");
                }
                return Http.Resp.json(200, LocalUpdater.stage(ctx, req.uploadFile, name).toString());
            }
            JSONObject r = WebFiles.save(ctx, req.uploadFile, name, kind,
                    cfg.transferMaxBytes(), cfg.transferMaxTotal(), cfg.transferKeepDays());
            JSONObject item = r.optJSONObject("item");
            Logs.add("面板上传文件：" + (item == null ? name : item.optString("name")));
            return Http.Resp.json(200, r.toString());
        } catch (SecurityException e) {
            return err(400, e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(400, e.getMessage());
        }
    }

    private static Http.Resp fileDownload(Context ctx, Http.Req req) throws Exception {
        String id = req.q("id", "");
        JSONObject item = WebFiles.find(ctx, id);
        if (item == null) {
            return err(404, "文件不存在");
        }
        InputStream in = WebFiles.open(ctx, item);
        if (in == null) {
            return err(404, "文件已被移动或删除");
        }
        String name = item.optString("name", "file");
        Http.Resp r = new Http.Resp();
        r.contentType = item.optString("mime", "application/octet-stream");
        r.stream = in;
        r.streamLength = item.optLong("size", 0);
        r.headers.put("Content-Disposition", "attachment; filename=\""
                + asciiName(name) + "\"; filename*=UTF-8''"
                + URLEncoder.encode(name, "UTF-8"));
        return r;
    }

    private static Http.Resp fileDelete(Context ctx, Http.Req req) throws Exception {
        String id = new JSONObject(req.body).optString("id", "");
        boolean removed = WebFiles.delete(ctx, id);
        JSONObject o = new JSONObject();
        o.put("ok", removed);
        if (!removed) {
            o.put("warning", "文件不存在或已删除");
        }
        return Http.Resp.json(200, o.toString());
    }

    private static Http.Resp noteAdd(Context ctx, AppConfig cfg, Http.Req req) throws Exception {
        JSONObject body = new JSONObject(req.body);
        return Http.Resp.json(200, Notes.add(ctx, body.optString("text", ""),
                body.optString("from", "pc"), cfg.notesMaxItems()).toString());
    }

    private static Http.Resp noteDelete(Context ctx, Http.Req req) throws Exception {
        String id = new JSONObject(req.body).optString("id", "");
        JSONObject o = new JSONObject();
        o.put("ok", Notes.delete(ctx, id));
        return Http.Resp.json(200, o.toString());
    }

    private static String asciiName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 100; i++) {
            char c = name.charAt(i);
            sb.append(c >= 32 && c < 127 && c != '"' && c != '\\' ? c : '_');
        }
        return sb.length() == 0 ? "file" : sb.toString();
    }

    private static Http.Resp saveConfig(Context ctx, AppConfig cfg, Http.Req req)
            throws Exception {
        JSONObject body = new JSONObject(req.body);
        int oldPort = cfg.webPort();
        cfg.update(body);
        Sims.clearCache();
        boolean portChanged = cfg.webPort() != oldPort;
        RelayService.start(ctx);
        Logs.add("配置已保存" + (portChanged ? "，面板端口变更，正在重启" : ""));
        if (portChanged) {
            restartAsync(ctx);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("restart", portChanged);
        return Http.Resp.json(200, o.toString());
    }

    private static Http.Resp err(int status, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg == null ? "" : msg);
        } catch (JSONException ignored) {
        }
        return Http.Resp.json(status, o.toString());
    }

    private static int intQ(Http.Req req, String name, int def, int min, int max) {
        try {
            int v = Integer.parseInt(req.q(name, String.valueOf(def)));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- 状态 ----------

    /** assets 下静态资源读取 + 进程内缓存（页面、manifest、图标共用）。 */
    private static byte[] asset(Context ctx, String path) throws IOException {
        synchronized (ASSETS) {
            if (ASSETS.containsKey(path)) {
                return ASSETS.get(path);
            }
        }
        InputStream in = ctx.getAssets().open(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        byte[] data = out.toByteArray();
        synchronized (ASSETS) {
            ASSETS.put(path, data);
        }
        return data;
    }

    private static Http.Resp staticAsset(Context ctx, String path, String type) {
        try {
            return Http.Resp.bytes(200, type, asset(ctx, path));
        } catch (IOException e) {
            return Http.Resp.text(404, "资源不存在");
        }
    }

    private static String status(Context ctx, AppConfig cfg) {
        try {
            Db db = Db.get(ctx);
            JSONObject o = new JSONObject();
            o.put("app", "phonebox");
            o.put("version", version(ctx));
            o.put("running", ServiceState.running);
            o.put("startedAt", ServiceState.startedAtWall);
            o.put("uptimeSec", ServiceState.uptimeSec());
            o.put("heartbeatAgeSec", ServiceState.heartbeatAgeSec());

            JSONObject panel = new JSONObject();
            panel.put("running", isRunning());
            panel.put("port", ServiceState.panelPort);
            panel.put("error", ServiceState.panelError == null ? "" : ServiceState.panelError);
            o.put("panel", panel);

            JSONObject device = new JSONObject();
            device.put("model", Build.MODEL);
            device.put("android", Build.VERSION.RELEASE);
            device.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", device);

            int port = ServiceState.panelPort > 0 ? ServiceState.panelPort : cfg.webPort();
            JSONArray addresses = new JSONArray();
            for (NetInfo.Addr a : NetInfo.addresses(port)) {
                JSONObject item = new JSONObject();
                item.put("iface", a.iface);
                item.put("ip", a.ip);
                item.put("url", a.url);
                addresses.put(item);
            }
            o.put("addresses", addresses);

            JSONObject sms = new JSONObject();
            sms.put("enabled", cfg.smsEnabled());
            sms.put("pending", db.pending());
            sms.put("delivered", db.delivered());
            sms.put("failed", db.failed());
            sms.put("events", db.events());
            sms.put("outbox", db.countOut());
            sms.put("lastError", db.lastError());
            sms.put("channels", new JSONArray(cfg.channelNames()));
            o.put("sms", sms);

            if (cfg.monitorEnabled()) {
                o.put("monitor", Monitor.snapshot(ctx));
            }

            o.put("watchdog", Watchdog.snapshot(ctx));
            o.put("alerts", Alert.snapshot(ctx));
            // 概览始终显示双卡号码，不依赖 monitor.enabled 开关。
            o.put("sims", Sims.list(ctx));

            JSONObject ddns = new JSONObject();
            ddns.put("enabled", cfg.ddnsEnabled());
            ddns.put("family", cfg.ddnsFamily());
            ddns.put("intervalMin", cfg.ddnsIntervalMin());
            ddns.put("records", cfg.ddnsRecords().size());
            ddns.put("ran", ServiceState.ddnsRan);
            ddns.put("ok", ServiceState.ddnsOk);
            ddns.put("ip", ServiceState.ddnsIp);
            ddns.put("message", ServiceState.ddnsMessage);
            ddns.put("warning", ServiceState.ddnsWarning);
            ddns.put("at", ServiceState.ddnsAt);
            ddns.put("updated", ServiceState.ddnsUpdated);
            ddns.put("unchanged", ServiceState.ddnsUnchanged);
            o.put("ddns", ddns);

            JSONObject config = new JSONObject();
            JSONObject web = new JSONObject();
            web.put("port", cfg.webPort());
            web.put("user", cfg.webUser());
            config.put("web", web);
            o.put("config", config);

            return o.toString();
        } catch (JSONException e) {
            return "{\"error\":\"status\"}";
        }
    }

    private static String version(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "0.0.0" : pi.versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }
}
