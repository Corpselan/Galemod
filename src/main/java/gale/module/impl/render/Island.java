package gale.module.impl.render;

import gale.Gale;
import gale.bedwars.BedTracker;
import gale.bridge.External;
import gale.core.BlockBreaks;
import gale.event.impl.PacketEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.TextSetting;
import gale.util.ChatUtil;
import gale.util.Friends;
import gale.util.Mc;
import gale.util.Projection;
import gale.util.ReflectUtil;
import gale.util.RenderUtil;
import gale.util.RoundRect;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The dynamic island (BetterIsland, ported from the B4 script): a capsule at the top of the screen with your name and
 * frame rate that turns into a card for what matters now, most important first:
 * <ol>
 * <li>someone is digging at your bed: who, which block and how far, from the server's crack (or estimated)</li>
 * <li>bed alert: enemies near your bed, or a bed event (your bed destroyed, its defense changed)</li>
 * <li>scaffold: blocks left and speed</li>
 * <li>breaking a bed: progress (B4's BedAura through GaleLink, or your own hand)</li>
 * <li>a module switched on or off (Gale's, and B4's through GaleLink)</li>
 * </ol>
 * Your team and bed come from {@link BedTracker}.
 */
public final class Island extends Module {
    private static final int COLOR_BG_BASE = 0x141414, COLOR_TEXT = 0xFFFFFFFF, COLOR_ACCENT = 0xFFAA00FF, COLOR_GRAY = 0xFF9E9E9E;
    private static final int COLOR_FPS_OK = 0xFF2ECC71, COLOR_FPS_MID = 0xFFF1C40F, COLOR_FPS_LOW = 0xFFE74C3C;
    private static final int COLOR_SWITCH_ON = 0xFF4CAF50, COLOR_SWITCH_OFF = 0xFF616161, COLOR_SPEED = 0xFF6FB1E0, COLOR_SPEED_TEXT = 0xFF2ECC71;
    private static final int COLOR_CARD_ICON_BG = 0x40FFFFFF, COLOR_ALERT_RED = 0xFFFF4444;
    private static final String[] THEMES = { "Raven", "Rainbow", "Red", "Green", "Blue", "Yellow", "Pink", "Cyan", "White", "Custom" };
    private static final float ANIMATION_MS = 360f;
    // glow rings for 1..5 layers: {grow by, alpha}
    private static final float[][][] GLOW_TABLES = {
        { { 3f, 0.30f } },
        { { 7f, 0.12f }, { 2f, 0.26f } },
        { { 9f, 0.08f }, { 4.5f, 0.16f }, { 1.5f, 0.27f } },
        { { 10f, 0.06f }, { 6f, 0.12f }, { 3f, 0.19f }, { 1f, 0.28f } },
        { { 10f, 0.05f }, { 7f, 0.09f }, { 4.5f, 0.14f }, { 2.5f, 0.20f }, { 1f, 0.28f } }
    };
    private static final String LOGO_URL = "https://avatars.githubusercontent.com/u/88325435?s=280&v=4";
    private static final ResourceLocation LOGO = new ResourceLocation("galemod", "island/logo");
    private static final Field CURRENT_BLOCK = ReflectUtil.field(PlayerControllerMP.class, "currentBlock", "field_178895_c");
    private static final Field BLOCK_DAMAGE = ReflectUtil.field(PlayerControllerMP.class, "curBlockDamageMP", "field_78770_f");

    // ------------------------------------------------------------------ settings

    private final NumberSetting size = number("Size", 1.0, 0.6, 2.0, 0.1).describe("整体大小");
    private final NumberSetting xOffset = number("X offset", 0, -400, 400, 1).suffix("px").describe("左右挪");
    private final NumberSetting yOffset = number("Y offset", 0, -100, 200, 1).suffix("px").describe("上下挪");
    private final NumberSetting bgOpacity = number("Background", 72, 0, 100, 1).suffix("%").describe("底色不透明度");
    private final NumberSetting glow = number("Glow", 55, 0, 100, 1).suffix("%").describe("外圈光晕");
    private final NumberSetting glowLayers = number("Glow layers", 3, 1, 5, 1).describe("光晕画几圈（越多越柔，也越费）");
    private final BoolSetting shadow = bool("Text shadow", true);
    private final BoolSetting fpsColor = bool("FPS color", true).describe("帧数按高低变绿、黄、红");
    private final TextSetting title = text("Title", "Ravenb4").describe("胶囊里的名字");
    private final BoolSetting logo = bool("Logo", true).describe("胶囊左边的 Raven 图标（第一次要联网下载，关掉就显示首字母）");
    private final BoolSetting toggleCard = bool("Toggle card", true).describe("模块开关时变成卡片：Gale 的模块，和 GaleLink 报来的 B4 模块");
    private final NumberSetting displayTime = number("Display time", 1600, 500, 5000, 100).suffix("ms").describe("开关卡片留多久");
    private final BoolSetting scaffold = bool("Scaffold counter", true).describe("搭路时显示剩下多少方块和速度");
    private final TextSetting scaffoldModules = text("Scaffold modules", "Scaffold").describe("哪些模块开着算在搭路（逗号分隔；B4 的要开 GaleLink）");
    private final BoolSetting bedProgressCard = bool("Bed progress", true).describe("拆床进度：B4 的 BedAura（要开 GaleLink），或你自己手挖床");
    private final BoolSetting bedAlert = bool("Bed alert", true).describe("起床战争里有敌人靠近你的床时变成警报卡片");
    private final BoolSetting tracker = bool("Bed tracker", true).describe("看你的床周围：警报、谁在挖、防御变化。关掉这些都停");
    private final ModeSetting alertMode = mode("Alert mode", "Live", "Live", "Instant").describe("Live：一直跟着最近的人；Instant：有人进范围时提示一阵");
    private final NumberSetting instantDuration = number("Instant duration", 2500, 500, 10000, 100).suffix("ms");
    private final NumberSetting detectRange = number("Detect range", 50, 1, 100, 1).suffix(" blocks").describe("离你的床多远算靠近");
    private final NumberSetting scanInterval = number("Scan interval", 50, 50, 500, 25).suffix("ms").describe("开局在出生点附近找床时，两批之间隔多久");
    private final BoolSetting sound = bool("Alert sound", true).describe("有人靠近、开始挖床、床的事件时响一声");
    private final NumberSetting soundCooldown = number("Sound cooldown", 2000, 0, 30000, 250).suffix("ms");
    private final BoolSetting teammates = bool("Detect teammates", false).describe("队友靠近也报");
    private final NumberSetting alertHold = number("Alert hold", 600, 0, 5000, 100).suffix("ms").describe("人走了警报还留多久");
    private final BoolSetting overlay = bool("Defense overlay", false).describe("床上方显示每层防御的方块和最快的直线路线");
    private final BoolSetting changeAlerts = bool("Change alerts", true).describe("床的防御被拆或被补时提示");
    private final BoolSetting exactMining = bool("Exact mining", true).describe("谁在挖你的床：用服务器发来的挖方块进度（离床 30 格内收得到）");
    private final BoolSetting miningEstimate = bool("Mining estimate", false).describe("收不到进度时按挥手、工具和方块估算（不准，默认关）");
    private final NumberSetting defenseRadius = number("Defense radius", 4, 1, 6, 1).suffix(" blocks").describe("床周围多大算防御");
    private final NumberSetting eventDuration = number("Event duration", 3500, 1000, 10000, 250).suffix("ms").describe("床被拆、防御变化这类提示留多久");
    private final ModeSetting barTheme = mode("Bed bar", "Raven", THEMES).describe("拆床进度条的颜色");
    private final ColorSetting barColor = color("Bed bar color", 0xFFFF5555);
    private final ModeSetting cardTheme = mode("Card bar", "White", THEMES).describe("搭路卡片进度条的颜色");
    private final ColorSetting cardColor = color("Card bar color", 0xFFFFFFFF);
    private final NumberSetting cardBarOpacity = number("Card bar opacity", 100, 0, 100, 1).suffix("%");
    private final NumberSetting cardBarBg = number("Card bar bg", 15, 0, 100, 1).suffix("%");
    private final NumberSetting bedBarOpacity = number("Bed bar opacity", 100, 0, 100, 1).suffix("%");
    private final NumberSetting bedBarBg = number("Bed bar bg", 15, 0, 100, 1).suffix("%");

    // ------------------------------------------------------------------ state

    private float currentWidth = -1f, currentHeight = -1f;
    private float scaffoldAlpha, bedAlpha, notifAlpha, alertAlpha, digAlpha, digShown;
    private long lastFrameNanos = -1L;
    // where the island was last drawn, for the HUD editor (open the chat to move or resize it)
    private float hudX, hudY, hudW, hudH;
    private final gale.hud.HudElement hud = new gale.hud.HudElement("island", "Island", this) {
        @Override
        public float[] bounds(float sw, float sh) {
            if (drawnRecently()) return new float[]{ hudX, hudY, hudW, hudH };
            float s = size.getFloat(), w = 150f * s, h = 26f * s;
            return new float[]{ (sw - w) / 2f + xOffset.getFloat(), 10f + yOffset.getFloat(), w, h };
        }

        @Override
        public void moveTo(float x, float y, float sw, float sh) {
            float[] b = bounds(sw, sh);
            xOffset.set(Math.max(-400, Math.min(400, Math.round(x + b[2] / 2f - sw / 2f))));
            yOffset.set(Math.max(-100, Math.min(200, Math.round(y - 10f))));
        }

        @Override
        public float scale() { return size.getFloat(); }

        @Override
        public void scaleTo(float s, float sw, float sh) { size.set(Math.max(0.6, Math.min(2.0, Math.round(s * 10f) / 10.0))); }

        @Override
        public float minScale() { return 0.6f; }

        @Override
        public float maxScale() { return 2f; }

        @Override
        public void reset() {
            xOffset.reset();
            yOffset.reset();
            size.reset();
        }
    };

    // module toggles
    private final Map<Module, Boolean> galeStates = new HashMap<>();
    private Set<String> b4On = new HashSet<>();
    private boolean b4Known, togglesKnown;
    private long lastToggleTime = -1L, toggleSequence;
    private boolean lastToggleState = true;
    private String notifPart1 = " has been ", notifPart2 = "Enabled!", notifLine2 = " has been Enabled!";
    private long switchSequence = -1L, switchStartTime = -1L;
    private float switchPosition, switchFrom, switchTo;

    // scaffold
    private boolean bridgeActive, hasLastSample;
    private int currentBlockCount, initialBlockCount, scaffoldTextCount = -1;
    private double lastSampleX, lastSampleZ;
    private long lastSpeedTime;
    private float blockSpeed, scaffoldTextSpeed = -1f;
    private String scaffoldSpeedText = "0.00", scaffoldPart2Left = "0 blocks left - ", scaffoldLine2 = "0 blocks left - 0.00 block/s";
    private final Map<Item, Boolean> placeable = new HashMap<>();

    // breaking a bed
    private float bedProgress;
    private boolean showBedProgress;
    private int bedTextPercent = -1;
    private String bedLine2Right = "0%", bedLine2Full = "Break Progress: 0%";

    // bed alert
    private long lastAlertTime = -1L, lastAlertSoundTime = -1L, instantAlertStart = -1L, instantAlertDuration = 2500L;
    private String alertPlayerName = "", alertPlayerId = "", alertEquipment = "";
    private int alertDistance, alertThreatCount, alertDisplayThreatCount, bedAlertMode;
    private double alertExactDistance;
    private NetworkPlayerInfo alertInfo;
    private final Set<String> nearbyPlayers = new HashSet<>(), instantParticipants = new HashSet<>();
    private final Map<String, Object[]> colorCache = new HashMap<>();

    // bed events and the defense
    private BedRecord own;
    private final List<BlockPos> bedChanges = new ArrayList<>();
    private volatile boolean defenseRefreshPending;
    private volatile long lastDigPacketTime = -1L;
    private long nextDefenseSample, lastGlobalRefresh = -1L, bedEventTime = -1L;
    private int sampledRadius = 4, bedEventPriority, seenResets = -1;
    private long seenDestroyedAt = -1L;
    private String bedEventTitle = "", bedEventDetail = "";
    private String cardBedTitle = "Bed Alert!", cardBedDetail = "", cardBedExtra = "";
    private int cardBedColor = COLOR_ALERT_RED;
    private boolean cardBedEvent;
    private final Map<String, ItemStack> defenseIcons = new HashMap<>();

    // digging: who is digging at your bed now, exact or estimated. The server moves the crack on in 10% steps; the bar
    // fills between steps at the pace the last steps took.
    private final Map<String, MiningState> miningStates = new HashMap<>();
    private String miningDetail = "";
    private float miningProgress;
    private String digId = "", digKey = "", digName = "", digBlock = "";
    private int digStage = -1;
    private long digStageStart = -1L, digStageMs = 500L, digSeenAt = -1L, digSoundAt = -1L;
    private boolean digExact;
    private NetworkPlayerInfo digInfo;

    private static volatile boolean logoReady;
    private static boolean logoRequested;
    private long lastDiagnostic = -1L;

    public Island() {
        super("Island", Category.RENDER, "灵动岛：顶部胶囊，有事时变成卡片：床被挖、床警报、搭路、拆床、模块开关（BetterIsland 的移植）");
        complements("BetterIsland");
        gale.hud.HudLayout.register(hud);
        hud.showWhen(() -> !isDeferred());
        glowLayers.visibleWhen(() -> glow.get() > 0);
        displayTime.visibleWhen(toggleCard::get);
        scaffoldModules.visibleWhen(scaffold::get);
        instantDuration.visibleWhen(() -> alertMode.is("Instant"));
        soundCooldown.visibleWhen(sound::get);
        barColor.visibleWhen(() -> barTheme.is("Custom"));
        cardColor.visibleWhen(() -> cardTheme.is("Custom"));
        on(UpdateEvent.class, e -> tick());
        on(Render2DEvent.class, this::render);
        on(PacketEvent.Receive.class, this::packet);
    }

    @Override
    protected void onEnable() { resetAll(); }

    @Override
    protected void onDisable() { resetAll(); }

    @Override
    public String getInfo() {
        if (!BedTracker.inGame()) return null;
        if (BedTracker.bedDestroyed()) return "床没了";
        return BedTracker.ownBed() == null ? "在找床" : "看着床";
    }

    private void resetAll() {
        clearThreats();
        resetBedExtras();
        seenResets = BedTracker.resets();
        seenDestroyedAt = BedTracker.destroyedAt();
        galeStates.clear();
        b4On = new HashSet<>();
        b4Known = togglesKnown = false;
        lastToggleTime = -1L;
        toggleSequence = 0L;
        switchSequence = switchStartTime = -1L;
        switchPosition = switchFrom = switchTo = 0f;
        currentWidth = currentHeight = -1f;
        scaffoldAlpha = bedAlpha = notifAlpha = alertAlpha = digAlpha = digShown = 0f;
        lastFrameNanos = -1L;
        hasLastSample = false;
        blockSpeed = 0f;
        initialBlockCount = 0;
        bridgeActive = false;
        bedProgress = 0f;
        showBedProgress = false;
        lastAlertSoundTime = -1L;
        colorCache.clear();
        placeable.clear();
        alertInfo = null;
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        long now = System.currentTimeMillis();
        try {
            updateTracker(now);
        } catch (Exception e) {
            clearThreats();
            diagnostic("tracker update: " + e);
        }
        try {
            updateHudData(now);
        } catch (Exception e) {
            diagnostic("HUD update: " + e);
        }
    }

    // what changes at most once a tick is gathered here, not every frame
    private void updateHudData(long now) {
        updateToggles(now);
        bridgeActive = scaffold.get() && isBridgeActive();
        if (bridgeActive) {
            updateBridgeSpeed(now);
            currentBlockCount = countPlaceableBlocks();
            if (initialBlockCount == 0 || currentBlockCount > initialBlockCount) initialBlockCount = currentBlockCount;
        } else {
            blockSpeed = 0f;
            hasLastSample = false;
            currentBlockCount = 0;
            initialBlockCount = 0;
        }
        if (currentBlockCount != scaffoldTextCount || blockSpeed != scaffoldTextSpeed) {
            scaffoldTextCount = currentBlockCount;
            scaffoldTextSpeed = blockSpeed;
            scaffoldSpeedText = String.format(Locale.ROOT, "%.2f", blockSpeed);
            scaffoldPart2Left = currentBlockCount + " blocks left - ";
            scaffoldLine2 = scaffoldPart2Left + scaffoldSpeedText + " block/s";
        }
        if (bedProgressCard.get()) updateBedProgress();
        else showBedProgress = false;
        selectBedCard(now);
    }

    // Gale's modules straight away; B4's as GaleLink reports them (every few ticks). A report that stopped coming
    // changes nothing.
    private void updateToggles(long now) {
        for (Module m : Gale.modules().all()) {
            if (m == this || m.isAlwaysOn()) continue;
            Boolean old = galeStates.put(m, m.isEnabled());
            if (togglesKnown && old != null && old != m.isEnabled()) toggled(m.getName(), m.isEnabled(), now);
        }
        togglesKnown = true;
        if (!External.clients().contains("b4")) return;
        Set<String> on = new HashSet<>(External.enabled("b4"));
        if (b4Known) {
            for (String n : on) if (!b4On.contains(n)) toggled(n, true, now);
            for (String n : b4On) if (!on.contains(n)) toggled(n, false, now);
        }
        b4On = on;
        b4Known = true;
    }

    private void toggled(String name, boolean on, long now) {
        if (!toggleCard.get()) return;
        lastToggleTime = now;
        lastToggleState = on;
        notifPart1 = name + " has been ";
        notifPart2 = on ? "Enabled!" : "Disabled!";
        notifLine2 = notifPart1 + notifPart2;
        toggleSequence++;
    }

    private boolean isBridgeActive() {
        for (String name : scaffoldModules.get().split(",")) {
            String n = name.trim();
            if (n.isEmpty()) continue;
            Module m = Gale.modules().get(n);
            if (m != null && m.isEnabled() || External.anyEnabled(n)) return true;
        }
        return false;
    }

    private void updateBridgeSpeed(long now) {
        EntityPlayerSP p = mc.thePlayer;
        if (!hasLastSample) {
            lastSampleX = p.posX;
            lastSampleZ = p.posZ;
            lastSpeedTime = now;
            hasLastSample = true;
            return;
        }
        long elapsed = now - lastSpeedTime;
        if (elapsed < 100) return;
        double bps = Math.hypot(p.posX - lastSampleX, p.posZ - lastSampleZ) / (elapsed / 1000.0);
        if (bps < 30.0) blockSpeed = blockSpeed * 0.6f + (float) bps * 0.4f;
        lastSampleX = p.posX;
        lastSampleZ = p.posZ;
        lastSpeedTime = now;
    }

    private int countPlaceableBlocks() {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s == null || !(s.getItem() instanceof ItemBlock) || s.stackSize <= 0) continue;
            Boolean ok = placeable.get(s.getItem());
            if (ok == null) {
                ok = isPlaceableBlock(itemName(s));
                placeable.put(s.getItem(), ok);
            }
            if (ok) total += s.stackSize;
        }
        return total;
    }

    private static boolean isPlaceableBlock(String name) {
        String[] bad = { "snow_layer", "torch", "sign", "door", "ladder", "button", "lever", "pressure_plate", "rail", "web", "sapling",
            "flower", "mushroom", "cactus", "vine", "tallgrass", "deadbush", "waterlily", "carpet", "banner", "skull", "pot", "bed", "chest",
            "furnace", "crafting_table", "anvil", "brewing_stand", "enchanting_table", "jukebox", "noteblock", "dispenser", "dropper",
            "hopper", "piston", "tnt", "slab", "stairs", "fence", "pane", "iron_bars", "cobblestone_wall", "trapdoor" };
        for (String b : bad) if (name.contains(b)) return false;
        return true;
    }

    // BedAura (Gale's port, or B4's through GaleLink), or your own hand on a bed block
    private void updateBedProgress() {
        float p = gale.b4port.B4Values.bedAuraProgress()[0];
        PlayerControllerMP c = mc.playerController;
        if (c != null && c.getIsHittingBlock()) {
            BlockPos at = ReflectUtil.get(CURRENT_BLOCK, c);
            Float damage = ReflectUtil.get(BLOCK_DAMAGE, c);
            if (at != null && damage != null && BedTracker.isBed(at)) p = Math.max(p, damage);
        }
        bedProgress = Math.max(0f, Math.min(1f, p));
        showBedProgress = bedProgress > 0.01f;
    }

    // ------------------------------------------------------------------ the bed

    private void updateTracker(long now) {
        BedTracker.scanInterval = scanInterval.getInt();
        if (BedTracker.resets() != seenResets) {
            seenResets = BedTracker.resets();
            resetBedExtras();
        }
        updateAlertMode(alertMode.index(), now);
        if (defenseRadius.getInt() != sampledRadius) {
            // a new radius needs a fresh sample
            if (own != null) {
                own.sampled = -1L;
                own.names = null;
                own.blockCount = 0;
            }
            nextDefenseSample = 0L;
            sampledRadius = defenseRadius.getInt();
        }
        if (BedTracker.destroyedAt() != seenDestroyedAt) {
            seenDestroyedAt = BedTracker.destroyedAt();
            if (seenDestroyedAt >= 0) {
                if (own != null) {
                    own.destroyed = true;
                    own.available = false;
                    own.state = "Destroyed";
                }
                bedEvent("Your bed destroyed", BedTracker.destroyedDetail(), 3, now);
            }
        }
        EntityPlayerSP local = mc.thePlayer;
        if (!BedTracker.inGame() || BedTracker.bedDestroyed() || BedTracker.spectating() || !tracker.get()) {
            clearThreats();
            clearDig();
            return;
        }
        updateBedMap(local, now);
        if (BedTracker.bedCenter() != null && bedAlert.get()) {
            trackPlayers(local, now);
            updateMining(local, now);
        } else {
            clearThreats();
            clearDig();
        }
        if (digSeenAt >= 0L && now - digSeenAt > 400L) clearDig();
    }

    private void clearThreats() {
        miningStates.clear();
        miningDetail = "";
        miningProgress = 0f;
        resetAlertModes();
        nearbyPlayers.clear();
        lastAlertTime = -1L;
        alertThreatCount = 0;
        // the last text stays until the card has faded out
    }

    private void resetBedExtras() {
        own = null;
        miningStates.clear();
        synchronized (bedChanges) {
            bedChanges.clear();
            defenseRefreshPending = false;
        }
        nextDefenseSample = 0L;
        bedEventTime = -1L;
        bedEventPriority = 0;
        lastDigPacketTime = -1L;
        bedEventTitle = bedEventDetail = alertEquipment = miningDetail = "";
        miningProgress = 0f;
        clearDig();
        cardBedTitle = "Bed Alert!";
        cardBedDetail = cardBedExtra = "";
        cardBedEvent = false;
    }

    private void resetAlertModes() {
        instantAlertStart = -1L;
        instantParticipants.clear();
        alertDisplayThreatCount = 0;
    }

    private void updateAlertMode(int mode, long now) {
        mode = mode == 1 ? 1 : 0;
        if (mode == bedAlertMode) return;
        boolean wasLive = bedAlertMode == 0 && wantsBedAlert(now);
        bedAlertMode = mode;
        instantAlertStart = -1L;
        instantParticipants.clear();
        // a live alert becomes one finite window, without a new sound; who is near is never forgotten
        if (mode == 1 && wasLive && !nearbyPlayers.isEmpty()) {
            instantAlertStart = now;
            instantAlertDuration = instantDuration.getInt();
            instantParticipants.addAll(nearbyPlayers);
        }
    }

    private void trackPlayers(EntityPlayerSP local, long now) {
        Vec3 bed = BedTracker.bedCenter();
        Set<String> present = new HashSet<>(), entered = new HashSet<>();
        Map<String, Double> distances = new HashMap<>();
        Map<String, EntityPlayer> entities = new HashMap<>();
        Map<String, NetworkPlayerInfo> infos = new HashMap<>();
        String nearestId = "", nearestName = "";
        double nearestSquared = Double.MAX_VALUE, range = detectRange.get() * detectRange.get();
        int threats = 0;
        String localGroup = BedTracker.teamOf(local.getName());
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == local || mc.getNetHandler() == null) continue;
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            if (info == null) continue;
            String id = player.getUniqueID().toString();
            if (player.isDead || player.getHealth() <= 0f) continue;
            String name = player.getName();
            if (name == null || name.equalsIgnoreCase(local.getName()) || !validName(name)) continue;
            // distance first: the team checks are the costly part
            double squared = player.getPositionVector().squareDistanceTo(bed);
            if (squared > range) continue;
            if (!teammates.get()) {
                if (Friends.is(name)) continue;
                String group = BedTracker.teamOf(name);
                if (localGroup != null && localGroup.equals(group)) continue;
                char color = cachedPlayerColor(id, player, now), mine = BedTracker.ownColor();
                if (color == 0 || mine == 0 || color == mine) continue;
            }
            if (!present.add(id)) continue;
            threats++;
            if (!nearbyPlayers.contains(id)) entered.add(id);
            distances.put(id, squared);
            entities.put(id, player);
            infos.put(id, info);
            if (squared < nearestSquared || (squared == nearestSquared && id.compareTo(nearestId) < 0)) {
                nearestSquared = squared;
                nearestName = name;
                nearestId = id;
            }
        }
        // mode changes never clear who is physically near
        nearbyPlayers.clear();
        nearbyPlayers.addAll(present);
        alertThreatCount = threats;
        if (threats > 0) lastAlertTime = now;
        if (bedAlertMode == 0) {
            if (threats > 0) {
                alertPlayerId = nearestId;
                alertPlayerName = nearestName;
                alertExactDistance = Math.sqrt(nearestSquared);
                alertDistance = (int) Math.ceil(alertExactDistance);
                alertDisplayThreatCount = threats;
                updateAlertEquipment(entities.get(nearestId));
            }
        } else {
            if (!entered.isEmpty()) {
                boolean visible = instantAlertStart >= 0L && now - instantAlertStart < instantAlertDuration;
                if (!visible) {
                    instantAlertStart = now;
                    instantAlertDuration = instantDuration.getInt();
                    instantParticipants.clear();
                }
                // more entrants share the window; they never make it longer
                instantParticipants.addAll(entered);
            }
            if (instantAlertStart >= 0L && now - instantAlertStart < instantAlertDuration) {
                String selected = "";
                double best = Double.MAX_VALUE;
                for (String id : instantParticipants) {
                    Double squared = distances.get(id);
                    if (squared != null && (squared < best || (squared == best && id.compareTo(selected) < 0))) {
                        selected = id;
                        best = squared;
                    }
                }
                if (!selected.isEmpty()) {
                    alertPlayerId = selected;
                    alertPlayerName = entities.get(selected).getName();
                    alertExactDistance = Math.sqrt(best);
                    alertDistance = (int) Math.ceil(alertExactDistance);
                    updateAlertEquipment(entities.get(selected));
                }
                // someone who left: the last snapshot stays until the window ends
                alertDisplayThreatCount = instantParticipants.size();
            }
        }
        NetworkPlayerInfo targetInfo = infos.get(alertPlayerId);
        if (targetInfo != null) alertInfo = targetInfo;
        else if (alertInfo != null && !alertInfo.getGameProfile().getId().toString().equals(alertPlayerId)) alertInfo = null;
        if (!entered.isEmpty() && sound.get() && (lastAlertSoundTime < 0L || now - lastAlertSoundTime >= soundCooldown.get())) {
            lastAlertSoundTime = now;
            playSound(1f);
        }
    }

    // [A-Za-z0-9_]{1,16} without a regex per player per tick
    private static boolean validName(String name) {
        int n = name.length();
        if (n < 1 || n > 16) return false;
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    // a player's team colour, looked up once a second
    private char cachedPlayerColor(String id, EntityPlayer player, long now) {
        Object[] cached = colorCache.get(id);
        if (cached != null && now - (Long) cached[1] < 1000L) return (Character) cached[0];
        char color = BedTracker.playerColor(player);
        if (colorCache.size() > 128) colorCache.clear();
        colorCache.put(id, new Object[]{ color, now });
        return color;
    }

    private boolean wantsBedAlert(long now) {
        if (!tracker.get() || !bedAlert.get() || !BedTracker.inGame() || BedTracker.bedDestroyed() || BedTracker.bedCenter() == null) return false;
        if (bedAlertMode == 1) return instantAlertStart >= 0L && now - instantAlertStart >= 0L && now - instantAlertStart < instantAlertDuration;
        return lastAlertTime >= 0L && (alertThreatCount > 0 || now - lastAlertTime < alertHold.get());
    }

    private int alertDangerColor() {
        if (alertExactDistance <= 5.0) return 0xFFB71C1C;
        if (alertExactDistance <= 15.0) return 0xFFFF4444;
        if (alertExactDistance <= 30.0) return 0xFFFF9800;
        return 0xFFFFDD44;
    }

    private void updateAlertEquipment(EntityPlayer player) {
        alertEquipment = "";
        if (player == null) return;
        ItemStack held = player.getHeldItem();
        String item = held == null ? "Empty hand" : BedTracker.plain(held.getDisplayName());
        if (item.isEmpty() && held != null) item = materialLabel(itemName(held));
        ItemStack legs = player.inventory.armorInventory[1];
        String armor = legs == null ? "" : itemName(legs).replace("_leggings", " armor").replace('_', ' ');
        alertEquipment = item + (armor.isEmpty() ? "" : " / " + armor);
    }

    private void bedEvent(String title, String detail, int priority, long now) {
        if (!bedAlert.get() || !tracker.get()) return;
        if (bedEventTime >= 0L && now - bedEventTime < eventDuration.get() && priority < bedEventPriority) return;
        bedEventTitle = title;
        bedEventDetail = detail;
        bedEventPriority = priority;
        bedEventTime = now;
        if (sound.get() && (lastAlertSoundTime < 0L || now - lastAlertSoundTime >= soundCooldown.get())) {
            lastAlertSoundTime = now;
            playSound(1f);
        }
    }

    private boolean wantsAnyBedCard(long now) {
        return tracker.get() && bedAlert.get() && BedTracker.inGame()
            && (wantsBedAlert(now) || (bedEventTime >= 0L && now - bedEventTime < eventDuration.get()));
    }

    private void selectBedCard(long now) {
        boolean event = bedEventTime >= 0L && now - bedEventTime < eventDuration.get();
        if (!wantsAnyBedCard(now)) return; // the text stays while the card fades out
        cardBedEvent = event && (bedEventPriority >= 3 || !wantsBedAlert(now));
        if (cardBedEvent) {
            cardBedTitle = bedEventTitle;
            cardBedDetail = bedEventDetail;
            cardBedExtra = "";
            cardBedColor = bedEventPriority >= 3 ? COLOR_ALERT_RED : 0xFFFFAA33;
        } else {
            cardBedTitle = "Bed Alert!" + (alertDisplayThreatCount > 1 ? " (" + alertDisplayThreatCount + " enemies)" : "");
            cardBedDetail = alertPlayerName + " / " + alertDistance + " blocks from YOUR bed";
            cardBedExtra = !miningDetail.isEmpty() ? miningDetail : alertEquipment;
            cardBedColor = alertDangerColor();
        }
    }

    private void playSound(float pitch) {
        if (mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1f, pitch);
    }

    // ------------------------------------------------------------------ the bed's defense

    private static final class BedRecord {
        final BlockPos a, b;
        final Vec3 center;
        char color;
        boolean destroyed, available;
        int missing;
        long sampled = -1L, dirtyAfter, lastChangeAlert = -1L;
        // the last sample of the area around the bed: block names and variants, flat (y, x, z order)
        String[] names;
        int[] variants;
        int blockCount, minX, minY, minZ, sizeX, sizeY, sizeZ, maxLayer;
        Map<Integer, Map<String, Integer>> layers = new HashMap<>();
        List<String> materials = new ArrayList<>();
        String defense = "Not sampled", route = "", state = "Unknown";
        // the overlay's text, made again for a new sample or a new distance
        List<String> rowCache;
        long rowSample = -2L;
        float rowWidth = -1f;
        String titleCache, titleState, routeClip = "";
        int titleDistance = -1;

        BedRecord(BlockPos a, BlockPos b) {
            this.a = a;
            this.b = b;
            center = new Vec3((a.getX() + b.getX()) / 2.0 + 0.5, a.getY() + 0.28125, (a.getZ() + b.getZ()) / 2.0 + 0.5);
        }
    }

    private static final class MiningState {
        BlockPos target;
        String tool = "";
        long started, lastSwing;
    }

    private void packet(PacketEvent.Receive e) {
        // network thread: only noted here, read next tick
        Object p = e.getPacket();
        if (p instanceof S23PacketBlockChange) {
            BlockPos pos = ((S23PacketBlockChange) p).getBlockPosition();
            synchronized (bedChanges) {
                if (pos != null && bedChanges.size() < 128) bedChanges.add(pos);
            }
        } else if (p instanceof S22PacketMultiBlockChange || p instanceof S27PacketExplosion) {
            synchronized (bedChanges) { defenseRefreshPending = true; }
        } else if (p instanceof S25PacketBlockBreakAnim) {
            lastDigPacketTime = System.currentTimeMillis();
        }
    }

    private boolean isOwn(BedRecord bed) {
        BlockPos[] parts = BedTracker.ownBed();
        return bed != null && parts != null && ((bed.a.equals(parts[0]) && bed.b.equals(parts[1])) || (bed.a.equals(parts[1]) && bed.b.equals(parts[0])));
    }

    private boolean nearDefense(BlockPos p, BedRecord bed) {
        int r = sampledRadius;
        return p != null && Math.abs(p.getX() - bed.center.xCoord) <= r + 2 && Math.abs(p.getZ() - bed.center.zCoord) <= r + 2
            && p.getY() >= bed.a.getY() && p.getY() <= bed.a.getY() + r;
    }

    private void updateBedMap(EntityPlayerSP local, long now) {
        List<BlockPos> changes = null;
        boolean refresh;
        synchronized (bedChanges) {
            if (!bedChanges.isEmpty()) {
                changes = new ArrayList<>(bedChanges);
                bedChanges.clear();
            }
            refresh = defenseRefreshPending;
            defenseRefreshPending = false;
        }
        BlockPos[] parts = BedTracker.ownBed();
        if (parts == null) return;
        if (!isOwn(own)) own = new BedRecord(parts[0], parts[1]);
        BedRecord bed = own;
        bed.color = BedTracker.ownColor();
        // block changes far away and explosions say nothing about where: a fresh sample at most twice a second (a
        // block change next to the bed still gets one at once; one a second happens anyway)
        if (refresh && bed.dirtyAfter == 0L && (lastGlobalRefresh < 0L || now - lastGlobalRefresh >= 500L)) {
            bed.dirtyAfter = now;
            lastGlobalRefresh = now;
        }
        if (changes != null) {
            for (BlockPos changed : changes) {
                if (nearDefense(changed, bed) || changed.equals(bed.a) || changed.equals(bed.b)) {
                    if (bed.dirtyAfter == 0L) bed.dirtyAfter = now;
                    break;
                }
            }
        }
        String state = BedTracker.teamState(bed.color);
        if (state != null) bed.state = state;
        if (bed.destroyed || now < nextDefenseSample || (!overlay.get() && !changeAlerts.get())) return;
        // far from it (out of the overlay's reach, past change-alert distance) every 5 s is enough
        long every = local.getPositionVector().squareDistanceTo(bed.center) > 64.0 * 64.0 ? 5000L : 1000L;
        if ((bed.dirtyAfter > 0L && now > bed.dirtyAfter) || bed.sampled < 0L || now - bed.sampled >= every) sampleBed(bed, local, now);
        nextDefenseSample = now + 50L;
    }

    private static char dyeTeam(int variant) {
        switch (variant & 15) {
            case 14: return 'c';
            case 11: return '9';
            case 5: return 'a';
            case 4: return 'e';
            case 9: return 'b';
            case 0: return 'f';
            case 6: return 'd';
            case 7: return '7';
            default: return 0;
        }
    }

    private static int teamColor(char color) {
        switch (color) {
            case 'c': return 0xFFFF5555;
            case '9': return 0xFF5555FF;
            case 'a': return 0xFF55FF55;
            case 'e': return 0xFFFFFF55;
            case 'b': return 0xFF55FFFF;
            case 'f': return 0xFFFFFFFF;
            case 'd': return 0xFFFF55FF;
            case '7': return 0xFFAAAAAA;
            default: return COLOR_GRAY;
        }
    }

    private void sampleBed(BedRecord bed, EntityPlayerSP local, long now) {
        if (!isOwn(bed)) return;
        bed.dirtyAfter = 0L;
        int ax = bed.a.getX(), ay = bed.a.getY(), az = bed.a.getZ(), bx = bed.b.getX(), bz = bed.b.getZ();
        // there is no "is this chunk loaded" for blocks: the blocks under the bed must be there
        if (mc.theWorld.isAirBlock(bed.a.down()) || mc.theWorld.isAirBlock(bed.b.down())) {
            bed.available = false;
            bed.sampled = now;
            return;
        }
        if (!BedTracker.isBed(bed.a) && !BedTracker.isBed(bed.b)) {
            if (local.getPositionVector().squareDistanceTo(bed.center) <= 32 * 32) bed.missing++;
            if (bed.missing >= 3 && !bed.destroyed) {
                bed.destroyed = true;
                bed.available = false;
                bed.state = "Destroyed";
                if (changeAlerts.get()) bedEvent("Your bed destroyed", "Confirmed by block checks", 3, now);
            }
            bed.sampled = now;
            return;
        }
        bed.missing = 0;
        bed.available = true;
        int r = sampledRadius;
        int minX = Math.min(ax, bx) - r, minZ = Math.min(az, bz) - r;
        int sizeX = Math.max(ax, bx) + r - minX + 1, sizeY = r + 1, sizeZ = Math.max(az, bz) + r - minZ + 1;
        // the area as flat arrays, compared slot by slot with the last sample
        String[] names = new String[sizeX * sizeY * sizeZ];
        int[] variants = new int[names.length];
        Map<Integer, Map<String, Integer>> layers = new HashMap<>();
        Map<Character, Integer> votes = new HashMap<>();
        List<String> materials = new ArrayList<>();
        int maxLayer = 0, count = 0;
        for (int y = 0; y < sizeY; y++) {
            int wy = ay + y;
            for (int x = 0; x < sizeX; x++) {
                int wx = minX + x;
                for (int z = 0; z < sizeZ; z++) {
                    int wz = minZ + z;
                    IBlockState s = mc.theWorld.getBlockState(new BlockPos(wx, wy, wz));
                    Block block = s.getBlock();
                    if (block.getMaterial() == Material.air || block instanceof BlockBed) continue;
                    String name = blockName(block);
                    int variant = block.getMetaFromState(s);
                    int index = (y * sizeX + x) * sizeZ + z;
                    names[index] = name;
                    variants[index] = variant;
                    count++;
                    int layer = Math.min(Math.abs(wx - ax) + Math.abs(wy - ay) + Math.abs(wz - az), Math.abs(wx - bx) + Math.abs(wy - ay) + Math.abs(wz - bz));
                    if (layer < 1 || layer > r || !defenseMaterial(name)) continue;
                    Map<String, Integer> shell = layers.computeIfAbsent(layer, k -> new HashMap<>());
                    String label = name;
                    if (name.equals("wool") || name.equals("stained_hardened_clay") || name.equals("stained_glass") || name.equals("stained_glass_pane"))
                        label += "@" + (variant & 15);
                    shell.merge(label, 1, Integer::sum);
                    if (!materials.contains(name)) materials.add(name);
                    maxLayer = Math.max(maxLayer, layer);
                    if (name.equals("wool") || name.equals("stained_hardened_clay")) {
                        char c = dyeTeam(variant);
                        if (c != 0) votes.merge(c, 1, Integer::sum);
                    }
                }
            }
        }
        boolean baseline = bed.sampled >= 0L && bed.names != null && bed.blockCount > 0 && bed.minX == minX && bed.minY == ay && bed.minZ == minZ
            && bed.sizeX == sizeX && bed.sizeY == sizeY && bed.sizeZ == sizeZ;
        int removed = 0, added = 0;
        if (baseline) {
            for (int i = 0; i < names.length; i++) {
                String was = bed.names[i], is = names[i];
                if (was == null ? is == null : is != null && bed.variants[i] == variants[i] && was.equals(is)) continue;
                if (was != null) removed++;
                if (is != null) added++;
            }
        }
        bed.names = names;
        bed.variants = variants;
        bed.blockCount = count;
        bed.minX = minX;
        bed.minY = ay;
        bed.minZ = minZ;
        bed.sizeX = sizeX;
        bed.sizeY = sizeY;
        bed.sizeZ = sizeZ;
        bed.layers = layers;
        bed.materials = materials;
        bed.maxLayer = maxLayer;
        // only said when nothing guards the bed (the overlay lists the layers itself)
        bed.defense = layers.isEmpty() ? "Exposed bed" : "";
        bed.route = overlay.get() ? bestStraightRoute(bed) : "";
        bed.sampled = now;
        if ((removed > 0 || added > 0) && changeAlerts.get() && bedAlert.get() && local.getPositionVector().squareDistanceTo(bed.center) <= 32 * 32
            && (bed.lastChangeAlert < 0L || now - bed.lastChangeAlert >= 1500L)) {
            bed.lastChangeAlert = now;
            bedEvent("Your bed defense changed", "Removed " + removed + " / added " + added, 2, now);
        }
    }

    private static String snapshotName(BedRecord bed, int px, int py, int pz) {
        if (bed.names == null) return "air";
        int x = px - bed.minX, y = py - bed.minY, z = pz - bed.minZ;
        if (x < 0 || y < 0 || z < 0 || x >= bed.sizeX || y >= bed.sizeY || z >= bed.sizeZ) return "air";
        String name = bed.names[(y * bed.sizeX + x) * bed.sizeZ + z];
        return name == null ? "air" : name;
    }

    private static String materialLabel(String name) {
        int at = name.indexOf('@');
        if (at >= 0) {
            String[] dyes = { "White", "Orange", "Magenta", "Light blue", "Yellow", "Lime", "Pink", "Gray", "Light gray", "Cyan", "Purple", "Blue",
                "Brown", "Green", "Red", "Black" };
            return dyes[Integer.parseInt(name.substring(at + 1)) & 15] + " " + materialLabel(name.substring(0, at));
        }
        if (name.equals("wool")) return "Wool";
        if (name.equals("stained_hardened_clay") || name.equals("hardened_clay")) return "Clay";
        if (name.equals("planks") || name.equals("log") || name.equals("log2")) return "Wood";
        if (name.equals("end_stone")) return "End stone";
        if (name.equals("obsidian")) return "Obsidian";
        if (name.contains("glass")) return "Glass";
        return name.replace('_', ' ');
    }

    private static boolean defenseMaterial(String name) {
        return name.equals("wool") || name.equals("stained_hardened_clay") || name.equals("hardened_clay") || name.equals("planks") || name.equals("log")
            || name.equals("log2") || name.equals("end_stone") || name.equals("obsidian") || name.contains("glass") || name.equals("ladder")
            || name.equals("water") || name.equals("flowing_water");
    }

    // vanilla's break time from the block's hardness and the tool (efficiency counted), in seconds
    private static double approximateBreakSeconds(String name, ItemStack tool) {
        if (name.equals("air") || name.equals("bed")) return 0.0;
        if (name.contains("water") || name.contains("lava") || name.equals("bedrock")) return 1.0e6;
        double hardness = name.equals("obsidian") ? 50.0 : name.equals("end_stone") ? 3.0 : name.contains("clay") ? 1.25 : name.equals("wool") ? 0.8
            : name.contains("glass") ? 0.3 : name.equals("planks") || name.startsWith("log") ? 2.0 : name.equals("ladder") ? 0.4 : 3.0;
        String item = itemName(tool);
        boolean pickMaterial = name.equals("obsidian") || name.equals("end_stone") || name.contains("clay") || name.equals("stone") || name.equals("cobblestone");
        boolean correct = (pickMaterial && item.contains("pickaxe")) || ((name.equals("planks") || name.startsWith("log")) && item.endsWith("_axe"))
            || (name.equals("wool") && item.equals("shears"));
        boolean harvest = !pickMaterial || item.contains("pickaxe");
        if (name.equals("obsidian")) harvest = item.equals("diamond_pickaxe");
        double speed = 1.0;
        if (correct) {
            speed = item.startsWith("wooden") ? 2 : item.startsWith("stone") ? 4 : item.startsWith("iron") ? 6 : item.startsWith("diamond") ? 8
                : item.startsWith("golden") ? 12 : item.equals("shears") ? 5 : 1;
            int level = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, tool);
            if (level > 0) speed += level * level + 1;
        }
        double ticks = Math.ceil(hardness * (harvest ? 30.0 : 100.0) / speed);
        return Math.max(0.05, ticks / 20.0);
    }

    private double bestLocalBreakSeconds(String name) {
        double best = approximateBreakSeconds(name, null);
        for (int i = 0; i < 36; i++) {
            ItemStack tool = mc.thePlayer.inventory.getStackInSlot(i);
            if (tool != null) best = Math.min(best, approximateBreakSeconds(name, tool));
        }
        return best;
    }

    private String bestStraightRoute(BedRecord bed) {
        int[] dx = { 1, -1, 0, 0, 0 }, dz = { 0, 0, 1, -1, 0 }, dy = { 0, 0, 0, 0, 1 };
        String[] directions = { "East", "West", "South", "North", "Top" };
        double best = Double.MAX_VALUE;
        int count = 0, bestDir = 0;
        Map<String, Double> costs = new HashMap<>();
        for (BlockPos part : new BlockPos[]{ bed.a, bed.b }) {
            for (int i = 0; i < 5; i++) {
                // not through the other half of the bed
                BlockPos first = part.add(dx[i], dy[i], dz[i]);
                if (first.equals(bed.a) || first.equals(bed.b)) continue;
                double cost = 0;
                int blocks = 0;
                for (int step = sampledRadius; step >= 1; step--) {
                    String name = snapshotName(bed, part.getX() + dx[i] * step, part.getY() + dy[i] * step, part.getZ() + dz[i] * step);
                    if (name.equals("air")) continue;
                    blocks++;
                    Double cached = costs.get(name);
                    if (cached == null) {
                        cached = bestLocalBreakSeconds(name);
                        costs.put(name, cached);
                    }
                    cost += cached;
                }
                if (cost < best) {
                    best = cost;
                    count = blocks;
                    bestDir = i;
                }
            }
        }
        if (best >= 1.0e6) return "Straight routes blocked by fluid/unbreakable blocks";
        return "Straight: " + directions[bestDir] + " / " + count + " blocks / ~" + String.format(Locale.ROOT, "%.1f", best) + "s";
    }

    // ------------------------------------------------------------------ who is digging

    private void updateMining(EntityPlayerSP local, long now) {
        miningDetail = "";
        miningProgress = 0f;
        Vec3 bed = BedTracker.bedCenter();
        BlockPos[] parts = BedTracker.ownBed();
        if (bed == null || parts == null) {
            miningStates.clear();
            return;
        }
        // the server sends the crack only to players within 32 blocks of the block: farther away there is only the estimate
        if (exactMining.get() && local.getPositionVector().squareDistanceTo(bed) <= 30 * 30) {
            updateMiningExact(bed, parts[0], now);
            return;
        }
        if (!miningEstimate.get()) {
            miningStates.clear();
            return;
        }
        Set<String> seen = new HashSet<>();
        int checked = 0, r = sampledRadius;
        double nearest = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!BedTracker.isEnemy(player, teammates.get()) || player.getPositionVector().squareDistanceTo(bed) > 8 * 8) continue;
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            if (info == null) continue;
            String id = player.getUniqueID().toString();
            if (++checked > 4) break;
            ItemStack tool = player.getHeldItem();
            String name = itemName(tool);
            if (!name.contains("pickaxe") && !name.endsWith("_axe") && !name.equals("shears")) continue;
            MiningState state = miningStates.get(id);
            if (!player.isSwingInProgress && (state == null || now - state.lastSwing > 350L)) continue;
            BlockPos target = miningRay(player);
            if (target == null || target.getY() < parts[0].getY() || Math.abs(target.getX() - bed.xCoord) > r + 1 || Math.abs(target.getZ() - bed.zCoord) > r + 1
                || target.getY() > parts[0].getY() + r) continue;
            if (state == null || !state.target.equals(target) || !name.equals(state.tool) || now - state.lastSwing > 350L) {
                state = new MiningState();
                state.target = target;
                state.tool = name;
                state.started = now;
                state.lastSwing = now;
                miningStates.put(id, state);
            }
            if (player.isSwingInProgress) state.lastSwing = now;
            seen.add(id);
            double dist = player.getPositionVector().squareDistanceTo(bed);
            if (dist >= nearest) continue;
            nearest = dist;
            double seconds = approximateBreakSeconds(blockName(mc.theWorld.getBlockState(target).getBlock()), tool);
            // a suspicion, not digging the server confirmed: never shown as done
            miningProgress = Math.min(0.95f, (float) ((now - state.started) / Math.max(50.0, seconds * 1000.0)));
            miningDetail = "Digging? " + player.getName() + " ~" + (int) (miningProgress * 100) + "% @ " + blockKey(target);
            if (lastDigPacketTime >= 0L && now - lastDigPacketTime < 500L) miningDetail += " [S25 nearby?]";
            setDig(id, target, player.getName(), (int) (miningProgress * 10), false, info, tool, 0, now);
        }
        miningStates.keySet().retainAll(seen);
    }

    // exact digging from the server's crack (BlockBreaks): the nearest enemy digging inside the defense
    private void updateMiningExact(Vec3 bed, BlockPos partA, long now) {
        miningStates.clear();
        int r = sampledRadius;
        double nearest = Double.MAX_VALUE;
        BlockBreaks.Break pick = null;
        EntityPlayer pickPlayer = null;
        for (BlockBreaks.Break b : BlockBreaks.list()) {
            BlockPos t = b.pos;
            if (t.getY() < partA.getY() || Math.abs(t.getX() - bed.xCoord) > r + 1 || Math.abs(t.getZ() - bed.zCoord) > r + 1 || t.getY() > partA.getY() + r) continue;
            EntityPlayer player = b.breaker instanceof EntityPlayer ? (EntityPlayer) b.breaker : null;
            if (b.breaker == mc.thePlayer || player != null && !BedTracker.isEnemy(player, teammates.get())) continue;
            double dist = player == null ? 0 : player.getPositionVector().squareDistanceTo(bed);
            if (dist >= nearest) continue;
            nearest = dist;
            pick = b;
            pickPlayer = player;
        }
        if (pick == null) return;
        String name = pickPlayer != null ? pickPlayer.getName() : "Someone";
        miningProgress = Math.min(1f, pick.progress());
        miningDetail = "Digging: " + name + " " + (pick.stage + 1) * 10 + "% @ " + blockKey(pick.pos);
        NetworkPlayerInfo info = pickPlayer == null || mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(pickPlayer.getUniqueID());
        String id = pickPlayer != null ? pickPlayer.getUniqueID().toString() : "e" + pick.breakerId;
        setDig(id, pick.pos, name, pick.stage, true, info, pickPlayer == null ? null : pickPlayer.getHeldItem(), pick.ticks, now);
    }

    private BlockPos miningRay(EntityPlayer player) {
        double yaw = Math.toRadians(player.rotationYaw), pitch = Math.toRadians(player.rotationPitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch), dz = Math.cos(yaw) * Math.cos(pitch), dy = -Math.sin(pitch);
        double ex = player.posX, ey = player.posY + player.getEyeHeight(), ez = player.posZ;
        BlockPos last = null;
        for (double d = 0.15; d <= 5.0; d += 0.15) {
            BlockPos p = new BlockPos(Math.floor(ex + dx * d), Math.floor(ey + dy * d), Math.floor(ez + dz * d));
            if (p.equals(last)) continue;
            last = p;
            Block block = mc.theWorld.getBlockState(p).getBlock();
            if (block.getMaterial() != Material.air && !blockName(block).contains("water")) return p;
        }
        return null;
    }

    // once a tick: a new digger or block starts over, a new step times the steps
    private void setDig(String id, BlockPos target, String name, int stage, boolean exact, NetworkPlayerInfo info, ItemStack tool, int ticks, long now) {
        String key = blockKey(target);
        String material = blockName(mc.theWorld.getBlockState(target).getBlock());
        if (!id.equals(digId) || !key.equals(digKey)) {
            if (digId.isEmpty() && sound.get() && (digSoundAt < 0L || now - digSoundAt >= soundCooldown.get())) {
                digSoundAt = now;
                playSound(1.6f);
            }
            digId = id;
            digKey = key;
            digStage = stage;
            digStageStart = now;
            // a first guess from the block and the tool; the start time is better once a step has passed
            digStageMs = (long) Math.max(50.0, Math.min(10000.0, approximateBreakSeconds(material, tool) * 100.0));
            if (exact && ticks > 0 && stage > 0) digStageMs = Math.max(50L, ticks * 50L / stage);
            digShown = stage / 10f;
            digInfo = info;
        } else if (stage > digStage) {
            digStageMs = Math.max(50L, (now - digStageStart) / (stage - digStage));
            digStage = stage;
            digStageStart = now;
        } else if (stage < digStage) {
            digStage = stage;
            digStageStart = now;
        }
        digName = name;
        digBlock = material.equals("bed") ? "BED" : materialLabel(material);
        digExact = exact;
        digSeenAt = now;
        if (info != null) digInfo = info;
    }

    private void clearDig() {
        digId = "";
        digKey = "";
        digStage = -1;
        digSeenAt = -1L;
    }

    // where the bar should be now: the last step plus how far into the next one its pace says we are
    private float digProgressNow(long now) {
        if (digId.isEmpty()) return 0f;
        if (!digExact) return miningProgress;
        float within = digStageMs <= 0L ? 0f : Math.min(0.97f, (now - digStageStart) / (float) digStageMs);
        return Math.max(0f, Math.min(1f, (digStage + within) / 10f));
    }

    // ------------------------------------------------------------------ drawing

    private void render(Render2DEvent e) {
        // what is drawn after the island finds depth, blending and the alpha test as they were
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND), alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        try {
            draw(e);
        } catch (Exception ex) {
            diagnostic("render: " + ex);
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableTexture2D();
        if (depth) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        if (blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        if (alpha) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
    }

    private static float approach(float current, float target, float factor, float frames) {
        if (frames <= 0f || current == target) return current;
        return current + (target - current) * (1f - (float) Math.pow(1f - factor, frames));
    }

    private static float fade(float current, boolean show, float factor, float frames) {
        float target = show ? 1f : 0f;
        float v = approach(current, target, factor, frames);
        if (v < 0.005f && !show) return 0f;
        if (v > 0.995f && show) return 1f;
        return v;
    }

    // Per frame: animation, the layout of the one card on screen, drawing. Text and numbers come from the tick.
    private void draw(Render2DEvent e) {
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        // animations move by elapsed time, counted in 100 fps frames: the same speed at any frame rate
        float frames = lastFrameNanos < 0L ? 1f : (float) Math.max(0.0, Math.min(30.0, (nanos - lastFrameNanos) / 1.0e7));
        lastFrameNanos = nanos;
        float screenW = e.width(), screenH = e.height();
        renderBedMap(screenW, screenH);

        float scale = size.getFloat();
        alertAlpha = fade(alertAlpha, wantsAnyBedCard(now), 0.10f, frames);
        scaffoldAlpha = fade(scaffoldAlpha, bridgeActive, 0.10f, frames);
        bedAlpha = fade(bedAlpha, showBedProgress, 0.10f, frames);
        boolean notif = false;
        if (lastToggleTime != -1L) {
            if (now - lastToggleTime < displayTime.get()) notif = true;
            else lastToggleTime = -1L;
        }
        if (notif && notifAlpha < 1f) notifAlpha = approach(notifAlpha, 1f, 0.05f * (1f + (1f - notifAlpha) * 0.5f), frames);
        else if (!notif && notifAlpha > 0f) notifAlpha = approach(notifAlpha, 0f, 0.05f * (1f + (1f - notifAlpha) * 0.3f), frames);
        if (notifAlpha < 0.005f) notifAlpha = 0f;
        if (notifAlpha > 0.995f) notifAlpha = 1f;
        boolean digging = !digId.isEmpty() && tracker.get() && bedAlert.get() && BedTracker.inGame() && !BedTracker.bedDestroyed();
        digAlpha = fade(digAlpha, digging, 0.15f, frames);
        if (digging) digShown = approach(digShown, digProgressNow(now), 0.35f, frames);
        updateSwitchAnimation(now);

        float padding = 8f * scale, iconSize = 22f * scale, iconGap = 6f * scale, dotGap = 5f * scale, fpsGap = iconGap;
        float capsuleHeight = 26f * scale;
        float cardPadding = 12f * scale, cardIconSize = 24f * scale, cardIconGap = 10f * scale, cardHeight = 48f * scale;
        boolean textShadow = shadow.get();

        int fps = Mc.fps();
        String fpsText = fps + " fps";
        int fpsTextColor = !fpsColor.get() ? COLOR_TEXT : fps < 30 ? COLOR_FPS_LOW : fps < 60 ? COLOR_FPS_MID : COLOR_FPS_OK;
        String titleText = title.get().isEmpty() ? Gale.NAME : title.get();
        String playerName = mc.thePlayer.getName();
        float capsuleWidth = padding + iconSize + iconGap + tw(titleText) * scale + dotGap + tw("-") * scale + dotGap + tw(playerName) * scale
            + fpsGap + tw(fpsText) * scale + padding;

        boolean showDig = digAlpha > 0.5f;
        boolean showAlert = !showDig && alertAlpha > 0.5f;
        boolean showScaffold = !showDig && !showAlert && scaffoldAlpha > 0.5f;
        boolean showBed = !showDig && !showAlert && !showScaffold && bedAlpha > 0.5f;
        boolean showNotif = !showDig && !showAlert && !showScaffold && !showBed && notifAlpha > 0.5f;
        String digLine1 = digExact ? "BED UNDER ATTACK" : "Digging? (estimate)", digLine2 = "", digPercent = "";

        // only the card on screen is laid out
        float cardWidth = 0f, cardAlpha = 0f;
        int cardType = 0;
        String alertLine1 = "", alertLine2 = "", alertLine3 = "";
        float maxText = Math.max(40f, screenW - cardPadding * 2f - cardIconSize - cardIconGap - 12f);
        if (showDig) {
            digLine2 = clip(digName + "  " + digBlock, maxText, scale);
            digPercent = (int) (digShown * 100f) + "%";
            float w = Math.max((tw(digLine1) + 8f + tw("100%")) * scale, tw(digLine2) * scale);
            cardWidth = cardPadding + cardIconSize + cardIconGap + w + cardPadding;
            cardAlpha = digAlpha;
            cardType = 5;
        } else if (showAlert) {
            alertLine1 = clip(cardBedTitle, maxText, scale);
            alertLine2 = clip(cardBedDetail, maxText, scale);
            alertLine3 = clip(cardBedExtra, maxText, scale);
            float w = Math.max(Math.max(tw(alertLine1), tw(alertLine2)), tw(alertLine3)) * scale;
            cardWidth = cardPadding + cardIconSize + cardIconGap + w + cardPadding;
            cardAlpha = alertAlpha;
            cardType = 1;
        } else if (showScaffold) {
            float w = Math.max(tw("Scaffold Toggled"), tw(scaffoldLine2)) * scale;
            cardWidth = cardPadding + cardIconSize + cardIconGap + w + cardPadding;
            cardAlpha = scaffoldAlpha;
            cardType = 2;
        } else if (showBed) {
            int percent = (int) (bedProgress * 100);
            if (percent != bedTextPercent) {
                bedTextPercent = percent;
                bedLine2Right = percent + "%";
                bedLine2Full = "Break Progress: " + bedLine2Right;
            }
            float w = Math.max(tw("Breaking Bed"), tw(bedLine2Full)) * scale;
            cardWidth = cardPadding + cardIconSize + cardIconGap + w + cardPadding;
            cardAlpha = bedAlpha;
            cardType = 3;
        } else if (showNotif) {
            float w = Math.max(tw("Module Toggled"), tw(notifLine2)) * scale;
            cardWidth = cardPadding + cardIconSize + cardIconGap + w + cardPadding;
            cardAlpha = notifAlpha;
            cardType = 4;
        }

        float targetWidth = cardAlpha > 0f ? capsuleWidth * (1f - cardAlpha) + cardWidth * cardAlpha : capsuleWidth;
        if (currentWidth < 0f) currentWidth = targetWidth;
        currentWidth = approach(currentWidth, Math.max(0f, targetWidth), 0.10f, frames);
        float width = currentWidth;
        if (cardType == 1 && !alertLine3.isEmpty()) cardHeight = 62f * scale;
        float targetHeight = cardAlpha > 0f ? capsuleHeight * (1f - cardAlpha) + cardHeight * cardAlpha : capsuleHeight;
        if (currentHeight < 0f) currentHeight = targetHeight;
        currentHeight = approach(currentHeight, targetHeight, 0.10f, frames);
        float height = currentHeight;
        float radius = height / 2f * (1f - cardAlpha) + 10f * scale * cardAlpha;
        float x = (screenW - width) / 2f + xOffset.getFloat();
        float y = 10f + yOffset.getFloat();
        hudX = x;
        hudY = y;
        hudW = width;
        hudH = height;
        hud.drawn();

        float glowStrength = glow.getFloat() / 100f;
        if (glowStrength > 0.01f) {
            for (float[] layer : GLOW_TABLES[Math.max(1, Math.min(5, glowLayers.getInt())) - 1]) {
                float grow = layer[0] * scale;
                int ga = Math.max(0, Math.min(255, (int) (layer[1] * glowStrength * 255)));
                rr(x - grow, y - grow, x + width + grow, y + height + grow, radius + grow, (ga << 24) | COLOR_BG_BASE);
            }
        }
        int bgAlpha = Math.max(0, Math.min(255, (int) (bgOpacity.get() / 100.0 * 255.0)));
        rr(x, y, x + width, y + height, radius, (bgAlpha << 24) | COLOR_BG_BASE);

        float iconX = x + cardPadding, iconY = y + 10f * scale, textX = iconX + cardIconSize + cardIconGap;
        float line1Y = y + 10f * scale, line2Y = y + 26f * scale;
        if (cardType == 5) {
            int a = alpha255(digAlpha);
            iconBackground(iconX, iconY, cardIconSize, a, scale);
            if (digInfo != null) face(digInfo, iconX + 2f * scale, iconY + 2f * scale, cardIconSize - 4f * scale, digAlpha);
            else drawBedIcon(iconX, iconY, cardIconSize, a, scale);
            // the title pulses while the bed is being dug
            float pulse = 0.75f + 0.25f * (float) Math.sin(now / 160.0);
            int titleColor = (Math.max(0, Math.min(255, (int) (a * (digExact ? pulse : 1f)))) << 24) | (COLOR_ALERT_RED & 0xFFFFFF);
            text(digLine1, textX, line1Y, scale, titleColor, textShadow);
            text(digPercent, x + width - cardPadding - tw(digPercent) * scale, line1Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            text(digLine2, textX, line2Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            float barX = x + cardPadding, barW = width - cardPadding * 2f, barH = 4f * scale, barY = y + height - 11f * scale;
            rr(barX, barY, barX + barW, barY + barH, barH / 2f, multAlpha(0x26FFFFFF, digAlpha));
            if (digShown > 0.005f) {
                float fillW = Math.max(barH, barW * Math.min(1f, digShown));
                rr(barX, barY, barX + fillW, barY + barH, barH / 2f, (a << 24) | (mixRgb(0xFFAA00, 0xFF2222, digShown) & 0xFFFFFF));
            }
        } else if (cardType == 1) {
            int a = alpha255(alertAlpha);
            iconBackground(iconX, iconY, cardIconSize, a, scale);
            if (!cardBedEvent && alertInfo != null) face(alertInfo, iconX + 2f * scale, iconY + 2f * scale, cardIconSize - 4f * scale, alertAlpha);
            else drawBedIcon(iconX, iconY, cardIconSize, a, scale);
            text(alertLine1, textX, line1Y, scale, (a << 24) | (cardBedColor & 0xFFFFFF), textShadow);
            text(alertLine2, textX, line2Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            if (!alertLine3.isEmpty()) text(alertLine3, textX, y + 40f * scale, scale * 0.9f, multAlpha(COLOR_GRAY, alertAlpha), textShadow);
            if (!cardBedEvent && miningProgress > 0f) {
                float bx = x + cardPadding, by = y + height - 6f * scale, bw = width - cardPadding * 2f;
                rr(bx, by, bx + bw, by + 2f * scale, scale, multAlpha(0x40FFFFFF, alertAlpha));
                rr(bx, by, bx + bw * miningProgress, by + 2f * scale, scale, multAlpha(cardBedColor, alertAlpha));
            }
        } else if (cardType == 2) {
            int a = alpha255(scaffoldAlpha);
            iconBackground(iconX, iconY, cardIconSize, a, scale);
            drawCubeIcon(iconX, iconY, cardIconSize, (a << 24) | (COLOR_SPEED & 0xFFFFFF), scale);
            text("Scaffold Toggled", textX, line1Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            text(scaffoldPart2Left, textX, line2Y, scale, (a << 24) | (COLOR_GRAY & 0xFFFFFF), textShadow);
            text(scaffoldSpeedText + " block/s", textX + tw(scaffoldPart2Left) * scale, line2Y, scale, (a << 24) | (COLOR_SPEED_TEXT & 0xFFFFFF), textShadow);
            float ratio = initialBlockCount > 0 ? Math.min((float) currentBlockCount / initialBlockCount, 1f) : 0f;
            bar(x, y, width, height, scale, ratio, themeColor(cardTheme.index(), cardColor.get(), now), cardBarOpacity.get(), cardBarBg.get(), scaffoldAlpha);
        } else if (cardType == 3) {
            int a = alpha255(bedAlpha);
            iconBackground(iconX, iconY, cardIconSize, a, scale);
            drawBedIcon(iconX, iconY, cardIconSize, a, scale);
            float available = (x + width - cardPadding) - textX;
            float textW = Math.max(tw("Breaking Bed"), tw(bedLine2Full)) * scale;
            float tx = textX + (available - textW) / 2f;
            text("Breaking Bed", tx, line1Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            text("Break Progress: ", tx, line2Y, scale, (a << 24) | (COLOR_GRAY & 0xFFFFFF), textShadow);
            text(bedLine2Right, tx + tw("Break Progress: ") * scale, line2Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            bar(x, y, width, height, scale, bedProgress, themeColor(barTheme.index(), barColor.get(), now), bedBarOpacity.get(), bedBarBg.get(), bedAlpha);
        } else if (cardType == 4) {
            int a = alpha255(notifAlpha);
            float switchW = cardIconSize * 1.15f, switchH = cardIconSize * 0.7f;
            float switchX = iconX + (cardIconSize - switchW) / 2f, switchY = iconY + (cardIconSize - switchH) / 2f;
            rr(switchX, switchY, switchX + switchW, switchY + switchH, switchH / 2f, (a << 24) | (mixRgb(COLOR_SWITCH_OFF, COLOR_SWITCH_ON, switchPosition) & 0xFFFFFF));
            float dotR = switchH * 0.4f, dotLeft = switchX + dotR + 3f * scale, dotRight = switchX + switchW - dotR - 3f * scale;
            float dotX = dotLeft + (dotRight - dotLeft) * switchPosition, dotY = switchY + switchH / 2f;
            rr(dotX - dotR, dotY - dotR, dotX + dotR, dotY + dotR, dotR, (a << 24) | 0xFFFFFF);
            text("Module Toggled", textX, line1Y, scale, (a << 24) | (COLOR_TEXT & 0xFFFFFF), textShadow);
            text(notifPart1, textX, line2Y, scale, (a << 24) | (COLOR_GRAY & 0xFFFFFF), textShadow);
            text(notifPart2, textX + tw(notifPart1) * scale, line2Y, scale, (a << 24) | ((lastToggleState ? COLOR_SWITCH_ON : 0xFFFF4444) & 0xFFFFFF), textShadow);
        } else {
            float textY = y + height / 2f - 3f * scale, textCenterY = textY + 4f * scale;
            float cx = x + padding, logoY = textCenterY - iconSize / 2f;
            if (!logo.get() || !drawLogo(cx, logoY, iconSize)) {
                String letter = titleText.substring(0, 1);
                float letterScale = scale * 1.4f;
                text(letter, cx + (iconSize - tw(letter) * letterScale) / 2f, logoY + iconSize / 2f - 5.5f * scale, letterScale, COLOR_ACCENT, true);
            }
            cx += iconSize + iconGap;
            text(titleText, cx, textY, scale, COLOR_ACCENT, textShadow);
            cx += tw(titleText) * scale + dotGap;
            text("-", cx, textY, scale, COLOR_GRAY, textShadow);
            cx += tw("-") * scale + dotGap;
            text(playerName, cx, textY, scale, COLOR_TEXT, textShadow);
            cx += tw(playerName) * scale + fpsGap;
            text(fpsText, cx, textY, scale, fpsTextColor, textShadow);
        }
    }

    private void bar(float x, float y, float width, float height, float scale, float ratio, int color, double opacity, double bgOpacity, float alpha) {
        float barX = x + 12f * scale, barW = width - 24f * scale, barH = 4f * scale, barY = y + height - 11f * scale;
        int bg = Math.max(0, Math.min(255, (int) (bgOpacity / 100.0 * 255.0 * alpha)));
        rr(barX, barY, barX + barW, barY + barH, barH / 2f, (bg << 24) | 0xFFFFFF);
        if (ratio <= 0.005f) return;
        int fa = Math.max(0, Math.min(255, (int) (opacity / 100.0 * 255.0 * alpha)));
        rr(barX, barY, barX + Math.max(barH, barW * ratio), barY + barH, barH / 2f, (fa << 24) | (color & 0xFFFFFF));
    }

    private void iconBackground(float x, float y, float size, int alpha, float scale) {
        int a = (int) ((COLOR_CARD_ICON_BG >>> 24) * (alpha / 255f));
        rr(x, y, x + size, y + size, 5f * scale, (a << 24) | (COLOR_CARD_ICON_BG & 0xFFFFFF));
    }

    private static void drawBedIcon(float x, float y, float size, int alpha, float scale) {
        float bedW = size * 0.85f, bedH = size * 0.65f;
        float bx = x + (size - bedW) / 2f, by = y + (size - bedH) / 2f;
        rr(bx, by, bx + bedW, by + bedH, 2f * scale, (alpha << 24) | 0x5D4037);
        rr(bx + scale, by + scale, bx + bedW - scale, by + bedH - scale, scale, (alpha << 24) | 0xD32F2F);
        float pillowW = bedW * 0.35f, pillowH = bedH - 2f * scale;
        rr(bx + scale, by + scale, bx + scale + pillowW, by + scale + pillowH, scale, (alpha << 24) | 0xFFFFFF);
        rr(bx + scale + pillowW, by + scale, bx + bedW - scale, by + bedH - scale, scale, (alpha << 24) | 0xB71C1C);
    }

    private static void drawCubeIcon(float iconX, float iconY, float size, int color, float scale) {
        float l = size * 0.24f, lw = 1.6f * scale;
        float cx = iconX + size / 2f, cy = iconY + size / 2f - l * 0.1f;
        float topX = cx, topY = cy - l * 0.9f, leftX = cx - l, leftY = cy - l * 0.4f, rightX = cx + l, rightY = cy - l * 0.4f;
        float midX = cx, midY = cy + l * 0.1f, bLeftX = cx - l, bLeftY = cy + l * 0.6f, bMidX = cx, bMidY = cy + l * 1.1f, bRightX = cx + l, bRightY = cy + l * 0.6f;
        line(topX, topY, leftX, leftY, lw, color);
        line(topX, topY, rightX, rightY, lw, color);
        line(leftX, leftY, midX, midY, lw, color);
        line(rightX, rightY, midX, midY, lw, color);
        line(leftX, leftY, bLeftX, bLeftY, lw, color);
        line(midX, midY, bMidX, bMidY, lw, color);
        line(rightX, rightY, bRightX, bRightY, lw, color);
        line(bLeftX, bLeftY, bMidX, bMidY, lw, color);
        line(bRightX, bRightY, bMidX, bMidY, lw, color);
    }

    private void updateSwitchAnimation(long now) {
        if (switchSequence != toggleSequence) {
            if (switchStartTime < 0L) {
                switchPosition = lastToggleState ? 1f : 0f;
                switchFrom = switchTo = switchPosition;
            } else {
                switchFrom = switchPosition;
                switchTo = lastToggleState ? 1f : 0f;
            }
            switchStartTime = now;
            switchSequence = toggleSequence;
        }
        if (switchStartTime < 0L) {
            switchPosition = lastToggleState ? 1f : 0f;
            return;
        }
        float t = Math.max(0f, Math.min(1f, (now - switchStartTime) / ANIMATION_MS));
        switchPosition = switchFrom + (switchTo - switchFrom) * (1f - (float) Math.pow(1f - t, 3));
        if (t >= 1f) switchPosition = switchFrom = switchTo;
    }

    private static int themeColor(int theme, int custom, long now) {
        switch (theme) {
            case 0: return 0xFFAA00FF;
            case 1: return hsvToRgb((now % 3000L) / 3000f * 360f, 1f, 1f);
            case 2: return 0xFFFF4444;
            case 3: return 0xFF4CAF50;
            case 4: return 0xFF4488FF;
            case 5: return 0xFFFFDD44;
            case 6: return 0xFFFF66CC;
            case 7: return 0xFF44DDDD;
            case 8: return 0xFFFFFFFF;
            default: return custom | 0xFF000000;
        }
    }

    private static int hsvToRgb(float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f;
        float c = v * s, x = c * (1f - Math.abs((h / 60f) % 2f - 1f)), m = v - c, r, g, b;
        if (h < 60f) { r = c; g = x; b = 0; }
        else if (h < 120f) { r = x; g = c; b = 0; }
        else if (h < 180f) { r = 0; g = c; b = x; }
        else if (h < 240f) { r = 0; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.max(0, Math.min(255, (int) ((r + m) * 255f))) << 16) | (Math.max(0, Math.min(255, (int) ((g + m) * 255f))) << 8)
            | Math.max(0, Math.min(255, (int) ((b + m) * 255f)));
    }

    private static int mixRgb(int from, int to, float progress) {
        float t = Math.max(0f, Math.min(1f, progress));
        int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int multAlpha(int color, float factor) {
        factor = Math.max(0f, Math.min(1f, factor));
        return ((int) ((color >>> 24) * factor) << 24) | (color & 0xFFFFFF);
    }

    private static int alpha255(float a) { return Math.max(0, Math.min(255, (int) (a * 255))); }

    // ------------------------------------------------------------------ the defense overlay over your bed

    private void renderBedMap(float screenW, float screenH) {
        if (!BedTracker.inGame() || !tracker.get() || !overlay.get() || own == null || !isOwn(own)) return;
        BedRecord bed = own;
        double squared = mc.thePlayer.getPositionVector().squareDistanceTo(bed.center);
        if (bed.destroyed || !bed.available || squared > 64 * 64) return;
        float[] screen = Projection.toScreen(bed.center.xCoord, bed.a.getY() + 2.3, bed.center.zCoord);
        if (screen == null || screen[0] < 0 || screen[0] > screenW || screen[1] < 0 || screen[1] > screenH) return;
        float scale = 0.8f, width = Math.min(300f, screenW - 12f);
        // rows and route are cut once a sample, the title once a metre: not every frame
        if (bed.rowCache == null || bed.rowSample != bed.sampled || bed.rowWidth != width) {
            List<String> cut = new ArrayList<>();
            for (String row : defenseRows(bed, width - 12, scale)) cut.add(clipText(row, width - 12, scale));
            bed.rowCache = cut;
            bed.rowSample = bed.sampled;
            bed.rowWidth = width;
            bed.routeClip = clipText(bed.route, width - 12, scale);
            bed.titleCache = null;
        }
        String state = BedTracker.teamState(bed.color);
        String shown = state == null ? bed.state : state;
        int metres = (int) Math.ceil(Math.sqrt(squared));
        if (bed.titleCache == null || metres != bed.titleDistance || !shown.equals(bed.titleState)) {
            bed.titleDistance = metres;
            bed.titleState = shown;
            bed.titleCache = clipText(BedTracker.teamName(bed.color) + " / " + shown + " / " + metres + "m", width - 12, scale);
        }
        List<String> rows = bed.rowCache;
        float height = 50f + rows.size() * 12f;
        float x = Math.max(6f, Math.min(screenW - width - 6f, screen[0] - width / 2f));
        float y = Math.max(6f, Math.min(screenH - height - 6f, screen[1]));
        rr(x, y, x + width, y + height, 5f, 0xCC141414);
        text(bed.titleCache, x + 6, y + 5, scale, teamColor(bed.color), true);
        float rowY = y + 17;
        for (String row : rows) {
            text(row, x + 6, rowY, scale, COLOR_TEXT, true);
            rowY += 12;
        }
        text(bed.routeClip, x + 6, rowY, scale, COLOR_GRAY, true);
        float ix = x + 6;
        for (String material : bed.materials) {
            if (ix + 16 > x + width - 6) break;
            if (material.contains("water") || material.equals("ladder")) continue;
            ItemStack icon = defenseIcons.get(material);
            if (icon == null) {
                Item item = Item.getByNameOrId(material);
                if (item == null) continue;
                icon = new ItemStack(item);
                defenseIcons.put(material, icon);
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(ix, rowY + 13, 0);
            GlStateManager.scale(0.8f, 0.8f, 1f);
            RenderUtil.item(icon, 0, 0);
            GlStateManager.popMatrix();
            ix += 18;
        }
    }

    private List<String> defenseRows(BedRecord bed, float maxWidth, float scale) {
        List<String> rows = new ArrayList<>();
        if (bed.layers.isEmpty()) {
            rows.add(bed.defense);
            return rows;
        }
        for (int i = 1; i <= sampledRadius; i++) {
            Map<String, Integer> layer = bed.layers.get(i);
            if (layer == null) continue;
            List<String> names = new ArrayList<>(layer.keySet());
            Collections.sort(names);
            StringBuilder row = new StringBuilder("L" + i + ": ");
            for (String name : names) {
                String token = materialLabel(name) + " x" + layer.get(name);
                if (tw(row + token) * scale > maxWidth && row.length() > 4) {
                    rows.add(row.toString());
                    row = new StringBuilder("    ");
                }
                row.append(token).append("  ");
            }
            rows.add(row.toString());
        }
        return rows;
    }

    // ------------------------------------------------------------------ small drawing helpers

    private static void rr(float x1, float y1, float x2, float y2, float radius, int color) { RoundRect.draw(x1, y1, x2, y2, radius, color); }

    private static int tw(String text) { return text == null ? 0 : mc.fontRendererObj.getStringWidth(text); }

    private static void text(String text, float x, float y, float scale, int color, boolean shadow) {
        // the font draws alpha below 4 as solid
        if (text == null || text.isEmpty() || (color >>> 24) < 4) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        if (scale != 1f) GlStateManager.scale(scale, scale, 1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.fontRendererObj.drawString(text, 0, 0, color, shadow);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    // B4's line2D: a GL line, width in screen pixels
    private static void line(float x1, float y1, float x2, float y2, float width, int color) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(width);
        GlStateManager.color((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        w.pos(x1, y1, 0).endVertex();
        w.pos(x2, y2, 0).endVertex();
        t.draw();
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void texQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0).tex(u1, v0).endVertex();
        wr.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
    }

    // the player's own skin: face and hat layer, as the tab list draws it
    private static void face(NetworkPlayerInfo info, float x, float y, float size, float alpha) {
        mc.getTextureManager().bindTexture(info.getLocationSkin());
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha);
        texQuad(x, y, size, size, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f);
        texQuad(x, y, size, size, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    // the Raven logo, downloaded once in the background (as the script did); false until it is here
    private static boolean drawLogo(float x, float y, float size) {
        if (!logoRequested) {
            logoRequested = true;
            mc.getTextureManager().loadTexture(LOGO, new ThreadDownloadImageData(null, LOGO_URL, null, new IImageBuffer() {
                @Override
                public BufferedImage parseUserSkin(BufferedImage image) { return image; }

                @Override
                public void skinAvailable() { logoReady = true; }
            }));
        }
        if (!logoReady) return false;
        mc.getTextureManager().bindTexture(LOGO);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        texQuad(x, y, size, size, 0, 0, 1, 1);
        GlStateManager.disableBlend();
        return true;
    }

    private final Map<String, String> clipCache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 64; }
    };

    // cut to the width with "...", remembered per text and width
    private String clip(String text, float maxWidth, float scale) {
        if (text == null) return "";
        String key = text + "\u0000" + maxWidth + "\u0000" + scale;
        String out = clipCache.get(key);
        if (out == null) {
            out = clipText(text, maxWidth, scale);
            clipCache.put(key, out);
        }
        return out;
    }

    private static String clipText(String text, float maxWidth, float scale) {
        if (text == null) return "";
        if (tw(text) * scale <= maxWidth) return text;
        // the longest start that still fits with "...", found by halving
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (tw(text.substring(0, mid) + "...") * scale <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low) + "...";
    }

    // ------------------------------------------------------------------ names

    private static String blockName(Block block) {
        ResourceLocation r = Block.blockRegistry.getNameForObject(block);
        return r == null ? "" : r.getResourcePath();
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        ResourceLocation r = Item.itemRegistry.getNameForObject(stack.getItem());
        return r == null ? "" : r.getResourcePath();
    }

    private static String blockKey(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    private void diagnostic(String text) {
        long now = System.currentTimeMillis();
        if (lastDiagnostic >= 0L && now - lastDiagnostic < 5000L) return;
        lastDiagnostic = now;
        Gale.LOG.warn("[Island] {}", ChatUtil.strip(text));
    }

    // ------------------------------------------------------------------ for the self test

    /** Everything the cards would say, on one line. */
    public String debugState() {
        return "dig=" + digId + "/" + digStage + "/" + (int) (digShown * 100) + "% " + digName + " " + digBlock + (digExact ? " exact" : " estimate")
            + " alert=" + cardBedTitle + " | " + cardBedDetail + " | " + cardBedExtra + " event=" + bedEventTitle + " | " + bedEventDetail
            + " toggle=" + notifLine2 + " scaffold=" + bridgeActive + " bedProgress=" + bedProgress;
    }

    public boolean isDigging() { return !digId.isEmpty(); }

    public float digShown() { return digShown; }

    public String digText() { return digName + "  " + digBlock; }

    public String alertTitle() { return cardBedTitle; }

    public String alertDetail() { return cardBedDetail; }

    public String toggleText() { return lastToggleTime < 0 ? "" : notifLine2; }

    /** The card on screen: 0 capsule, 1 bed alert, 2 scaffold, 3 bed progress, 4 toggle, 5 digging. */
    public int cardShown() {
        if (digAlpha > 0.5f) return 5;
        if (alertAlpha > 0.5f) return 1;
        if (scaffoldAlpha > 0.5f) return 2;
        if (bedAlpha > 0.5f) return 3;
        if (notifAlpha > 0.5f) return 4;
        return 0;
    }

    /** The sample of your bed's defense: layers and route, as the overlay shows them. */
    public String defenseText() {
        if (own == null) return "no bed";
        StringBuilder sb = new StringBuilder(own.defense).append(' ');
        for (Map.Entry<Integer, Map<String, Integer>> e : own.layers.entrySet()) sb.append("L").append(e.getKey()).append(e.getValue()).append(' ');
        return sb.append(own.route).toString().trim();
    }
}
