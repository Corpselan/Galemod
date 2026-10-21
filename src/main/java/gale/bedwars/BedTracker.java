package gale.bedwars;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.ChatEvent;
import gale.event.impl.EntityJoinEvent;
import gale.event.impl.ServerEvent;
import gale.event.impl.UpdateEvent;
import gale.util.ChatUtil;
import gale.util.Friends;
import gale.util.ReflectUtil;
import gale.util.ServerUtil;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel Bed Wars as your client sees it: whether a game is on, your team, where your own bed is and whether it is
 * gone. Read from the sidebar, chat and the blocks around your spawn only (ported from BetterIsland's tracker).
 * <p>Your bed is never guessed from colours: when the game starts (or you respawn without a known bed) the place you
 * spawn at is anchored, the blocks around it are searched a little each tick, and only exactly one bed there counts.
 * It is gone when the sidebar, the server's "BED DESTRUCTION" line or three block checks in a row say so.
 * <p>Runs by itself in every world; outside Bed Wars it only reads the sidebar four times a second. Game thread.
 */
public final class BedTracker {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SCAN_RADIUS = 25, SCAN_Y_RADIUS = 6, SCAN_EDGE = SCAN_RADIUS * 2 + 1;
    private static final int SCAN_TOTAL = SCAN_EDGE * SCAN_EDGE * (SCAN_Y_RADIUS * 2 + 1), SCAN_BUDGET = 2048;
    private static final Pattern YOU_ROW = Pattern.compile(".*\\byou\\b.*");
    private static final Pattern FINALS_ROW = Pattern.compile(".*:\\s*[0-9]+\\s+.*");
    private static final Pattern FINALS_COUNT = Pattern.compile(":\\s*(\\d+)(?:\\s|$)");
    private static final Pattern RESPAWN_IN = Pattern.compile("You will respawn in [0-9]+ seconds?!");
    // any wording ("was destroyed by", "was dismantled by"...): only the server writes lines starting like this
    private static final Pattern BED_DESTRUCTION = Pattern.compile(
        "^BED DESTRUCTION > (Your|Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Grey) Bed .*\\bby (?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16})[!.]?$",
        Pattern.CASE_INSENSITIVE);
    private static final Field TITLES_TIMER = ReflectUtil.field(GuiIngame.class, "titlesTimer", "field_175195_w");
    private static final Field TITLE = ReflectUtil.field(GuiIngame.class, "displayedTitle", "field_175201_x");
    private static final Field SUBTITLE = ReflectUtil.field(GuiIngame.class, "displayedSubTitle", "field_175200_y");

    /** Milliseconds between two batches of the spawn search (the island's "Scan interval"). */
    public static volatile long scanInterval = 50L;

    private static BlockPos partA, partB;
    private static Vec3 center, spawnAnchor, captureOrigin, respawnOrigin, lastLocalPosition;
    private static boolean destroyed, gameActive, board, waitingObserved, capturePending, captureNeedsTeleport, respawnPending, localWasDead;
    private static long respawnNotBefore, captureNotBefore, captureDeadline, scanTime;
    private static long lastBoardTime = -1, lastBedCheckTime = -1, lastRelocationTime = -1, lastTeamsRefresh = -1, lastRecoverAttempt = -1;
    private static int missingChecks, scanCursor = -1, scanAttempts, previousTicks = -1;
    private static String worldKey = "", ownTeam = "";
    private static char ownColor;
    private static final Map<String, String> TEAM_BY_NAME = new HashMap<>();
    private static final Map<Character, String> TEAM_STATES = new HashMap<>();
    private static final List<BlockPos> CAND_A = new ArrayList<>(), CAND_B = new ArrayList<>();
    private static final Queue<String> CHAT = new ConcurrentLinkedQueue<>();
    private static volatile boolean localJoinPending;
    private static int resets;
    private static long destroyedAt = -1;
    private static String destroyedDetail = "";
    // the bed last confirmed this game: taken back after a respawn without searching again
    private static BlockPos lastA, lastB;
    private static char lastColor;
    private static long lastDiagnostic = -1;

    private BedTracker() { }

    public static void install() {
        // before the modules' own UpdateEvent listeners, so they see this tick's state
        Gale.BUS.listen(UpdateEvent.class, Priority.HIGHEST + 50, e -> tick(), "bedwars");
        Gale.BUS.listen(ChatEvent.class, Priority.HIGHEST + 50, e -> {
            if (e.type != 2 && CHAT.size() < 64) CHAT.add(plain(e.text()));
        }, "bedwars");
        Gale.BUS.listen(EntityJoinEvent.class, Priority.MONITOR, e -> {
            if (e.entity instanceof EntityPlayerSP) localJoinPending = true;
        }, "bedwars");
        Gale.BUS.listen(ServerEvent.Disconnect.class, Priority.MONITOR, e -> reset(), "bedwars");
    }

    // ------------------------------------------------------------------ what others read

    /** A Bed Wars game is on (the sidebar shows the game with your team's row, not the waiting lobby). */
    public static boolean inGame() { return gameActive; }

    /** The sidebar is a Bed Wars one (also in the waiting lobby). */
    public static boolean onBoard() { return board; }

    /** Your team's colour code ('c' red, '9' blue...), 0 when not known. */
    public static char ownColor() { return ownColor; }

    /** The two blocks of your own bed, or null while it is not known (or gone). */
    public static BlockPos[] ownBed() { return partA == null ? null : new BlockPos[]{ partA, partB }; }

    /** The middle of your bed's top, or null. */
    public static Vec3 bedCenter() { return center; }

    /** Your bed is gone this game (you will not respawn). */
    public static boolean bedDestroyed() { return destroyed; }

    /** When your bed was last reported gone (System.currentTimeMillis), -1 for never. */
    public static long destroyedAt() { return destroyedAt; }

    /** How it was found gone: "By &lt;name&gt;" from the server's line, or "Respawns disabled". */
    public static String destroyedDetail() { return destroyedDetail; }

    /** Counts up whenever a new game, world or team starts: anything kept about the old bed is stale then. */
    public static int resets() { return resets; }

    /** Where you spawned when the search began, or null. */
    public static Vec3 spawnAnchor() { return spawnAnchor; }

    /** What the sidebar says about a team: "Bed alive", "Eliminated", "Finals: 2", or null. */
    public static String teamState(char color) { return TEAM_STATES.get(color); }

    /** The scoreboard team a player name is in, or null. */
    public static String teamOf(String name) { return name == null ? null : TEAM_BY_NAME.get(name.toLowerCase(Locale.ROOT)); }

    /** Watching without playing (vanilla spectators can fly and cannot build). */
    public static boolean spectating() {
        EntityPlayerSP p = mc.thePlayer;
        return p != null && p.capabilities.allowFlying && !p.capabilities.allowEdit;
    }

    /**
     * A player on another team: not you, not a friend, not in your scoreboard team, and with a known team colour that
     * is not yours. With detectTeammates every other player counts.
     */
    public static boolean isEnemy(EntityPlayer player, boolean detectTeammates) {
        EntityPlayerSP local = mc.thePlayer;
        if (player == null || local == null || player == local || player.isDead) return false;
        String name = player.getName();
        if (name == null || name.equalsIgnoreCase(local.getName()) || Friends.is(name)) return false;
        if (detectTeammates) return true;
        String a = teamOf(local.getName()), b = teamOf(name);
        if (a != null && a.equals(b)) return false;
        char color = playerColor(player);
        return ownColor != 0 && color != 0 && color != ownColor;
    }

    /** The colour code just before a player's name in the tab list (or above their head); 0 when there is none. */
    public static char playerColor(EntityPlayer player) {
        char color = 0;
        if (mc.getNetHandler() != null) {
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            if (info != null) color = colorBeforeName(mc.ingameGUI.getTabList().getPlayerName(info), player.getName());
        }
        if (color == 0) color = colorBeforeName(player.getDisplayName().getFormattedText(), player.getName());
        return color;
    }

    /** The team a sidebar row is about ("Red: ...", "红队: ..."), as a colour code; 0 for none. */
    public static char teamRowColor(String row) {
        String text = row.toLowerCase(Locale.ROOT);
        if (text.contains("red:") || text.contains("红队:")) return 'c';
        if (text.contains("blue:") || text.contains("蓝队:")) return '9';
        if (text.contains("green:") || text.contains("绿队:")) return 'a';
        if (text.contains("yellow:") || text.contains("黄队:")) return 'e';
        if (text.contains("aqua:") || text.contains("青队:")) return 'b';
        if (text.contains("white:") || text.contains("白队:")) return 'f';
        if (text.contains("pink:") || text.contains("粉队:")) return 'd';
        if (text.contains("gray:") || text.contains("grey:") || text.contains("灰队:")) return '7';
        return 0;
    }

    public static String teamName(char color) {
        switch (color) {
            case 'c': return "Red";
            case '9': return "Blue";
            case 'a': return "Green";
            case 'e': return "Yellow";
            case 'b': return "Aqua";
            case 'f': return "White";
            case 'd': return "Pink";
            case '7': return "Gray";
            default: return "Unknown";
        }
    }

    /** Text without colour codes, full-width colons as ':' and trimmed. */
    public static String plain(String value) {
        if (value == null) return "";
        return ChatUtil.strip(value).replace('：', ':').trim();
    }

    public static boolean isBed(BlockPos pos) { return mc.theWorld != null && mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed; }

    // ------------------------------------------------------------------ every tick

    private static void tick() {
        try {
            update(System.currentTimeMillis());
        } catch (Exception e) {
            diagnostic("update failed: " + e);
        }
    }

    private static void update(long now) {
        EntityPlayerSP local = mc.thePlayer;
        if (local == null || mc.theWorld == null) return;
        String key = ServerUtil.address() + "|" + mc.theWorld.provider.getDimensionId();
        int ticks = local.ticksExisted;
        boolean joined = localJoinPending || (previousTicks > 10 && ticks + 5 < previousTicks);
        boolean changed = !worldKey.isEmpty() && !worldKey.equals(key);
        if (changed || (joined && !respawnPending && !localWasDead)) reset();
        localJoinPending = false;
        worldKey = key;
        previousTicks = ticks;
        Vec3 position = local.getPositionVector();
        if (lastLocalPosition != null && position.squareDistanceTo(lastLocalPosition) > 16 * 16) lastRelocationTime = now;
        lastLocalPosition = position;
        if (lastBoardTime < 0 || now - lastBoardTime >= 250) {
            lastBoardTime = now;
            refreshGame(local, now);
        }
        processChat(local, now);
        boolean dead = local.isDead || local.getHealth() <= 0f;
        if (dead && gameActive && !destroyed) respawnPending = true;
        boolean respawnTeleport = respawnOrigin != null && position.squareDistanceTo(respawnOrigin) > 16 * 16 && now >= respawnNotBefore;
        // the title is only read while a respawn is awaited
        if (respawnPending && !dead && !spectating() && (joined || localWasDead || respawnTeleport || respawnedTitle())) {
            respawnPending = false;
            // a confirmed bed stays valid across a respawn on the same team
            if (center == null && !capturePending) scheduleSpawn(now, 0);
        }
        localWasDead = dead;
        if (!gameActive || destroyed || spectating()) return;
        boolean captureAllowed = !captureNeedsTeleport || (captureOrigin != null && position.squareDistanceTo(captureOrigin) > 16 * 16);
        if (capturePending && now > captureDeadline) {
            capturePending = false;
            diagnostic("spawn was not confirmed; waiting for the next respawn");
        }
        if (capturePending && captureAllowed && now >= captureNotBefore && !dead && !ownTeam.isEmpty()) {
            spawnAnchor = position;
            capturePending = false;
            scanTime = now + 500;
            diagnostic("spawn anchored at " + position + "; searching for the bed");
        }
        scanBatch(now);
        checkCachedBed(local, now);
        recover(local, now);
    }

    private static void reset() {
        clearBed();
        resets++;
        gameActive = board = waitingObserved = capturePending = captureNeedsTeleport = respawnPending = localWasDead = destroyed = false;
        captureOrigin = respawnOrigin = lastLocalPosition = null;
        respawnNotBefore = 0;
        ownTeam = "";
        ownColor = 0;
        TEAM_BY_NAME.clear();
        TEAM_STATES.clear();
        lastTeamsRefresh = lastRelocationTime = lastBoardTime = lastBedCheckTime = lastRecoverAttempt = -1;
        previousTicks = -1;
        worldKey = "";
        localJoinPending = false;
        CHAT.clear();
        destroyedAt = -1;
        destroyedDetail = "";
        lastA = lastB = null;
        lastColor = 0;
    }

    private static void clearBed() {
        partA = partB = null;
        center = spawnAnchor = null;
        scanCursor = -1;
        CAND_A.clear();
        CAND_B.clear();
        scanTime = 0;
        scanAttempts = 0;
        missingChecks = 0;
    }

    private static void scheduleSpawn(long now, long delay) {
        clearBed();
        capturePending = true;
        captureNotBefore = now + delay;
        captureDeadline = now + delay + 12000;
        captureNeedsTeleport = false;
        captureOrigin = null;
    }

    private static void scheduleTeamSpawn(EntityPlayerSP local, long now, long delay) {
        Vec3 p = local.getPositionVector();
        scheduleSpawn(now, delay);
        // the sidebar can change after the server has already moved us
        captureNeedsTeleport = lastRelocationTime < 0 || now - lastRelocationTime > 1000;
        captureOrigin = p;
    }

    private static void destroyBed(long now, String detail) {
        clearBed();
        destroyed = true;
        capturePending = false;
        respawnPending = false;
        destroyedAt = now;
        destroyedDetail = detail;
        lastA = lastB = null;
        Gale.LOG.info("[BedTracker] own bed destroyed ({})", detail);
    }

    // ------------------------------------------------------------------ the sidebar

    private static void refreshGame(EntityPlayerSP local, long now) {
        List<String> lines = sidebar();
        boolean isBoard = false, waiting = false;
        String ownRow = "";
        for (String text : lines) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("bed wars") || lower.contains("bedwars") || text.contains("起床战争")) isBoard = true;
            if (lower.contains("waiting") || lower.contains("starting in") || text.contains("等待中") || text.contains("即将开始")) waiting = true;
            if (text.contains(":") && (YOU_ROW.matcher(lower).matches() || text.contains("你") || text.contains("您"))) ownRow = text;
        }
        boolean active = isBoard && !waiting && !ownRow.isEmpty();
        board = isBoard;
        if (isBoard && waiting) waitingObserved = true;
        if (!isBoard || waiting) {
            if (gameActive || center != null || spawnAnchor != null) {
                clearBed();
                resets++;
                capturePending = respawnPending = destroyed = false;
                ownTeam = "";
            }
            gameActive = false;
            return;
        }
        // a sidebar that is not understood never gives a bed
        if (!active) {
            gameActive = false;
            return;
        }
        // Hypixel keeps many scoreboard teams: their members are read again once a second
        if (lastTeamsRefresh < 0 || now - lastTeamsRefresh >= 1000 || TEAM_BY_NAME.isEmpty()) {
            lastTeamsRefresh = now;
            refreshTeams();
        }
        char color = teamRowColor(ownRow);
        if (color == 0) color = playerColor(local);
        String team = color == 0 ? "" : String.valueOf(color);
        if (!ownTeam.isEmpty() && !team.isEmpty() && !ownTeam.equals(team)) {
            destroyed = false;
            scheduleTeamSpawn(local, now, 0);
            diagnostic("team changed; waiting for the new spawn");
        }
        if (!gameActive && waitingObserved && !capturePending) {
            destroyed = false;
            scheduleSpawn(now, 0);
        }
        gameActive = true;
        waitingObserved = false;
        ownTeam = team;
        ownColor = color;
        refreshTeamStates(lines);
        // a number instead of a check mark: the team has players left but no bed
        boolean alive = ownRow.contains("✔") || ownRow.contains("✓") || ownRow.toLowerCase(Locale.ROOT).contains("alive");
        boolean gone = ownRow.contains("✗") || ownRow.contains("✘") || ownRow.toLowerCase(Locale.ROOT).contains("destroyed")
            || FINALS_ROW.matcher(ownRow).matches() || ownRow.contains("已破坏");
        if (gone && !alive && !destroyed) destroyBed(now, "Respawns disabled");
    }

    /** The sidebar's title and up to 15 lines, top to bottom, without colours (as B4 reads it). */
    private static List<String> sidebar() {
        List<String> out = new ArrayList<>();
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScoreObjective o = sb == null ? null : sb.getObjectiveInDisplaySlot(1);
        if (o == null) return out;
        out.add(plain(o.getDisplayName()));
        List<Score> scores = new ArrayList<>(sb.getSortedScores(o));
        int n = 0;
        for (int i = scores.size() - 1; i >= 0 && n < 15; i--) {
            String name = scores.get(i).getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            out.add(plain(ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(name), name)));
            n++;
        }
        return out;
    }

    private static void refreshTeams() {
        TEAM_BY_NAME.clear();
        for (ScorePlayerTeam t : mc.theWorld.getScoreboard().getTeams())
            for (String member : t.getMembershipCollection()) TEAM_BY_NAME.put(plain(member).toLowerCase(Locale.ROOT), t.getRegisteredName());
    }

    private static void refreshTeamStates(List<String> lines) {
        TEAM_STATES.clear();
        for (String text : lines) {
            char c = teamRowColor(text);
            if (c == 0) continue;
            String state = "Unknown";
            if (text.contains("✓") || text.contains("✔")) state = "Bed alive";
            else if (text.contains("✗") || text.contains("✘")) state = "Eliminated";
            else {
                Matcher m = FINALS_COUNT.matcher(text);
                if (m.find()) state = "Finals: " + m.group(1);
            }
            TEAM_STATES.put(c, state);
        }
    }

    private static char colorBeforeName(String formatted, String name) {
        if (formatted == null || name == null || name.isEmpty()) return 0;
        int end = formatted.indexOf(name);
        if (end < 0) return 0;
        char color = 0;
        for (int i = 0; i + 1 < end; i++) {
            if (formatted.charAt(i) != ChatUtil.SECTION) continue;
            char code = Character.toLowerCase(formatted.charAt(++i));
            if ("0123456789abcdef".indexOf(code) >= 0) color = code;
            else if (code == 'r') color = 0;
        }
        return color;
    }

    private static boolean respawnedTitle() {
        if (ReflectUtil.getInt(TITLES_TIMER, mc.ingameGUI, 0) <= 0) return false;
        String title = ReflectUtil.get(TITLE, mc.ingameGUI), subtitle = ReflectUtil.get(SUBTITLE, mc.ingameGUI);
        return plain(title).equalsIgnoreCase("RESPAWNED!") || plain(subtitle).equalsIgnoreCase("RESPAWNED!");
    }

    // ------------------------------------------------------------------ chat

    private static void processChat(EntityPlayerSP local, long now) {
        String text;
        while ((text = CHAT.poll()) != null) {
            processBedMessage(text, now);
            if (!board) continue;
            if (text.equals("The game starts in 1 second!") || text.equals("游戏将在1秒后开始！")) {
                destroyed = false;
                resets++;
                scheduleSpawn(now, 1250);
            } else if (!text.contains(":") && text.contains("Protect your bed and destroy the enemy beds.")) {
                destroyed = false;
                resets++;
                scheduleSpawn(now, 0);
            } else if (gameActive && !destroyed && text.startsWith("BED DESTRUCTION > Your Bed")) {
                destroyBed(now, "Respawns disabled");
            } else if (gameActive && text.equals("You have respawned!")) {
                if (!destroyed && center == null) scheduleSpawn(now, 0);
                respawnPending = false;
            } else if (gameActive && RESPAWN_IN.matcher(text).matches()) {
                if (!destroyed) {
                    respawnPending = true;
                    respawnOrigin = local.getPositionVector();
                    respawnNotBefore = now + Long.parseLong(text.replaceAll("[^0-9]", "")) * 1000L;
                }
            } else if (gameActive && text.startsWith("Your team swapped and you are now:")) {
                destroyed = false;
                scheduleTeamSpawn(local, now, 0);
            }
        }
    }

    // "BED DESTRUCTION > Your Bed was destroyed by Name!": who broke it (a player quoting it in chat has a prefix)
    private static void processBedMessage(String text, long now) {
        if (!gameActive || !board || text.length() < 15 || !text.regionMatches(true, 0, "BED DESTRUCTION", 0, 15)) return;
        Matcher m = BED_DESTRUCTION.matcher(text);
        if (!m.matches()) return;
        boolean own = m.group(1).equalsIgnoreCase("Your") || (ownColor != 0 && teamRowColor(m.group(1) + ":") == ownColor);
        if (!own) return;
        String detail = "By " + m.group(2);
        if (!destroyed) destroyBed(now, detail);
        else {
            destroyedDetail = detail;
            destroyedAt = now;
        }
    }

    // ------------------------------------------------------------------ finding the bed

    private static void scanBatch(long now) {
        if (spawnAnchor == null || center != null || now < scanTime) return;
        if (scanCursor < 0) {
            scanCursor = 0;
            CAND_A.clear();
            CAND_B.clear();
        }
        int ox = (int) Math.floor(spawnAnchor.xCoord), oy = (int) Math.floor(spawnAnchor.yCoord), oz = (int) Math.floor(spawnAnchor.zCoord);
        long started = System.nanoTime();
        int reads = 0;
        while (scanCursor < SCAN_TOTAL && reads < SCAN_BUDGET - 4) {
            int index = scanCursor++;
            int dx = index % SCAN_EDGE - SCAN_RADIUS;
            int dz = (index / SCAN_EDGE) % SCAN_EDGE - SCAN_RADIUS;
            int dy = index / (SCAN_EDGE * SCAN_EDGE) - SCAN_Y_RADIUS;
            if (dx * dx + dz * dz > SCAN_RADIUS * SCAN_RADIUS) continue;
            reads++;
            BlockPos p = new BlockPos(ox + dx, oy + dy, oz + dz);
            if (isBed(p)) {
                reads += 4;
                addCandidate(p);
            }
            // at most 1.5 ms of a tick; the clock is looked at every 32 reads
            if (CAND_A.size() > 1 || (reads & 31) == 0 && System.nanoTime() - started >= 1_500_000L) break;
        }
        scanTime = now + scanInterval;
        if (CAND_A.size() > 1) scanCursor = SCAN_TOTAL;
        if (scanCursor < SCAN_TOTAL) return;
        scanCursor = -1;
        scanAttempts++;
        if (CAND_A.size() == 1) {
            BlockPos a = CAND_A.get(0), b = CAND_B.get(0);
            if (isBed(a) && isBed(b)) {
                setBed(a, b);
                Gale.LOG.info("[BedTracker] own bed at {} / {}", a, b);
                CAND_A.clear();
                CAND_B.clear();
                return;
            }
        }
        // search again at the same spawn, never around wherever you are now
        scanTime = now + Math.min(30000L, 2000L * Math.min(scanAttempts, 15));
        diagnostic(CAND_A.size() > 1 ? "more than one bed near the spawn; not taking any" : "no bed near the spawn; searching again later");
        CAND_A.clear();
        CAND_B.clear();
    }

    private static void addCandidate(BlockPos p) {
        BlockPos other = null;
        int neighbours = 0;
        for (BlockPos n : new BlockPos[]{ p.east(), p.west(), p.south(), p.north() }) {
            if (isBed(n)) {
                other = n;
                neighbours++;
            }
        }
        if (neighbours != 1) return;
        BlockPos first = p, second = other;
        if (first.getX() > second.getX() || (first.getX() == second.getX() && first.getZ() > second.getZ())) {
            first = other;
            second = p;
        }
        for (BlockPos known : CAND_A) if (known.equals(first)) return;
        CAND_A.add(first);
        CAND_B.add(second);
    }

    private static void setBed(BlockPos a, BlockPos b) {
        partA = a;
        partB = b;
        center = new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, a.getY() + 0.28125, (a.getZ() + b.getZ()) / 2.0 + 0.5);
        missingChecks = 0;
        lastA = a;
        lastB = b;
        lastColor = ownColor;
    }

    private static void checkCachedBed(EntityPlayerSP local, long now) {
        if (center == null || (lastBedCheckTime >= 0 && now - lastBedCheckTime < 500)) return;
        lastBedCheckTime = now;
        if (local.getPositionVector().squareDistanceTo(center) > 32 * 32) return;
        if (isBed(partA) || isBed(partB)) {
            missingChecks = 0;
            return;
        }
        // air in a chunk that is not loaded says nothing: the blocks under the bed must be there
        boolean support = !mc.theWorld.isAirBlock(partA.down()) && !mc.theWorld.isAirBlock(partB.down());
        missingChecks = support ? missingChecks + 1 : 0;
        if (missingChecks >= 3) destroyBed(now, "Respawns disabled");
    }

    // after a respawn the bed confirmed earlier this game is taken back, if it is still there next to the new spawn
    private static void recover(EntityPlayerSP local, long now) {
        if (center != null || capturePending || destroyed || ownColor == 0 || lastA == null || local.isDead || spectating()) return;
        if (lastRecoverAttempt >= 0 && now - lastRecoverAttempt < 1000) return;
        lastRecoverAttempt = now;
        if (lastColor != ownColor || spawnAnchor == null || !isBed(lastA) || !isBed(lastB)) return;
        Vec3 c = new Vec3((lastA.getX() + lastB.getX()) / 2.0 + 0.5, lastA.getY() + 0.28125, (lastA.getZ() + lastB.getZ()) / 2.0 + 0.5);
        if (local.getPositionVector().squareDistanceTo(c) > 25 * 25 || spawnAnchor.squareDistanceTo(c) > 25 * 25) return;
        setBed(lastA, lastB);
    }

    private static void diagnostic(String text) {
        long now = System.currentTimeMillis();
        if (lastDiagnostic >= 0 && now - lastDiagnostic < 5000) return;
        lastDiagnostic = now;
        Gale.LOG.debug("[BedTracker] {}", text);
    }
}
