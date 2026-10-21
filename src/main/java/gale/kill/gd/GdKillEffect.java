package gale.kill.gd;

import com.google.gson.*;
import gale.Gale;
import gale.event.impl.Render2DEvent;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.ValorantStyleCatalog;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.impl.*;
import gale.kill.gd.client.sounds.ExternalSoundManager;
import gale.kill.gd.client.stats.ClientStatsManager;
import gale.kill.gd.compat.GuiGraphics;
import gale.kill.gd.common.KillType;
import gale.kill.gd.common.BonusType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The upstream preset renderers running on Gale's kill attribution and fixed-pipeline adapter. */
public final class GdKillEffect {
    public static final String[] PRESET_NAMES;
    public static final String[] PRESET_IDS;
    static {
        try (InputStream in = GdKillEffect.class.getResourceAsStream("/assets/gd656killicon/presets/index.json")) {
            if (in == null) throw new IOException("GD656 preset index missing");
            JsonArray index = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            PRESET_NAMES = new String[index.size()]; PRESET_IDS = new String[index.size()];
            for (int i = 0; i < index.size(); i++) {
                JsonObject entry = index.get(i).getAsJsonObject();
                PRESET_NAMES[i] = entry.get("name").getAsString(); PRESET_IDS[i] = entry.get("id").getAsString();
            }
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static boolean reloadHookInstalled;

    private final Map<String, IHudRenderer> renderers = new LinkedHashMap<>();
    private final Minecraft mc = Minecraft.getMinecraft();
    private String preset = "00009";
    private ISound playing;
    private int pendingStage;
    private long pendingTick;
    private final java.nio.IntBuffer savedClip = org.lwjgl.BufferUtils.createIntBuffer(16);

    public GdKillEffect() {
        if (!reloadHookInstalled && mc.getResourceManager() instanceof net.minecraft.client.resources.IReloadableResourceManager) {
            reloadHookInstalled = true;
            ((net.minecraft.client.resources.IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(resources -> {
                ValorantIconRenderer.clearProcessedTextureCache();
                gale.kill.gd.client.textures.ExternalTextureManager.clearCache();
            });
        }
        renderers.put("kill_icon/valorant", new ValorantIconRenderer());
        renderers.put("kill_icon/scrolling", new ScrollingIconRenderer());
        renderers.put("kill_icon/combo", new ComboIconRenderer());
        renderers.put("kill_icon/card", new CardRenderer());
        renderers.put("kill_icon/card_bar", new CardBarRenderer());
        renderers.put("kill_icon/battlefield1", new Battlefield1Renderer());
        renderers.put("subtitle/kill_feed", new SubtitleRenderer());
        renderers.put("subtitle/combo", ComboSubtitleRenderer.getInstance());
        renderers.put("subtitle/bonus_list", BonusListRenderer.getInstance());
        renderers.put("subtitle/score", ScoreSubtitleRenderer.getInstance());
    }

    public void select(int index) {
        String id = PRESET_IDS[Math.max(0, Math.min(PRESET_IDS.length - 1, index))];
        boolean changed = !id.equals(preset);
        if (changed) { clear(); preset = id; }
        ConfigManager.select(preset);
        if (changed) ValorantIconRenderer.prepareTextures(preset, ConfigManager.getElementConfig(preset, "kill_icon/valorant", true));
    }

    public void trigger(int stage, String victim) {
        ConfigManager.select(preset);
        ClientStatsManager.kills++;
        IHudRenderer.TriggerContext context = IHudRenderer.TriggerContext.of(KillType.NORMAL, -1, stage, victim == null ? "目标" : victim);
        for (Map.Entry<String, IHudRenderer> entry : renderers.entrySet()) {
            JsonObject config = ConfigManager.getElementConfig(preset, entry.getKey(), true);
            if (config == null || (config.has("visible") && !config.get("visible").getAsBoolean())) continue;
            if (entry.getKey().equals("subtitle/bonus_list")) {
                // Cosmetic local kill score only; this is not the upstream server's scoreboard or combat data.
                entry.getValue().trigger(IHudRenderer.TriggerContext.of(BonusType.KILL, -1, stage, "5||" + victim));
            } else if (entry.getKey().equals("subtitle/score")) {
                ScoreSubtitleRenderer.getInstance().addScore(5);
            } else entry.getValue().trigger(context);
        }
        pendingStage = stage; pendingTick = gale.core.Scheduler.ticks();
    }

    /** Upstream renderers request a sound; Gale coalesces all requests into one cue on the next tick. */
    public static void queueSound(String ignored) { }

    public void tick(boolean sound, float volume) {
        ExternalSoundManager.enabled = sound; ExternalSoundManager.volume = volume;
        if (!sound) stopSound();
        if (pendingStage != 0 && gale.core.Scheduler.ticks() > pendingTick) {
            int stage = pendingStage; pendingStage = 0; stopSound();
            if (sound) playing = ExternalSoundManager.play(soundName(preset, stage));
        }
    }

    public static String soundName(String id, int stage) {
        ValorantStyleCatalog.StyleSpec spec = ValorantStyleCatalog.getPresetSpec(id);
        if (spec != null) return ValorantStyleCatalog.getComboSoundBaseName(id, null, stage);
        if (id.equals("00002")) return "combokillsound_" + Math.max(1, Math.min(6, stage)) + "_cf";
        if (id.equals("00003")) return "cardkillsound_default_cs";
        if (id.equals("00004")) return "killsound_bf1";
        if (id.equals("00007") || id.equals("00036")) return "killsound_bf5";
        if (id.equals("00037")) return "killsound_bf6";
        return "killsound_df";
    }

    public void render(Render2DEvent event, float x, float y, float scale) {
        if (mc.gameSettings.hideGUI) return;
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D), write = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        java.nio.IntBuffer clip = savedClip;
        clip.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, clip);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        int sr = GL11.glGetInteger(0x80C9), dr = GL11.glGetInteger(0x80C8), sa = GL11.glGetInteger(0x80CB), da = GL11.glGetInteger(0x80CA);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0); GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-event.width() / 2f, -event.height() + 80, 0);
        GlStateManager.disableDepth(); GlStateManager.depthMask(false); GlStateManager.disableAlpha(); GlStateManager.disableCull();
        GlStateManager.enableBlend(); GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); GL11.glShadeModel(GL11.GL_SMOOTH);
        try {
            ConfigManager.select(preset);
            GuiGraphics graphics = new GuiGraphics(x - event.width() / 2f * scale, y - (event.height() - 80) * scale, scale);
            for (Map.Entry<String, IHudRenderer> entry : renderers.entrySet()) {
                JsonObject config = ConfigManager.getElementConfig(preset, entry.getKey(), true);
                if (config == null || (config.has("visible") && !config.get("visible").getAsBoolean())) continue;
                entry.getValue().render(graphics, event.partialTicks);
            }
        } finally {
            GlStateManager.popMatrix(); GlStateManager.color(1, 1, 1, 1); GL11.glShadeModel(shade);
            GlStateManager.depthMask(write);
            if (depth) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (texture) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.tryBlendFuncSeparate(sr, dr, sa, da);
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.bindTexture(bound);
            GL11.glScissor(clip.get(0), clip.get(1), clip.get(2), clip.get(3));
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    public void clear() {
        for (IHudRenderer renderer : renderers.values()) renderer.resetPreview();
        pendingStage = 0; ClientStatsManager.kills = 0; stopSound();
        // The bounded processed-texture cache stays warm across kills and preset switches.
    }

    private void stopSound() { if (playing != null) { mc.getSoundHandler().stopSound(playing); playing = null; } }
}
