package gale.util;

import gale.Gale;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Rounded rectangles with soft, anti-aliased edges, drawn by a small fragment shader (the way B4 draws its script
 * rectangles, so ported HUDs look the same). Falls back to {@link RenderUtil#roundedRect} where shaders do not work.
 * Corners by position: x1, y1 top left, x2, y2 bottom right, in GUI coordinates.
 */
public final class RoundRect {
    private static final String VERTEX = "#version 120\nvoid main() {\n    gl_TexCoord[0] = gl_MultiTexCoord0;\n    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n}";
    // distance to the rounded box, faded over one GUI pixel
    private static final String FRAGMENT = "#version 120\nuniform vec2 u_size;\nuniform float u_radius;\nuniform vec4 u_color;\n"
        + "void main() {\n    gl_FragColor = vec4(u_color.rgb, u_color.a * smoothstep(1.0, 0.0, length(max((abs(gl_TexCoord[0].st - 0.5) + 0.5) * u_size - u_size + u_radius, 0.0)) - u_radius + 0.5));\n}";

    private static int program;
    private static int uSize, uRadius, uColor;

    private RoundRect() { }

    public static void draw(float x1, float y1, float x2, float y2, float radius, int color) {
        if ((color >>> 24) == 0 || x2 <= x1 || y2 <= y1) return;
        float w = x2 - x1, h = y2 - y1;
        radius = Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
        if (!ready()) {
            RenderUtil.roundedRect(x1, y1, w, h, radius, color);
            return;
        }
        float a = (color >>> 24) / 255f, r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        GL20.glUseProgram(program);
        GL20.glUniform2f(uSize, w, h);
        GL20.glUniform1f(uRadius, radius);
        GL20.glUniform4f(uColor, r, g, b, a);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0f);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x1, y2, 0).tex(0, 1).endVertex();
        wr.pos(x2, y2, 0).tex(1, 1).endVertex();
        wr.pos(x2, y1, 0).tex(1, 0).endVertex();
        wr.pos(x1, y1, 0).tex(0, 0).endVertex();
        t.draw();
        GL20.glUseProgram(0);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** A circle around (cx, cy). */
    public static void circle(float cx, float cy, float r, int color) { draw(cx - r, cy - r, cx + r, cy + r, r, color); }

    private static boolean ready() {
        if (program != 0) return program > 0;
        program = -1;
        try {
            if (!OpenGlHelper.shadersSupported) return false;
            int f = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT), v = compile(GL20.GL_VERTEX_SHADER, VERTEX);
            if (f == 0 || v == 0) return false;
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, f);
            GL20.glAttachShader(p, v);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(f);
            GL20.glDeleteShader(v);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
                Gale.LOG.warn("Rounded rectangle shader did not link: {}", GL20.glGetProgramInfoLog(p, 512));
                return false;
            }
            uSize = GL20.glGetUniformLocation(p, "u_size");
            uRadius = GL20.glGetUniformLocation(p, "u_radius");
            uColor = GL20.glGetUniformLocation(p, "u_color");
            program = p;
            return true;
        } catch (Throwable t) {
            Gale.LOG.warn("Rounded rectangle shader unavailable; drawing them without it", t);
            return false;
        }
    }

    private static int compile(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) return s;
        Gale.LOG.warn("Rounded rectangle shader did not compile: {}", GL20.glGetShaderInfoLog(s, 512));
        GL20.glDeleteShader(s);
        return 0;
    }
}
