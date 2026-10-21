package gale.kill.gd.compat;
import net.minecraft.client.Minecraft;
public final class Font {
 public static final Font INSTANCE=new Font(); public final int lineHeight=9;
 public int width(String s){return Minecraft.getMinecraft().fontRendererObj.getStringWidth(s);}
 public int width(Component s){return width(s.formatted());}
}
