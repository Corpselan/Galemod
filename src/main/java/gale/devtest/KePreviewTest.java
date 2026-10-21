package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.TickEvent;
import gale.kill.gd.GdKillEffect;
import gale.module.impl.render.KillEffects;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Interactive preview of the five imported skins. Development only; excluded from release jars. */
final class KePreviewTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] NAMES = {"CYRAX", "Reaver", "Forsaken", "Mystbloom", "Ayakashi"};
    private static final String[] IDS = {"00038", "00039", "00040", "00041", "00042"};
    private static final boolean[] KEYS = new boolean[4];
    private static final boolean[] SEEN = new boolean[30];
    private static final List<String> REPORT = new ArrayList<>();
    private static KillEffects fx;
    private static GdKillEffect gd;
    private static int ticks, settled, skin, stage = 1, visited, frameSamples;
    private static boolean launched, ready, autoplay = true, checked;
    private static long started, renderStart, renderNanos, frames;
    private static String screenshot;

    static void start() {
        line("New KE preview: CYRAX, Reaver, Forsaken, Mystbloom, Ayakashi / stages 1-6");
        line("F6 next skin; F7 next kill tier; F8 replay; F9 autoplay on/off. Window stays open.");
        Gale.BUS.listen(TickEvent.Post.class, Priority.MONITOR, e -> tick(), "ke-preview");
        Gale.BUS.listen(Render2DEvent.class, Priority.HIGHEST, e -> renderStart = System.nanoTime(), "ke-preview");
        Gale.BUS.listen(Render2DEvent.class, Priority.MONITOR, KePreviewTest::render, "ke-preview");
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (screenshot == null || mc.theWorld == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, screenshot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            screenshot = null;
        }, "ke-preview");
    }

    private static void tick() {
        ticks++;
        if (!launched) {
            if (ticks < 40 || !(mc.currentScreen instanceof GuiMainMenu)) return;
            launched = true;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.difficulty = EnumDifficulty.PEACEFUL;
            mc.gameSettings.renderDistanceChunks = 4;
            mc.gameSettings.limitFramerate = 120;
            mc.gameSettings.enableVsync = false;
            mc.gameSettings.guiScale = 2;
            // Unique save name: repeated previews never delete or replace a world.
            String save = "ke-preview-" + System.currentTimeMillis();
            line("Creating creative flat world " + save + " in " + mc.mcDataDir.getAbsolutePath());
            mc.launchIntegratedServer(save, "KE Effects Preview", new WorldSettings(1L,
                WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!ready) {
            if (!mc.thePlayer.onGround || mc.currentScreen != null || ++settled < 40) return;
            fx = Gale.modules().get(KillEffects.class);
            ((ModeSetting) fx.getSetting("Theme")).set("GD656");
            ((BoolSetting) fx.getSetting("Sound")).set(true);
            ((NumberSetting) fx.getSetting("Volume")).set(.6);
            ((NumberSetting) fx.getSetting("Size")).set(160);
            ((NumberSetting) fx.getSetting("X")).set(50);
            ((NumberSetting) fx.getSetting("Y")).set(62);
            mc.gameSettings.hideGUI = false;
            mc.gameSettings.thirdPersonView = 0;
            fx.setEnabled(true);
            gd = (GdKillEffect) field(fx, "gdEffect");
            ready = true;
            mc.thePlayer.addChatMessage(new ChatComponentText("KE Preview: F6 skin / F7 tier / F8 replay / F9 auto"));
            replay();
        }
        if (mc.currentScreen != null || mc.isGamePaused()) {
            // Opening a GUI pauses the automatic preview without consuming its slot.
            started += 50;
            return;
        }
        if (pressed(0, Keyboard.KEY_F6)) { skin = (skin + 1) % NAMES.length; replay(); }
        if (pressed(1, Keyboard.KEY_F7)) { stage = stage % 6 + 1; replay(); }
        if (pressed(2, Keyboard.KEY_F8)) replay();
        if (pressed(3, Keyboard.KEY_F9)) { autoplay = !autoplay; replay(); }
        long elapsed = System.currentTimeMillis() - started;
        if (!checked && elapsed >= 650) {
            checked = true;
            boolean exists = true;
            try (InputStream in = mc.getResourceManager().getResource(new ResourceLocation("gd656killicon",
                "sounds/" + GdKillEffect.soundName(IDS[skin], stage) + ".ogg")).getInputStream()) {
                exists = in.read() >= 0;
            } catch (Exception ex) { exists = false; line("Sound resource error: " + ex); }
            ISound playing = (ISound) field(gd, "playing");
            boolean audible = playing != null && mc.getSoundHandler().isSoundPlaying(playing);
            int error = GL11.glGetError();
            line((exists && audible && error == GL11.GL_NO_ERROR && frameSamples > 0 ? "PASS " : "FAIL ")
                + NAMES[skin] + " " + stage + "K / sound=" + audible + ", resource=" + exists
                + ", GL=" + error + ", frames=" + frameSamples + ", listeners=" + Gale.BUS.errorCount());
            int index = skin * 6 + stage - 1;
            if (!SEEN[index]) { SEEN[index] = true; visited++; }
            screenshot = "ke-" + NAMES[skin].toLowerCase(java.util.Locale.ROOT) + "-" + stage;
            if (visited == 30) line("All 30 preview slots visited. Game remains open for manual inspection.");
            saveReport();
        }
        if (autoplay && elapsed >= 3200) {
            if (++stage > 6) { stage = 1; skin = (skin + 1) % NAMES.length; }
            replay();
        }
    }

    private static void replay() {
        ((ModeSetting) fx.getSetting("GD preset")).set("Valorant " + NAMES[skin]);
        fx.preview(stage);
        started = System.currentTimeMillis();
        checked = false;
        frameSamples = 0;
        line("Preview " + NAMES[skin] + " " + stage + "K");
    }

    private static boolean pressed(int index, int code) {
        boolean down = Keyboard.isKeyDown(code), edge = down && !KEYS[index];
        KEYS[index] = down;
        return edge;
    }

    private static void render(Render2DEvent e) {
        if (!ready) return;
        renderNanos += System.nanoTime() - renderStart;
        frames++; frameSamples++;
        GlStateManager.pushMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1, 1, 1, 1);
        mc.fontRendererObj.drawStringWithShadow("KE PREVIEW  |  " + NAMES[skin] + "  " + stage + " KILL  |  AUTO "
            + (autoplay ? "ON" : "OFF"), 12, 12, 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow("F6 skin  /  F7 kill tier  /  F8 replay  /  F9 auto", 12, 26, 0xFFB8CB);
        mc.fontRendererObj.drawStringWithShadow("Checked " + visited + "/30  |  FPS " + Minecraft.getDebugFPS(), 12, 40, 0xC4DBEB);
        GlStateManager.popMatrix();
    }

    private static Object field(Object object, String name) {
        try {
            java.lang.reflect.Field f = object.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(object);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static void line(String text) { REPORT.add(text); Gale.LOG.info("[ke-preview] {}", text); }

    private static void saveReport() {
        try {
            List<String> lines = new ArrayList<>(REPORT);
            lines.add("Render2D event CPU average (all HUD listeners, not GPU): "
                + (frames == 0 ? 0 : renderNanos / 1e6 / frames) + " ms / " + frames + " frames");
            Files.write(new File(mc.mcDataDir, "ke-preview-report.txt").toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ex) { Gale.LOG.error("[ke-preview] Cannot write report", ex); }
    }
}
