package gale.devtest;

import gale.spotify.SpotifyApi;
import gale.spotify.SpotifyAuth;
import gale.spotify.SpotifyException;
import gale.spotify.SpotifyPlayer;
import gale.spotify.TokenStore;
import gale.spotify.CoverCache;
import gale.spotify.model.Device;
import gale.spotify.model.Playback;
import gale.spotify.model.Playlist;
import gale.spotify.model.Track;

import java.io.File;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * gradlew spotifyCoreTest: the Spotify client without the game, against FakeSpotify (no account, nothing leaves this
 * computer): login with PKCE through the 127.0.0.1 callback, the saved login, every control, lists and search, a token
 * running out, a free account, no device, being asked to slow down, logging out.
 */
public final class SpotifyCoreTest {
    private static int passed, failed;

    private SpotifyCoreTest() { }

    static void check(String name, boolean ok, Object detail) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : "  (" + detail + ")"));
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return c.getAsBoolean();
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public static void main(String[] args) throws Exception {
        FakeSpotify fake = new FakeSpotify().start();
        File dir = new File("build/coretest");
        dir.mkdirs();
        File tokenFile = new File(dir, "spotify.json");
        tokenFile.delete();
        int port = freePort();
        try {
            run(fake, tokenFile, port);
        } catch (Throwable t) {
            t.printStackTrace();
            check("no exception", false, t);
        } finally {
            fake.stop();
        }
        System.out.println("=== " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(FakeSpotify fake, File tokenFile, int port) throws Exception {
        System.out.println("--- login");
        SpotifyAuth auth = new SpotifyAuth("test-client-id", port);
        TokenStore store = new TokenStore(tokenFile);
        SpotifyApi api = new SpotifyApi(auth, store);
        check("not logged in at first", !api.loggedIn(), null);
        AtomicReference<String> opened = new AtomicReference<>();
        TokenStore.Tokens t = auth.login(url -> {
            opened.set(url);
            fake.followLogin(url);
        }, 10000);
        api.loggedIn(t);
        String url = opened.get();
        check("the login page asks with PKCE (S256), the app's id and the 127.0.0.1 callback", url != null && url.contains("code_challenge_method=S256")
            && url.contains("client_id=test-client-id") && url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A" + port + "%2Fcallback"), url);
        check("the code is traded for tokens with the verifier", fake.tokenGrants.get() == 1 && fake.lastVerifierSeen != null, fake.tokenGrants.get());
        check("the login is saved", tokenFile.isFile() && store.load() != null && store.load().refresh.startsWith("refresh-"), tokenFile);
        SpotifyApi again = new SpotifyApi(auth, new TokenStore(tokenFile));
        check("and found again next time (no new login)", again.loggedIn(), null);

        System.out.println("--- account and what is playing");
        check("who is logged in", "Gale Tester".equals(api.me().name) && api.me().premium(), api.me().name);
        Playback p = api.playback();
        check("what is playing: Midnight Drive by Neon Coast, on the PC", p != null && p.track != null && p.track.name.equals("Midnight Drive")
            && p.track.artists.equals("Neon Coast") && p.playing && "LLUOQ-PC".equals(p.device.name), p == null ? null : p.track);
        check("the cover: the 64 px picture for the HUD, 300 px for the screen", p != null && p.track.cover.endsWith("t1-64.png") && p.track.coverLarge.endsWith("t1-300.png"),
            p == null ? null : p.track.cover);

        System.out.println("--- controls");
        api.pause();
        check("pause", !fake.playing, null);
        api.resume(null);
        check("play", fake.playing, null);
        api.next();
        check("next", fake.current == 1, fake.current);
        api.previous();
        check("previous", fake.current == 0, fake.current);
        api.seek(90000);
        check("seek to 1:30", Math.abs(api.playback().progressMs - 90000) < 2000, api.playback().progressMs);
        api.volume(35);
        check("volume 35%", fake.volume == 35 && api.playback().volume() == 35, fake.volume);
        api.shuffle(true);
        api.repeat("track");
        check("shuffle and repeat", fake.shuffle && fake.repeat.equals("track"), fake.shuffle + " " + fake.repeat);
        api.playContext(null, "spotify:playlist:p2", "spotify:track:t6");
        check("play a playlist from a song in it", fake.song().id.equals("t6") && "spotify:playlist:p2".equals(fake.context), fake.song().name);
        api.playTracks(null, Arrays.asList("spotify:track:t2", "spotify:track:t3"), 1);
        check("play a list of songs from the second", fake.song().id.equals("t3"), fake.song().name);
        api.queue("spotify:track:t4");
        check("add to the queue", fake.queue.contains("spotify:track:t4"), fake.queue);
        List<Track> next = api.upNext();
        check("what comes next", next.size() == 3, next);

        System.out.println("--- lists");
        List<Playlist> lists = api.playlists();
        check("your playlists", lists.size() == 2 && lists.get(1).name.equals("华语精选") && lists.get(0).tracks == 4, lists);
        List<Track> songs = api.playlistTracks("p2");
        check("a playlist's songs", songs.size() == 2 && songs.get(0).name.equals("晴天") && songs.get(0).artists.equals("周杰伦"), songs);
        check("liked songs", api.likedTracks().size() == 2, api.likedTracks());
        List<Track> found = api.search("tokyo", 20);
        check("search", found.size() == 1 && found.get(0).name.equals("Paper Planes Over Tokyo"), found);
        List<Device> devices = api.devices();
        check("devices", devices.size() == 2 && devices.get(0).active, devices);
        api.transfer("d2", true);
        check("move playback to the phone", "d2".equals(fake.activeDevice) && "iPhone".equals(api.playback().device.name), fake.activeDevice);

        System.out.println("--- when things go wrong");
        fake.expireTokens = true;
        int refreshes = api.refreshes;
        Playback afterExpiry = api.playback();
        check("a token that ran out is refreshed and the request goes through", afterExpiry != null && api.refreshes == refreshes + 1 && fake.refreshGrants.get() >= 1,
            api.refreshes + " refreshes");
        fake.free = true;
        try {
            api.next();
            check("a free account: controls say Premium is needed", false, "no error");
        } catch (SpotifyException e) {
            check("a free account: controls say Premium is needed", e.premiumRequired() && e.explain().contains("Premium"), e.explain());
        }
        check("but it can still see what is playing", api.playback() != null, null);
        fake.free = false;
        fake.activeDevice = null;
        check("nothing playing anywhere: no playback (204)", api.playback() == null, null);
        try {
            api.pause();
            check("no device: says to open Spotify or pick a device", false, "no error");
        } catch (SpotifyException e) {
            check("no device: says to open Spotify or pick a device", e.noDevice(), e.explain());
        }
        fake.activeDevice = "d1";
        fake.rateLimitOnce = true;
        try {
            api.playback();
            check("asked to slow down: waits as long as Spotify says", false, "no error");
        } catch (SpotifyException e) {
            check("asked to slow down: waits as long as Spotify says", e.status == 429 && e.retryAfterMs == 1000, e.retryAfterMs);
        }

        System.out.println("--- the player (background thread)");
        SpotifyPlayer player = new SpotifyPlayer(api);
        AtomicReference<Track> started = new AtomicReference<>();
        player.onTrack = started::set;
        player.intervalMs = 300;
        player.start();
        check("it finds what is playing", waitFor(() -> player.status == SpotifyPlayer.Status.PLAYING && player.playback != null, 3000), player.status);
        check("and who you are", waitFor(() -> player.user != null && player.user.premium(), 3000), player.user);
        long a = player.playback.progressNow();
        Thread.sleep(400);
        check("the position moves on between refreshes", player.playback.progressNow() > a, a + " -> " + player.playback.progressNow());
        boolean wasPlaying = player.playback.playing;
        player.togglePlay();
        check("play/pause answers at once", player.playback.playing != wasPlaying, null);
        check("and Spotify follows", waitFor(() -> fake.playing != wasPlaying, 2000), fake.playing);
        player.togglePlay();
        String before = player.playback.track.uri;
        started.set(null);
        player.next();
        check("next: another song, and it says so", waitFor(() -> started.get() != null && !started.get().uri.equals(before), 3000), started.get());
        player.changeVolume(-10);
        int want = fake.volume;
        check("volume down 10", waitFor(() -> fake.volume <= 25, 2000) && want >= 0, fake.volume);
        fake.free = true;
        player.next();
        check("a refused control leaves a message to show", waitFor(() -> player.message.contains("Premium"), 2000), player.message);
        fake.free = false;
        player.stop();

        System.out.println("--- covers");
        CoverCache covers = new CoverCache();
        String cover = api.playback().track.cover;
        covers.get(cover);
        check("a cover downloads in the background", waitFor(() -> covers.get(cover) != null, 3000) && covers.get(cover).getWidth() == 64, cover);

        System.out.println("--- logout");
        api.logout();
        check("logging out forgets the login", !api.loggedIn() && !tokenFile.exists(), tokenFile.exists());
        try {
            api.playback();
            check("and nothing is asked without one", false, "no error");
        } catch (SpotifyException e) {
            check("and nothing is asked without one", e.status == 401, e.explain());
        }
    }
}
