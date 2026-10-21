package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.Timer;

/** Short access to the game's main objects. */
public final class Mc {
    public static final Minecraft mc = Minecraft.getMinecraft();

    private Mc() { }

    public static EntityPlayerSP player() { return mc.thePlayer; }

    public static WorldClient world() { return mc.theWorld; }

    public static NetHandlerPlayClient net() { return mc.getNetHandler(); }

    public static PlayerControllerMP controller() { return mc.playerController; }

    public static GameSettings settings() { return mc.gameSettings; }

    /** In a world with a player. */
    public static boolean inGame() { return mc.thePlayer != null && mc.theWorld != null; }

    /** In a world with no screen open. */
    public static boolean inGameNoScreen() { return inGame() && mc.currentScreen == null; }

    public static boolean isMainThread() { return mc.isCallingFromMinecraftThread(); }

    /** The game's tick timer (speed and partial ticks). */
    public static Timer timer() { return ReflectUtil.get(ReflectUtil.field(Minecraft.class, "timer", "field_71428_T"), mc); }

    /** How far the current frame is between the last tick and the next, 0..1. */
    public static float partialTicks() {
        Timer t = timer();
        return t == null ? 1f : t.renderPartialTicks;
    }

    /** Game speed: 1 is normal (what a "Timer" module changes). */
    public static void setTimerSpeed(float speed) {
        Timer t = timer();
        if (t != null) t.timerSpeed = speed;
    }

    public static float timerSpeed() {
        Timer t = timer();
        return t == null ? 1f : t.timerSpeed;
    }

    public static int fps() { return Minecraft.getDebugFPS(); }
}
