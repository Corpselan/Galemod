package gale.spotify.model;

import com.google.gson.JsonObject;
import gale.spotify.Json;

/** One of your playlists. */
public final class Playlist {
    public final String id, uri, name, owner, cover;
    public final int tracks;

    public Playlist(String id, String uri, String name, String owner, String cover, int tracks) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.owner = owner;
        this.cover = cover;
        this.tracks = tracks;
    }

    public static Playlist from(JsonObject o) {
        if (o == null) return null;
        JsonObject count = Json.obj(o, "tracks");
        if (count == null) count = Json.obj(o, "items");
        return new Playlist(Json.str(o, "id"), Json.str(o, "uri", ""), Json.str(o, "name", "?"),
            Json.str(Json.obj(o, "owner"), "display_name", ""), Track.image(Json.arr(o, "images"), 64), (int) Json.num(count, "total", 0));
    }

    @Override
    public String toString() { return name; }
}
