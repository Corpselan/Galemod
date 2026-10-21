package gale.render;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Smooth text in any script (Chinese, Japanese, Korean, Latin): each character is drawn once, large, by Java's font
 * engine from a font on this computer (Microsoft YaHei first), packed into 1024x1024 textures and shrunk with mipmaps,
 * so it stays sharp and clean at any size, in the world as well as on screen. Same idea as DangoMusic's CjkFont.
 *
 * <p>Sizes are in the units of the current matrix: {@code size} is the height of the em square (Minecraft's font is
 * about 8). Game thread only.
 */
public final class CjkFont {
    public static final int PLAIN = 0, BOLD = 1, DISPLAY = 2;
    private static final int PAGE = 1024, PAD = 4;
    /** heavy fonts for the big flashing words */
    private static final List<String> DISPLAY_FAMILIES = Arrays.asList("Impact", "Anton", "Bebas Neue", "Arial Black", "Segoe UI Black");
    private static final List<String> FAMILIES = Arrays.asList("Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Yu Gothic UI", "Meiryo UI",
        "Meiryo", "Malgun Gothic", "Noto Sans CJK SC", "Source Han Sans SC", "SimHei", "MS Gothic", "Segoe UI Symbol", "Segoe UI", "Arial Unicode MS");
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    @SuppressWarnings("unchecked")
    private static final List<Font>[] FONTS = new List[3];
    private static final Map<Integer, Glyph> GLYPHS = new HashMap<>();
    private static final List<Integer> PAGES = new ArrayList<>();
    private static final List<Boolean> DIRTY = new ArrayList<>();
    private static int px = PAD, py = PAD, rowH;
    private static Set<String> available;
    private static Boolean canMipmap;

    private CjkFont() { }

    /** One rendered character: where it is on its page, its size and offsets at the base size. */
    public static final class Glyph {
        final int page;
        final float u0, v0, u1, v1, w, h, left, top, adv, base;

        Glyph(int page, float u0, float v0, float u1, float v1, float w, float h, float left, float top, float adv, float base) {
            this.page = page;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.w = w;
            this.h = h;
            this.left = left;
            this.top = top;
            this.adv = adv;
            this.base = base;
        }
    }

    private static float baseOf(int style) { return style == DISPLAY ? 96f : 48f; }

    private static Set<String> available() {
        if (available == null) {
            available = new HashSet<>();
            try {
                for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ENGLISH)) available.add(f.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
        }
        return available;
    }

    private static List<Font> fonts(int style) {
        if (FONTS[style] != null) return FONTS[style];
        float base = baseOf(style);
        int awt = style == PLAIN ? Font.PLAIN : Font.BOLD;
        List<Font> list = new ArrayList<>();
        if (style == DISPLAY) for (String f : DISPLAY_FAMILIES) if (available().contains(f.toLowerCase(Locale.ROOT))) list.add(new Font(f, Font.PLAIN, 1).deriveFont(base));
        for (String f : FAMILIES) if (available().contains(f.toLowerCase(Locale.ROOT))) list.add(new Font(f, awt, 1).deriveFont(base));
        list.add(new Font("Dialog", awt, 1).deriveFont(base));
        FONTS[style] = list;
        return list;
    }

    /** True if some font on this computer has a picture for this character. */
    public static boolean canDisplay(char c) { return glyph(c, PLAIN) != null; }

    static Glyph glyph(char c, int style) {
        int key = c * 3 + style;
        if (GLYPHS.containsKey(key)) return GLYPHS.get(key);
        Glyph g;
        try {
            g = render(c, style);
        } catch (Exception e) {
            g = null;
        }
        GLYPHS.put(key, g);
        return g;
    }

    /** Draws the character ahead of time (so the first frame that shows it does not hitch). */
    public static void preload(String s, int style) {
        for (int i = 0; i < s.length(); i++) glyph(s.charAt(i), style);
    }

    public static float width(String s, float size, int style) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            Glyph g = glyph(s.charAt(i), style);
            if (g != null) w += g.adv * size / g.base;
        }
        return w;
    }

    private static Glyph render(char c, int style) {
        float base = baseOf(style);
        Font font = null;
        for (Font f : fonts(style)) {
            if (f.canDisplay(c)) {
                font = f;
                break;
            }
        }
        if (font == null) return null;
        GlyphVector gv = font.createGlyphVector(FRC, new char[]{ c });
        float adv = gv.getGlyphMetrics(0).getAdvanceX();
        Rectangle2D vb = gv.getVisualBounds();
        if (vb.getWidth() <= 0 || vb.getHeight() <= 0) return new Glyph(-1, 0, 0, 0, 0, 0, 0, 0, 0, adv, base);
        int left = (int) Math.floor(vb.getX()) - 1, top = (int) Math.floor(vb.getY()) - 1;
        int w = (int) Math.ceil(vb.getX() + vb.getWidth()) + 1 - left, h = (int) Math.ceil(vb.getY() + vb.getHeight()) + 1 - top;
        if (w > PAGE - 2 * PAD || h > PAGE - 2 * PAD) return null;
        if (PAGES.isEmpty()) newPage();
        if (px + w + PAD > PAGE) {
            px = PAD;
            py += rowH + PAD;
            rowH = 0;
        }
        if (py + h + PAD > PAGE) newPage();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.drawGlyphVector(gv, -left, -top);
        g.dispose();
        int page = PAGES.size() - 1;
        upload(PAGES.get(page), px, py, w, h, ((DataBufferByte) img.getRaster().getDataBuffer()).getData());
        DIRTY.set(page, true);
        Glyph glyph = new Glyph(page, px / (float) PAGE, py / (float) PAGE, (px + w) / (float) PAGE, (py + h) / (float) PAGE, w, h, left, top, adv, base);
        px += w + PAD;
        rowH = Math.max(rowH, h);
        return glyph;
    }

    private static boolean canMipmap() {
        if (canMipmap == null) {
            ContextCapabilities caps = GLContext.getCapabilities();
            canMipmap = caps.OpenGL30 || caps.GL_EXT_framebuffer_object;
        }
        return canMipmap;
    }

    private static void genMipmap() {
        if (GLContext.getCapabilities().OpenGL30) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        else EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
    }

    private static void newPage() {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        // white everywhere, see-through everywhere: the glyphs only write their coverage into the alpha
        ByteBuffer clear = BufferUtils.createByteBuffer(PAGE * PAGE * 4);
        for (int i = 0; i < PAGE * PAGE; i++) clear.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) 0);
        clear.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, PAGE, PAGE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, clear);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (canMipmap()) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            genMipmap();
        } else {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        PAGES.add(id);
        DIRTY.add(false);
        px = PAD;
        py = PAD;
        rowH = 0;
    }

    private static void upload(int tex, int x, int y, int w, int h, byte[] coverage) {
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < w * h; i++) buf.put((byte) -1).put((byte) -1).put((byte) -1).put(coverage[i]);
        buf.flip();
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
            prevRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRow);
    }

    private static void bindPage(int i) {
        // through the game's cache, so its own next bind is not skipped
        GlStateManager.bindTexture(PAGES.get(i));
        if (DIRTY.get(i)) {
            if (canMipmap()) genMipmap();
            DIRTY.set(i, false);
        }
    }

    /**
     * Draws s with its baseline at y, in the current colour (GlStateManager.color) and matrix. Blending must be on and
     * the alpha test off (see {@link #begin}).
     */
    public static void draw(String s, float x, float baseline, float size, int style) {
        float pen = x;
        int bound = -1;
        boolean begun = false;
        for (int i = 0; i < s.length(); i++) {
            Glyph g = glyph(s.charAt(i), style);
            if (g == null) continue;
            float scale = size / g.base;
            if (g.page >= 0) {
                if (g.page != bound) {
                    if (begun) GL11.glEnd();
                    bindPage(g.page);
                    bound = g.page;
                    GL11.glBegin(GL11.GL_QUADS);
                    begun = true;
                }
                quad(g, pen + g.left * scale, baseline + g.top * scale, scale);
            }
            pen += g.adv * scale;
        }
        if (begun) GL11.glEnd();
    }

    private static void quad(Glyph g, float x, float y, float scale) {
        float w = g.w * scale, h = g.h * scale;
        GL11.glTexCoord2f(g.u0, g.v0);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(g.u0, g.v1);
        GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(g.u1, g.v1);
        GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(g.u1, g.v0);
        GL11.glVertex2f(x + w, y);
    }

    /** Sets up for smooth text: texturing and blending on, alpha test off. Pair with the caller's GL state restore. */
    public static void begin() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
    }

    /** How many texture pages the glyphs use so far (for the tests). */
    public static int pages() { return PAGES.size(); }
}
