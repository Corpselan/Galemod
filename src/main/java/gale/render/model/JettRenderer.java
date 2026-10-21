package gale.render.model;

import gale.Gale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.ARBVertexArrayObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/** Static VBOs and a small 15-bone shader; no per-frame vertex skinning or texture uploads. */
public final class JettRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int STRIDE = 19 * 4;
    private final float[][] pivots = new float[15][3];
    private final int[] parents = new int[15];
    private final FloatBuffer palette = BufferUtils.createFloatBuffer(15 * 16);
    private final Material[] materials;
    private final int program, bonesUniform, gripUniform, lightUniform, hurtUniform, textureUniform;
    private final int vertexArray;
    public final JettPose pose;
    public final int triangles, firstPersonTriangles;

    private static final class Material {
        ResourceLocation texture;
        int vertexBuffer, indexBuffer, fullCount, handCount;
        boolean blend;
    }

    public JettRenderer() throws Exception {
        if (!GLContext.getCapabilities().GL_ARB_vertex_array_object)
            throw new IllegalStateException("Jett requires vertex array object support");
        int oldVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldEbo = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (DataInputStream in = new DataInputStream(resource("jett.bin"))) {
            byte[] magic = new byte[8]; in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("GALEJETT") || in.readInt() != 1 || in.readInt() != 15)
                throw new IllegalArgumentException("Unsupported Jett model data");
            for (int i = 0; i < 15; i++) {
                parents[i] = in.readInt();
                for (int j = 0; j < 3; j++) pivots[i][j] = in.readFloat();
            }
            int count = in.readInt();
            if (count < 1 || count > 8) throw new IllegalArgumentException("Invalid material count");
            materials = new Material[count];
            int total = 0, hands = 0;
            for (int i = 0; i < count; i++) {
                Material m = new Material(); materials[i] = m;
                m.texture = new ResourceLocation(Gale.ID, in.readUTF());
                m.blend = in.readBoolean();
                int vertices = in.readInt(); m.fullCount = in.readInt(); m.handCount = in.readInt();
                if (vertices < 1 || vertices > 100000 || m.fullCount < 0 || m.fullCount > 1000000 || m.handCount < 0 || m.handCount > m.fullCount)
                    throw new IllegalArgumentException("Invalid model limits");
                FloatBuffer data = BufferUtils.createFloatBuffer(vertices * 19);
                for (int k = 0; k < vertices * 19; k++) data.put(in.readFloat());
                data.flip();
                IntBuffer indices = BufferUtils.createIntBuffer(m.fullCount + m.handCount);
                for (int k = 0; k < m.fullCount + m.handCount; k++) indices.put(in.readInt());
                indices.flip();
                m.vertexBuffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, m.vertexBuffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
                m.indexBuffer = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.indexBuffer);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
                total += m.fullCount / 3; hands += m.handCount / 3;
                mc.getTextureManager().bindTexture(m.texture);
            }
            triangles = total; firstPersonTriangles = hands;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, oldEbo);
            GlStateManager.bindTexture(oldTexture);
            GlStateManager.setActiveTexture(oldActive);
        }
        int vertex = shader(GL20.GL_VERTEX_SHADER, text("jett.vert"));
        int fragment = shader(GL20.GL_FRAGMENT_SHADER, text("jett.frag"));
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
        String[] names = {"aPosition","aNormal","aUV","aJoints","aWeights","aGrip"};
        for (int i = 0; i < names.length; i++) GL20.glBindAttribLocation(program, i, names[i]);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 4096));
        bonesUniform = GL20.glGetUniformLocation(program, "uBones[0]");
        gripUniform = GL20.glGetUniformLocation(program, "uGrip");
        lightUniform = GL20.glGetUniformLocation(program, "uLight");
        hurtUniform = GL20.glGetUniformLocation(program, "uHurt");
        textureUniform = GL20.glGetUniformLocation(program, "uTexture");
        vertexArray = ARBVertexArrayObject.glGenVertexArrays();
        pose = new JettPose(pivots, parents);
        Gale.LOG.info("Jett model ready: {} triangles, {} first-person triangles, 15 GPU animation drivers", triangles, firstPersonTriangles);
    }

    private static InputStream resource(String name) throws Exception {
        return mc.getResourceManager().getResource(new ResourceLocation(Gale.ID, "models/jett/" + name)).getInputStream();
    }

    private static String text(String name) throws Exception {
        try (InputStream in = resource(name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type); GL20.glShaderSource(id, source); GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL20.glGetShaderInfoLog(id, 4096); GL20.glDeleteShader(id);
            throw new IllegalStateException(error);
        }
        return id;
    }

    public void draw(boolean firstPerson, float brightness, boolean hurt) {
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldVao = GL11.glGetInteger(ARBVertexArrayObject.GL_VERTEX_ARRAY_BINDING);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int blendSrc = GL11.glGetInteger(0x80C9), blendDst = GL11.glGetInteger(0x80C8);
        int alphaSrc = GL11.glGetInteger(0x80CB), alphaDst = GL11.glGetInteger(0x80CA);
        try {
            // Isolate shader attributes from Minecraft's legacy client vertex pointers.
            ARBVertexArrayObject.glBindVertexArray(vertexArray);
            GL20.glUseProgram(program);
            palette.clear(); for (float[] matrix : pose.matrices) palette.put(matrix); palette.flip();
            GL20.glUniformMatrix4(bonesUniform, false, palette);
            GL20.glUniform1f(gripUniform, pose.grip);
            GL20.glUniform1f(lightUniform, 0.42f + 0.58f * Math.max(0, Math.min(1, brightness)));
            GL20.glUniform1f(hurtUniform, hurt ? 0.6f : 0);
            GL20.glUniform1i(textureUniform, 0);
            GlStateManager.disableCull();
            for (int i = 0; i < 6; i++) GL20.glEnableVertexAttribArray(i);
            for (Material m : materials) {
                int count = firstPerson ? m.handCount : m.fullCount;
                if (count == 0) continue;
                mc.getTextureManager().bindTexture(m.texture);
                if (m.blend) { GlStateManager.enableBlend(); GlStateManager.tryBlendFuncSeparate(770,771,1,0); GlStateManager.depthMask(false); }
                else { GlStateManager.disableBlend(); GlStateManager.depthMask(true); }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, m.vertexBuffer);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.indexBuffer);
                GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,STRIDE,0L);
                GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,STRIDE,12L);
                GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,STRIDE,24L);
                GL20.glVertexAttribPointer(3,4,GL11.GL_FLOAT,false,STRIDE,32L);
                GL20.glVertexAttribPointer(4,4,GL11.GL_FLOAT,false,STRIDE,48L);
                GL20.glVertexAttribPointer(5,3,GL11.GL_FLOAT,false,STRIDE,64L);
                GL11.glDrawElements(GL11.GL_TRIANGLES,count,GL11.GL_UNSIGNED_INT,firstPerson ? m.fullCount*4L : 0L);
            }
        } finally {
            ARBVertexArrayObject.glBindVertexArray(oldVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
            GL20.glUseProgram(oldProgram);
            GlStateManager.bindTexture(oldTexture); GlStateManager.setActiveTexture(oldActive);
            GlStateManager.depthMask(depthWrite);
            GlStateManager.tryBlendFuncSeparate(blendSrc,blendDst,alphaSrc,alphaDst);
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            GlStateManager.color(1,1,1,1);
        }
    }
}
