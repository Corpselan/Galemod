package gale.devtest;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.b4port.module.render.NametagsModule;
import gale.b4port.module.render.PlayerESPModule;
import gale.b4port.util.EspShaders;
import gale.b4port.util.Strings;
import gale.bridge.External;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * B4's Nametags, PlayerESP and Chams in Gale (gradlew runClient -Pb4rendertest): two made-up players in front of you,
 * each module on in turn, the frame read back to see what it drew (B4's opaque nametag background, PlayerESP's cyan in
 * every type, the team colour, players showing through a wall), vanilla name tags hidden, and each module giving way
 * while "B4" runs its own. Screenshots in run/screenshots/b4render-*.png.
 */
final class B4RenderTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int A_ID = -4101, B_ID = -4102;
    private static final UUID A = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000000a"), B = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000000b");
    private static final List<String> MODULES = Arrays.asList("Nametags", "PlayerESP", "Chams");
    private static final Map<Module, Boolean> SAVED = new LinkedHashMap<>();
    private static final Map<String, int[]> counts = new HashMap<>();
    private static final Map<String, int[]> frames = new HashMap<>();

    private static String measure, shot;
    private static List<String> b4Report;
    private static BlockPos spawn;
    private static ByteBuffer pixels;
    // the vanilla name tag of player A: asked for, and whether something cancelled it
    private static int specials, specialsCancelled;
    // ... and in PlayerESP's outline pass (drawn off screen, where B4 hides every name tag)
    private static int passSpecials, passCancelled;
    // Chams: the polygon offset while player A is drawn, and after
    private static int chamsOn, chamsOff, drawnA;

    private B4RenderTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Render " + name, ticks, enter, tick, exit));
    }

    private static Module module(String name) { return Gale.modules().get(name); }

    private static gale.b4port.module.Module port(String name) { return (gale.b4port.module.Module) module(name); }

    private static void set(String module, String setting, Object value) {
        Setting<?> s = module(module).getSetting(setting);
        if (s instanceof NumberSetting) ((NumberSetting) s).set(((Number) value).doubleValue());
        else if (s instanceof ModeSetting) ((ModeSetting) s).set((String) value);
        else if (s instanceof BoolSetting) ((BoolSetting) s).set((Boolean) value);
        else throw new IllegalArgumentException(module + " has no setting " + setting);
    }

    // what the middle of a frame holds (the players; not the HUD, chat, hotbar or toggle notices): [0] cyan (PlayerESP's
    // default colour), [1] black (Nametags' background at 100%), [2] red (the §c team's)
    private static int[] count(int w, int h) {
        int[] c = new int[3];
        int[] frame = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int r = pixels.get(i * 4) & 0xFF, g = pixels.get(i * 4 + 1) & 0xFF, b = pixels.get(i * 4 + 2) & 0xFF;
            frame[i] = r << 16 | g << 8 | b;
            int col = i % w, row = i / w;
            if (col < w / 4 || col >= w * 3 / 4 || row < h / 4 || row >= h * 3 / 4) continue;
            if (r < 90 && g > 140 && b > 140 && Math.abs(g - b) < 70) c[0]++;
            if (r < 6 && g < 6 && b < 6) c[1]++;
            if (r > 200 && g < 110 && b < 110 && r - g > 120) c[2]++;
        }
        frames.put(measure, frame);
        return c;
    }

    // (the enchanted diamond gear's glint moves the cyan count by up to ~150 between frames; PlayerESP adds 1500+)
    private static int cyan(String k) { return counts.containsKey(k) ? counts.get(k)[0] : -1; }

    private static int black(String k) { return counts.containsKey(k) ? counts.get(k)[1] : -1; }

    private static int red(String k) { return counts.containsKey(k) ? counts.get(k)[2] : -1; }

    /** Pixels that changed between two measured frames, in the middle half of the screen (where the wall is). */
    private static int changed(String a, String b) {
        int[] x = frames.get(a), y = frames.get(b);
        if (x == null || y == null) return -1;
        int w = mc.displayWidth, h = mc.displayHeight, n = 0;
        for (int row = h / 4; row < h * 3 / 4; row++) {
            for (int col = w / 4; col < w * 3 / 4; col++) {
                int p = x[row * w + col], q = y[row * w + col];
                int d = Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 40) n++;
            }
        }
        return n;
    }

    public static final class Forge {
        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onSpecials(RenderLivingEvent.Specials.Pre<?> e) {
            if (e.entity.getEntityId() != A_ID) return;
            if (PlayerESPModule.shaderPass) {
                passSpecials++;
                if (e.isCanceled()) passCancelled++;
                return;
            }
            specials++;
            if (e.isCanceled()) specialsCancelled++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPre(RenderPlayerEvent.Pre e) {
            if (e.entity.getEntityId() != A_ID || PlayerESPModule.shaderPass) return;
            drawnA++;
            if (GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)) chamsOn++;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPost(RenderPlayerEvent.Post e) {
            if (e.entity.getEntityId() != A_ID || PlayerESPModule.shaderPass) return;
            if (!GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL)) chamsOff++;
        }
    }

    private static void resetCounters() {
        specials = specialsCancelled = passSpecials = passCancelled = chamsOn = chamsOff = drawnA = 0;
    }

    static void phases() {
        MinecraftForge.EVENT_BUS.register(new Forge());
        // at the start of a frame the last one is still in the framebuffer: count it, then the screenshot
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (measure != null) {
                int w = mc.displayWidth, h = mc.displayHeight;
                if (pixels == null || pixels.capacity() < w * h * 4) pixels = BufferUtils.createByteBuffer(w * h * 4);
                pixels.clear();
                Framebuffer fb = mc.getFramebuffer();
                fb.bindFramebuffer(false);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                counts.put(measure, count(w, h));
                measure = null;
            }
            if (shot != null) {
                ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                DevTest.line("screenshot screenshots/" + shot + ".png");
                shot = null;
            }
        }, "b4render-test");
        // "B4" reporting its modules, as GaleLink does
        Gale.BUS.listen(UpdateEvent.class, Priority.HIGHEST + 200, e -> {
            if (b4Report != null) External.report("b4", b4Report);
        }, "b4render-test");

        phase("two players in front of you", 40, () -> {
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != module("HUD")) m.setEnabled(false);
            for (String n : MODULES) for (Setting<?> s : module(n).getSettings()) s.reset();
            spawn = new BlockPos(mc.thePlayer);
            DevTest.server(p -> {
                p.worldObj.setWorldTime(6000);
                p.worldObj.getWorldInfo().setRaining(false);
                p.worldObj.getWorldInfo().setThundering(false);
                p.playerNetServerHandler.setPlayerLocation(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
            });
            mc.gameSettings.thirdPersonView = 0;
            addPlayer(A_ID, A, "Enemy1", 0.0, 4.0, true);
            addPlayer(B_ID, B, "Enemy2", 2.2, 7.0, false);
        }, t -> {
            mc.thePlayer.rotationYaw = 0f;
            mc.thePlayer.rotationPitch = 0f;
            if (t == 30) resetCounters();
            if (t == 38) measure = "base";
            if (t == 39) shot = "b4render-0-vanilla";
        }, () -> {
            check("both players are in the world", mc.theWorld.getEntityByID(A_ID) != null && mc.theWorld.getEntityByID(B_ID) != null, "");
            check("without Nametags the vanilla name tag shows", specials > 0 && specialsCancelled == 0, specialsCancelled + "/" + specials + " cancelled");
            check("the three modules are there with B4's settings", module("Nametags") != null && module("Nametags").getSettings().size() >= 17
                && module("PlayerESP") != null && module("PlayerESP").getSettings().size() >= 11 && module("Chams") != null
                && module("Chams").getSettings().size() >= 3, "");
        });

        phase("Nametags", 40, () -> {
            set("Nametags", "Show items", true);
            module("Nametags").setEnabled(true);
        }, t -> {
            if (t == 6) resetCounters();
            if (t == 14) shot = "b4render-1-nametags";
            if (t == 16) set("Nametags", "Background", 100.0);
            if (t == 30) measure = "tags";
        }, () -> {
            EntityPlayer a = (EntityPlayer) mc.theWorld.getEntityByID(A_ID);
            String tag = a == null ? "" : Strings.nametag(a, true);
            check("Nametags runs", port("Nametags").isRunning(), gale.b4port.client.Hypixel.state);
            check("B4's nametag text: the name and the health", tag.contains("Enemy1") && tag.contains(Strings.health(a)), tag);
            check("the vanilla name tag is hidden", specials > 0 && specialsCancelled == specials, specialsCancelled + "/" + specials + " cancelled");
            check("B4's tag is drawn (an opaque background at 100%)", black("tags") - black("base") > 80, black("base") + " -> " + black("tags"));
        });

        phase("Nametags give way to B4's", 30, () -> b4Report = Collections.singletonList("Nametags"), t -> {
            if (t == 10) resetCounters();
            if (t == 25) measure = "tagsB4";
        }, () -> {
            check("Nametags gives way while B4 runs its own", !port("Nametags").isRunning(), "");
            check("then the name tag is not touched", specials > 0 && specialsCancelled == 0, specialsCancelled + "/" + specials + " cancelled");
            check("and B4's tag is not drawn by Gale", black("tagsB4") - black("base") < 40, black("base") + " -> " + black("tagsB4"));
            b4Report = Collections.emptyList();
            module("Nametags").setEnabled(false);
            set("Nametags", "Background", 30.0);
        });

        phase("PlayerESP shaders", 5, () -> { }, t -> { }, () ->
            check("PlayerESP's shaders compile", EspShaders.valid(EspShaders.fill()) && EspShaders.valid(EspShaders.outline()), ""));

        String[] types = { "Outline", "Outline (Light Glow)", "Outline (Heavy Glow)", "Box", "Shaded", "2D", "Ring" };
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            String file = "b4render-2" + (char) ('a' + i) + "-esp-" + type.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-$", "");
            phase("PlayerESP " + type, 22, () -> {
                set("PlayerESP", "Health bar", false);
                set("PlayerESP", "Type", type);
                module("PlayerESP").setEnabled(false);
            }, t -> {
                if (t == 2) measure = "pre " + type;
                if (t == 4) {
                    resetCounters();
                    module("PlayerESP").setEnabled(true);
                }
                if (t == 16) measure = "esp " + type;
                if (t == 17) shot = file;
            }, () -> {
                int min = type.equals("Ring") ? 250 : 1000;
                check("PlayerESP " + type + " draws in its colour", cyan("esp " + type) - cyan("pre " + type) > min,
                    cyan("pre " + type) + " -> " + cyan("esp " + type));
                if (type.startsWith("Outline")) {
                    check("its outline pass hides name tags, the frame keeps them", passSpecials > 0 && passCancelled == passSpecials
                        && specials > 0 && specialsCancelled == 0, passCancelled + "/" + passSpecials + " in the pass, " + specialsCancelled + "/" + specials + " in the frame");
                }
            });
        }

        phase("PlayerESP None and the health bar", 22, () -> {
            module("PlayerESP").setEnabled(false);
            set("PlayerESP", "Type", "None");
            set("PlayerESP", "Health bar", true);
        }, t -> {
            if (t == 2) measure = "pre health";
            if (t == 4) module("PlayerESP").setEnabled(true);
            if (t == 16) measure = "esp health";
            if (t == 17) shot = "b4render-3-esp-health-bar";
        }, () -> check("type None draws no colour (only the health bar)", Math.abs(cyan("esp health") - cyan("pre health")) < 200,
            cyan("pre health") + " -> " + cyan("esp health")));

        phase("PlayerESP team colour", 22, () -> {
            module("PlayerESP").setEnabled(false);
            team("Enemy1", "§c");
            set("PlayerESP", "Type", "Outline (Light Glow)");
            set("PlayerESP", "Health bar", false);
            set("PlayerESP", "Team color", true);
        }, t -> {
            if (t == 2) measure = "pre team";
            if (t == 4) module("PlayerESP").setEnabled(true);
            if (t == 16) measure = "esp team";
            if (t == 17) shot = "b4render-4-esp-team-color";
        }, () -> check("Team color: Enemy1 (red team) outlined red", red("esp team") - red("pre team") > 100, red("pre team") + " -> " + red("esp team")));

        phase("PlayerESP gives way to B4's", 22, () -> {
            module("PlayerESP").setEnabled(false);
            set("PlayerESP", "Team color", false);
            set("PlayerESP", "Type", "Outline");
        }, t -> {
            if (t == 2) measure = "pre espB4";
            if (t == 4) {
                b4Report = Collections.singletonList("PlayerESP");
                module("PlayerESP").setEnabled(true);
            }
            if (t == 16) measure = "espB4";
        }, () -> {
            check("PlayerESP gives way while B4 runs its own", !port("PlayerESP").isRunning(), "");
            check("and draws nothing then", Math.abs(cyan("espB4") - cyan("pre espB4")) < 200, cyan("pre espB4") + " -> " + cyan("espB4"));
            b4Report = Collections.emptyList();
            module("PlayerESP").setEnabled(false);
        });

        phase("Chams through a wall", 50, () -> {
            DevTest.server(p -> {
                for (int x = -4; x <= 4; x++) for (int y = 0; y < 4; y++) p.worldObj.setBlockState(spawn.add(x, y, 2), Blocks.stone.getDefaultState(), 3);
            });
        }, t -> {
            if (t == 20) measure = "wall";
            if (t == 22) measure = "wall2";
            if (t == 24) {
                resetCounters();
                module("Chams").setEnabled(true);
            }
            if (t == 40) measure = "chams";
            if (t == 42) shot = "b4render-5-chams";
        }, () -> {
            check("behind the wall the players are hidden (control: the wall frame does not change)", changed("wall", "wall2") < 200, changed("wall", "wall2"));
            check("Chams runs", port("Chams").isRunning(), "");
            check("Chams: polygon offset on while a player draws, off after", drawnA > 0 && chamsOn == drawnA && chamsOff == drawnA,
                chamsOn + " on, " + chamsOff + " off, of " + drawnA);
            check("Chams: the players show through the wall", changed("wall", "chams") > 1500, changed("wall", "chams") + " pixels changed");
        });

        phase("Chams gives way to B4's", 30, () -> b4Report = Collections.singletonList("Chams"), t -> {
            if (t == 6) resetCounters();
            if (t == 20) measure = "chamsB4";
        }, () -> {
            check("Chams gives way while B4 runs its own", !port("Chams").isRunning() && drawnA > 0 && chamsOn == 0, chamsOn + " of " + drawnA);
            check("and the wall hides the players again", changed("wall", "chamsB4") < 200, changed("wall", "chamsB4"));
            b4Report = Collections.emptyList();
            module("Chams").setEnabled(false);
        });

        phase("both: Nametags and PlayerESP in third person", 30, () -> {
            DevTest.server(p -> {
                for (int x = -4; x <= 4; x++) for (int y = 0; y < 4; y++) p.worldObj.setBlockToAir(spawn.add(x, y, 2));
            });
            set("Nametags", "Render self", true);
            set("PlayerESP", "Render self", true);
            set("PlayerESP", "Type", "Outline (Heavy Glow)");
            set("PlayerESP", "Health bar", true);
            set("Nametags", "Show items", true);
            module("Nametags").setEnabled(true);
            module("PlayerESP").setEnabled(true);
            module("Chams").setEnabled(true);
            mc.gameSettings.thirdPersonView = 1;
        }, t -> { if (t == 25) shot = "b4render-6-all-third-person"; }, () -> {
            check("all three run together", port("Nametags").isRunning() && port("PlayerESP").isRunning() && port("Chams").isRunning(),
                port("Nametags").isRunning() + " " + port("PlayerESP").isRunning() + " " + port("Chams").isRunning());
            mc.gameSettings.thirdPersonView = 0;
        });

        phase("finish", 5, () -> {
            b4Report = Collections.emptyList();
            for (String n : MODULES) {
                module(n).setEnabled(false);
                for (Setting<?> s : module(n).getSettings()) s.reset();
            }
            mc.theWorld.removeEntityFromWorld(A_ID);
            mc.theWorld.removeEntityFromWorld(B_ID);
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) if (Gale.modules().all().contains(e.getKey())) e.getKey().setEnabled(e.getValue());
        }, t -> { }, () -> {
            b4Report = null;
            Gale.config().save();
        });
    }

    private static void addPlayer(int id, UUID uuid, String name, double dx, double dz, boolean armored) {
        GameProfile profile = new GameProfile(uuid, name);
        Map<UUID, NetworkPlayerInfo> tab = ReflectUtil.get(ReflectUtil.field(NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i"), mc.getNetHandler());
        if (tab != null) tab.put(uuid, new NetworkPlayerInfo(profile));
        EntityOtherPlayerMP p = new EntityOtherPlayerMP(mc.theWorld, profile);
        p.setPositionAndRotation(spawn.getX() + 0.5 + dx, spawn.getY(), spawn.getZ() + 0.5 + dz, 180f, 0f);
        p.rotationYawHead = 180f;
        p.prevRotationYawHead = 180f;
        ItemStack sword = new ItemStack(Items.diamond_sword);
        sword.addEnchantment(Enchantment.sharpness, 3);
        p.setCurrentItemOrArmor(0, sword);
        if (armored) {
            ItemStack chest = new ItemStack(Items.diamond_chestplate);
            chest.addEnchantment(Enchantment.protection, 2);
            p.setCurrentItemOrArmor(1, new ItemStack(Items.iron_boots));
            p.setCurrentItemOrArmor(2, new ItemStack(Items.iron_leggings));
            p.setCurrentItemOrArmor(3, chest);
            p.setCurrentItemOrArmor(4, new ItemStack(Items.golden_helmet));
        }
        mc.theWorld.addEntityToWorld(id, p);
    }

    private static void team(String player, String prefix) {
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScorePlayerTeam t = sb.getTeam("b4render");
        if (t == null) t = sb.createTeam("b4render");
        t.setNamePrefix(prefix);
        sb.addPlayerToTeam(player, "b4render");
    }
}
