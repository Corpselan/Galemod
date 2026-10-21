package gale.spotify;

import gale.spotify.model.Device;
import gale.spotify.model.Playback;
import gale.spotify.model.Playlist;
import gale.spotify.model.SpotifyUser;
import gale.spotify.model.Track;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The player as the game sees it: what is playing (asked every few seconds, more often while the player screen is
 * open), and the controls. Everything that talks to Spotify runs on one background thread; the game reads the
 * fields, which are always a finished snapshot. A control changes the snapshot at once (the button answers), then
 * Spotify is asked and the next refresh shows what really happened.
 */
public final class SpotifyPlayer {
    public enum Status { LOGGED_OUT, CONNECTING, NOTHING_PLAYING, PLAYING, ERROR }

    private final SpotifyApi api;
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> ticker;
    private volatile long nextPoll, backoffUntil;

    public volatile Status status = Status.LOGGED_OUT;
    public volatile Playback playback;
    public volatile SpotifyUser user;
    public volatile List<Device> devices = Collections.emptyList();
    /** the last error, said in Chinese (empty when the last request worked) */
    public volatile String message = "";
    public volatile long messageAt;
    /** seconds between refreshes, and while the player screen is open */
    public volatile int intervalMs = 2000, fastIntervalMs = 1000;
    public volatile boolean fast;
    /** told on the background thread when another song starts (the game moves it to its own thread) */
    public volatile Consumer<Track> onTrack;
    /** told on the background thread when a control or refresh fails */
    public volatile Consumer<String> onError;

    public SpotifyPlayer(SpotifyApi api) { this.api = api; }

    public SpotifyApi api() { return api; }

    public synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Gale Spotify");
            t.setDaemon(true);
            return t;
        });
        status = api.loggedIn() ? Status.CONNECTING : Status.LOGGED_OUT;
        nextPoll = 0;
        ticker = exec.scheduleWithFixedDelay(this::tick, 0, 200, TimeUnit.MILLISECONDS);
        exec.execute(this::loadUser);
    }

    public synchronized void stop() {
        if (exec == null) return;
        ticker.cancel(false);
        exec.shutdownNow();
        exec = null;
    }

    public boolean running() { return exec != null; }

    /** Refresh soon (after a control, or when the screen opens). */
    public void refreshSoon(long ms) { nextPoll = Math.min(nextPoll, System.currentTimeMillis() + ms); }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!api.loggedIn()) {
            status = Status.LOGGED_OUT;
            return;
        }
        if (now < nextPoll || now < backoffUntil) return;
        nextPoll = now + (fast ? fastIntervalMs : intervalMs);
        try {
            Playback p = api.playback();
            Track before = playback == null ? null : playback.track;
            playback = p;
            status = p == null || p.track == null ? Status.NOTHING_PLAYING : Status.PLAYING;
            if (p != null && p.track != null && (before == null || !p.track.uri.equals(before.uri))) {
                Consumer<Track> c = onTrack;
                if (c != null) c.accept(p.track);
            }
            if (!message.isEmpty() && now - messageAt > 6000) message = "";
        } catch (SpotifyException e) {
            fail(e);
        } catch (RuntimeException e) {
            fail(new SpotifyException(0, "error", e.toString()));
        }
    }

    private void loadUser() {
        if (!api.loggedIn()) return;
        try {
            user = api.me();
        } catch (SpotifyException e) {
            fail(e);
        }
    }

    private void fail(SpotifyException e) {
        if (e.status == 429) backoffUntil = System.currentTimeMillis() + e.retryAfterMs;
        if (!api.loggedIn()) {
            status = Status.LOGGED_OUT;
            playback = null;
        } else if (e.noDevice()) {
            status = Status.NOTHING_PLAYING;
        } else if (e.status == 0 || e.status >= 500) {
            status = Status.ERROR;
        }
        message = e.explain();
        messageAt = System.currentTimeMillis();
        Consumer<String> c = onError;
        if (c != null) c.accept(message);
    }

    /** A control, on the background thread; a refresh follows so the screen catches up. */
    private void run(SpotifyCall call) { run(call, true); }

    /** Loading a list: does not clear the last control's message. */
    private void fetch(SpotifyCall call) { run(call, false); }

    private void run(SpotifyCall call, boolean control) {
        ScheduledExecutorService e = exec;
        if (e == null) return;
        e.execute(() -> {
            try {
                call.run();
                if (control && message.length() > 0 && !message.startsWith("请求太快")) message = "";
            } catch (SpotifyException ex) {
                fail(ex);
            } catch (RuntimeException ex) {
                fail(new SpotifyException(0, "error", ex.toString()));
            }
            refreshSoon(350);
        });
    }

    private interface SpotifyCall {
        void run() throws SpotifyException;
    }

    private String deviceId() {
        Playback p = playback;
        return p != null && p.device != null ? p.device.id : null;
    }

    // ------------------------------------------------------------------ controls (any thread)

    public void togglePlay() {
        Playback p = playback;
        boolean play = p == null || !p.playing;
        if (p != null) playback = p.withPlaying(play);
        if (play) run(() -> api.resume(null));
        else run(api::pause);
    }

    public void play() {
        Playback p = playback;
        if (p != null) playback = p.withPlaying(true);
        run(() -> api.resume(null));
    }

    public void pause() {
        Playback p = playback;
        if (p != null) playback = p.withPlaying(false);
        run(api::pause);
    }

    public void next() { run(api::next); }

    public void previous() { run(api::previous); }

    public void seek(long ms) {
        Playback p = playback;
        if (p != null) playback = p.withProgress(ms);
        run(() -> api.seek(ms));
    }

    public void setVolume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        Playback p = playback;
        if (p != null) playback = p.withVolume(v);
        run(() -> api.volume(v));
    }

    public void changeVolume(int by) {
        Playback p = playback;
        int now = p == null ? -1 : p.volume();
        if (now < 0) now = 50;
        setVolume(now + by);
    }

    public void toggleShuffle() {
        Playback p = playback;
        boolean on = p == null || !p.shuffle;
        if (p != null) playback = p.withShuffle(on);
        run(() -> api.shuffle(on));
    }

    /** off, then the whole list, then this song */
    public void cycleRepeat() {
        Playback p = playback;
        String next = p == null || p.repeat.equals("off") ? "context" : p.repeat.equals("context") ? "track" : "off";
        if (p != null) playback = p.withRepeat(next);
        run(() -> api.repeat(next));
    }

    public void playContext(String contextUri, String trackUri) { run(() -> api.playContext(deviceId(), contextUri, trackUri)); }

    public void playTracks(List<String> uris, int index) { run(() -> api.playTracks(deviceId(), uris, index)); }

    public void queue(String uri) { run(() -> api.queue(uri)); }

    public void transfer(String deviceId) {
        run(() -> {
            api.transfer(deviceId, true);
            devices = api.devices();
        });
    }

    // ------------------------------------------------------------------ lists for the screen (results come back on the background thread)

    public void loadDevices(Runnable done) {
        fetch(() -> {
            devices = api.devices();
            if (done != null) done.run();
        });
    }

    public void playlists(Consumer<List<Playlist>> done) { fetch(() -> done.accept(api.playlists())); }

    public void playlistTracks(String id, Consumer<List<Track>> done) { fetch(() -> done.accept(api.playlistTracks(id))); }

    public void liked(Consumer<List<Track>> done) { fetch(() -> done.accept(api.likedTracks())); }

    public void search(String query, Consumer<List<Track>> done) { fetch(() -> done.accept(api.search(query, 20))); }

    public void upNext(Consumer<List<Track>> done) { fetch(() -> done.accept(api.upNext())); }

    /** After logging in: who you are, then start asking. */
    public void loggedIn() {
        status = Status.CONNECTING;
        message = "";
        ScheduledExecutorService e = exec;
        if (e != null) e.execute(this::loadUser);
        refreshSoon(0);
    }

    public void loggedOut() {
        status = Status.LOGGED_OUT;
        playback = null;
        user = null;
        devices = Collections.emptyList();
    }
}
