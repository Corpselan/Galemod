package gale.devtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gale.spotify.Json;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Spotify on 127.0.0.1 (for the tests, not in the mod jar): its login (authorize, token), and the Web
 * API a player uses, over six made-up songs, two playlists and two devices. Switches make it answer like Spotify does
 * when the account is not Premium, when no device is active, when the access token ran out, or when asked too fast.
 */
public final class FakeSpotify {
    static final class Song {
        final String id, name, artist, album;
        final long duration;
        final Color color;

        Song(String id, String name, String artist, String album, long duration, Color color) {
            this.id = id;
            this.name = name;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.color = color;
        }
    }

    final List<Song> songs = Arrays.asList(
        new Song("t1", "Midnight Drive", "Neon Coast", "Afterglow", 213000, new Color(0xE0457B)),
        new Song("t2", "Glass Garden", "Aurora Lane", "Bloom", 187000, new Color(0x3FA7D6)),
        new Song("t3", "Paper Planes Over Tokyo", "Kites & Comets", "Departures", 245000, new Color(0xF2A541)),
        new Song("t4", "Low Tide", "Harbor Lights", "Shoreline", 198000, new Color(0x59CD90)),
        new Song("t5", "晴天", "周杰伦", "叶惠美", 269000, new Color(0x7E57C2)),
        new Song("t6", "夜曲", "周杰伦", "十一月的萧邦", 226000, new Color(0x37474F)));
    final Map<String, List<String>> playlists = new HashMap<>();
    final List<String> liked = Arrays.asList("t2", "t5");

    private HttpServer server;
    public int port;
    // the player
    public volatile int current;
    public volatile boolean playing = true, shuffle;
    public volatile String repeat = "off", context = "spotify:playlist:p1";
    public volatile int volume = 60;
    public volatile long progressAt, startedAt = System.currentTimeMillis();
    public volatile String activeDevice = "d1";
    public final List<String> queue = new ArrayList<>();
    // switches
    public volatile boolean free, expireTokens, rateLimitOnce;
    // what happened
    public final AtomicInteger tokenGrants = new AtomicInteger(), refreshGrants = new AtomicInteger(), unauthorized = new AtomicInteger();
    public final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    public volatile String lastClientId, lastVerifierSeen, lastPlayBody;
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public FakeSpotify() {
        playlists.put("p1", Arrays.asList("t1", "t2", "t3", "t4"));
        playlists.put("p2", Arrays.asList("t5", "t6"));
    }

    public String base() { return "http://127.0.0.1:" + port; }

    /** Starts it and points the Spotify client at it (system properties). */
    public FakeSpotify start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        System.setProperty("gale.spotify.accounts", base());
        System.setProperty("gale.spotify.api", base() + "/v1");
        return this;
    }

    public void stop() { if (server != null) server.stop(0); }

    public int calls(String key) { AtomicInteger n = calls.get(key); return n == null ? 0 : n.get(); }

    public Song song() { return songs.get(current); }

    long progress() {
        long p = progressAt + (playing ? System.currentTimeMillis() - startedAt : 0);
        return Math.min(p, song().duration);
    }

    private void setProgress(long ms) {
        progressAt = ms;
        startedAt = System.currentTimeMillis();
    }

    /** As a browser would: open the login page, Spotify sends it to the redirect URI with a code. */
    public void followLogin(String authorizeUrl) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(authorizeUrl).openConnection();
        c.setInstanceFollowRedirects(true);
        c.getResponseCode();
        c.disconnect();
    }

    // ------------------------------------------------------------------ requests

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String body = read(ex.getRequestBody());
        String key = method + " " + path;
        calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        try {
            if (path.equals("/authorize")) {
                authorize(ex, q);
                return;
            }
            if (path.equals("/api/token")) {
                token(ex, query(body));
                return;
            }
            if (path.startsWith("/img/")) {
                image(ex, path.substring(5).replace(".png", ""));
                return;
            }
            if (!path.startsWith("/v1/")) {
                send(ex, 404, error(404, "", "no such page"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = auth == null ? "" : auth.replace("Bearer ", "");
            Boolean valid = tokens.get(token);
            if (valid == null || !valid || expireTokens) {
                if (expireTokens) {
                    tokens.put(token, false);
                    expireTokens = false;
                }
                unauthorized.incrementAndGet();
                send(ex, 401, error(401, "", "The access token expired"));
                return;
            }
            if (rateLimitOnce) {
                rateLimitOnce = false;
                ex.getResponseHeaders().add("Retry-After", "1");
                send(ex, 429, error(429, "", "API rate limit exceeded"));
                return;
            }
            api(ex, method, path.substring(3), q, body);
        } catch (RuntimeException e) {
            send(ex, 500, error(500, "", e.toString()));
        }
    }

    private void authorize(HttpExchange ex, Map<String, String> q) throws IOException {
        lastClientId = q.get("client_id");
        if (!"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method")) || q.get("code_challenge") == null) {
            send(ex, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        String code = "code-" + counter.incrementAndGet();
        codes.put(code, q.get("code_challenge"));
        ex.getResponseHeaders().add("Location", q.get("redirect_uri") + "?code=" + code + "&state=" + enc(q.get("state")));
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void token(HttpExchange ex, Map<String, String> f) throws IOException {
        String grant = f.get("grant_type");
        if ("authorization_code".equals(grant)) {
            String challenge = codes.remove(String.valueOf(f.get("code")));
            lastVerifierSeen = f.get("code_verifier");
            if (challenge == null || f.get("code_verifier") == null || !challenge.equals(s256(f.get("code_verifier")))) {
                send(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad code or verifier\"}");
                return;
            }
            tokenGrants.incrementAndGet();
        } else if ("refresh_token".equals(grant)) {
            if (f.get("refresh_token") == null || !f.get("refresh_token").startsWith("refresh-")) {
                send(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}");
                return;
            }
            refreshGrants.incrementAndGet();
        } else {
            send(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        String access = "access-" + counter.incrementAndGet();
        tokens.put(access, true);
        JsonObject o = new JsonObject();
        o.addProperty("access_token", access);
        o.addProperty("token_type", "Bearer");
        o.addProperty("scope", "user-read-playback-state user-modify-playback-state");
        o.addProperty("expires_in", 3600);
        o.addProperty("refresh_token", "refresh-" + counter.incrementAndGet());
        send(ex, 200, o.toString());
    }

    private boolean playerCommand(String path) { return path.startsWith("/me/player") && !path.equals("/me/player/devices") && !path.equals("/me/player/queue"); }

    private void api(HttpExchange ex, String method, String path, Map<String, String> q, String body) throws IOException {
        // what Spotify says to free accounts and with nothing to play on
        if (playerCommand(path) && !method.equals("GET") && free) {
            send(ex, 403, error(403, "PREMIUM_REQUIRED", "Player command failed: Premium required"));
            return;
        }
        if (playerCommand(path) && !method.equals("GET") && activeDevice == null && !(method.equals("PUT") && path.equals("/me/player"))) {
            send(ex, 404, error(404, "NO_ACTIVE_DEVICE", "Player command failed: No active device found"));
            return;
        }
        switch (method + " " + path) {
            case "GET /me": {
                JsonObject o = new JsonObject();
                o.addProperty("id", "galetester");
                o.addProperty("display_name", "Gale Tester");
                o.addProperty("product", free ? "free" : "premium");
                send(ex, 200, o.toString());
                return;
            }
            case "GET /me/player":
                if (activeDevice == null) {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }
                send(ex, 200, playerJson().toString());
                return;
            case "PUT /me/player/play": {
                lastPlayBody = body;
                JsonObject b = Json.parse(body);
                if (b != null && b.has("context_uri")) {
                    context = Json.str(b, "context_uri");
                    String list = context.substring(context.lastIndexOf(':') + 1);
                    String uri = Json.str(Json.obj(b, "offset"), "uri");
                    current = index(uri != null ? uri.substring(uri.lastIndexOf(':') + 1) : playlists.getOrDefault(list, Arrays.asList("t1")).get(0));
                    setProgress(0);
                } else if (b != null && b.has("uris")) {
                    int at = (int) Json.num(Json.obj(b, "offset"), "position", 0);
                    String uri = Json.arr(b, "uris").get(at).getAsString();
                    current = index(uri.substring(uri.lastIndexOf(':') + 1));
                    context = null;
                    setProgress(0);
                }
                setProgress(progress());
                playing = true;
                break;
            }
            case "PUT /me/player/pause":
                setProgress(progress());
                playing = false;
                break;
            case "POST /me/player/next":
                current = (current + 1) % songs.size();
                setProgress(0);
                break;
            case "POST /me/player/previous":
                current = (current + songs.size() - 1) % songs.size();
                setProgress(0);
                break;
            case "PUT /me/player/seek":
                setProgress(Long.parseLong(q.get("position_ms")));
                break;
            case "PUT /me/player/volume":
                volume = Integer.parseInt(q.get("volume_percent"));
                break;
            case "PUT /me/player/shuffle":
                shuffle = Boolean.parseBoolean(q.get("state"));
                break;
            case "PUT /me/player/repeat":
                repeat = q.get("state");
                break;
            case "PUT /me/player": {
                JsonObject b = Json.parse(body);
                activeDevice = Json.arr(b, "device_ids").get(0).getAsString();
                if (Json.bool(b, "play", false)) playing = true;
                break;
            }
            case "POST /me/player/queue":
                queue.add(q.get("uri"));
                break;
            case "GET /me/player/queue": {
                JsonObject o = new JsonObject();
                o.add("currently_playing", track(song()));
                JsonArray a = new JsonArray();
                for (int i = 1; i <= 3; i++) a.add(track(songs.get((current + i) % songs.size())));
                o.add("queue", a);
                send(ex, 200, o.toString());
                return;
            }
            case "GET /me/player/devices": {
                JsonObject o = new JsonObject();
                JsonArray a = new JsonArray();
                a.add(device("d1", "LLUOQ-PC", "Computer"));
                a.add(device("d2", "iPhone", "Smartphone"));
                o.add("devices", a);
                send(ex, 200, o.toString());
                return;
            }
            case "GET /search": {
                String want = String.valueOf(q.get("q")).toLowerCase();
                JsonArray items = new JsonArray();
                for (Song s : songs) if ((s.name + " " + s.artist + " " + s.album).toLowerCase().contains(want)) items.add(track(s));
                JsonObject tracks = new JsonObject();
                tracks.add("items", items);
                JsonObject o = new JsonObject();
                o.add("tracks", tracks);
                send(ex, 200, o.toString());
                return;
            }
            case "GET /me/playlists": {
                JsonArray items = new JsonArray();
                items.add(playlist("p1", "Gale Mix", 0));
                items.add(playlist("p2", "华语精选", 4));
                JsonObject o = new JsonObject();
                o.add("items", items);
                send(ex, 200, o.toString());
                return;
            }
            case "GET /me/tracks": {
                JsonObject o = new JsonObject();
                o.add("items", wrapped(liked));
                send(ex, 200, o.toString());
                return;
            }
            default:
                if (method.equals("GET") && path.startsWith("/playlists/") && path.endsWith("/tracks")) {
                    String id = path.substring("/playlists/".length(), path.length() - "/tracks".length());
                    List<String> ids = playlists.get(id);
                    if (ids == null) {
                        send(ex, 404, error(404, "", "Not found"));
                        return;
                    }
                    JsonObject o = new JsonObject();
                    o.add("items", wrapped(ids));
                    send(ex, 200, o.toString());
                    return;
                }
                send(ex, 404, error(404, "", "Service not found"));
                return;
        }
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private int index(String id) {
        for (int i = 0; i < songs.size(); i++) if (songs.get(i).id.equals(id)) return i;
        return 0;
    }

    private JsonObject playerJson() {
        JsonObject o = new JsonObject();
        o.add("device", device(activeDevice, activeDevice.equals("d1") ? "LLUOQ-PC" : "iPhone", activeDevice.equals("d1") ? "Computer" : "Smartphone"));
        o.addProperty("shuffle_state", shuffle);
        o.addProperty("repeat_state", repeat);
        o.addProperty("timestamp", System.currentTimeMillis());
        o.addProperty("progress_ms", progress());
        o.addProperty("is_playing", playing);
        o.addProperty("currently_playing_type", "track");
        if (context != null) {
            JsonObject c = new JsonObject();
            c.addProperty("uri", context);
            o.add("context", c);
        }
        o.add("item", track(song()));
        return o;
    }

    private JsonObject device(String id, String name, String type) {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("is_active", id.equals(activeDevice));
        d.addProperty("name", name);
        d.addProperty("type", type);
        d.addProperty("volume_percent", id.equals(activeDevice) ? volume : 50);
        return d;
    }

    private JsonObject track(Song s) {
        JsonObject t = new JsonObject();
        t.addProperty("id", s.id);
        t.addProperty("uri", "spotify:track:" + s.id);
        t.addProperty("name", s.name);
        t.addProperty("type", "track");
        t.addProperty("duration_ms", s.duration);
        JsonArray artists = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("name", s.artist);
        artists.add(a);
        t.add("artists", artists);
        JsonObject album = new JsonObject();
        album.addProperty("name", s.album);
        album.add("images", images(s.id));
        t.add("album", album);
        return t;
    }

    private JsonArray images(String id) {
        JsonArray a = new JsonArray();
        for (int size : new int[]{ 640, 300, 64 }) {
            JsonObject i = new JsonObject();
            i.addProperty("url", base() + "/img/" + id + "-" + size + ".png");
            i.addProperty("width", size);
            i.addProperty("height", size);
            a.add(i);
        }
        return a;
    }

    private JsonObject playlist(String id, String name, int cover) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("uri", "spotify:playlist:" + id);
        p.addProperty("name", name);
        JsonObject owner = new JsonObject();
        owner.addProperty("display_name", "Gale Tester");
        p.add("owner", owner);
        p.add("images", images(songs.get(cover).id));
        JsonObject tracks = new JsonObject();
        tracks.addProperty("total", playlists.get(id).size());
        p.add("tracks", tracks);
        return p;
    }

    private JsonArray wrapped(List<String> ids) {
        JsonArray items = new JsonArray();
        for (String id : ids) {
            JsonObject w = new JsonObject();
            w.add("track", track(songs.get(index(id))));
            items.add(w);
        }
        return items;
    }

    // a cover: the song's colour, its number in the middle
    private void image(HttpExchange ex, String name) throws IOException {
        String id = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
        int size = name.contains("-") ? Integer.parseInt(name.substring(name.indexOf('-') + 1)) : 64;
        Song s = songs.get(index(id));
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new java.awt.GradientPaint(0, 0, s.color, size, size, s.color.darker().darker()));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(new Font("SansSerif", Font.BOLD, size / 2));
        String n = id.substring(1);
        g.drawString(n, (size - g.getFontMetrics().stringWidth(n)) / 2, size / 2 + g.getFontMetrics().getAscent() / 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] data = out.toByteArray();
        ex.getResponseHeaders().add("Content-Type", "image/png");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String error(int status, String reason, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("status", status);
        e.addProperty("message", message);
        if (!reason.isEmpty()) e.addProperty("reason", reason);
        JsonObject o = new JsonObject();
        o.add("error", e);
        return o.toString();
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String s256(String verifier) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unused")
    private static JsonPrimitive str(String s) { return new JsonPrimitive(s); }
}
