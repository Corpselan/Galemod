package gale.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gale.netease.NeteaseApi;
import gale.netease.model.NeteaseTrack;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Finds a song's timed lyrics. A NetEase song: by its id (word-timed YRC when NetEase has it, else LRC). Any other song
 * (Spotify's): looked up by name, singer and length, on NetEase (searched; the result whose length matches) and on
 * LRCLIB (lrclib.net, an open lyrics library). Chinese, Japanese and Korean songs try NetEase first, others LRCLIB.
 * Blocking: call it off the game thread.
 */
public final class LyricsProvider {
    /** a stand-in for lrclib.net in the tests */
    public static volatile String lrclib = "https://lrclib.net";
    private static final String UA = "GaleMod/1.0 (Minecraft 1.8.9 mod)";

    private LyricsProvider() { }

    /** prefer: for songs that are not NetEase's, "Auto", "NetEase" or "LRCLIB". Null when none are found. */
    public static Lyrics find(NowPlaying s, String prefer) {
        if (s.neteaseId != 0) return netease(s.neteaseId);
        boolean cjk = hasCjk(s.title) || hasCjk(s.artists);
        boolean neteaseFirst = "NetEase".equals(prefer) || !"LRCLIB".equals(prefer) && cjk;
        Lyrics l = neteaseFirst ? neteaseSearch(s.title, s.artists, s.durationMs) : lrclib(s.title, s.artists, s.album, s.durationMs);
        if (l != null) return l;
        return neteaseFirst ? lrclib(s.title, s.artists, s.album, s.durationMs) : neteaseSearch(s.title, s.artists, s.durationMs);
    }

    public static Lyrics netease(long id) {
        String[] texts = NeteaseApi.lyricTexts(id);
        if (texts[1] != null) {
            List<LyricLine> lines = LyricParser.yrc(texts[1]);
            if (!lines.isEmpty()) return nonEmpty(new Lyrics(lines, "网易云"));
        }
        if (texts[0] != null) {
            List<LyricLine> lines = LyricParser.lrc(texts[0]);
            if (!lines.isEmpty()) return nonEmpty(new Lyrics(lines, "网易云"));
        }
        return null;
    }

    private static Lyrics nonEmpty(Lyrics l) { return l.isEmpty() ? null : l; }

    /** The NetEase song with this name and singer whose length is closest (within 3 s, when the length is known). */
    public static Lyrics neteaseSearch(String title, String artists, long durationMs) {
        try {
            String first = firstArtist(artists);
            List<NeteaseTrack> found = NeteaseApi.search(bare(title) + (first.isEmpty() ? "" : " " + first), 10);
            NeteaseTrack best = null;
            long bestScore = Long.MAX_VALUE;
            String want = norm(title);
            for (NeteaseTrack t : found) {
                long diff = durationMs > 0 && t.durationMs > 0 ? Math.abs(t.durationMs - durationMs) : 0;
                if (durationMs > 0 && t.durationMs > 0 && diff > 3000) continue;
                boolean sameName = norm(t.name).equals(want) || norm(t.name).contains(want) || want.contains(norm(t.name));
                long score = diff + (sameName ? 0 : 10_000);
                if (score < bestScore) {
                    bestScore = score;
                    best = t;
                }
            }
            return best == null ? null : netease(best.id);
        } catch (Exception e) {
            return null;
        }
    }

    public static Lyrics lrclib(String title, String artists, String album, long durationMs) {
        try {
            String first = firstArtist(artists);
            StringBuilder q = new StringBuilder(lrclib).append("/api/get?track_name=").append(enc(title)).append("&artist_name=").append(enc(first));
            if (album != null && !album.isEmpty()) q.append("&album_name=").append(enc(album));
            if (durationMs > 0) q.append("&duration=").append(Math.round(durationMs / 1000.0));
            String body = get(q.toString());
            Lyrics l = body == null ? null : fromLrclib(new JsonParser().parse(body));
            if (l != null) return l;
            body = get(lrclib + "/api/search?track_name=" + enc(bare(title)) + "&artist_name=" + enc(first));
            if (body == null) return null;
            JsonElement e = new JsonParser().parse(body);
            if (!e.isJsonArray()) return null;
            JsonObject best = null;
            double bestDiff = Double.MAX_VALUE;
            for (JsonElement r : (JsonArray) e) {
                if (!r.isJsonObject()) continue;
                JsonObject o = r.getAsJsonObject();
                if (!o.has("syncedLyrics") || o.get("syncedLyrics").isJsonNull()) continue;
                double d = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
                double diff = durationMs > 0 && d > 0 ? Math.abs(d - durationMs / 1000.0) : 0;
                if (durationMs > 0 && d > 0 && diff > 3) continue;
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = o;
                }
            }
            return best == null ? null : fromLrclib(best);
        } catch (Exception e) {
            return null;
        }
    }

    private static Lyrics fromLrclib(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement synced = o.get("syncedLyrics");
        if (synced == null || synced.isJsonNull()) return null;
        List<LyricLine> lines = LyricParser.lrc(synced.getAsString());
        return lines.isEmpty() ? null : nonEmpty(new Lyrics(lines, "LRCLIB"));
    }

    /** GET, the body, or null on 404 and other errors. */
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    public static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
            if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) return true;
        }
        return false;
    }

    public static String firstArtist(String artists) {
        if (artists == null) return "";
        String a = artists.split(",| / |、|&")[0].trim();
        return a;
    }

    /** The name without "(Remastered 2011)", "[Live]", " - Radio Edit" and the like. */
    public static String bare(String title) {
        String t = title.replaceAll("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        return t.trim().isEmpty() ? title.trim() : t.trim();
    }

    public static String norm(String title) {
        StringBuilder sb = new StringBuilder();
        for (char c : bare(title).toLowerCase(Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) sb.append(c);
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
