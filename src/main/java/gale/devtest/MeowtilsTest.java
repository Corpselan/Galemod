package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.meowtils.ExtensionLoader;
import gale.meowtils.ExtensionModule;
import gale.meowtils.api.config.Config;
import gale.meowtils.api.event.ClientTickEvent;
import gale.meowtils.api.event.api.EventTarget;
import gale.meowtils.api.extension.Extension;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import gale.module.setting.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Meowtils extensions in Gale (gradlew runClient -Pmeowtilstest), with Furiah's Visuals in run/galemod/extensions: it
 * loads, switching its module starts and pauses it (its hooks in and out), its /furiah command and menu, a reload; and
 * a made-up extension for every kind of value (each a Gale setting, both ways), its listener and button.
 * Screenshots in run/screenshots/meowtils-*.png.
 */
final class MeowtilsTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String FV = "FuriahVisuals-1.3.0.meowtils", NAME = "Furiah's Visuals";
    private static String shot;
    private static Demo demo;
    private static int ticksBefore;
    private static BlockPos spawn;

    private MeowtilsTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Meowtils " + name, ticks, enter, tick, exit));
    }

    private static ExtensionModule fv() { return (ExtensionModule) Gale.modules().get(NAME); }

    private static ExtensionLoader.Loaded fvFile() {
        for (ExtensionLoader.Loaded l : ExtensionLoader.loaded()) if (l.file.getName().equals(FV)) return l;
        return null;
    }

    // FuriahsVisuals.instance.isRunning() and Install.installed(), in the extension's own classes
    private static Object call(String cls, String method, boolean onInstance) {
        try {
            ClassLoader loader = fv().ext.getClass().getClassLoader();
            Class<?> c = Class.forName(cls, false, loader);
            Object target = onInstance ? c.getField("instance").get(null) : null;
            if (onInstance && target == null) return null;
            return c.getMethod(method).invoke(target);
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private static Object running() { return call("meowtils.furiahvisuals.core.FuriahsVisuals", "isRunning", true); }

    private static Object hooksIn() { return call("meowtils.furiahvisuals.hook.Install", "installed", false); }

    private static final String[] SECTIONS = { "weapons", "dynamic_skies", "target_aura", "hit_effect", "kill_effects" };

    // its own sections (first start: all off but the WaterMark), switched through its module list
    private static Object section(String id, Boolean on) {
        try {
            ClassLoader loader = fv().ext.getClass().getClassLoader();
            Object inst = Class.forName("meowtils.furiahvisuals.core.FuriahsVisuals", false, loader).getField("instance").get(null);
            Object list = inst.getClass().getMethod("getModules").invoke(inst);
            Object m = list.getClass().getMethod("get", String.class).invoke(list, id);
            if (on != null) m.getClass().getMethod("setEnabled", boolean.class).invoke(m, on);
            return m.getClass().getMethod("isEnabled").invoke(m);
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private static String profiler() { return mc.mcProfiler.getClass().getSimpleName(); }

    private static void command(String c) { ClientCommandHandler.instance.executeCommand(mc.thePlayer, c); }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "meowtils-test");

        phase("Furiah's Visuals loads", 5, () -> { }, t -> { }, () -> {
            ExtensionLoader.Loaded l = fvFile();
            check("the extension file loads", l != null && l.error == null, l == null ? "not found" : l.error == null ? l.modules : l.error);
            Module m = fv();
            check("its module is a Gale module in Extensions", m != null && m.getCategory() == Category.EXTENSIONS, m == null ? null : m.getCategory());
            check("with its tooltip and author as the description", m != null && m.getDescription().contains("Furiah"), m == null ? null : m.getDescription());
            check("its button is a setting", m != null && m.getSetting("Open menu") instanceof BoolSetting, m == null ? null : m.getSettings());
        });

        phase("switching it on starts it", 90, () -> {
            spawn = new BlockPos(mc.thePlayer);
            DevTest.server(p -> {
                p.worldObj.setWorldTime(6000);
                p.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
                p.inventory.currentItem = 0;
            });
            mc.thePlayer.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
            mc.thePlayer.inventory.currentItem = 0;
            mc.thePlayer.rotationPitch = 10f;
            fv().setEnabled(true);
        }, t -> { if (t == 85) shot = "meowtils-1-on"; }, () -> {
            check("the extension's module runs (its onEnable)", fv().ext.isOn(), "");
            check("Furiah's Visuals is running", Boolean.TRUE.equals(running()), running());
            check("its hooks are in", Boolean.TRUE.equals(hooksIn()), hooksIn());
            check("its frame hooks: the profiler is its own", profiler().equals("FvProfiler"), profiler());
            check("its /furiah command is registered", ClientCommandHandler.instance.getCommands().containsKey("furiah"), "");
        });

        phase("its sections and practice dummy", 110, () -> {
            command("/furiah dummy");
            for (String id : SECTIONS) section(id, true);
        }, t -> {
            if (t == 70) shot = "meowtils-2-sections";
            if (t == 80) command("/furiah dummy kill");
            if (t == 88) shot = "meowtils-3-kill";
        }, () -> {
            StringBuilder on = new StringBuilder();
            boolean all = true;
            for (String id : SECTIONS) {
                Object v = section(id, null);
                all &= Boolean.TRUE.equals(v);
                on.append(id).append('=').append(v).append(' ');
            }
            check("its sections switch on (3D weapons, sky, target aura, hit and kill effects)", all, on);
            check("still running after its commands", Boolean.TRUE.equals(running()), running());
        });

        phase("its menu (the Open menu button)", 50, () -> ((BoolSetting) fv().getSetting("Open menu")).set(true), t -> {
            if (t == 45) shot = "meowtils-4-menu";
        }, () -> {
            String screen = mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName();
            check("the button opens its menu", screen.startsWith("meowtils.furiahvisuals"), screen);
            check("the button goes back off", !((BoolSetting) fv().getSetting("Open menu")).get(), "");
            mc.displayGuiScreen(null);
        });

        phase("a made-up extension: values are Gale settings", 30, () -> {
            demo = new Demo();
            Extension.registerModule(demo);
            // what an earlier run saved for it comes back (Gale's config): start from its defaults
            for (Setting<?> s : Gale.modules().get("Meow Demo").getSettings()) s.reset();
        }, t -> {
            Module m = Gale.modules().get("Meow Demo");
            if (m == null) return;
            if (t == 2) {
                check("Flag is a switch", m.getSetting("Flag") instanceof BoolSetting && ((BoolSetting) m.getSetting("Flag")).get(), m.getSetting("Flag"));
                Setting<?> sp = m.getSetting("Speed");
                check("Speed is a slider (0 to 10, step 0.5, x)", sp instanceof NumberSetting && ((NumberSetting) sp).min == 0 && ((NumberSetting) sp).max == 10
                    && ((NumberSetting) sp).step == 0.5 && ((NumberSetting) sp).get() == 2.5 && "x".equals(((NumberSetting) sp).getSuffix()), sp);
                Setting<?> st = m.getSetting("Style");
                check("Style is a mode (A B C, on B)", st instanceof ModeSetting && ((ModeSetting) st).getModes().equals(Arrays.asList("A", "B", "C"))
                    && ((ModeSetting) st).is("B"), st);
                check("Note is text", m.getSetting("Note") instanceof TextSetting && "hi".equals(((TextSetting) m.getSetting("Note")).get()), m.getSetting("Note"));
                check("Tint is a colour (red)", m.getSetting("Tint") instanceof ColorSetting && (((ColorSetting) m.getSetting("Tint")).getValue() & 0xFFFFFF) == 0xFF0000,
                    m.getSetting("Tint"));
                Setting<?> inner = m.getSetting("Inner");
                check("a group's value is a setting described by the group", inner instanceof BoolSetting && "Group".equals(inner.getDescription()), inner);
                ((NumberSetting) sp).set(7.5);
                ((ModeSetting) st).set("C");
                ((ColorSetting) m.getSetting("Tint")).set(0xFF00FF00);
                ((BoolSetting) m.getSetting("Press")).set(true);
                check("Gale settings write the extension's fields", demo.speed == 7.5 && "C".equals(demo.style) && demo.r == 0 && demo.g == 255 && demo.b == 0,
                    demo.speed + " " + demo.style + " " + demo.r + "," + demo.g + "," + demo.b);
                check("the button runs once and goes back off", demo.pressed == 1 && !((BoolSetting) m.getSetting("Press")).get(), demo.pressed);
                demo.flag = false;
                m.setEnabled(true);
            }
            if (t == 5) {
                check("the extension changing a field shows in the setting", !((BoolSetting) m.getSetting("Flag")).get(), "");
                check("switching it on runs its onEnable", demo.enables == 1, demo.enables);
            }
            if (t == 25) {
                check("its @EventTarget listener gets Meowtils' ClientTickEvent while on", demo.ticks >= 15, demo.ticks + " ticks");
                m.setEnabled(false);
                ticksBefore = demo.ticks;
            }
        }, () -> check("and not while off", demo.ticks == ticksBefore, ticksBefore + " -> " + demo.ticks));

        phase("switching it off pauses it", 20, () -> fv().setEnabled(false), t -> { }, () -> {
            check("Furiah's Visuals is paused", Boolean.FALSE.equals(running()), running());
            check("its hooks are out (the game's profiler is back)", Boolean.FALSE.equals(hooksIn()) && !profiler().equals("FvProfiler"), hooksIn() + " " + profiler());
        });

        phase("reload", 80, () -> fv().setEnabled(true), t -> {
            if (t == 20) command("/gale extensions reload");
            if (t == 75) shot = "meowtils-5-reloaded";
        }, () -> {
            ExtensionLoader.Loaded l = fvFile();
            check("after /gale extensions reload it is loaded again", l != null && l.error == null && fv() != null, l == null ? "not found" : l.error);
            check("its module is on again (as it was)", fv() != null && fv().isEnabled() && Boolean.TRUE.equals(running()), running());
        });

        phase("finish", 10, () -> {
            if (fv() != null && Boolean.TRUE.equals(running())) for (String id : SECTIONS) section(id, false);
            if (fv() != null) fv().setEnabled(false);
        }, t -> { }, () -> Gale.config().save());
    }

    /** A made-up extension with every kind of value, written the way a Meowtils extension is. */
    public static final class Demo extends Extension {
        @Config
        public boolean enabled;
        @Config
        public int key;
        public boolean flag = true, inner;
        public double speed = 2.5;
        public String style = "B", note = "hi";
        public int r = 255, g = 0, b = 0;
        public int pressed, enables, ticks;

        Demo() {
            super("Meow Demo", "Gale");
            tooltip("A made-up extension for the test");
            toggle("Flag", "flag");
            slider("Speed", 0, 10, 0.5, "x", "speed", double.class);
            mode("Style", Arrays.asList("A", "B", "C"), "style");
            text("Note", "note");
            color("Tint", linkColor("r", "g", "b"));
            button("Press", 1f, () -> pressed++);
            expand("Group", e -> e.toggle("Inner", "inner"));
        }

        @Override
        public void onEnable() { enables++; }

        @EventTarget
        public void onTick(ClientTickEvent e) {
            if (e.getPhase() == ClientTickEvent.Phase.POST) ticks++;
        }
    }
}
