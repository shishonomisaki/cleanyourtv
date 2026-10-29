package io.github.shishonomisaki.parentlock;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone page. A small HTTP server that answers Tailscale addresses (100.64.0.0/10) only, so a phone on the
 * home Wi-Fi gets nothing; Tailscale's WireGuard tunnel carries it encrypted. Every change needs the PIN.
 */
final class WebServer {
    private static final int MAX_BODY = 4096;

    private final Context context;
    private final LockState state;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private volatile ServerSocket socket;
    private volatile boolean running;

    WebServer(Context context, LockState state) {
        this.context = context;
        this.state = state;
    }

    static boolean isTailnet(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40;
    }

    void start() {
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try (ServerSocket s = new ServerSocket(state.port())) {
                    socket = s;
                    while (running) {
                        Socket c = s.accept();
                        pool.execute(() -> handle(c));
                    }
                } catch (Exception e) {
                    // port busy right after a restart of the service, or rebind(): open again shortly
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "parentlock-web");
        t.setDaemon(true);
        t.start();
    }

    /** Close the socket; the loop opens it again on the port now stored. */
    void rebind() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    private void handle(Socket c) {
        try (Socket client = c) {
            client.setSoTimeout(5000);
            if (!isTailnet(client.getInetAddress())) {   // drop, say nothing
                android.util.Log.i("ParentLock", "dropped " + client.getInetAddress());
                return;
            }
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (length < 0 || length > MAX_BODY) return;
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            route(parts[0], parts[1], parse(new String(body, 0, read, StandardCharsets.UTF_8)),
                    client.getOutputStream());
        } catch (Exception e) {
            android.util.Log.i("ParentLock", "request failed: " + e);
        }
    }

    private void route(String method, String path, Map<String, String> form, OutputStream out) throws Exception {
        if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
            send(out, 200, "text/html; charset=utf-8", Page.HTML);
            return;
        }
        if (method.equals("GET") && path.equals("/api/status")) {
            sendJson(out, 200, status());
            return;
        }
        if (!method.equals("POST")) {
            send(out, 404, "text/plain", "not found");
            return;
        }
        // First visit: the parent picks the PIN on the phone, so it never passes through adb or a chat. Only
        // while none is set; changing it later goes through adb (AdminReceiver).
        if (path.equals("/api/setpin")) {
            String pin = form.get("pin");
            if (state.hasPin()) {
                sendJson(out, 403, status().put("error", "pin_set"));
            } else if (pin == null || !pin.matches("\\d{6}")) {
                sendJson(out, 400, status().put("error", "pin_format"));
            } else {
                state.setPin(pin);
                sendJson(out, 200, status());
            }
            return;
        }
        // Locking needs no PIN: the page asks for a second press instead, and locking early is never the risk.
        if (path.equals("/api/lock")) {
            state.lock();
            GuardService.refresh();
            sendJson(out, 200, status());
            return;
        }
        // Errors go out as codes (plus a number in "n"); the page words them in the phone's language.
        int[] n = new int[1];
        // +30 min: only on an open window, twice per unlock; the first needs no PIN, the second does.
        if (path.equals("/api/extend")) {
            if (state.locked()) {
                sendJson(out, 409, status().put("error", "not_open"));
                return;
            }
            if (state.extensions() >= LockState.MAX_EXTENSIONS) {
                sendJson(out, 409, status().put("error", "max_extend"));
                return;
            }
            if (state.extensions() > 0) {
                String err = state.checkPin(form.get("pin"), n);
                if (err != null) {
                    sendJson(out, 403, status().put("error", err).put("n", n[0]));
                    return;
                }
            }
            state.extend();
            GuardService.refresh();
            sendJson(out, 200, status());
            return;
        }
        String err = state.checkPin(form.get("pin"), n);
        if (err != null) {
            sendJson(out, 403, status().put("error", err).put("n", n[0]));
            return;
        }
        switch (path) {
            case "/api/unlock": {
                int minutes;
                try { minutes = Integer.parseInt(form.get("minutes")); } catch (Exception e) { minutes = 0; }
                if (!LockState.validMinutes(minutes)) {
                    sendJson(out, 400, status().put("error", "pick_time"));
                    return;
                }
                state.unlock(minutes);
                break;
            }
            case "/api/settings":
                // Settings open only on an unlocked TV; while locked the lock screen covers everything.
                if (state.locked()) {
                    sendJson(out, 409, status().put("error", "unlock_first"));
                    return;
                }
                state.openSettings(5);
                break;
            default:
                send(out, 404, "text/plain", "not found");
                return;
        }
        GuardService.refresh();
        sendJson(out, 200, status());
    }

    private JSONObject status() throws Exception {
        return new JSONObject()
                .put("locked", state.locked())
                .put("remainingSec", state.remainingMs() / 1000)
                .put("settingsOpen", state.settingsOpen())
                .put("settingsLeftSec", state.settingsLeftMs() / 1000)
                .put("hasPin", state.hasPin())
                .put("extensions", state.extensions())
                .put("maxExtensions", LockState.MAX_EXTENSIONS)
                .put("durationSec", state.durationMs() / 1000)
                .put("watchedTodaySec", state.watchedTodayMs() / 1000)
                .put("unlocksToday", state.unlocksToday())
                .put("showing", GuardService.nowShowing());
    }

    private static Map<String, String> parse(String body) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return map;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 8192) return null;
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static void sendJson(OutputStream out, int code, JSONObject json) throws Exception {
        send(out, code, "application/json; charset=utf-8", json.toString());
    }

    private static void send(OutputStream out, int code, String type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = String.format(Locale.ROOT,
                "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n",
                code, code == 200 ? "OK" : "Error", type, bytes.length);
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
