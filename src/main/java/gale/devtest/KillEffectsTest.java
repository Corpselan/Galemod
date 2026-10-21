package gale.devtest;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.event.Priority;
import gale.event.impl.AttackEvent;
import gale.event.impl.ChatEvent;
import gale.event.impl.EntityStatusEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.Render3DEvent;
import gale.event.impl.WorldEvent;
import gale.module.impl.client.HUD;
import gale.module.impl.render.KillEffectAnimation;
import gale.module.impl.render.KillEffects;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.UUID;

/** Focused in-game integration test; excluded from the distributed mod jar. */
final class KillEffectsTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static int serial;
    private static long renderStart, renderNanos;
    private static int renderSamples, visibleSamples;
    private static boolean measure, wasDepth, wasBlend, wasAlpha, glRestored = true;
    private static boolean glErrorFree = true;

    private static KillEffects fx() { return Gale.modules().get(KillEffects.class); }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static EntityOtherPlayerMP target() {
        int n = ++serial;
        EntityOtherPlayerMP p = new EntityOtherPlayerMP(mc.theWorld,
            new GameProfile(new UUID(123456, n), "GaleVictim" + n));
        p.setPosition(mc.thePlayer.posX + 3, mc.thePlayer.posY, mc.thePlayer.posZ);
        mc.theWorld.addEntityToWorld(-9000 - n, p);
        return p;
    }

    private static void kill() {
        EntityOtherPlayerMP p = target();
        Gale.BUS.post(new AttackEvent(p));
        p.setHealth(0);
        Gale.BUS.post(new EntityStatusEvent(p.getEntityId(), (byte) 3, p));
        // A health update, status retransmission and chat must not count the same victim again.
        Gale.BUS.post(new EntityStatusEvent(p.getEntityId(), (byte) 3, p));
        Gale.BUS.post(new ChatEvent(new ChatComponentText(p.getName() + " was killed by " + mc.thePlayer.getName() + "."), (byte) 0));
    }

    private static Object field(Object object, String name) {
        try {
            java.lang.reflect.Field f = object.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(object);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static java.util.List<?> trees() { return (java.util.List<?>) field(field(fx(), "endTree"), "trees"); }

    private static int[] worldState() {
        return new int[]{GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0,
            GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0, GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_LIGHTING) ? 1 : 0, GL11.glIsEnabled(GL11.GL_TEXTURE_2D) ? 1 : 0,
            GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 1 : 0, GL11.glIsEnabled(GL11.GL_FOG) ? 1 : 0,
            GL11.glGetInteger(0x84E0), GL11.glGetInteger(GL11.GL_MATRIX_MODE),
            GL11.glGetInteger(0x80C9), GL11.glGetInteger(0x80C8),
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), GL11.glGetInteger(GL11.GL_SHADE_MODEL)};
    }

    static void phases() {
        if (Boolean.getBoolean("gale.endfxtest")) { endPhases(); return; }
        KillEffectAnimation a = new KillEffectAnimation();
        long start = 1_000_000_000L;
        a.show(1, start, 2200);
        check("animation starts small", Math.abs(a.scale(start) - 0.42) < 0.001, a.scale(start));
        check("animation is nonlinear", a.scale(start + 70_000_000) > 0.75f, a.scale(start + 70_000_000));
        check("animation settles at one", Math.abs(a.scale(start + 280_000_000) - 1) < 0.001, a.scale(start + 280_000_000));
        check("expired animation is idle", !a.visible(start + 2_200_000_000L, 2200), "");
        a.show(1, start, 2200);
        a.show(2, start + 400_000_000, 2200);
        check("upgrade retains old and new medals", a.previous() == 1 && a.current() == 2, "");
        check("transition crossfades", a.outgoingAlpha(start + 490_000_000, 2200) > 0 && a.incomingAlpha(start + 490_000_000, 2200) > 0, "");
        a.show(3, start + 500_000_000, 2200);
        check("rapid upgrade stays bounded", a.previous() >= 1 && a.current() == 3, "two slots");
        a.clear();
        check("clear removes both layers", a.current() == 0 && a.previous() == 0, "");

        Gale.BUS.listen(Render2DEvent.class, Priority.HIGHEST, e -> {
            if (!measure) return;
            wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            renderStart = System.nanoTime();
        }, "killfx-test");
        Gale.BUS.listen(Render2DEvent.class, Priority.MONITOR, e -> {
            if (!measure) return;
            renderNanos += System.nanoTime() - renderStart;
            renderSamples++;
            if (wasDepth != GL11.glIsEnabled(GL11.GL_DEPTH_TEST) || wasBlend != GL11.glIsEnabled(GL11.GL_BLEND)
                || wasAlpha != GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) glRestored = false;
            if (GL11.glGetError() != GL11.GL_NO_ERROR) glErrorFree = false;
        }, "killfx-test");

        DevTest.PHASES.add(new DevTest.Phase("KillEffects assets and attribution", 30, () -> {
            Gale.modules().get(HUD.class).setEnabled(false);
            fx().setEnabled(true);
            // Each run starts with defaults, regardless of a previous run's saved timeout settings.
            for (gale.module.setting.Setting<?> setting : fx().getSettings()) setting.reset();
            for (String year : new String[]{"21", "23", "24"}) for (int i = 1; i <= 6; i++) {
                try (InputStream in = mc.getResourceManager().getResource(new ResourceLocation(Gale.ID,
                    "textures/kill/champions" + year + "_" + i + ".png")).getInputStream()) {
                    check("20" + year + " medal " + i + " loads", ImageIO.read(in).getWidth() == 300, "PNG");
                } catch (Exception ex) { check("20" + year + " medal " + i + " loads", false, ex); }
                try (InputStream in = mc.getResourceManager().getResource(new ResourceLocation(Gale.ID,
                    "sounds/kill/champions" + year + "_" + i + ".ogg")).getInputStream()) {
                    check("20" + year + " sound " + i + " is Ogg", in.read() == 'O' && in.read() == 'g' && in.read() == 'g' && in.read() == 'S', "");
                } catch (Exception ex) { check("20" + year + " sound " + i + " loads", false, ex); }
                check("20" + year + " sound event " + i + " registered", mc.getSoundHandler().getSound(new ResourceLocation(Gale.ID, "kill.champions" + year + "." + i)) != null, "");
            }
            EntityOtherPlayerMP unrelated = target();
            unrelated.setHealth(0);
            Gale.BUS.post(new EntityStatusEvent(unrelated.getEntityId(), (byte) 3, unrelated));
            check("unattacked death ignored", fx().streak() == 0, fx().streak());
            EntityOtherPlayerMP leaving = target();
            Gale.BUS.post(new AttackEvent(leaving));
            mc.theWorld.removeEntityFromWorld(leaving.getEntityId());
        }, t -> { }, () -> check("entity unloading ignored", fx().streak() == 0, fx().streak())));

        for (int i = 1; i <= 6; i++) {
            final int stage = i;
            DevTest.PHASES.add(new DevTest.Phase("KillEffects stage " + stage, 25,
                () -> { measure = true; kill(); }, t -> {
                    if (t == 2) ScreenShotHelper.saveScreenshot(mc.mcDataDir, "killfx-transition-" + stage + ".png",
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    if (t == 8) {
                        ScreenShotHelper.saveScreenshot(mc.mcDataDir, "killfx-2021-" + stage + ".png",
                            mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                        try {
                            java.lang.reflect.Field field = KillEffects.class.getDeclaredField("playingSound");
                            field.setAccessible(true);
                            ISound active = (ISound) field.get(fx());
                            check("native sound " + stage + " is playing", active != null && mc.getSoundHandler().isSoundPlaying(active)
                                && active.getSoundLocation().equals(new ResourceLocation(Gale.ID, "kill.champions21." + stage)), "2021");
                        } catch (Exception ex) { check("sound " + stage + " plays", false, ex); }
                    }
                }, () -> {
                    check("stage " + stage + " counted once", fx().streak() == stage, fx().streak());
                    if (stage == 6) { measure = false; visibleSamples = renderSamples; }
                }));
        }
        for (String year : new String[]{"23", "24"}) for (int i = 1; i <= 6; i++) {
            final int stage = i;
            DevTest.PHASES.add(new DevTest.Phase("KillEffects 20" + year + " stage " + stage, 20, () -> {
                if (stage == 1) {
                    fx().previewAll(); // these old-theme tasks must be cancelled when switching
                    ((ModeSetting) fx().getSetting("Theme")).set("20" + year);
                    check("20" + year + " theme switch preserves real streak", fx().streak() == 6, fx().streak());
                    try {
                        java.lang.reflect.Field field = KillEffects.class.getDeclaredField("playingSound");
                        field.setAccessible(true);
                        check("20" + year + " theme switch stops previous audio", field.get(fx()) == null, "");
                    } catch (Exception ex) { check("20" + year + " theme switch stops previous audio", false, ex); }
                }
                measure = true;
                fx().preview(stage);
            }, t -> {
                if (t == 2) ScreenShotHelper.saveScreenshot(mc.mcDataDir, "killfx-20" + year + "-transition-" + stage + ".png",
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                if (t == 8) {
                    ScreenShotHelper.saveScreenshot(mc.mcDataDir, "killfx-20" + year + "-" + stage + ".png",
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    try {
                        java.lang.reflect.Field field = KillEffects.class.getDeclaredField("playingSound");
                        field.setAccessible(true);
                        ISound active = (ISound) field.get(fx());
                        check("20" + year + " native sound " + stage + " is playing", active != null && mc.getSoundHandler().isSoundPlaying(active)
                            && active.getSoundLocation().equals(new ResourceLocation(Gale.ID, "kill.champions" + year + "." + stage)), "");
                    } catch (Exception ex) { check("20" + year + " sound " + stage + " plays", false, ex); }
                }
            }, () -> {
                check("20" + year + " preview " + stage + " preserves real streak", fx().streak() == 6, fx().streak());
                if (stage == 6) { measure = false; visibleSamples = renderSamples; }
            }));
        }
        DevTest.PHASES.add(new DevTest.Phase("KillEffects rapid multikill and preview", 20, () -> {
            kill(); kill(); kill();
            check("seventh and later kills stay at six", fx().streak() == 6, fx().streak());
            fx().preview(1); fx().preview(4); fx().preview(6);
            check("preview does not alter real streak", fx().streak() == 6, fx().streak());
            check("HUD GL state restored", glRestored, "depth/blend/alpha");
            check("no GL error while rendering medals", glErrorFree, "");
            check("visible overlay rendered", visibleSamples > 20, visibleSamples);
            DevTest.line("KillEffects active overlay CPU/event cost: " + (renderSamples == 0 ? 0 : renderNanos / 1e6 / renderSamples) + " ms/frame, " + renderSamples + " frames (GPU not measured)");
            fx().resetStreak();
            ((ModeSetting) fx().getSetting("Detection")).setValue("Server");
            kill();
            check("server confirmation counts once", fx().streak() == 1, fx().streak());
            ((ModeSetting) fx().getSetting("Detection")).setValue("Auto");
            EntityPig pig = new EntityPig(mc.theWorld);
            mc.theWorld.addEntityToWorld(-9999, pig);
            Gale.BUS.post(new AttackEvent(pig));
            pig.setHealth(0);
            Gale.BUS.post(new EntityStatusEvent(pig.getEntityId(), (byte) 3, pig));
            check("players-only excludes mobs", fx().streak() == 0, fx().streak());
            ((BoolSetting) fx().getSetting("Players only")).set(false);
            EntityPig allowedPig = new EntityPig(mc.theWorld);
            mc.theWorld.addEntityToWorld(-9998, allowedPig);
            Gale.BUS.post(new AttackEvent(allowedPig));
            allowedPig.setHealth(0);
            Gale.BUS.post(new EntityStatusEvent(allowedPig.getEntityId(), (byte) 3, allowedPig));
            check("optional mob kills work", fx().streak() == 1, fx().streak());
            ((BoolSetting) fx().getSetting("Players only")).set(true);
            kill();
            Gale.BUS.post(new EntityStatusEvent(mc.thePlayer.getEntityId(), (byte) 3, mc.thePlayer));
            check("own death resets", fx().streak() == 0, fx().streak());
            kill();
            Gale.BUS.post(new WorldEvent.Load(mc.theWorld));
            check("world change resets", fx().streak() == 0, fx().streak());
            kill();
            ((NumberSetting) fx().getSetting("Reset after")).set(1);
        }, t -> { }, () -> { }));
        DevTest.PHASES.add(new DevTest.Phase("KillEffects reset timeout and disable", 25, () -> { }, t -> { }, () -> {
            check("optional timeout resets", fx().streak() == 0, fx().streak());
            ((NumberSetting) fx().getSetting("Reset after")).set(0);
            fx().setEnabled(false);
            kill();
            check("disabled module ignores kills", fx().streak() == 0, fx().streak());
        }));
        endPhases();
    }

    private static void endPhases() {
        final int[][] before = {null};
        final boolean[] worldGL = {true, true};
        final int[] worldFrames = {0};
        Gale.BUS.listen(Render3DEvent.class, Priority.HIGHEST, e -> {
            if (((ModeSetting) fx().getSetting("Theme")).is("end") && fx().isActive()) before[0] = worldState();
        }, "end-test");
        Gale.BUS.listen(Render3DEvent.class, Priority.MONITOR, e -> {
            if (before[0] == null) return;
            if (!java.util.Arrays.equals(before[0], worldState())) worldGL[0] = false;
            if (GL11.glGetError() != GL11.GL_NO_ERROR) worldGL[1] = false;
            worldFrames[0]++;
            before[0] = null;
        }, "end-test");
        final boolean[] oldGUI = {false};
        final float[] oldFov = {0};
        DevTest.PHASES.add(new DevTest.Phase("end tree world placement, growth and expiry", 126, () -> {
            fx().setEnabled(true);
            for (gale.module.setting.Setting<?> setting : fx().getSettings()) setting.reset();
            ((ModeSetting) fx().getSetting("Theme")).set("end");
            ((NumberSetting) fx().getSetting("Tree lifetime")).set(5);
            for (String frame : new String[]{"bud", "growing", "mature"}) {
                try (InputStream in = mc.getResourceManager().getResource(new ResourceLocation(Gale.ID,
                    "textures/kill/end/" + frame + ".png")).getInputStream()) {
                    java.awt.image.BufferedImage image = ImageIO.read(in);
                    check("end " + frame + " sprite loads with alpha", image.getColorModel().hasAlpha() && image.getHeight() > 400, image.getWidth());
                    check("end " + frame + " background is transparent", (image.getRGB(0, 0) >>> 24) == 0, "corner alpha");
                } catch (Exception ex) { check("end " + frame + " sprite loads", false, ex); }
            }
            check("end reference sprite renderer prepared", (Boolean) field(field(fx(), "endTree"), "prepared"), "three stages");
            fx().previewAll();
            check("end preview all creates one complete tree", trees().size() == 1, trees().size());
            fx().resetStreak();
            check("end is a sibling theme", ((ModeSetting) fx().getSetting("Theme")).getModes().contains("end"), "");
            check("end settings replace HUD settings", fx().getSetting("Tree size").isVisible() && !fx().getSetting("X").isVisible(), "");
            oldGUI[0] = mc.gameSettings.hideGUI;
            oldFov[0] = mc.gameSettings.fovSetting;
            mc.gameSettings.fovSetting = 75;
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.thirdPersonView = 0;
            EntityOtherPlayerMP victim = target();
            victim.setPosition(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ + 7);
            Vec3 expected = victim.getPositionVector();
            Gale.BUS.post(new AttackEvent(victim));
            victim.setHealth(0);
            Gale.BUS.post(new EntityStatusEvent(victim.getEntityId(), (byte) 3, victim));
            Gale.BUS.post(new EntityStatusEvent(victim.getEntityId(), (byte) 3, victim));
            Gale.BUS.post(new ChatEvent(new ChatComponentText(victim.getName() + " was killed by " + mc.thePlayer.getName()), (byte) 0));
            check("end duplicate packets and chat create one tree", trees().size() == 1 && fx().streak() == 1, trees().size());
            check("end anchored at victim death position", ((Vec3) field(trees().get(0), "origin")).squareDistanceTo(expected) < 1e-8, expected);
            victim.setPosition(victim.posX + 40, victim.posY + 20, victim.posZ);
            check("end origin survives entity movement", ((Vec3) field(trees().get(0), "origin")).squareDistanceTo(expected) < 1e-8, "snapshot");
        }, t -> {
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -18;
            if (t == 10 || t == 35 || t == 66) ScreenShotHelper.saveScreenshot(mc.mcDataDir,
                "killfx-end-" + (t == 10 ? "vortex" : t == 35 ? "growth" : "mature") + ".png",
                mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (t == 35) {
                check("end has no Champions sound", field(fx(), "playingSound") == null, "");
                check("end has no HUD medal", ((KillEffectAnimation) field(fx(), "animation")).current() == 0, "");
            }
        }, () -> {
            mc.gameSettings.hideGUI = oldGUI[0];
            mc.gameSettings.fovSetting = oldFov[0];
            check("end trees expire", trees().isEmpty(), trees().size());
            check("end world GL state restored", worldGL[0], "depth/write/blend/cull/light/texture/alpha/fog/active unit/matrix/blend factors");
            check("end draws without GL errors", worldGL[1] && worldFrames[0] > 30, worldFrames[0]);
        }));
        DevTest.PHASES.add(new DevTest.Phase("end server position, multikill bounds and cleanup", 25, () -> {
            ((ModeSetting) fx().getSetting("Detection")).set("Server");
            EntityOtherPlayerMP victim = target();
            Gale.BUS.post(new AttackEvent(victim));
            Vec3 expected = victim.getPositionVector();
            Gale.BUS.post(new EntityStatusEvent(victim.getEntityId(), (byte) 3, victim));
            check("end Server waits for confirmation", trees().isEmpty(), "");
            mc.theWorld.removeEntityFromWorld(victim.getEntityId());
            victim.setPosition(victim.posX + 30, victim.posY, victim.posZ);
            Gale.BUS.post(new ChatEvent(new ChatComponentText(victim.getName() + " was killed by " + mc.thePlayer.getName()), (byte) 0));
            check("end delayed confirmation retains death position", trees().size() == 1
                && ((Vec3) field(trees().get(0), "origin")).squareDistanceTo(expected) < 1e-8, "removed victim");
            ((ModeSetting) fx().getSetting("Detection")).set("Auto");
            for (int i = 0; i < 6; i++) kill();
            check("end rapid multikills capped at four trees", trees().size() == 4, trees().size());
            int streak = fx().streak();
            fx().preview(1);
            check("end preview preserves streak", streak == fx().streak(), streak);
            ((ModeSetting) fx().getSetting("Theme")).set("2021");
            check("leaving end clears trees and preserves streak", trees().isEmpty() && fx().streak() == streak, "");
            ((ModeSetting) fx().getSetting("Theme")).set("end");
            fx().preview(1);
            Gale.BUS.post(new WorldEvent.Load(mc.theWorld));
            check("end world reset clears trees", trees().isEmpty(), "");
            fx().preview(1);
            Gale.BUS.post(new EntityStatusEvent(mc.thePlayer.getEntityId(), (byte) 3, mc.thePlayer));
            check("end own death clears trees", trees().isEmpty(), "");
            fx().preview(1);
            fx().setEnabled(false);
            check("end disable clears trees and stops sprite renderer", trees().isEmpty() && !(Boolean) field(field(fx(), "endTree"), "prepared"), "");
            ((ModeSetting) fx().getSetting("Theme")).set("2021");
            ((NumberSetting) fx().getSetting("Tree lifetime")).reset();
        }, t -> { }, () -> { }));
    }
}
