package gale.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTextureFloat;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A soft, wide glow around whatever is drawn between {@link #begin} and {@link #end} (After Effects' "Deep Glow"
 * look, as in DangoMusic): the drawing goes into its own picture, which is halved seven times and added back up level
 * by level (each level twice as wide as the last, which together falls off like light does), then laid over the
 * screen with an exposure curve, so the middle runs white-hot and the edge keeps its colour and never clips.
 *
 * <p>Needs framebuffers and shaders; if either is missing or fails once, {@link #available} turns false and the
 * glow is simply left out.
 */
public final class DeepGlow {
    private static final int LEVELS = 7;
    private static final String VERT = "#version 120\nvoid main() {\n    gl_TexCoord[0] = gl_MultiTexCoord0;\n    gl_Position = gl_Vertex;\n}\n";
    /** halves: a tap in the middle and four around it */
    private static final String DOWN = "#version 120\nuniform sampler2D tex;\nuniform vec2 texel;\nvoid main() {\n"
        + "    vec2 uv = gl_TexCoord[0].xy;\n    vec4 c = texture2D(tex, uv) * 4.0;\n    c += texture2D(tex, uv - texel);\n"
        + "    c += texture2D(tex, uv + texel);\n    c += texture2D(tex, uv + vec2(texel.x, -texel.y));\n"
        + "    c += texture2D(tex, uv + vec2(-texel.x, texel.y));\n    gl_FragColor = c / 8.0;\n}\n";
    /** doubles with a tent filter, added onto the next finer level */
    private static final String UP = "#version 120\nuniform sampler2D tex;\nuniform vec2 texel;\nuniform float intensity;\nvoid main() {\n"
        + "    vec2 uv = gl_TexCoord[0].xy;\n    vec4 s = texture2D(tex, uv + vec2(-2.0 * texel.x, 0.0));\n"
        + "    s += texture2D(tex, uv + vec2(2.0 * texel.x, 0.0));\n    s += texture2D(tex, uv + vec2(0.0, -2.0 * texel.y));\n"
        + "    s += texture2D(tex, uv + vec2(0.0, 2.0 * texel.y));\n    s += texture2D(tex, uv + vec2(-texel.x, texel.y)) * 2.0;\n"
        + "    s += texture2D(tex, uv + vec2(texel.x, texel.y)) * 2.0;\n    s += texture2D(tex, uv + vec2(texel.x, -texel.y)) * 2.0;\n"
        + "    s += texture2D(tex, uv + vec2(-texel.x, -texel.y)) * 2.0;\n    gl_FragColor = s / 12.0 * intensity;\n}\n";
    /** exposure: 1 - e^-energy, so it never clips; a little noise against banding */
    private static final String COMPOSITE = "#version 120\nuniform sampler2D tex;\nuniform vec3 tint;\nuniform float exposure;\n"
        + "float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }\nvoid main() {\n"
        + "    vec4 g = texture2D(tex, gl_TexCoord[0].xy);\n    vec3 energy = g.rgb * tint * exposure;\n    vec3 c = vec3(1.0) - exp(-energy);\n"
        + "    c += (rand(gl_FragCoord.xy) - 0.5) / 255.0;\n    gl_FragColor = vec4(max(c, vec3(0.0)), 1.0);\n}\n";

    private final Minecraft mc = Minecraft.getMinecraft();
    private int progDown, progUp, progComp, width, height, maskTex, maskFbo, savedFbo;
    private final int[] levelTex = new int[LEVELS], levelFbo = new int[LEVELS], levelW = new int[LEVELS], levelH = new int[LEVELS];
    private final IntBuffer savedViewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer savedClear = BufferUtils.createFloatBuffer(16);
    private boolean failed, active;
    private Boolean floatFormat;
    /** why it turned itself off (empty while it works) */
    public String error = "";

    public boolean available() { return !failed; }

    public boolean active() { return active; }

    /** Turns the glow off for good (after an error), putting the screen back if a pass was open. */
    public void disable(Throwable why) {
        if (!failed) error = why.toString();
        failed = true;
        abort();
    }

    private int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(id, 4096);
            GL20.glDeleteShader(id);
            throw new IllegalStateException("glow shader: " + log);
        }
        return id;
    }

    private int program(String frag) {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT), fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("glow shader link: " + GL20.glGetProgramInfoLog(p, 4096));
        return p;
    }

    private boolean ensurePrograms() {
        if (failed) return false;
        if (progComp != 0) return true;
        try {
            if (!GLContext.getCapabilities().OpenGL20) throw new IllegalStateException("no OpenGL 2.0");
            progDown = program(DOWN);
            progUp = program(UP);
            progComp = program(COMPOSITE);
            return true;
        } catch (Throwable e) {
            error = e.toString();
            failed = true;
            return false;
        }
    }

    private boolean floatFormat() {
        if (floatFormat == null) {
            ContextCapabilities caps = GLContext.getCapabilities();
            floatFormat = caps.OpenGL30 || caps.GL_ARB_texture_float;
        }
        return floatFormat;
    }

    private int newTexture(int w, int h, boolean hdr) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // the levels hold more than 1.0 where light piles up: half floats when the card has them
        int internal = hdr && floatFormat() ? (GLContext.getCapabilities().OpenGL30 ? GL30.GL_RGBA16F : ARBTextureFloat.GL_RGBA16F_ARB) : GL11.GL_RGBA8;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return id;
    }

    private int newFbo(int tex) {
        int fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        return fbo;
    }

    private void release() {
        if (maskTex != 0) GL11.glDeleteTextures(maskTex);
        if (maskFbo != 0) OpenGlHelper.glDeleteFramebuffers(maskFbo);
        for (int i = 0; i < LEVELS; i++) {
            if (levelTex[i] != 0) GL11.glDeleteTextures(levelTex[i]);
            if (levelFbo[i] != 0) OpenGlHelper.glDeleteFramebuffers(levelFbo[i]);
            levelTex[i] = 0;
            levelFbo[i] = 0;
        }
        maskTex = 0;
        maskFbo = 0;
    }

    private void ensureTargets(int w, int h) {
        if (w == width && h == height && maskFbo != 0) return;
        release();
        width = w;
        height = h;
        maskTex = newTexture(w, h, false);
        maskFbo = newFbo(maskTex);
        int lw = w, lh = h;
        for (int i = 0; i < LEVELS; i++) {
            lw = Math.max(1, lw / 2);
            lh = Math.max(1, lh / 2);
            levelW[i] = lw;
            levelH[i] = lh;
            levelTex[i] = newTexture(lw, lh, true);
            levelFbo[i] = newFbo(levelTex[i]);
        }
    }

    /** Starts drawing into the glow's own picture (cleared); false if the glow cannot run here. */
    public boolean begin() {
        if (!OpenGlHelper.isFramebufferEnabled() || !ensurePrograms()) return false;
        int w = mc.displayWidth, h = mc.displayHeight;
        if (w <= 0 || h <= 0) return false;
        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        savedViewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, savedViewport);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        ensureTargets(w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, maskFbo);
        GL11.glViewport(0, 0, w, h);
        savedClear.clear();
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, savedClear);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3));
        active = true;
        return true;
    }

    /**
     * Blurs what was drawn and adds it onto the screen: tinted r, g, b; exposure is the brightness, spread (0..1) how
     * far it reaches.
     */
    public void end(float r, float g, float b, float exposure, float spread) {
        if (!active) return;
        active = false;
        GlState saved = GlState.save();
        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GlStateManager.disableBlend();
            GL20.glUseProgram(progDown);
            GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "tex"), 0);
            int texelDown = GL20.glGetUniformLocation(progDown, "texel");
            int srcTex = maskTex, srcW = width, srcH = height;
            for (int i = 0; i < LEVELS; i++) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, levelFbo[i]);
                GL11.glViewport(0, 0, levelW[i], levelH[i]);
                GlStateManager.bindTexture(srcTex);
                GL20.glUniform2f(texelDown, 1f / srcW, 1f / srcH);
                quad();
                srcTex = levelTex[i];
                srcW = levelW[i];
                srcH = levelH[i];
            }
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GL20.glUseProgram(progUp);
            GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "tex"), 0);
            int texelUp = GL20.glGetUniformLocation(progUp, "texel");
            GL20.glUniform1f(GL20.glGetUniformLocation(progUp, "intensity"), 0.55f + Math.max(0f, Math.min(1f, spread)) * 0.6f);
            for (int i = LEVELS - 2; i >= 0; i--) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, levelFbo[i]);
                GL11.glViewport(0, 0, levelW[i], levelH[i]);
                GlStateManager.bindTexture(levelTex[i + 1]);
                GL20.glUniform2f(texelUp, 1f / levelW[i + 1], 1f / levelH[i + 1]);
                quad();
            }
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, savedFbo);
            GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
            GL20.glUseProgram(progComp);
            GL20.glUniform1i(GL20.glGetUniformLocation(progComp, "tex"), 0);
            GL20.glUniform3f(GL20.glGetUniformLocation(progComp, "tint"), r, g, b);
            GL20.glUniform1f(GL20.glGetUniformLocation(progComp, "exposure"), exposure);
            GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
            GlStateManager.bindTexture(levelTex[0]);
            quad();
        } finally {
            GL20.glUseProgram(0);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, savedFbo);
            GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            saved.restore();
        }
    }

    /** Leaves the glow's picture without drawing it (after an error). */
    public void abort() {
        if (!active) return;
        active = false;
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3));
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0);
        GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0);
        GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1);
        GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1);
        GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }
}
