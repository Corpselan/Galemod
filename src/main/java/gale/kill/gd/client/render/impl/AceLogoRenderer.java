/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.client.render.impl;


import gale.kill.gd.compat.RenderSystem;
import net.minecraft.client.Minecraft;
import gale.kill.gd.compat.GuiGraphics;
import net.minecraft.util.ResourceLocation;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.Gd656killicon;
import gale.kill.gd.client.config.ClientConfigManager;
import gale.kill.gd.client.render.IHudRenderer;

public class AceLogoRenderer implements IHudRenderer {
    private static final ResourceLocation TEXTURE = new ResourceLocation(Gd656killicon.MODID, "icon/ace_logo.png");
    private static final long DISPLAY_DURATION_MS = 10000L;
    private static final long FADE_OUT_DURATION_MS = 600L;
    private static final int LOGO_WIDTH = 74;
    private static final int LOGO_HEIGHT = 25;

    private long startTime = -1L;
    private boolean isVisible = false;

    @Override
    public void render(GuiGraphics guiGraphics, float partialTick) {
        if (!isVisible || startTime < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        if (elapsed >= DISPLAY_DURATION_MS + FADE_OUT_DURATION_MS) {
            isVisible = false;
            startTime = -1L;
            return;
        }
        float alpha = 1.0f;
        if (elapsed > DISPLAY_DURATION_MS) {
            float t = (float) (elapsed - DISPLAY_DURATION_MS) / (float) FADE_OUT_DURATION_MS;
            t = Mth.clamp(t, 0.0f, 1.0f);
            float fade = 1.0f - t;
            alpha = fade * fade;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int screenWidth = new net.minecraft.client.gui.ScaledResolution(mc).getScaledWidth();
        int screenHeight = new net.minecraft.client.gui.ScaledResolution(mc).getScaledHeight();
        int drawX = screenWidth - LOGO_WIDTH;
        int drawY = screenHeight - LOGO_HEIGHT;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        guiGraphics.blit(TEXTURE, drawX, drawY, 0, 0, LOGO_WIDTH, LOGO_HEIGHT, LOGO_WIDTH, LOGO_HEIGHT);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    @Override
    public void trigger(TriggerContext context) {
        if (!ClientConfigManager.isEnableAceLag()) {
            return;
        }
        startTime = System.currentTimeMillis();
        isVisible = true;
    }
}
