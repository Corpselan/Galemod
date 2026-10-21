package gale.devtest;

import gale.Gale;
import gale.b4script.Script;
import gale.b4script.ScriptDefaults;
import gale.b4script.ScriptManager;
import gale.b4script.ScriptManagerModule;
import gale.b4script.ScriptModule;
import gale.core.RotationManager;
import gale.core.ServerState;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.event.impl.PacketEvent;
import gale.module.Module;
import gale.module.impl.render.Trajectories;
import gale.module.setting.Setting;
import gale.util.KeyUtil;
import gale.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Trajectories and Gale's B4 script runtime (gradlew runClient -Pb4scripttest): a held and a thrown pearl and a
 * fireball in flight; then test scripts that use every event, their settings, onPreMotion changing the movement packet,
 * getRotations, cancelling packets and chat, a script that does not compile, an unsafe one, reloading; and last every
 * script of the user's B4 folder (copied to run/galemod/scripts-all) loaded under Gale, BetterIsland switched on.
 * Screenshots in run/screenshots/b4script-*.png.
 */
final class B4ScriptTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String shot;
    private static volatile int c0aCancelled, c0aSeen;
    private static float camPitch, camYaw;
    private static int tracked;
    private static BlockPos fireballHit;
    private static final Map<Module, Boolean> SAVED = new LinkedHashMap<>();

    private B4ScriptTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("B4 " + name, ticks, enter, tick, exit));
    }

    private static Object b(String key) { return ScriptDefaults.bridge.get(key); }

    private static int num(String key) {
        Object o = b(key);
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    private static ScriptModule script(String name) {
        Module m = Gale.modules().get(name);
        return m instanceof ScriptModule ? (ScriptModule) m : null;
    }

    private static ScriptManagerModule manager() { return Gale.modules().get(ScriptManagerModule.class); }

    private static Trajectories trajectories() { return Gale.modules().get(Trajectories.class); }

    private static File dir(String name) { return new File(Gale.config().dir(), name); }

    private static void write(String folder, String name, String source) {
        try {
            File d = dir(folder);
            d.mkdirs();
            Files.write(new File(d, name + ".java").toPath(), source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ the test scripts

    private static final String T1 = String.join("\n",
        "int ticks, motions, posts, inputs, sent, received, renders, worlds;",
        "void onLoad() {",
        "    modules.registerDescription(\"Gale B4 runtime test\");",
        "    modules.registerButton(\"Flag\", true);",
        "    modules.registerSlider(\"Amount\", \"ms\", 50, 0, 100, 5);",
        "    modules.registerSlider(\"Mode\", \"\", 1, new String[]{ \"A\", \"B\", \"C\" });",
        "    bridge.add(\"t.loaded\", \"yes\");",
        "}",
        "void onEnable() { bridge.add(\"t.enabled\", 1); config.set(\"t.key\", \"v1\"); }",
        "void onDisable() { bridge.add(\"t.disabled\", 1); }",
        "void onPreUpdate() {",
        "    ticks++;",
        "    bridge.add(\"t.ticks\", ticks);",
        "    bridge.add(\"t.flag\", modules.getButton(getScriptName(), \"Flag\"));",
        "    bridge.add(\"t.amount\", modules.getSlider(getScriptName(), \"Amount\"));",
        "    bridge.add(\"t.mode\", modules.getSlider(getScriptName(), \"Mode\"));",
        "    bridge.add(\"t.name\", getScriptName());",
        "    bridge.add(\"t.config\", config.get(\"t.key\"));",
        "    Entity p = client.getPlayer();",
        "    bridge.add(\"t.player\", p.getName());",
        "    bridge.add(\"t.below\", world.getBlockAt(p.getBlockPosition().offset(0, -1, 0)).name);",
        "}",
        "void onPreMotion(PlayerState s) {",
        "    motions++;",
        "    bridge.add(\"t.motions\", motions);",
        "    if (modules.getButton(getScriptName(), \"Flag\")) s.pitch = 33.5f;",
        "}",
        "void onPostMotion() { posts++; bridge.add(\"t.posts\", posts); }",
        "void onPostPlayerInput() { inputs++; bridge.add(\"t.inputs\", inputs); }",
        "boolean onPacketSent(CPacket packet) {",
        "    sent++;",
        "    bridge.add(\"t.sent\", sent);",
        "    if (packet instanceof C0A) { bridge.add(\"t.c0a\", 1); return false; }",
        "    return true;",
        "}",
        "boolean onPacketReceived(SPacket packet) { received++; bridge.add(\"t.received\", received); return true; }",
        "boolean onChat(String message) {",
        "    if (message.contains(\"GALE-HIDE-ME\")) { bridge.add(\"t.chat\", util.strip(message)); return false; }",
        "    return true;",
        "}",
        "void onRenderTick(float partialTicks) {",
        "    renders++;",
        "    bridge.add(\"t.renders\", renders);",
        "    render.roundedRect(6, 60, 150, 84, 6, 0xC0202020);",
        "    render.text(\"B4 script in Gale: \" + ticks, 12, 68, 1f, 0xFF55FF55, true);",
        "}",
        "void onRenderWorld(float partialTicks) { worlds++; bridge.add(\"t.world\", worlds); }",
        "boolean onMouse(int button, boolean pressed) { bridge.add(\"t.mouse\", button); return button != 4; }");

    private static final String T2 = String.join("\n",
        "void onLoad() { modules.registerSlider(\"Yaw\", \"\", 45, -180, 180, 1); }",
        "float[] getRotations(float yaw, float pitch) {",
        "    return new float[]{ (float) modules.getSlider(getScriptName(), \"Yaw\"), 20f };",
        "}");

    private static final String BAD = "void onLoad() { int x = \"not a number\"; }";

    private static final String UNSAFE = "void onEnable() { System.exit(0); }";

    // ------------------------------------------------------------------ phases

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "b4script-test");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> {
            if (!(e.getPacket() instanceof C0APacketAnimation)) return;
            if (e.isCancelled()) c0aCancelled++;
            else c0aSeen++;
        }, "b4script-test");

        // ---------------------------------------------------------------- Trajectories
        phase("Trajectories: held pearl", 30, () -> {
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != Gale.modules().get("HUD")) m.setEnabled(false);
            trajectories().setEnabled(true);
            give(0, new ItemStack(Items.ender_pearl, 16));
            mc.thePlayer.inventory.currentItem = 0;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0f;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -20f;
        }, t -> { if (t == 20) shot = "b4script-00-held-pearl"; }, () -> check("Trajectories draws for a held pearl", trajectories().isEnabled(), ""));

        phase("Trajectories: pearl and fireball in flight", 30, () -> {
            tracked = 0;
            fireballHit = null;
            DevTest.server(p -> {
                EntityEnderPearl pearl = new EntityEnderPearl(p.worldObj, p);
                p.worldObj.spawnEntityInWorld(pearl);
                // high up, flying a little downward: its line ends on the ground some 40 blocks away
                EntityLargeFireball fb = new EntityLargeFireball(p.worldObj, p.posX + 2, p.posY + 8, p.posZ + 3, 0, -0.15, 1);
                p.worldObj.spawnEntityInWorld(fb);
            });
        }, t -> {
            tracked = Math.max(tracked, trajectories().tracked());
            if (t == 6) {
                shot = "b4script-01-in-flight";
                for (net.minecraft.entity.Entity e : mc.theWorld.loadedEntityList) if (e instanceof EntityLargeFireball) fireballHit = trajectories().fireballTarget(e);
            }
        }, () -> {
            check("Trajectories follows the thrown pearl and the fireball", tracked >= 2, tracked + " in flight");
            check("the fireball's line ends on a block", fireballHit != null, fireballHit);
            trajectories().setEnabled(false);
            give(0, null);
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0f;
        });

        // ---------------------------------------------------------------- scripts
        phase("load test scripts", 200, () -> {
            File d = dir("scripts-test");
            File[] old = d.listFiles();
            if (old != null) for (File f : old) f.delete();
            write("scripts-test", "GaleT1", T1);
            write("scripts-test", "GaleT2", T2);
            write("scripts-test", "GaleBad", BAD);
            write("scripts-test", "GaleUnsafe", UNSAFE);
            write("scripts-test", "This_name_is_too_long", T2);
            ScriptDefaults.bridge.clear();
            manager().folder.set("galemod/scripts-test");
            if (!ScriptManager.loading && ScriptManager.modules().length == 0) ScriptManager.reload();
        }, t -> { }, () -> {
            check("compiler found (game runs on a JDK)", ScriptManager.compiler != null, "");
            check("scripts loaded", !ScriptManager.loading && script("GaleT1") != null && script("GaleT2") != null, ScriptManager.modules().length + " modules");
            check("onLoad ran", "yes".equals(b("t.loaded")), b("t.loaded"));
            ScriptModule t1 = script("GaleT1");
            // a run before saved its settings in the config (they come back on load): start from the defaults
            for (String n : new String[]{ "GaleT1", "GaleT2" }) {
                ScriptModule m = script(n);
                if (m == null) continue;
                m.setEnabled(false);
                for (Setting<?> s : m.getSettings()) s.reset();
            }
            check("description from registerDescription", t1 != null && t1.getDescription().equals("Gale B4 runtime test"), t1 == null ? null : t1.getDescription());
            Setting<?> amount = t1 == null ? null : t1.getSetting("Amount"), mode = t1 == null ? null : t1.getSetting("Mode"), flag = t1 == null ? null : t1.getSetting("Flag");
            check("button, slider and mode slider became Gale settings", flag != null && amount != null && mode != null && amount.display().equals("50ms") && mode.display().equals("B"),
                flag + " " + amount + " " + mode);
            ScriptModule bad = script("GaleBad");
            check("a script that does not compile is listed as failed", bad != null && bad.script.failed && "加载出错".equals(bad.getInfo()), bad == null ? "missing" : bad.getInfo());
            check("an unsafe script is blocked", script("GaleUnsafe") == null, "");
            check("a name over 14 letters is blocked", Gale.modules().get("This_name_is_too_long") == null, "");
        });

        phase("events", 50, () -> {
            camPitch = mc.thePlayer.rotationPitch;
            script("GaleT1").setEnabled(true);
        }, t -> { if (t == 40) shot = "b4script-02-test-script"; }, () -> {
            check("onEnable ran, config.set / get work", num("t.enabled") == 1 && "v1".equals(b("t.config")), b("t.config"));
            check("onPreUpdate every tick", num("t.ticks") >= 45, num("t.ticks"));
            check("getScriptName is the file's name", "GaleT1".equals(b("t.name")), b("t.name"));
            check("modules.getButton / getSlider read the script's own settings",
                Boolean.TRUE.equals(b("t.flag")) && Double.valueOf(50).equals(b("t.amount")) && Double.valueOf(1).equals(b("t.mode")), b("t.flag") + " " + b("t.amount") + " " + b("t.mode"));
            check("client.getPlayer / world.getBlockAt", mc.thePlayer.getName().equals(b("t.player")) && "grass".equals(b("t.below")), b("t.player") + " on " + b("t.below"));
            check("onPreMotion, onPostMotion, onPostPlayerInput every tick", num("t.motions") >= 45 && num("t.posts") >= 45 && num("t.inputs") >= 45,
                num("t.motions") + " / " + num("t.posts") + " / " + num("t.inputs"));
            check("onPacketSent / onPacketReceived", num("t.sent") > 0 && num("t.received") > 0, num("t.sent") + " / " + num("t.received"));
            check("onRenderTick / onRenderWorld", num("t.renders") > 0 && num("t.world") > 0, num("t.renders") + " / " + num("t.world"));
            check("onPreMotion's pitch is what the server gets", Math.abs(ServerState.pitch() - 33.5f) < 0.01f, "sent " + ServerState.pitch());
            check("onPreMotion leaves the camera alone", mc.thePlayer.rotationPitch == camPitch, mc.thePlayer.rotationPitch);
        });

        phase("cancelling", 20, () -> {
            c0aCancelled = c0aSeen = 0;
            mc.thePlayer.swingItem();
            DevTest.server(p -> p.addChatMessage(new ChatComponentText("GALE-HIDE-ME please")));
        }, t -> { }, () -> {
            check("onPacketSent returning false cancels the packet", num("t.c0a") == 1 && c0aCancelled >= 1 && c0aSeen == 0, c0aCancelled + " cancelled, " + c0aSeen + " sent");
            check("onChat returning false hides the message (the one-argument form)", "GALE-HIDE-ME please".equals(b("t.chat")), b("t.chat"));
            int pass = gale.b4script.ScriptInvoker.dispatchBoolean(gale.b4script.ScriptEvent.MOUSE, 1, true, 10, 10);
            int block = gale.b4script.ScriptInvoker.dispatchBoolean(gale.b4script.ScriptEvent.MOUSE, 4, true, 10, 10);
            check("onMouse(int, boolean) gets B4's mouse event, and can block a button", num("t.mouse") == 4 && pass == 1 && block == 0, pass + " " + block);
        });

        phase("Gale changes the script's settings", 10, () -> {
            script("GaleT1").getSetting("Flag").parse("off");
            script("GaleT1").getSetting("Amount").parse("70");
        }, t -> { }, () -> {
            check("the script sees Gale's setting change", Boolean.FALSE.equals(b("t.flag")) && Double.valueOf(70).equals(b("t.amount")), b("t.flag") + " " + b("t.amount"));
            check("without the flag the pitch is yours again", Math.abs(ServerState.pitch() - camPitch) < 0.01f, ServerState.pitch());
        });

        phase("getRotations", 30, () -> {
            camYaw = mc.thePlayer.rotationYaw;
            script("GaleT2").setEnabled(true);
        }, t -> { }, () -> {
            float yaw = MathUtil.wrap180(ServerState.yaw()), pitch = ServerState.pitch();
            check("getRotations is the server rotation", Math.abs(MathUtil.wrap180(yaw - 45f)) < 1f && Math.abs(pitch - 20f) < 1f, String.format("yaw %.2f pitch %.2f", yaw, pitch));
            check("the camera keeps its own rotation", mc.thePlayer.rotationYaw == camYaw, mc.thePlayer.rotationYaw + " vs " + camYaw);
            check("Gale holds a silent rotation for the script", RotationManager.isActive(), RotationManager.owner());
            script("GaleT2").setEnabled(false);
        });

        phase("reload a changed script", 200, () -> {
            ScriptDefaults.bridge.remove("t.loaded");
            write("scripts-test", "GaleT1", T1 + "\n// changed");
            ScriptManager.reload();
        }, t -> { }, () -> {
            ScriptModule t1 = script("GaleT1");
            check("the changed script is loaded again", !ScriptManager.loading && "yes".equals(b("t.loaded")) && t1 != null, b("t.loaded"));
            check("it stays on, with its settings", t1 != null && t1.isEnabled() && t1.getSetting("Amount").display().equals("70ms"), t1 == null ? null : t1.isEnabled() + " " + t1.getSetting("Amount"));
        });

        phase("switch off", 10, () -> script("GaleT1").setEnabled(false), t -> { }, () -> {
            check("onDisable ran", num("t.disabled") == 1, num("t.disabled"));
            check("with no script on, the events are off Forge's and Gale's bus", ScriptManager.events == null, "");
        });

        // ---------------------------------------------------------------- the user's own scripts
        phase("load the B4 folder", 900, () -> manager().folder.set("galemod/scripts-all"), t -> {
            if (t > 40 && !ScriptManager.loading && script("BetterIsland") != null && !script("BetterIsland").isEnabled()) {
                script("BetterIsland").setEnabled(true);
                if (script("Watermark") != null) script("Watermark").setEnabled(true);
            }
            if (t == 880) shot = "b4script-03-betterisland";
        }, () -> {
            // GaleLink is B4's side of the link: Gale leaves it out on purpose
            File[] files = dir("scripts-all").listFiles((d, n) -> n.endsWith(".java") && !n.startsWith("_") && !n.equalsIgnoreCase("GaleLink.java"));
            int total = files == null ? 0 : files.length, failed = 0;
            List<String> failures = new ArrayList<>();
            for (ScriptModule m : ScriptManager.modules()) {
                if (!m.script.failed) continue;
                failed++;
                failures.add(m.getName() + (m.script.loadErrors == null ? "" : " " + m.script.loadErrors));
            }
            DevTest.line("B4 folder: " + total + " files, " + ScriptManager.modules().length + " loaded, " + failed + " failed");
            for (String f : failures) DevTest.line("  failed: " + f);
            check("every script of the B4 folder compiles and loads", !ScriptManager.loading && failed == 0 && ScriptManager.modules().length == total,
                ScriptManager.modules().length + "/" + total + " loaded, " + failed + " failed");
            check("GaleLink is left out (it is B4's side of the link)", script("GaleLink") == null, "");
            ScriptModule bi = script("BetterIsland");
            check("BetterIsland runs under Gale", bi != null && bi.isEnabled() && !bi.script.failed, bi == null ? "missing" : "on " + bi.isEnabled());
        });

        phase("finish", 20, () -> {
            manager().folder.set("galemod/scripts");
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) if (Gale.modules().all().contains(e.getKey())) e.getKey().setEnabled(e.getValue());
        }, t -> { }, () -> {
            check("changing the folder takes the old scripts out", ScriptManager.modules().length == 0 || !ScriptManager.loading, ScriptManager.modules().length);
            List<Script> left = ScriptManager.scripts();
            DevTest.line("scripts left: " + left.size());
            Gale.config().save();
        });
    }

    // ------------------------------------------------------------------ exposcaffold bridging (gradlew runClient -Pexpotest)

    private static double startY, minY, startX, startZ;
    private static BlockPos platform;

    /**
     * The user's exposcaffold.java (copied to run/galemod/scripts-expo) run by Gale alone: standing on one block high
     * up, W held for 5 seconds, it must bridge (never drop below the start, get more than 8 blocks away).
     */
    static void expoPhases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "expo-test");
        phase("load exposcaffold", 200, () -> {
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != Gale.modules().get("HUD")) m.setEnabled(false);
            manager().folder.set("galemod/scripts-expo");
            if (!ScriptManager.loading && script("exposcaffold") == null) ScriptManager.reload();
        }, t -> { }, () -> {
            ScriptModule m = script("exposcaffold");
            check("exposcaffold loads under Gale", m != null && !m.script.failed, m == null ? "missing" : m.getInfo());
            if (m != null) for (Setting<?> s : m.getSettings()) s.reset();
        });
        bridgePhase("defaults (SPRINT, humanized)", 0, null);
        bridgePhase("your settings (KEEP_Y, Humanize off)", 40, m -> {
            m.getSetting("Normal mode").parse("KEEP_Y");
            m.getSetting("Humanize rotations").parse("off");
        });
        phase("expo finish", 10, () -> {
            KeyUtil.restore(mc.gameSettings.keyBindForward);
            if (script("exposcaffold") != null) script("exposcaffold").setEnabled(false);
            manager().folder.set("galemod/scripts");
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) if (Gale.modules().all().contains(e.getKey())) e.getKey().setEnabled(e.getValue());
        }, t -> { }, () -> Gale.config().save());
    }

    private static void bridgePhase(String name, int offset, java.util.function.Consumer<ScriptModule> setup) {
        phase("expo bridge: " + name, 110, () -> {
            ScriptModule m = script("exposcaffold");
            if (m == null) return;
            if (setup != null) setup.accept(m);
            BlockPos me = new BlockPos(mc.thePlayer);
            platform = new BlockPos(me.getX() + offset, 24, me.getZ() - 40);
            give(0, new ItemStack(net.minecraft.init.Blocks.wool, 64));
            give(1, new ItemStack(net.minecraft.init.Blocks.wool, 64));
            mc.thePlayer.inventory.currentItem = 0;
            DevTest.server(p -> {
                p.worldObj.setBlockState(platform, net.minecraft.init.Blocks.stone.getDefaultState(), 3);
                p.playerNetServerHandler.setPlayerLocation(platform.getX() + 0.5, platform.getY() + 1, platform.getZ() + 0.5, 0f, 10f);
            });
        }, t -> {
            ScriptModule m = script("exposcaffold");
            if (m == null) return;
            if (t == 10) {
                // standing on the block: go
                startY = minY = mc.thePlayer.posY;
                startX = mc.thePlayer.posX;
                startZ = mc.thePlayer.posZ;
                m.setEnabled(true);
                KeyUtil.setPressed(mc.gameSettings.keyBindForward, true);
            }
            if (t > 10) minY = Math.min(minY, mc.thePlayer.posY);
            if (t == 70) shot = "expo-" + (offset == 0 ? "sprint" : "keepy");
        }, () -> {
            KeyUtil.restore(mc.gameSettings.keyBindForward);
            ScriptModule m = script("exposcaffold");
            if (m != null) m.setEnabled(false);
            double dist = Math.hypot(mc.thePlayer.posX - startX, mc.thePlayer.posZ - startZ);
            int wool = 0;
            for (int dz = 1; dz <= 30; dz++) if (mc.theWorld.getBlockState(platform.add(0, 0, dz)).getBlock() == net.minecraft.init.Blocks.wool) wool++;
            check("expo bridges (" + name + "): never falls", minY >= startY - 0.1, String.format("start y %.2f, lowest %.2f", startY, minY));
            check("expo bridges (" + name + "): goes forward", dist > 8, String.format("%.1f blocks, %d wool placed in line", dist, wool));
        });
    }

    private static void give(int slot, ItemStack stack) {
        mc.thePlayer.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy());
        DevTest.server(p -> p.inventory.setInventorySlotContents(slot, stack == null ? null : stack.copy()));
    }
}
