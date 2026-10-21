package gale.devtest;

import gale.Gale;
import gale.core.Notifications;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.hud.HudBox;
import gale.hud.HudElement;
import gale.hud.HudLayout;
import gale.module.Module;
import gale.module.impl.client.HUD;
import gale.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.ClientCommandHandler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.IntConsumer;

/**
 * The HUD editor (gradlew runClient -Phudtest): with the chat open, HUD elements move (and snap), resize by their
 * corner and the wheel, go back with a right click, and the bottom ones rise above the chat box; Island and the kill
 * picture move through their settings; the module list slides; everything is saved in hud.json.
 * Screenshots in run/screenshots/hud-*.png.
 */
final class HudTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String shot;
    private static float sw, sh, before;
    private static boolean islandWasOn, killWasOn, hudWasOn, guided;
    private static float[] mid;

    private HudTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, detail == null ? "" : String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("HUD " + name, ticks, enter, tick, exit));
    }

    private static HudBox box(String id) { return (HudBox) HudLayout.get(id); }

    private static void screen() {
        ScaledResolution sr = new ScaledResolution(mc);
        sw = sr.getScaledWidth();
        sh = sr.getScaledHeight();
    }

    /** Presses at (x, y), moves to (tx, ty), lets go: as the mouse would in the chat screen. */
    private static boolean drag(float x, float y, float tx, float ty) {
        boolean took = HudLayout.mouse(x, y, 0, true, 0, sw, sh);
        HudLayout.mouse((x + tx) / 2f, (y + ty) / 2f, -1, false, 0, sw, sh);
        HudLayout.mouse(tx, ty, -1, false, 0, sw, sh);
        guided = !HudLayout.guides().isEmpty();
        HudLayout.mouse(tx, ty, 0, false, 0, sw, sh);
        return took;
    }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "hud-test");

        phase("the elements", 20, () -> {
            HudLayout.resetAll();
            Module island = Gale.modules().get("Island"), kill = Gale.modules().get("KillEffects");
            islandWasOn = island.isEnabled();
            killWasOn = kill.isEnabled();
            Module hud = Gale.modules().get("HUD");
            hudWasOn = hud.isEnabled();
            hud.setEnabled(true);
            for (String s : new String[]{ "Watermark", "Module list", "Info", "Notifications" }) ((gale.module.setting.BoolSetting) hud.getSetting(s)).set(true);
            island.setEnabled(true);
            kill.setEnabled(true);
            mc.gameSettings.showInventoryAchievementHint = false;
        }, t -> { }, () -> {
            StringBuilder ids = new StringBuilder();
            for (HudElement e : HudLayout.elements()) if (e.listed()) ids.append(e.id).append(' ');
            String s = ids.toString();
            check("the HUD's parts, Island and the kill picture are elements", s.contains("watermark") && s.contains("modulelist") && s.contains("info")
                && s.contains("island") && s.contains("killeffects"), s);
        });

        phase("the chat opens the editor", 30, () -> mc.displayGuiScreen(new GuiChat()), t -> {
            if (t == 25) shot = "hud-1-editor";
        }, () -> {
            screen();
            check("the chat is open: editing", HudLayout.editing(), mc.currentScreen);
            float[] b = box("info").bounds(sw, sh);
            check("bottom elements rise above the chat box", HudLayout.chatLift() > 13f && b[1] + b[3] < sh - 14f, HudLayout.chatLift() + " lift, info at " + b[1]);
        });

        phase("drag the watermark to the middle", 5, () -> {
            screen();
            float[] b = box("watermark").bounds(sw, sh);
            // dropped 3 pixels off the middle: it snaps onto it
            drag(b[0] + 2, b[1] + 2, sw / 2f - b[2] / 2f + 3 + 2, 60 + 2);
        }, t -> { }, () -> {
            HudBox w = box("watermark");
            float[] b = w.bounds(sw, sh);
            check("it snaps to the middle of the screen (with a guide line)", Math.abs(b[0] + b[2] / 2f - sw / 2f) < 0.6f && guided, (b[0] + b[2] / 2f) + " vs " + sw / 2f);
            check("and is anchored in the middle now", w.anchorX() == HudBox.CENTER && w.anchorY() == HudBox.TOP, w.anchorX() + "," + w.anchorY());
            String saved = "";
            try {
                saved = new String(Files.readAllBytes(new File(Gale.config().dir(), "hud.json").toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            check("saved in hud.json", saved.contains("\"watermark\"") && saved.contains("\"anchorX\": 1"), saved.length() + " characters");
        });

        phase("move the module list to the bottom left", 25, () -> {
            float[] b = box("modulelist").bounds(sw, sh);
            drag(b[0] + 3, b[1] + 3, 30, sh - 70);
        }, t -> { if (t == 20) shot = "hud-2-moved"; }, () -> {
            HudBox l = box("modulelist");
            check("the module list is anchored bottom left (it stacks upward, left aligned)", l.anchorX() == HudBox.LEFT && l.anchorY() == HudBox.BOTTOM && !l.right() && l.bottom(),
                l.anchorX() + "," + l.anchorY());
        });

        phase("resize by the corner", 5, () -> {
            HudBox w = box("watermark");
            float[] b = w.bounds(sw, sh);
            before = w.scale();
            // its handle: the bottom right corner (it is anchored in the middle, at the top)
            drag(b[0] + b[2], b[1] + b[3], b[0] + b[2] + 30, b[1] + b[3] + 10);
        }, t -> { }, () -> {
            HudBox w = box("watermark");
            check("dragging the corner makes it bigger", w.scale() > before * 1.2f, before + " -> " + w.scale());
        });

        phase("the wheel", 5, () -> {
            float[] b = box("info").bounds(sw, sh);
            before = box("info").scale();
            HudLayout.mouse(b[0] + 3, b[1] + 3, -1, false, 120, sw, sh);
        }, t -> { }, () -> check("the wheel over it resizes it", Math.abs(box("info").scale() - before * 1.08f) < 0.01f, before + " -> " + box("info").scale()));

        phase("right click puts it back", 5, () -> {
            float[] b = box("watermark").bounds(sw, sh);
            HudLayout.mouse(b[0] + b[2] / 2f, b[1] + b[3] / 2f, 1, true, 0, sw, sh);
        }, t -> { }, () -> {
            HudBox w = box("watermark");
            float[] b = w.bounds(sw, sh);
            check("back top left, full size", w.anchorX() == HudBox.LEFT && w.anchorY() == HudBox.TOP && Math.abs(b[0] - 4) < 0.1f && w.scale() == 1f, b[0] + " " + w.scale());
        });

        phase("Island and the kill picture move through their settings", 10, () -> {
            HudElement island = HudLayout.get("island");
            float[] b = island.bounds(sw, sh);
            drag(b[0] + 4, b[1] + 4, b[0] + 4 + 40, b[1] + 4 + 12);
            HudElement kill = HudLayout.get("killeffects");
            float[] k = kill.bounds(sw, sh);
            mid = k;
            drag(k[0] + 10, k[1] + 10, 40, 60);
        }, t -> { }, () -> {
            NumberSetting xo = (NumberSetting) Gale.modules().get("Island").getSetting("X offset"), yo = (NumberSetting) Gale.modules().get("Island").getSetting("Y offset");
            check("Island's X / Y offset follow the drag", Math.abs(xo.get() - 40) <= 1 && Math.abs(yo.get() - 12) <= 1, xo.get() + ", " + yo.get());
            NumberSetting kx = (NumberSetting) Gale.modules().get("KillEffects").getSetting("X");
            check("the kill picture's X follows the drag", kx.get() < 50, kx.get() + "% (was " + mid[0] + ")");
        });

        phase("the chat closes", 30, () -> mc.displayGuiScreen(null), t -> { }, () -> {
            check("the editor stops and the bottom elements come back down", !HudLayout.editing() && HudLayout.chatLift() < 0.5f, HudLayout.chatLift());
        });

        phase("the module list slides", 34, () -> {
            HUD hud = Gale.modules().get(HUD.class);
            HudLayout.resetAll();
            Gale.modules().get("Trajectories").setEnabled(false);
        }, t -> {
            HUD hud = Gale.modules().get(HUD.class);
            Module m = Gale.modules().get("Trajectories");
            if (t == 2) m.setEnabled(true);
            if (t == 4) {
                float p = hud.listProgress(m);
                check("a module switched on slides in (not there at once)", p > 0f && p < 1f, p);
                shot = "hud-3-sliding";
            }
            if (t == 20) {
                check("and is all the way in after a moment", hud.listProgress(m) == 1f, hud.listProgress(m));
                m.setEnabled(false);
            }
            if (t == 22) {
                float p = hud.listProgress(m);
                check("switched off it slides out (still drawn for a moment)", p >= 0f && p < 1f, p);
            }
            if (t == 33) check("and is gone after", hud.listProgress(m) == -1f, hud.listProgress(m));
        }, () -> { });

        phase("notifications", 20, () -> {
            Notifications.info("HUD test one");
            Notifications.success("HUD test two");
            Notifications.warn("HUD test three");
        }, t -> { if (t == 12) shot = "hud-4-notifications"; }, () -> check("notifications are an element too (drawn in their box)", HudLayout.get("notifications").drawnRecently(), ""));

        phase("/gale hud reset", 5, () -> {
            float[] b = box("modulelist").bounds(sw, sh);
            HudLayout.mouse(b[0] + 3, b[1] + 3, 0, true, 0, sw, sh);
            HudLayout.mouse(40, 80, -1, false, 0, sw, sh);
            HudLayout.mouse(40, 80, 0, false, 0, sw, sh);
            ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/gale hud reset");
        }, t -> { }, () -> {
            HudBox l = box("modulelist");
            check("puts everything back", l.anchorX() == HudBox.RIGHT && l.anchorY() == HudBox.TOP && l.scale() == 1f, l.anchorX() + "," + l.anchorY());
            Gale.modules().get("Island").setEnabled(islandWasOn);
            Gale.modules().get("KillEffects").setEnabled(killWasOn);
            Gale.modules().get("HUD").setEnabled(hudWasOn);
            for (String s : new String[]{ "X offset", "Y offset" }) Gale.modules().get("Island").getSetting(s).reset();
            Gale.modules().get("KillEffects").getSetting("X").reset();
            Gale.modules().get("KillEffects").getSetting("Y").reset();
            Gale.config().save();
        });
    }
}
