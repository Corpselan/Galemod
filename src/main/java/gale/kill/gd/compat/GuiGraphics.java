package gale.kill.gd.compat;
import net.minecraft.client.Minecraft;import net.minecraft.client.gui.ScaledResolution;import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;import net.minecraft.util.ResourceLocation;import org.lwjgl.opengl.GL11;
import java.util.*;
public final class GuiGraphics {
 private final PoseStack pose=new PoseStack();private final Deque<int[]> clips=new ArrayDeque<>();
 private final float offsetX,offsetY,outerScale;
 public GuiGraphics(){this(0,0,1);}
 public GuiGraphics(float x,float y,float scale){offsetX=x;offsetY=y;outerScale=scale;
  if(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)){java.nio.IntBuffer b=org.lwjgl.BufferUtils.createIntBuffer(16);GL11.glGetInteger(GL11.GL_SCISSOR_BOX,b);clips.push(new int[]{b.get(0),b.get(1),b.get(2),b.get(3)});}}
 public PoseStack pose(){return pose;}
 private void prepareText(){GlStateManager.enableTexture2D();GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(770,771,1,0);}
 public int drawString(Font font,String s,int x,int y,int color,boolean shadow){prepareText();return Minecraft.getMinecraft().fontRendererObj.drawString(s,x,y,color,shadow);}
 public int drawString(Font font,Component s,int x,int y,int color,boolean shadow){prepareText();return drawComponent(s,x,y,color,shadow);}
 private int drawComponent(Component c,int x,int y,int color,boolean shadow){
  int tint=c.getStyle().getColor()==null?color:(color&0xFF000000)|c.getStyle().getColor().getValue();
  String text=(c.getStyle().isBold()?"§l":"")+c.text;
  Minecraft mc=Minecraft.getMinecraft();mc.fontRendererObj.drawString(text,x,y,tint,shadow);int end=x+mc.fontRendererObj.getStringWidth(text);
  for(Component child:c.getSiblings())end=drawComponent(child,end,y,color,shadow);return end;
 }
 public void fill(int x,int y,int x2,int y2,int color){gale.util.RenderUtil.rect(x,y,x2,y2,color);}
 public void blit(ResourceLocation texture,int x,int y,int w,int h,float u,float v,int regionW,int regionH,int texW,int texH){
  Minecraft.getMinecraft().getTextureManager().bindTexture(texture);GlStateManager.enableTexture2D();
  WorldRenderer out=Tessellator.getInstance().getWorldRenderer();out.begin(7,DefaultVertexFormats.POSITION_TEX);
  out.pos(x,y+h,0).tex(u/texW,(v+regionH)/texH).endVertex();out.pos(x+w,y+h,0).tex((u+regionW)/texW,(v+regionH)/texH).endVertex();
  out.pos(x+w,y,0).tex((u+regionW)/texW,v/texH).endVertex();out.pos(x,y,0).tex(u/texW,v/texH).endVertex();Tessellator.getInstance().draw();
 }
 public void blit(ResourceLocation t,int x,int y,float u,float v,int w,int h,int tw,int th){blit(t,x,y,w,h,u,v,w,h,tw,th);}
 public void blit(ResourceLocation t,int x,int y,int z,float u,float v,int w,int h,int tw,int th){blit(t,x,y,w,h,u,v,w,h,tw,th);}
 public void enableScissor(int x,int y,int x2,int y2){
  x=Math.round(x*outerScale+offsetX);x2=Math.round(x2*outerScale+offsetX);
  y=Math.round(y*outerScale+offsetY);y2=Math.round(y2*outerScale+offsetY);
  int s=new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor(),height=Minecraft.getMinecraft().displayHeight;
  int[] clip={x*s,height-y2*s,(x2-x)*s,(y2-y)*s};if(!clips.isEmpty()){int[] old=clips.peek();int r=Math.min(old[0]+old[2],clip[0]+clip[2]),top=Math.min(old[1]+old[3],clip[1]+clip[3]);clip[0]=Math.max(old[0],clip[0]);clip[1]=Math.max(old[1],clip[1]);clip[2]=Math.max(0,r-clip[0]);clip[3]=Math.max(0,top-clip[1]);}clips.push(clip);apply(clip);
 }
 private void apply(int[] c){GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(c[0],c[1],c[2],c[3]);}
 public void disableScissor(){if(!clips.isEmpty())clips.pop();if(clips.isEmpty())GL11.glDisable(GL11.GL_SCISSOR_TEST);else apply(clips.peek());}
}
