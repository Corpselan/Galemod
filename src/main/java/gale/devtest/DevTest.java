package gale.devtest;

import gale.Gale;
import gale.bridge.Bridge;
import gale.bridge.External;
import gale.core.NetHook;
import gale.core.RotationManager;
import gale.core.ServerState;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.InputEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.Render3DEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.UpdateEvent;
import gale.core.EntityTracker;
import gale.core.SlotManager;
import gale.event.impl.PostUpdateEvent;
import gale.devtest.fakeb4.FakeDefaults;
import gale.devtest.fakeb4.FakeScript;
import gale.script.Functions;
import gale.script.ScriptApi;
import net.minecraft.util.MovementInputFromOptions;
import gale.example.AimAssist;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.DamageSource;
import java.util.Comparator;
import java.util.UUID;
import gale.example.SafeWalk;
import gale.example.Sprint;
import gale.example.Velocity;
import gale.module.Module;
import gale.module.setting.NumberSetting;
import gale.ui.ClickGui;
import gale.module.Category;
import gale.module.impl.client.ClickGuiModule;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.input.Keyboard;
import gale.util.CombatUtil;
import gale.util.MathUtil;
import gale.util.PlayerSim;
import gale.util.RotationUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-game self test, development only (gradlew runClient -Pdevtest; not in the mod jar). Makes a flat world, then
 * drives Gale through its parts: events, the movement prediction, silent rotation with the movement fix, SafeWalk on
 * a pillar, Velocity and deferring to another client, the bridge, commands, the GUI and the config. Writes
 * run/galemod-devtest.txt and quits.
 */
public final class DevTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<String> REPORT = new ArrayList<>();
    private static int passed, failed;

    static final List<Phase> PHASES = new ArrayList<>();
    private static int phase = -2, phaseTicks, totalTicks, settle;

    private static int ticks, updates, inputs, frames, r2d, r3d;
    private static final AtomicInteger sends = new AtomicInteger(), receives = new AtomicInteger();
    private static volatile boolean driveForward;
    private static int jumpEvery;
    private static Float rotYaw, rotPitch;
    private static PlayerSim predicted;
    private static double maxSimError;
    private static int simSamples;
    private static boolean sprintSeen;
    private static volatile double s12x = Double.NaN, s12y, s12z;
    private static float cam0, camDrift;
    private static double ax, az, bx, bz, minY;
    private static int pillarTop, pillarX, pillarZ;

    static final class Phase {
        final String name;
        final int length;
        final Runnable enter, exit;
        final java.util.function.IntConsumer tick;

        Phase(String name, int length, Runnable enter, java.util.function.IntConsumer tick, Runnable exit) {
            this.name = name;
            this.length = length;
            this.enter = enter;
            this.tick = tick;
            this.exit = exit;
        }
    }

    private DevTest() { }

    // rotation limit, silent slot, attack
    private static final List<Float> sentYaws = new ArrayList<>();
    private static boolean recordYaw;
    private static int slotWanted = -1, ownSlotBefore, slotHeldTicks, slotShownOwn;
    private static final AtomicInteger c09 = new AtomicInteger();
    private static volatile int pigId = -1;
    private static volatile float pigHealth = -1;
    private static boolean attackPig;

    // script API
    private static final int DUMMY_ID = -4242;
    static FakeScript script;
    private static final AtomicInteger c02 = new AtomicInteger(), c0a = new AtomicInteger(), c07c08 = new AtomicInteger();
    private static volatile double[] velIn, velAfter;
    private static volatile boolean velCapture;
    private static volatile int statusPigId = -1, statusSeen = -1;
    private static Object statusEntity;
    private static boolean pigHurtShown, pigHurtCancelled, statusDropped;
    private static volatile int statusInSeen = -1;
    private static boolean innerHookTest;
    private static int innerHookInputs, innerHookKept;
    private static volatile boolean statusInNetty;
    private static int fakeMode, useAtInput, inputsCounted, framesUsing, framesCounted;
    private static double movedFull, movedRender;
    private static boolean fakeUsingReported;

    public static void start() {
        if (Boolean.getBoolean("gale.ketest")) { KePreviewTest.start(); return; }
        Gale.LOG.info("[devtest] armed");
        // the example modules are the test's tools
        Gale.modules().register(new Sprint(), new SafeWalk(), new AimAssist(), new Velocity(), new GuiDemo());
        Gale.BUS.listen(PostUpdateEvent.class, Priority.NORMAL, e -> postUpdate(), "devtest");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> { if (e.is(C09PacketHeldItemChange.class) && !e.isCancelled()) c09.incrementAndGet(); }, "devtest");
        Gale.BUS.listen(TickEvent.Post.class, Priority.HIGHEST, e -> {
            if (slotWanted >= 0 && mc.thePlayer != null && mc.thePlayer.inventory.currentItem == ownSlotBefore) slotShownOwn++;
        }, "devtest");
        Gale.BUS.listen(TickEvent.Pre.class, Priority.MONITOR, e -> ticks++, "devtest");
        Gale.BUS.listen(UpdateEvent.class, Priority.HIGH, e -> onUpdate(), "devtest");
        Gale.BUS.listen(InputEvent.class, Priority.HIGHEST, DevTest::drive, "devtest");
        Gale.BUS.listen(InputEvent.class, Priority.LOWEST, DevTest::predict, "devtest");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> sends.incrementAndGet(), "devtest");
        Gale.BUS.listen(PacketEvent.Receive.class, Priority.MONITOR, DevTest::received, "devtest");
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> frames++, "devtest");
        Gale.BUS.listen(Render2DEvent.class, Priority.MONITOR, e -> r2d++, "devtest");
        Gale.BUS.listen(Render3DEvent.class, Priority.MONITOR, e -> r3d++, "devtest");
        Gale.BUS.listen(TickEvent.Post.class, Priority.MONITOR, e -> step(), "devtest");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> {
            if (e.isCancelled()) return;
            if (e.is(C02PacketUseEntity.class)) c02.incrementAndGet();
            if (e.is(C0APacketAnimation.class)) c0a.incrementAndGet();
            if (e.is(C07PacketPlayerDigging.class) || e.is(C08PacketPlayerBlockPlacement.class)) c07c08.incrementAndGet();
        }, "devtest");
        // the game applies received packets before its ticks: at tick start the knockback is in the motion
        Gale.BUS.listen(TickEvent.Pre.class, Priority.MONITOR, e -> {
            if (velCapture && velIn != null && velAfter == null && mc.thePlayer != null)
                velAfter = new double[]{ mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ };
        }, "devtest");
        Gale.BUS.listen(InputEvent.class, Priority.MONITOR, e -> {
            if (innerHookTest) {
                innerHookInputs++;
                if (e.forward == 0.5f) innerHookKept++;
            }
            if (fakeMode == 0 || mc.thePlayer == null) return;
            inputsCounted++;
            if (mc.thePlayer.isUsingItem()) useAtInput++;
        }, "devtest");
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (fakeMode == 0 || mc.thePlayer == null) return;
            framesCounted++;
            if (mc.thePlayer.isUsingItem()) framesUsing++;
        }, "devtest");
        // a screenshot asked for in a tick: the frame drawn before this one is still in the framebuffer
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "devtest");
        // gradlew runClient -Pguishots: only the GUI part, in a 1920x1080 window
        if (Boolean.getBoolean("gale.islandtest")) IslandTest.phases();
        else if (Boolean.getBoolean("gale.b4scripttest")) B4ScriptTest.phases();
        else if (Boolean.getBoolean("gale.expotest")) B4ScriptTest.expoPhases();
        else if (Boolean.getBoolean("gale.b4porttest")) B4PortTest.phases();
        else if (Boolean.getBoolean("gale.jappylaytest")) JappylayTest.phases();
        else if (Boolean.getBoolean("gale.b4rendertest")) B4RenderTest.phases();
        else if (Boolean.getBoolean("gale.ravengui")) RavenGuiTest.phases();
        else if (Boolean.getBoolean("gale.meowtilstest")) MeowtilsTest.phases();
        else if (Boolean.getBoolean("gale.spotifytest")) SpotifyTest.phases();
        else if (Boolean.getBoolean("gale.hudtest")) HudTest.phases();
        else if (Boolean.getBoolean("gale.lyricstest")) LyricsTest.phases();
        else if (Boolean.getBoolean("gale.fvtest")) FuriahTest.phases();
        else if (Boolean.getBoolean("gale.ballisticstest")) {BallisticsTest.phases();ScopeTest.phases();KuronamiTest.phases();}
        else if (Boolean.getBoolean("gale.scopetest")) {ScopeTest.phases();KuronamiTest.phases();}
        else if (Boolean.getBoolean("gale.kuronamitest")) KuronamiTest.phases();
        else if (Boolean.getBoolean("gale.katanatest")) KatanaTest.phases();
        else if (Boolean.getBoolean("gale.jetttest")) {
            JettModelTest.phases();
            if (Boolean.getBoolean("gale.killfxtest")) KillEffectsTest.phases();
        }
        else if (Boolean.getBoolean("gale.killfxtest")) KillEffectsTest.phases();
        else if (Boolean.getBoolean("gale.gdtest")) GdPortTest.phases();
        else if (Boolean.getBoolean("gale.guishots")) guiPhases();
        else if (Boolean.getBoolean("gale.scripttest")) scriptApiPhases();
        else if (Boolean.getBoolean("gale.optimizetest")) OptimizeTest.phases();
        else phases();
    }

    // ------------------------------------------------------------------ listeners

    private static void onUpdate() {
        updates++;
        EntityPlayerSP p = mc.thePlayer;
        if (predicted != null) {
            double err = Math.max(Math.abs(p.posX - predicted.x), Math.max(Math.abs(p.posY - predicted.y), Math.abs(p.posZ - predicted.z)));
            maxSimError = Math.max(maxSimError, err);
            simSamples++;
            predicted = null;
        }
        if (rotYaw != null) RotationManager.request("devtest", rotYaw, rotPitch, 100, null, rotTurn);
        if (slotWanted >= 0) SlotManager.request("devtest", slotWanted, 100);
    }

    private static float rotTurn;

    private static void postUpdate() {
        if (recordYaw) sentYaws.add(RotationManager.yaw());
        if (slotWanted >= 0 && mc.thePlayer.inventory.currentItem == slotWanted) slotHeldTicks++;
        if (attackPig) {
            Entity pig = mc.theWorld.getEntityByID(pigId);
            if (pig != null && CombatUtil.inReach(pig, 3.0)) {
                CombatUtil.attack(pig);
                attackPig = false;
            }
        }
    }

    private static void drive(InputEvent e) {
        inputs++;
        if (!driveForward) return;
        e.forward = e.sneak ? 0.3f : 1f;
        e.strafe = 0;
        if (jumpEvery > 0 && phaseTicks % jumpEvery == 0) e.jump = true;
    }

    private static void predict(InputEvent e) {
        if (!collectSim) return;
        EntityPlayerSP p = mc.thePlayer;
        PlayerSim s = PlayerSim.of(p);
        float[] keys = RotationManager.toServerInput(e.forward, e.strafe);
        s.forward = keys[0];
        s.strafe = keys[1];
        s.jump = e.jump;
        s.sneak = e.sneak;
        s.sprintKey = mc.gameSettings.keyBindSprint.isKeyDown() || p.isSprinting() || Boolean.TRUE.equals(e.sprint);
        s.tick();
        predicted = s;
    }

    private static boolean collectSim;

    private static void received(PacketEvent.Receive e) {
        receives.incrementAndGet();
        S12PacketEntityVelocity s12 = e.as(S12PacketEntityVelocity.class);
        if (s12 != null && mc.thePlayer != null && s12.getEntityID() == mc.thePlayer.getEntityId() && !e.isCancelled()) {
            s12x = s12.getMotionX() / 8000.0;
            s12y = s12.getMotionY() / 8000.0;
            s12z = s12.getMotionZ() / 8000.0;
        }
    }

    // ------------------------------------------------------------------ driving the phases

    private static boolean finished;

    private static void step() {
        if (finished) return;
        totalTicks++;
        phaseTicks++;
        if (mc.currentScreen instanceof GuiIngameMenu) mc.displayGuiScreen(null);
        if (totalTicks > 20 * 300) {
            check("finishes in 5 minutes", false, "stopped in phase " + phase);
            finish();
            return;
        }
        if (phase == -2) {
            if (mc.currentScreen instanceof GuiMainMenu && phaseTicks > 40) launchWorld();
            return;
        }
        if (phase == -1) {
            EntityPlayerSP p = mc.thePlayer;
            if (p != null && mc.theWorld != null && p.onGround && mc.currentScreen == null) settle++;
            // Peaceful on the server itself (the option set before the world is not always used), and no hostile mobs:
            // a flat world spawns slimes, and a dead player fails every check after it
            if (settle == 1) server(sp -> {
                sp.mcServer.setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL);
                for (Entity e : new ArrayList<>(sp.worldObj.loadedEntityList)) if (e instanceof net.minecraft.entity.monster.IMob) e.setDead();
            });
            if (settle > 60) next();
            else if (phaseTicks > 20 * 90) { check("world loads", false, "no player on the ground after 90 s"); finish(); }
            return;
        }
        Phase ph = PHASES.get(phase);
        try {
            ph.tick.accept(phaseTicks);
        } catch (Throwable t) {
            check(ph.name + " ran", false, t.toString());
            Gale.LOG.error("[devtest] {} threw", ph.name, t);
        }
        if (phaseTicks >= ph.length) {
            try {
                ph.exit.run();
            } catch (Throwable t) {
                check(ph.name + " checks ran", false, t.toString());
                Gale.LOG.error("[devtest] {} threw", ph.name, t);
            }
            next();
        }
    }

    private static void next() {
        phase++;
        phaseTicks = 0;
        if (phase >= PHASES.size()) { finish(); return; }
        Phase ph = PHASES.get(phase);
        line("--- " + ph.name);
        try {
            ph.enter.run();
        } catch (Throwable t) {
            check(ph.name + " started", false, t.toString());
            Gale.LOG.error("[devtest] {} threw", ph.name, t);
        }
    }

    private static void launchWorld() {
        phase = -1;
        phaseTicks = 0;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.gameSettings.difficulty = EnumDifficulty.PEACEFUL;
        deleteDir(new File(mc.mcDataDir, "saves/galetest"));
        line("world: making a flat world");
        WorldSettings ws = new WorldSettings(1L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT);
        mc.launchIntegratedServer("galetest", "galetest", ws);
    }

    private static void phases() {
        PHASES.add(new Phase("events fire", 40, () -> {
            ticks = updates = inputs = frames = r2d = r3d = 0;
            sends.set(0);
            receives.set(0);
        }, t -> { }, () -> {
            check("TickEvent every tick", ticks >= 38, ticks + "/40");
            check("UpdateEvent every tick", updates >= 38, updates + "/40");
            check("InputEvent every tick", inputs >= 38, inputs + "/40");
            check("packets seen going out", sends.get() > 0, sends.get() + "");
            check("packets seen coming in", receives.get() > 0, receives.get() + "");
            check("FrameEvent / Render2D / Render3D", frames > 0 && r2d > 0 && r3d > 0, frames + " / " + r2d + " / " + r3d);
            check("network hook in the pipeline", NetHook.isInstalled(), "");
            check("no silent rotation when nobody asks", !RotationManager.isActive(), "");
        }));
        PHASES.add(new Phase("walk, sprint, jump; PlayerSim predicts each tick", 70, () -> {
            module(Sprint.class, true);
            mc.thePlayer.rotationYaw = 0;
            mc.thePlayer.rotationPitch = 0;
            driveForward = true;
            jumpEvery = 17;
            maxSimError = 0;
            simSamples = 0;
            sprintSeen = false;
            collectSim = true;
            ax = mc.thePlayer.posX;
            az = mc.thePlayer.posZ;
        }, t -> sprintSeen |= mc.thePlayer.isSprinting(), () -> {
            driveForward = false;
            jumpEvery = 0;
            collectSim = false;
            predicted = null;
            double moved = Math.hypot(mc.thePlayer.posX - ax, mc.thePlayer.posZ - az);
            check("Sprint module makes you sprint", sprintSeen, "");
            check("walked forward", moved > 8, String.format("%.2f blocks", moved));
            check("PlayerSim matches the game", simSamples > 60 && maxSimError < 0.005, String.format("%d ticks, max error %.6f", simSamples, maxSimError));
            module(Sprint.class, false);
        }));
        PHASES.add(new Phase("silent rotation +90 with movement fix", 40, () -> {
            cam0 = mc.thePlayer.rotationYaw;
            camDrift = 0;
            rotYaw = cam0 + 90;
            rotPitch = 20f;
            driveForward = true;
        }, t -> {
            camDrift = Math.max(camDrift, Math.abs(mc.thePlayer.rotationYaw - cam0));
            if (t == 15) { ax = mc.thePlayer.posX; az = mc.thePlayer.posZ; }
            if (t == 39) { bx = mc.thePlayer.posX; bz = mc.thePlayer.posZ; }
        }, () -> {
            float sentYaw = ServerState.yaw(), sentPitch = ServerState.pitch();
            check("server gets the silent yaw", Math.abs(MathUtil.wrap180(sentYaw - (cam0 + 90))) < 1, String.format("sent %.2f, wanted %.2f", sentYaw, cam0 + 90));
            check("server gets the silent pitch", Math.abs(sentPitch - 20) < 1, String.format("sent %.2f", sentPitch));
            check("camera keeps its own rotation", camDrift < 0.001, String.format("drift %.4f", camDrift));
            float moveYaw = (float) Math.toDegrees(Math.atan2(-(bx - ax), bz - az));
            check("you still walk where the camera looks", Math.abs(MathUtil.wrap180(moveYaw - cam0)) < 10 && Math.hypot(bx - ax, bz - az) > 2,
                String.format("moved toward %.1f, camera %.1f", moveYaw, cam0));
            rotYaw = null;
            driveForward = false;
        }));
        PHASES.add(new Phase("server setback during a silent rotation", 30, () -> {
            cam0 = mc.thePlayer.rotationYaw;
            camDrift = 0;
            rotYaw = cam0 + 90;
            rotPitch = 0f;
        }, t -> {
            if (t == 8) server(sp -> sp.setPositionAndUpdate(sp.posX, sp.posY + 0.5, sp.posZ));
            if (t > 1) camDrift = Math.max(camDrift, Math.abs(mc.thePlayer.rotationYaw - cam0));
        }, () -> {
            check("camera not turned by the teleport", camDrift < 0.001, String.format("drift %.3f", camDrift));
            check("server still has the silent yaw", Math.abs(MathUtil.wrap180(ServerState.yaw() - (cam0 + 90))) < 1, String.format("sent %.2f", ServerState.yaw()));
            rotYaw = null;
        }));
        PHASES.add(new Phase("rotation goes back to the camera", 10, () -> { }, t -> { }, () -> {
            check("silent rotation released", !RotationManager.isActive(), "");
            check("server yaw back at the camera", Math.abs(MathUtil.wrap180(ServerState.yaw() - mc.thePlayer.rotationYaw)) < 1,
                String.format("sent %.2f, camera %.2f", ServerState.yaw(), mc.thePlayer.rotationYaw));
        }));
        PHASES.add(new Phase("rotation with a 30 degree turn limit", 8, () -> {
            cam0 = mc.thePlayer.rotationYaw;
            sentYaws.clear();
            recordYaw = true;
            rotYaw = cam0 + 90;
            rotPitch = 0f;
            rotTurn = 30;
        }, t -> { }, () -> {
            recordYaw = false;
            rotYaw = null;
            rotTurn = 0;
            boolean ok = sentYaws.size() >= 4;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < Math.min(4, sentYaws.size()); i++) {
                float want = cam0 + Math.min(90, 30 * (i + 1));
                s.append(String.format("%.1f ", sentYaws.get(i) - cam0));
                ok &= Math.abs(sentYaws.get(i) - want) < 1;
            }
            check("turns 30, 60, 90, 90", ok, s.toString().trim());
        }));
        PHASES.add(new Phase("rotation released after the limit test", 10, () -> { }, t -> { }, () -> check("released", !RotationManager.isActive(), "")));
        PHASES.add(new Phase("silent slot", 30, () -> {
            ownSlotBefore = mc.thePlayer.inventory.currentItem;
            slotWanted = (ownSlotBefore + 3) % 9;
            slotHeldTicks = slotShownOwn = 0;
            c09.set(0);
        }, t -> {
            if (t == 15) {
                check("server holds the slot", ServerState.slot() == slotWanted, ServerState.slot() + " / " + slotWanted);
                slotWanted = -1;
            }
        }, () -> {
            check("held during the ticks", slotHeldTicks >= 13, slotHeldTicks + " ticks");
            check("hotbar shows your own slot between ticks", slotShownOwn >= 13, slotShownOwn + " ticks");
            check("own slot back for the server", ServerState.slot() == ownSlotBefore && mc.thePlayer.inventory.currentItem == ownSlotBefore,
                ServerState.slot() + " / " + ownSlotBefore);
            check("one slot packet each way, not one a tick", c09.get() == 2, c09.get() + " C09 packets");
        }));
        PHASES.add(new Phase("attack helper and entity tracker", 50, () -> {
            pigId = -1;
            pigHealth = -1;
            server(sp -> {
                EntityPig pig = new EntityPig(sp.worldObj);
                pig.setLocationAndAngles(sp.posX, sp.posY, sp.posZ + 2.2, 0, 0);
                pig.setNoAI(true);
                sp.worldObj.spawnEntityInWorld(pig);
                pigId = pig.getEntityId();
            });
        }, t -> {
            Entity pig = pigId < 0 || mc.theWorld == null ? null : mc.theWorld.getEntityByID(pigId);
            if (t == 15 && pig != null) {
                float[] r = RotationUtil.to(pig);
                mc.thePlayer.rotationYaw = r[0];
                mc.thePlayer.rotationPitch = r[1];
                attackPig = true;
            }
            if (t == 40) server(sp -> {
                Entity e = sp.worldObj.getEntityByID(pigId);
                pigHealth = e instanceof EntityLivingBase ? ((EntityLivingBase) e).getHealth() : -2;
            });
        }, () -> {
            Entity pig = pigId < 0 ? null : mc.theWorld.getEntityByID(pigId);
            check("pig spawned and seen", pig != null, "id " + pigId);
            check("CombatUtil.attack hurt it on the server", pigHealth >= 0 && pigHealth < 10, "health " + pigHealth);
            if (pig != null) {
                check("tracker counts your hit", EntityTracker.hitsByYou(pig) == 1, EntityTracker.hitsByYou(pig) + "");
                check("tracker saw it hurt", EntityTracker.ticksSinceHurt(pig) < 40, EntityTracker.ticksSinceHurt(pig) + " ticks ago");
                check("tracker keeps its positions", EntityTracker.history(pig).size() >= 30, EntityTracker.history(pig).size() + "");
                check("combo counted", EntityTracker.combo() >= 1, EntityTracker.combo() + "");
            }
            attackPig = false;
        }));
        PHASES.add(new Phase("hold bind", 6, () -> {
            Module s = Gale.modules().get(Sprint.class);
            s.setEnabled(false);
            s.setKey(org.lwjgl.input.Keyboard.KEY_K);
            s.setBindMode(Module.BindMode.HOLD);
            Gale.modules().onKey(org.lwjgl.input.Keyboard.KEY_K, true);
            check("pressing the key switches it on", s.isEnabled(), "");
            Gale.modules().onKey(org.lwjgl.input.Keyboard.KEY_K, false);
            check("releasing switches it off", !s.isEnabled(), "");
            Gale.modules().onKey(org.lwjgl.input.Keyboard.KEY_K, true);
        }, t -> { }, () -> {
            Module s = Gale.modules().get(Sprint.class);
            check("off again when the key is not really held", !s.isEnabled(), "");
            s.setKey(0);
            s.setBindMode(Module.BindMode.TOGGLE);
        }));
        PHASES.add(new Phase("SafeWalk keeps you on a pillar", 90, () -> {
            EntityPlayerSP p = mc.thePlayer;
            pillarX = (int) Math.floor(p.posX) + 6;
            pillarZ = (int) Math.floor(p.posZ);
            int ground = (int) Math.floor(p.posY);
            pillarTop = ground + 6;
            // the blocks first; the teleport a few ticks later, once the client has them
            server(sp -> {
                WorldServer w = (WorldServer) sp.worldObj;
                for (int y = ground; y <= pillarTop - 1; y++) w.setBlockState(new BlockPos(pillarX, y, pillarZ), Blocks.stone.getDefaultState());
            });
            module(SafeWalk.class, true);
            minY = 1000;
        }, t -> {
            if (t == 10) server(sp -> sp.setPositionAndUpdate(pillarX + 0.5, pillarTop, pillarZ + 0.5));
            if (t == 35) { mc.thePlayer.rotationYaw = 0; mc.thePlayer.rotationPitch = 0; driveForward = true; }
            if (t > 35) minY = Math.min(minY, mc.thePlayer.posY);
            if (t == 85) driveForward = false;
        }, () -> {
            check("standing on the pillar before walking", pillarTop > 0 && Math.abs(minY - pillarTop) < 1.5, String.format("lowest %.2f, top %d", minY, pillarTop));
            check("SafeWalk stops you at the edge", minY >= pillarTop - 0.001, String.format("lowest %.3f, top %d", minY, pillarTop));
        }));
        PHASES.add(new Phase("without SafeWalk you fall off", 40, () -> {
            module(SafeWalk.class, false);
            driveForward = true;
        }, t -> { }, () -> {
            driveForward = false;
            check("fell off the pillar", mc.thePlayer.posY < pillarTop - 2, String.format("y %.2f, top %d", mc.thePlayer.posY, pillarTop));
        }));
        PHASES.add(new Phase("Velocity 0% horizontal", 30, () -> {
            Module v = module(Velocity.class, true);
            ((NumberSetting) v.getSetting("Horizontal")).set(0);
            ((NumberSetting) v.getSetting("Vertical")).set(100);
            ((NumberSetting) v.getSetting("Chance")).set(100);
            s12x = Double.NaN;
        }, t -> { if (t == 5) knockback(); }, () -> {
            check("knockback packet arrived", !Double.isNaN(s12x), "");
            check("horizontal knockback removed", s12x == 0 && s12z == 0 && s12y > 0.3, String.format("%.3f, %.3f, %.3f", s12x, s12y, s12z));
        }));
        PHASES.add(new Phase("Velocity gives way when B4 reports its Velocity", 30, () -> {
            Bridge.receive("b4.enabled", "KillAura,Velocity");
            s12x = Double.NaN;
        }, t -> {
            if (t == 2) check("module is deferred", Gale.modules().get(Velocity.class).isDeferred(), "");
            if (t == 5) knockback();
            if (t % 10 == 0) Bridge.receive("b4.enabled", "KillAura,Velocity");
        }, () -> {
            check("knockback untouched while deferred", Math.abs(s12x) > 0.3 || Math.abs(s12z) > 0.3, String.format("%.3f, %.3f, %.3f", s12x, s12y, s12z));
            Bridge.receive("b4.enabled", "");
            check("not deferred after B4 reports it off", !Gale.modules().get(Velocity.class).isDeferred(), "");
            module(Velocity.class, false);
        }));
        PHASES.add(new Phase("bridge over HTTP", 5, () -> {
            String state = http("/gale");
            check("GET /gale gives the state", state != null && state.contains("\"Velocity\""), state == null ? "no answer" : state.length() + " chars");
            http("/gale/set?key=devtest&value=42");
            check("GET /gale/set stores a value", "42".equals(Bridge.get("devtest")), String.valueOf(Bridge.get("devtest")));
            Bridge.publish("hello", "world");
            check("GET /gale/get reads a published value", "world".equals(http("/gale/get?key=hello")), "");
        }, t -> { }, () -> { }));
        PHASES.add(new Phase("commands", 5, () -> {
            int r = ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/gale set Velocity Horizontal 50");
            check("/gale set changes a setting", r != 0 && ((NumberSetting) Gale.modules().get(Velocity.class).getSetting("Horizontal")).get() == 50, "");
            check("/gale list runs", ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/gale list") != 0, "");
            ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/gale bridge b4 KillAura,Hit Select");
            check("/gale bridge b4 reports modules", External.isEnabled("b4", "Hit Select") && External.anyEnabled("KillAura"), "");
            Bridge.receive("b4.enabled", "");
        }, t -> { }, () -> { }));
        scriptApiPhases();
        OptimizeTest.phases();
        guiPhases();
        PHASES.add(new Phase("config saved", 3, () -> Gale.config().save(), t -> { }, () -> {
            File f = new File(new File(mc.mcDataDir, "galemod"), "config.json");
            String text = "";
            try {
                text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            check("config.json written with modules", text.contains("\"Velocity\"") && text.contains("\"Horizontal\""), f.getPath());
        }));
    }

    // ------------------------------------------------------------------ the GUI, clicked through and photographed

    private static ClickGui gui;
    static volatile String shot;
    private static boolean velocityWasOn;

    private static void guiPhases() {
        Module aim = Gale.modules().get(AimAssist.class), vel = Gale.modules().get(Velocity.class);
        GuiDemo demo = Gale.modules().get(GuiDemo.class);
        ClickGuiModule guiModule = Gale.modules().get(ClickGuiModule.class);
        PHASES.add(new Phase("GUI: opens on Combat", 16, () -> {
            frames = 0;
            gui = new ClickGui();
            gui.select(Category.COMBAT);
            ClickGui.setExpanded(aim, true);
            ClickGui.setExpanded(vel, false);
            mc.displayGuiScreen(gui);
        }, t -> { if (t == 14) shot = "gale-gui-1-combat"; }, () -> {
            check("ClickGUI open and drawing", mc.currentScreen == gui && frames > 5, frames + " frames");
            check("Combat lists its modules", gui.shownCount() == Gale.modules().in(Category.COMBAT).size(), gui.shownCount() + " shown");
        }));
        PHASES.add(new Phase("GUI: clicking switches, settings and keys", 12, () -> velocityWasOn = vel.isEnabled(), t -> {
            BoolSetting explosions = (BoolSetting) vel.getSetting("Explosions");
            NumberSetting horizontal = (NumberSetting) vel.getSetting("Horizontal");
            // Combat holds more modules than fit (the ones from B4 come first): Velocity is the last, so its settings
            // are at the end of the list; Aim Example's key box is near the start
            // (the opened row grows for a few frames: keep at the end until the clicks)
            if (t >= 3 && t <= 5) gui.scrollToEnd(true);
            switch (t) {
                case 1:
                    check("the switch switches the module", gui.click(vel, "SWITCH", 0, 0, 0.5f) && vel.isEnabled() != velocityWasOn, "");
                    check("a click on the row opens its settings", gui.click(vel, "ROW", 0, 0, 0.3f), "");
                    break;
                case 6: {
                    boolean before = explosions.get();
                    check("a click on a bool switches it", gui.click(explosions, "BOOL", 0, 0, 0.5f) && explosions.get() != before, "");
                    gui.click(horizontal, "SLIDER", 0, 0, 0f);
                    check("slider pressed at the left end", horizontal.get() == horizontal.min, horizontal.display());
                    gui.click(horizontal, "SLIDER", 0, 0, 1f);
                    check("slider pressed at the right end", horizontal.get() == horizontal.max, horizontal.display());
                    gui.click(horizontal, "CELL", 0, 1, 0.5f);
                    check("right click puts a setting back to default", horizontal.get() == horizontal.getDefault(), horizontal.display());
                    explosions.set(before);
                    gui.scrollToEnd(false);
                    break;
                }
                case 7:
                    gui.click(aim, "KEY", 0, 0, 0.5f);
                    check("a click on the key box waits for a key", gui.bindingModule() == aim, "");
                    gui.type('r', Keyboard.KEY_R);
                    check("the next key is bound", aim.getKey() == Keyboard.KEY_R, gale.util.KeyUtil.name(aim.getKey()));
                    gui.click(aim, "KEY", 0, 1, 0.5f);
                    check("right click on the key box clears it", aim.getKey() == 0, "");
                    break;
                case 8:
                    check("a tab click shows the category", gui.click(Category.CLIENT, "TAB", 0, 0, 0.5f) && gui.shownCount() == Gale.modules().in(Category.CLIENT).size(), gui.shownCount() + " shown");
                    break;
                default:
            }
        }, () -> {
            vel.setEnabled(velocityWasOn);
            ClickGui.setExpanded(vel, false);
        }));
        PHASES.add(new Phase("GUI: the Client category", 10, () -> {
            gui.select(Category.CLIENT);
            ClickGui.setExpanded(Gale.modules().get("联系我们"), true);
            ClickGui.setExpanded(Gale.modules().get("Targets"), true);
            ClickGui.setExpanded(Gale.modules().get("Rotations"), true);
        }, t -> { if (t == 8) shot = "gale-gui-2-client"; }, () -> ClickGui.setExpanded(Gale.modules().get("联系我们"), false)));
        PHASES.add(new Phase("GUI: every kind of setting", 14, () -> {
            gui.select(Category.WORLD);
            ClickGui.setExpanded(demo, true);
        }, t -> {
            switch (t) {
                case 3:
                    check("a click on a colour opens the picker", gui.click(demo.color, "COLOR", 0, 0, 0.5f) && gui.openPicker() == demo.color, "");
                    break;
                case 5: {
                    int before = demo.color.getValue();
                    gui.click(demo.color, "PICK_HUE", 0, 0, 0.5f);
                    check("the hue bar changes the colour", demo.color.getValue() != before, String.format("#%08X", demo.color.getValue()));
                    gui.click(demo.color, "PICK_ALPHA", 0, 0, 0.5f);
                    int alpha = demo.color.getValue() >>> 24;
                    check("the alpha bar sets the alpha", alpha > 110 && alpha < 145, String.valueOf(alpha));
                    gui.click(demo.shortMode, "SEGMENT", 2, 0, 0.5f);
                    check("a segment click picks that option", demo.shortMode.is("Three"), demo.shortMode.get());
                    gui.click(demo.manyModes, "CYCLE", 1, 0, 0.5f);
                    check("the right half of a cycle box goes to the next option", demo.manyModes.is("Sixth"), demo.manyModes.get());
                    gui.click(demo.range, "SLIDER", 0, 0, 1f);
                    check("a range moves its nearer end", demo.range.high() == demo.range.max && demo.range.low() == 8, demo.range.display());
                    gui.click(demo.text, "TEXT", 0, 0, 0.5f);
                    gui.type('!', Keyboard.KEY_1);
                    check("typing into a text setting", "hello!".equals(demo.text.get()) && gui.editingSetting() == demo.text, demo.text.get());
                    break;
                }
                case 12:
                    shot = "gale-gui-3-settings";
                    break;
                default:
            }
        }, () -> { }));
        PHASES.add(new Phase("GUI: typing searches", 10, () -> {
            gui.type((char) 0, Keyboard.KEY_RETURN);
            for (char c : "vel".toCharArray()) gui.type(c, Keyboard.getKeyIndex(String.valueOf(c).toUpperCase()));
        }, t -> { if (t == 8) shot = "gale-gui-4-search"; }, () -> {
            check("search finds Velocity", gui.shownCount() >= 1, gui.shownCount() + " shown");
            gui.type((char) 0, Keyboard.KEY_ESCAPE);
            check("Esc clears the search", gui.shownCount() == Gale.modules().in(Category.WORLD).size(), gui.shownCount() + " shown");
        }));
        PHASES.add(new Phase("GUI: tiled glass", 8, () -> {
            guiModule.glass.set("Tiled");
            gui.select(Category.COMBAT);
        }, t -> { if (t == 6) shot = "gale-gui-5-tiled"; }, () -> guiModule.glass.set("Framed")));
        PHASES.add(new Phase("GUI: scale 3", 8, () -> guiModule.scale.set("3"), t -> { if (t == 6) shot = "gale-gui-6-scale3"; }, () -> guiModule.scale.set("Auto")));
        PHASES.add(new Phase("GUI: the Assist category", 10, () -> {
            guiModule.scale.set("Auto");
            gale.script.ScriptEvents.on("packetIn", "LeaderCombat", (n, a) -> 0);
            gale.script.ScriptEvents.on("attack", "LeaderCombat", (n, a) -> 0);
            gale.script.ScriptEvents.on("socket", "ChatBridge", (n, a) -> 0);
            gui.select(Category.ASSIST);
            ClickGui.setExpanded(Gale.modules().get("Script System"), true);
        }, t -> { if (t == 8) shot = "gale-gui-7-assist"; }, () -> {
            check("Assist lists the script API's areas", gui.shownCount() == 8, gui.shownCount() + " shown");
            gale.script.ScriptEvents.off(null, "LeaderCombat");
            gale.script.ScriptEvents.off(null, "ChatBridge");
            ClickGui.setExpanded(Gale.modules().get("Script System"), false);
        }));
        PHASES.add(new Phase("GUI: the Optimize category", 80, () -> {
            Gale.modules().get("Profiler").setEnabled(true);
            Gale.modules().get("Performance").setEnabled(true);
            gui.select(Category.OPTIMIZE);
            ClickGui.setExpanded(Gale.modules().get("Performance"), true);
        }, t -> { if (t == 78) shot = "gale-gui-8-optimize"; }, () -> {
            check("Optimize lists Performance and Profiler", gui.shownCount() == 2, gui.shownCount() + " shown");
            Gale.modules().get("Profiler").setEnabled(false);
            Gale.modules().get("Performance").setEnabled(false);
            ClickGui.setExpanded(Gale.modules().get("Performance"), false);
        }));
        PHASES.add(new Phase("GUI: Esc closes", 4, () -> gui.type((char) 0, Keyboard.KEY_ESCAPE), t -> { }, () -> {
            check("Esc closes the screen", mc.currentScreen == null, String.valueOf(mc.currentScreen));
            ClickGui.setExpanded(aim, false);
            ClickGui.setExpanded(demo, false);
        }));
        GdPortTest.guiPhases();
    }

    // ------------------------------------------------------------------ the script API, driven like a B4 script would

    private static void scriptApiPhases() {
        PHASES.add(new Phase("script API: a script links and calls", 5, () -> {
            script = new FakeScript();
            check("gale link puts the API in the script's bridge", script.link(), "");
            check("bridge.get(\"gale\") is Gale's API", script.gale == ScriptApi.INSTANCE, String.valueOf(script.gale));
            check("calls by text", Gale.VERSION.equals(script.call("version")), String.valueOf(script.call("version")));
            Object help = script.call("help");
            check("help lists the functions", help instanceof List && ((List<?>) help).size() >= 8, String.valueOf(help));
            check("containsKey knows the functions", script.gale.containsKey("attackLocal") && !script.gale.containsKey("noSuchThing"), "");
            check("an unknown function gives null", script.call("noSuchThing") == null, "");
            Functions.add("devtest.echo", "<x>", "gives its argument back", a -> a.get(0));
            Functions.add("devtest.sum", "<a> <b>", "adds", a -> a.num(0) + a.num(1));
            check("text arguments read as numbers", Double.valueOf(3).equals(script.call("devtest.sum 1 2")), String.valueOf(script.call("devtest.sum 1 2")));
            Object e = script.call("devtest.echo", script.wrap(mc.thePlayer));
            check("B4 Entity in, B4 Entity out", e instanceof FakeDefaults.Entity && ((FakeDefaults.Entity) e).entityId == mc.thePlayer.getEntityId(), String.valueOf(e));
            Object v = script.call("devtest.echo", script.vec(1, 2, 3));
            check("B4 Vec3 in, B4 Vec3 out", v instanceof FakeDefaults.Vec3 && ((FakeDefaults.Vec3) v).y == 2, String.valueOf(v));
            Object pk = script.call("devtest.echo", new FakeDefaults.CPacket(new C02PacketUseEntity(mc.thePlayer, C02PacketUseEntity.Action.ATTACK)));
            check("packets come back as B4's own wrapper (C02)", pk instanceof FakeDefaults.C02 && "ATTACK".equals(((FakeDefaults.C02) pk).action), String.valueOf(pk));
            Object it = script.call("devtest.echo", new ItemStack(Items.stick));
            check("game ItemStack goes out as B4's", it instanceof FakeDefaults.ItemStack, String.valueOf(it));
            Object bad = script.call("on", "noSuchEvent", "DevTestScript", (Comparator<Object>) (n, a) -> 0);
            check("listening to an unknown event gives null", bad == null, String.valueOf(bad));
            check("timer is 1.0", Double.valueOf(1.0).equals(script.call("timer")), String.valueOf(script.call("timer")));
            EntityPlayerSP me = mc.thePlayer;
            double ey = me.posY + me.getEyeHeight();
            Object down = script.call("rayTraceBlocks", me.posX, ey, me.posZ, me.posX, me.posY - 2, me.posZ);
            Object up = script.call("rayTraceBlocks", me.posX, ey, me.posZ, me.posX, ey + 3, me.posZ);
            check("rayTraceBlocks hits the ground below", down instanceof Object[] && ((Object[]) down)[1] instanceof FakeDefaults.Vec3 && "UP".equals(((Object[]) down)[2]),
                down instanceof Object[] ? java.util.Arrays.toString((Object[]) down) : String.valueOf(down));
            check("rayTraceBlocks: open air gives null", up == null, String.valueOf(up));
            FakeDefaults.bridge.clear();
        }, t -> { }, () -> check("the API is back after a script cleared the bridge", FakeDefaults.bridge.get("gale") == ScriptApi.INSTANCE, "")));

        PHASES.add(new Phase("script API: attack hook and attackLocal", 6, () -> {
            EntityPlayerSP p = mc.thePlayer;
            EntityOtherPlayerMP dummy = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(UUID.randomUUID(), "GaleDummy"));
            dummy.setPositionAndRotation(p.posX, p.posY, p.posZ + 2, 0, 0);
            mc.theWorld.addEntityToWorld(DUMMY_ID, dummy);
            c02.set(0);
            String plain = hit(() -> mc.playerController.attackEntity(p, dummy));
            check("plain hit: x0.6 and sprint stops", plain.equals("0.180 false"), plain);
            Object[] seen = new Object[1];
            script.on("attack", (n, a) -> {
                Object[] args = (Object[]) a;
                seen[0] = args[0];
                args[1] = 1.0;
                args[2] = true;
                return 0;
            });
            String kept = hit(() -> mc.playerController.attackEntity(p, dummy));
            check("script sets slowdown 1.0, keep sprint", kept.equals("0.300 true"), kept);
            check("script got the target as a B4 Entity", seen[0] instanceof FakeDefaults.Entity && ((FakeDefaults.Entity) seen[0]).entityId == DUMMY_ID, String.valueOf(seen[0]));
            script.on("attack", (n, a) -> 1);
            String none = hit(() -> mc.playerController.attackEntity(p, dummy));
            check("script cancels: nothing on your side", none.equals("0.300 true"), none);
            script.off();
            String local = hit(() -> script.call("attackLocal", script.wrap(dummy), 0.5, false));
            check("attackLocal: x0.5, sprint stops", local.equals("0.150 false"), local);
        }, t -> { }, () -> {
            check("three C02 went out (attackLocal sends none)", c02.get() == 3, c02.get() + " C02");
            mc.theWorld.removeEntityFromWorld(DUMMY_ID);
        }));

        PHASES.add(new Phase("script API: velocity hook", 34, () -> {
            velIn = velAfter = null;
            velCapture = true;
            script.on("velocity", (n, a) -> {
                Object[] v = (Object[]) a;
                if (velIn == null) velIn = new double[]{ (Double) v[0], (Double) v[1], (Double) v[2] };
                v[0] = (Double) v[0] / 2;
                v[2] = (Double) v[2] / 2;
                return 0;
            });
        }, t -> {
            if (t == 5) knockback();
            if (t == 16) {
                check("script saw the knockback", velIn != null && Math.abs(velIn[0] - 0.6) < 1e-3 && Math.abs(velIn[1] - 0.4) < 1e-3, velIn == null ? "none" : String.format("%.3f, %.3f, %.3f", velIn[0], velIn[1], velIn[2]));
                check("halved knockback applied", velAfter != null && Math.abs(velAfter[0] - 0.3) < 1e-3 && Math.abs(velAfter[2] - 0.3) < 1e-3 && Math.abs(velAfter[1] - 0.4) < 1e-3,
                    velAfter == null ? "none" : String.format("%.3f, %.3f, %.3f", velAfter[0], velAfter[1], velAfter[2]));
                velIn = velAfter = null;
                script.on("velocity", (n, a) -> {
                    Object[] v = (Object[]) a;
                    if (velIn == null) velIn = new double[]{ (Double) v[0], (Double) v[1], (Double) v[2] };
                    return 1;
                });
            }
            if (t == 20) knockback();
        }, () -> {
            check("cancelled knockback: motion untouched", velIn != null && velAfter != null && velAfter[1] < 0.3 && Math.abs(velAfter[0]) < 0.2,
                velAfter == null ? "none" : String.format("%.3f, %.3f, %.3f", velAfter[0], velAfter[1], velAfter[2]));
            velCapture = false;
            script.off();
        }));

        PHASES.add(new Phase("script API: entity status hook", 60, () -> {
            statusPigId = -1;
            statusSeen = -1;
            statusEntity = null;
            statusDropped = false;
            statusInSeen = -1;
            statusInNetty = false;
            pigHurtShown = pigHurtCancelled = false;
            server(sp -> {
                EntityPig pig = new EntityPig(sp.worldObj);
                pig.setLocationAndAngles(sp.posX + 2, sp.posY, sp.posZ, 0, 0);
                pig.setNoAI(true);
                sp.worldObj.spawnEntityInWorld(pig);
                statusPigId = pig.getEntityId();
            });
        }, t -> {
            if (t == 8) {
                script.on("entityStatusIn", (n, a) -> {
                    Object[] s = (Object[]) a;
                    if ((Integer) s[0] == statusPigId && statusInSeen < 0) {
                        statusInSeen = (Integer) s[1];
                        statusInNetty = !mc.isCallingFromMinecraftThread();
                    }
                    return 0;
                });
                script.on("entityStatus", (n, a) -> {
                    Object[] s = (Object[]) a;
                    if ((Integer) s[0] == statusPigId) {
                        statusSeen = (Integer) s[1];
                        statusEntity = s[2];
                    }
                    return 0;
                });
                hurtPig();
            }
            Entity pig = mc.theWorld.getEntityByID(statusPigId);
            int hurt = pig instanceof EntityLivingBase ? ((EntityLivingBase) pig).hurtTime : -1;
            if (t > 8 && t < 30 && hurt > 0) pigHurtShown = true;
            if (t == 32) {
                pigHurtCancelled = true;
                script.on("entityStatus", (n, a) -> {
                    if ((Integer) ((Object[]) a)[0] != statusPigId) return 0;
                    statusDropped = true;
                    return 1;
                });
                hurtPig();
            }
            if (t > 32 && hurt > 0) pigHurtCancelled = false;
        }, () -> {
            check("script saw the pig's hurt status", statusSeen == 2, "status " + statusSeen);
            check("entityStatusIn: the id and status on arrival, on the network thread", statusInSeen == 2 && statusInNetty, "status " + statusInSeen + ", network thread " + statusInNetty);
            check("with the entity as a B4 Entity", statusEntity instanceof FakeDefaults.Entity, String.valueOf(statusEntity));
            check("hurt animation when passed on", pigHurtShown, "");
            check("the second hurt status reached the script", statusDropped, "");
            check("no hurt animation when the script drops it", pigHurtCancelled, "");
            script.off();
            server(sp -> {
                Entity pig = sp.worldObj.getEntityByID(statusPigId);
                if (pig != null) pig.setDead();
            });
        }));

        PHASES.add(new Phase("script API: fakeUse full / render / off", 68, () -> {
            server(sp -> {
                sp.inventory.setInventorySlotContents(sp.inventory.currentItem, new ItemStack(Items.diamond_sword));
                sp.inventoryContainer.detectAndSendChanges();
            });
            c07c08.set(0);
            fakeUsingReported = false;
        }, t -> {
            EntityPlayerSP p = mc.thePlayer;
            if (t == 6) {
                check("holding a sword", p.getHeldItem() != null && p.getHeldItem().getItem() == Items.diamond_sword, String.valueOf(p.getHeldItem()));
                p.rotationYaw = 0;
                script.call("fakeUse full");
                fakeMode = 1;
                useAtInput = inputsCounted = framesUsing = framesCounted = 0;
                ax = p.posX;
                az = p.posZ;
                driveForward = true;
            }
            if (t == 16) fakeUsingReported = Boolean.TRUE.equals(script.call("fakeUsing"));
            if (t == 26) {
                movedFull = Math.hypot(p.posX - ax, p.posZ - az);
                check("full: using at every movement", useAtInput >= 18 && useAtInput == inputsCounted, useAtInput + "/" + inputsCounted);
                check("full: frames show the block", framesUsing > 0 && framesUsing == framesCounted, framesUsing + "/" + framesCounted);
                script.call("fakeUse render");
                fakeMode = 2;
                useAtInput = inputsCounted = framesUsing = framesCounted = 0;
                ax = p.posX;
                az = p.posZ;
            }
            if (t == 46) {
                movedRender = Math.hypot(p.posX - ax, p.posZ - az);
                check("render: not using while moving", useAtInput == 0 && inputsCounted >= 18, useAtInput + "/" + inputsCounted);
                check("render: frames show the block", framesUsing > 0 && framesUsing == framesCounted, framesUsing + "/" + framesCounted);
                script.call("fakeUse off");
                fakeMode = 3;
                useAtInput = inputsCounted = framesUsing = framesCounted = 0;
            }
        }, () -> {
            driveForward = false;
            check("off: never using", useAtInput == 0 && framesUsing == 0 && !mc.thePlayer.isUsingItem(), useAtInput + " / " + framesUsing);
            check("full slows you like blocking", movedFull > 0.2 && movedFull < movedRender * 0.5, String.format("full %.2f, render %.2f blocks", movedFull, movedRender));
            check("fakeUsing reports Gale's state", fakeUsingReported, "");
            check("nothing sent: no C07 / C08", c07c08.get() == 0, c07c08.get() + "");
            fakeMode = 0;
        }));

        PHASES.add(new Phase("script API: swingLocal", 5, () -> {
            c0a.set(0);
            mc.thePlayer.isSwingInProgress = false;
            mc.thePlayer.swingProgressInt = 0;
            script.call("swingLocal");
            check("arm swings", mc.thePlayer.isSwingInProgress, "");
        }, t -> { }, () -> check("no C0A sent", c0a.get() == 0, c0a.get() + " C0A")));

        PHASES.add(new Phase("another client's hook inside the game's input keeps its changes (B4's onPostPlayerInput)", 12, () -> {
            EntityPlayerSP p = mc.thePlayer;
            innerHookInputs = innerHookKept = 0;
            // like B4: once the keys are read, its hook (and its scripts' client.setForward) change the player's input
            p.movementInput = new MovementInputFromOptions(mc.gameSettings) {
                @Override
                public void updatePlayerMoveState() {
                    super.updatePlayerMoveState();
                    mc.thePlayer.movementInput.moveForward = 0.5f;
                }
            };
            innerHookTest = true;
        }, t -> { }, () -> {
            innerHookTest = false;
            check("Gale wrapped it and the hook's change came through", innerHookInputs >= 8 && innerHookKept == innerHookInputs, innerHookKept + "/" + innerHookInputs);
            mc.thePlayer.movementInput = new MovementInputFromOptions(mc.gameSettings);
        }));
        ScriptApiTest.phases();
    }

    /** Sprinting at 0.3, 0.3; runs the hit; gives "motionX sprinting". */
    private static String hit(Runnable attack) {
        EntityPlayerSP p = mc.thePlayer;
        p.motionX = 0.3;
        p.motionZ = 0.3;
        p.setSprinting(true);
        attack.run();
        return String.format("%.3f %s", p.motionX, p.isSprinting());
    }

    private static void hurtPig() {
        server(sp -> {
            Entity pig = sp.worldObj.getEntityByID(statusPigId);
            if (pig != null) pig.attackEntityFrom(DamageSource.generic, 1f);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Module module(Class<? extends Module> type, boolean on) {
        Module m = Gale.modules().get(type);
        m.setEnabled(on);
        return m;
    }

    private static void knockback() {
        server(sp -> {
            sp.motionX = 0.6;
            sp.motionY = 0.4;
            sp.motionZ = 0.6;
            sp.velocityChanged = true;
        });
    }

    static void server(java.util.function.Consumer<EntityPlayerMP> action) {
        IntegratedServer s = mc.getIntegratedServer();
        if (s == null) return;
        java.util.UUID id = mc.thePlayer.getUniqueID();
        s.addScheduledTask(() -> {
            EntityPlayerMP sp = s.getConfigurationManager().getPlayerByUUID(id);
            if (sp != null) action.accept(sp);
        });
    }

    private static String http(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + Bridge.port() + path).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try (InputStream in = c.getInputStream(); Scanner sc = new Scanner(in, "UTF-8")) {
                sc.useDelimiter("\\A");
                return sc.hasNext() ? sc.next() : "";
            }
        } catch (Exception e) {
            return null;
        }
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        line((ok ? "PASS " : "FAIL ") + what + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    static void line(String s) {
        REPORT.add(s);
        Gale.LOG.info("[devtest] {}", s);
    }

    /** Ends the running phase after this tick (its checks run now): for phases that wait for something with a time limit. */
    static void skipRest() {
        if (phase >= 0 && phase < PHASES.size()) phaseTicks = Math.max(phaseTicks, PHASES.get(phase).length);
    }

    private static void finish() {
        if (finished) return;
        finished = true;
        check("no listener threw", Gale.BUS.errorCount() == 0, Gale.BUS.errorCount() + " errors");
        line(String.format("=== %d passed, %d failed", passed, failed));
        try {
            Files.write(new File(mc.mcDataDir, "galemod-devtest.txt").toPath(), String.join(System.lineSeparator(), REPORT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Gale.LOG.error("[devtest] could not write the report", e);
        }
        // The focused test can close normally. Sending a disconnect first can stop the 1.8.9 server
        // before loadWorld's shutdown task reaches it, leaving the client waiting on a dead server.
        if (Boolean.getBoolean("gale.gdtest") || Boolean.getBoolean("gale.killfxtest") || Boolean.getBoolean("gale.jetttest") || Boolean.getBoolean("gale.katanatest") || Boolean.getBoolean("gale.kuronamitest") || Boolean.getBoolean("gale.scopetest") || Boolean.getBoolean("gale.ballisticstest") || Boolean.getBoolean("gale.islandtest") || Boolean.getBoolean("gale.b4scripttest") || Boolean.getBoolean("gale.expotest") || Boolean.getBoolean("gale.b4porttest") || Boolean.getBoolean("gale.jappylaytest") || Boolean.getBoolean("gale.b4rendertest") || Boolean.getBoolean("gale.ravengui") || Boolean.getBoolean("gale.meowtilstest") || Boolean.getBoolean("gale.spotifytest") || Boolean.getBoolean("gale.hudtest") || Boolean.getBoolean("gale.lyricstest") || Boolean.getBoolean("gale.fvtest")) { mc.shutdown(); return; }
        // leave the world first so the integrated server stops cleanly, then quit
        if (mc.theWorld != null) {
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
            // as the game does when you quit: a frame with no world and no screen would throw
            mc.displayGuiScreen(new GuiMainMenu());
        }
        gale.core.Scheduler.later(40, mc::shutdown);
    }

    private static void deleteDir(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteDir(c);
        f.delete();
    }
}
