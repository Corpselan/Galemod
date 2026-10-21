package gale.devtest;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.bedwars.BedTracker;
import gale.bridge.Bridge;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.Render2DEvent;
import gale.module.Module;
import gale.module.impl.render.Island;
import gale.module.impl.world.AutoDef;
import gale.util.ReflectUtil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ScreenShotHelper;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The island and AutoDef in a made-up Bed Wars game (gradlew runClient -Pislandtest): the toggle, scaffold and
 * bed-progress cards, then a Bed Wars sidebar with teams, your bed found from the spawn, an enemy near it, the enemy
 * digging it (crack packets), a defense change, AutoDef walling the bed in, and the bed destroyed. Screenshots go to
 * run/screenshots/island-*.png.
 */
final class IslandTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ENEMY_ID = -777;
    private static final UUID ENEMY_UUID = UUID.fromString("0f3c2a9e-5b1d-4c8e-9a7f-2d6b8e4c1a35");
    private static final Map<Module, Boolean> SAVED = new java.util.LinkedHashMap<>();
    private static String shot;
    private static boolean glClean = true;
    private static BlockPos foot, head;
    private static float lastShown;
    private static boolean shownRose = true, digCardSeen;
    private static int maxStageSeen = -1;
    private static long drawNanos, draws;

    private IslandTest() { }

    private static Island island() { return Gale.modules().get(Island.class); }

    private static AutoDef autoDef() { return Gale.modules().get(AutoDef.class); }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Island " + name, ticks, enter, tick, exit));
    }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "island-test");
        // the island draws at NORMAL priority: time it from just before to just after, and look for GL errors
        Gale.BUS.listen(Render2DEvent.class, Priority.HIGH, e -> drawNanos -= System.nanoTime(), "island-test");
        Gale.BUS.listen(Render2DEvent.class, Priority.LOW, e -> {
            drawNanos += System.nanoTime();
            draws++;
            if (GL11.glGetError() != GL11.GL_NO_ERROR) glClean = false;
        }, "island-test");

        phase("capsule", 30, () -> {
            // put back at the end: the dev config is saved, and the other tests need their modules
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != Gale.modules().get("HUD")) m.setEnabled(false);
            Bridge.receive("b4.enabled", "");
            island().setEnabled(true);
            island().getSetting("Defense overlay").parse("on");
            mc.thePlayer.rotationPitch = 0;
        }, t -> { if (t == 25) shot = "island-00-capsule"; }, () -> {
            check("island starts as the capsule", island().cardShown() == 0, island().cardShown());
            check("island draws without OpenGL errors", glClean, "shader rounded rectangles, text, logo");
        });

        phase("Gale toggle card", 30, () -> Gale.modules().get("LocalSpin").setEnabled(true), t -> { if (t == 15) shot = "island-01-toggle"; }, () -> {
            check("a Gale module switched on shows the toggle card", island().cardShown() == 4 && island().toggleText().equals("LocalSpin has been Enabled!"),
                island().cardShown() + " " + island().toggleText());
            Gale.modules().get("LocalSpin").setEnabled(false);
        });

        phase("B4 toggle card", 30, () -> Bridge.receive("b4.enabled", "KillAura"), t -> { }, () ->
            check("a B4 module GaleLink reports shows the toggle card", island().toggleText().equals("KillAura has been Enabled!"), island().toggleText()));

        phase("scaffold card", 40, () -> {
            give(0, new ItemStack(Blocks.wool, 64, 14));
            give(1, new ItemStack(Blocks.wool, 40, 14));
        }, t -> Bridge.receive("b4.enabled", "KillAura,Scaffold"), () -> {
            check("B4 Scaffold on shows the scaffold card", island().cardShown() == 2, island().debugState());
            shot = "island-02-scaffold";
        });

        phase("bed progress card", 40, () -> {
            Bridge.receive("b4.enabled", "KillAura");
            Bridge.receive("b4.bedaura", "0.5");
        }, t -> Bridge.receive("b4.enabled", "KillAura,BedAura"), () -> {
            check("B4 BedAura progress shows the breaking card", island().cardShown() == 3 && island().debugState().contains("bedProgress=0.5"), island().debugState());
            shot = "island-03-bedaura";
            Bridge.receive("b4.bedaura", "0");
            Bridge.receive("b4.enabled", "");
            give(0, null);
            give(1, null);
        });

        // ---------------------------------------------------------------- a Bed Wars game
        phase("Bed Wars sidebar and spawn", 40, () -> {
            BlockPos me = new BlockPos(mc.thePlayer);
            foot = me.add(-3, 0, 0);
            head = foot.south();
            String self = mc.thePlayer.getName();
            DevTest.server(p -> {
                Scoreboard sb = p.worldObj.getScoreboard();
                ScoreObjective o = sb.addScoreObjective("bw", IScoreObjectiveCriteria.DUMMY);
                o.setDisplayName("BED WARS");
                sb.setObjectiveInDisplaySlot(1, o);
                sb.getValueFromObjective("R Red: ✔", o).setScorePoints(3);
                sb.getValueFromObjective("B Blue: ✔ YOU", o).setScorePoints(2);
                sb.getValueFromObjective("www.hypixel.net", o).setScorePoints(1);
                ScorePlayerTeam red = sb.createTeam("Red"), blue = sb.createTeam("Blue");
                red.setNamePrefix("§c");
                blue.setNamePrefix("§9");
                sb.addPlayerToTeam("Enemy1", "Red");
                sb.addPlayerToTeam(self, "Blue");
                IBlockState bed = Blocks.bed.getDefaultState().withProperty(BlockBed.FACING, EnumFacing.SOUTH);
                p.worldObj.setBlockState(foot, bed.withProperty(BlockBed.PART, BlockBed.EnumPartType.FOOT), 3);
                p.worldObj.setBlockState(head, bed.withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD), 3);
                // one block of defense, so the first sample has something to compare with
                p.worldObj.setBlockState(foot.north(), Blocks.wool.getStateFromMeta(11), 3);
                p.playerNetServerHandler.setPlayerLocation(foot.getX() + 3.5, foot.getY(), foot.getZ() + 1.0, 90f, 30f);
            });
        }, t -> {
            if (t == 20) DevTest.server(p -> p.addChatMessage(new ChatComponentText("Protect your bed and destroy the enemy beds.")));
        }, () -> {
            check("tracker sees a Bed Wars game", BedTracker.inGame() && BedTracker.onBoard(), "inGame " + BedTracker.inGame());
            check("tracker reads your team from the sidebar", BedTracker.ownColor() == '9', "color '" + BedTracker.ownColor() + "'");
        });

        phase("own bed found from the spawn", 60, () -> { }, t -> { }, () -> {
            BlockPos[] own = BedTracker.ownBed();
            Set<BlockPos> want = new HashSet<>(), got = new HashSet<>();
            want.add(foot);
            want.add(head);
            if (own != null) for (BlockPos b : own) got.add(b);
            check("tracker finds exactly your bed near the spawn", want.equals(got), own == null ? "none" : got.toString());
            check("island knows the bed", "看着床".equals(island().getInfo()), island().getInfo());
        });

        phase("bed alert", 40, () -> {
            GameProfile profile = new GameProfile(ENEMY_UUID, "Enemy1");
            Map<UUID, NetworkPlayerInfo> tab = ReflectUtil.get(ReflectUtil.field(NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i"), mc.getNetHandler());
            if (tab != null) tab.put(ENEMY_UUID, new NetworkPlayerInfo(profile));
            EntityOtherPlayerMP enemy = new EntityOtherPlayerMP(mc.theWorld, profile);
            enemy.setPositionAndRotation(foot.getX() + 0.5, foot.getY(), foot.getZ() - 1.9, 0f, 60f);
            enemy.setCurrentItemOrArmor(0, new ItemStack(net.minecraft.init.Items.diamond_pickaxe));
            mc.theWorld.addEntityToWorld(ENEMY_ID, enemy);
        }, t -> { if (t == 30) shot = "island-04-alert"; }, () -> {
            check("an enemy near your bed shows the bed alert", island().cardShown() == 1 && island().alertTitle().equals("Bed Alert!")
                && island().alertDetail().equals("Enemy1 / 3 blocks from YOUR bed"), island().alertTitle() + " | " + island().alertDetail());
            check("the enemy counts as an enemy", BedTracker.isEnemy((net.minecraft.entity.player.EntityPlayer) mc.theWorld.getEntityByID(ENEMY_ID), false), "");
        });

        phase("digging card", 80, () -> {
            lastShown = 0;
            shownRose = true;
            digCardSeen = false;
            maxStageSeen = -1;
        }, t -> {
            if (t % 7 == 1 && t / 7 <= 9) {
                int stage = t / 7;
                mc.getNetHandler().handleBlockBreakAnim(new S25PacketBlockBreakAnim(ENEMY_ID, foot, stage));
                maxStageSeen = stage;
            }
            float shown = island().digShown();
            if (shown + 0.001f < lastShown) shownRose = false;
            lastShown = shown;
            if (island().cardShown() == 5) digCardSeen = true;
            if (t == 45) shot = "island-05-digging";
        }, () -> {
            check("the enemy's crack on your bed shows the digging card", digCardSeen && island().isDigging(), island().debugState());
            check("the digging card names who and which block", island().digText().equals("Enemy1  BED"), island().digText());
            check("the bar only fills up", shownRose && lastShown > 0.8f, "shown " + lastShown + ", stage " + maxStageSeen);
            mc.getNetHandler().handleBlockBreakAnim(new S25PacketBlockBreakAnim(ENEMY_ID, foot, -1));
        });

        phase("defense change", 50, () -> {
            mc.theWorld.removeEntityFromWorld(ENEMY_ID);
            DevTest.server(p -> {
                p.worldObj.setBlockState(foot.west(), Blocks.end_stone.getDefaultState(), 3);
                p.worldObj.setBlockState(head.west(), Blocks.end_stone.getDefaultState(), 3);
            });
        }, t -> { if (t == 40) shot = "island-06-defense"; }, () -> {
            check("the digging card goes away when the digging stops", !island().isDigging() && island().cardShown() != 5, island().cardShown());
            check("a change next to your bed shows the defense event", island().debugState().contains("event=Your bed defense changed"), island().debugState());
            check("the overlay lists the defense", island().defenseText().contains("L1") && island().defenseText().contains("Straight:"), island().defenseText());
        });

        // From one place AutoDef can only click faces it can see (as the B4 script): from beside the bed the far side and
        // the top row are out of sight. Each pass must place everything it can see, nothing else, then switch off.
        phase("AutoDef from the east", 170, () -> {
            DevTest.server(p -> {
                p.worldObj.setBlockToAir(foot.west());
                p.worldObj.setBlockToAir(head.west());
                p.worldObj.setBlockToAir(foot.north());
                p.playerNetServerHandler.setPlayerLocation(foot.getX() + 3.5, foot.getY(), foot.getZ() + 1.0, 90f, 30f);
            });
            give(0, new ItemStack(Blocks.end_stone, 64));
            give(1, new ItemStack(Blocks.stained_glass, 64, 11));
            autoDef().getSetting("Debug").parse("on");
            autoDef().setEnabled(true);
        }, t -> { if (t == 60) shot = "island-07-autodef"; }, () -> {
            String open = openSpots();
            check("AutoDef from the east fills all but the far side and the top", open.equals("(-2,0,0) (-2,0,1) (-1,1,0) (0,2,0) (0,2,1)"), open);
            check("AutoDef from the east places nothing outside the pattern", strays().isEmpty(), strays());
            check("AutoDef switches itself off when nothing is left it can place", !autoDef().isEnabled(), "enabled " + autoDef().isEnabled());
            check("AutoDef lets go of sneak", !mc.gameSettings.keyBindSneak.isKeyDown(), "");
            autoDef().setEnabled(false);
        });
        phase("AutoDef from the west", 170, () -> {
            DevTest.server(p -> p.playerNetServerHandler.setPlayerLocation(foot.getX() - 2.5, foot.getY(), foot.getZ() + 1.0, -90f, 30f));
            autoDef().setEnabled(true);
        }, t -> { }, () -> {
            String open = openSpots();
            check("AutoDef from the west fills the far side", open.equals("(0,2,0) (0,2,1)"), open);
            check("AutoDef from the west places nothing outside the pattern", strays().isEmpty(), strays());
            autoDef().setEnabled(false);
        });
        phase("AutoDef from a block up", 120, () -> {
            DevTest.server(p -> {
                p.worldObj.setBlockState(foot.add(3, 0, 0), Blocks.stone.getDefaultState(), 3);
                p.playerNetServerHandler.setPlayerLocation(foot.getX() + 3.5, foot.getY() + 1, foot.getZ() + 0.5, 90f, 40f);
            });
            autoDef().setEnabled(true);
        }, t -> { }, () -> {
            String open = openSpots();
            check("AutoDef finishes the pattern from higher up", open.isEmpty(), open.isEmpty() ? "26/26" : open);
            check("AutoDef switches itself off when the pattern is done", !autoDef().isEnabled(), "enabled " + autoDef().isEnabled());
            check("AutoDef places nothing outside the pattern", strays().isEmpty(), strays());
            autoDef().setEnabled(false);
            shot = "island-08-walled";
        });

        phase("bed destroyed", 40, () -> {
            DevTest.server(p -> {
                p.worldObj.setBlockToAir(foot);
                p.worldObj.setBlockToAir(head);
                p.addChatMessage(new ChatComponentText("BED DESTRUCTION > Your Bed was destroyed by Enemy1!"));
            });
        }, t -> { if (t == 30) shot = "island-09-destroyed"; }, () -> {
            check("tracker marks your bed destroyed by whom", BedTracker.bedDestroyed() && BedTracker.destroyedDetail().equals("By Enemy1"), BedTracker.destroyedDetail());
            check("the island shows who destroyed it", island().cardShown() == 1 && island().alertTitle().equals("Your bed destroyed")
                && island().alertDetail().equals("By Enemy1"), island().alertTitle() + " | " + island().alertDetail());
            check("island draws without OpenGL errors all along", glClean, "");
            check("HUD + island draw time", draws > 0, String.format("%.3f ms a frame over %d frames", drawNanos / 1e6 / Math.max(1, draws), draws));
            island().getSetting("Defense overlay").reset();
            autoDef().getSetting("Debug").reset();
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) e.getKey().setEnabled(e.getValue());
            Gale.config().save();
        });
    }

    // the pattern's spots still open, relative to the foot of the bed
    private static String openSpots() {
        StringBuilder sb = new StringBuilder();
        for (BlockPos s : autoDef().patternAround(foot)) {
            if (!mc.theWorld.isAirBlock(s)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append('(').append(s.getX() - foot.getX()).append(',').append(s.getY() - foot.getY()).append(',').append(s.getZ() - foot.getZ()).append(')');
        }
        return sb.toString();
    }

    // blocks around the bed that are not the bed, the ground, the step to stand on or a spot of the pattern
    private static String strays() {
        Set<BlockPos> pattern = new HashSet<>(autoDef().patternAround(foot));
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : BlockPos.getAllInBox(foot.add(-4, 0, -4), foot.add(4, 4, 5))) {
            if (mc.theWorld.isAirBlock(p) || p.equals(foot) || p.equals(head) || p.equals(foot.add(3, 0, 0)) || pattern.contains(p)) continue;
            sb.append(' ').append(p.getX() - foot.getX()).append(',').append(p.getY() - foot.getY()).append(',').append(p.getZ() - foot.getZ());
        }
        return sb.toString().trim();
    }

    // a hotbar slot, on both sides
    private static void give(int slot, ItemStack stack) {
        mc.thePlayer.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy());
        Consumer<EntityPlayerMP> serverSide = p -> p.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy());
        DevTest.server(serverSide);
    }
}
