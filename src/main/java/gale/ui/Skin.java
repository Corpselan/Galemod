package gale.ui;

import gale.util.ColorUtil;
import gale.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The block look of Gale's screens: an oak frame with transparent glass panes and small pixel controls on the
 * glass. Both textures come from the resource pack in use. One texel is one screen unit (a block is 16 units), so
 * at the whole-number scales the screen uses the pixels stay sharp.
 */
final class Skin {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ResourceLocation PLANKS = new ResourceLocation("textures/blocks/planks_oak.png");
    private static final ResourceLocation GLASS = new ResourceLocation("textures/blocks/glass.png");
    static final float BLOCK = 16;

    // Clear glass: only a faint tint; the world remains visible through the panes.
    static final int GLASS_FILL = 0x0CE3F3F6, GLASS_HOVER = 0x20EEF9FB, GLASS_SELECTED = 0x403D9DC0, GLASS_INSET = 0x240C2029;
    static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xFFE0EEF2, TEXT_3 = 0xFFBCCED5;
    static final int ACCENT = 0xFF2A93BF, ACCENT_DARK = 0xFF72D1F5, WARN = 0xFFFFC66B;
    static final int LINE = 0x40C0DDE5, CONTROL = 0x70628693, CONTROL_HOVER = 0xB086B3C0, FIELD = 0x600C2029, TRACK_OFF = 0xB043606D;

    /** Glass drawn as whole tiled blocks instead of one pane with a frame. */
    static boolean tiledGlass;

    private Skin() { }

    // ------------------------------------------------------------------ blocks

    /** Oak planks filling the rectangle, the blocks lined up from (originX, originY). */
    static void planks(float x, float y, float w, float h, float originX, float originY) {
        bind(PLANKS);
        quad(x, y, x + w, y + h, (x - originX) / BLOCK, (y - originY) / BLOCK, (x + w - originX) / BLOCK, (y + h - originY) / BLOCK);
        GlStateManager.disableBlend();
    }

    /**
     * A transparent glass pane. Framed connects the glass into one seamless pane, keeping only the outer frame
     * and corner streaks from the game's glass texture. Tiled is the optional individual-block look.
     */
    static void glass(float x, float y, float w, float h, int fill) {
        RenderUtil.rect(x, y, x + w, y + h, fill);
        bind(GLASS);
        float t = 1 / 16f, u = w / BLOCK, v = h / BLOCK;
        if (tiledGlass) {
            quad(x, y, x + w, y + h, 0, 0, u, v);
        } else {
            int n = (int) Math.min(7, Math.floor((Math.min(w, h) - 2) / 2));
            if (n > 0) {
                quad(x + 1, y + 1, x + 1 + n, y + 1 + n, t, t, t * (1 + n), t * (1 + n));
                quad(x + w - 1 - n, y + h - 1 - n, x + w - 1, y + h - 1, t * (15 - n), t * (15 - n), t * 15, t * 15);
            }
            quad(x, y, x + w, y + 1, 0, 0, u, t);
            quad(x, y, x + 1, y + h, 0, 0, t, v);
            quad(x, y + h - 1, x + w, y + h, 0, 15 * t, u, 1);
            quad(x + w - 1, y, x + w, y + h, 15 * t, 0, 1, v);
        }
        GlStateManager.disableBlend();
    }

    /** The shadow a pane casts on the wood, down and to the right. */
    static void shade(float x, float y, float w, float h) {
        RenderUtil.rect(x + 2, y + h, x + w + 2, y + h + 2, 0x48000000);
        RenderUtil.rect(x + w, y + 2, x + w + 2, y + h, 0x48000000);
    }

    private static void bind(ResourceLocation texture) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        mc.getTextureManager().bindTexture(texture);
        // repeat for tiling and sharp pixels, whatever the pack's .mcmeta says
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    private static void quad(float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        w.pos(x1, y2, 0).tex(u1, v2).endVertex();
        w.pos(x2, y2, 0).tex(u2, v2).endVertex();
        w.pos(x2, y1, 0).tex(u2, v1).endVertex();
        w.pos(x1, y1, 0).tex(u1, v1).endVertex();
        t.draw();
    }

    // ------------------------------------------------------------------ text

    static void text(String s, float x, float y, int color) { mc.fontRendererObj.drawString(s, x, y, color, true); }

    static void shadowText(String s, float x, float y, int color) { mc.fontRendererObj.drawString(s, x, y, color, true); }

    static int width(String s) { return mc.fontRendererObj.getStringWidth(s); }

    /**
     * The text cut to fit the width, with "..." when it was cut. Cut at the first character that would go past the
     * width: a few glyphs of the game's Unicode font have a negative width, so a longer piece can measure shorter.
     */
    static String fit(String s, float max) {
        if (width(s) <= max) {
            boolean grows = true;
            for (int i = 1; i < s.length() && grows; i++) grows = width(s.substring(0, i)) <= max;
            if (grows) return s;
        }
        float room = max - width("...");
        int end = 0;
        while (end < s.length() && width(s.substring(0, end + 1)) <= room) end++;
        return end == 0 ? "" : s.substring(0, end) + "...";
    }

    // ------------------------------------------------------------------ controls (all square, pixel style)

    /** A switch, 20 x 10; on goes 0 (off) to 1 (on) and moves the knob. */
    static void toggle(float x, float y, float on) {
        int track = ColorUtil.blend(TRACK_OFF, ACCENT, on);
        RenderUtil.rect(x, y, x + 20, y + 10, ColorUtil.darker(track, 0.78));
        RenderUtil.rect(x + 1, y + 1, x + 19, y + 9, track);
        float k = x + 1 + 10 * on;
        RenderUtil.rect(k, y + 1, k + 8, y + 9, 0xFFFFFFFF);
        RenderUtil.rect(k, y + 8, k + 8, y + 9, 0xFFC8DBE0);
    }

    /** A box with a border: key chips, the search field, text fields. */
    static void box(float x, float y, float w, float h, boolean active, boolean hover) {
        RenderUtil.rect(x, y, x + w, y + h, active ? ACCENT : hover ? CONTROL_HOVER : CONTROL);
        RenderUtil.rect(x + 1, y + 1, x + w - 1, y + h - 1, active ? 0x90213C4B : FIELD);
    }

    /** A slider track at y (2 high), filled from a to b (0..1); a range has a handle at both ends. */
    static void slider(float x, float y, float w, float a, float b, boolean range, boolean active) {
        RenderUtil.rect(x, y, x + w, y + 2, CONTROL);
        RenderUtil.rect(x + w * a, y, x + w * b, y + 2, ACCENT);
        if (range) handle(x + w * a, y, active);
        handle(x + w * b, y, active);
    }

    private static void handle(float cx, float y, boolean active) {
        cx = Math.round(cx);
        RenderUtil.rect(cx - 2, y - 3, cx + 2, y + 5, active ? ACCENT : ACCENT_DARK);
        RenderUtil.rect(cx - 1, y - 2, cx + 1, y + 4, 0xFFFFFFFF);
    }

    /** A small solid triangle, 5 units: pointing right, or down when open. */
    static void arrow(float x, float y, boolean down, int color) {
        for (int i = 0; i < 3; i++) {
            if (down) RenderUtil.rect(x + i, y + 1 + i, x + 5 - i, y + 2 + i, color);
            else RenderUtil.rect(x + 1 + i, y + i, x + 2 + i, y + 5 - i, color);
        }
    }

    /** Grey and white squares, to show how see-through a colour is. */
    static void checker(float x, float y, float w, float h) {
        RenderUtil.rect(x, y, x + w, y + h, 0xFFFFFFFF);
        for (float cy = y; cy < y + h; cy += 4) {
            for (float cx = x + ((int) ((cy - y) / 4) % 2) * 4; cx < x + w; cx += 8)
                RenderUtil.rect(cx, cy, Math.min(cx + 4, x + w), Math.min(cy + 4, y + h), 0xFFCCCCCC);
        }
    }
}
