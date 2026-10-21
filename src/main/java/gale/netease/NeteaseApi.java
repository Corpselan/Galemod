package gale.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gale.netease.model.NeteasePlaylist;
import gale.netease.model.NeteaseTrack;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NetEase Cloud Music's web API (the endpoints its website and PC client use; the same ones DangoMusic uses). Reading
 * (search, playlists, charts, lyrics, song addresses) works without an account; your own playlists and VIP songs need
 * the login cookie ({@link NeteaseLogin}), kept in galemod/netease.json. Blocking calls: run them off the game thread.
 */
public final class NeteaseApi {
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String CLIENT = "os=pc; appver=2.10.13";
    /** where the API lives (a local stand-in in the tests) */
    public static volatile String base = "https://music.163.com";
    /** the login cookie's file (set by the module; the tests use their own) */
    public static volatile File cookieFile;
    public static volatile String nickname = "", avatarUrl = "";
    public static volatile long userId;
    /** why the last search found nothing ("" when it worked) */
    public static volatile String lastSearchError = "";
    private static volatile String cookieCache;

    private NeteaseApi() { }

    // ------------------------------------------------------------------ login cookie

    public static synchronized String cookie() {
        if (cookieCache != null) return cookieCache;
        String c = "";
        File f = cookieFile;
        try {
            if (f != null && f.isFile()) {
                JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (e.isJsonObject() && e.getAsJsonObject().has("cookie")) c = e.getAsJsonObject().get("cookie").getAsString().trim();
            }
        } catch (Exception ignored) {
        }
        // only MUSIC_U pasted by hand
        if (!c.isEmpty() && !c.contains("=")) c = "MUSIC_U=" + c;
        cookieCache = c;
        return c;
    }

    public static boolean loggedIn() { return cookie().contains("MUSIC_U="); }

    public static synchronized void saveCookie(String cookie) {
        cookieCache = cookie == null ? "" : cookie.trim();
        File f = cookieFile;
        if (f != null) {
            try {
                f.getParentFile().mkdirs();
                JsonObject o = new JsonObject();
                o.addProperty("cookie", cookieCache);
                Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        userId = 0;
        nickname = "";
        avatarUrl = "";
    }

    public static void logout() { saveCookie(""); }

    private static String cookieHeader() {
        String c = cookie();
        return c.isEmpty() ? CLIENT : c + "; " + CLIENT;
    }

    // ------------------------------------------------------------------ account and lists

    /** Who is logged in (asked once); 0 when no one is. */
    public static long user() {
        if (userId != 0 || !loggedIn()) return userId;
        try {
            JsonObject o = getJson(base + "/api/nuser/account/get");
            JsonObject profile = obj(o, "profile"), account = obj(o, "account");
            long id = profile != null && profile.has("userId") ? profile.get("userId").getAsLong() : account != null && account.has("id") ? account.get("id").getAsLong() : 0;
            if (id != 0) {
                nickname = str(profile, "nickname");
                avatarUrl = str(profile, "avatarUrl");
                userId = id;
            }
        } catch (Exception ignored) {
        }
        return userId;
    }

    public static List<NeteasePlaylist> userPlaylists() throws IOException {
        long uid = user();
        if (uid == 0) return new ArrayList<>();
        JsonObject o = getJson(base + "/api/user/playlist/?uid=" + uid + "&limit=1000&offset=0");
        List<NeteasePlaylist> out = new ArrayList<>();
        for (JsonElement e : arr(o, "playlist")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            if (!p.has("id")) continue;
            JsonObject creator = obj(p, "creator");
            boolean owned = creator != null && creator.has("userId") && creator.get("userId").getAsLong() == uid;
            out.add(new NeteasePlaylist(p.get("id").getAsLong(), str(p, "name"), num(p, "trackCount"), str(p, "coverImgUrl"), str(creator, "nickname"), owned));
        }
        return out;
    }

    /** All of a playlist's songs (the first ones come with it; the rest are looked up 200 at a time). */
    public static List<NeteaseTrack> playlistTracks(long id) throws IOException {
        JsonObject root = getJson(base + "/api/v6/playlist/detail?id=" + id + "&n=1000&s=0");
        JsonObject pl = obj(root, "playlist");
        if (pl == null) return new ArrayList<>();
        Map<Long, Integer> levels = playLevels(root);
        Map<Long, NeteaseTrack> known = new LinkedHashMap<>();
        for (JsonElement e : arr(pl, "tracks")) {
            NeteaseTrack t = e.isJsonObject() ? NeteaseTrack.parse(e.getAsJsonObject()) : null;
            if (t != null) known.put(t.id, levels.containsKey(t.id) ? t.withPlayable(levels.get(t.id)) : t);
        }
        List<Long> ids = new ArrayList<>();
        JsonArray trackIds = arr(pl, "trackIds");
        if (trackIds.size() > 0) {
            for (JsonElement e : trackIds) if (e.isJsonObject() && e.getAsJsonObject().has("id")) ids.add(e.getAsJsonObject().get("id").getAsLong());
        } else {
            ids.addAll(known.keySet());
        }
        List<Long> missing = new ArrayList<>();
        for (long i : ids) if (!known.containsKey(i)) missing.add(i);
        for (int from = 0; from < missing.size(); from += 200) {
            try {
                for (NeteaseTrack t : details(missing.subList(from, Math.min(missing.size(), from + 200)))) known.put(t.id, t);
            } catch (IOException ignored) {
            }
        }
        List<NeteaseTrack> out = new ArrayList<>();
        for (long i : ids) if (known.containsKey(i)) out.add(known.get(i));
        return out;
    }

    public static List<NeteaseTrack> details(List<Long> ids) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) sb.append(i == 0 ? "" : ",").append(ids.get(i));
        JsonObject o = getJson(base + "/api/song/detail?ids=" + enc(sb.append(']').toString()));
        Map<Long, Integer> levels = playLevels(o);
        List<NeteaseTrack> out = new ArrayList<>();
        for (JsonElement e : arr(o, "songs")) {
            NeteaseTrack t = e.isJsonObject() ? NeteaseTrack.parse(e.getAsJsonObject()) : null;
            if (t != null) out.add(levels.containsKey(t.id) ? t.withPlayable(levels.get(t.id)) : t);
        }
        return out;
    }

    /** id → play level, from a "privileges" list beside the songs. */
    private static Map<Long, Integer> playLevels(JsonObject o) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        for (JsonElement e : arr(o, "privileges")) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
            int level = NeteaseTrack.playLevel(e.getAsJsonObject());
            if (level >= 0) out.put(e.getAsJsonObject().get("id").getAsLong(), level);
        }
        return out;
    }

    /** 推荐歌单 */
    public static List<NeteasePlaylist> recommendPlaylists() throws IOException {
        JsonObject o = getJson(base + "/api/personalized/playlist?limit=30");
        List<NeteasePlaylist> out = new ArrayList<>();
        for (JsonElement e : arr(o, "result")) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
            JsonObject p = e.getAsJsonObject();
            out.add(new NeteasePlaylist(p.get("id").getAsLong(), str(p, "name"), num(p, "trackCount"), str(p, "picUrl"), "", false));
        }
        return out;
    }

    /** 排行榜 */
    public static List<NeteasePlaylist> toplists() throws IOException {
        JsonObject o = getJson(base + "/api/toplist");
        List<NeteasePlaylist> out = new ArrayList<>();
        for (JsonElement e : arr(o, "list")) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
            JsonObject p = e.getAsJsonObject();
            out.add(new NeteasePlaylist(p.get("id").getAsLong(), str(p, "name"), num(p, "trackCount"), str(p, "coverImgUrl"), str(p, "updateFrequency"), false));
        }
        return out;
    }

    /**
     * Songs by name, singer or album. Three ways in turn (each is refused now and then): the PC client's search, the
     * website's encrypted one, then the old one with the covers looked up separately.
     */
    public static List<NeteaseTrack> search(String keyword, int limit) {
        String q = keyword.trim();
        if (q.isEmpty()) return new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            JsonObject o = getJson(base + "/api/cloudsearch/pc?s=" + enc(q) + "&type=1&offset=0&limit=" + limit);
            List<NeteaseTrack> songs = songsOf(o);
            if (songs != null && !songs.isEmpty()) return done(songs);
            if (songs == null) errors.add("cloudsearch " + codeOf(o));
        } catch (Exception e) {
            errors.add("cloudsearch " + e.getClass().getSimpleName());
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("s", q);
            body.addProperty("type", "1");
            body.addProperty("limit", String.valueOf(limit));
            body.addProperty("offset", "0");
            body.addProperty("total", "true");
            body.addProperty("csrf_token", "");
            Weapi r = weapi(base + "/weapi/cloudsearch/get/web", body.toString(), cookieHeader());
            JsonObject o = r == null ? null : parseObject(r.body);
            List<NeteaseTrack> songs = songsOf(o);
            if (songs != null && !songs.isEmpty()) return done(songs);
            if (songs == null) errors.add("weapi " + codeOf(o));
        } catch (Exception e) {
            errors.add("weapi " + e.getClass().getSimpleName());
        }
        try {
            JsonObject o = getJson(base + "/api/search/get/web?s=" + enc(q) + "&type=1&offset=0&limit=" + limit);
            List<NeteaseTrack> basic = songsOf(o);
            if (basic == null) {
                errors.add("web " + codeOf(o));
            } else if (!basic.isEmpty()) {
                Map<Long, NeteaseTrack> detailed = new LinkedHashMap<>();
                try {
                    List<Long> ids = new ArrayList<>();
                    for (NeteaseTrack t : basic) ids.add(t.id);
                    for (NeteaseTrack t : details(ids)) detailed.put(t.id, t);
                } catch (Exception ignored) {
                }
                List<NeteaseTrack> out = new ArrayList<>();
                for (NeteaseTrack t : basic) {
                    NeteaseTrack d = detailed.get(t.id);
                    out.add(d != null && !d.cover.isEmpty() ? d : t);
                }
                return done(out);
            }
        } catch (Exception e) {
            errors.add("web " + e.getClass().getSimpleName());
        }
        lastSearchError = String.join(", ", errors);
        return new ArrayList<>();
    }

    private static List<NeteaseTrack> done(List<NeteaseTrack> songs) {
        lastSearchError = "";
        return songs;
    }

    private static List<NeteaseTrack> songsOf(JsonObject o) {
        JsonObject result = obj(o, "result");
        if (result == null) return null;
        List<NeteaseTrack> out = new ArrayList<>();
        for (JsonElement e : arr(result, "songs")) {
            NeteaseTrack t = e.isJsonObject() ? NeteaseTrack.parse(e.getAsJsonObject()) : null;
            if (t != null) out.add(t);
        }
        return out;
    }

    private static String codeOf(JsonObject o) {
        return o == null || !o.has("code") || o.get("code").isJsonNull() ? "no response" : o.get("code").getAsString();
    }

    /** Where to download a song: the API's address (bitrate br) when it gives an MP3, else the public outer link. */
    public static SongUrl songUrl(long id, int br) {
        try {
            JsonObject o = getJson(base + "/api/song/enhance/player/url?ids=" + enc("[" + id + "]") + "&br=" + br);
            JsonArray data = arr(o, "data");
            JsonObject d = data.size() > 0 && data.get(0).isJsonObject() ? data.get(0).getAsJsonObject() : null;
            String url = d == null || !d.has("url") || d.get("url").isJsonNull() ? null : d.get("url").getAsString();
            String type = d == null || !d.has("type") || d.get("type").isJsonNull() ? "" : d.get("type").getAsString().toLowerCase();
            boolean trial = d != null && d.has("freeTrialInfo") && !d.get("freeTrialInfo").isJsonNull();
            if (url != null && !url.isEmpty() && (type.isEmpty() || type.equals("mp3"))) return new SongUrl(url, trial);
        } catch (Exception ignored) {
        }
        return new SongUrl(base + "/song/media/outer/url?id=" + id + ".mp3", false);
    }

    public static final class SongUrl {
        public final String url;
        /** only a 30-second sample (a VIP song without VIP) */
        public final boolean trialOnly;

        SongUrl(String url, boolean trialOnly) {
            this.url = url;
            this.trialOnly = trialOnly;
        }
    }

    /** The lyric texts of a song: [lrc, yrc] (either may be null). Tries the new endpoint, then the old one. */
    public static String[] lyricTexts(long id) {
        String lrc = null, yrc = null;
        List<String> urls = Arrays.asList(base + "/api/song/lyric/v1?id=" + id + "&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0",
            base + "/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1&yv=-1");
        for (String url : urls) {
            if (lrc != null && yrc != null) break;
            try {
                JsonObject o = getJson(url);
                if (o == null) continue;
                if (lrc == null) lrc = lyricField(o, "lrc");
                if (yrc == null) yrc = lyricField(o, "yrc");
            } catch (Exception ignored) {
            }
        }
        return new String[]{ lrc, yrc };
    }

    private static String lyricField(JsonObject o, String key) {
        JsonObject f = obj(o, key);
        if (f == null || !f.has("lyric") || f.get("lyric").isJsonNull()) return null;
        String s = f.get("lyric").getAsString();
        return s.trim().isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ HTTP

    public static JsonObject getJson(String url) throws IOException {
        byte[] b = request(url, "application/json", 8 << 20);
        return b == null ? null : parseObject(new String(b, StandardCharsets.UTF_8));
    }

    private static JsonObject parseObject(String s) {
        try {
            JsonElement e = new JsonParser().parse(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean neteaseHost(URL u) {
        String h = u.getHost();
        try {
            return h.endsWith("music.163.com") || h.equals(new URL(base).getHost());
        } catch (Exception e) {
            return h.endsWith("music.163.com");
        }
    }

    /**
     * GET, following up to six redirects by hand (the song addresses hop between hosts); the cookie and referer only
     * go to NetEase's own site. Null on an error status or if the answer is larger than maxBytes.
     */
    public static byte[] request(String url, String accept, int maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", accept);
                if (neteaseHost(u)) {
                    c.setRequestProperty("Referer", "https://music.163.com/");
                    c.setRequestProperty("Cookie", cookieHeader());
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    current = new URL(u, loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        if (out.size() > maxBytes) return null;
                    }
                    return out.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ weapi (the website's encrypted requests)

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud", IV = "0102030405060708", PUB_EXP = "010001";
    private static final String MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf69528"
        + "0104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RNG = new SecureRandom();

    /** A weapi answer: the body and the cookies it set. */
    public static final class Weapi {
        public final String body;
        public final List<String> setCookies;

        Weapi(String body, List<String> setCookies) {
            this.body = body;
            this.setCookies = setCookies;
        }
    }

    public static String aes(String text, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static String rsa(String secKey) {
        byte[] reversed = new StringBuilder(secKey).reverse().toString().getBytes(StandardCharsets.UTF_8);
        String hex = new BigInteger(1, reversed).modPow(new BigInteger(PUB_EXP, 16), new BigInteger(MODULUS, 16)).toString(16);
        StringBuilder sb = new StringBuilder();
        for (int i = hex.length(); i < 256; i++) sb.append('0');
        return sb.append(hex).toString();
    }

    /** The form body for a weapi request: params (AES twice) and encSecKey (the random key, RSA). */
    public static String weapiBody(String json) throws Exception {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 16; i++) key.append(CHARS.charAt(RNG.nextInt(CHARS.length())));
        String params = aes(aes(json, PRESET_KEY), key.toString());
        return "params=" + URLEncoder.encode(params, "UTF-8") + "&encSecKey=" + URLEncoder.encode(rsa(key.toString()), "UTF-8");
    }

    public static Weapi weapi(String url, String json, String cookie) throws Exception {
        String body = weapiBody(json);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Referer", "https://music.163.com/");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Cookie", cookie == null ? CLIENT : cookie);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String text;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) cookies.addAll(h.getValue());
            }
            return new Weapi(text, cookies);
        } finally {
            c.disconnect();
        }
    }

    /** The login cookies worth keeping, from Set-Cookie headers. */
    public static String keepCookies(List<String> setCookies) {
        Set<String> wanted = new HashSet<>(Arrays.asList("MUSIC_U", "__csrf", "MUSIC_A_T", "MUSIC_R_T", "NMTID"));
        List<String> parts = new ArrayList<>();
        for (String sc : setCookies) {
            String kv = sc.split(";", 2)[0].trim();
            String name = kv.split("=", 2)[0];
            if (wanted.contains(name) && kv.length() > name.length() + 1) parts.add(kv);
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ JSON

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    static int num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsInt();
        } catch (Exception ex) {
            return 0;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
