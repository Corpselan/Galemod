package gale.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

/**
 * The GL state a drawing pass touches, read straight from GL before it and put back after it, through both the game's
 * state cache (GlStateManager) and GL itself, so the two agree again afterwards whatever the pass did.
 */
public final class GlState {
    private static final FloatBuffer COLOR = BufferUtils.createFloatBuffer(16);
    private boolean blend, depth, alpha, cull, texture, lighting, fog, scissor, depthMask, lightmap;
    private int srcRgb, dstRgb, srcAlpha, dstAlpha, boundTexture, program;
    private final float[] color = new float[4];

    private GlState() { }

    public static GlState save() {
        GlState s = new GlState();
        s.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        s.depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        s.alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        s.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        s.lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        s.fog = GL11.glIsEnabled(GL11.GL_FOG);
        s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        s.srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        s.dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        s.srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        s.dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        COLOR.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR);
        for (int i = 0; i < 4; i++) s.color[i] = COLOR.get(i);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        s.lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        s.texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        s.boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        return s;
    }

    /** Text and pictures: blending on, no lighting, fog, culling or lightmap; depth and alpha test as the pass wants. */
    public static void prepareText() {
        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
    }

    public void restore() {
        GL20.glUseProgram(program);
        set(GL11.GL_BLEND, blend);
        set(GL11.GL_DEPTH_TEST, depth);
        set(GL11.GL_ALPHA_TEST, alpha);
        set(GL11.GL_CULL_FACE, cull);
        set(GL11.GL_LIGHTING, lighting);
        set(GL11.GL_FOG, fog);
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.depthMask(depthMask);
        GL11.glDepthMask(depthMask);
        GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        texture(lightmap);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        texture(texture);
        GlStateManager.bindTexture(boundTexture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTexture);
        GlStateManager.color(color[0], color[1], color[2], color[3]);
        GL11.glColor4f(color[0], color[1], color[2], color[3]);
    }

    private static void texture(boolean on) {
        if (on) {
            GlStateManager.enableTexture2D();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        } else {
            GlStateManager.disableTexture2D();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
    }

    private static void set(int cap, boolean on) {
        switch (cap) {
            case GL11.GL_BLEND: if (on) GlStateManager.enableBlend(); else GlStateManager.disableBlend(); break;
            case GL11.GL_DEPTH_TEST: if (on) GlStateManager.enableDepth(); else GlStateManager.disableDepth(); break;
            case GL11.GL_ALPHA_TEST: if (on) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha(); break;
            case GL11.GL_CULL_FACE: if (on) GlStateManager.enableCull(); else GlStateManager.disableCull(); break;
            case GL11.GL_LIGHTING: if (on) GlStateManager.enableLighting(); else GlStateManager.disableLighting(); break;
            case GL11.GL_FOG: if (on) GlStateManager.enableFog(); else GlStateManager.disableFog(); break;
            default: break;
        }
        if (on) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }
}
