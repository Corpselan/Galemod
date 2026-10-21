package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.KeyEvent;
import gale.spotify.SpotifyModule;
import gale.spotify.SpotifyPlayer;
import gale.spotify.model.Playback;
import gale.spotify.ui.SpotifyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.function.IntConsumer;

/**
 * Spotify (gradlew runClient -Pspotifytest), against FakeSpotify (no account, nothing leaves this computer): logging
 * in, the HUD card, /spotify, the player screen (now playing, playlists, search, the bars, the queue, devices), a key,
 * a free account, a token running out, nothing playing, logging out. Screenshots in run/screenshots/spotify-*.png.
 */
final class SpotifyTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static FakeSpotify fake;
    private static String shot;
    private static SpotifyScreen screen;

    private SpotifyTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, detail == null ? "" : String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Spotify " + name, ticks, enter, tick, exit));
    }

    private static SpotifyModule m() { return Gale.modules().get(SpotifyModule.class); }

    private static SpotifyPlayer p() { return m().spotify(); }

    private static void command(String c) { ClientCommandHandler.instance.executeCommand(mc.thePlayer, c); }

    private static void click(String id, int button) {
        try {
            if (screen == null || !screen.click(id, button)) DevTest.line("could not click " + id + (screen == null ? "" : " " + screen.hitIds()));
        } catch (IOException e) {
            DevTest.line("click " + id + ": " + e);
        }
    }

    private static void openScreen(SpotifyScreen.Section s) {
        screen = new SpotifyScreen(m(), s);
        mc.displayGuiScreen(screen);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 25617;
        }
    }

    static void phases() {
        try {
            fake = new FakeSpotify().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        new File(Gale.config().dir(), "spotify.json").delete();
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "spotify-test");

        phase("login", 80, () -> {
            mc.gameSettings.showInventoryAchievementHint = false;
            mc.guiAchievement.clearAchievements();
            m().clientId.set("test-client-id");
            m().port.set(freePort());
            SpotifyModule.browser = url -> fake.followLogin(url);
            m().setEnabled(true);
            command("/spotify login");
        }, x -> { }, () -> {
            check("/spotify login logs in (through the 127.0.0.1 callback)", p().api().loggedIn() && fake.tokenGrants.get() == 1, fake.tokenGrants.get());
            check("with your app's Client ID", "test-client-id".equals(fake.lastClientId), fake.lastClientId);
            check("the login is kept in galemod/spotify.json", new File(Gale.config().dir(), "spotify.json").isFile(), null);
            check("who you are (Premium)", p().user != null && p().user.premium(), p().user == null ? null : p().user.name);
        });

        phase("the HUD card", 50, () -> mc.thePlayer.rotationPitch = -20f, x -> { if (x == 45) shot = "spotify-1-hud"; }, () -> {
            Playback pb = p().playback;
            check("it knows what is playing", pb != null && pb.track != null && pb.track.name.equals("Midnight Drive"), pb == null ? null : pb.track);
            check("the module's line says it too", m().getInfo().contains("Midnight Drive"), m().getInfo());
        });

        phase("/spotify", 70, () -> command("/spotify pause"), x -> {
            if (x == 10) {
                check("/spotify pause", !fake.playing, null);
                command("/spotify p");
            }
            if (x == 20) {
                check("/spotify p (play again)", fake.playing, null);
                command("/spotify next");
            }
            if (x == 30) {
                check("/spotify next", fake.current == 1, fake.current);
                command("/spotify vol 30");
            }
            if (x == 40) {
                check("/spotify vol 30", fake.volume == 30, fake.volume);
                command("/spotify seek 1:00");
            }
            if (x == 50) check("/spotify seek 1:00", Math.abs(fake.progressAt - 60000) < 1500, fake.progressAt);
        }, () -> { });

        phase("the player: now playing", 50, () -> openScreen(SpotifyScreen.Section.NOW), x -> {
            if (x == 30) shot = "spotify-2-now";
            if (x == 35) click("play", 0);
        }, () -> {
            check("the player screen opens", mc.currentScreen instanceof SpotifyScreen, mc.currentScreen);
            check("its play button pauses", !fake.playing, null);
            click("play", 0);
        });

        phase("the player: playlists", 80, () -> click("nav:playlists", 0), x -> {
            if (x == 20) shot = "spotify-3-playlists";
            if (x == 25) click("playlist:1", 0);
            if (x == 45) click("row:1", 0);
            if (x == 70) shot = "spotify-4-playlist";
        }, () -> {
            check("your playlists show", screen.playlists() != null && screen.playlists().size() == 2, screen.playlists());
            check("a playlist opens with its songs", screen.section() == SpotifyScreen.Section.PLAYLIST && screen.tracks() != null && screen.tracks().size() == 2,
                screen.tracks());
            check("clicking a song plays it in its playlist", fake.song().id.equals("t6") && "spotify:playlist:p2".equals(fake.context), fake.song().name + " " + fake.context);
        });

        phase("the player: search", 60, () -> {
            try {
                screen.type("tokyo", true);
            } catch (IOException e) {
                DevTest.line("type: " + e);
            }
        }, x -> {
            if (x == 25) click("row:0", 0);
            if (x == 50) shot = "spotify-5-search";
        }, () -> {
            check("search finds it", screen.tracks() != null && screen.tracks().size() == 1, screen.tracks());
            check("and plays it", fake.song().id.equals("t3"), fake.song().name);
        });

        phase("the player: bars and the queue", 20, () -> {
            screen.dragTo("volume", 0.8f);
            screen.dragTo("progress", 0.5f);
            click("row:0", 1);
        }, x -> { }, () -> {
            check("dragging the volume bar", Math.abs(fake.volume - 80) <= 3, fake.volume);
            check("dragging the position bar", Math.abs(fake.progressAt - fake.song().duration / 2) < 5000, fake.progressAt + " of " + fake.song().duration);
            check("right click adds to the queue", fake.queue.contains("spotify:track:t3") && screen.toast().contains("队列"), fake.queue + " " + screen.toast());
        });

        phase("the player: devices", 50, () -> click("nav:devices", 0), x -> {
            if (x == 15) click("device:1", 0);
            if (x == 40) shot = "spotify-6-devices";
        }, () -> check("clicking a device moves playback there", "d2".equals(fake.activeDevice), fake.activeDevice));

        phase("a key", 20, () -> {
            mc.displayGuiScreen(null);
            screen = null;
            m().playKey.set("K");
        }, x -> {
            if (x == 5) Gale.BUS.post(new KeyEvent(Keyboard.KEY_K, true));
        }, () -> {
            check("the Play/Pause key pauses", !fake.playing, null);
            m().playKey.reset();
        });

        phase("a free account", 40, () -> {
            fake.playing = true;
            fake.free = true;
            command("/spotify next");
            openScreen(SpotifyScreen.Section.NOW);
        }, x -> { if (x == 30) shot = "spotify-7-premium"; }, () -> {
            check("controls say Premium is needed", p().message.contains("Premium"), p().message);
            fake.free = false;
            mc.displayGuiScreen(null);
        });

        phase("a token that runs out", 60, () -> {
            fake.expireTokens = true;
            p().message = "";
        }, x -> { }, () -> check("is refreshed, and it keeps going", p().api().refreshes >= 1 && p().status == SpotifyPlayer.Status.PLAYING,
            p().api().refreshes + " " + p().status));

        phase("nothing playing", 50, () -> fake.activeDevice = null, x -> { }, () -> {
            check("nothing playing anywhere: the card goes away", p().status == SpotifyPlayer.Status.NOTHING_PLAYING && p().playback == null, p().status);
            fake.activeDevice = "d1";
        });

        phase("logout", 40, () -> command("/spotify logout"), x -> {
            if (x == 5) openScreen(SpotifyScreen.Section.NOW);
            if (x == 30) shot = "spotify-8-login";
        }, () -> {
            check("/spotify logout forgets the login", !p().api().loggedIn() && !new File(Gale.config().dir(), "spotify.json").exists(), null);
            check("and the player asks to log in", screen.hitIds().contains("login"), screen.hitIds());
            mc.displayGuiScreen(null);
        });

        phase("finish", 5, () -> {
            m().clientId.reset();
            m().port.reset();
            m().setEnabled(false);
        }, x -> { }, () -> {
            fake.stop();
            Gale.config().save();
        });
    }
}
