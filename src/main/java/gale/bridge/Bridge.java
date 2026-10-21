package gale.bridge;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gale.Gale;
import gale.module.Module;
import gale.module.impl.client.BridgeModule;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Talking with other clients' scripts. Two ways in, one way out:
 * <ul>
 * <li>in, by command: {@code /gale bridge set <key> <value>} and {@code /gale bridge b4 <module,module,...>}
 * (B4 scripts call these with {@code client.runForgeCommand("gale bridge ...")});</li>
 * <li>in, by HTTP: {@code GET http://127.0.0.1:<port>/gale/set?key=..&value=..};</li>
 * <li>out, by HTTP: {@code /gale} (all state as JSON), {@code /gale/get?key=..} (one published value).</li>
 * </ul>
 * The HTTP server only listens on 127.0.0.1. Values in are kept in {@link #inbox()}; values out are what Gale
 * {@link #publish}es. Modules can ask another client to switch one of its modules with {@link #request}; the GaleLink
 * script carries that out in B4.
 */
public final class Bridge {
    private static final Map<String, String> PUBLISHED = new ConcurrentHashMap<>();
    private static final Map<String, String> INBOX = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Boolean>> REQUESTS = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static int port;

    private Bridge() { }

    /** Makes a value readable by other clients' scripts. */
    public static void publish(String key, String value) {
        if (value == null) PUBLISHED.remove(key);
        else PUBLISHED.put(key, value);
    }

    /** A value another client sent in; null if none. */
    public static String get(String key) { return INBOX.get(key); }

    public static Map<String, String> inbox() { return new HashMap<>(INBOX); }

    public static Map<String, String> published() { return new HashMap<>(PUBLISHED); }

    /** Asks another client ("b4") to switch one of its modules on or off. Its script picks this up and clears it. */
    public static void request(String client, String module, boolean on) {
        Map<String, Boolean> r = REQUESTS.computeIfAbsent(client.toLowerCase(), k -> new LinkedHashMap<>());
        synchronized (r) { r.put(module, on); }
        publishRequests(client.toLowerCase());
    }

    private static void publishRequests(String client) {
        Map<String, Boolean> r = REQUESTS.get(client);
        StringBuilder sb = new StringBuilder();
        if (r != null) synchronized (r) {
            for (Map.Entry<String, Boolean> e : r.entrySet()) sb.append(sb.length() > 0 ? "," : "").append(e.getKey()).append(':').append(e.getValue() ? "on" : "off");
        }
        publish(client + ".request", sb.toString());
    }

    /** A value sent in by a script. A few keys mean something to Gale itself. */
    public static void receive(String key, String value) {
        if (key == null || key.isEmpty()) return;
        String v = value == null ? "" : value;
        INBOX.put(key, v);
        if (key.endsWith(".enabled")) {
            // "<client>.enabled" = the client's modules that are on, comma separated
            String client = key.substring(0, key.length() - ".enabled".length());
            External.report(client, v.isEmpty() ? new java.util.ArrayList<>() : Arrays.asList(v.split("\\s*,\\s*")));
        } else if (key.endsWith(".request.done")) {
            // the script carried the requests out
            String client = key.substring(0, key.length() - ".request.done".length()).toLowerCase();
            REQUESTS.remove(client);
            publishRequests(client);
        }
    }

    // ------------------------------------------------------------------ HTTP

    /** Starts or stops the HTTP side as the Bridge module's settings say. */
    public static synchronized void start() {
        BridgeModule m = Gale.modules().get(BridgeModule.class);
        boolean want = m != null && m.http.get();
        int wantPort = m == null ? 25595 : m.port.getInt();
        if (server != null && (!want || wantPort != port)) {
            server.stop(0);
            server = null;
        }
        if (!want || server != null) return;
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), wantPort), 0);
            s.createContext("/gale", Bridge::handle);
            s.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Gale bridge");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            port = wantPort;
            Gale.LOG.info("Bridge listening on 127.0.0.1:{}", port);
        } catch (Exception e) {
            Gale.LOG.error("Bridge could not listen on port {}", wantPort, e);
        }
    }

    public static boolean isListening() { return server != null; }

    public static int port() { return port; }

    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String body;
            String type = "text/plain; charset=utf-8";
            if (path.equals("/gale") || path.equals("/gale/")) {
                body = state().toString();
                type = "application/json; charset=utf-8";
            } else if (path.equals("/gale/get")) {
                String v = PUBLISHED.get(q.getOrDefault("key", ""));
                body = v == null ? "" : v;
            } else if (path.equals("/gale/set")) {
                receive(q.get("key"), q.get("value"));
                body = "ok";
            } else {
                body = "unknown";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            Gale.LOG.debug("Bridge request failed", e);
        } finally {
            ex.close();
        }
    }

    private static JsonObject state() {
        JsonObject root = new JsonObject();
        root.addProperty("version", Gale.VERSION);
        JsonObject modules = new JsonObject();
        for (Module m : Gale.modules().all()) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.isEnabled());
            o.addProperty("active", m.isEnabled() && !m.isDeferred());
            modules.add(m.getName(), o);
        }
        root.add("modules", modules);
        JsonObject values = new JsonObject();
        for (Map.Entry<String, String> e : PUBLISHED.entrySet()) values.addProperty(e.getKey(), e.getValue());
        root.add("values", values);
        return root;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            try {
                if (eq < 0) out.put(URLDecoder.decode(part, "UTF-8"), "");
                else out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
