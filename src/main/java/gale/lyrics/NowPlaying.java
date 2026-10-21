package gale.lyrics;

import gale.netease.NeteasePlayer;
import gale.netease.model.NeteaseTrack;
import gale.spotify.SpotifyModule;
import gale.spotify.model.Playback;
import gale.spotify.model.Track;

/**
 * What is playing right now, from either player: NetEase (played by Gale itself) or Spotify (played elsewhere, seen
 * through its API). The lyrics and the cards read this, so they work the same for both.
 */
public final class NowPlaying {
    public enum Source { NETEASE, SPOTIFY }

    public final Source source;
    /** the same for the same song: "netease:123", "spotify:4uLU6hMCjMI75M1A2tKUQC" */
    public final String key, title, artists, album, cover;
    public final long durationMs;
    /** the NetEase id when it is a NetEase song (its lyrics are fetched directly), else 0 */
    public final long neteaseId;
    public final boolean playing;
    private final long positionMs;

    private NowPlaying(Source source, String key, String title, String artists, String album, String cover, long durationMs, long neteaseId,
                       boolean playing, long positionMs) {
        this.source = source;
        this.key = key;
        this.title = title;
        this.artists = artists;
        this.album = album;
        this.cover = cover;
        this.durationMs = durationMs;
        this.neteaseId = neteaseId;
        this.playing = playing;
        this.positionMs = positionMs;
    }

    /** Where the song is, in ms, when this was taken. */
    public long position() { return positionMs; }

    /** A beat landing now (0..1); only NetEase songs have them (Spotify does not let anyone hear its sound). */
    public float beat() { return source == Source.NETEASE ? NeteasePlayer.INSTANCE.beatPulse(positionMs) : 0f; }

    /** For the tests: a song that is not in either player (asked each frame, so its position can move). */
    public static volatile java.util.function.Supplier<NowPlaying> override;

    public static NowPlaying fake(String key, String title, String artists, long durationMs, long positionMs, boolean playing, long neteaseId) {
        return new NowPlaying(Source.NETEASE, key, title, artists, "", null, durationMs, neteaseId, playing, positionMs);
    }

    public static NowPlaying netease() {
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        NeteaseTrack t = p.current;
        if (t == null || !p.active()) return null;
        return new NowPlaying(Source.NETEASE, "netease:" + t.id, t.name, t.artists, t.album, t.cover, t.durationMs, t.id,
            p.state == NeteasePlayer.State.PLAYING, p.positionMs());
    }

    public static NowPlaying spotify() {
        SpotifyModule m = SpotifyModule.instance;
        if (m == null || !m.isEnabled()) return null;
        Playback pb = m.spotify().playback;
        Track t = pb == null ? null : pb.track;
        if (t == null || t.episode) return null;
        return new NowPlaying(Source.SPOTIFY, "spotify:" + (t.id != null ? t.id : t.uri), t.name, t.artists, t.album, t.cover, t.durationMs, 0, pb.playing,
            Math.min(pb.progressNow(), Math.max(t.durationMs, 0)));
    }

    /**
     * The song to follow: the one that is playing (NetEase first when both are), else a paused one (NetEase first).
     * prefer: "Auto", "NetEase" or "Spotify".
     */
    public static NowPlaying current(String prefer) {
        java.util.function.Supplier<NowPlaying> o = override;
        if (o != null) return o.get();
        if ("NetEase".equals(prefer)) return netease();
        if ("Spotify".equals(prefer)) return spotify();
        NowPlaying n = netease(), s = spotify();
        if (n != null && n.playing) return n;
        if (s != null && s.playing) return s;
        return n != null ? n : s;
    }
}
