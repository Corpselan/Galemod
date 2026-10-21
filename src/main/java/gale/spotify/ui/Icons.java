package gale.spotify.ui;

import gale.util.RoundRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** The player's icons, drawn as shapes (centred on cx, cy; s is the icon's size), and text that is never colour-coded. */
public final class Icons {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Icons() { }

    private static void begin(int color) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color((color >> 16 & 0xFF) / 255f, (color >> 8 & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
    }

    private static void end() {
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void triangle(float x1, float y1, float x2, float y2, float x3, float y3, int color) {
        begin(color);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION);
        wr.pos(x1, y1, 0).endVertex();
        wr.pos(x2, y2, 0).endVertex();
        wr.pos(x3, y3, 0).endVertex();
        t.draw();
        end();
    }

    public static void line(float x1, float y1, float x2, float y2, float width, int color) {
        begin(color);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(Math.max(1f, width * new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor()));
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        wr.pos(x1, y1, 0).endVertex();
        wr.pos(x2, y2, 0).endVertex();
        t.draw();
        GL11.glLineWidth(1f);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        end();
    }

    public static void play(float cx, float cy, float s, int color) {
        float h = s / 2f, w = s * 0.42f;
        triangle(cx - w * 0.8f, cy - h, cx - w * 0.8f, cy + h, cx + w * 1.2f, cy, color);
    }

    public static void pause(float cx, float cy, float s, int color) {
        float h = s / 2f, w = s * 0.22f, gap = s * 0.14f;
        RoundRect.draw(cx - gap - w, cy - h, cx - gap, cy + h, w * 0.3f, color);
        RoundRect.draw(cx + gap, cy - h, cx + gap + w, cy + h, w * 0.3f, color);
    }

    /** next (forward) or previous */
    public static void skip(float cx, float cy, float s, boolean forward, int color) {
        float h = s / 2f, w = s * 0.55f, bar = s * 0.14f;
        if (forward) {
            triangle(cx - w * 0.6f, cy - h, cx - w * 0.6f, cy + h, cx + w * 0.5f, cy, color);
            RoundRect.draw(cx + w * 0.5f, cy - h, cx + w * 0.5f + bar, cy + h, bar * 0.4f, color);
        } else {
            triangle(cx + w * 0.6f, cy - h, cx + w * 0.6f, cy + h, cx - w * 0.5f, cy, color);
            RoundRect.draw(cx - w * 0.5f - bar, cy - h, cx - w * 0.5f, cy + h, bar * 0.4f, color);
        }
    }

    public static void shuffle(float cx, float cy, float s, int color) {
        float h = s * 0.38f, w = s / 2f, lw = Math.max(1f, s / 9f);
        line(cx - w, cy - h, cx + w * 0.7f, cy + h, lw, color);
        line(cx - w, cy + h, cx + w * 0.7f, cy - h, lw, color);
        float a = s * 0.22f;
        triangle(cx + w, cy + h, cx + w - a, cy + h - a * 0.9f, cx + w - a, cy + h + a * 0.9f, color);
        triangle(cx + w, cy - h, cx + w - a, cy - h - a * 0.9f, cx + w - a, cy - h + a * 0.9f, color);
    }

    /** the loop; one: repeat this song */
    public static void repeat(float cx, float cy, float s, boolean one, int color) {
        float h = s * 0.32f, w = s / 2f, lw = Math.max(1f, s / 9f);
        line(cx - w, cy - h, cx + w, cy - h, lw, color);
        line(cx + w, cy - h, cx + w, cy + h, lw, color);
        line(cx + w, cy + h, cx - w, cy + h, lw, color);
        line(cx - w, cy + h, cx - w, cy - h * 0.2f, lw, color);
        float a = s * 0.2f;
        triangle(cx + w * 0.15f + a, cy - h, cx + w * 0.15f, cy - h - a, cx + w * 0.15f, cy - h + a, color);
        if (one) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0);
            GlStateManager.scale(s / 16f, s / 16f, 1f);
            mc.fontRendererObj.drawString("1", -2.5f, -3.5f, color, false);
            GlStateManager.popMatrix();
        }
    }

    public static void speaker(float cx, float cy, float s, int volume, int color) {
        float h = s / 2f;
        RoundRect.draw(cx - h, cy - h * 0.35f, cx - h * 0.4f, cy + h * 0.35f, 0.5f, color);
        triangle(cx - h * 0.45f, cy - h * 0.35f, cx - h * 0.45f, cy + h * 0.35f, cx, cy + h * 0.85f, color);
        triangle(cx - h * 0.45f, cy - h * 0.35f, cx, cy + h * 0.85f, cx, cy - h * 0.85f, color);
        if (volume > 0) RoundRect.draw(cx + h * 0.25f, cy - h * 0.3f, cx + h * 0.4f, cy + h * 0.3f, 0.5f, color);
        if (volume > 50) RoundRect.draw(cx + h * 0.6f, cy - h * 0.6f, cx + h * 0.75f, cy + h * 0.6f, 0.5f, color);
    }

    /** a music note (while a cover loads) */
    public static void note(float cx, float cy, float s, int color) {
        float r = s * 0.22f;
        RoundRect.circle(cx - s * 0.18f, cy + s * 0.3f, r, color);
        RoundRect.draw(cx - s * 0.18f + r * 0.6f, cy - s * 0.45f, cx - s * 0.18f + r * 1.0f, cy + s * 0.3f, 0.3f, color);
        RoundRect.draw(cx - s * 0.18f + r * 0.6f, cy - s * 0.45f, cx + s * 0.35f, cy - s * 0.3f, 0.3f, color);
    }

    public static void heart(float cx, float cy, float s, int color) {
        float r = s * 0.27f;
        RoundRect.circle(cx - r * 0.85f, cy - r * 0.4f, r, color);
        RoundRect.circle(cx + r * 0.85f, cy - r * 0.4f, r, color);
        triangle(cx - r * 1.8f, cy - r * 0.15f, cx + r * 1.8f, cy - r * 0.15f, cx, cy + s * 0.5f, color);
    }

    // ------------------------------------------------------------------ text: song names can contain '&' and '§', drawn as they are

    public static float text(String s, float x, float y, int color) {
        mc.fontRendererObj.drawString(s.replace('§', '?'), x, y, color, false);
        return x + width(s);
    }

    public static int width(String s) { return mc.fontRendererObj.getStringWidth(s.replace('§', '?')); }

    /** Cut with "…" to fit. */
    public static String fit(String s, float max) {
        FontRenderer fr = mc.fontRendererObj;
        String clean = s.replace('§', '?');
        if (fr.getStringWidth(clean) <= max) return clean;
        return fr.trimStringToWidth(clean, (int) Math.max(0, max - fr.getStringWidth("…"))) + "…";
    }

    public static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }
}
