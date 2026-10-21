package gale.module.impl.render;

import gale.Gale;
import gale.kill.gd.GdKillEffect;
import gale.core.Scheduler;
import gale.event.Priority;
import gale.event.impl.AttackEvent;
import gale.event.impl.ChatEvent;
import gale.event.impl.EntityStatusEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.Render3DEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.WorldEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StringUtils;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Champions HUD medals and the world-space end tree share the same kill attribution. */
public final class KillEffects extends Module {
    private static final int MAX_TARGETS = 32;
    private static final long HIT_WINDOW = 160; // eight seconds, game ticks
    private static final String[] YEARS = {"2021", "2023", "2024"};
    private static final ResourceLocation[][] TEXTURES = new ResourceLocation[YEARS.length][6];
    private static final ResourceLocation[][] SOUNDS = new ResourceLocation[YEARS.length][6];
    private static final float[] GLOW_POS = {-150f, -130f, 130f, 150f};
    private static final float[] GLOW_UV = {0f, 20f / 300f, 280f / 300f, 1f};
    private static final float[] GLOW_ALPHA = {0f, 1f, 1f, 0f};
    private static final Pattern DEATH_LINE = Pattern.compile(
        "^(?:\\[[^\\]]{1,24}\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+was "
            + "(?:killed|slain|shot|pummeled|burnt to a crisp|blown up|thrown.{0,40}|knocked.{0,40})"
            + "\\s+by\\s+(?:\\[[^\\]]{1,24}\\]\\s*)?([A-Za-z0-9_]{1,16})(?:\\b.*)$",
        Pattern.CASE_INSENSITIVE);

    static {
        for (int theme = 0; theme < YEARS.length; theme++) {
            String year = YEARS[theme].substring(2);
            for (int i = 0; i < 6; i++) {
                TEXTURES[theme][i] = new ResourceLocation(Gale.ID, "textures/kill/champions" + year + "_" + (i + 1) + ".png");
                SOUNDS[theme][i] = new ResourceLocation(Gale.ID, "kill.champions" + year + "." + (i + 1));
            }
        }
    }

    private final ModeSetting theme = mode("Theme", YEARS[0], "2021", "2023", "2024", "end", "GD656")
        .hideModes(2, "2021", "2023", "2024")
        .hideModes(3, "end")
        .describe("Champions 图标、end 红叶白树，或 GD656 原版图标动画音效预设");
    private final ModeSetting gdPreset = mode("GD preset", GdKillEffect.PRESET_NAMES[8], GdKillEffect.PRESET_NAMES)
        .describe("GD656 共 42 套预设，含 CYRAX、Reaver、Forsaken、Mystbloom、Ayakashi").visibleWhen(() -> theme.is("GD656"));
    private final BoolSetting sound = bool("Sound", true).describe("播放当前预设对应击杀等级的音效").visibleWhen(() -> !theme.is("end"));
    private final NumberSetting volume = number("Volume", 0.7, 0, 1, 0.05).visibleWhen(() -> !theme.is("end") && sound.get());
    private final NumberSetting size = number("Size", 96, 64, 200, 4).describe("完整贴图的大小，GUI 像素").visibleWhen(() -> !theme.is("end"));
    private final NumberSetting x = number("X", 50, 5, 95, 1).suffix("%").visibleWhen(() -> !theme.is("end"));
    private final NumberSetting y = number("Y", 66, 10, 90, 1).suffix("%").visibleWhen(() -> !theme.is("end"));
    private final NumberSetting duration = number("Duration", 2.2, 0.6, 5, 0.1).suffix("s").visibleWhen(() -> !theme.is("end") && !theme.is("GD656"));
    private final NumberSetting treeSize = number("Tree size", 1, .5, 2, .05)
        .describe("end 透明动画贴片比例；1 时约 8 格高").visibleWhen(() -> theme.is("end"));
    private final NumberSetting treeLifetime = number("Tree lifetime", 8, 3, 16, .5).suffix("s")
        .describe("end 从红色旋涡到树木淡出的总时长").visibleWhen(() -> theme.is("end"));
    private final NumberSetting resetAfter = number("Reset after", 0, 0, 120, 1).suffix("s")
        .describe("多久没有击杀后重置；0 为只在死亡、换世界或手动重置时清零");
    private final BoolSetting playersOnly = bool("Players only", true).describe("只对玩家触发；关闭后也可击杀生物");
    private final ModeSetting detection = mode("Detection", "Auto", "Auto", "Server")
        .describe("Auto：近期攻击目标死亡或服务器击杀消息；Server：只认支持格式的击杀消息，更严格");

    private final KillEffectAnimation animation = new KillEffectAnimation();
    private final EndKillEffect endTree = new EndKillEffect();
    private final GdKillEffect gdEffect = new GdKillEffect();
    private String gdVictim = "Preview";
    private final Map<UUID, Target> targets = new LinkedHashMap<>();
    private final Map<String, Long> messages = new LinkedHashMap<>();
    private WorldClient trackedWorld;
    private EntityPlayerSP trackedPlayer;
    private int streak, previewGeneration;
    private volatile int sessionGeneration;
    private long lastKillTick;
    private ISound playingSound;
    private int pendingSoundStage;
    private long pendingSoundTick;

    private static final class Target {
        final EntityLivingBase entity;
        long attacked;
        boolean credited;
        Vec3 position, deathPosition;

        Target(EntityLivingBase entity, long attacked) {
            this.entity = entity; this.attacked = attacked; this.position = positionOf(entity);
        }
    }

    private static final class MedalSound extends PositionedSound {
        MedalSound(ResourceLocation location, float volume) {
            super(location);
            this.volume = volume;
            this.pitch = 1f;
            this.attenuationType = ISound.AttenuationType.NONE;
        }
    }

    // the kill picture as a HUD element (open the chat to move or resize it; it shows where the picture appears)
    private final gale.hud.HudElement hud = new gale.hud.HudElement("killeffects", "KillEffects", this) {
        @Override
        public float[] bounds(float sw, float sh) {
            float s = size.getFloat();
            return new float[]{ sw * x.getFloat() / 100f - s / 2f, sh * y.getFloat() / 100f - s / 2f, s, s };
        }

        @Override
        public void moveTo(float nx, float ny, float sw, float sh) {
            float s = size.getFloat();
            x.set(Math.max(5, Math.min(95, Math.round((nx + s / 2f) / sw * 100f))));
            y.set(Math.max(10, Math.min(90, Math.round((ny + s / 2f) / sh * 100f))));
        }

        @Override
        public float scale() { return size.getFloat() / 96f; }

        @Override
        public void scaleTo(float s, float sw, float sh) { size.set(Math.max(64, Math.min(200, Math.round(s * 96f / 4f) * 4))); }

        @Override
        public float minScale() { return 64f / 96f; }

        @Override
        public float maxScale() { return 200f / 96f; }

        @Override
        public void reset() {
            x.reset();
            y.reset();
            size.reset();
        }
    };

    public KillEffects() {
        super("KillEffects", Category.RENDER, "Champions 图标音效、end 红叶白树、GD656 的 37 套击杀预设");
        gale.hud.HudLayout.register(hud);
        hud.showWhen(() -> !theme.is("end"));
        on(AttackEvent.class, Priority.MONITOR, e -> { if (!e.isCancelled()) attacked(e.target); });
        // Packet-only attacks from companion clients also count. World access stays on the game thread.
        onAlways(PacketEvent.Send.class, Priority.MONITOR, e -> {
            C02PacketUseEntity p = e.as(C02PacketUseEntity.class);
            if (e.isCancelled() || p == null || p.getAction() != C02PacketUseEntity.Action.ATTACK) return;
            WorldClient world = mc.theWorld;
            int generation = sessionGeneration;
            Scheduler.main(() -> {
                if (isActive() && mc.theWorld == world && generation == sessionGeneration)
                    attacked(p.getEntityFromWorld(world));
            });
        });
        on(EntityStatusEvent.class, Priority.MONITOR, e -> {
            if (e.isCancelled() || e.status != 3) return;
            if (e.entity == mc.thePlayer) { clear(); return; }
            if (e.entity != null) died(e.entity);
        });
        on(ChatEvent.class, Priority.MONITOR, this::chat);
        onAlways(TickEvent.Post.class, Priority.NORMAL, e -> tick());
        onAlways(WorldEvent.Load.class, Priority.NORMAL, e -> clear());
        onAlways(WorldEvent.Unload.class, Priority.NORMAL, e -> clear());
        on(Render2DEvent.class, this::draw);
        on(Render3DEvent.class, e -> { if (theme.is("end")) endTree.render(e.partialTicks); });
        gdPreset.onChange(v -> { gdEffect.clear(); gdEffect.select(gdPreset.index()); });
        sound.onChange(v -> { if (!v) { pendingSoundStage = 0; stopSound(); } });
        playersOnly.onChange(v -> clear());
        detection.onChange(v -> clear());
        theme.onChange(v -> {
            // Stop the previous theme without erasing the real streak.
            previewGeneration++;
            animation.clear();
            endTree.dispose();
            gdEffect.clear();
            pendingSoundStage = 0;
            stopSound();
            if (isEnabled()) preloadTextures();
        });
    }

    @Override
    protected void onEnable() {
        clear();
        preloadTextures();
    }

    private void preloadTextures() {
        if (theme.is("GD656")) { gdEffect.select(gdPreset.index()); return; }
        if (theme.is("end")) { endTree.prepare(); return; }
        // Six small native textures, uploaded once, away from the kill/render hot path.
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try { for (ResourceLocation texture : TEXTURES[theme.index()]) mc.getTextureManager().bindTexture(texture); }
        finally { GlStateManager.bindTexture(bound); }
    }

    @Override
    protected void onDisable() { clear(); endTree.dispose(); }

    private void clear() {
        targets.clear();
        messages.clear();
        streak = 0;
        lastKillTick = 0;
        trackedWorld = mc.theWorld;
        trackedPlayer = mc.thePlayer;
        previewGeneration++;
        sessionGeneration++;
        animation.clear();
        endTree.clear();
        gdEffect.clear();
        gdVictim = "Preview";
        pendingSoundStage = 0;
        stopSound();
    }

    private void stopSound() {
        if (playingSound != null) mc.getSoundHandler().stopSound(playingSound);
        playingSound = null;
    }

    private boolean allowed(Entity e) {
        return e instanceof EntityLivingBase && e != mc.thePlayer && !(e instanceof EntityArmorStand)
            && (!playersOnly.get() || e instanceof EntityPlayer);
    }

    private void attacked(Entity e) {
        if (!allowed(e) || mc.thePlayer == null || mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) return;
        EntityLivingBase living = (EntityLivingBase) e;
        if (living.getHealth() <= 0 || living.deathTime > 0) return;
        Target t = targets.get(e.getUniqueID());
        if (t == null || t.entity != e) {
            trim(targets, MAX_TARGETS);
            targets.put(e.getUniqueID(), new Target(living, Scheduler.ticks()));
            messages.remove(e.getName().toLowerCase(Locale.ROOT));
        } else { t.attacked = Scheduler.ticks(); t.position = positionOf(e); }
    }

    private void died(Entity e) {
        Target t = targets.get(e.getUniqueID());
        if (t != null && t.entity == e && !t.credited && Scheduler.ticks() - t.attacked <= HIT_WINDOW && allowed(e)) {
            if (t.deathPosition == null) t.deathPosition = positionOf(e);
            if (detection.is("Auto")) {
                t.credited = true;
                credit(e.getName(), t.deathPosition);
            }
        }
    }

    private static Vec3 positionOf(Entity e) { return new Vec3(e.posX, e.posY, e.posZ); }

    private void credit(String victim, Vec3 position) {
        String name = victim.toLowerCase(Locale.ROOT);
        Long last = messages.get(name);
        if (last != null && Scheduler.ticks() - last <= HIT_WINDOW) return;
        trim(messages, 64);
        messages.put(name, Scheduler.ticks());
        if (resetAfter.get() > 0 && Scheduler.ticks() - lastKillTick > resetAfter.get() * 20) streak = 0;
        streak = Math.min(6, streak + 1);
        lastKillTick = Scheduler.ticks();
        previewGeneration++; // real kills take priority over a pending preview sequence
        if (theme.is("end")) endTree.spawn(position, treeLifetime.get(), treeSize.get());
        else { gdVictim = victim; show(streak); }
    }

    private static <K, V> void trim(Map<K, V> map, int limit) {
        if (map.size() < limit) return;
        Iterator<K> it = map.keySet().iterator();
        if (it.hasNext()) { it.next(); it.remove(); }
    }

    private void tick() {
        if (trackedWorld != mc.theWorld || trackedPlayer != mc.thePlayer) clear();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            if (streak != 0 || !targets.isEmpty() || animation.current() != 0 || theme.is("end") || theme.is("GD656")) clear();
            return;
        }
        if (mc.isGamePaused()) return;
        endTree.tick();
        if (theme.is("GD656")) gdEffect.tick(sound.get(), volume.getFloat());
        long now = Scheduler.ticks();
        if (resetAfter.get() > 0 && streak > 0 && now - lastKillTick > resetAfter.get() * 20) streak = 0;
        Iterator<Target> it = targets.values().iterator();
        while (it.hasNext()) {
            Target t = it.next();
            if (now - t.attacked > HIT_WINDOW) { it.remove(); continue; }
            // Entity disappearance/unloading alone is never a kill.
            if (!t.credited && (t.entity.deathTime > 0 || t.entity.getHealth() <= 0)) died(t.entity);
            else if (!t.credited && mc.theWorld.getEntityByID(t.entity.getEntityId()) == t.entity)
                t.position = positionOf(t.entity);
        }
        messages.values().removeIf(at -> now - at > HIT_WINDOW);
        flushSound(now);
    }

    private void chat(ChatEvent e) {
        if (e.type == 2 || mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.getHealth() <= 0) return;
        if (translated(e.getMessage())) return;
        String line = StringUtils.stripControlCodes(e.text()).trim();
        Matcher m = DEATH_LINE.matcher(line);
        if (m.matches() && m.group(2).equalsIgnoreCase(mc.thePlayer.getName())) confirmed(m.group(1));
    }

    private boolean translated(IChatComponent component) {
        if (component instanceof ChatComponentTranslation) {
            ChatComponentTranslation t = (ChatComponentTranslation) component;
            Object[] args = t.getFormatArgs();
            if (t.getKey().startsWith("death.") && args.length >= 2
                && plain(args[1]).equalsIgnoreCase(mc.thePlayer.getName())) {
                confirmed(plain(args[0]));
                return true;
            }
        }
        for (IChatComponent sibling : component.getSiblings()) if (translated(sibling)) return true;
        return false;
    }

    private static String plain(Object value) {
        return StringUtils.stripControlCodes(value instanceof IChatComponent
            ? ((IChatComponent) value).getUnformattedText() : String.valueOf(value));
    }

    private void confirmed(String victim) {
        if (victim.equalsIgnoreCase(mc.thePlayer.getName())) return;
        for (Target t : targets.values()) {
            if (t.entity.getName().equalsIgnoreCase(victim)) {
                if (!allowed(t.entity) || t.credited) return;
                t.credited = true;
                Vec3 position = t.deathPosition != null ? t.deathPosition
                    : mc.theWorld.getEntityByID(t.entity.getEntityId()) == t.entity ? positionOf(t.entity) : t.position;
                credit(victim, position);
                return;
            }
        }
        // A server kill message can confirm an arrow/void kill with no recent local melee hit.
        // With Players only on, require the name to be an actual player in the tab list.
        if (playersOnly.get() && (mc.getNetHandler() == null || mc.getNetHandler().getPlayerInfo(victim) == null)) return;
        EntityPlayer entity = mc.theWorld.getPlayerEntityByName(victim);
        // A message without a known victim position can count a medal, but cannot place a tree.
        credit(victim, entity == null ? null : positionOf(entity));
    }

    private void show(int stage) {
        if (theme.is("GD656")) { gdEffect.select(gdPreset.index()); gdEffect.trigger(stage, gdVictim); return; }
        if (theme.is("end")) {
            if (mc.thePlayer == null || mc.theWorld == null) return;
            Vec3 look = mc.thePlayer.getLookVec();
            double length = Math.sqrt(look.xCoord * look.xCoord + look.zCoord * look.zCoord);
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            double dx = length > .001 ? look.xCoord / length : -Math.sin(yaw);
            double dz = length > .001 ? look.zCoord / length : Math.cos(yaw);
            Vec3 position = new Vec3(mc.thePlayer.posX + dx * 5, mc.thePlayer.posY, mc.thePlayer.posZ + dz * 5);
            MovingObjectPosition ground = mc.theWorld.rayTraceBlocks(position.addVector(0, 2, 0), position.addVector(0, -8, 0), false, true, false);
            if (ground != null && ground.hitVec != null) position = ground.hitVec;
            endTree.spawn(position, treeLifetime.get(), treeSize.get());
            return;
        }
        animation.show(stage, System.nanoTime(), duration.get() * 1000);
        // Coalesce same-tick multikills before handing a source to the legacy asynchronous sound engine.
        // Stopping a source immediately after queuing it can hit OpenAL before it has a channel.
        pendingSoundStage = stage;
        pendingSoundTick = Scheduler.ticks();
    }

    private void flushSound(long now) {
        if (pendingSoundStage == 0 || now <= pendingSoundTick) return;
        int stage = pendingSoundStage;
        pendingSoundStage = 0;
        stopSound();
        if (!theme.is("end") && !theme.is("GD656") && sound.get() && volume.get() > 0) {
            playingSound = new MedalSound(SOUNDS[theme.index()][stage - 1], volume.getFloat());
            mc.getSoundHandler().playSound(playingSound);
        }
    }

    /** Local preview: it does not change the real kill streak or send packets. */
    public void preview(int stage) {
        if (isActive()) show(Math.max(1, Math.min(6, stage)));
    }

    public void previewAll() {
        if (theme.is("end")) { previewGeneration++; preview(1); return; }
        int generation = ++previewGeneration;
        for (int i = 1; i <= 6; i++) {
            final int stage = i;
            Scheduler.later((i - 1) * 15 + 1, () -> {
                if (isActive() && generation == previewGeneration) preview(stage);
            });
        }
    }

    public void resetStreak() { clear(); }

    public int streak() { return streak; }

    @Override
    public String getSuffix() { return theme.get() + (streak == 0 ? "" : " " + streak + (streak == 6 ? "+" : "")); }

    private void draw(Render2DEvent e) {
        if (theme.is("end")) return;
        if (theme.is("GD656")) {
            gdEffect.render(e, e.width() * x.getFloat() / 100f, e.height() * y.getFloat() / 100f, size.getFloat() / 96f);
            return;
        }
        long now = System.nanoTime();
        double durationMs = duration.get() * 1000;
        if (mc.gameSettings.hideGUI || !animation.visible(now, durationMs)) return;
        float centerX = e.width() * x.getFloat() / 100f;
        float centerY = e.height() * y.getFloat() / 100f;
        hud.drawn();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int srcRgb = GL11.glGetInteger(GL14_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14_BLEND_DST_ALPHA);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
        try {
            GlStateManager.translate(centerX, centerY, 0);
            float exit = animation.exitScale(now, durationMs);
            quad(animation.previous(), size.getFloat() * animation.outgoingScale(now) * exit, animation.outgoingAlpha(now, durationMs));
            quad(animation.current(), size.getFloat() * animation.scale(now) * exit, animation.incomingAlpha(now, durationMs));
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.depthMask(depthWrite);
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (texture) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.bindTexture(bound);
        }
    }

    private static final int GL14_BLEND_SRC_RGB = 0x80C9, GL14_BLEND_DST_RGB = 0x80C8;
    private static final int GL14_BLEND_SRC_ALPHA = 0x80CB, GL14_BLEND_DST_ALPHA = 0x80CA;

    private void quad(int stage, float size, float alpha) {
        if (stage < 1 || alpha <= 0.003f) return;
        mc.getTextureManager().bindTexture(TEXTURES[theme.index()][stage - 1]);
        GlStateManager.color(1f, 1f, 1f, alpha);
        // Keep sub-pixel placement and size smooth at high FPS.
        GlStateManager.pushMatrix();
        GlStateManager.scale(size / 300f, size / 300f, 1f);
        if (theme.is("2024") && stage >= 5) glow(alpha);
        else Gui.drawModalRectWithCustomSizedTexture(-150, -150, 0, 0, 300, 300, 300, 300);
        GlStateManager.popMatrix();
    }

    /** Feather the supplied glow's cropped edges in one draw, without a shader or dynamic texture. */
    private static void glow(float alpha) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            glowVertex(renderer, col, row + 1, alpha);
            glowVertex(renderer, col + 1, row + 1, alpha);
            glowVertex(renderer, col + 1, row, alpha);
            glowVertex(renderer, col, row, alpha);
        }
        tessellator.draw();
    }

    private static void glowVertex(WorldRenderer renderer, int col, int row, float alpha) {
        renderer.pos(GLOW_POS[col], GLOW_POS[row], 0).tex(GLOW_UV[col], GLOW_UV[row])
            .color(1f, 1f, 1f, alpha * GLOW_ALPHA[col] * GLOW_ALPHA[row]).endVertex();
    }
}
