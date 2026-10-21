package gale.devtest;

import gale.Gale;
import gale.core.FrameSampler;
import gale.core.HookedEffects;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.module.Module;
import gale.module.impl.optimize.Performance;
import gale.module.impl.optimize.Profiler;
import gale.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumParticleTypes;
import org.lwjgl.opengl.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** The self test of the frame-time analyzer and the Performance module. */
final class OptimizeTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int FIRST_ID = -6000;
    private static final List<EntityArmorStand> stands = new ArrayList<>();
    private static volatile boolean busy;
    private static int px, py, pz, framesA, framesB, limitBefore;
    private static long nanosA, nanosB, lastFrame;
    private static boolean vsyncBefore;
    private static int measuring, activeFrames;
    private static EntityArmorStand behind, front, side;
    private static net.minecraft.entity.passive.EntityPig pig;
    private static net.minecraft.entity.item.EntityItem item;

    private OptimizeTest() { }

    private static void check(String what, boolean ok, Object detail) { DevTest.check(what, ok, String.valueOf(detail)); }

    private static void phase(String name, int length, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase(name, length, enter, tick, exit));
    }

    private static Performance perf() { return Gale.modules().get(Performance.class); }

    private static Module profiler() { return Gale.modules().get(Profiler.class); }

    private static void set(Module m, String setting, double v) { ((NumberSetting) m.getSetting(setting)).set(v); }

    static void phases() {
        // the frames themselves: a known amount of work in a Gale listener, and frame timing for the benchmark
        Gale.BUS.listen(FrameEvent.class, Priority.NORMAL, e -> {
            long now = System.nanoTime();
            if (measuring == 1 && lastFrame != 0) {
                framesA++;
                nanosA += now - lastFrame;
                if (Display.isActive()) activeFrames++;
            } else if (measuring == 2 && lastFrame != 0) {
                framesB++;
                nanosB += now - lastFrame;
            }
            lastFrame = now;
            if (busy) {
                long end = System.nanoTime() + 3_000_000;
                double x = 1;
                while (System.nanoTime() < end) x = Math.sqrt(x + 1);
                if (x < 0) Gale.LOG.info("never");
            }
        }, "devtest");

        phase("profiler: time goes to whoever spends it", 100, () -> {
            set(perf(), "Background FPS", 0);
            profiler().setEnabled(true);
            busy = true;
        }, t -> { }, () -> {
            busy = false;
            FrameSampler.Report r = FrameSampler.recent();
            check("the profiler reports", r != null && r.samples > 200, r == null ? "nothing" : r.samples + " samples");
            if (r == null) return;
            double gale = 0;
            boolean vanilla = false;
            for (FrameSampler.Row row : r.rows) {
                if (row.name.equals(Gale.NAME)) gale = row.ms;
                if (row.name.equals("画地形") || row.name.equals("等显卡（交换画面）") || row.name.equals("画世界（其他）") || row.name.equals("HUD 和界面")) vanilla = true;
            }
            check("3 ms of work in a Gale listener shows up as Gale, about 3 ms a frame", gale > 2.2 && gale < 4.5, String.format("%.2f ms of %.2f", gale, r.frameMs));
            check("the game's own parts are named", vanilla, "");
            for (String l : FrameSampler.describe(r)) if (!l.startsWith("      ")) DevTest.line("  " + l);
        });

        phase("Performance: entities behind a wall are not drawn", 60, () -> {
            EntityPlayerSP p = mc.thePlayer;
            p.rotationYaw = p.prevRotationYaw = 0;
            p.rotationPitch = p.prevRotationPitch = 0;
            px = (int) Math.floor(p.posX);
            py = (int) Math.floor(p.posY);
            pz = (int) Math.floor(p.posZ);
            wall(4, 5, 5, true);
            behind = stand(px + 0.5, py, pz + 9.5);
            front = stand(px + 0.5, py, pz + 3);
            side = stand(px + 10.5, py, pz + 9.5);
            pig = new net.minecraft.entity.passive.EntityPig(mc.theWorld);
            pig.setPosition(px - 0.5, py, pz + 10);
            mc.theWorld.addEntityToWorld(FIRST_ID - 500, pig);
            item = new net.minecraft.entity.item.EntityItem(mc.theWorld, px + 1.5, py, pz + 3, new ItemStack(Items.diamond));
            mc.theWorld.addEntityToWorld(FIRST_ID - 501, item);
            set(perf(), "Misc distance", 1);
            perf().setEnabled(true);
        }, t -> {
            if (t == 20) {
                check("an armour stand behind the wall is hidden (skipped through the render event)", perf().isHidden(behind) && perf().skipsLiving(behind, 100), "");
                check("a pig behind the wall is hidden (render distance weight 0)", perf().isHidden(pig) && pig.renderDistanceWeight == 0, pig.renderDistanceWeight);
                check("in front of it: drawn", !perf().isHidden(front) && !perf().skipsLiving(front, 9), "");
                check("beside it: drawn", !perf().isHidden(side), "");
                check("the module says how many it hides", perf().hiddenCount() >= 1 && String.valueOf(perf().getInfo()).contains("藏了"), perf().getInfo());
                wall(4, 5, 5, false);
            }
            if (t == 40) {
                check("wall gone: drawn again", !perf().isHidden(behind) && !perf().isHidden(pig) && pig.renderDistanceWeight == 1, pig.renderDistanceWeight);
                set(perf(), "Misc distance", 0.5);
            }
            if (t == 44) {
                check("Misc distance 0.5 halves the render distance of misc things (dropped item)", item.renderDistanceWeight == 0.5, item.renderDistanceWeight);
                check("and of armour stands (about 254 blocks, so skipped at 200 but not 100)", perf().skipsLiving(front, 200 * 200) && !perf().skipsLiving(front, 100 * 100), "");
                set(perf(), "Misc distance", 1);
            }
        }, () -> {
            perf().setEnabled(false);
            check("off: every entity's own render distance is back", pig.renderDistanceWeight == 1 && item.renderDistanceWeight == 1, "");
            removeStands();
            mc.theWorld.removeEntityFromWorld(FIRST_ID - 500);
            mc.theWorld.removeEntityFromWorld(FIRST_ID - 501);
        });

        phase("Performance: particles", 8, () -> perf().setEnabled(true), t -> {
            if (t == 3) {
                check("Gale took over the particles", mc.effectRenderer instanceof HookedEffects, mc.effectRenderer.getClass().getSimpleName());
                set(perf(), "Particle limit", 200);
                spawn(600, 2);
                check("the limit holds", HookedEffects.count() <= 200, HookedEffects.count());
                set(perf(), "Particle limit", 0);
                int before = HookedEffects.count();
                spawn(50, -5);
                int afterBehind = HookedEffects.count();
                spawn(50, 4);
                int afterFront = HookedEffects.count();
                check("particles behind you are not added", afterBehind == before, before + " -> " + afterBehind);
                check("particles in front are", afterFront == afterBehind + 50, afterBehind + " -> " + afterFront);
                set(perf(), "Particle limit", 1000);
            }
        }, () -> { });

        phase("Performance: far sign text", 4, () -> { }, t -> {
            if (t != 2) return;
            TileEntitySpecialRenderer<?> r = TileEntityRendererDispatcher.instance.mapSpecialRenderers.get(TileEntitySign.class);
            check("the sign renderer is wrapped", r != null && r.getClass().getSimpleName().equals("SignText"), r == null ? null : r.getClass().getSimpleName());
            if (r == null) return;
            TileEntitySign sign = new TileEntitySign();
            sign.setWorldObj(mc.theWorld);
            sign.setPos(new BlockPos(px, py, pz + 2));
            sign.signText[0] = new ChatComponentText("hello");
            @SuppressWarnings("unchecked")
            TileEntitySpecialRenderer<TileEntitySign> sr = (TileEntitySpecialRenderer<TileEntitySign>) r;
            sr.renderTileEntityAt(sign, 0, 0, 200, 0, -1);
            check("a far sign is drawn without its text, and keeps its text", "hello".equals(sign.signText[0].getUnformattedText()), sign.signText[0].getUnformattedText());
        }, () -> perf().setEnabled(false));

        phase("Performance: 180 armoured stands behind a wall, off then on", 170, () -> {
            limitBefore = mc.gameSettings.limitFramerate;
            vsyncBefore = mc.gameSettings.enableVsync;
            mc.gameSettings.limitFramerate = 260;
            mc.gameSettings.enableVsync = false;
            org.lwjgl.opengl.Display.setVSyncEnabled(false);
            perf().setEnabled(false);
            wall(9, 7, 5, true);
            int id = 0;
            for (int x = -6; x <= 6; x++)
                for (int z = 7; z <= 20; z++) {
                    EntityArmorStand s = stand(px + x + 0.5, py, pz + z + 0.5);
                    s.setCurrentItemOrArmor(0, new ItemStack(Items.diamond_sword));
                    s.setCurrentItemOrArmor(1, new ItemStack(Items.diamond_boots));
                    s.setCurrentItemOrArmor(2, new ItemStack(Items.diamond_leggings));
                    s.setCurrentItemOrArmor(3, new ItemStack(Items.diamond_chestplate));
                    s.setCurrentItemOrArmor(4, new ItemStack(Items.diamond_helmet));
                    id++;
                }
        }, t -> {
            if (t == 20) measuring = 1;
            if (t == 80) {
                measuring = 0;
                report("off");
                perf().setEnabled(true);
            }
            if (t == 100) measuring = 2;
            if (t == 160) {
                measuring = 0;
                report("on");
            }
        }, () -> {
            double a = nanosA / 1e6 / Math.max(1, framesA), b = nanosB / 1e6 / Math.max(1, framesB);
            check("all the stands behind the wall were hidden", perf().hiddenCount() >= stands.size() - 2, perf().hiddenCount() + " of " + stands.size());
            check(String.format("frame time: off %.2f ms (%.0f FPS), on %.2f ms (%.0f FPS)", a, 1000 / a, b, 1000 / b), framesA > 50 && framesB > 50,
                String.format("%+.0f%% FPS", (a / b - 1) * 100));
            perf().setEnabled(false);
            profiler().setEnabled(false);
            removeStands();
            wall(9, 7, 5, false);
            mc.gameSettings.limitFramerate = limitBefore;
            mc.gameSettings.enableVsync = vsyncBefore;
        });

        phase("Performance: background frame cap", 40, () -> {
            set(perf(), "Background FPS", 20);
            perf().setEnabled(true);
            framesA = 0;
            nanosA = 0;
            activeFrames = 0;
        }, t -> {
            if (t == 10) measuring = 1;
            if (t == 38) measuring = 0;
        }, () -> {
            double fps = framesA / (nanosA / 1e9);
            if (activeFrames == 0) check("window in the background: at most 20 FPS", fps < 23, String.format("%.0f FPS", fps));
            else if (activeFrames == framesA) check("window in front: not capped", fps > 25, String.format("%.0f FPS", fps));
            else DevTest.line("  background cap not checked: the window came to the front or went to the back while measuring");
            perf().setEnabled(false);
            set(perf(), "Background FPS", 30);
        });
    }

    private static void report(String label) {
        FrameSampler.Report r = FrameSampler.recent();
        if (r == null) return;
        DevTest.line("  profiler, Performance " + label + ":");
        int n = 0;
        for (String l : FrameSampler.describe(r)) {
            if (l.startsWith("      ")) continue;
            DevTest.line("  " + l);
            if (++n > 7) break;
        }
    }

    private static EntityArmorStand stand(double x, double y, double z) {
        EntityArmorStand s = new EntityArmorStand(mc.theWorld, x, y, z);
        mc.theWorld.addEntityToWorld(FIRST_ID - stands.size(), s);
        stands.add(s);
        return s;
    }

    private static void removeStands() {
        for (EntityArmorStand s : stands) mc.theWorld.removeEntityFromWorld(s.getEntityId());
        stands.clear();
    }

    /** A stone wall (or air) in front of you: half width, height, distance. */
    private static void wall(int half, int height, int distance, boolean stone) {
        int x0 = px, y0 = py, z0 = pz + distance;
        DevTest.server(sp -> {
            for (int x = -half; x <= half; x++)
                for (int y = 0; y < height; y++)
                    sp.worldObj.setBlockState(new BlockPos(x0 + x, y0 + y, z0), stone ? Blocks.stone.getDefaultState() : Blocks.air.getDefaultState());
        });
    }

    private static void spawn(int count, double ahead) {
        EntityPlayerSP p = mc.thePlayer;
        for (int i = 0; i < count; i++) mc.theWorld.spawnParticle(EnumParticleTypes.CRIT, p.posX + (i % 5) * 0.1, p.posY + 1.6, p.posZ + ahead, 0, 0, 0);
    }
}
