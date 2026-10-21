package gale.kill.gd.compat;
import net.minecraft.client.Minecraft;import net.minecraft.client.renderer.GlStateManager;import net.minecraft.util.ResourceLocation;
import java.util.function.Supplier;import org.lwjgl.opengl.GL11;
public final class RenderSystem {
 public static void enableBlend(){GlStateManager.enableBlend();}public static void disableBlend(){GlStateManager.disableBlend();}
 public static void enableCull(){GlStateManager.enableCull();}public static void disableCull(){GlStateManager.disableCull();}
 public static void enableDepthTest(){GlStateManager.enableDepth();}public static void disableDepthTest(){GlStateManager.disableDepth();}
 public static void defaultBlendFunc(){GlStateManager.tryBlendFuncSeparate(770,771,1,0);}
 public static void blendFunc(int s,int d){GlStateManager.blendFunc(s,d);}
 public static void setShaderColor(float r,float g,float b,float a){GlStateManager.color(r,g,b,a);}
 public static void setShaderTexture(int unit,ResourceLocation r){GlStateManager.enableTexture2D();Minecraft.getMinecraft().getTextureManager().bindTexture(r);}
 public static void setShader(Supplier<Object> shader){GlStateManager.disableAlpha();if(shader.get()==GameRenderer.COLOR)GlStateManager.disableTexture2D();else GlStateManager.enableTexture2D();}
}
