package gale.kill.gd.client.textures;
import java.io.*;import java.util.*;import javax.imageio.ImageIO;import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;import java.awt.image.BufferedImage;
import net.minecraft.util.ResourceLocation;import net.minecraft.client.Minecraft;
public final class ExternalTextureManager {
 private static final Map<String,TextureDimensions> DIMS=new java.util.concurrent.ConcurrentHashMap<>();
 private static final Map<String,ResourceLocation> LOCATIONS=new java.util.concurrent.ConcurrentHashMap<>();
 public static void clearCache(){DIMS.clear();LOCATIONS.clear();}
 public static ResourceLocation getTexture(String path){ResourceLocation found=LOCATIONS.get(path);if(found!=null)return found;
  ResourceLocation location=path.indexOf(':')>=0?new ResourceLocation(path):new ResourceLocation("gd656killicon",path.startsWith("textures/")||path.startsWith("icon/")?path:"textures/"+path);
  LOCATIONS.put(path,location);return location;
 }
 public static boolean isVanillaTexturePath(String p){return p!=null&&p.startsWith("minecraft:");}
 public static boolean isCustomTextureName(String p){return false;}
 public static boolean isOfficialTextureName(String p){return p!=null&&(p.startsWith("killicon_")||p.startsWith("textures/"));}
 private static InputStream open(String path)throws IOException{return Minecraft.getMinecraft().getResourceManager().getResource(getTexture(path)).getInputStream();}
 public static byte[] readTextureBytes(String preset,String path)throws IOException{
  try(InputStream in=open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
 }
 public static BufferedImage readTextureImage(String preset,String path)throws IOException{
  try(InputStream in=open(path);MemoryCacheImageInputStream stream=new MemoryCacheImageInputStream(in)){
   Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())return null;
   ImageReader reader=readers.next();try{reader.setInput(stream,true,true);return reader.read(0);}finally{reader.dispose();}
  }
 }
 public static TextureDimensions getTextureDimensions(String p,String path){TextureDimensions dims=DIMS.get(path);if(dims!=null)return dims;
  // Metadata only: animated strips can contain millions of pixels. Do not decode them to ask their size.
  try(InputStream in=open(path);MemoryCacheImageInputStream stream=new MemoryCacheImageInputStream(in)){
   Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException(path);
   ImageReader reader=readers.next();try{reader.setInput(stream,true,true);dims=new TextureDimensions(reader.getWidth(0),reader.getHeight(0));}finally{reader.dispose();}
  }catch(IOException e){throw new IllegalStateException("Missing GD656 texture: "+path,e);}
  DIMS.put(path,dims);return dims;
 }
 public static final class TextureDimensions {public final int width,height;public TextureDimensions(int w,int h){width=w;height=h;}}
}
