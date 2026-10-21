package gale.netease.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A song on NetEase Cloud Music, from any of its song lists (search results, playlists, song details). */
public final class NeteaseTrack {
    public final long id;
    public final String name, artists, album, cover;
    public final long durationMs;
    /** 0 or 8: free; 1: VIP only; 4: bought with its album */
    public final int fee;
    /** the best bitrate this account may play (NetEase's "privilege"): 0 = not at all (no copyright, or VIP), -1 = not known */
    public final int playable;

    public NeteaseTrack(long id, String name, String artists, String album, String cover, long durationMs, int fee, int playable) {
        this.id = id;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.cover = cover;
        this.durationMs = durationMs;
        this.fee = fee;
        this.playable = playable;
    }

    public boolean vip() { return fee == 1 || fee == 4; }

    /** False when NetEase said this account cannot play it (no copyright here, or VIP only). */
    public boolean canPlay() { return playable != 0; }

    public NeteaseTrack withPlayable(int pl) { return new NeteaseTrack(id, name, artists, album, cover, durationMs, fee, pl); }

    /** The play level from a privilege object ({"id":…, "pl":128000, "st":0, …}); -1 if it says nothing. */
    public static int playLevel(JsonObject privilege) {
        if (privilege == null) return -1;
        try {
            if (privilege.has("st") && !privilege.get("st").isJsonNull() && privilege.get("st").getAsInt() < 0) return 0;
            return privilege.has("pl") && !privilege.get("pl").isJsonNull() ? privilege.get("pl").getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The cover at about this many pixels (NetEase's pictures resize on request). */
    public String cover(int px) { return sized(cover, px); }

    public static String sized(String url, int px) {
        if (url == null || url.isEmpty()) return null;
        return url + (url.contains("?") ? "&" : "?") + "param=" + px + "y" + px;
    }

    /** Old style (artists, album, duration) or new style (ar, al, dt); null if it has no id. */
    public static NeteaseTrack parse(JsonObject o) {
        long id;
        try {
            JsonElement e = o.get("id");
            if (e == null || e.isJsonNull()) return null;
            id = Long.parseLong(e.getAsString());
        } catch (Exception ex) {
            return null;
        }
        JsonElement ar = o.has("artists") ? o.get("artists") : o.get("ar");
        List<String> names = new ArrayList<>();
        if (ar != null && ar.isJsonArray()) {
            for (JsonElement a : ar.getAsJsonArray()) {
                if (!a.isJsonObject()) continue;
                String n = str(a.getAsJsonObject(), "name");
                if (!n.isEmpty()) names.add(n);
            }
        }
        JsonElement d = o.has("duration") ? o.get("duration") : o.get("dt");
        long dur = 0;
        try {
            if (d != null && !d.isJsonNull()) dur = d.getAsLong();
        } catch (Exception ignored) {
        }
        JsonElement al = o.has("album") ? o.get("album") : o.get("al");
        String cover = "", album = "";
        if (al != null && al.isJsonObject()) {
            JsonObject a = al.getAsJsonObject();
            cover = str(a, "picUrl");
            if (cover.isEmpty()) cover = str(a, "cover");
            album = str(a, "name");
        }
        int fee = 0;
        try {
            if (o.has("fee") && !o.get("fee").isJsonNull()) fee = o.get("fee").getAsInt();
        } catch (Exception ignored) {
        }
        JsonElement pr = o.get("privilege");
        return new NeteaseTrack(id, str(o, "name"), String.join(" / ", names), album, cover, dur, fee, playLevel(pr != null && pr.isJsonObject() ? pr.getAsJsonObject() : null));
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    @Override
    public String toString() { return name + (artists.isEmpty() ? "" : " - " + artists); }
}
