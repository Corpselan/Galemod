package gale.render.model;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** Procedural mask, lens rim and reticle: three batches, no textures or extra world pass. */
public final class ScopeOverlay {
    private static final int SEGMENTS=96;
    private static final double[] X=new double[SEGMENTS+1],Y=new double[SEGMENTS+1];
    static{for(int i=0;i<=SEGMENTS;i++){double a=i*Math.PI*2/SEGMENTS;X[i]=Math.cos(a);Y[i]=Math.sin(a);}}
    private ScopeOverlay(){}
    public static void draw(int width,int height,float progress){
        float alpha=progress*progress*(3-2*progress);
        double cx=width*.5,cy=height*.5,r=Math.min(width,height)*.455,outer=Math.max(width,height)*2;
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        boolean texture=GL11.glIsEnabled(GL11.GL_TEXTURE_2D),test=GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthWrite=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sr=GL11.glGetInteger(0x80C9),dr=GL11.glGetInteger(0x80C8),sa=GL11.glGetInteger(0x80CB),da=GL11.glGetInteger(0x80CA);
        GlStateManager.disableDepth();GlStateManager.depthMask(false);GlStateManager.disableTexture2D();GlStateManager.disableAlpha();GlStateManager.disableCull();
        GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
        try{
            Tessellator t=Tessellator.getInstance();WorldRenderer w=t.getWorldRenderer();
            w.begin(GL11.GL_TRIANGLE_STRIP,DefaultVertexFormats.POSITION_COLOR);
            for(int i=0;i<=SEGMENTS;i++){
                vertex(w,cx+X[i]*r,cy+Y[i]*r,0,0,0,alpha);
                vertex(w,cx+X[i]*outer,cy+Y[i]*outer,0,0,0,alpha);
            }t.draw();
            w.begin(GL11.GL_TRIANGLE_STRIP,DefaultVertexFormats.POSITION_COLOR);
            for(int i=0;i<=SEGMENTS;i++){
                vertex(w,cx+X[i]*(r-1.8),cy+Y[i]*(r-1.8),.12f,.23f,.27f,alpha);
                vertex(w,cx+X[i]*(r-.3),cy+Y[i]*(r-.3),.02f,.04f,.05f,alpha);
            }t.draw();
            w.begin(GL11.GL_QUADS,DefaultVertexFormats.POSITION_COLOR);
            double gap=2.5,line=.30;
            quad(w,cx-r+3,cy-line,cx-gap,cy+line,0,0,0,alpha);
            quad(w,cx+gap,cy-line,cx+r-3,cy+line,0,0,0,alpha);
            quad(w,cx-line,cy-r+3,cx+line,cy-gap,0,0,0,alpha);
            quad(w,cx-line,cy+gap,cx+line,cy+r-3,0,0,0,alpha);
            quad(w,cx-.5,cy-.5,cx+.5,cy+.5,.95f,.12f,.10f,alpha);t.draw();
        }finally{
            GlStateManager.color(1,1,1,1);GlStateManager.depthMask(depthWrite);
            if(depth)GlStateManager.enableDepth();else GlStateManager.disableDepth();
            if(texture)GlStateManager.enableTexture2D();else GlStateManager.disableTexture2D();
            if(test)GlStateManager.enableAlpha();else GlStateManager.disableAlpha();
            if(cull)GlStateManager.enableCull();else GlStateManager.disableCull();
            GlStateManager.tryBlendFuncSeparate(sr,dr,sa,da);if(blend)GlStateManager.enableBlend();else GlStateManager.disableBlend();
        }
    }
    private static void vertex(WorldRenderer w,double x,double y,float r,float g,float b,float a){w.pos(x,y,0).color(r,g,b,a).endVertex();}
    private static void quad(WorldRenderer w,double l,double t,double r,double b,float red,float green,float blue,float a){
        vertex(w,l,b,red,green,blue,a);vertex(w,r,b,red,green,blue,a);vertex(w,r,t,red,green,blue,a);vertex(w,l,t,red,green,blue,a);
    }
}
