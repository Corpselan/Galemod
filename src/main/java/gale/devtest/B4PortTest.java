package gale.devtest;

import gale.Gale;
import gale.b4port.B4Values;
import gale.b4port.module.ModuleManager;
import gale.b4port.module.combat.KillAuraModule;
import gale.b4port.module.player.BedAuraModule;
import gale.b4script.ScriptProtected;
import gale.bridge.B4State;
import gale.bridge.External;
import gale.core.MotionHook;
import gale.event.Priority;
import gale.event.impl.AntiCheatFlagEvent;
import gale.event.impl.BedWarsAlertEvent;
import gale.event.impl.IrcMessageEvent;
import gale.event.impl.LocrawEvent;
import gale.event.impl.MotionEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.PostUpdateEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.Setting;
import gale.script.ScriptApi;
import gale.util.KeyUtil;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumDifficulty;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Gale's ports of B4's modules (gradlew runClient -Pb4porttest): their settings as in OpenB4, the motion event and safe
 * walk they run on, KillAura hitting a mob (and giving way while "B4" runs its KillAura, then seeing B4's target),
 * AimAssist turning the camera, Scaffold bridging, BedAura breaking a bed, GaleLink's b4.report / b4.event, and the
 * rest switched on without errors.
 */
final class B4PortTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<Module, Boolean> SAVED = new LinkedHashMap<>();
    private static final List<String> PORTS = Arrays.asList("KillAura", "AimAssist", "Backtrack", "Lag Range", "BedAura", "Scaffold", "Tower",
        "Bed Wars", "AntiCheat", "AntiBot", "B4 Settings", "Locraw");

    private static final AtomicInteger attacks = new AtomicInteger(), locraws = new AtomicInteger();
    private static volatile int golemId = Integer.MIN_VALUE;
    private static volatile Boolean c03Ground;
    private static volatile float c03Yaw = Float.NaN;
    private static boolean motionTest, safeWalk, b4Reports;
    private static int rotatedByKillAura;
    private static float startDiff, healthBefore;
    private static double startX, startY, startZ, minY;
    private static BlockPos platform, bedFoot, spawn;
    private static boolean bedSeen, progressSeen;
    private static final Object[] events = new Object[4];

    private B4PortTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Port " + name, ticks, enter, tick, exit));
    }

    private static Module module(String name) { return Gale.modules().get(name); }

    private static gale.b4port.module.Module port(String name) { return (gale.b4port.module.Module) module(name); }

    private static Object value(String module, String setting) {
        Setting<?> s = module(module).getSetting(setting);
        return s == null ? null : s.getValue();
    }

    static void phases() {
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> {
            if (e.isCancelled()) return;
            C02PacketUseEntity c02 = e.as(C02PacketUseEntity.class);
            if (c02 != null && c02.getAction() == C02PacketUseEntity.Action.ATTACK && mc.theWorld != null) {
                Entity hit = c02.getEntityFromWorld(mc.theWorld);
                if (hit != null && hit.getEntityId() == golemId) attacks.incrementAndGet();
            }
            C03PacketPlayer c03 = e.as(C03PacketPlayer.class);
            if (c03 != null && motionTest) {
                c03Ground = c03.isOnGround();
                if (c03.getRotating()) c03Yaw = c03.getYaw();
            }
            C01PacketChatMessage c01 = e.as(C01PacketChatMessage.class);
            if (c01 != null && c01.getMessage().startsWith("/locraw")) locraws.incrementAndGet();
        }, "port-test");
        Gale.BUS.listen(MotionEvent.class, Priority.NORMAL, e -> {
            if (!motionTest) return;
            e.onGround = false;
            e.yaw = 123f;
        }, "port-test");
        Gale.BUS.listen(UpdateEvent.class, Priority.NORMAL, e -> {
            if (safeWalk) MotionHook.safeWalk();
            // GaleLink's report, as a B4 script makes it (through the map from bridge.get("gale"))
            if (b4Reports) {
                External.report("b4", Collections.singletonList("KillAura"));
                ScriptApi.INSTANCE.put("b4.report", new Object[]{ golem(), null, null, null, null, null, false, false, null });
            }
        }, "port-test");
        Gale.BUS.listen(PostUpdateEvent.class, Priority.NORMAL, e -> {
            if (gale.core.RotationManager.owner() == module("KillAura")) rotatedByKillAura++;
            if (BedAuraModule.target != null) bedSeen = true;
            if (B4Values.bedAuraProgress()[0] > 0f) progressSeen = true;
        }, "port-test");
        Gale.BUS.listen(BedWarsAlertEvent.class, Priority.NORMAL, e -> events[0] = e.type + "/" + e.fromB4, "port-test");
        Gale.BUS.listen(AntiCheatFlagEvent.class, Priority.NORMAL, e -> events[1] = e.check + "/" + e.fromB4, "port-test");
        Gale.BUS.listen(LocrawEvent.class, Priority.NORMAL, e -> events[2] = e.json, "port-test");
        Gale.BUS.listen(IrcMessageEvent.class, Priority.NORMAL, e -> events[3] = e.message, "port-test");

        phase("modules and B4's settings", 5, () -> {
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != module("HUD")) m.setEnabled(false);
            // where the player stands on the flat world's ground: every phase starts from there, healed
            spawn = new BlockPos(mc.thePlayer);
            // B4's defaults (they may have been changed by an earlier run)
            for (String n : PORTS) if (module(n) != null) for (Setting<?> s : module(n).getSettings()) s.reset();
        }, t -> { }, () -> {
            StringBuilder missing = new StringBuilder();
            for (String n : PORTS) if (module(n) == null) missing.append(n).append(' ');
            check("every ported module is registered", missing.length() == 0, missing.length() == 0 ? PORTS.size() + " modules" : "missing " + missing);
            check("KillAura: B4's defaults (APS 12, AutoBlock Manual, Silent, range 3)", Double.valueOf(12).equals(value("KillAura", "APS"))
                && "Manual".equals(value("KillAura", "AutoBlock")) && "Silent".equals(value("KillAura", "Rotation mode"))
                && Double.valueOf(3).equals(value("KillAura", "Range (attack)")), value("KillAura", "APS") + " " + value("KillAura", "AutoBlock"));
            check("Scaffold: Strict rotation, Tower off", "Strict".equals(value("Scaffold", "Rotation")) && Boolean.FALSE.equals(value("Scaffold", "Tower")),
                value("Scaffold", "Rotation"));
            check("BedAura: Legit, range 4.5, rate 0.2 s", "Legit".equals(value("BedAura", "Break mode")) && Double.valueOf(4.5).equals(value("BedAura", "Range"))
                && Double.valueOf(0.2).equals(value("BedAura", "Rate")), value("BedAura", "Break mode"));
            check("Tower and B4 Settings are always on (as in B4)", module("Tower").isAlwaysOn() && module("B4 Settings").isAlwaysOn(), "");
            check("modules that B4 has give way to it (Defer)", module("KillAura").getSetting("Defer") instanceof BoolSetting
                && module("Bed Wars").getSetting("Defer") instanceof BoolSetting && module("AntiBot").getSetting("Defer") == null, "");
            check("ported modules sit in B4's categories", module("KillAura").getCategory() == gale.module.Category.COMBAT
                && module("Scaffold").getCategory() == gale.module.Category.PLAYER && module("Bed Wars").getCategory() == gale.module.Category.MINIGAMES, "");
        });

        phase("motion event changes the movement packet", 12, () -> {
            c03Ground = null;
            c03Yaw = Float.NaN;
            motionTest = true;
        }, t -> { }, () -> {
            motionTest = false;
            check("MotionEvent: onGround false reaches the packet (standing)", Boolean.FALSE.equals(c03Ground) && mc.thePlayer.onGround, "c03 ground " + c03Ground);
            check("MotionEvent: a changed yaw reaches the packet", c03Yaw == 123f, "c03 yaw " + c03Yaw);
        });

        safeWalkPhase(false);
        safeWalkPhase(true);

        phase("KillAura hits a mob", 80, () -> {
            toGround(0f);
            give(0, new ItemStack(Items.diamond_sword));
            mc.thePlayer.inventory.currentItem = 0;
            attacks.set(0);
            rotatedByKillAura = 0;
        }, t -> {
            if (t == 4) spawnGolem(2.3);
            if (t == 10) {
                healthBefore = golemHealth();
                module("KillAura").setEnabled(true);
            }
        }, () -> {
            check("KillAura targets the golem", ModuleManager.get("KillAura").getTarget() == golem() && golem() != null,
                String.valueOf(ModuleManager.get("KillAura").getTarget()));
            check("KillAura attacks it (C02 attack packets)", attacks.get() >= 3, attacks.get() + " attacks");
            check("KillAura rotates silently toward it", rotatedByKillAura > 20, rotatedByKillAura + " ticks");
            check("the golem took damage", golemHealth() < healthBefore, healthBefore + " -> " + golemHealth());
            check("B4 scripts see Gale's KillAura target", ScriptProtected.getKillAuraTarget() == golem(), "");
        });

        phase("KillAura gives way while B4 runs its own", 40, () -> {
            b4Reports = true;
        }, t -> {
            if (t == 8) attacks.set(0);
        }, () -> {
            check("KillAura defers when B4 reports its KillAura on", module("KillAura").isDeferred() && !port("KillAura").isRunning(), "");
            check("no attacks of Gale's own meanwhile", attacks.get() == 0, attacks.get() + " attacks");
            check("b4.report: B4's target reaches B4State", B4State.killAuraTarget() == golem() && B4State.fresh(), "");
            check("the other ported modules see B4's target", KillAuraModule.target != null && KillAuraModule.target.entity == golem(), "");
            check("B4 scripts in Gale see B4's target", ScriptProtected.getKillAuraTarget() == golem(), "");
            b4Reports = false;
            External.report("b4", Collections.emptyList());
        });

        phase("KillAura takes over again", 40, () -> attacks.set(0), t -> { }, () -> {
            check("KillAura runs again once B4's is off", port("KillAura").isRunning(), "");
            check("and attacks again", attacks.get() >= 1, attacks.get() + " attacks");
            module("KillAura").setEnabled(false);
        });

        phase("KillAura's Lock view turns the camera", 40, () -> {
            killGolem();
            toGround(30f);
        }, t -> {
            if (t == 4) spawnGolem(2.3);
            if (t == 10) {
                ((ModeSetting) module("KillAura").getSetting("Rotation mode")).set("Lock view");
                Entity g = golem();
                if (g != null) mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yawTo(g) + 30f;
                module("KillAura").setEnabled(true);
            }
        }, () -> {
            Entity g = golem();
            // KillAura aims at the nearest point of the box, not its middle: the camera must follow what it sent
            float sent = gale.b4port.util.RotationManager.lastYaw;
            float offSent = Math.abs(MathHelper.wrapAngleTo180_float(sent - mc.thePlayer.rotationYaw));
            float off = g == null ? 999 : Math.abs(MathHelper.wrapAngleTo180_float(yawTo(g) - mc.thePlayer.rotationYaw));
            check("Lock view: the camera itself turns to KillAura's rotation", offSent < 1 && off < 25,
                String.format("%.2f degrees from the sent yaw, %.1f from the target's middle (was 30)", offSent, off));
            module("KillAura").setEnabled(false);
            module("KillAura").getSetting("Rotation mode").reset();
        });

        phase("AimAssist turns the camera", 40, () -> {
            // B4's AimAssist only aims while the game window has the mouse; the test window may not
            mc.inGameHasFocus = true;
            // the target is a golem: B4's AimAssist leaves mobs alone unless "Aim mobs" is on
            ((BoolSetting) module("AimAssist").getSetting("Aim mobs")).set(true);
            module("AimAssist").setEnabled(true);
            Entity g = golem();
            if (g != null) {
                float yaw = yawTo(g) + 45f;
                mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
                startDiff = Math.abs(MathHelper.wrapAngleTo180_float(yawTo(g) - yaw));
            }
        }, t -> mc.inGameHasFocus = true, () -> {
            Entity g = golem();
            float now = g == null ? 999 : Math.abs(MathHelper.wrapAngleTo180_float(yawTo(g) - mc.thePlayer.rotationYaw));
            check("AimAssist brings the camera toward the target", now < startDiff - 20, String.format("%.1f -> %.1f degrees", startDiff, now));
            check("AimAssist's target", ModuleManager.get("AimAssist").getTarget() == g, "");
            module("AimAssist").setEnabled(false);
            module("AimAssist").getSetting("Aim mobs").reset();
            killGolem();
        });

        phase("Scaffold bridges", 120, () -> {
            // four blocks above the ground: a fall would not hurt much
            platform = spawn.add(30, 3, -40);
            heal();
            give(0, new ItemStack(Blocks.wool, 64));
            give(1, new ItemStack(Blocks.wool, 64));
            mc.thePlayer.inventory.currentItem = 0;
            DevTest.server(p -> {
                p.worldObj.setBlockState(platform, Blocks.stone.getDefaultState(), 3);
                p.playerNetServerHandler.setPlayerLocation(platform.getX() + 0.5, platform.getY() + 1, platform.getZ() + 0.5, 0f, 10f);
            });
        }, t -> {
            if (t == 10) {
                startX = mc.thePlayer.posX;
                startY = minY = mc.thePlayer.posY;
                startZ = mc.thePlayer.posZ;
                module("Scaffold").setEnabled(true);
                KeyUtil.setPressed(mc.gameSettings.keyBindForward, true);
            }
            if (t > 10) minY = Math.min(minY, mc.thePlayer.posY);
        }, () -> {
            KeyUtil.restore(mc.gameSettings.keyBindForward);
            boolean placing = B4Values.scaffolding();
            module("Scaffold").setEnabled(false);
            double dist = Math.hypot(mc.thePlayer.posX - startX, mc.thePlayer.posZ - startZ);
            check("Scaffold: never falls", minY >= startY - 0.1, String.format("start y %.2f, lowest %.2f", startY, minY));
            check("Scaffold: goes forward on its blocks", dist > 6, String.format("%.1f blocks", dist));
            check("Scaffold: isScaffolding while on", placing, "");
        });

        phase("BedAura breaks a bed", 80, () -> {
            bedSeen = progressSeen = false;
            heal();
            DevTest.server(p -> {
                BlockPos ground = spawn.add(60, -1, 0);
                for (int dx = -3; dx <= 4; dx++) for (int dz = -3; dz <= 3; dz++) p.worldObj.setBlockState(ground.add(dx, 0, dz), Blocks.stone.getDefaultState(), 3);
                bedFoot = ground.add(3, 1, 0);
                p.worldObj.setBlockState(bedFoot, Blocks.bed.getDefaultState().withProperty(BlockBed.FACING, EnumFacing.EAST)
                    .withProperty(BlockBed.PART, BlockBed.EnumPartType.FOOT), 3);
                p.worldObj.setBlockState(bedFoot.east(), Blocks.bed.getDefaultState().withProperty(BlockBed.FACING, EnumFacing.EAST)
                    .withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD), 3);
                p.playerNetServerHandler.setPlayerLocation(ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, -90f, 30f);
            });
            give(0, null);
        }, t -> { if (t == 15) module("BedAura").setEnabled(true); }, () -> {
            boolean gone = bedFoot != null && mc.theWorld.getBlockState(bedFoot).getBlock() != Blocks.bed
                || bedFoot != null && mc.theWorld.getBlockState(bedFoot.east()).getBlock() != Blocks.bed;
            check("BedAura finds the bed", bedSeen, "");
            check("BedAura's progress shows (island, scripts)", progressSeen, "");
            check("BedAura breaks it", gone, bedFoot == null ? "no bed" : mc.theWorld.getBlockState(bedFoot).getBlock().getLocalizedName());
            module("BedAura").setEnabled(false);
        });

        phase("GaleLink's B4 events become Gale events", 10, () -> {
            Arrays.fill(events, null);
            ScriptApi.INSTANCE.put("b4.event", new Object[]{ "bedwars", "FIREBALL", mc.thePlayer });
            ScriptApi.INSTANCE.put("b4.event", new Object[]{ "anticheat", "Scaffold", mc.thePlayer });
            ScriptApi.INSTANCE.put("b4.event", new Object[]{ "locraw", "{\"server\":\"mini1A\",\"gametype\":\"BEDWARS\"}" });
            ScriptApi.INSTANCE.put("b4.event", new Object[]{ "irc", "hello" });
        }, t -> { }, () -> {
            check("b4.event bedwars -> BedWarsAlertEvent from B4", "FIREBALL/true".equals(events[0]), events[0]);
            check("b4.event anticheat -> AntiCheatFlagEvent from B4", "Scaffold/true".equals(events[1]), events[1]);
            check("b4.event locraw -> LocrawEvent", String.valueOf(events[2]).contains("BEDWARS"), events[2]);
            check("b4.event irc -> IrcMessageEvent", "hello".equals(events[3]), events[3]);
        });

        phase("the others run without errors", 40, () -> {
            locraws.set(0);
            for (String n : Arrays.asList("Backtrack", "Lag Range", "Bed Wars", "AntiCheat", "AntiBot")) module(n).setEnabled(true);
        }, t -> { }, () -> {
            for (String n : Arrays.asList("Backtrack", "Lag Range", "Bed Wars", "AntiCheat", "AntiBot")) {
                check(n + " runs", port(n).isRunning(), "");
                module(n).setEnabled(false);
            }
            check("no /locraw outside Hypixel", locraws.get() == 0, locraws.get() + " sent");
        });

        phase("finish", 5, () -> {
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) if (Gale.modules().all().contains(e.getKey())) e.getKey().setEnabled(e.getValue());
            DevTest.server(p -> p.mcServer.setDifficultyForAllWorlds(EnumDifficulty.PEACEFUL));
        }, t -> { }, () -> Gale.config().save());
    }

    private static void safeWalkPhase(boolean on) {
        phase("safe walk " + (on ? "on" : "off (control)"), 50, () -> {
            // a block two above the ground: walking off drops you three, which does not hurt
            platform = spawn.add(on ? 10 : 20, 2, -20);
            heal();
            DevTest.server(p -> {
                p.worldObj.setBlockState(platform, Blocks.stone.getDefaultState(), 3);
                p.playerNetServerHandler.setPlayerLocation(platform.getX() + 0.5, platform.getY() + 1, platform.getZ() + 0.5, 0f, 10f);
            });
        }, t -> {
            if (t == 10) {
                startY = minY = mc.thePlayer.posY;
                safeWalk = on;
                KeyUtil.setPressed(mc.gameSettings.keyBindForward, true);
            }
            if (t > 10) minY = Math.min(minY, mc.thePlayer.posY);
        }, () -> {
            KeyUtil.restore(mc.gameSettings.keyBindForward);
            safeWalk = false;
            if (on) check("safe walk keeps you on the block (and the server never sees a sneak)", minY >= startY - 0.1 && !mc.thePlayer.isSneaking(),
                String.format("start %.2f lowest %.2f", startY, minY));
            else check("without safe walk you walk off (control)", minY < startY - 1, String.format("start %.2f lowest %.2f", startY, minY));
            check("still alive", mc.thePlayer.getHealth() > 0 && mc.currentScreen == null, mc.thePlayer.getHealth());
        });
    }

    private static void heal() {
        DevTest.server(p -> {
            p.setHealth(p.getMaxHealth());
            p.getFoodStats().addStats(20, 1f);
            p.fallDistance = 0;
        });
    }

    /** Back on the ground where the test started, healed, looking along this yaw. */
    private static void toGround(float yaw) {
        heal();
        DevTest.server(p -> p.playerNetServerHandler.setPlayerLocation(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, yaw, 0f));
    }

    private static Entity golem() {
        return mc.theWorld == null || golemId == Integer.MIN_VALUE ? null : mc.theWorld.getEntityByID(golemId);
    }

    private static float golemHealth() {
        Entity g = golem();
        return g instanceof EntityLiving ? ((EntityLiving) g).getHealth() : -1f;
    }

    /** An iron golem (a mob for B4, not removed in Peaceful) that does nothing, this far in front of you. */
    private static void spawnGolem(double distance) {
        golemId = Integer.MIN_VALUE;
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double x = mc.thePlayer.posX - Math.sin(yaw) * distance, z = mc.thePlayer.posZ + Math.cos(yaw) * distance, y = mc.thePlayer.posY;
        DevTest.server(p -> {
            EntityIronGolem g = new EntityIronGolem(p.worldObj);
            g.setPosition(x, y, z);
            NBTTagCompound tag = new NBTTagCompound();
            g.writeEntityToNBT(tag);
            tag.setBoolean("NoAI", true);
            g.readEntityFromNBT(tag);
            p.worldObj.spawnEntityInWorld(g);
            golemId = g.getEntityId();
        });
    }

    private static void killGolem() {
        int id = golemId;
        DevTest.server(p -> {
            Entity g = p.worldObj.getEntityByID(id);
            if (g != null) g.setDead();
        });
        golemId = Integer.MIN_VALUE;
    }

    private static float yawTo(Entity e) {
        return (float) Math.toDegrees(Math.atan2(e.posZ - mc.thePlayer.posZ, e.posX - mc.thePlayer.posX)) - 90f;
    }

    private static void give(int slot, ItemStack stack) {
        mc.thePlayer.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy());
        DevTest.server(p -> p.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy()));
    }
}
