package gale.spotify.model;

import com.google.gson.JsonObject;
import gale.spotify.Json;

/** Something Spotify plays on (Spotify Connect): this computer's app, a phone, a speaker. */
public final class Device {
    public final String id, name, type;
    public final boolean active, restricted;
    /** -1 when the device does not report it */
    public final int volume;

    public Device(String id, String name, String type, boolean active, boolean restricted, int volume) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.active = active;
        this.restricted = restricted;
        this.volume = volume;
    }

    public static Device from(JsonObject o) {
        if (o == null) return null;
        return new Device(Json.str(o, "id"), Json.str(o, "name", "?"), Json.str(o, "type", ""), Json.bool(o, "is_active", false),
            Json.bool(o, "is_restricted", false), (int) Json.num(o, "volume_percent", -1));
    }

    @Override
    public String toString() { return name + " (" + type + ")"; }
}
