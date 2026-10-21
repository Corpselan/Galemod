package gale.spotify.ui;

import gale.spotify.CoverCache;
import gale.util.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Album covers as textures (made on the game thread from CoverCache's pictures; the latest 48 kept). */
public final class Covers {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<String, ResourceLocation> TEXTURES = new LinkedHashMap<String, ResourceLocation>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> e) {
            if (size() <= 48) return false;
            mc.getTextureManager().deleteTexture(e.getValue());
            return true;
        }
    };
    private static int counter;

    private Covers() { }

    private static ResourceLocation texture(CoverCache cache, String url) {
        if (url == null) return null;
        ResourceLocation rl = TEXTURES.get(url);
        if (rl != null) return rl;
        BufferedImage img = cache.get(url);
        if (img == null) return null;
        rl = new ResourceLocation("galespotify", "cover/" + counter++);
        mc.getTextureManager().loadTexture(rl, new DynamicTexture(img));
        TEXTURES.put(url, rl);
        return rl;
    }

    /** Draws the cover (smoothly scaled), or a dark square with a note while it loads. */
    public static void draw(CoverCache cache, String url, float x, float y, float size, int alpha) {
        ResourceLocation rl = texture(cache, url);
        if (rl == null) {
            RoundRect.draw(x, y, x + size, y + size, Math.max(1f, size / 12f), (alpha << 24) | 0x282828);
            Icons.note(x + size / 2f, y + size / 2f, size * 0.45f, (Math.min(alpha, 160) << 24) | 0x8A8A8A);
            return;
        }
        mc.getTextureManager().bindTexture(rl);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, alpha / 255f);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + size, 0).tex(0, 1).endVertex();
        wr.pos(x + size, y + size, 0).tex(1, 1).endVertex();
        wr.pos(x + size, y, 0).tex(1, 0).endVertex();
        wr.pos(x, y, 0).tex(0, 0).endVertex();
        t.draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
