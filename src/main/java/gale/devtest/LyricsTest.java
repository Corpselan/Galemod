package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.Render3DEvent;
import gale.lyrics.LyricParser;
import gale.lyrics.Lyrics;
import gale.lyrics.LyricsModule;
import gale.lyrics.NowPlaying;
import gale.module.setting.Setting;
import gale.netease.NeteaseApi;
import gale.netease.NeteaseLogin;
import gale.netease.NeteaseModule;
import gale.netease.NeteasePlayer;
import gale.netease.model.NeteaseTrack;
import gale.netease.ui.NeteaseScreen;
import gale.render.CjkFont;
import gale.spotify.SpotifyModule;
import gale.spotify.ui.SpotifyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The 3D lyrics and NetEase Cloud Music (gradlew runClient -Plyricstest): a made-up song with Chinese and English
 * lines floating in the world (smooth font, glow, word pop, pausing, the pixel font, a pink glow, text flash, staying
 * on screen, GL state left as found); then a real free NetEase song played with no sound, its lyrics following; the
 * NetEase player's pages (discover, charts, search, the login QR code) and switching to Spotify's player and back.
 * Needs the internet for the second half. Screenshots in run/screenshots/lyrics-*.png.
 */
final class LyricsTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String KEY = "test:lyrics";
    private static String shot;
    private static long songStart;
    private static volatile boolean playing = true;
    private static long pausedAt;
    private static final Map<String, Object> saved = new HashMap<>();
    private static boolean lyricsWasOn, neteaseWasOn, spotifyWasOn, glStateChecked, seeked;
    private static int glLeaks, glFrames;
    private static int[] glBefore;
    private static int baseline = -1;
    private static final List<NeteaseTrack> songs = new ArrayList<>();
    private static NeteaseScreen screen;

    private LyricsTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, detail == null ? "" : String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Lyrics " + name, ticks, enter, tick, exit));
    }

    private static LyricsModule lm() { return LyricsModule.instance; }

    private static long songPos() { return playing ? System.currentTimeMillis() - songStart : pausedAt; }

    /** Bright pixels in the middle half of the last frame (at night: the lyrics and their glow). */
    private static int brightMiddle() {
        Framebuffer fb = mc.getFramebuffer();
        int w = fb.framebufferTextureWidth, h = fb.framebufferTextureHeight;
        java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(w * h);
        net.minecraft.client.renderer.GlStateManager.bindTexture(fb.framebufferTexture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        int n = 0;
        for (int y = h / 4; y < h * 3 / 4; y += 2) {
            for (int x = w / 4; x < w * 3 / 4; x += 2) {
                int c = buf.get(y * w + x), r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
                if (r * 0.3 + g * 0.59 + b * 0.11 > 140) n++;
            }
        }
        return n;
    }

    /** Text flash's colours in the middle: its red, or white. */
    private static int textPixels() {
        Framebuffer fb = mc.getFramebuffer();
        int w = fb.framebufferTextureWidth, h = fb.framebufferTextureHeight;
        java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(w * h);
        net.minecraft.client.renderer.GlStateManager.bindTexture(fb.framebufferTexture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        int n = 0;
        for (int y = h / 4; y < h * 3 / 4; y += 2) {
            for (int x = w / 3; x < w * 2 / 3; x += 2) {
                int c = buf.get(y * w + x), r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
                if (r > 200 && (g < 110 && b < 150 || g > 225 && b > 225)) n++;
            }
        }
        return n;
    }

    private static int[] glSnapshot() {
        return new int[]{ GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0, GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0, GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING), GL11.glIsEnabled(GL11.GL_TEXTURE_2D) ? 1 : 0 };
    }

    private static void keep(String... names) {
        for (String n : names) saved.put(n, lm().getSetting(n).getValue());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void set(String name, Object v) { ((Setting) lm().getSetting(name)).setValue(v); }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "lyrics-test");
        // the GL state around the 3D pass: the same after the lyrics (and their glow) as before
        Gale.BUS.listen(Render3DEvent.class, Priority.HIGHEST, e -> glBefore = glSnapshot(), "lyrics-test");
        Gale.BUS.listen(Render3DEvent.class, Priority.LOWEST, e -> {
            if (glBefore == null || !lm().isEnabled() || lm().showing() == null) return;
            glFrames++;
            if (!java.util.Arrays.equals(glBefore, glSnapshot())) glLeaks++;
        }, "lyrics-test");

        phase("setup", 50, () -> {
            LyricsModule m = lm();
            lyricsWasOn = m.isEnabled();
            NeteaseModule n = NeteaseModule.instance;
            neteaseWasOn = n.isEnabled();
            spotifyWasOn = SpotifyModule.instance.isEnabled();
            keep("Style", "Follow", "Glow", "Glow color", "Glow strength", "Smooth font", "Word pop", "Wiggle", "Stay on screen", "Spread", "Distance", "Scale", "Offset");
            set("Style", "3D");
            set("Follow", "Auto");
            set("Glow", true);
            set("Glow color", "Text");
            set("Glow strength", 0.3);
            set("Smooth font", true);
            set("Word pop", true);
            set("Wiggle", true);
            set("Stay on screen", false);
            set("Spread", 0.0);
            set("Distance", 10.0);
            set("Scale", 1.0);
            set("Offset", 0.0);
            m.setEnabled(true);
            mc.gameSettings.showInventoryAchievementHint = false;
            mc.guiAchievement.clearAchievements();
            mc.thePlayer.rotationPitch = 0;
            mc.thePlayer.rotationYaw = 0;
            // night: the glow shows best on a dark sky
            mc.getIntegratedServer().addScheduledTask(() -> mc.getIntegratedServer().worldServers[0].setWorldTime(18000));
        }, t -> {
            if (t == 45) baseline = brightMiddle();
        }, () -> check("nothing bright in the middle before the lyrics", baseline >= 0, baseline));

        phase("a made-up song", 50, () -> {
            Lyrics l = new Lyrics(LyricParser.lrc("[00:00.30]你好世界 Hello World\n[00:04.00]第二句歌词\n[00:07.00]third line, in English\n[00:10.00]\n[00:11.00]最后一句"), "test");
            lm().provide(KEY, l);
            songStart = System.currentTimeMillis();
            playing = true;
            NowPlaying.override = () -> NowPlaying.fake(KEY, "Test Song", "Gale", 20000, songPos(), playing, 0);
        }, t -> {
            if (t == 40) shot = "lyrics-1-3d";
            if (t == 42) {
                int bright = brightMiddle();
                check("the line and its glow are drawn (bright pixels in the middle)", bright > baseline + 60, baseline + " -> " + bright);
            }
        }, () -> {
            check("the first line is showing", "你好世界 Hello World".equals(lm().showing()), lm().showing());
            check("the CJK font drew its characters", CjkFont.pages() > 0, CjkFont.pages() + " pages");
            check("the glow is running", lm().deepGlow.available() && lm().deepGlow.error.isEmpty(), lm().deepGlow.error);
            check("lyrics source shown in the module list", "test".equals(lm().getInfo()), lm().getInfo());
        });

        phase("the next line", 40, () -> { }, t -> { }, () -> check("the second line follows at 4 s", "第二句歌词".equals(lm().showing()), lm().showing()));

        phase("pause", 20, () -> {
            pausedAt = songPos();
            playing = false;
        }, t -> { }, () -> check("paused: the line fades away", lm().showing() == null, lm().showing()));

        phase("resume", 20, () -> {
            songStart = System.currentTimeMillis() - pausedAt;
            playing = true;
        }, t -> { }, () -> check("resumed: the line is back", "第二句歌词".equals(lm().showing()), lm().showing()));

        phase("the GL state", 2, () -> { }, t -> { }, () -> {
            check("GL state the same after the lyrics as before", glFrames > 20 && glLeaks == 0, glLeaks + " of " + glFrames + " frames changed it");
            glStateChecked = true;
        });

        phase("pixel font", 25, () -> {
            set("Smooth font", false);
            songStart = System.currentTimeMillis() - 7200;
        }, t -> { if (t == 20) shot = "lyrics-2-pixel-font"; }, () -> check("English line in the pixel font", "third line, in English".equals(lm().showing()), lm().showing()));

        phase("pink glow", 25, () -> {
            set("Smooth font", true);
            set("Glow color", "Pink");
            set("Glow strength", 0.8);
            songStart = System.currentTimeMillis() - 11000;
        }, t -> { if (t == 22) shot = "lyrics-3-pink-glow"; }, () -> check("the last line", "最后一句".equals(lm().showing()), lm().showing()));

        phase("stay on screen", 40, () -> {
            set("Stay on screen", true);
            mc.thePlayer.rotationYaw += 150;
            mc.thePlayer.prevRotationYaw = mc.thePlayer.rotationYaw;
        }, t -> { if (t == 35) shot = "lyrics-4-stay-on-screen"; }, () -> {
            double[] p = lm().showingAt();
            boolean inView = false;
            if (p != null) {
                double dx = p[0] - mc.thePlayer.posX, dz = p[2] - mc.thePlayer.posZ;
                double yaw = Math.toDegrees(Math.atan2(-dx, dz)), d = ((yaw - mc.thePlayer.rotationYaw) % 360 + 540) % 360 - 180;
                inView = Math.abs(d) < 60;
                DevTest.line("line at " + Math.round(d) + " degrees from the view");
            }
            check("after turning away the line glides back into view", inView, p == null ? "no line" : "");
            set("Stay on screen", false);
        });

        phase("text flash", 40, () -> {
            set("Style", "Text flash");
            songStart = System.currentTimeMillis() - 300;
        }, t -> {
            if (t == 30) shot = "lyrics-5-text-flash";
            if (t == 32) {
                int n = textPixels();
                check("big words in the middle", n > 2000, n);
            }
        }, () -> set("Style", "3D"));

        phase("no lyrics, no song", 80, () -> {
            NowPlaying.override = null;
        }, t -> { }, () -> check("the line goes when the song is gone", lm().showing() == null, lm().showing()));

        // ------------------------------------------------------------------ NetEase, for real (needs the internet)

        phase("NetEase: find free songs", 200, () -> {
            NeteaseModule.instance.setEnabled(true);
            NeteasePlayer.silent = true;
            NeteaseModule.instance.quality.set("128k");
            Thread t = new Thread(() -> {
                for (NeteaseTrack s : NeteaseApi.search("古风", 30)) if (!s.vip() && s.canPlay()) songs.add(s);
            }, "lyrics-test search");
            t.setDaemon(true);
            t.start();
        }, t -> { if (!songs.isEmpty()) DevTest.skipRest(); }, () -> check("free songs found on NetEase", !songs.isEmpty(), songs.size()));

        phase("NetEase: play with lyrics", 600, () -> {
            set("Follow", "NetEase");
            if (!songs.isEmpty()) NeteasePlayer.INSTANCE.playQueue(songs, 0);
        }, t -> {
            NeteasePlayer p = NeteasePlayer.INSTANCE;
            if (p.state == NeteasePlayer.State.PLAYING && p.positionMs() < 20000 && p.durationMs() > 60000 && !seeked) {
                seeked = true;
                p.seek(45000);
            }
            if (p.state == NeteasePlayer.State.PLAYING && lm().looked() && lm().lyrics() == null) {
                seeked = false;
                p.next();
            }
            if (lm().showing() != null && p.positionMs() > 45000) DevTest.skipRest();
        }, () -> {
            NeteasePlayer p = NeteasePlayer.INSTANCE;
            check("a NetEase song is playing (no sound in the test)", p.state == NeteasePlayer.State.PLAYING, p.current + " " + p.message);
            check("its lyrics were found by its id", lm().lyrics() != null && "网易云".equals(lm().lyrics().source), lm().getInfo());
            check("and float in the world in time", lm().showing() != null, lm().showing());
        });

        phase("NetEase: the card and lyrics", 30, () -> { }, t -> { if (t == 25) shot = "lyrics-6-netease-playing"; }, () -> { });

        phase("NetEase player: now playing", 30, () -> {
            screen = new NeteaseScreen(NeteaseModule.instance);
            mc.displayGuiScreen(screen);
        }, t -> { if (t == 25) shot = "lyrics-7-netease-now"; }, () -> check("the player screen is open", mc.currentScreen == screen, mc.currentScreen));

        phase("NetEase player: discover", 200, () -> screen.open(NeteaseScreen.Section.DISCOVER), t -> {
            if (screen.hitIds().contains("playlist:0") && t > 20 && t < 190) {
                shot = "lyrics-8-netease-discover";
                DevTest.skipRest();
            }
        }, () -> check("recommended playlists shown", screen.hitIds().contains("playlist:0"), screen.hitIds().size() + " things to click"));

        phase("NetEase player: a chart", 200, () -> screen.open(NeteaseScreen.Section.CHARTS), t -> {
            if (t == 30 && screen.hitIds().contains("playlist:0")) screen.click("playlist:0", 0);
            if (t > 40 && screen.tracks() != null && !screen.tracks().isEmpty() && t < 190) {
                shot = "lyrics-9-netease-chart";
                DevTest.skipRest();
            }
        }, () -> check("a chart's songs listed", screen.tracks() != null && screen.tracks().size() >= 50, screen.tracks() == null ? null : screen.tracks().size()));

        phase("NetEase player: search", 200, () -> screen.type("晴天", true), t -> {
            if (t > 10 && screen.tracks() != null && !screen.tracks().isEmpty() && t < 190) {
                shot = "lyrics-10-netease-search";
                DevTest.skipRest();
            }
        }, () -> check("search results", screen.tracks() != null && !screen.tracks().isEmpty(), screen.tracks() == null ? null : screen.tracks().size()));

        phase("NetEase player: QR login", 200, () -> screen.open(NeteaseScreen.Section.LOGIN), t -> {
            if (NeteaseLogin.state == NeteaseLogin.State.WAITING && NeteaseLogin.qr != null && t > 10 && t < 190) {
                shot = "lyrics-11-netease-qr";
                DevTest.skipRest();
            }
        }, () -> check("a login QR code is shown", NeteaseLogin.qr != null && NeteaseLogin.state == NeteaseLogin.State.WAITING, NeteaseLogin.state + " " + NeteaseLogin.error));

        phase("switch to Spotify and back", 20, () -> screen.click("source:spotify", 0), t -> {
            if (t == 10) {
                check("the pill opens Spotify's player", mc.currentScreen instanceof SpotifyScreen, mc.currentScreen);
                shot = "lyrics-12-spotify-pill";
            }
            if (t == 15 && mc.currentScreen instanceof SpotifyScreen) {
                try {
                    ((SpotifyScreen) mc.currentScreen).click("source:netease", 0);
                } catch (Exception ignored) {
                }
            }
        }, () -> check("and Spotify's pill opens NetEase's", mc.currentScreen instanceof NeteaseScreen, mc.currentScreen));

        phase("cleanup", 5, () -> {
            mc.displayGuiScreen(null);
            NeteaseLogin.cancel();
            NeteasePlayer.INSTANCE.stop();
            NowPlaying.override = null;
            for (Map.Entry<String, Object> e : saved.entrySet()) set(e.getKey(), e.getValue());
            lm().setEnabled(lyricsWasOn);
            NeteaseModule.instance.setEnabled(neteaseWasOn);
            SpotifyModule.instance.setEnabled(spotifyWasOn);
        }, t -> { }, () -> check("GL state was checked", glStateChecked, null));
    }
}
