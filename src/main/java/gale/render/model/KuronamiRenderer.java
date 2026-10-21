package gale.render.model;

import gale.Gale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** One static VBO/EBO, one cached texture atlas, one draw call per weapon. */
public final class KuronamiRenderer {
    private final int vbo,ebo,vao,program,lightUniform,timeUniform,enchantUniform;
    public final int triangles;
    private final int indexCount;
    private final ResourceLocation texture;
    private final int textureUniform;
    public long draws;

    public KuronamiRenderer(String skin) throws Exception {
        if (!GLContext.getCapabilities().GL_ARB_vertex_array_object) throw new IllegalStateException("Vertex arrays unavailable");
        texture=new ResourceLocation(Gale.ID,"models/kuronami/"+skin.toLowerCase(java.util.Locale.ROOT)+".png");
        int array=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), element=GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        try (DataInputStream in=new DataInputStream(resource(skin.toLowerCase(java.util.Locale.ROOT)+".bin"))) {
            byte[] magic=new byte[8]; in.readFully(magic);
            if (!new String(magic,StandardCharsets.US_ASCII).equals("GALEKURI") || in.readInt()!=1) throw new IOException("Invalid Kuronami mesh");
            int vertices=in.readInt(); indexCount=in.readInt();
            if (vertices<1 || vertices>200000 || indexCount<3 || indexCount>1000000) throw new IOException("Invalid mesh size");
            triangles=indexCount/3;
            FloatBuffer vertex=BufferUtils.createFloatBuffer(vertices*12);
            for(int i=0;i<vertices*12;i++) vertex.put(in.readFloat()); vertex.flip();
            IntBuffer indices=BufferUtils.createIntBuffer(indexCount);
            for(int i=0;i<indexCount;i++) { int n=in.readInt();if(n<0 || n>=vertices)throw new IOException("Invalid mesh index");indices.put(n); }indices.flip();
            vbo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,vertex,GL15.GL_STATIC_DRAW);
            ebo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,GL15.GL_STATIC_DRAW);
        } finally { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,element); }
        int vs=shader(GL20.GL_VERTEX_SHADER,text("weapon.vert")), fs=shader(GL20.GL_FRAGMENT_SHADER,text("weapon.frag"));
        program=GL20.glCreateProgram();GL20.glAttachShader(program,vs);GL20.glAttachShader(program,fs);
        String[] attrs={"aPosition","aNormal","aUV","aColor","aEmissive"};for(int i=0;i<5;i++)GL20.glBindAttribLocation(program,i,attrs[i]);
        GL20.glLinkProgram(program);GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);
        if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program,4096));
        lightUniform=GL20.glGetUniformLocation(program,"uLight");timeUniform=GL20.glGetUniformLocation(program,"uTime");enchantUniform=GL20.glGetUniformLocation(program,"uEnchanted");
        textureUniform=GL20.glGetUniformLocation(program,"uTexture");
        vao=ARBVertexArrayObject.glGenVertexArrays();
        Gale.LOG.info("Kuronami {} cached: {} triangles, one static GPU draw",skin,triangles);
    }
    private static InputStream resource(String name) throws Exception { return Minecraft.getMinecraft().getResourceManager().getResource(new ResourceLocation(Gale.ID,"models/kuronami/"+name)).getInputStream(); }
    private static String text(String name)throws Exception {
        try(InputStream in=resource(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static int shader(int type,String text) {
        int id=GL20.glCreateShader(type);GL20.glShaderSource(id,text);GL20.glCompileShader(id);
        if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(id,4096);GL20.glDeleteShader(id);throw new IllegalStateException(error);}return id;
    }
    public void draw(float brightness,boolean enchanted) {
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(ARBVertexArrayObject.GL_VERTEX_ARRAY_BINDING),oldVbo=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        int oldTexture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        try {
            ARBVertexArrayObject.glBindVertexArray(vao);GL20.glUseProgram(program);
            Minecraft.getMinecraft().getTextureManager().bindTexture(texture);GL20.glUniform1i(textureUniform,0);
            GL20.glUniform1f(lightUniform,.40f+.60f*Math.max(0,Math.min(1,brightness)));
            GL20.glUniform1f(timeUniform,(Minecraft.getSystemTime()%60000)/1000f);GL20.glUniform1f(enchantUniform,enchanted?1:0);
            GlStateManager.disableCull();GlStateManager.disableBlend();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ebo);
            for(int i=0;i<5;i++)GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,48,0L);GL20.glVertexAttribPointer(1,3,GL11.GL_FLOAT,false,48,12L);
            GL20.glVertexAttribPointer(2,2,GL11.GL_FLOAT,false,48,24L);GL20.glVertexAttribPointer(3,3,GL11.GL_FLOAT,false,48,32L);GL20.glVertexAttribPointer(4,1,GL11.GL_FLOAT,false,48,44L);
            GL11.glDrawElements(GL11.GL_TRIANGLES,indexCount,GL11.GL_UNSIGNED_INT,0L);draws++;
        } finally {
            ARBVertexArrayObject.glBindVertexArray(oldVao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,oldVbo);GL20.glUseProgram(oldProgram);
            GlStateManager.bindTexture(oldTexture);GlStateManager.setActiveTexture(oldActive);
            if(cull)GlStateManager.enableCull();else GlStateManager.disableCull();if(blend)GlStateManager.enableBlend();else GlStateManager.disableBlend();
        }
    }
}
