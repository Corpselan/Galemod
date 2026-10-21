package gale.kill.gd.client.sounds;
import net.minecraft.client.Minecraft;import net.minecraft.client.audio.PositionedSound;import net.minecraft.client.audio.ISound;import net.minecraft.util.ResourceLocation;
public final class ExternalSoundManager {
 public static final String SLOT_BF1_DEFAULT="bf1_default",SLOT_BF1_HEADSHOT="bf1_headshot";
 public static boolean enabled=true;public static float volume=.7f;
 public static void playConfiguredSound(String preset,String slot){gale.kill.gd.GdKillEffect.queueSound(slot);}
 public static ISound play(String name){if(!enabled||volume<=0||name==null||name.isEmpty())return null;Cue cue=new Cue(new ResourceLocation("gd656killicon",name));Minecraft.getMinecraft().getSoundHandler().playSound(cue);return cue;}
 private static final class Cue extends PositionedSound {Cue(ResourceLocation r){super(r);this.volume=ExternalSoundManager.volume;this.pitch=1;this.attenuationType=ISound.AttenuationType.NONE;}}
}
