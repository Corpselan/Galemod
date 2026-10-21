package gale.devtest;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.devtest.fakeb4.FakeDefaults;
import gale.devtest.fakeb4.FakeScript;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.script.api.InputApi;
import gale.script.api.ScreenApi;
import gale.util.KeyUtil;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** The self test of the script API's areas (packets, data, render, input, screens, system, crosshair) and their switches. */
final class ScriptApiTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int DUMMY = -4343;
    private static final AtomicInteger count = new AtomicInteger(), count2 = new AtomicInteger(), count3 = new AtomicInteger(), count4 = new AtomicInteger();
    private static final List<String> events = new CopyOnWriteArrayList<>();
    private static volatile Object seen;
    private static ServerSocket tcpServer, wsServer;
    private static String clipboard;
    private static float fovBefore;

    private ScriptApiTest() { }

    private static FakeScript s() { return DevTest.script; }

    private static void check(String what, boolean ok, Object detail) { DevTest.check(what, ok, String.valueOf(detail)); }

    private static void phase(String name, int length, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase(name, length, enter, tick, exit));
    }

    private static void reset() {
        count.set(0);
        count2.set(0);
        count3.set(0);
        count4.set(0);
        events.clear();
        seen = null;
    }

    static void phases() {
        packets();
        data();
        mining();
        render();
        input();
        screens();
        system();
        crosshair();
        switches();
    }

    // ------------------------------------------------------------------ packets

    private static void packets() {
        phase("script API: packets", 40, () -> {
            reset();
            s().on("packetIn", (n, a) -> {
                count.incrementAndGet();
                if (seen == null) seen = ((Object[]) a)[0];
                return 0;
            });
            s().on("packetOut", (n, a) -> {
                if ("C0FPacketConfirmTransaction".equals(((Object[]) a)[1])) count2.incrementAndGet();
                return 0;
            });
            Object p = s().call("packet.create", "C0FPacketConfirmTransaction", "windowId", 5, "uid", 77, "accepted", true);
            check("packet.create makes a packet (as B4's CPacket)", p instanceof FakeDefaults.CPacket, p);
            Object fields = s().call("packet.fields", p);
            check("packet.fields reads the fields by name", fields instanceof Map && Integer.valueOf(5).equals(((Map<?, ?>) fields).get("windowId"))
                && Integer.valueOf(77).equals(((Map<?, ?>) fields).get("uid")) && Boolean.TRUE.equals(((Map<?, ?>) fields).get("accepted")), fields);
            s().call("packet.set", p, "uid", 99);
            check("packet.set changes the packet itself", Integer.valueOf(99).equals(s().call("packet.get", p, "uid")), s().call("packet.get", p, "uid"));
            check("a number prefix finds the packet when only one has it", s().call("packet.create", "C0F") instanceof FakeDefaults.CPacket, "");
            check("a prefix two packets share gives null", s().call("packet.create", "S19") == null, "");
            Object s12 = s().call("packet.create", "EntityVelocity", "entityID", 1, "motionX", 8000);
            check("server packets come back as B4's own wrapper (S12)", s12 instanceof FakeDefaults.S12 && ((FakeDefaults.S12) s12).entityId == 1, s12);
            Object chat = s().call("packet.create", "S02PacketChat", "chatComponent", "&ahello");
            Object text = s().call("packet.get", chat, "chatComponent");
            check("chat text goes in with & codes and comes out formatted", String.valueOf(text).contains("§ahello"), text);
            check("packet.list lists the play packets", s().call("packet.list") instanceof List && ((List<?>) s().call("packet.list")).size() > 90, "");
            s().call("packet.send", p);
            s().call("packet.send", s().call("packet.create", "C0F"), true);
        }, t -> {
            switch (t) {
                case 4:
                    check("packetOut sees a normal send, not a silent one", count2.get() == 1, count2.get() + " C0F");
                    s().call("packet.hold", "out", true, "C0F");
                    for (int i = 0; i < 3; i++) s().call("packet.send", s().call("packet.create", "C0F"));
                    break;
                case 6:
                    check("held sent packets after packetOut saw them", Integer.valueOf(3).equals(s().call("packet.held", "out")) && count2.get() == 4,
                        s().call("packet.held", "out") + " held, " + count2.get() + " seen");
                    check("packet.release lets the oldest go", Integer.valueOf(2).equals(s().call("packet.release", "out", 2)), "");
                    break;
                case 8:
                    check("one left", Integer.valueOf(1).equals(s().call("packet.held", "out")), s().call("packet.held", "out"));
                    s().call("packet.hold", "out", false);
                    break;
                case 10:
                    check("hold off lets the rest go", Integer.valueOf(0).equals(s().call("packet.held", "out")) && Boolean.FALSE.equals(s().call("packet.holding", "out")), "");
                    s().call("packet.hold", "in", true, "S03PacketTimeUpdate");
                    break;
                case 38: {
                    Object held = s().call("packet.held", "in");
                    check("received packets can be held too (time updates)", held instanceof Integer && (Integer) held >= 1, held);
                    s().call("packet.drop", "in");
                    s().call("packet.hold", "in", false);
                    break;
                }
                default:
            }
        }, () -> {
            check("packetIn sees received packets, as B4 SPackets", count.get() > 0 && seen instanceof FakeDefaults.SPacket, count.get() + " " + seen);
            s().off();
        });
    }

    // ------------------------------------------------------------------ data

    private static void data() {
        phase("script API: entity and world data", 4, () -> {
            EntityPlayerSP p = mc.thePlayer;
            Object me = s().wrap(p);
            Object pos = s().call("entity.serverPos", me);
            check("entity.serverPos (you: what was sent last)", pos instanceof FakeDefaults.Vec3 && Math.abs(((FakeDefaults.Vec3) pos).y - p.posY) < 1, pos);
            check("entity.velocity", s().call("entity.velocity", me) instanceof FakeDefaults.Vec3, "");
            check("entity.headYaw", s().call("entity.headYaw", me) instanceof Double, "");
            check("entity.useTicks is 0 while not using", Integer.valueOf(0).equals(s().call("entity.useTicks", me)), "");
            check("entity.data has the flags", s().call("entity.data", me) instanceof Map && ((Map<?, ?>) s().call("entity.data", me)).containsKey("0"), "");
            check("entity.box", s().call("entity.box", me) instanceof double[], "");
            BlockPos below = new BlockPos(p.posX, p.getEntityBoundingBox().minY - 0.01, p.posZ);
            net.minecraft.block.Block block = mc.theWorld.getBlockState(below).getBlock();
            Object hard = s().call("block.hardness", below.getX(), below.getY(), below.getZ());
            check("block.hardness", hard instanceof Double && Math.abs((Double) hard - block.getBlockHardness(mc.theWorld, below)) < 1e-6, hard + " " + block);
            float perTick = block.getPlayerRelativeBlockHardness(p, mc.theWorld, below);
            Object ticks = s().call("block.breakTicks", below.getX(), below.getY(), below.getZ());
            check("block.breakTicks", Integer.valueOf(perTick >= 1 ? 1 : (int) Math.ceil(1 / perTick)).equals(ticks), ticks);
            Object state = s().call("block.state", below.getX(), below.getY(), below.getZ());
            check("block.state has the block's name", state instanceof Map && String.valueOf(((Map<?, ?>) state).get("name")).startsWith("minecraft:"), state);
            Object boxes = s().call("block.boxes", below.getX(), below.getY(), below.getZ());
            check("block.boxes", boxes instanceof List && ((List<?>) boxes).size() == 1, boxes);
            double eye = p.posY + p.getEyeHeight();
            Object ray = s().call("raycast", p.posX, eye, p.posZ, 0, 90, 5);
            check("raycast straight down hits the block under you", ray instanceof Object[] && "block".equals(((Object[]) ray)[0])
                && ((FakeDefaults.Vec3) ((Object[]) ray)[2]).y == below.getY(), ray instanceof Object[] ? java.util.Arrays.toString((Object[]) ray) : ray);
            Object path = s().call("sim.path", 5);
            check("sim.path predicts 5 ticks", path instanceof List && ((List<?>) path).size() >= 5, path);
            Object proj = s().call("projectile.path", p.rotationYaw, -30, 1, "thrown");
            check("projectile.path: a snowball thrown up comes down on something", proj instanceof Map && ((Map<?, ?>) proj).get("hit") != null
                && (Integer) ((Map<?, ?>) proj).get("ticks") > 10, proj instanceof Map ? ((Map<?, ?>) proj).get("hit") + " " + ((Map<?, ?>) proj).get("ticks") : proj);
        }, t -> { }, () -> { });
    }

    // ------------------------------------------------------------------ who is breaking which block

    private static volatile int breakerId = -1;
    private static BlockPos broken;

    private static void mining() {
        phase("script API: who is breaking which block (S25)", 20, () -> {
            reset();
            breakerId = -1;
            EntityPlayerSP p = mc.thePlayer;
            broken = new BlockPos(p.posX + 2, p.getEntityBoundingBox().minY - 1, p.posZ);
            DevTest.server(sp -> {
                net.minecraft.entity.passive.EntityPig pig = new net.minecraft.entity.passive.EntityPig(sp.worldObj);
                pig.setPosition(sp.posX + 3, sp.posY, sp.posZ + 1);
                sp.worldObj.spawnEntityInWorld(pig);
                breakerId = pig.getEntityId();
            });
            s().on("mining", (n, a) -> {
                Object[] x = (Object[]) a;
                events.add(x[1] + " " + x[3] + " " + (x[0] != null));
                return 0;
            });
        }, t -> {
            if (t == 4) DevTest.server(sp -> sp.worldObj.sendBlockBreakProgress(breakerId, broken, 5));
            if (t == 8) {
                Object list = s().call("mining.list");
                Map<?, ?> m = null;
                if (list instanceof List) for (Object o : (List<?>) list) if (Integer.valueOf(breakerId).equals(((Map<?, ?>) o).get("entityId"))) m = (Map<?, ?>) o;
                check("mining.list: who, which block, how far", m != null && Integer.valueOf(5).equals(m.get("stage")) && m.get("entity") instanceof FakeDefaults.Entity
                    && ((FakeDefaults.Vec3) m.get("pos")).x == broken.getX() && ((FakeDefaults.Vec3) m.get("pos")).y == broken.getY(), m);
                Object at = s().call("mining.at", broken.getX(), broken.getY(), broken.getZ());
                check("mining.at the block", at instanceof Map && Integer.valueOf(breakerId).equals(((Map<?, ?>) at).get("entityId")), at);
                check("mining.by the pig", s().call("mining.by", breakerId) instanceof Map, "");
                check("the mining event fired with the stage and the entity", events.contains(breakerId + " 5 true"), events);
                boolean names = true;
                for (String n : new String[]{ "S12PacketEntityVelocity", "S19PacketEntityStatus", "S25PacketBlockBreakAnim", "S27PacketExplosion" }) {
                    try {
                        Class<?> c = Class.forName("gale.core.HookedPackets$" + n);
                        names &= c.getSimpleName().equals(n) && c.getSuperclass().getSimpleName().equals(n);
                    } catch (ClassNotFoundException ex) {
                        names = false;
                    }
                }
                check("Gale's stand-in packets keep the packets' names (B4 scripts check them)", names, "");
                DevTest.server(sp -> sp.worldObj.sendBlockBreakProgress(breakerId, broken, -1));
            }
            if (t == 14) {
                check("stopping: gone from the list, event stage -1", s().call("mining.at", broken.getX(), broken.getY(), broken.getZ()) == null
                    && events.contains(breakerId + " -1 true"), events);
            }
        }, () -> {
            s().off();
            DevTest.server(sp -> {
                net.minecraft.entity.Entity pig = sp.worldObj.getEntityByID(breakerId);
                if (pig != null) pig.setDead();
            });
        });
    }

    // ------------------------------------------------------------------ render

    private static void render() {
        phase("script API: render hooks and options", 30, () -> {
            reset();
            EntityPlayerSP p = mc.thePlayer;
            p.rotationPitch = 0;
            double yr = Math.toRadians(p.rotationYaw);
            EntityOtherPlayerMP d = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "GaleDummy2"));
            d.setPositionAndRotation(p.posX - Math.sin(yr) * 3, p.posY, p.posZ + Math.cos(yr) * 3, 0, 0);
            mc.theWorld.addEntityToWorld(DUMMY, d);
            s().on("renderLiving", (n, a) -> {
                if (((FakeDefaults.Entity) ((Object[]) a)[0]).entityId == DUMMY) count.incrementAndGet();
                return 0;
            });
            s().on("overlay", (n, a) -> {
                events.add(String.valueOf(((Object[]) a)[0]));
                return "CROSSHAIRS".equals(((Object[]) a)[0]) ? 1 : 0;
            });
            s().on("fov", (n, a) -> {
                count2.incrementAndGet();
                ((Object[]) a)[0] = 1.0;
                return 0;
            });
            s().on("particle", (n, a) -> {
                count3.incrementAndGet();
                return 1;
            });
            fovBefore = mc.gameSettings.fovSetting;
            s().call("option.set", "fov", 95);
            check("option.set changes the game's option", mc.gameSettings.fovSetting == 95f && Double.valueOf(95).equals(s().call("option.get", "fov")), mc.gameSettings.fovSetting);
            mc.gameSettings.fovSetting = fovBefore;
        }, t -> {
            if (t == 10 || t == 12) {
                EntityPlayerSP p = mc.thePlayer;
                for (int i = 0; i < 5; i++) mc.theWorld.spawnParticle(EnumParticleTypes.CRIT, p.posX, p.posY + 1, p.posZ + 1, 0, 0.1, 0);
            }
        }, () -> {
            check("renderLiving sees the entity in front of you", count.get() > 0, count.get() + " frames");
            check("overlay sees the HUD's parts", events.contains("HOTBAR") && events.contains("CROSSHAIRS"), events.size() + " events");
            check("fov fires", count2.get() > 0, count2.get());
            check("particle: Gale took over the particles and the script saw them", count3.get() > 0 && mc.effectRenderer.getClass().getSimpleName().equals("HookedEffects"),
                count3.get() + " " + mc.effectRenderer.getClass().getSimpleName());
            s().off();
            mc.theWorld.removeEntityFromWorld(DUMMY);
        });
    }

    // ------------------------------------------------------------------ input

    private static void input() {
        phase("script API: input hooks", 6, () -> {
            s().on("key", (n, a) -> Integer.valueOf(Keyboard.KEY_Q).equals(((Object[]) a)[0]) ? 1 : 0);
            s().on("mouseMove", (n, a) -> {
                ((Object[]) a)[0] = 7;
                ((Object[]) a)[1] = -3;
                return 0;
            });
        }, t -> {
            if (t != 3) return;
            KeyBinding drop = mc.gameSettings.keyBindDrop;
            int key = drop.getKeyCode();
            KeyBinding.setKeyBindState(key, true);
            KeyBinding.onTick(key);
            boolean swallowed = InputApi.onKey(key, true);
            check("a script swallows a key press (the game never sees it)", swallowed && !drop.isKeyDown() && !drop.isPressed(), swallowed + " " + drop.isKeyDown());
            KeyBinding.setKeyBindState(key, false);
            check("other keys go through", !InputApi.onKey(Keyboard.KEY_F7, true), "");
            check("the mouse is taken over", mc.mouseHelper.getClass().getSimpleName().equals("HookedMouse"), mc.mouseHelper.getClass().getSimpleName());
            mc.mouseHelper.mouseXYChange();
            check("mouseMove sets this frame's movement", mc.mouseHelper.deltaX == 7 && mc.mouseHelper.deltaY == -3, mc.mouseHelper.deltaX + " " + mc.mouseHelper.deltaY);
        }, () -> s().off());
        phase("script API: the mouse is given back", 3, () -> { }, t -> { },
            () -> check("no listener: the game's own mouse reader again", mc.mouseHelper.getClass() == net.minecraft.util.MouseHelper.class, mc.mouseHelper.getClass().getSimpleName()));
    }

    // ------------------------------------------------------------------ screens and chat

    private static final java.lang.reflect.Field CHAT_LINES = ReflectUtil.field(GuiNewChat.class, "chatLines", "field_146252_h");

    private static void screens() {
        phase("script API: screens and chat", 10, () -> {
            reset();
            s().on("screenButton", (n, a) -> {
                events.add("button " + ((Object[]) a)[1]);
                return 0;
            });
            s().on("screenDraw", (n, a) -> {
                count.incrementAndGet();
                return 0;
            });
            s().on("screenClose", (n, a) -> {
                events.add("close " + ((Object[]) a)[0]);
                return 0;
            });
            s().on("chatClick", (n, a) -> {
                events.add("click " + ((Object[]) a)[0]);
                return 0;
            });
            s().call("screen.button", "devtest", "ok", 10, 30, 60, 20, "OK");
            s().call("screen.field", "devtest", "name", 10, 60, 100, 20, "hello");
            s().call("screen.open", "devtest", "&eDev test");
        }, t -> {
            if (t == 4) {
                check("screen.open shows the script's screen", mc.currentScreen instanceof ScreenApi.ScriptScreen && "devtest".equals(s().call("screen.current")),
                    mc.currentScreen);
                if (mc.currentScreen instanceof ScreenApi.ScriptScreen) ((ScreenApi.ScriptScreen) mc.currentScreen).pressButton("ok");
                check("a button press reaches the script", events.contains("button ok"), events);
                check("screen.text reads a field", "hello".equals(s().call("screen.text", "devtest", "name")), s().call("screen.text", "devtest", "name"));
                check("screenDraw fires every frame", count.get() > 3, count.get());
                s().call("screen.close");
            }
            if (t == 6) {
                check("closing tells the script", events.contains("close devtest"), events);
                s().call("chat.link", "&bclick me", "callback", "devtest-value", "hover");
                @SuppressWarnings("unchecked")
                List<ChatLine> lines = ReflectUtil.get(CHAT_LINES, mc.ingameGUI.getChatGUI());
                String command = lines == null || lines.isEmpty() || lines.get(0).getChatComponent().getChatStyle().getChatClickEvent() == null ? null
                    : lines.get(0).getChatComponent().getChatStyle().getChatClickEvent().getValue();
                check("chat.link prints a line that runs a Gale command", command != null && command.startsWith("/gale chatcb "), command);
                if (command != null) ClientCommandHandler.instance.executeCommand(mc.thePlayer, command);
                check("clicking it reaches the script", events.contains("click devtest-value"), events);
                check("chat.print takes JSON", Boolean.TRUE.equals(s().call("chat.print", "{\"text\":\"gale json\",\"color\":\"aqua\"}", 4242)), "");
            }
        }, () -> s().off());
    }

    // ------------------------------------------------------------------ system

    private static void system() {
        phase("script API: files, clipboard, TCP, WebSocket", 40, () -> {
            reset();
            check("file.write / file.read", Boolean.TRUE.equals(s().call("file.write", "devtest/a.txt", "hi")) && "hi".equals(s().call("file.read", "devtest/a.txt")), "");
            s().call("file.append", "devtest/a.txt", "!");
            check("file.append", "hi!".equals(s().call("file.read", "devtest/a.txt")), s().call("file.read", "devtest/a.txt"));
            check("file.list", s().call("file.list", "devtest") instanceof List && ((List<?>) s().call("file.list", "devtest")).contains("a.txt"), s().call("file.list", "devtest"));
            check("file.delete", Boolean.TRUE.equals(s().call("file.delete", "devtest/a.txt")) && Boolean.FALSE.equals(s().call("file.exists", "devtest/a.txt")), "");
            check("launcher account files are off limits", s().call("file.read", "../../launcher_accounts.json") == null, "");
            clipboard = GuiScreen.getClipboardString();
            s().call("clipboard.set", "gale-devtest");
            check("clipboard.set / get", "gale-devtest".equals(s().call("clipboard.get")), s().call("clipboard.get"));
            GuiScreen.setClipboardString(clipboard);
            try {
                tcpServer = new ServerSocket(0);
                wsServer = new ServerSocket(0);
            } catch (Exception e) {
                check("test servers start", false, e);
                return;
            }
            serve(tcpServer, ScriptApiTest::echoLines);
            serve(wsServer, ScriptApiTest::echoWebSocket);
            s().on("socket", (n, a) -> {
                Object[] x = (Object[]) a;
                events.add(x[0] + " " + x[1] + " " + x[2]);
                if ("open".equals(x[1])) {
                    if ("tcp".equals(x[0])) s().call("socket.send", "tcp", "ping");
                    else s().call("ws.send", "ws", "hi");
                }
                return 0;
            });
            s().call("socket.open", "tcp", "127.0.0.1", tcpServer.getLocalPort());
            s().call("ws.open", "ws", "ws://127.0.0.1:" + wsServer.getLocalPort() + "/test");
        }, t -> { }, () -> {
            check("TCP: the line came back", events.contains("tcp line ping"), events);
            check("WebSocket: the message came back", events.contains("ws message echo:hi"), events);
            s().call("net.closeAll");
            s().off();
            try {
                tcpServer.close();
                wsServer.close();
            } catch (Exception ignored) {
            }
        });
    }

    private interface Conn {
        void handle(Socket s) throws Exception;
    }

    private static void serve(ServerSocket server, Conn handler) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                handler.handle(s);
            } catch (Exception ignored) {
                // the test checks what arrived
            }
        }, "devtest server");
        t.setDaemon(true);
        t.start();
    }

    private static void echoLines(Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter w = new PrintWriter(s.getOutputStream(), true);
        String line = r.readLine();
        if (line != null) w.println(line);
        Thread.sleep(500);
    }

    private static void echoWebSocket(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String key = null;
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1) return;
            if (c == '\n') {
                String l = line.toString().trim();
                if (l.isEmpty()) break;
                if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(l.indexOf(':') + 1).trim();
                line.setLength(0);
            } else {
                line.append((char) c);
            }
        }
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        in.readUnsignedByte();
        int len = in.readUnsignedByte() & 0x7F;
        byte[] mask = new byte[4];
        in.readFully(mask);
        byte[] data = new byte[len];
        in.readFully(data);
        for (int i = 0; i < len; i++) data[i] ^= mask[i % 4];
        byte[] reply = ("echo:" + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        out.write(reply.length);
        out.write(reply);
        out.flush();
        Thread.sleep(500);
    }

    // ------------------------------------------------------------------ crosshair

    private static void crosshair() {
        phase("script API: crosshair (reach)", 12, () -> {
            reset();
            EntityPlayerSP p = mc.thePlayer;
            p.rotationYaw = p.prevRotationYaw = 0;
            p.rotationPitch = p.prevRotationPitch = 0;
            EntityOtherPlayerMP d = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "GaleDummy3"));
            d.setPositionAndRotation(p.posX, p.posY, p.posZ + 5, 0, 0);
            mc.theWorld.addEntityToWorld(DUMMY, d);
            s().on("pick", (n, a) -> {
                Object[] x = (Object[]) a;
                Object r = s().call("pick.entity", 6);
                if (r instanceof Object[]) {
                    x[0] = "entity";
                    x[1] = ((Object[]) r)[0];
                    x[2] = ((Object[]) r)[1];
                }
                return 0;
            });
            s().on("packetOut", (n, a) -> {
                Object[] x = (Object[]) a;
                if ("C02PacketUseEntity".equals(x[1])) {
                    count.incrementAndGet();
                    seen = s().call("packet.get", x[0], "entityId");
                }
                return 0;
            });
        }, t -> {
            if (t == 2) {
                Object far = s().call("pick.entity", 6), near = s().call("pick.entity", 3);
                check("pick.entity finds it at 6 blocks, not at 3", far instanceof Object[] && ((FakeDefaults.Entity) ((Object[]) far)[0]).entityId == DUMMY && near == null,
                    (far instanceof Object[] ? "found" : "none") + " / " + (near == null ? "none" : "found"));
                check("Gale's player controller is in", mc.playerController.getClass().getSimpleName().equals("HookedController"), mc.playerController.getClass().getSimpleName());
                gale.util.ClickUtil.setLeftClickCounter(0);
                KeyUtil.press(mc.gameSettings.keyBindAttack);
            }
            if (t == 4) KeyUtil.restore(mc.gameSettings.keyBindAttack);
        }, () -> {
            check("a click hits the entity 5 blocks away", count.get() >= 1 && Integer.valueOf(DUMMY).equals(seen), count.get() + " C02, entity " + seen);
            s().off();
            mc.theWorld.removeEntityFromWorld(DUMMY);
        });
    }

    // ------------------------------------------------------------------ switching assists off

    private static void switches() {
        phase("script API: assists switched off in ClickGUI", 30, () -> {
            reset();
            check("eight assists in the Assist category", Gale.modules().in(Category.ASSIST).size() == 8, Gale.modules().in(Category.ASSIST).size());
            Module packets = Gale.modules().get("Script Packets");
            s().on("packetIn", (n, a) -> {
                count.incrementAndGet();
                return 0;
            });
            check("the module shows which script uses it", String.valueOf(packets.getInfo()).contains("DevTestScript"), packets.getInfo());
            packets.setEnabled(false);
            check("off: its functions give null", s().call("packet.list") == null, "");
        }, t -> { }, () -> {
            Module packets = Gale.modules().get("Script Packets");
            check("off: its events stop", count.get() == 0, count.get() + " packets seen");
            packets.setEnabled(true);
            check("on again: it works", s().call("packet.list") instanceof List, "");
            BoolSetting files = (BoolSetting) Gale.modules().get("Script System").getSetting("Files");
            files.set(false);
            check("a part switched off (System → Files)", s().call("file.exists", "x") == null && s().call("clipboard.get") != null, "");
            files.set(true);
            check("core functions are always on", s().call("version") != null, "");
            s().off();
            try {
                ScriptDocs.write(new java.io.File(mc.mcDataDir, "SCRIPTS-reference.md"));
            } catch (Exception e) {
                check("the reference docs are written", false, e);
            }
        });
    }
}
