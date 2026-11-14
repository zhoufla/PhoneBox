package com.zhoufla.phonebox;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP/1.1 服务器：ServerSocket + 每连接一线程，Connection: close。
 * 只服务内嵌面板，不支持 chunked / 100-continue / keep-alive。
 */
public final class Http {

    public interface Handler {
        Resp serve(Req req) throws Exception;
    }

    public static final class Req {
        public String method = "GET";
        public String path = "/";
        public String rawQuery = "";
        public String body = "";
        /** 对端 IP，仅用于登录失败限流，不写日志、不外传。 */
        public String remoteAddr = "";
        /** 二进制上传已落盘的临时文件；非 null 时 body 为空，用完后由 Http 兜底删除。 */
        public File uploadFile;
        public long uploadBytes = 0;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public final Map<String, String> query = new LinkedHashMap<String, String>();

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String q(String name, String def) {
            String v = query.get(name);
            return v == null ? def : v;
        }
    }

    public static final class Resp {
        public int status = 200;
        public String contentType = "application/json; charset=utf-8";
        public byte[] body = new byte[0];
        /** 流式响应体（大文件下载）；非 null 时忽略 body，streamLength 作为 Content-Length。 */
        public InputStream stream;
        public long streamLength = -1;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();

        public static Resp text(int status, String text) {
            Resp r = new Resp();
            r.status = status;
            r.contentType = "text/plain; charset=utf-8";
            r.body = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Resp json(int status, String json) {
            Resp r = new Resp();
            r.status = status;
            r.body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Resp bytes(int status, String contentType, byte[] body) {
            Resp r = new Resp();
            r.status = status;
            r.contentType = contentType;
            r.body = body == null ? new byte[0] : body;
            return r;
        }
    }

    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;
    private static final int UPLOAD_TIMEOUT_MS = 60000;

    private final int requestedPort;
    private final Handler handler;
    private final File uploadDir;

    private volatile boolean running = false;
    private volatile ServerSocket server;
    private Thread acceptThread;

    public Http(int port, Handler handler) {
        this(port, handler, null);
    }

    public Http(int port, Handler handler, File uploadDir) {
        this.requestedPort = port;
        this.handler = handler;
        this.uploadDir = uploadDir;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(requestedPort), 16);
        server = s;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "phonebox-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public int getPort() {
        ServerSocket s = server;
        return s == null ? requestedPort : s.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket s = server;
                if (s == null) {
                    break;
                }
                final Socket socket = s.accept();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "phonebox-http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    Logs.add("面板 accept 失败：" + e);
                }
            }
        }
    }

    private static boolean isUpload(Req req) {
        String type = req.header("content-type");
        return type != null
                && type.toLowerCase(Locale.ROOT).startsWith("application/octet-stream");
    }

    private void handle(Socket socket) {
        Req req = null;
        try {
            socket.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                write(out, Resp.text(400, "bad request"), false);
                return;
            }

            req = new Req();
            req.method = parts[0];
            if (socket.getInetAddress() != null) {
                req.remoteAddr = socket.getInetAddress().getHostAddress();
            }
            String target = parts[1];
            int q = target.indexOf('?');
            req.path = q >= 0 ? target.substring(0, q) : target;
            req.rawQuery = q >= 0 ? target.substring(q + 1) : "";
            parseQuery(req.rawQuery, req.query);

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                req.headers.put(key, value);
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            boolean upload = isUpload(req);
            if (upload) {
                socket.setSoTimeout(UPLOAD_TIMEOUT_MS);
            }

            if (contentLength > 0) {
                long limit = upload ? MAX_UPLOAD_BYTES : 1024L * 1024L;
                if (contentLength > limit) {
                    write(out, Resp.text(413, "body too large"), false);
                    return;
                }
                if (upload) {
                    if (uploadDir == null) {
                        write(out, Resp.text(503, "upload disabled"), false);
                        return;
                    }
                    if (!uploadDir.isDirectory() && !uploadDir.mkdirs()) {
                        write(out, Resp.text(500, "upload dir unavailable"), false);
                        return;
                    }
                    File tmp = File.createTempFile("phonebox-upload-", ".bin", uploadDir);
                    req.uploadFile = tmp;
                    FileOutputStream fos = new FileOutputStream(tmp);
                    long left = contentLength;
                    byte[] buffer = new byte[8192];
                    try {
                        while (left > 0) {
                            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (n < 0) {
                                break;
                            }
                            fos.write(buffer, 0, n);
                            left -= n;
                            req.uploadBytes += n;
                        }
                        fos.flush();
                        fos.getFD().sync();
                    } finally {
                        fos.close();
                    }
                    if (req.uploadBytes != contentLength) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        req.uploadFile = null;
                        write(out, Resp.text(400, "incomplete body"), false);
                        return;
                    }
                } else {
                    byte[] body = new byte[contentLength];
                    int off = 0;
                    while (off < contentLength) {
                        int n = in.read(body, off, contentLength - off);
                        if (n < 0) {
                            break;
                        }
                        off += n;
                    }
                    req.body = new String(body, 0, off, StandardCharsets.UTF_8);
                }
            }

            Resp resp;
            try {
                resp = handler.serve(req);
            } catch (Exception e) {
                Logs.add("面板请求异常 " + req.path + "：" + e);
                resp = Resp.json(500, "{\"error\":\"internal error\"}");
            }
            write(out, resp, "HEAD".equals(req.method));
        } catch (Exception e) {
            Logs.add("面板连接异常：" + e);
        } finally {
            if (req != null && req.uploadFile != null) {
                //noinspection ResultOfMethodCallIgnored
                req.uploadFile.delete();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void parseQuery(String raw, Map<String, String> out) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] pairs = raw.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                key = URLDecoder.decode(key, "UTF-8");
                value = URLDecoder.decode(value, "UTF-8");
            } catch (Exception ignored) {
            }
            if (!out.containsKey(key)) {
                out.put(key, value);
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Resp resp, boolean head) throws IOException {
        byte[] body = resp.body == null ? new byte[0] : resp.body;
        boolean streamed = resp.stream != null;
        long length = streamed ? resp.streamLength : body.length;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n");
        h.append("Content-Type: ").append(resp.contentType).append("\r\n");
        h.append("Content-Length: ").append(length < 0 ? 0 : length).append("\r\n");
        h.append("Connection: close\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        try {
            if (!head) {
                if (streamed) {
                    copy(resp.stream, out, length);
                } else {
                    out.write(body);
                }
            }
            out.flush();
        } finally {
            if (streamed) {
                try {
                    resp.stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long left = length;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                break;
            }
            out.write(buffer, 0, n);
            left -= n;
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 302:
                return "Found";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            default:
                return "Status";
        }
    }
}
