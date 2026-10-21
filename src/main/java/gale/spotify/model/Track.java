package gale.spotify.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gale.spotify.Json;

import java.util.ArrayList;
import java.util.List;

/** A song (or a podcast episode) as Spotify describes it. */
public final class Track {
    public final String id, uri, name, artists, album, cover, coverLarge;
    public final long durationMs;
    public final boolean episode;

    public Track(String id, String uri, String name, String artists, String album, String cover, String coverLarge, long durationMs, boolean episode) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.cover = cover;
        this.coverLarge = coverLarge;
        this.durationMs = durationMs;
        this.episode = episode;
    }

    /** From a track or episode object; null if it is not one (a local file Spotify cannot describe still is). */
    public static Track from(JsonObject o) {
        if (o == null) return null;
        boolean episode = "episode".equals(Json.str(o, "type"));
        String artists;
        JsonObject album;
        if (episode) {
            JsonObject show = Json.obj(o, "show");
            artists = Json.str(show, "publisher", Json.str(show, "name", ""));
            album = show;
        } else {
            List<String> names = new ArrayList<>();
            for (JsonElement a : Json.arr(o, "artists")) if (a.isJsonObject()) names.add(Json.str(a.getAsJsonObject(), "name", ""));
            artists = String.join(", ", names);
            album = Json.obj(o, "album");
        }
        JsonArray images = Json.arr(album, "images");
        if (images.size() == 0) images = Json.arr(o, "images");
        return new Track(Json.str(o, "id"), Json.str(o, "uri", ""), Json.str(o, "name", "?"), artists, Json.str(album, "name", ""),
            image(images, 64), image(images, 300), Json.num(o, "duration_ms", 0), episode);
    }

    /** The smallest picture at least this wide (Spotify lists them largest first), or the largest there is. */
    static String image(JsonArray images, int want) {
        String best = null;
        long bestW = Long.MAX_VALUE;
        String any = null;
        for (JsonElement e : images) {
            if (!e.isJsonObject()) continue;
            JsonObject i = e.getAsJsonObject();
            String url = Json.str(i, "url");
            if (url == null) continue;
            if (any == null) any = url;
            long w = Json.num(i, "width", 0);
            if (w >= want && w < bestW) {
                best = url;
                bestW = w;
            }
        }
        return best != null ? best : any;
    }

    @Override
    public String toString() { return name + (artists.isEmpty() ? "" : " - " + artists); }
}
