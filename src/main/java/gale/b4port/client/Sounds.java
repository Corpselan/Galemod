package gale.b4port.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/** B4's sounds: one queued sound a tick, played at the end of the tick (B4Port flushes it). */
public final class Sounds {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String name = "note.pling";
    private static final float[] args = new float[2];
    private static volatile boolean queued;

    private Sounds() {
    }

    public static void queue(String s, float volume, float pitch) {
        if (!Chat.inWorld()) {
            return;
        }
        name = s;
        args[0] = volume;
        args[1] = pitch;
        queued = true;
    }

    public static void flush() {
        if (queued) {
            queued = false;
            if (Chat.inWorld()) play(name, args[0], args[1]);
        }
    }

    public static void play(String s, float volume, float pitch) {
        mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(s), volume, pitch,
            (float) mc.thePlayer.posX, (float) mc.thePlayer.posY, (float) mc.thePlayer.posZ));
    }
}
