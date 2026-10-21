package gale.kill.gd.client.config;
import com.google.gson.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
public final class ConfigManager {
 private static final Map<String,JsonObject> PRESETS=new LinkedHashMap<>();private static String current="00009";
 public static JsonObject read(String resource){try(InputStream in=ConfigManager.class.getResourceAsStream("/assets/gd656killicon/"+resource)){if(in==null)throw new IllegalArgumentException(resource);return new JsonParser().parse(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();}catch(IOException e){throw new IllegalStateException(resource,e);}}
 public static JsonObject preset(String id){JsonObject p=PRESETS.get(id);if(p==null){p=read("presets/official/"+id+".json");PRESETS.put(id,p);}return p;}
 public static void select(String id){preset(id);current=id;}public static String getCurrentPresetId(){return current;}
 public static JsonObject getElementConfig(String category,String name){return getElementConfig(current,category+"/"+name,true);}
 public static JsonObject getElementConfig(String preset,String element,boolean ignored){JsonObject p=preset(preset);return p.has(element)&&p.get(element).isJsonObject()?p.getAsJsonObject(element):null;}
}
