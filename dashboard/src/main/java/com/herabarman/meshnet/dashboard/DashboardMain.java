package com.herabarman.meshnet.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live dashboard for the simulator. Open http://127.0.0.1:8080 in a browser.
 * Usage: DashboardMain [--port 8080] [--host 127.0.0.1]
 * The host defaults to localhost only: the page can kill nodes, so do not expose it to a network you do not trust.
 */
public final class DashboardMain {
    public static void main(String[] args) throws IOException {
        int port = 8080;
        String host = "127.0.0.1";
        for (int i = 0; i + 1 < args.length; i += 2) {
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
            else if ("--host".equals(args[i])) host = args[i + 1];
        }

        final DashboardSim sim = new DashboardSim();
        final byte[] page = readResource("/index.html");

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                if (!"/".equals(ex.getRequestURI().getPath()) && !"/index.html".equals(ex.getRequestURI().getPath())) {
                    reply(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                reply(ex, 200, "text/html; charset=utf-8", page);
            }
        });
        api(server, "/api/state", new Api() {
            public String run(Map<String, String> q) {
                return sim.state(num(q, "since", 0), (int) num(q, "a", -1), (int) num(q, "b", -1));
            }
        });
        api(server, "/api/send", new Api() {
            public String run(Map<String, String> q) {
                return sim.send(q.containsKey("mode") ? q.get("mode") : "remote", (int) num(q, "src", -1), (int) num(q, "dst", -1));
            }
        });
        api(server, "/api/kill", new Api() {
            public String run(Map<String, String> q) { sim.kill((int) num(q, "id", -1)); return "{\"ok\":true}"; }
        });
        api(server, "/api/revive", new Api() {
            public String run(Map<String, String> q) { sim.revive((int) num(q, "id", -1)); return "{\"ok\":true}"; }
        });
        api(server, "/api/killgw", new Api() {
            public String run(Map<String, String> q) { sim.killGateways((int) num(q, "zone", -1)); return "{\"ok\":true}"; }
        });
        api(server, "/api/config", new Api() {
            public String run(Map<String, String> q) { sim.configure(q); return "{\"ok\":true}"; }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    sim.tick(50);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        System.out.println("MeshNet dashboard: http://" + host + ":" + port + "  (Ctrl+C to stop)");
    }

    private interface Api {
        String run(Map<String, String> query);
    }

    private static void api(HttpServer server, String path, final Api api) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    String json = api.run(query(ex.getRequestURI().getRawQuery()));
                    reply(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    reply(ex, 500, "application/json; charset=utf-8", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
    }

    private static long num(Map<String, String> q, String k, long def) {
        try { return q.containsKey(k) ? Long.parseLong(q.get(k)) : def; } catch (NumberFormatException e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(pair, "UTF-8"), "");
                else out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    private static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = DashboardMain.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
