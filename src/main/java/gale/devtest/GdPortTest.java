package gale.devtest;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.event.Priority;
import gale.event.impl.*;
import gale.kill.gd.GdKillEffect;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.render.KillEffects;
import gale.module.setting.ModeSetting;
import gale.module.setting.BoolSetting;
import gale.ui.ClickGui;
import gale.ui.raven.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import java.util.UUID;
import java.util.Arrays;

/** Checks runtime GUI hiding, all shipped presets, kill deduplication and GL state after actual drawing. */
final class GdPortTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static KillEffects fx;
    private static boolean restored = true, glClean = true;
    private static int samples;
    private static HiddenProbe probe;
    private static final class HiddenProbe extends Module {
        boolean hide = true; int ticks;
        HiddenProbe() { super("GdHiddenProbe", Category.CLIENT, "hidden test module"); on(TickEvent.Post.class, e -> ticks++); }
        @Override public boolean isHide() { return hide; }
    }
    private static boolean ravenContains(Module probe) {
        for (RavenCategory c : RavenGui.instance().categories)
            for (RavenComponent entry : c.modules) if (((RavenModule) entry).mod == probe) return true;
        return false;
    }
    private static void check(String name, boolean ok) { DevTest.check(name, ok, ""); }
    private static int[] state() {
        return new int[]{GL11.glIsEnabled(GL11.GL_DEPTH_TEST)?1:0, GL11.glIsEnabled(GL11.GL_BLEND)?1:0,
            GL11.glIsEnabled(GL11.GL_ALPHA_TEST)?1:0,GL11.glIsEnabled(GL11.GL_CULL_FACE)?1:0,
            GL11.glIsEnabled(GL11.GL_TEXTURE_2D)?1:0,GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)?1:0,GL11.glGetInteger(GL11.GL_SHADE_MODEL),
            GL11.glGetInteger(0x80C9),GL11.glGetInteger(0x80C8),GL11.glGetInteger(0x80CB),GL11.glGetInteger(0x80CA)};
    }
    private static void verifyRenderer() {
        try {
            java.lang.reflect.Field field = KillEffects.class.getDeclaredField("gdEffect"); field.setAccessible(true);
            GdKillEffect renderer = (GdKillEffect) field.get(fx);
            int[] before = state();
            Render2DEvent event = new Render2DEvent(0, new ScaledResolution(mc));
            renderer.render(event, event.width() * .5f, event.height() * .66f, 1);
            int[] after = state();
            if (!Arrays.equals(before, after)) {
                DevTest.line("GD656 state mismatch before=" + Arrays.toString(before) + " after=" + Arrays.toString(after));
                restored = false;
            }
            glClean &= GL11.glGetError() == GL11.GL_NO_ERROR; samples++;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    static void guiPhases() {
        final KillEffects effect = Gale.modules().get(KillEffects.class);
        final ModeSetting preset = (ModeSetting) effect.getSetting("GD preset");
        final ClickGui[] gui = new ClickGui[1];
        DevTest.PHASES.add(new DevTest.Phase("one hidden setting in Gale", 16, () -> {
            ((ModeSetting) effect.getSetting("Theme")).set("GD656");
            preset.isHide(true);
            gui[0] = new ClickGui(); gui[0].select(Category.RENDER); ClickGui.setExpanded(effect, true);
            mc.displayGuiScreen(gui[0]);
        }, t -> {}, () -> {
            int index = preset.index();
            check("hidden option has no clickable control in Gale", !gui[0].click(preset,"CYCLE",1,0,.75f) && preset.index() == index);
            preset.set(GdKillEffect.PRESET_NAMES[8]);
            check("hidden option remains editable by code", preset.index() == 8);
            check("hidden option remains saved in config", Gale.config().snapshot(effect).getAsJsonObject("settings").get("GD preset").getAsString().equals(preset.get()));
            preset.isHide(false);
        }));
        DevTest.PHASES.add(new DevTest.Phase("GD656 Gale selector", 30, () -> {
            ((ModeSetting) effect.getSetting("Theme")).set("GD656");
            preset.set(GdKillEffect.PRESET_NAMES[20]);
            gui[0] = new ClickGui(); gui[0].select(Category.RENDER); ClickGui.setExpanded(effect, true);
            mc.displayGuiScreen(gui[0]);
        }, t -> { if (t == 25) DevTest.shot = "gd656-gui-gale"; }, () -> {
            int index = preset.index();
            check("long preset selector is clickable in Gale", gui[0].click(preset,"CYCLE",1,0,.75f) && preset.index() == (index+1)%GdKillEffect.PRESET_NAMES.length);
        }));
        DevTest.PHASES.add(new DevTest.Phase("GD656 Raven selector", 40, () -> {
            preset.set(GdKillEffect.PRESET_NAMES[20]);
            gale.module.impl.client.ClickGuiModule settings = Gale.modules().get(gale.module.impl.client.ClickGuiModule.class);
            settings.style.set("Raven B4"); settings.ravenScale.set("Normal"); gale.module.impl.client.ClickGuiModule.open();
            for (RavenCategory c : RavenGui.instance().categories) {
                c.setOpened(c.category == Category.RENDER);
                if (c.category != Category.RENDER) continue;
                c.setX(180); c.setY(20);
                for (RavenComponent component : c.modules) {
                    RavenModule module = (RavenModule) component;
                    if (module.mod == effect && !module.opened) module.toggleOpen();
                }
            }
        }, t -> { if (t == 34) DevTest.shot = "gd656-gui-raven"; }, () -> {
            for (RavenCategory c : RavenGui.instance().categories) for (RavenComponent component : c.modules) {
                RavenModule module = (RavenModule) component;
                if (module.mod != effect) continue;
                for (RavenComponent child : module.settings) if (child instanceof RavenSlider && ((RavenSlider) child).setting == preset) {
                    RavenSlider slider = (RavenSlider) child;
                    check("Raven wraps long preset names", slider.getHeight() > 17);
                    check("Raven wrapped slider remains clickable", slider.isLeftHalf((int) slider.x+4,(int)(slider.y+slider.getHeight()-3)));
                    preset.isHide(true);
                    check("hidden option has no row or click target in Raven", slider.getHeight() == 0 && !slider.mouseClicked((int) slider.x+4,(int) slider.y+4,0));
                    preset.isHide(false);
                }
            }
            mc.displayGuiScreen(null); ClickGui.setExpanded(effect, false);
        }));
    }
    static void phases() {
        fx = Gale.modules().get(KillEffects.class);
        Gale.BUS.listen(Render2DEvent.class, Priority.MONITOR, e -> {
            if (!fx.isEnabled()) return;
            glClean &= GL11.glGetError() == GL11.GL_NO_ERROR;
        }, "gd-port-test");
        DevTest.PHASES.add(new DevTest.Phase("GUI visibility contract", 12, () -> {
            probe = new HiddenProbe(); Gale.modules().register(probe); probe.setEnabled(true);
            ClickGui gui = new ClickGui(); gui.select(Category.CLIENT); mc.displayGuiScreen(gui);
            check("hidden modules stay addressable and enabled", Gale.modules().get(probe.getName()) == probe && probe.isEnabled());
            check("category filter excludes isHide", !Gale.modules().visibleIn(Category.CLIENT).contains(probe));
            check("Gale excludes hidden module", gui.shownCount() == Gale.modules().visibleIn(Category.CLIENT).size());
            try { java.lang.reflect.Field field = ClickGui.class.getDeclaredField("search"); field.setAccessible(true); field.set(gui, "GdHiddenProbe"); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            check("Gale search excludes hidden module", gui.shownCount() == 0);
            check("Raven excludes hidden module", !ravenContains(probe));
            probe.hide = false; check("Raven notices runtime visibility changes", ravenContains(probe));
            probe.hide = true; check("Raven hides it again", !ravenContains(probe));
        }, t -> {}, () -> {
            check("GUI hiding does not stop module listeners", probe.ticks > 0);
            Gale.modules().unregister(probe); mc.displayGuiScreen(null);
            fx.setEnabled(true); ((ModeSetting) fx.getSetting("Theme")).set("GD656");
            ((BoolSetting) fx.getSetting("Players only")).set(false);
        }));
        for (int i = 0; i < GdKillEffect.PRESET_NAMES.length; i++) {
            final int index = i;
            DevTest.PHASES.add(new DevTest.Phase("GD656 " + GdKillEffect.PRESET_IDS[i] + " " + GdKillEffect.PRESET_NAMES[i], 24, () -> {
                ((ModeSetting) fx.getSetting("GD preset")).set(GdKillEffect.PRESET_NAMES[index]);
                fx.resetStreak(); fx.preview(1);
                try { mc.getResourceManager().getResource(new ResourceLocation("gd656killicon", "sounds/" + GdKillEffect.soundName(GdKillEffect.PRESET_IDS[index], 6) + ".ogg")).getInputStream().close(); }
                catch (Exception ex) { throw new IllegalStateException("Missing cue", ex); }
            }, t -> {
                if (t == 7) fx.preview(3);
                if (t == 13) fx.preview(6);
                if (t == 19) DevTest.shot = "gd656-" + GdKillEffect.PRESET_IDS[index];
            }, () -> { verifyRenderer(); check("preset renders without listener errors " + GdKillEffect.PRESET_IDS[index], Gale.BUS.errorCount() == 0); }));
        }
        DevTest.PHASES.add(new DevTest.Phase("GD656 real kill and cleanup", 12, () -> {
            fx.resetStreak();
            EntityOtherPlayerMP target = new EntityOtherPlayerMP(mc.theWorld,new GameProfile(new UUID(92345,1),"GdVictim"));
            target.setPosition(mc.thePlayer.posX + 3,mc.thePlayer.posY,mc.thePlayer.posZ); mc.theWorld.addEntityToWorld(-92345,target);
            Gale.BUS.post(new AttackEvent(target)); target.setHealth(0);
            Gale.BUS.post(new EntityStatusEvent(target.getEntityId(),(byte)3,target));
            Gale.BUS.post(new EntityStatusEvent(target.getEntityId(),(byte)3,target));
            check("real kill counted once", fx.streak() == 1);
        }, t -> {}, () -> {
            check("renderer restores GL state", restored && samples > 10);
            check("renderer produces no OpenGL errors", glClean);
            fx.setEnabled(false); check("disabling resets state", fx.streak() == 0);
        }));
    }
}
