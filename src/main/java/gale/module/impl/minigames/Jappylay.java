package gale.module.impl.minigames;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gale.b4port.client.Chat;
import gale.b4port.client.Hypixel;
import gale.b4script.GlowShader;
import gale.core.Scheduler;
import gale.event.Priority;
import gale.event.impl.ChatEvent;
import gale.event.impl.EntityJoinEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.TextSetting;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jappylay, Pug's Bed Wars stats overlay (a B4 script), as a Gale module: everyone in your Bed Wars lobby or game with
 * their star, FKDR, winstreak, Prism tags, session length, how often you met them and whether they are out. Stats come
 * from Prism's Flashlight API (no key needed); a lobby is read from the tab list, and before the game starts the
 * script's own /who fills it in. /ov: sc (add a player), hide, clearhidden, reload.
 */
public final class Jappylay extends Module {
    private static final String CHAT_PREFIX = "&7[&dL&7]&r ";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** Prism's Flashlight (the dev self test points this at a local stand-in) */
    private static String prism() { return System.getProperty("gale.jappylay.prism", "https://flashlight.prismoverlay.com"); }
    private static final String S = "§";

    // the overlay's data keys, as the script names them
    private static final String STAR = "star", FKDR = "fkdr", WINSTREAK = "winstreaks", SESSION = "session", DEAD = "dead";
    private static final String STAR_V = "starvalue", FKDR_V = "fkdrvalue", INDEX_V = "indexvalue", SESSION_V = "sessionvalue", WINSTREAK_V = "winstreakvalue";
    private static final String STATE = "requeststate";
    private static final String PLAYER = "player", SEEN = "seen", TAGS = "tags", HEAD = "playerhead";
    private static final String SEEN_V = "seenvalue", JOIN_V = "joinvalue";
    private static final List<String> TAG_KEYS = java.util.Arrays.asList("cheating", "sniping");

    private static final long STATS_WINDOW_MS = 60000L, DEBUG_SPACING_MS = 800L, GLOBAL_COOLDOWN_MS = 15000L, RETRY_BASE_MS = 7000L;
    private static final int STATS_LIMIT = 110, STATS_BUFFER = 8;

    /** A column: its switch, title and key; its width and place, worked out from what it shows. */
    private final class Column {
        final BoolSetting on;
        final String header, key;
        float width, maxWidth, position;
        boolean enabled;

        Column(BoolSetting on, String header, String key) {
            this.on = on;
            this.header = header;
            this.key = key;
            this.width = mc.fontRendererObj.getStringWidth(header);
            this.maxWidth = width;
        }
    }

    private final BoolSetting debug = bool("Debug Mode", false).describe("在聊天栏里打印过滤掉的人、状态和侧边栏");
    private final List<Column> columns = new ArrayList<>();
    private final BoolSetting colEncounters = bool("Encounters", true).describe("这段时间里在几个大厅/对局里碰到过这个人");
    private final BoolSetting colHeads = bool("Player Heads", true);
    private final BoolSetting colUsername = bool("Username", true);
    private final BoolSetting showRanks = bool("Show Ranks", true).describe("名字前显示 Hypixel 的 rank");
    private final BoolSetting colStar = bool("Star", true);
    private final BoolSetting colFkdr = bool("FKDR", true);
    private final ModeSetting fkdrMode = mode("FKDR Mode", "Overall", "Overall", "Solos", "Doubles", "Threes", "Fours", "4v4");
    private final BoolSetting colWinstreak = bool("Winstreaks", true);
    private final ModeSetting winstreakMode = mode("Winstreak Mode", "Overall", "Overall", "Solos", "Doubles", "Threes", "Fours", "4v4");
    private final BoolSetting colTags = bool("Tags", true).describe("Prism 的标记：C 开挂、S 狙人（橙色中等，红色严重）");
    private final BoolSetting colSession = bool("Session", true).describe("这次上线多久了");
    private final BoolSetting colDead = bool("Dead", true).describe("被最终击杀出局的人");
    private final BoolSetting teamsSetting = bool("Teams", true).describe("游戏里按队伍颜色显示和排序");
    private final BoolSetting teamPrefix = bool("Team Prefix", false);
    private final BoolSetting showYourselfSetting = bool("Show Yourself", false);
    private final BoolSetting onlyOnTab = bool("Only Show On Tab", false).describe("只在按住 Tab 时显示");
    private final BoolSetting useBloomSetting = bool("Use Bloom", false);
    private final NumberSetting encountersTimeout = number("Encounters Timeout (mins)", 30, 1, 60, 1);
    private final NumberSetting overlayX = number("Overlay X", 12, 0, 1000, 0.1).suffix("px");
    private final NumberSetting overlayY = number("Overlay Y", 12, 0, 1000, 0.1).suffix("px");
    private final ModeSetting sortBySetting = mode("Sort By", "FKDR", "Encounters", "Star", "FKDR", "Index", "Winstreak", "Join Time");
    private final ModeSetting sortMode = mode("Sort Mode", "Ascending", "Ascending", "Descending");
    private final NumberSetting backgroundOpacity = number("Background Opacity", 170, 0, 255, 5);
    private final NumberSetting headerColor = number("Header Color", 290, 0, 360, 4).describe("表头文字的色相；0 黑色，360 彩虹");
    private final NumberSetting cornerRadius = number("Corner Radius", 5, 0.5, 8, 0.1);
    private final NumberSetting fontSize = number("Font Size", 9, 4.5, 18, 0.5).suffix("px");
    private final TextSetting urchinKey = text("Urchin API key", "").describe("可不填；填了 Prism 的标记会带上它");

    private static final Map<String, String> SORT_KEYS = new LinkedHashMap<>();

    static {
        SORT_KEYS.put("Encounters", SEEN_V);
        SORT_KEYS.put("Star", STAR_V);
        SORT_KEYS.put("FKDR", FKDR_V);
        SORT_KEYS.put("Index", INDEX_V);
        SORT_KEYS.put("Winstreak", WINSTREAK_V);
        SORT_KEYS.put("Join Time", JOIN_V);
    }

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Gale Jappylay");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Map<String, Object>> statsCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingStats = new ConcurrentHashMap<>();
    private final Map<String, Integer> statsFailures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingWho = new ConcurrentHashMap<>();
    private final List<Long> statsRequestTimes = new ArrayList<>();
    private final Object rateLock = new Object();
    private volatile long globalCooldownUntil, lastDebugRequestAt;
    private volatile String prismUserId = "";

    private final Map<String, Map<String, Object>> overlayPlayers = new ConcurrentHashMap<>();
    private final Map<String, String> ignoredPlayers = new ConcurrentHashMap<>();
    private final List<String> currentPlayers = new ArrayList<>();
    private volatile String currentLobby = "";
    private final Map<String, List<Object[]>> encounters = new HashMap<>();
    private final Map<String, String> teams = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deadPlayers = new ConcurrentHashMap<>();
    private volatile boolean needsSort, needsLayout;

    private boolean firstEnable, doWho = true, didWho;
    private volatile boolean ascending;
    private boolean showYourself, showTeamPrefix, showTeamColors, showHeads, showOnlyOnTab, useBloom;
    private volatile String sortBy = FKDR_V;
    private volatile int status;
    private int overlayTicks = 5, lastDebugOverlayState = -1;
    private long lastDebugPrint;
    // the overlay as a HUD element (open the chat to move it; resizing changes the font size)
    private final gale.hud.HudElement hud = new gale.hud.HudElement("jappylay", "Jappylay", this) {
        @Override
        public float[] bounds(float sw, float sh) {
            if (drawnRecently() && endX > startX) return new float[]{ startX, startY, endX - startX, endY - startY };
            float k = fontSize.getFloat() / 9f;
            return new float[]{ overlayX.getFloat(), overlayY.getFloat(), 260f * k, 40f * k };
        }

        @Override
        public void moveTo(float x, float y, float sw, float sh) {
            overlayX.set(Math.max(0, Math.min(1000, Math.round(x * 10f) / 10.0)));
            overlayY.set(Math.max(0, Math.min(1000, Math.round(y * 10f) / 10.0)));
            startX = overlayX.getFloat();
            startY = overlayY.getFloat();
        }

        @Override
        public float scale() { return fontSize.getFloat() / 9f; }

        @Override
        public void scaleTo(float s, float sw, float sh) { fontSize.set(Math.max(4.5, Math.min(18, Math.round(s * 9f * 2f) / 2.0))); }

        @Override
        public float maxScale() { return 2f; }

        @Override
        public void reset() {
            overlayX.reset();
            overlayY.reset();
            fontSize.reset();
        }
    };
    private float startX = 12, startY = 12, endX, endY, offsetY = 3, lineHeight, textScale = 1, fontHeight = 9, headsSize = 10;
    private int columnTitles;

    private static Jappylay instance;

    public Jappylay() {
        super("Jappylay", Category.MINIGAMES, "Pug 的起床战争数据面板（B4 脚本 Jappylay 的移植）：大厅和对局里每个人的星级、FKDR、连胜、Prism 标记、在线时长、碰到几次、出局没有；/ov 命令");
        complements("jappylay", "Jappylay");
        instance = this;
        gale.hud.HudLayout.register(hud);
        hud.showWhen(() -> !isDeferred());
        columns.add(new Column(colEncounters, "E", SEEN));
        columns.add(new Column(colHeads, "H", HEAD));
        columns.add(new Column(colUsername, "Player", PLAYER));
        columns.add(new Column(colStar, "Star", STAR));
        columns.add(new Column(colFkdr, "FK/D", FKDR));
        columns.add(new Column(colWinstreak, "WS", WINSTREAK));
        columns.add(new Column(colTags, "Tags", TAGS));
        columns.add(new Column(colSession, "Online", SESSION));
        columns.add(new Column(colDead, "Dead", DEAD));
        lineHeight = fontHeight + offsetY;
        on(UpdateEvent.class, Priority.NORMAL, e -> preUpdate());
        on(Render2DEvent.class, Priority.NORMAL, e -> render());
        on(ChatEvent.class, Priority.NORMAL, e -> {
            if (!chat(e.formatted())) e.cancel();
        });
        onAlways(EntityJoinEvent.class, Priority.NORMAL, e -> {
            if (mc.thePlayer != null && e.entity == mc.thePlayer) {
                doWho = true;
                didWho = false;
                overlayTicks = 0;
                clearMaps();
            }
        });
    }

    public static Jappylay get() { return instance; }

    @Override
    protected void onEnable() {
        if (mc.thePlayer == null) return;
        if (!firstEnable) {
            print("&eWelcome to &3Jappylay&e! Please run &3/ov&e for commands.");
            firstEnable = true;
        }
        overlayTicks = 5;
        settings();
        updateStatus();
        needsSort = true;
        needsLayout = true;
        doColumns(false);
    }

    private static String color(String s) { return s.replace("&", S); }

    private static void print(String s) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(color(CHAT_PREFIX + s)));
    }

    private static String strip(String s) { return Chat.strip(s); }

    private static double round(double d, int n) {
        if (n == 0) return Math.round(d);
        double p = Math.pow(10.0, n);
        return (double) Math.round(d * p) / p;
    }

    // ------------------------------------------------------------------ settings and layout

    private void settings() {
        showHeads = colHeads.get();
        showYourself = showYourselfSetting.get();
        showTeamPrefix = teamPrefix.get();
        showTeamColors = teamsSetting.get();
        showOnlyOnTab = onlyOnTab.get();
        useBloom = useBloomSetting.get();
        startX = overlayX.getFloat();
        startY = overlayY.getFloat();
        boolean asc = sortMode.is("Ascending");
        String by = SORT_KEYS.get(sortBySetting.get());
        boolean resort = overlayPlayers.size() > 1 && (asc != ascending || !by.equals(sortBy));
        ascending = asc;
        sortBy = by;
        if (resort) sortOverlay();
        columnTitles = hueRGB(headerColor.getFloat(), 255);
        textScale = fontSize.getFloat() / (float) mc.fontRendererObj.FONT_HEIGHT;
        fontHeight = mc.fontRendererObj.FONT_HEIGHT * textScale;
        headsSize = Math.max(8f * textScale, fontHeight - textScale);
        offsetY = 3f * textScale;
        lineHeight = fontHeight + offsetY;
    }

    private void doColumns(boolean updateEnabled) {
        float x = startX + 5f * textScale;
        for (Column c : columns) {
            float longest = c.width * textScale;
            if (updateEnabled) c.enabled = c.on.get();
            if (!c.enabled) continue;
            synchronized (currentPlayers) {
                for (String uuid : currentPlayers) {
                    Map<String, Object> data = uuid == null ? null : overlayPlayers.get(uuid);
                    if (data == null) continue;
                    Object v = data.get(c.key);
                    if (v == null) continue;
                    String text;
                    if (c.key.equals(TAGS)) {
                        StringBuilder sb = new StringBuilder();
                        for (String tag : TAG_KEYS) if (data.get(tag) != null) sb.append(data.get(tag));
                        text = sb.toString();
                    } else {
                        text = v.toString();
                    }
                    float w = mc.fontRendererObj.getStringWidth(text) * textScale;
                    if (c.key.equals(HEAD)) w = headsSize * textScale;
                    if (w > longest) longest = w;
                }
            }
            c.maxWidth = longest;
            c.position = x;
            x += longest + 5f * textScale;
        }
        endX = x;
        int n;
        synchronized (currentPlayers) {
            n = currentPlayers.size();
        }
        endY = startY + lineHeight + n * lineHeight + (n > 0 ? 5f * textScale : textScale);
    }

    private static int chroma(long speed, int alpha) {
        float hue = System.currentTimeMillis() % (15000L / speed) / (15000.0f / speed);
        Color c = Color.getHSBColor(hue, 1.0f, 1.0f);
        return alpha << 24 | c.getRed() << 16 | c.getGreen() << 8 | c.getBlue();
    }

    private static int hueRGB(float hue, int alpha) {
        if (hue == 0) return new Color(0, 0, 0, alpha).getRGB();
        if (hue == 360) return chroma(1L, alpha);
        Color c = Color.getHSBColor(hue / 360.0f, 1.0f, 1.0f);
        return alpha << 24 | c.getRed() << 16 | c.getGreen() << 8 | c.getBlue();
    }

    // ------------------------------------------------------------------ every tick

    private void preUpdate() {
        updateStatus();
        if (status != 3 && !deadPlayers.isEmpty()) deadPlayers.clear();
        if (overlayTicks++ % 5 != 0) return;
        settings();
        doColumns(true);
        boolean dbg = debug.get();
        List<NetworkPlayerInfo> infos = networkPlayers();
        if (status > 1) {
            Set<String> present = new HashSet<>();
            int threshold = encountersTimeout.getInt() * 60000;
            long now = System.currentTimeMillis();
            for (NetworkPlayerInfo info : infos) {
                String uuid = info.getGameProfile().getId().toString().replace("-", "");
                String displayName = displayName(info);
                String username = info.getGameProfile().getName();
                if (ignoredPlayers.containsKey(username.toLowerCase())) {
                    if (overlayPlayers.containsKey(uuid)) {
                        overlayPlayers.remove(uuid);
                        synchronized (currentPlayers) {
                            currentPlayers.remove(uuid);
                        }
                    }
                    continue;
                }
                present.add(uuid);
                String reason = botReason(info, displayName);
                if (!reason.isEmpty()) {
                    if (dbg) debugPrint("&7filtered &3" + username + "&7: " + reason);
                    continue;
                }
                if (overlayPlayers.containsKey(uuid)) {
                    updateTeamEntry(uuid, displayName);
                    if (shouldRefreshStats(uuid)) handlePlayerStats(uuid, currentLobby);
                    continue;
                }
                boolean real = uuid.charAt(12) == '4';
                String encounterKey = real ? uuid : username;
                List<Object[]> list = encounters.getOrDefault(encounterKey, new ArrayList<>());
                if (!list.isEmpty()) list.removeIf(en -> now - (long) en[1] > threshold);
                if (list.isEmpty() || !list.get(list.size() - 1)[0].equals(currentLobby)) list.add(new Object[]{ currentLobby, now });
                encounters.put(encounterKey, list);

                Map<String, Object> placeholder = new ConcurrentHashMap<>();
                placeholder.put(SEEN, seenColor(list.size()));
                placeholder.put(SEEN_V, list.size());
                placeholder.put(PLAYER, displayName);
                if (!real) {
                    // a nick (not a real account's UUID): no stats to get
                    placeholder.put(JOIN_V, (int) (now / 1000) * -1);
                    placeholder.put("nicked", true);
                    placeholder.put(STATE, "nicked");
                    placeholder.put(PLAYER, username);
                    placeholder.put(HEAD, new Head(uuid));
                    overlayPlayers.put(uuid, placeholder);
                    needsSort = needsLayout = true;
                    continue;
                }
                overlayPlayers.put(uuid, placeholder);
                updateTeamEntry(uuid, displayName);
                addPlaceholderStats(uuid, displayName, false);
                handlePlayerStats(uuid, currentLobby);
            }
            if (status == 2) {
                Iterator<Map.Entry<String, Map<String, Object>>> it = overlayPlayers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Map<String, Object>> en = it.next();
                    if (present.contains(en.getKey()) || en.getValue().containsKey("manual")) continue;
                    it.remove();
                    needsSort = needsLayout = true;
                }
            }
        }
        synchronized (currentPlayers) {
            if (status != 3) {
                Iterator<String> it = currentPlayers.iterator();
                while (it.hasNext()) {
                    if (overlayPlayers.containsKey(it.next())) continue;
                    it.remove();
                    needsSort = needsLayout = true;
                }
            }
            for (String uuid : overlayPlayers.keySet()) {
                if (currentPlayers.contains(uuid)) continue;
                currentPlayers.add(uuid.charAt(12) == '4' ? (ascending ? currentPlayers.size() : 0) : (ascending ? 0 : currentPlayers.size()), uuid);
                needsSort = needsLayout = true;
            }
        }
        if (needsSort) {
            sortOverlay();
            needsSort = false;
        }
        if (needsLayout) {
            doColumns(false);
            needsLayout = false;
        }
        if (dbg) {
            int size = overlayPlayers.size();
            if (System.currentTimeMillis() - lastDebugPrint > 2500L || lastDebugOverlayState != size) {
                lastDebugOverlayState = size;
                lastDebugPrint = System.currentTimeMillis();
                int current;
                synchronized (currentPlayers) {
                    current = currentPlayers.size();
                }
                debugPrint("&7status=&3" + status + "&7 lobby=&3" + currentLobby + "&7 net=&3" + infos.size() + "&7 overlay=&3" + size + "&7 current=&3" + current);
                List<String> sb = Hypixel.sidebar();
                if (sb == null) debugPrint("&7scoreboard=&cnull");
                else for (int i = 0; i < sb.size(); i++) debugPrint("&7sb[" + i + "]=&f" + strip(sb.get(i)));
            }
        }
    }

    /** The tab list, each player once (B4's world.getNetworkPlayers). */
    private static List<NetworkPlayerInfo> networkPlayers() {
        if (mc.getNetHandler() == null) return Collections.emptyList();
        return new ArrayList<>(new HashSet<>(mc.getNetHandler().getPlayerInfoMap()));
    }

    /** As B4 shows a tab-list player: you by your display name, others with their team's prefix and colour. */
    private static String displayName(NetworkPlayerInfo info) {
        if (mc.thePlayer != null && info.getGameProfile() == mc.thePlayer.getGameProfile()) return mc.thePlayer.getDisplayName().getUnformattedText();
        return ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), info.getGameProfile().getName());
    }

    private String botReason(NetworkPlayerInfo info, String displayName) {
        String uuid = info.getGameProfile().getId().toString();
        if (info.getResponseTime() < 0) return "negative ping";
        if (info.getGameProfile().getName().length() < 2) return "name too short";
        if (uuid.charAt(14) != '4' && uuid.charAt(14) != '1') return "uuid version " + uuid.charAt(14);
        if (overlayTicks < 80 && displayName.startsWith(S + "c") && !displayName.contains(" ")) return "startup red name";
        if (!showYourself && mc.thePlayer != null && uuid.equals(mc.thePlayer.getUniqueID().toString())) return "self hidden";
        if (status == 3 && !displayName.contains(" ")) return "missing team prefix in-game";
        return "";
    }

    private void addPlaceholderStats(String uuid, String username, boolean withName) {
        Map<String, Object> p = new ConcurrentHashMap<>();
        for (Column c : columns) {
            if (!c.enabled || c.key.equals(SEEN)) continue;
            if (c.key.equals(PLAYER)) {
                if (withName) p.put(PLAYER, S + "7" + username);
            } else if (c.key.equals(HEAD)) {
                if (showHeads) p.put(HEAD, new Head(uuid));
            } else if (c.key.equals(DEAD)) {
                p.put(DEAD, "");
            } else {
                p.put(c.key, S + "8...");
            }
        }
        p.put(JOIN_V, (int) (System.currentTimeMillis() / 1000) * -1);
        p.put(STATE, "loading");
        if (withName) overlayPlayers.put(uuid, p);
        else addToOverlay(uuid, p);
    }

    private void addToOverlay(String uuid, Map<String, Object> data) {
        Map<String, Object> existing = overlayPlayers.get(uuid);
        if (existing == null) return;
        existing.putAll(data);
        needsSort = needsLayout = true;
    }

    private void addToPlayers(String uuid) {
        synchronized (currentPlayers) {
            boolean real = uuid.charAt(12) == '4';
            if (ascending == real) currentPlayers.add(uuid);
            else currentPlayers.add(0, uuid);
            needsSort = needsLayout = true;
        }
    }

    private void clearMaps() {
        teams.clear();
        overlayPlayers.clear();
        lastDebugOverlayState = -1;
        needsSort = needsLayout = false;
        pendingStats.clear();
        statsFailures.clear();
        pendingWho.clear();
        deadPlayers.clear();
        synchronized (rateLock) {
            statsRequestTimes.clear();
            globalCooldownUntil = 0L;
            lastDebugRequestAt = 0L;
        }
        synchronized (currentPlayers) {
            currentPlayers.clear();
        }
    }

    private void debugPrint(String message) { print("&dDBG &7" + message); }

    // ------------------------------------------------------------------ Bed Wars state from the sidebar

    private void updateStatus() {
        String last = currentLobby;
        status = bedwarsStatus();
        if (!last.equals(currentLobby)) clearMaps();
    }

    /** -1 not Bed Wars, 0 limbo, 1 a Bed Wars lobby, 2 waiting for a game, 3 in a game. */
    private int bedwarsStatus() {
        List<String> sidebar = Hypixel.sidebar();
        if (sidebar == null) {
            return mc.theWorld != null && "The End".equals(mc.theWorld.provider.getDimensionName()) ? 0 : -1;
        }
        if (sidebar.size() < 7 || !strip(sidebar.get(0)).startsWith("BED WARS")) return -1;
        String lobby = lobbyId(sidebar);
        if (!lobby.isEmpty()) currentLobby = lobby;
        if (!lobby.isEmpty() && lobby.charAt(0) == 'L') return 1;
        if (inGameSidebar(sidebar)) return 3;
        for (String line : sidebar) {
            String s = strip(line).trim();
            if (s.equals("Waiting...") || s.startsWith("Starting in ")) return 2;
        }
        return -1;
    }

    private String lobbyId(List<String> sidebar) {
        for (String line : sidebar) {
            String s = strip(line).trim();
            if (s.isEmpty()) continue;
            int i = s.indexOf("  ");
            if (i == -1 || i + 2 >= s.length()) continue;
            String suffix = s.substring(i + 2).trim();
            if (suffix.isEmpty()) continue;
            String candidate = suffix.split(" ")[0];
            if (candidate.endsWith("]")) candidate = candidate.substring(0, candidate.length() - 1);
            if (candidate.length() >= 2 && Character.isLetter(candidate.charAt(0))) return candidate;
        }
        return currentLobby;
    }

    private static boolean inGameSidebar(List<String> sidebar) {
        int teamLines = 0;
        for (String line : sidebar) {
            String s = strip(line).trim();
            if (s.startsWith("R Red:") || s.startsWith("B Blue:") || s.startsWith("G Green:") || s.startsWith("Y Yellow:") || s.startsWith("A Aqua:")
                || s.startsWith("W White:") || s.startsWith("P Pink:") || s.startsWith("G Gray:") || s.contains(" YOU")) {
                if (++teamLines >= 2) return true;
            }
        }
        for (String line : sidebar) {
            String s = strip(line).trim();
            if (s.isEmpty()) continue;
            if (s.contains("Diamond") || s.contains("Emerald")) return true;
            if (s.startsWith("Kills:") || s.startsWith("Final Kills:") || s.startsWith("Beds Broken:")) return true;
            if (s.contains(" YOU") || s.endsWith("YOU")) return true;
        }
        return false;
    }

    private static int timeUntilStart() {
        List<String> sidebar = Hypixel.sidebar();
        if (sidebar == null || sidebar.size() < 7) return -1;
        for (String raw : sidebar) {
            String line = strip(raw).trim();
            if (!line.startsWith("Starting in ")) {
                if (line.equals("Waiting...")) return 20;
                continue;
            }
            String[] parts = line.split(" ");
            String last = parts[parts.length - 1];
            if (!last.endsWith("s")) return -1;
            try {
                return Integer.parseInt(last.substring(0, last.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ teams

    private boolean shouldUseTeamDisplay(String uuid) { return status == 3 && showTeamColors && teams.containsKey(uuid); }

    private String currentName(String uuid) {
        Map<String, Object> data = overlayPlayers.get(uuid);
        Object name = data == null ? null : data.get(PLAYER);
        return name == null ? "" : name.toString();
    }

    private String resolveName(String uuid, Map<String, Object> stats, String fallback) {
        if (shouldUseTeamDisplay(uuid)) {
            String team = teams.getOrDefault(uuid, fallback);
            if (!team.contains(" ") || showTeamPrefix) return team;
            return team.split(" ", 2)[1];
        }
        String key = showRanks.get() ? "usernamewithrank" : "usernamewithrankcolor";
        String name = stats != null ? String.valueOf(stats.getOrDefault(key, "")) : "";
        if (name.isEmpty()) {
            Map<String, Object> cached = statsCache.get(uuid);
            if (cached != null) name = String.valueOf(cached.getOrDefault(key, ""));
        }
        return name.isEmpty() ? fallback : name;
    }

    private void updateTeamEntry(String uuid, String displayName) {
        if (status != 3 || displayName == null || !displayName.contains(" ")) return;
        String previous = teams.get(uuid);
        boolean changed = previous == null || !previous.equals(displayName);
        teams.put(uuid, displayName);
        if (!overlayPlayers.containsKey(uuid)) return;
        String desired = resolveName(uuid, overlayPlayers.get(uuid), displayName);
        if (!desired.isEmpty() && !desired.equals(currentName(uuid))) {
            Map<String, Object> update = new HashMap<>();
            update.put(PLAYER, desired);
            addToOverlay(uuid, update);
        } else if (changed) {
            needsSort = needsLayout = true;
        }
    }

    private int compareTeams(String a, String b) {
        if (status != 3) return 0;
        String ta = teams.getOrDefault(a, ""), tb = teams.getOrDefault(b, "");
        if (ta.isEmpty() && tb.isEmpty()) return 0;
        if (ta.isEmpty() != tb.isEmpty()) return ta.isEmpty() ? 1 : -1;
        int pa = teamPriority(ta), pb = teamPriority(tb);
        if (pa != pb) return Integer.compare(pa, pb);
        return teamToken(ta).compareToIgnoreCase(teamToken(tb));
    }

    private static String teamToken(String displayName) {
        String s = strip(displayName);
        int i = s.indexOf(' ');
        return i == -1 ? s : s.substring(0, i);
    }

    private static int teamPriority(String displayName) {
        int i = displayName.indexOf(S);
        char code = i == -1 || i + 1 >= displayName.length() ? ' ' : Character.toLowerCase(displayName.charAt(i + 1));
        int p = "c9aebfd7".indexOf(code);
        if (p != -1) return p;
        String token = teamToken(displayName);
        String[] order = { "[R", "[B", "[G", "[Y", "[A", "[W", "[P" };
        for (int k = 0; k < order.length; k++) if (token.startsWith(order[k])) return k;
        return 7;
    }

    // ------------------------------------------------------------------ sorting

    private final Comparator<String> comparator = (a, b) -> {
        try {
            Map<String, Object> sa = overlayPlayers.get(a), sb = overlayPlayers.get(b);
            int team = compareTeams(a, b);
            if (team != 0) return team;
            boolean na = sa != null && Boolean.TRUE.equals(sa.get("nicked")), nb = sb != null && Boolean.TRUE.equals(sb.get("nicked"));
            if (!JOIN_V.equals(sortBy)) {
                if (na && !nb) return ascending ? -1 : 1;
                if (!na && nb) return ascending ? 1 : -1;
            }
            String va = sa != null && sa.get(sortBy) != null ? sa.get(sortBy).toString() : "-";
            String vb = sb != null && sb.get(sortBy) != null ? sb.get(sortBy).toString() : "-";
            va = va.replaceAll(S + ".", "");
            vb = vb.replaceAll(S + ".", "");
            boolean da = containsDigit(va), db = containsDigit(vb);
            if (!da && !db) return 0;
            if (!da) return ascending ? 1 : -1;
            if (!db) return ascending ? -1 : 1;
            double x = Double.parseDouble(va), y = Double.parseDouble(vb);
            int c = ascending ? Double.compare(y, x) : Double.compare(x, y);
            if (c != 0) return c;
            String ma = sa != null && sa.get(PLAYER) != null ? strip(sa.get(PLAYER).toString()) : a;
            String mb = sb != null && sb.get(PLAYER) != null ? strip(sb.get(PLAYER).toString()) : b;
            return ma.compareToIgnoreCase(mb);
        } catch (NumberFormatException e) {
            return ascending ? -1 : 1;
        }
    };

    private static boolean containsDigit(String s) {
        for (char c : s.toCharArray()) if (Character.isDigit(c)) return true;
        return false;
    }

    private void sortOverlay() {
        synchronized (currentPlayers) {
            try {
                currentPlayers.sort(comparator);
            } catch (IllegalArgumentException ignored) {
                // stats arriving while sorting can make the order inconsistent for a moment; the next sort fixes it
            }
        }
    }

    // ------------------------------------------------------------------ stats from Prism's Flashlight

    private boolean shouldRefreshStats(String uuid) {
        if (pendingStats.containsKey(uuid)) return false;
        Map<String, Object> cached = statsCache.get(uuid);
        return cached == null || System.currentTimeMillis() > cacheTime(cached);
    }

    private static long cacheTime(Map<String, Object> m) {
        Object t = m.get("cachetime");
        return t instanceof Number ? ((Number) t).longValue() : 0L;
    }

    private static long retryDelay(int failures, long base, long max) {
        int f = Math.max(0, Math.min(6, failures));
        return Math.min(max, base * (1L << f));
    }

    private void markStatsFailure(String uuid, String state) {
        int failures = statsFailures.getOrDefault(uuid, 0) + 1;
        statsFailures.put(uuid, failures);
        long delay = retryDelay(failures, RETRY_BASE_MS, 180000L);
        if ("rate".equals(state)) delay = Math.max(GLOBAL_COOLDOWN_MS, delay);
        Map<String, Object> failed = new ConcurrentHashMap<>();
        failed.put("error", true);
        failed.put(STATE, state);
        failed.put("cachetime", System.currentTimeMillis() + delay);
        statsCache.put(uuid, failed);
    }

    private void handlePlayerStats(String uuid, String lobby) {
        Map<String, Object> cached = statsCache.get(uuid);
        if (cached == null || System.currentTimeMillis() > cacheTime(cached)) {
            if (cached != null) statsCache.remove(uuid);
            if (pendingStats.putIfAbsent(uuid, true) != null) return;
            pool.execute(() -> {
                Map<String, Object> stats = new ConcurrentHashMap<>();
                try {
                    if (!awaitStatsSlot(lobby)) return;
                    Object[] r = get(prism() + "/v1/playerdata?uuid=" + uuid, 3500, prismHeaders());
                    int code = (int) r[1];
                    JsonObject json = (JsonObject) r[0];
                    if (code == 200) {
                        boolean success = "true".equalsIgnoreCase(str(json, "success", "false"));
                        String cause = str(json, "cause", "");
                        if (success) {
                            String fallback = currentName(uuid);
                            stats = parseStats(json, (String) r[2], uuid);
                            stats.putAll(fetchTags(uuid));
                            stats.putIfAbsent(HEAD, new Head(uuid));
                            String name = resolveName(uuid, stats, fallback);
                            if (!name.isEmpty()) stats.put(PLAYER, name);
                            statsFailures.remove(uuid);
                        } else if (cause.toLowerCase().contains("throttle")) {
                            globalCooldownUntil = Math.max(globalCooldownUntil, System.currentTimeMillis() + GLOBAL_COOLDOWN_MS);
                            markStatsFailure(uuid, "rate");
                        } else {
                            markStatsFailure(uuid, "error");
                        }
                    } else if (code == 429 || code == 503 || code == 504) {
                        globalCooldownUntil = Math.max(globalCooldownUntil, System.currentTimeMillis() + GLOBAL_COOLDOWN_MS);
                        markStatsFailure(uuid, "rate");
                    } else {
                        markStatsFailure(uuid, "error");
                    }
                } catch (Exception e) {
                    gale.Gale.LOG.debug("Jappylay: stats for {} failed", uuid, e);
                    markStatsFailure(uuid, "error");
                } finally {
                    pendingStats.remove(uuid);
                }
                Map<String, Object> update = !stats.isEmpty() ? stats : statsCache.get(uuid);
                if (update == null) update = stats;
                if (overlayPlayers.containsKey(uuid) && lobby.equals(currentLobby)) addToOverlay(uuid, update);
            });
        } else {
            Map<String, Object> copy = new ConcurrentHashMap<>(cached);
            copy.putIfAbsent(HEAD, new Head(uuid));
            String name = resolveName(uuid, copy, currentName(uuid));
            if (!name.isEmpty()) copy.put(PLAYER, name);
            long login = copy.get("login") instanceof Number ? ((Number) copy.get("login")).longValue() : 0L;
            long logout = copy.get("logout") instanceof Number ? ((Number) copy.get("logout")).longValue() : 0L;
            String session = S + "cAPI";
            if (login != 0) {
                long now = System.currentTimeMillis();
                if (login - logout > -2000 && now - login < 43200000) session = sessionColor(login, now, relativeTime(login, now));
                else session = S + "cOFFLINE";
            }
            copy.put(SESSION, session);
            addToOverlay(uuid, copy);
        }
    }

    /** Waits (on the worker) until the minute's request budget allows one more; false if the lobby changed meanwhile. */
    private boolean awaitStatsSlot(String lobby) throws InterruptedException {
        while (lobby.equals(currentLobby)) {
            long now = System.currentTimeMillis();
            boolean dbg = debug.get();
            long sleep = dbg ? 200L : 250L;
            synchronized (rateLock) {
                while (!statsRequestTimes.isEmpty() && now - statsRequestTimes.get(0) >= STATS_WINDOW_MS) statsRequestTimes.remove(0);
                if (now < globalCooldownUntil) {
                    sleep = Math.max(200L, globalCooldownUntil - now);
                } else if (dbg) {
                    long wait = DEBUG_SPACING_MS - (now - lastDebugRequestAt);
                    if (wait > 0) {
                        sleep = Math.max(sleep, wait);
                    } else if (statsRequestTimes.size() < STATS_LIMIT - STATS_BUFFER) {
                        statsRequestTimes.add(now);
                        lastDebugRequestAt = now;
                        return true;
                    }
                } else if (statsRequestTimes.size() < STATS_LIMIT - STATS_BUFFER) {
                    statsRequestTimes.add(now);
                    return true;
                }
            }
            Thread.sleep(sleep);
        }
        return false;
    }

    private Map<String, Object> parseStats(JsonObject root, String body, String uuid) {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        try {
            JsonObject player = obj(root, "player");
            if ("{\"success\":true,\"player\":null}".equals(body) || player == null) {
                stats.put("nicked", true);
                stats.put(STATE, "nicked");
                stats.put("cachetime", System.currentTimeMillis() + 300000);
                statsCache.put(uuid, stats);
                statsFailures.remove(uuid);
                return stats;
            }
            String username = str(player, "displayname", "");
            String rank = formattedRank(player);
            String rankColor = rank.length() >= 2 ? rank.substring(0, 2) : S + "7";
            stats.put("usernamewithrank", rank.length() == 2 ? rank + username : rank + " " + username);
            stats.put("usernamewithrankcolor", rankColor + username);

            JsonObject stats0 = obj(player, "stats");
            JsonObject bw = stats0 == null ? null : obj(stats0, "Bedwars");
            if (bw == null) bw = new JsonObject();

            int star = (int) Math.floor(expToStars((int) Double.parseDouble(str(bw, "Experience", "0"))));
            String fkMode = modePrefix(fkdrMode.index());
            double finalKills = Double.parseDouble(str(bw, fkMode.isEmpty() ? "final_kills_bedwars" : fkMode + "final_kills_bedwars", "0"));
            double finalDeaths = Double.parseDouble(str(bw, fkMode.isEmpty() ? "final_deaths_bedwars" : fkMode + "final_deaths_bedwars", "0"));
            double fkdr = finalDeaths == 0 ? finalKills : finalKills / finalDeaths < 10 ? round(finalKills / finalDeaths, 2) : round(finalKills / finalDeaths, 1);
            double index = star * Math.pow(fkdr, 2);

            long login = Long.parseLong(str(player, "lastLogin", "0"));
            long logout = Long.parseLong(str(player, "lastLogout", "0"));
            stats.put("login", login);
            stats.put("logout", logout);
            String session = S + "cAPI";
            if (login != 0) {
                if (login - logout > -2000) {
                    long now = System.currentTimeMillis();
                    session = sessionColor(login, now, relativeTime(login, now));
                } else {
                    session = S + "cOFFLINE";
                }
            }

            String wsMode = modePrefix(winstreakMode.index());
            boolean hidden = Integer.parseInt(str(bw, "games_played_bedwars_1", "0")) > 0 && str(bw, wsMode + "winstreak", "").isEmpty();
            int winstreak = hidden ? 0 : Integer.parseInt(str(bw, wsMode + "winstreak", "0"));

            stats.put(STAR, prestigeColor(star));
            stats.put(STAR_V, star);
            stats.put(FKDR, fkdrColor(formatDouble(fkdr)));
            stats.put(FKDR_V, fkdr);
            stats.put(WINSTREAK, winstreakColor(winstreak));
            stats.put(WINSTREAK_V, winstreak);
            stats.put(SESSION, session);
            stats.put(SESSION_V, login * -1);
            stats.put(INDEX_V, index);
            stats.put(TAGS, "");
            stats.put("cheating", "");
            stats.put("sniping", "");
            stats.put(STATE, "ready");
            long keep = winstreak > 50 ? 600000 : Math.max(300, Math.min(86400, 60 * (60 * ((int) finalDeaths / 120)))) * 1000L;
            stats.put("cachetime", System.currentTimeMillis() + keep);
            statsCache.put(uuid, stats);
            statsFailures.remove(uuid);
        } catch (Exception e) {
            gale.Gale.LOG.debug("Jappylay: parsing the stats of {} failed", uuid, e);
            stats.put("error", true);
            stats.put(STATE, "error");
        }
        return stats;
    }

    private Map<String, Object> fetchTags(String uuid) {
        Map<String, Object> tags = new ConcurrentHashMap<>();
        tags.put("cheating", "");
        tags.put("sniping", "");
        try {
            Map<String, String> headers = prismHeaders();
            String key = urchinKey.get().trim();
            if (!key.isEmpty()) headers.put("X-Urchin-Api-Key", key);
            Object[] r = get(prism() + "/v1/tags/" + uuid, 3000, headers);
            if ((int) r[1] != 200) return tags;
            JsonObject t = obj((JsonObject) r[0], "tags");
            if (t == null) return tags;
            String cheating = tag("C", str(t, "cheating", "none")), sniping = tag("S", str(t, "sniping", "none"));
            if (!cheating.isEmpty() && !sniping.isEmpty()) cheating += " ";
            tags.put("cheating", cheating);
            tags.put("sniping", sniping);
        } catch (Exception e) {
            gale.Gale.LOG.debug("Jappylay: tags for {} failed", uuid, e);
        }
        return tags;
    }

    private static String tag(String c, String severity) {
        if (severity == null) return "";
        switch (severity.toLowerCase()) {
            case "medium": return S + "6" + c;
            case "high": return S + "c" + c;
            default: return "";
        }
    }

    /** Your UUID without dashes, as Flashlight's X-User-Id (the script does the same). */
    private String prismUserId() {
        if (!prismUserId.isEmpty()) return prismUserId;
        if (mc.thePlayer != null) prismUserId = mc.thePlayer.getUniqueID().toString().replace("-", "");
        else prismUserId = "lazify_" + Long.toHexString(System.currentTimeMillis()) + "_" + Integer.toHexString(new Random().nextInt(Integer.MAX_VALUE));
        return prismUserId;
    }

    private Map<String, String> prismHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("X-User-Id", prismUserId());
        return h;
    }

    private static String modePrefix(int i) {
        switch (i) {
            case 1: return "eight_one_";
            case 2: return "eight_two_";
            case 3: return "four_three_";
            case 4: return "four_four_";
            case 5: return "two_four_";
            default: return "";
        }
    }

    private static double expToStars(int exp) {
        int levelBase = exp / 487000 * 100;
        int expMod = exp % 487000;
        int[][] levels = { { 7000, 4, 5000 }, { 3500, 3, 3500 }, { 1500, 2, 2000 }, { 500, 1, 1000 }, { 0, 0, 500 } };
        for (int[] lvl : levels) {
            if (expMod < lvl[0]) continue;
            return levelBase + lvl[1] + (double) (expMod - lvl[0]) / lvl[2];
        }
        return 0;
    }

    private static String rank(JsonObject player) {
        String prefix = str(player, "prefix", null);
        if (prefix != null) return prefix;
        String rank = str(player, "rank", null);
        if (rank != null) {
            if (rank.equals("GAME_MASTER")) return "GM";
            if (rank.equals("YOUTUBER")) return "YOUTUBE";
            if (!rank.equals("NORMAL")) return rank;
        }
        String pkg = str(player, "newPackageRank", str(player, "packageRank", null));
        if (pkg == null || pkg.equals("NONE")) return null;
        if (pkg.startsWith("MVP")) return str(player, "monthlyPackageRank", "").equals("SUPERSTAR") ? "MVP++" : pkg.length() == 3 ? pkg : "MVP+";
        if (pkg.startsWith("VIP")) return pkg.length() == 3 ? pkg : "VIP+";
        return null;
    }

    private static String formattedRank(JsonObject player) {
        String plus = S + "7";
        String rank = rank(player);
        if (rank == null) return plus;
        String[] names = { "BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY", "DARK_GRAY", "BLUE", "GREEN", "AQUA",
            "RED", "LIGHT_PURPLE", "YELLOW", "WHITE" };
        String plusColor = str(player, "rankPlusColor", "RED");
        for (int i = 0; i < names.length; i++) if (names[i].equals(plusColor)) plus = S + "0123456789abcdef".charAt(i);
        // Hypixel's own look; the script left out "[" (and VIP's green): "MVP+] Name"
        switch (rank) {
            case "VIP": return color("&a[VIP]");
            case "VIP+": return color("&a[VIP" + S + "6+" + S + "a]");
            case "MVP": return color("&b[MVP]");
            case "MVP+": return color("&b[MVP" + plus + "+" + S + "b]");
            case "MVP++": return color("&6[MVP" + plus + "++" + S + "6]");
            case "GM": return color("&2[GM]");
            case "YOUTUBE": return color("&c[" + S + "fYOUTUBE" + S + "c]");
            case "ADMIN": return color("&c[ADMIN]");
            default: return rank;
        }
    }

    private static String winstreakColor(int ws) {
        if (ws == 0) return "";
        char c = ws >= 1000 ? '5' : ws >= 500 ? 'd' : ws >= 300 ? '4' : ws >= 150 ? 'c' : ws >= 100 ? '6' : ws >= 75 ? 'e' : ws >= 50 ? '2' : ws >= 25 ? 'a' : '7';
        return S + c + ws;
    }

    private static String sessionColor(long login, long logout, String text) {
        long s = logout - login;
        char c = s > 21600000 ? '4' : s > 14400000 ? 'c' : s > 9000000 ? '6' : s > 7200000 ? 'e' : s > 1200000 ? 'a' : s > 300000 ? 'e' : s > 150000 ? 'c' : '4';
        return S + c + text;
    }

    private static String fkdrColor(String fkdr) {
        double f = Double.parseDouble(fkdr);
        char c = f > 1000 ? '5' : f > 100 ? '4' : f > 10 ? 'c' : f > 5 ? '6' : f > 2.4 ? 'e' : f > 1.4 ? 'f' : '7';
        return S + c + fkdr;
    }

    private static String formatDouble(double v) { return v % 1 == 0 ? String.valueOf((int) v) : String.valueOf(v); }

    private static String relativeTime(long from, long to) {
        long since = (to - from) / 1000L, r = since;
        long[] sizes = { 31557600L, 2629800L, 86400L, 3600L, 60L };
        String[] units = { "y", "mo", "d", "h", "m" };
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (int i = 0; i < sizes.length; i++) {
            long n = r / sizes[i];
            r %= sizes[i];
            if (n > 0 && added < 2) {
                sb.append(n).append(units[i]);
                added++;
            }
        }
        if (r > 0 && added < 2 || since == 0) sb.append(r).append("s");
        return sb.toString();
    }

    /** Bed Wars star colours, prestige by prestige (the script's table). */
    private static String prestigeColor(int number) {
        int prestige = number - number % 100;
        String n = String.format("%04d", number);
        String a = n.substring(0, 1), b = n.substring(1, 2), c = n.substring(2, 3), d = n.substring(3, 4);
        if (prestige >= 5000) return color("&4" + a + "&5" + b + "&9" + c + d);
        switch (prestige) {
            case 4900: return color("&a" + a + "&f" + b + c + "&a" + d);
            case 4800: return color("&5" + a + "&c" + b + "&6" + c + "&e" + d);
            case 4700: return color("&f" + a + "&4" + b + c + "&9" + d);
            case 4600: return color("&2" + a + "&b" + b + c + "&6" + d);
            case 4500: return color("&f" + a + "&b" + b + c + "&2" + d);
            case 4400: return color("&2" + a + "&a" + b + "&e" + c + "&6" + d);
            case 4300: return color("&0" + a + "&5" + b + c + "&5" + d);
            case 4200: return color("&1" + a + "&9" + b + "&3" + c + "&f" + d);
            case 4100: return color("&e" + a + "&6" + b + "&c" + c + "&d" + d);
            case 4000: return color("&5" + a + "&c" + b + c + "&6" + d);
            case 3900: return color("&c" + a + "&a" + b + c + "&3" + d);
            case 3800: return color("&1" + a + "&9" + b + c + "&5" + d);
            case 3700: return color("&4" + a + "&c" + b + c + "&b" + d);
            case 3600: return color("&a" + a + b + "&b" + c + "&9" + d);
            case 3500: return color("&c" + a + "&4" + b + c + "&2" + d);
            case 3400: return color("&2" + a + "&a" + b + c + "&5" + d);
            case 3300: return color("&9" + a + b + "&d" + c + "&c" + d);
            case 3200: return color("&c" + a + "&7" + b + c + "&4" + d);
            case 3100: return color("&9" + a + "&2" + b + c + "&6" + d);
            case 3000: return color("&e" + a + "&6" + b + "&c" + c + "&4" + d);
            case 2900: return color("&b" + a + "&3" + b + c + "&9" + d);
            case 2800: return color("&a" + a + "&2" + b + c + "&6" + d);
            case 2700: return color("&e" + a + "&f" + b + c + "&8" + d);
            case 2600: return color("&4" + a + "&c" + b + c + "&d" + d);
            case 2500: return color("&f" + a + "&a" + b + c + "&2" + d);
            case 2400: return color("&b" + a + "&f" + b + c + "&7" + d);
            case 2300: return color("&5" + a + "&d" + b + c + "&6" + d);
            case 2200: return color("&6" + a + "&f" + b + c + "&b" + d);
            case 2100: return color("&f" + a + "&e" + b + c + "&6" + d);
            case 2000: return color("&7" + a + "&f" + b + c + "&7" + d);
            case 1000: return color("&c" + a + "&6" + b + "&e" + c + "&a" + d);
            case 1900: case 900: return color("&5" + number);
            case 1800: case 800: return color("&9" + number);
            case 1700: case 700: return color("&d" + number);
            case 1600: return color("&c" + number);
            case 600: return color("&4" + number);
            case 1500: case 500: return color("&3" + number);
            case 1400: case 400: return color("&2" + number);
            case 1300: case 300: return color("&b" + number);
            case 1200: case 200: return color("&6" + number);
            case 1100: case 100: return color("&f" + number);
            default: return color("&7" + number);
        }
    }

    private static String seenColor(int n) {
        char c = n > 5 ? 'c' : n > 3 ? '6' : n > 1 ? 'e' : 'a';
        return S + c + n;
    }

    private static String retryCountdown(String prefix, char color, Map<String, Object> stats) {
        if (stats == null) return S + color + prefix;
        long remaining = cacheTime(stats) - System.currentTimeMillis();
        if (remaining <= 0L) return S + color + prefix;
        long sec = Math.max(1L, (remaining + 999L) / 1000L);
        return sec >= 60L ? S + color + prefix + sec / 60L + "m" + sec % 60L + "s" : S + color + prefix + sec + "s";
    }

    // ------------------------------------------------------------------ HTTP and JSON

    /** {JsonObject body (empty when none), Integer status, String raw body}. Worker thread. */
    private static Object[] get(String url, int timeout, Map<String, String> headers) {
        JsonObject json = new JsonObject();
        String body = "";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", UA);
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet())
                if (h.getKey() != null && h.getValue() != null && !h.getValue().isEmpty()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    JsonElement e = new JsonParser().parse(body);
                    if (e.isJsonObject()) json = e.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // not JSON: an empty object, as the script's Json did
            }
            return new Object[]{ json, code, body };
        } catch (Exception e) {
            return new Object[]{ json, 500, body };
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** A value as text (numbers as written), or the fallback when missing or not a plain value. */
    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    /** A name or UUID to {uuid without dashes, name}: Flashlight, then Mojang, then playerdb.co. Worker thread. */
    private String[] resolve(String player) {
        String[] r = resolveFlashlight(player);
        if (r[0].isEmpty()) r = resolveMojang(player);
        if (r[0].isEmpty()) r = resolvePlayerdb(player);
        return r;
    }

    private String[] resolveFlashlight(String player) {
        if (player == null || player.isEmpty()) return new String[]{ "", "" };
        if (player.length() == 32 && player.charAt(12) == '4' || player.length() == 36 && player.charAt(14) == '4')
            return new String[]{ player.replace("-", ""), player };
        Object[] r = get(prism() + "/v1/account/username/" + player, 3000, prismHeaders());
        if ((int) r[1] != 200) return new String[]{ "", "" };
        JsonObject d = (JsonObject) r[0];
        if (!"true".equalsIgnoreCase(str(d, "success", "false"))) return new String[]{ "", "" };
        String uuid = str(d, "uuid", "");
        return uuid.isEmpty() ? new String[]{ "", "" } : new String[]{ uuid.replace("-", ""), str(d, "username", "") };
    }

    private static String[] resolveMojang(String player) {
        boolean isUuid = player.length() == 32 && player.charAt(12) == '4' || player.length() == 36 && player.charAt(14) == '4';
        String url = isUuid ? "https://sessionserver.mojang.com/session/minecraft/profile/" + player : "https://api.mojang.com/users/profiles/minecraft/" + player;
        Object[] r = get(url, 3000, null);
        if ((int) r[1] != 200) return new String[]{ "", "" };
        JsonObject d = (JsonObject) r[0];
        return new String[]{ str(d, "id", ""), str(d, "name", "") };
    }

    private static String[] resolvePlayerdb(String player) {
        Object[] r = get("https://playerdb.co/api/player/minecraft/" + player, 3000, null);
        if ((int) r[1] != 200) return new String[]{ "", "" };
        JsonObject data = obj((JsonObject) r[0], "data");
        JsonObject p = data == null ? null : obj(data, "player");
        return new String[]{ str(p, "raw_id", ""), str(p, "username", "") };
    }

    // ------------------------------------------------------------------ chat: /who, final kills

    /** false hides the line (the answer to the overlay's own /who). */
    private boolean chat(String formatted) {
        String msg = strip(formatted);
        updateDead(msg);
        if (doWho && (msg.endsWith("!") && msg.contains("has joined") || msg.startsWith("You will respawn in"))) {
            doWho = false;
            Scheduler.later(10, () -> {
                if (mc.thePlayer != null && isActive() && status > 1 && timeUntilStart() > 5) mc.thePlayer.sendChatMessage("/who");
            });
            return true;
        }
        if (msg.startsWith("ONLINE: ")) {
            String[] players = msg.substring(8).split(", ");
            syncWho(players);
            if (JOIN_V.equals(sortBy)) {
                Map<String, Integer> order = new HashMap<>();
                for (int i = 0; i < players.length; i++) order.put(players[i], players.length - 1 - i);
                for (Map<String, Object> p : overlayPlayers.values()) {
                    Object name = p.get(PLAYER);
                    if (!(name instanceof String)) continue;
                    String n = strip((String) name);
                    if (n.contains(" ")) n = n.split(" ")[1];
                    Integer join = order.get(n);
                    if (join != null) p.put(JOIN_V, join);
                }
                sortOverlay();
            }
            if (!didWho) {
                didWho = true;
                return false;
            }
        }
        return true;
    }

    private void updateDead(String msg) {
        if (msg == null) return;
        String s = msg.trim();
        if (s.isEmpty() || s.startsWith("Party >")) return;
        if (s.endsWith("FINAL KILL!") || s.endsWith("FINAL KILL")) {
            String dead = leadingIgn(s);
            if (validIgn(dead)) deadPlayers.put(dead.toLowerCase(), true);
            return;
        }
        if (s.endsWith(" reconnected.") || s.endsWith(" reconnected")) {
            String back = leadingIgn(s);
            if (validIgn(back)) deadPlayers.remove(back.toLowerCase());
        }
    }

    private static final Set<String> NOT_NAMES = new HashSet<>(java.util.Arrays.asList("PARTY", "FINAL", "KILL", "R", "B", "G", "Y", "A", "W", "P",
        "VIP", "VIPPLUS", "MVP", "MVPPLUS", "MVPPLUSPLUS", "GM", "YOUTUBE"));

    private static String leadingIgn(String message) {
        String[] words = message.split(" ");
        for (int i = 0; i < words.length && i < 3; i++) {
            String c = sanitize(words[i]);
            if (c.isEmpty() || NOT_NAMES.contains(c.toUpperCase())) continue;
            if (validIgn(c)) return c;
        }
        return "";
    }

    private static String sanitize(String token) {
        if (token == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : token.toCharArray()) if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        return sb.toString();
    }

    private static boolean validIgn(String name) {
        if (name == null || name.isEmpty() || name.length() > 25) return false;
        for (char c : name.toCharArray()) if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        return true;
    }

    private static String overlayIgn(String overlayName) {
        if (overlayName == null || overlayName.isEmpty()) return "";
        String s = strip(overlayName).trim();
        if (s.isEmpty()) return "";
        if (s.contains(" ")) {
            String[] parts = s.split(" ");
            s = parts[parts.length - 1];
        }
        return sanitize(s);
    }

    private String uuidByName(String username) {
        for (Map.Entry<String, Map<String, Object>> e : overlayPlayers.entrySet()) {
            Object name = e.getValue().get(PLAYER);
            if (!(name instanceof String)) continue;
            String n = strip((String) name);
            if (n.contains(" ")) n = n.split(" ", 2)[1];
            if (n.equalsIgnoreCase(username)) return e.getKey();
        }
        return null;
    }

    private void syncWho(String[] players) {
        if (status <= 1) return;
        String lobby = currentLobby;
        for (int i = 0; i < players.length; i++) {
            String name = players[i] == null ? "" : players[i].trim();
            if (name.isEmpty() || ignoredPlayers.containsKey(name.toLowerCase())) continue;
            int join = players.length - 1 - i;
            String existing = uuidByName(name);
            if (existing != null) {
                Map<String, Object> data = new ConcurrentHashMap<>();
                data.put(JOIN_V, join);
                addToOverlay(existing, data);
                continue;
            }
            String key = name.toLowerCase();
            if (pendingWho.putIfAbsent(key, true) != null) continue;
            pool.execute(() -> {
                try {
                    if (!lobby.equals(currentLobby)) return;
                    String[] r = resolve(name);
                    if (r[0].isEmpty() || !lobby.equals(currentLobby)) return;
                    String uuid = r[0], resolved = r[1].isEmpty() ? name : r[1];
                    Scheduler.main(() -> {
                        if (!lobby.equals(currentLobby)) return;
                        synchronized (currentPlayers) {
                            if (!overlayPlayers.containsKey(uuid)) {
                                addPlaceholderStats(uuid, resolved, true);
                                addToPlayers(uuid);
                            }
                            Map<String, Object> data = new ConcurrentHashMap<>();
                            data.put(JOIN_V, join);
                            addToOverlay(uuid, data);
                        }
                        handlePlayerStats(uuid, currentLobby);
                    });
                } finally {
                    pendingWho.remove(key);
                }
            });
        }
    }

    // ------------------------------------------------------------------ /ov

    /** /ov, /ov sc|hide|clearhidden|reload. Game thread. */
    public void command(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        switch (args[0].toLowerCase()) {
            case "sc": {
                if (args.length < 2) {
                    print("&eInvalid syntax. Use &3/ov sc [username]&e.");
                    return;
                }
                String player = args[1];
                pool.execute(() -> {
                    String[] r = resolve(player);
                    Scheduler.main(() -> {
                        if (r[0].isEmpty()) {
                            print("&eFailed to convert &3" + player + "&e.");
                            return;
                        }
                        String uuid = r[0], name = r[1].isEmpty() ? player : r[1];
                        synchronized (currentPlayers) {
                            overlayPlayers.remove(uuid);
                            currentPlayers.remove(uuid);
                            addPlaceholderStats(uuid, name, true);
                            addToPlayers(uuid);
                            Map<String, Object> manual = new ConcurrentHashMap<>();
                            manual.put("manual", true);
                            addToOverlay(uuid, manual);
                        }
                        statsCache.remove(uuid);
                        handlePlayerStats(uuid, currentLobby);
                        print("&eAdded &3" + name + " &eto the overlay.");
                    });
                });
                return;
            }
            case "hide":
                if (args.length > 1) {
                    ignoredPlayers.put(args[1].toLowerCase(), "");
                    print("&3" + args[1] + "&e is now hidden.");
                } else {
                    print("&eInvalid syntax. Use &3/ov hide [username]&e.");
                }
                return;
            case "clearhidden": {
                int n = ignoredPlayers.size();
                ignoredPlayers.clear();
                print("&eCleared &3" + n + "&e player" + (n != 1 ? "s." : "."));
                return;
            }
            case "reload": {
                Map<String, Map<String, Object>> players = new HashMap<>(overlayPlayers);
                clearMaps();
                for (Map.Entry<String, Map<String, Object>> e : players.entrySet()) {
                    addPlaceholderStats(e.getKey(), String.valueOf(e.getValue().get(PLAYER)), true);
                    addToPlayers(e.getKey());
                    handlePlayerStats(e.getKey(), currentLobby);
                }
                overlayTicks = 5;
                print("&eReloaded &3" + players.size() + "&e player" + (players.size() != 1 ? "s" : "") + ".");
                return;
            }
            default:
                help();
        }
    }

    private static void help() {
        String title = " &eJappylay Overlay &7", footer = " &eMade by Pug &7";
        String[] lines = {
            "&3/ov clearhidden&e: Clears the hidden player list.",
            "&3/ov hide [username]&e: Hides player from the overlay.",
            "&3/ov reload&e: Reloads the players displayed on the overlay.",
            "&3/ov sc [username]&e: Manually adds a player to the overlay." };
        int max = 0;
        for (String l : lines) max = Math.max(max, width(l));
        int titleW = width(title), footerW = width(footer);
        max = Math.max(max, Math.max(titleW, footerW));
        int full = max + mc.fontRendererObj.getStringWidth("  ");
        String header = "&7" + pad('-', (full - titleW) / 2) + title + pad('-', (full - titleW) / 2) + ((full - titleW) % 2 != 0 ? "-" : "");
        print(header);
        for (String l : lines) print(pad(' ', (max - width(l)) / 2) + l);
        print("&7" + pad('-', (full - footerW) / 2) + footer + pad('-', (full - footerW) / 2) + ((full - footerW) % 2 != 0 ? "-" : ""));
    }

    private static int width(String s) { return mc.fontRendererObj.getStringWidth(s.replaceAll("&[0-9a-fk-or]", "")); }

    private static String pad(char c, int pixels) {
        int w = Math.max(1, mc.fontRendererObj.getStringWidth(String.valueOf(c)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pixels / w; i++) sb.append(c);
        return sb.toString();
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat) || overlayTicks < 5 || columns.isEmpty()) return;
        if (showOnlyOnTab && !mc.gameSettings.keyBindPlayerList.isKeyDown()) return;
        float radius = cornerRadius.getFloat();
        int opacity = backgroundOpacity.getInt();
        int panel = new Color(43, 45, 49, opacity).getRGB();
        int header = new Color(37, 37, 43, Math.min(255, opacity + 20)).getRGB();
        int shadow = new Color(0, 0, 0, Math.min(180, Math.max(0, opacity))).getRGB();
        int count;
        synchronized (currentPlayers) {
            count = currentPlayers.size();
        }
        if (useBloom) {
            GlowShader.get().startCapture();
            RoundRect.draw(startX + 1, startY + 1, endX - 1, endY - 1, radius, shadow);
            GlowShader.get().render(3, 2);
        }
        RoundRect.draw(startX, startY, endX, endY, radius, panel);
        hud.drawn();
        RoundRect.draw(startX, startY, endX, startY + lineHeight + offsetY / 2f, radius, header);
        if (count > 0) RenderUtil.rect(startX, startY + radius, endX, startY + lineHeight + offsetY / 2f, header);

        for (Column c : columns) {
            if (!c.enabled) continue;
            float x = c.position;
            if (!c.key.equals(PLAYER)) x += (c.maxWidth - c.width * textScale) / 2;
            text(c.header, x, startY + offsetY, columnTitles);
        }

        float y = startY + lineHeight + 5f * textScale;
        synchronized (currentPlayers) {
            Iterator<String> it = currentPlayers.iterator();
            while (it.hasNext()) {
                String uuid = it.next();
                Map<String, Object> stats = overlayPlayers.get(uuid);
                if (stats == null) continue;
                boolean nicked = Boolean.TRUE.equals(stats.get("nicked")), error = Boolean.TRUE.equals(stats.get("error"));
                String state = String.valueOf(stats.getOrDefault(STATE, ""));
                boolean loading = "loading".equals(state), rate = "rate".equals(state);
                for (Column c : columns) {
                    if (!c.enabled) continue;
                    Object value = stats.get(c.key);
                    String raw = String.valueOf(value);
                    float x = c.position;
                    if (nicked) {
                        if (c.key.equals(HEAD)) value = new Head(null);
                        else if (c.key.equals(DEAD)) value = "";
                        else if (!c.key.equals(PLAYER) && !c.key.equals(SEEN)) value = S + "eN";
                        else if (!shouldUseTeamDisplay(uuid) && c.key.equals(PLAYER)) value = S + "e" + raw.replaceAll(S + ".", "");
                    } else if (!c.key.equals(PLAYER) && !c.key.equals(SEEN) && !c.key.equals(HEAD) && !c.key.equals(DEAD)) {
                        boolean empty = value == null || raw.isEmpty() || raw.equals(S + "8...");
                        if (rate && empty) value = retryCountdown("API", 'c', stats);
                        else if (error && empty) value = retryCountdown("E", '4', stats);
                        else if (loading && (value == null || raw.isEmpty())) value = S + "8...";
                    }
                    switch (c.key) {
                        case PLAYER:
                            if (error && (value == null || raw.isEmpty() || raw.equals(S + "7-"))) value = S + "4E";
                            break;
                        case TAGS:
                            if (raw.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                for (String tag : TAG_KEYS) {
                                    Object t = stats.get(tag);
                                    if (t != null && t.toString().startsWith(S)) sb.append(t);
                                }
                                value = sb.length() > 0 ? sb.toString() : nicked ? S + "eN" : loading ? S + "8..." : null;
                            }
                            break;
                        case DEAD: {
                            String ign = overlayIgn(String.valueOf(stats.getOrDefault(PLAYER, "")));
                            value = !ign.isEmpty() && deadPlayers.containsKey(ign.toLowerCase()) ? S + "4DEAD" : "";
                            break;
                        }
                        case SEEN:
                            if (value == null || raw.isEmpty()) value = S + "a1";
                            break;
                        case HEAD:
                            drawHead(value instanceof Head ? (Head) value : new Head(null), x + (c.maxWidth - headsSize) / 2, y, headsSize);
                            continue;
                        default:
                    }
                    String t = value != null ? value.toString() : "";
                    if (!c.key.equals(PLAYER)) x += (c.maxWidth - mc.fontRendererObj.getStringWidth(t) * textScale) / 2f;
                    text(t, x, y, -1);
                }
                y += lineHeight;
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** B4's render.text: scaled, blended, with shadow. */
    private void text(String s, float x, float y, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(textScale, textScale, textScale);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.fontRendererObj.drawString(s, x / textScale, y / textScale, color, true);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ heads

    /** A player's face: from the tab list's skin when they are in it, else their avatar from mc-heads.net (as the script). */
    private static final class Head {
        final String uuid;

        Head(String uuid) { this.uuid = uuid; }

        @Override
        public String toString() { return ""; }
    }

    private static final Map<String, ResourceLocation> DOWNLOADED = new ConcurrentHashMap<>();
    private static final Set<String> READY = ConcurrentHashMap.newKeySet();

    private static void drawHead(Head head, float x, float y, float size) {
        NetworkPlayerInfo info = null;
        UUID id = null;
        if (head.uuid != null && head.uuid.length() == 32) {
            try {
                id = UUID.fromString(head.uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
                info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(id);
            } catch (IllegalArgumentException ignored) {
            }
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        if (info != null) {
            mc.getTextureManager().bindTexture(info.getLocationSkin());
            quad(x, y, size, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f);
            quad(x, y, size, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f);
        } else if (id != null && downloaded(head.uuid)) {
            mc.getTextureManager().bindTexture(DOWNLOADED.get(head.uuid));
            quad(x, y, size, 0, 0, 1, 1);
        } else {
            // Steve, the script's default head
            mc.getTextureManager().bindTexture(DefaultPlayerSkin.getDefaultSkinLegacy());
            quad(x, y, size, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f);
            quad(x, y, size, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f);
        }
        GlStateManager.disableBlend();
    }

    private static boolean downloaded(String uuid) {
        if (READY.contains(uuid)) return true;
        DOWNLOADED.computeIfAbsent(uuid, u -> {
            ResourceLocation loc = new ResourceLocation("galemod", "jappylay/heads/" + u);
            mc.getTextureManager().loadTexture(loc, new ThreadDownloadImageData(null, "https://mc-heads.net/avatar/" + u + "/8", null, new IImageBuffer() {
                @Override
                public BufferedImage parseUserSkin(BufferedImage image) { return image; }

                @Override
                public void skinAvailable() { READY.add(u); }
            }));
            return loc;
        });
        return READY.contains(uuid);
    }

    private static void quad(float x, float y, float size, float u0, float v0, float u1, float v1) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        w.pos(x, y + size, 0).tex(u0, v1).endVertex();
        w.pos(x + size, y + size, 0).tex(u1, v1).endVertex();
        w.pos(x + size, y, 0).tex(u1, v0).endVertex();
        w.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
    }

    // ------------------------------------------------------------------ for the self test

    public int overlayCount() { return overlayPlayers.size(); }

    public int status() { return status; }

    public String lobby() { return currentLobby; }

    public Map<String, Object> overlayEntry(String uuid) { return overlayPlayers.get(uuid); }

    public boolean isDead(String name) { return deadPlayers.containsKey(name.toLowerCase()); }

    public int shownRows() {
        synchronized (currentPlayers) {
            return currentPlayers.size();
        }
    }
}
