package gale.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import gale.spotify.model.Device;
import gale.spotify.model.Playback;
import gale.spotify.model.Playlist;
import gale.spotify.model.SpotifyUser;
import gale.spotify.model.Track;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spotify's Web API, the parts a player needs. Requests block: call them off the game thread (SpotifyPlayer does).
 * The access token is refreshed when it is about to run out, and once more if Spotify says 401.
 */
public final class SpotifyApi {
    private final SpotifyAuth auth;
    private final TokenStore store;
    private volatile TokenStore.Tokens tokens;
    /** how many times the token was refreshed (for the tests) */
    public volatile int refreshes;

    public SpotifyApi(SpotifyAuth auth, TokenStore store) {
        this.auth = auth;
        this.store = store;
        this.tokens = store.load();
    }

    public boolean loggedIn() { return tokens != null; }

    public SpotifyAuth auth() { return auth; }

    public void loggedIn(TokenStore.Tokens t) throws IOException {
        tokens = t;
        store.save(t);
    }

    public void logout() {
        tokens = null;
        store.clear();
    }

    private synchronized String token(boolean force) throws SpotifyException {
        TokenStore.Tokens t = tokens;
        if (t == null) throw new SpotifyException(401, "not_logged_in", "没有登录");
        if (force || t.stale() || t.access == null || t.access.isEmpty()) {
            TokenStore.Tokens n;
            try {
                n = auth.refresh(t);
            } catch (SpotifyException e) {
                // a refresh token Spotify no longer takes: logged out
                if (e.status == 401 || e.status == 400) logout();
                throw e;
            }
            refreshes++;
            tokens = n;
            try {
                store.save(n);
            } catch (IOException ignored) {
            }
            t = n;
        }
        return t.access;
    }

    private Http.Response call(String method, String path, String json) throws SpotifyException {
        Http.Response r = send(method, path, json, token(false));
        if (r.status == 401) r = send(method, path, json, token(true));
        if (r.status == 429) {
            long wait = 1000;
            try {
                wait = Math.max(1, Long.parseLong(r.retryAfter.trim())) * 1000L;
            } catch (Exception ignored) {
            }
            throw new SpotifyException(429, "rate_limited", "too many requests", wait);
        }
        if (r.status >= 400) {
            JsonObject err = Json.obj(Json.parse(r.body), "error");
            throw new SpotifyException(r.status, Json.str(err, "reason", ""), Json.str(err, "message", "HTTP " + r.status));
        }
        return r;
    }

    private Http.Response send(String method, String path, String json, String token) throws SpotifyException {
        try {
            return Http.send(method, SpotifyEndpoints.api() + path, Collections.singletonMap("Authorization", "Bearer " + token),
                json == null ? null : "application/json", json);
        } catch (IOException e) {
            throw new SpotifyException(0, "network", e.toString());
        }
    }

    private JsonObject get(String path) throws SpotifyException { return Json.parse(call("GET", path, null).body); }

    private static String q(String s) { return Http.enc(s); }

    private static String device(String deviceId, boolean first) { return deviceId == null ? "" : (first ? "?" : "&") + "device_id=" + q(deviceId); }

    // ------------------------------------------------------------------ account and playback

    public SpotifyUser me() throws SpotifyException { return SpotifyUser.from(get("/me")); }

    /** null when nothing is playing anywhere (Spotify says 204). */
    public Playback playback() throws SpotifyException {
        Http.Response r = call("GET", "/me/player?additional_types=track,episode", null);
        return r.status == 204 || r.body.trim().isEmpty() ? null : Playback.from(Json.parse(r.body), System.currentTimeMillis());
    }

    public void resume(String deviceId) throws SpotifyException { call("PUT", "/me/player/play" + device(deviceId, true), null); }

    public void pause() throws SpotifyException { call("PUT", "/me/player/pause", null); }

    public void next() throws SpotifyException { call("POST", "/me/player/next", null); }

    public void previous() throws SpotifyException { call("POST", "/me/player/previous", null); }

    public void seek(long ms) throws SpotifyException { call("PUT", "/me/player/seek?position_ms=" + Math.max(0, ms), null); }

    public void volume(int percent) throws SpotifyException { call("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)), null); }

    public void shuffle(boolean on) throws SpotifyException { call("PUT", "/me/player/shuffle?state=" + on, null); }

    /** off, context or track */
    public void repeat(String mode) throws SpotifyException { call("PUT", "/me/player/repeat?state=" + q(mode), null); }

    /** Plays a playlist or album from a track in it (offsetUri may be null: from the start). */
    public void playContext(String deviceId, String contextUri, String offsetUri) throws SpotifyException {
        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);
        if (offsetUri != null) {
            JsonObject offset = new JsonObject();
            offset.addProperty("uri", offsetUri);
            body.add("offset", offset);
        }
        call("PUT", "/me/player/play" + device(deviceId, true), body.toString());
    }

    /** Plays these tracks in order, from the one at index. */
    public void playTracks(String deviceId, List<String> uris, int index) throws SpotifyException {
        JsonObject body = new JsonObject();
        JsonArray a = new JsonArray();
        for (String u : uris) a.add(new JsonPrimitive(u));
        body.add("uris", a);
        if (index > 0) {
            JsonObject offset = new JsonObject();
            offset.addProperty("position", index);
            body.add("offset", offset);
        }
        call("PUT", "/me/player/play" + device(deviceId, true), body.toString());
    }

    public void queue(String uri) throws SpotifyException { call("POST", "/me/player/queue?uri=" + q(uri), null); }

    /** What comes next. */
    public List<Track> upNext() throws SpotifyException { return tracks(Json.arr(get("/me/player/queue"), "queue"), false); }

    public List<Device> devices() throws SpotifyException {
        List<Device> out = new ArrayList<>();
        for (JsonElement e : Json.arr(get("/me/player/devices"), "devices")) if (e.isJsonObject()) out.add(Device.from(e.getAsJsonObject()));
        return out;
    }

    /** Moves playback to this device (and plays). */
    public void transfer(String deviceId, boolean play) throws SpotifyException {
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(new JsonPrimitive(deviceId));
        body.add("device_ids", ids);
        body.addProperty("play", play);
        call("PUT", "/me/player", body.toString());
    }

    // ------------------------------------------------------------------ library and search

    public List<Track> search(String query, int limit) throws SpotifyException {
        JsonObject o = get("/search?type=track&limit=" + limit + "&q=" + q(query));
        return tracks(Json.arr(Json.obj(o, "tracks"), "items"), false);
    }

    public List<Playlist> playlists() throws SpotifyException {
        List<Playlist> out = new ArrayList<>();
        for (JsonElement e : Json.arr(get("/me/playlists?limit=50"), "items")) if (e.isJsonObject()) out.add(Playlist.from(e.getAsJsonObject()));
        return out;
    }

    /** A playlist's songs (the first 100). */
    public List<Track> playlistTracks(String playlistId) throws SpotifyException {
        JsonObject o;
        try {
            o = get("/playlists/" + q(playlistId) + "/tracks?limit=100");
        } catch (SpotifyException e) {
            // newer API name for the same list
            if (e.status != 404 && e.status != 410) throw e;
            o = get("/playlists/" + q(playlistId) + "/items?limit=100");
        }
        return tracks(Json.arr(o, "items"), true);
    }

    /** Your liked songs (the latest 50). */
    public List<Track> likedTracks() throws SpotifyException { return tracks(Json.arr(get("/me/tracks?limit=50"), "items"), true); }

    /** Tracks from a list; wrapped: each entry is {track: ...} (or {item: ...}). */
    private static List<Track> tracks(JsonArray items, boolean wrapped) {
        List<Track> out = new ArrayList<>();
        for (JsonElement e : items) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (wrapped) o = Json.obj(o, "track") != null ? Json.obj(o, "track") : Json.obj(o, "item");
            Track t = Track.from(o);
            if (t != null && t.uri != null && !t.uri.isEmpty()) out.add(t);
        }
        return out;
    }
}
