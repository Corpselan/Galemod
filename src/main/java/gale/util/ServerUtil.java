package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The server you are on: address, Hypixel game, sidebar scoreboard, tab header and footer. */
public final class ServerUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field TAB_HEADER = ReflectUtil.field(GuiPlayerTabOverlay.class, "header", "field_175256_i");
    private static final Field TAB_FOOTER = ReflectUtil.field(GuiPlayerTabOverlay.class, "footer", "field_175255_h");

    private ServerUtil() { }

    /** The server address, "singleplayer", or "" in menus. */
    public static String address() {
        if (mc.isSingleplayer()) return "singleplayer";
        return mc.getCurrentServerData() == null ? "" : mc.getCurrentServerData().serverIP.toLowerCase(Locale.ROOT);
    }

    public static boolean isHypixel() { return address().contains("hypixel.net"); }

    /** The sidebar title without colours ("BED WARS"), "" when there is none. */
    public static String scoreboardTitle() {
        if (mc.theWorld == null) return "";
        ScoreObjective o = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        return o == null ? "" : ChatUtil.strip(o.getDisplayName());
    }

    /** The sidebar lines top to bottom, without colours. */
    public static List<String> scoreboard() {
        List<String> out = new ArrayList<>();
        if (mc.theWorld == null) return out;
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScoreObjective o = sb.getObjectiveInDisplaySlot(1);
        if (o == null) return out;
        Collection<Score> scores = sb.getSortedScores(o);
        for (Score s : scores) {
            if (s.getPlayerName() == null || s.getPlayerName().startsWith("#")) continue;
            ScorePlayerTeam team = sb.getPlayersTeam(s.getPlayerName());
            out.add(ChatUtil.strip(ScorePlayerTeam.formatPlayerName(team, s.getPlayerName())));
        }
        Collections.reverse(out);
        return out;
    }

    /** Whether a sidebar line contains the text (case ignored). */
    public static boolean scoreboardHas(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String line : scoreboard()) if (line.toLowerCase(Locale.ROOT).contains(t)) return true;
        return false;
    }

    /** The Hypixel game you are in from the sidebar title: BEDWARS, SKYWARS, DUELS, ... or "" when not known. */
    public static String hypixelGame() {
        String t = scoreboardTitle().toUpperCase(Locale.ROOT).replace(" ", "");
        String[] games = { "BEDWARS", "SKYWARS", "DUELS", "THEPIT", "MURDERMYSTERY", "BUILDBATTLE", "UHC", "MEGAWALLS", "BLITZSG", "ARCADE",
            "TNTGAMES", "HOUSING", "SKYBLOCK", "WOOLWARS", "PROTOTYPE", "HYPIXEL" };
        for (String g : games) if (t.contains(g)) return g;
        return "";
    }

    public static String tabHeader() { return componentText(ReflectUtil.get(TAB_HEADER, mc.ingameGUI.getTabList())); }

    public static String tabFooter() { return componentText(ReflectUtil.get(TAB_FOOTER, mc.ingameGUI.getTabList())); }

    private static String componentText(Object c) { return c instanceof IChatComponent ? ChatUtil.strip(((IChatComponent) c).getFormattedText()) : ""; }
}
