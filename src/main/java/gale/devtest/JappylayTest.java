package gale.devtest;

import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpServer;
import gale.Gale;
import gale.event.Priority;
import gale.event.impl.ChatEvent;
import gale.event.impl.FrameEvent;
import gale.event.impl.PacketEvent;
import gale.module.Module;
import gale.module.impl.minigames.Jappylay;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.ClientCommandHandler;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Jappylay, Pug's stats overlay (gradlew runClient -Pjappylaytest): a made-up Bed Wars lobby with a fake sidebar and
 * tab list, Prism's Flashlight played by a local server, then the overlay's stats, nick, tags, /who, /ov and final
 * kills. Screenshots in run/screenshots/jappylay-*.png.
 */
final class JappylayTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SC = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID NICK = UUID.fromString("55555555-5555-1555-8555-555555555555");
    private static final Map<Module, Boolean> SAVED = new LinkedHashMap<>();
    private static HttpServer server;
    private static volatile int whoSent, prismRequests, accountLookups;
    private static volatile boolean firstOnlineHidden, laterOnlineShown;
    private static String shot;

    private JappylayTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("Jappylay " + name, ticks, enter, tick, exit));
    }

    private static Jappylay jappylay() { return Gale.modules().get(Jappylay.class); }

    private static String id(UUID u) { return u.toString().replace("-", ""); }

    private static Map<String, Object> entry(UUID u) { return jappylay().overlayEntry(id(u)); }

    private static String field(UUID u, String key) {
        Map<String, Object> e = entry(u);
        return e == null ? null : String.valueOf(e.get(key));
    }

    // Prism's answers for the made-up players
    private static String playerdata(String uuid) {
        long now = System.currentTimeMillis();
        if (uuid.equals(id(A))) return "{\"success\":true,\"player\":{\"displayname\":\"PlayerA\",\"newPackageRank\":\"MVP_PLUS\",\"rankPlusColor\":\"GOLD\","
            + "\"lastLogin\":" + (now - 3600000) + ",\"lastLogout\":" + (now - 7200000) + ",\"stats\":{\"Bedwars\":{\"Experience\":1000000,"
            + "\"final_kills_bedwars\":1200,\"final_deaths_bedwars\":300,\"winstreak\":12,\"games_played_bedwars_1\":5}}}}";
        if (uuid.equals(id(B)) || uuid.equals(id(C)) || uuid.equals(id(SC))) {
            String name = uuid.equals(id(B)) ? "PlayerB" : uuid.equals(id(C)) ? "PlayerC" : "Someone";
            return "{\"success\":true,\"player\":{\"displayname\":\"" + name + "\",\"lastLogin\":" + (now - 600000) + ",\"lastLogout\":" + (now - 900000)
                + ",\"stats\":{\"Bedwars\":{\"Experience\":0,\"final_kills_bedwars\":0,\"final_deaths_bedwars\":0}}}}";
        }
        return "{\"success\":true,\"player\":null}";
    }

    private static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", ex -> {
            prismRequests++;
            String path = ex.getRequestURI().getPath(), query = ex.getRequestURI().getQuery();
            String body;
            if (path.equals("/v1/playerdata") && query != null && query.startsWith("uuid=")) body = playerdata(query.substring(5));
            else if (path.startsWith("/v1/tags/"))
                body = path.endsWith(id(A)) ? "{\"tags\":{\"cheating\":\"high\",\"sniping\":\"medium\"}}" : "{\"tags\":{\"cheating\":\"none\",\"sniping\":\"none\"}}";
            else if (path.equals("/v1/account/username/PlayerC") && ++accountLookups > 0) body = "{\"success\":true,\"uuid\":\"" + C + "\",\"username\":\"PlayerC\"}";
            else if (path.equals("/v1/account/username/Someone")) body = "{\"success\":true,\"uuid\":\"" + SC + "\",\"username\":\"Someone\"}";
            else body = "{\"success\":false}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        System.setProperty("gale.jappylay.prism", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void tab(UUID u, String name) {
        Map<UUID, NetworkPlayerInfo> map = ReflectUtil.get(ReflectUtil.field(NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i"), mc.getNetHandler());
        if (map != null) map.put(u, new NetworkPlayerInfo(new GameProfile(u, name)));
    }

    private static void untab(UUID u) {
        Map<UUID, NetworkPlayerInfo> map = ReflectUtil.get(ReflectUtil.field(NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i"), mc.getNetHandler());
        if (map != null) map.remove(u);
    }

    /** The sidebar a Bed Wars pregame (or game) shows, with the server line "10/17/26  m77A". */
    private static void sidebar(boolean inGame) {
        DevTest.server(p -> {
            Scoreboard sb = p.worldObj.getScoreboard();
            ScoreObjective old = sb.getObjective("jappy");
            if (old != null) sb.removeObjective(old);
            ScoreObjective o = sb.addScoreObjective("jappy", IScoreObjectiveCriteria.DUMMY);
            o.setDisplayName("BED WARS");
            sb.setObjectiveInDisplaySlot(1, o);
            String[] lines = inGame
                ? new String[]{ "10/17/26  m77A", " ", "R Red: ✔", "B Blue: ✔ YOU", "  ", "Kills: 0", "Final Kills: 0", "www.hypixel.net" }
                : new String[]{ "10/17/26  m77A", " ", "Map: Lighthouse", "Players: 4/8", "  ", "Starting in 15s", "   ", "Mode: Solo", "www.hypixel.net" };
            for (int i = 0; i < lines.length; i++) sb.getValueFromObjective(lines[i], o).setScorePoints(lines.length - i);
        });
    }

    private static void say(String text) { DevTest.server(p -> p.addChatMessage(new ChatComponentText(text))); }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "jappylay-test");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.MONITOR, e -> {
            C01PacketChatMessage c01 = e.as(C01PacketChatMessage.class);
            if (c01 != null && !e.isCancelled() && c01.getMessage().equals("/who")) whoSent++;
        }, "jappylay-test");
        Gale.BUS.listen(ChatEvent.class, Priority.MONITOR, e -> {
            if (!e.text().startsWith("ONLINE: ")) return;
            if (e.isCancelled()) firstOnlineHidden = true;
            else laterOnlineShown = true;
        }, "jappylay-test");

        phase("a Bed Wars pregame", 80, () -> {
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn()) SAVED.put(m, m.isEnabled());
            for (Module m : Gale.modules().all()) if (!m.isAlwaysOn() && m != Gale.modules().get("HUD")) m.setEnabled(false);
            try {
                startServer();
            } catch (Exception e) {
                check("local Flashlight starts", false, e);
            }
            sidebar(false);
            tab(A, "PlayerA");
            tab(B, "PlayerB");
            tab(NICK, "Nicked1");
            jappylay().setEnabled(true);
        }, t -> { if (t == 75) shot = "jappylay-1-pregame"; }, () -> {
            check("reads the pregame from the sidebar", jappylay().status() == 2, "status " + jappylay().status());
            check("reads the server (lobby) id", "m77A".equals(jappylay().lobby()), jappylay().lobby());
            check("you are not listed (Show Yourself off)", jappylay().overlayEntry(id(mc.thePlayer.getUniqueID())) == null, "");
            check("PlayerA's stats from Flashlight: star 207, FKDR 4, winstreak 12", "§6207".equals(field(A, "star")) && "§e4".equals(field(A, "fkdr"))
                && "§712".equals(field(A, "winstreaks")), field(A, "star") + " " + field(A, "fkdr") + " " + field(A, "winstreaks"));
            check("PlayerA's rank in the name ([MVP+] with a gold plus)", "§b[MVP§6+§b] PlayerA".equals(field(A, "player")), field(A, "player"));
            check("Prism tags: cheating high, sniping medium", "§cC ".equals(field(A, "cheating")) && "§6S".equals(field(A, "sniping")),
                field(A, "cheating") + "|" + field(A, "sniping"));
            check("session: online for an hour", String.valueOf(field(A, "session")).endsWith("1h"), field(A, "session"));
            check("PlayerB without stats: star 0", "§70".equals(field(B, "star")), field(B, "star"));
            check("a nick (no real UUID) is marked nicked", "true".equals(field(NICK, "nicked")), field(NICK, "nicked"));
            check("three players shown", jappylay().shownRows() == 3, jappylay().shownRows());
        });

        phase("its own /who", 60, () -> {
            whoSent = 0;
            firstOnlineHidden = laterOnlineShown = false;
            say("PlayerA has joined (4/8)!");
        }, t -> {
            if (t == 40) say("ONLINE: PlayerA, PlayerB, PlayerC");
        }, () -> {
            check("sends /who when someone joins the pregame", whoSent == 1, whoSent + " sent");
            check("hides the answer to its own /who", firstOnlineHidden, "");
            check("/who gives the join order (first in the list joined last)", "2".equals(field(A, "joinvalue")) && "1".equals(field(B, "joinvalue")),
                field(A, "joinvalue") + " " + field(B, "joinvalue"));
            check("a /who name not in the tab list is looked up (Flashlight account)", accountLookups == 1, accountLookups + " lookups");
        });

        phase("/ov", 40, () -> {
            ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/ov hide PlayerB");
            ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/ov sc Someone");
        }, t -> { }, () -> {
            check("/ov hide takes PlayerB off", entry(B) == null, "");
            check("/ov sc adds a player by name, kept as manual", entry(SC) != null && "true".equals(field(SC, "manual")), field(SC, "manual"));
            ClientCommandHandler.instance.executeCommand(mc.thePlayer, "/ov clearhidden");
        });

        phase("a game: teams and final kills", 60, () -> {
            DevTest.server(p -> {
                Scoreboard sb = p.worldObj.getScoreboard();
                ScorePlayerTeam red = sb.getTeam("jR") != null ? sb.getTeam("jR") : sb.createTeam("jR");
                ScorePlayerTeam blue = sb.getTeam("jB") != null ? sb.getTeam("jB") : sb.createTeam("jB");
                red.setNamePrefix("§c§lR §c");
                blue.setNamePrefix("§9§lB §9");
                sb.addPlayerToTeam("PlayerA", "jR");
                sb.addPlayerToTeam("PlayerB", "jB");
                sb.addPlayerToTeam("Nicked1", "jB");
            });
            sidebar(true);
        }, t -> {
            if (t == 30) say("PlayerA was killed by PlayerB. FINAL KILL!");
            if (t == 55) shot = "jappylay-2-game";
        }, () -> {
            check("reads the game from the sidebar", jappylay().status() == 3, "status " + jappylay().status());
            check("in a game the name is the team's (prefix cut off, colour kept)", "§cPlayerA".equals(field(A, "player")), field(A, "player"));
            check("a final kill marks the player dead", jappylay().isDead("PlayerA"), "");
        });

        phase("finish", 10, () -> {
            jappylay().setEnabled(false);
            for (UUID u : new UUID[]{ A, B, NICK }) untab(u);
            DevTest.server(p -> {
                Scoreboard sb = p.worldObj.getScoreboard();
                ScoreObjective o = sb.getObjective("jappy");
                if (o != null) sb.removeObjective(o);
                for (String t : new String[]{ "jR", "jB" }) if (sb.getTeam(t) != null) sb.removeTeam(sb.getTeam(t));
            });
            if (server != null) server.stop(0);
            System.clearProperty("gale.jappylay.prism");
            for (Map.Entry<Module, Boolean> e : SAVED.entrySet()) if (Gale.modules().all().contains(e.getKey())) e.getKey().setEnabled(e.getValue());
        }, t -> { }, () -> {
            check("every request went to the local Flashlight", prismRequests > 0, prismRequests + " requests");
            Gale.config().save();
        });
    }
}
