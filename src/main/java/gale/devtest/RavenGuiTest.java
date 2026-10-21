package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.client.ClickGuiModule;
import gale.module.setting.NumberSetting;
import gale.ui.raven.RavenCategory;
import gale.ui.raven.RavenComponent;
import gale.ui.raven.RavenDescription;
import gale.ui.raven.RavenGui;
import gale.ui.raven.RavenModule;
import gale.ui.raven.RavenSlider;
import gale.ui.raven.RavenToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;

import java.util.function.IntConsumer;

/**
 * The Raven B4 style of the settings screen (gradlew runClient -Pravengui): B4's ClickGui over Gale's modules. Opens
 * it, opens a frame and a module, switches a module, drags a slider and a frame, and checks the GUI scale comes back.
 * Screenshots in run/screenshots/raven-*.png.
 */
final class RavenGuiTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String shot;
    private static int scaleBefore;
    // the Raven scale set in this game (a preference): Normal for the test, then back
    private static String ravenScaleBefore;
    private static boolean aimWasOn, clickedKillAura;

    private RavenGuiTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Raven " + name, ticks, enter, tick, exit));
    }

    private static ClickGuiModule settings() { return Gale.modules().get(ClickGuiModule.class); }

    private static RavenGui gui() { return mc.currentScreen instanceof RavenGui ? (RavenGui) mc.currentScreen : null; }

    private static RavenCategory frame(Category c) {
        RavenGui g = gui();
        if (g != null) for (RavenCategory f : g.categories) if (f.category == c) return f;
        return null;
    }

    private static RavenModule row(RavenCategory f, String module) {
        if (f != null) for (RavenComponent c : f.modules) if (((RavenModule) c).mod.getName().equals(module)) return (RavenModule) c;
        return null;
    }

    private static void click(RavenModule m, int button) throws Exception {
        m.hovered = true;
        gui().mouseClicked((int) ((m.left() + m.right()) / 2), (int) ((m.top() + m.bottom()) / 2), button);
    }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "raven-test");

        phase("opens in B4's look", 50, () -> {
            settings().style.set("Raven B4");
            ravenScaleBefore = settings().ravenScale.get();
            settings().ravenScale.set("Normal");
            scaleBefore = mc.gameSettings.guiScale;
            ClickGuiModule.open();
        }, t -> { if (t == 45) shot = "raven-1-open"; }, () -> {
            check("the Raven B4 style opens B4's GUI", gui() != null, mc.currentScreen);
            check("at B4's GUI scale (Normal = 2)", mc.gameSettings.guiScale == 2, mc.gameSettings.guiScale);
            int want = 0;
            for (Category c : Category.values()) if (!Gale.modules().in(c).isEmpty()) want++;
            check("a frame per category with modules", gui() != null && gui().categories.size() == want, (gui() == null ? -1 : gui().categories.size()) + " of " + want);
        });

        phase("a frame and a module open", 40, () -> {
            RavenCategory combat = frame(Category.COMBAT);
            if (combat != null) combat.setOpened(true);
        }, t -> {
            // (a frame takes no clicks while it opens, as in B4)
            RavenCategory combat = frame(Category.COMBAT);
            if (!clickedKillAura && t >= 10 && combat != null && !combat.opening) {
                clickedKillAura = true;
                try {
                    click(row(combat, "KillAura"), 1);
                } catch (Exception e) {
                    DevTest.line("click failed: " + e);
                }
            }
            if (t == 35) shot = "raven-2-killaura";
            if (t == 38 && !clickedKillAura) DevTest.line("the combat frame was still opening: " + (combat == null ? "none" : combat.opening + " " + combat.closing));
        }, () -> {
            RavenModule ka = row(frame(Category.COMBAT), "KillAura");
            check("the combat frame opens", frame(Category.COMBAT) != null && frame(Category.COMBAT).opened, "");
            check("right click opens KillAura's settings", ka != null && ka.opened, "");
            int toggles = 0, sliders = 0;
            if (ka != null) for (RavenComponent c : ka.settings) {
                if (c instanceof RavenToggle) toggles++;
                if (c instanceof RavenSlider) sliders++;
            }
            check("its switches and sliders", toggles > 0 && sliders > 0, toggles + " switches, " + sliders + " sliders");
        });

        phase("a left click switches a module", 10, () -> {
            Module aim = Gale.modules().get("AimAssist");
            aimWasOn = aim.isEnabled();
            try {
                click(row(frame(Category.COMBAT), "AimAssist"), 0);
            } catch (Exception e) {
                DevTest.line("click failed: " + e);
            }
        }, t -> { }, () -> {
            Module aim = Gale.modules().get("AimAssist");
            check("left click switches AimAssist", aim.isEnabled() != aimWasOn, aimWasOn + " -> " + aim.isEnabled());
            aim.setEnabled(aimWasOn);
        });

        phase("a slider drags", 5, () -> {
            RavenModule ka = row(frame(Category.COMBAT), "KillAura");
            RavenSlider slider = null;
            if (ka != null) for (RavenComponent c : ka.settings) if (c instanceof RavenSlider && ((RavenSlider) c).setting instanceof NumberSetting && ((RavenSlider) c).setting.isVisible()) {
                slider = (RavenSlider) c;
                break;
            }
            if (slider == null) {
                check("a slider drags to its end", false, "no slider");
                return;
            }
            NumberSetting n = (NumberSetting) slider.setting;
            double before = n.get();
            slider.updatePosition(0, 0);
            try {
                gui().mouseClicked((int) slider.x + 10, (int) slider.y + 8, 0);
            } catch (Exception e) {
                DevTest.line("click failed: " + e);
            }
            slider.updatePosition((int) (slider.x + 200), (int) slider.y + 8);
            gui().mouseReleased(0, 0, 0);
            check("dragging " + n.getName() + " to the right end sets its maximum", n.get() == n.max, before + " -> " + n.get() + " (max " + n.max + ")");
            n.set(before);
        }, t -> { }, () -> { });

        phase("a frame drags", 20, () -> frame(Category.COMBAT).setOpened(false), t -> {
            if (t != 15) return;
            RavenCategory render = frame(Category.RENDER);
            float x = render.x, y = render.y;
            try {
                gui().mouseClicked((int) render.x + 40, (int) render.y + 6, 0);
            } catch (Exception e) {
                DevTest.line("click failed: " + e);
            }
            render.mousePosition((int) render.x + 40 + 150, (int) render.y + 6 + 30);
            gui().mouseReleased(0, 0, 0);
            check("dragging the render frame's title moves it", Math.abs(render.x - (x + 150)) < 1 && Math.abs(render.y - (y + 30)) < 1,
                x + "," + y + " -> " + render.x + "," + render.y);
            render.setOpened(true);
        }, () -> { });

        // (a frame takes no clicks while it opens, as in B4)
        phase("the render frame", 45, () -> { }, t -> {
            if (t == 12) {
                try {
                    click(row(frame(Category.RENDER), "Nametags"), 1);
                } catch (Exception e) {
                    DevTest.line("click failed: " + e);
                }
            }
            if (t == 40) shot = "raven-3-render";
        }, () -> {
            RavenModule tags = row(frame(Category.RENDER), "Nametags");
            int titles = 0;
            if (tags != null) for (RavenComponent c : tags.settings) if (c instanceof RavenDescription) titles++;
            check("Nametags' settings open, with B4's section title (Item settings)", tags != null && tags.opened && titles == 1, titles + " titles");
        });

        phase("closing gives the GUI scale back", 10, () -> mc.displayGuiScreen(null), t -> { }, () -> {
            check("the GUI scale is back", mc.gameSettings.guiScale == scaleBefore, scaleBefore + " -> " + mc.gameSettings.guiScale);
            check("frames stay where they were for the next time", RavenGui.instance().categories.size() > 0
                && RavenGui.instance().categories.stream().anyMatch(c -> c.category == Category.RENDER && c.opened), "");
        });

        phase("Gale's own style again", 10, () -> {
            settings().style.set("Gale");
            if (ravenScaleBefore != null) settings().ravenScale.set(ravenScaleBefore);
            ClickGuiModule.open();
        }, t -> { }, () -> {
            check("the Gale style opens Gale's GUI", mc.currentScreen instanceof gale.ui.ClickGui, mc.currentScreen);
            mc.displayGuiScreen(null);
            Gale.config().save();
        });
    }
}
