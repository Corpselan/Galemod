package gale.spotify.model;

import com.google.gson.JsonObject;
import gale.spotify.Json;

/**
 * What is playing, as of one request: the track, where it was, on which device. {@link #progressNow} carries the
 * position forward from when it was fetched, so a progress bar moves smoothly between requests.
 */
public final class Playback {
    public final Track track;
    public final boolean playing, shuffle;
    /** off, context, track */
    public final String repeat;
    public final long progressMs;
    public final Device device;
    public final String contextUri;
    public final JsonObject disallows;
    /** System.currentTimeMillis() when this was fetched (or changed here, before Spotify confirms) */
    public final long fetchedAt;

    public Playback(Track track, boolean playing, boolean shuffle, String repeat, long progressMs, Device device, String contextUri,
                    JsonObject disallows, long fetchedAt) {
        this.track = track;
        this.playing = playing;
        this.shuffle = shuffle;
        this.repeat = repeat == null ? "off" : repeat;
        this.progressMs = progressMs;
        this.device = device;
        this.contextUri = contextUri;
        this.disallows = disallows;
        this.fetchedAt = fetchedAt;
    }

    public static Playback from(JsonObject o, long now) {
        if (o == null) return null;
        JsonObject actions = Json.obj(o, "actions");
        return new Playback(Track.from(Json.obj(o, "item")), Json.bool(o, "is_playing", false), Json.bool(o, "shuffle_state", false),
            Json.str(o, "repeat_state", "off"), Json.num(o, "progress_ms", 0), Device.from(Json.obj(o, "device")),
            Json.str(Json.obj(o, "context"), "uri"), Json.obj(actions, "disallows"), now);
    }

    public long progressNow() {
        long p = progressMs + (playing ? System.currentTimeMillis() - fetchedAt : 0);
        long d = track == null ? 0 : track.durationMs;
        return d > 0 ? Math.max(0, Math.min(d, p)) : Math.max(0, p);
    }

    /** Whether Spotify says this action cannot be done now (pausing, resuming, skipping_next, seeking ...). */
    public boolean disallowed(String action) { return Json.bool(disallows, action, false); }

    public int volume() { return device == null ? -1 : device.volume; }

    // changes made here before Spotify confirms them (so the screen answers at once)
    public Playback withPlaying(boolean p) {
        return new Playback(track, p, shuffle, repeat, progressNow(), device, contextUri, disallows, System.currentTimeMillis());
    }

    public Playback withProgress(long ms) {
        return new Playback(track, playing, shuffle, repeat, ms, device, contextUri, disallows, System.currentTimeMillis());
    }

    public Playback withShuffle(boolean s) {
        return new Playback(track, playing, s, repeat, progressNow(), device, contextUri, disallows, System.currentTimeMillis());
    }

    public Playback withRepeat(String r) {
        return new Playback(track, playing, shuffle, r, progressNow(), device, contextUri, disallows, System.currentTimeMillis());
    }

    public Playback withVolume(int v) {
        Device d = device == null ? null : new Device(device.id, device.name, device.type, device.active, device.restricted, v);
        return new Playback(track, playing, shuffle, repeat, progressNow(), d, contextUri, disallows, System.currentTimeMillis());
    }
}
