package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/** 2D drawing in GUI coordinates: rectangles, gradients, rounded shapes, text, items, clipping. Colours are 0xAARRGGBB. */
public final class RenderUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private RenderUtil() { }

    public static ScaledResolution resolution() { return new ScaledResolution(mc); }

    private static void begin(int color) {
        float[] c = ColorUtil.toFloats(color);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(c[0], c[1], c[2], c[3]);
    }

    private static void end() {
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void rect(float x1, float y1, float x2, float y2, int color) {
        if (x1 > x2) { float t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { float t = y1; y1 = y2; y2 = t; }
        begin(color);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        w.pos(x1, y2, 0).endVertex();
        w.pos(x2, y2, 0).endVertex();
        w.pos(x2, y1, 0).endVertex();
        w.pos(x1, y1, 0).endVertex();
        t.draw();
        end();
    }

    /** A rectangle by position and size. */
    public static void box(float x, float y, float width, float height, int color) { rect(x, y, x + width, y + height, color); }

    /** Top colour to bottom colour. */
    public static void gradient(float x1, float y1, float x2, float y2, int top, int bottom) { gradient(x1, y1, x2, y2, top, top, bottom, bottom); }

    /** Left colour to right colour. */
    public static void gradientSideways(float x1, float y1, float x2, float y2, int left, int right) { gradient(x1, y1, x2, y2, left, right, right, left); }

    private static void gradient(float x1, float y1, float x2, float y2, int topLeft, int topRight, int bottomRight, int bottomLeft) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(w, x2, y1, topRight);
        vertex(w, x1, y1, topLeft);
        vertex(w, x1, y2, bottomLeft);
        vertex(w, x2, y2, bottomRight);
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        end();
    }

    private static void vertex(WorldRenderer w, float x, float y, int c) {
        w.pos(x, y, 0).color(ColorUtil.red(c), ColorUtil.green(c), ColorUtil.blue(c), ColorUtil.alpha(c)).endVertex();
    }

    /** The edges of a rectangle, width thick, drawn inside it. */
    public static void outline(float x1, float y1, float x2, float y2, float width, int color) {
        rect(x1, y1, x2, y1 + width, color);
        rect(x1, y2 - width, x2, y2, color);
        rect(x1, y1 + width, x1 + width, y2 - width, color);
        rect(x2 - width, y1 + width, x2, y2 - width, color);
    }

    public static void roundedRect(float x, float y, float width, float height, float radius, int color) {
        radius = Math.min(radius, Math.min(width, height) / 2);
        begin(color);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_POLYGON, DefaultVertexFormats.POSITION);
        corner(w, x + width - radius, y + radius, radius, 270);
        corner(w, x + width - radius, y + height - radius, radius, 0);
        corner(w, x + radius, y + height - radius, radius, 90);
        corner(w, x + radius, y + radius, radius, 180);
        t.draw();
        end();
    }

    private static void corner(WorldRenderer w, float cx, float cy, float r, int startDeg) {
        for (int d = startDeg; d <= startDeg + 90; d += 10) {
            double a = Math.toRadians(d);
            w.pos(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 0).endVertex();
        }
    }

    public static void circle(float cx, float cy, float radius, int color) {
        begin(color);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
        w.pos(cx, cy, 0).endVertex();
        for (int d = 0; d <= 360; d += 10) {
            double a = Math.toRadians(d);
            w.pos(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, 0).endVertex();
        }
        t.draw();
        end();
    }

    /** Text with a shadow; '&' colour codes work. Returns the width drawn. */
    public static float text(String text, float x, float y, int color) { return text(text, x, y, color, true); }

    public static float text(String text, float x, float y, int color, boolean shadow) {
        String s = ChatUtil.color(text);
        mc.fontRendererObj.drawString(s, x, y, color, shadow);
        return mc.fontRendererObj.getStringWidth(s);
    }

    /**
     * Text that can be see-through (fading in or out): the font only blends while blending is on. Leaves blending as
     * it found it.
     */
    public static float textBlended(String text, float x, float y, int color) {
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        float w = text(text, x, y, color);
        if (!blend) GlStateManager.disableBlend();
        return w;
    }

    public static void centeredText(String text, float centerX, float y, int color) {
        text(text, centerX - textWidth(text) / 2f, y, color);
    }

    public static int textWidth(String text) { return mc.fontRendererObj.getStringWidth(ChatUtil.color(text)); }

    public static int fontHeight() { return mc.fontRendererObj.FONT_HEIGHT; }

    /** An item icon with its count and damage bar, 16x16 at x, y. */
    public static void item(ItemStack stack, int x, int y) {
        if (stack == null) return;
        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, x, y);
        RenderHelper.disableStandardItemLighting();
        GlStateManager.popMatrix();
        GlStateManager.enableBlend();
    }

    /** Only draws inside this rectangle until {@link #endScissor()}. */
    public static void scissor(float x, float y, float width, float height) {
        int f = resolution().getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * f), (int) (mc.displayHeight - (y + height) * f), Math.max(0, (int) (width * f)), Math.max(0, (int) (height * f)));
    }

    public static void endScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    public static boolean hovered(int mouseX, int mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
