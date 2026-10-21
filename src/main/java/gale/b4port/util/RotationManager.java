package gale.b4port.util;

import gale.b4port.module.Module;
import gale.b4port.module.SettingsModule;
import net.minecraft.client.Minecraft;

/**
 * B4's RotationManager as the ported modules read it, kept by B4Port from Gale's silent rotations: what the last
 * movement packet carried (lastYaw / lastPitch), the server rotation now (serverYaw / serverPitch), whether a silent
 * rotation is on (rotating) and which ported module asked for it (source).
 */
public final class RotationManager {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static volatile Module source;
    public static boolean rotating;
    public static boolean wasRotating;
    public static float serverYaw;
    public static float serverPitch;
    public static float lastYaw;
    public static float lastPitch;
    public static float previousPitch;
    public static float playerYawAtSend;
    public static Float smoothYaw;

    private RotationManager() {
    }

    /** Before the modules' hooks each tick (UpdateEvent): whose rotation was last used is asked again. */
    public static void beginTick() {
        wasRotating = rotating;
        source = null;
    }

    /** After this tick's movement packet values are final (MotionEvent, last): what went to the server. */
    public static void sent(float yaw, float pitch) {
        previousPitch = lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        playerYawAtSend = gale.core.RotationManager.cameraYaw();
        rotating = gale.core.RotationManager.isActive();
        serverYaw = yaw;
        serverPitch = pitch;
    }

    /** B4's movement fix setting: Gale's LEGIT fix (the best of the eight key directions, as B4 picks); off: exact. */
    public static gale.core.RotationManager.MoveFix fix() {
        return SettingsModule.movementFix == null || SettingsModule.movementFix.isToggled()
            ? gale.core.RotationManager.MoveFix.LEGIT : gale.core.RotationManager.MoveFix.EXACT;
    }

    public static boolean movementFix() {
        return rotating && source != null && (SettingsModule.movementFix == null || SettingsModule.movementFix.isToggled());
    }

    /** The yaw you move along: the server rotation while it is on, else the camera. */
    public static float movementYaw() {
        if (mc.thePlayer == null) return 0f;
        return gale.core.RotationManager.isActive() ? gale.core.RotationManager.yaw() : mc.thePlayer.rotationYaw;
    }
}
