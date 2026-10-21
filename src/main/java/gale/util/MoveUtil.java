package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;

/** Changing your motion, and the vanilla movement numbers. */
public final class MoveUtil {
    /** horizontal motion kept per tick on normal ground (0.6 slipperiness x 0.91) */
    public static final double GROUND_FRICTION = 0.546;
    /** horizontal motion kept per tick in the air */
    public static final double AIR_FRICTION = 0.91;
    public static final double JUMP_MOTION = 0.42;
    /** speed of sprint-jumping on flat ground, a common base for speed modules */
    public static final double BASE_SPEED = 0.2873;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private MoveUtil() { }

    private static EntityPlayerSP p() { return mc.thePlayer; }

    public static boolean isMoving() { return PlayerUtil.isMoving(); }

    /** The base speed with Speed / Slowness effects. */
    public static double baseSpeed() {
        double speed = BASE_SPEED;
        int s = PlayerUtil.effectLevel(Potion.moveSpeed), sl = PlayerUtil.effectLevel(Potion.moveSlowdown);
        if (s > 0) speed *= 1 + 0.2 * s;
        if (sl > 0) speed *= Math.max(0, 1 - 0.15 * sl);
        return speed;
    }

    /** Jump motion with Jump Boost. */
    public static double jumpMotion() { return JUMP_MOTION + PlayerUtil.effectLevel(Potion.jump) * 0.1; }

    /** The yaw you move toward with these keys (B4's MoveUtils.moveYaw). */
    public static float moveYaw(float yaw, float forward, float strafe) {
        float f = 1f;
        if (forward < 0) { yaw += 180f; f = -0.5f; } else if (forward > 0) f = 0.5f;
        if (strafe > 0) yaw -= 90f * f;
        else if (strafe < 0) yaw += 90f * f;
        return yaw;
    }

    /** The yaw your current keys move you toward. */
    public static float moveYaw() { return moveYaw(p().rotationYaw, p().movementInput.moveForward, p().movementInput.moveStrafe); }

    /** Sets horizontal motion to this speed toward the given yaw (0 stops). */
    public static void setSpeed(double speed, float yaw) {
        if (speed == 0) { stop(); return; }
        double r = Math.toRadians(yaw);
        p().motionX = -Math.sin(r) * speed;
        p().motionZ = Math.cos(r) * speed;
    }

    /** Sets horizontal motion to this speed toward where the keys point; stops when no key is held. */
    public static void strafe(double speed) {
        if (!isMoving()) { stop(); return; }
        setSpeed(speed, moveYaw());
    }

    /** Keeps your speed but points it where the keys point. */
    public static void strafe() { strafe(PlayerUtil.speed()); }

    public static void stop() {
        p().motionX = 0;
        p().motionZ = 0;
    }

    /** Multiplies horizontal motion. */
    public static void multiply(double factor) {
        p().motionX *= factor;
        p().motionZ *= factor;
    }
}
