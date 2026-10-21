package gale.kill.gd.compat;
import net.minecraft.client.renderer.Tessellator;import net.minecraft.client.renderer.WorldRenderer;
public final class BufferBuilder {
 private final WorldRenderer out=Tessellator.getInstance().getWorldRenderer();
 public void begin(VertexFormat.Mode mode,net.minecraft.client.renderer.vertex.VertexFormat format){out.begin(mode.gl,format);}
 public BufferBuilder vertex(Matrix4f ignored,float x,float y,float z){out.pos(x,y,z);return this;}
 public BufferBuilder uv(float u,float v){out.tex(u,v);return this;}
 public BufferBuilder color(float r,float g,float b,float a){out.color(r,g,b,a);return this;}
 public BufferBuilder color(int r,int g,int b,int a){out.color(r,g,b,a);return this;}
 public void endVertex(){out.endVertex();}public BufferBuilder end(){return this;}
}
