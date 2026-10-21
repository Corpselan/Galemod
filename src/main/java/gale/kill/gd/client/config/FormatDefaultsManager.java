package gale.kill.gd.client.config;
 import com.google.gson.*;import net.minecraft.client.Minecraft;import java.util.*;
 public final class FormatDefaultsManager {
  private static final Map<String,JsonObject> FORMATS=new HashMap<>();
  public static String getDefault(String element,String key){
   String lang=Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode().toLowerCase(java.util.Locale.ROOT);
   if(!lang.equals("zh_cn")&&!lang.equals("zh_tw")&&!lang.equals("lzh"))lang="en_us";
   JsonObject formats=FORMATS.get(lang);if(formats==null){formats=ConfigManager.read("formats/"+lang+".json");FORMATS.put(lang,formats);}
   JsonObject values=formats.has(element)?formats.getAsJsonObject(element):null;return values!=null&&values.has(key)?values.get(key).getAsString():"";
  }
 }
 