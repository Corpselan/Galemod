package gale.spotify.model;

import com.google.gson.JsonObject;
import gale.spotify.Json;

/** The logged-in account: its name and whether it is Premium (only Premium can control playback). */
public final class SpotifyUser {
    public final String id, name, product;

    public SpotifyUser(String id, String name, String product) {
        this.id = id;
        this.name = name;
        this.product = product;
    }

    public static SpotifyUser from(JsonObject o) {
        if (o == null) return null;
        String id = Json.str(o, "id", "");
        return new SpotifyUser(id, Json.str(o, "display_name", id), Json.str(o, "product", ""));
    }

    public boolean premium() { return "premium".equalsIgnoreCase(product); }
}
