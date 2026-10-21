package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.InputEvent;
import gale.event.impl.PacketEvent;
import gale.module.impl.client.HUD;
import gale.module.impl.render.JettModel;
import gale.module.impl.render.KillEffects;
import gale.module.impl.render.LocalSpin;
import gale.module.setting.NumberSetting;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.opengl.GL11;

/** Actual Forge client screenshots and render-path regressions, excluded from release jars. */
final class JettModelTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String shot;
    private static boolean glClean = true;
    private static long before;
    private static boolean walking;
    private static boolean monitorSpin, poseUnchanged = true;
    private static final float[] poseBefore = new float[6];
    private static int movementPackets, changedPackets;
    private static float spinYaw, spinPitch, firstAngle;
    private static volatile float serverYaw = Float.NaN, serverPitch = Float.NaN;
    private static net.minecraft.util.MouseHelper originalMouse;
    private static boolean fixedInput;
    private static LocalSpin spin() { return Gale.modules().get(LocalSpin.class); }
    private static JettModel model() { return Gale.modules().get(JettModel.class); }
    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    static void phases() {
        Gale.BUS.listen(gale.event.impl.MouseEvent.class, Priority.HIGHEST, e -> { if (fixedInput) e.cancel(); }, "jett-test");
        Gale.BUS.listen(FrameEvent.class, Priority.HIGHEST, e -> {
            if (!monitorSpin || mc.thePlayer == null) return;
            poseBefore[0]=mc.thePlayer.rotationYaw; poseBefore[1]=mc.thePlayer.rotationPitch;
            poseBefore[2]=mc.thePlayer.renderYawOffset; poseBefore[3]=mc.thePlayer.prevRenderYawOffset;
            poseBefore[4]=mc.thePlayer.rotationYawHead; poseBefore[5]=mc.thePlayer.prevRotationYawHead;
        }, "spin-test");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> {
            if (!monitorSpin || !e.is(C03PacketPlayer.class)) return;
            C03PacketPlayer p=e.as(C03PacketPlayer.class); movementPackets++;
            if (p.getRotating() && (Math.abs(p.getYaw()-spinYaw)>.001 || Math.abs(p.getPitch()-spinPitch)>.001)) changedPackets++;
        }, "spin-test");
        Gale.BUS.listen(InputEvent.class, Priority.LOWEST, e -> {
            if (fixedInput) { e.stop(); e.jump=false; e.setSneak(walking); if (walking) e.forward=.3f; }
        }, "jett-test");
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png"); shot = null;
        }, "jett-test");
        Gale.BUS.listen(Render2DEvent.class, Priority.MONITOR, e -> {
            if (GL11.glGetError() != GL11.GL_NO_ERROR) glClean = false;
            if (monitorSpin && mc.thePlayer!=null) {
                poseUnchanged &= poseBefore[0]==mc.thePlayer.rotationYaw && poseBefore[1]==mc.thePlayer.rotationPitch
                    && poseBefore[2]==mc.thePlayer.renderYawOffset && poseBefore[3]==mc.thePlayer.prevRenderYawOffset
                    && poseBefore[4]==mc.thePlayer.rotationYawHead && poseBefore[5]==mc.thePlayer.prevRotationYawHead;
            }
        }, "jett-test");
        DevTest.PHASES.add(new DevTest.Phase("Jett load and empty hands", 40, () -> {
            fixedInput=true;
            originalMouse=mc.mouseHelper;
            mc.mouseHelper=new net.minecraft.util.MouseHelper() {
                @Override public void mouseXYChange() { originalMouse.mouseXYChange(); deltaX=deltaY=0; }
                @Override public void grabMouseCursor() { originalMouse.grabMouseCursor(); }
                @Override public void ungrabMouseCursor() { originalMouse.ungrabMouseCursor(); }
            };
            for (gale.module.Module m : Gale.modules().all()) if (!m.isAlwaysOn()) m.setEnabled(false);
            for (gale.module.setting.Setting<?> setting : model().getSettings()) setting.reset();
            model().setEnabled(true);
            mc.gameSettings.thirdPersonView = 0; mc.gameSettings.hideGUI = false;
            mc.gameSettings.fovSetting = 70; mc.gameSettings.viewBobbing = false;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0;
            equip(null);
        }, t -> { if (t == 30) shot = "jett-01-empty"; }, () -> {
            check("GPU model loaded", model().isModelReady(), "shader + cached buffers + textures");
            check("first-person arms rendered", model().firstFrames() > 10, model().firstFrames());
            check("bounded first-person geometry", model().firstTriangles() == 16644, model().firstTriangles());
            check("full model geometry", model().thirdTriangles() == 42884, model().thirdTriangles());
        }));
        view("02-sword", new ItemStack(Items.diamond_sword), 0, false, false);
        view("03-block", new ItemStack(Blocks.stone), 0, false, false);
        view("04-bow", new ItemStack(Items.bow), 0, true, false);
        view("05-blocking", new ItemStack(Items.diamond_sword), 0, true, false);
        view("06-drinking", new ItemStack(Items.potionitem), 0, true, false);
        view("07-swing", new ItemStack(Items.iron_axe), 0, false, true);
        view("08-front", new ItemStack(Items.diamond_sword), 2, false, false);
        view("09-back", new ItemStack(Blocks.stone), 1, false, false);
        DevTest.PHASES.add(new DevTest.Phase("Jett walk and sneak", 30, () -> {
            mc.gameSettings.thirdPersonView = 2;
            walking = true;
        }, t -> {
            mc.thePlayer.setSneaking(true);
            mc.thePlayer.limbSwing += 0.25f;
            mc.thePlayer.limbSwingAmount = mc.thePlayer.prevLimbSwingAmount = 0.7f;
            if (t == 20) shot = "jett-10-sneak-walk";
        }, () -> {
            walking = false;
            check("third-person replacement rendered", model().thirdFrames() > 10, model().thirdFrames());
        }));
        DevTest.PHASES.add(new DevTest.Phase("Jett inventory preview", 25, () -> {
            mc.gameSettings.thirdPersonView = 0;
            mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.thePlayer));
        }, t -> { if (t == 18) shot = "jett-13-inventory"; }, () -> mc.displayGuiScreen(null)));
        DevTest.PHASES.add(new DevTest.Phase("Jett with Champions kill effects", 25, () -> {
            equip(new ItemStack(Items.diamond_sword));
            KillEffects fx = Gale.modules().get(KillEffects.class);
            fx.setEnabled(true);
            ((gale.module.setting.ModeSetting) fx.getSetting("Theme")).set("2024");
            fx.preview(6);
        }, t -> { if (t == 16) shot = "jett-14-killfx"; }, () -> {
            check("Jett and medals coexist", model().isModelReady() && model().firstFrames() > 0 && glClean, "2024 preview");
            Gale.modules().get(KillEffects.class).setEnabled(false);
        }));
        DevTest.PHASES.add(new DevTest.Phase("Jett map fallback", 20, () -> {
            mc.gameSettings.thirdPersonView = 0;
            equip(new ItemStack(Items.filled_map)); before = model().firstFrames();
        }, t -> { if (t == 15) shot = "jett-11-map-fallback"; }, () -> {
            check("map keeps vanilla hand path", model().firstFrames() == before, model().firstFrames() - before);
        }));
        DevTest.PHASES.add(new DevTest.Phase("Jett disabled fallback", 20, () -> {
            model().setEnabled(false); equip(new ItemStack(Items.diamond_sword)); before = model().firstFrames();
        }, t -> { if (t == 15) shot = "jett-12-disabled"; }, () -> {
            check("disabled keeps vanilla hand path", model().firstFrames() == before, model().firstFrames() - before);
            check("no OpenGL errors", glClean, "all screenshots");
            DevTest.line("Jett mean CPU submission us (excludes asset initialization and GPU execution): " + model().averageRenderMicros());
            check("kill effects still registered", Gale.modules().get(KillEffects.class) != null, "2021/2023/2024");
        }));
        DevTest.PHASES.add(new DevTest.Phase("LocalSpin Jett without changing player or server yaw", 45, () -> {
            model().setEnabled(true); spin().setEnabled(true);
            ((NumberSetting)spin().getSetting("Speed")).set(720);
            mc.gameSettings.thirdPersonView=2;
            spinYaw=mc.thePlayer.rotationYaw; spinPitch=mc.thePlayer.rotationPitch;
            monitorSpin=true; before=spin().renderedFrames(); firstAngle=spin().angle();
        }, t -> {
            if (t==7) shot="jett-15-spin-a";
            if (t==19) shot="jett-15-spin-b";
            if (t==35) DevTest.server(p -> { serverYaw=p.rotationYaw; serverPitch=p.rotationPitch; });
        }, () -> {
            check("spin renders Jett through canceled player pre event", spin().renderedFrames()>before+10, spin().renderedFrames()-before);
            check("spin angle advances", Math.abs(spin().angle()-firstAngle)>10, spin().angle());
            check("server does not see local spin", Math.abs(serverYaw-spinYaw)<.001 && Math.abs(serverPitch-spinPitch)<.001, serverYaw+", "+serverPitch);
        }));
        DevTest.PHASES.add(new DevTest.Phase("LocalSpin vanilla renderer", 30, () -> {
            model().setEnabled(false); before=spin().renderedFrames();
        }, t -> { if (t==20) shot="jett-16-vanilla-spin"; }, () -> {
            check("spin also supports vanilla player", spin().renderedFrames()>before+10, spin().renderedFrames()-before);
        }));
        DevTest.PHASES.add(new DevTest.Phase("LocalSpin first-person camera stays normal", 25, () -> {
            model().setEnabled(true); mc.gameSettings.thirdPersonView=0; before=spin().renderedFrames();
        }, t -> { if (t==18) shot="jett-17-first-spin"; }, () -> {
            check("first-person does not spin the camera or hands", spin().renderedFrames()==before && mc.thePlayer.rotationYaw==spinYaw && mc.thePlayer.rotationPitch==spinPitch, "camera unchanged");
            check("rendering never mutates entity rotation fields", poseUnchanged && spin().poseMutations()==0, "yaw, pitch, body and head; wrapper mutations="+spin().poseMutations());
            check("movement packets keep real facing", movementPackets>10 && changedPackets==0, movementPackets+" packets, "+changedPackets+" changed");
            check("IK keeps elbows and wrists joined", model().maxArmJointError()<.00001f, model().maxArmJointError()+" metres");
            monitorSpin=false; spin().setEnabled(false); before=spin().renderedFrames();
        }));
        DevTest.PHASES.add(new DevTest.Phase("LocalSpin disabled", 15, () -> mc.gameSettings.thirdPersonView=2, t -> {}, () -> {
            check("disabled spin stops drawing wrapper", spin().renderedFrames()==before, "");
            check("spin leaves no GL errors", glClean, "Jett, vanilla and first person");
            model().setEnabled(false); ((NumberSetting)spin().getSetting("Speed")).reset();
            fixedInput=false; mc.mouseHelper=originalMouse;
        }));
    }

    private static void equip(ItemStack item) {
        mc.thePlayer.inventory.currentItem = 0;
        mc.thePlayer.inventory.setInventorySlotContents(0, item);
        DevTest.server(p -> p.inventory.setInventorySlotContents(0, item == null ? null : item.copy()));
    }

    private static void view(String name, ItemStack item, int camera, boolean use, boolean swing) {
        DevTest.PHASES.add(new DevTest.Phase("Jett " + name, 30, () -> {
            mc.gameSettings.thirdPersonView = camera; equip(item);
        }, t -> {
            if (use) mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), Math.max(1, item.getMaxItemUseDuration() - t));
            if (swing && t % 8 == 0) mc.thePlayer.swingItem();
            if (t == 20) shot = "jett-" + name;
        }, () -> mc.thePlayer.clearItemInUse()));
    }
}
