package gale.util;

import gale.core.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

/**
 * Rotations as float[]{yaw, pitch}: toward points, entities and block faces; limiting, smoothing and mouse-step (GCD)
 * correction; field of view checks. Use RotationManager to send them silently, or {@link #turnCamera} to move the camera.
 */
public final class RotationUtil {
    /** where on an entity to aim */
    public enum AimPoint { CENTER, HEAD, FEET, CLOSEST }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private RotationUtil() { }

    /** From one point to another. */
    public static float[] to(Vec3 from, Vec3 to) {
        double dx = to.xCoord - from.xCoord, dy = to.yCoord - from.yCoord, dz = to.zCoord - from.zCoord;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{ MathUtil.wrap180(yaw), MathUtil.clamp(pitch, -90f, 90f) };
    }

    /** From your eyes to a point. */
    public static float[] to(Vec3 point) { return to(PlayerUtil.eyes(), point); }

    public static float[] to(double x, double y, double z) { return to(new Vec3(x, y, z)); }

    /** From your eyes to an entity (the point inside its hitbox closest to where you look by default). */
    public static float[] to(Entity e) { return to(e, AimPoint.CLOSEST); }

    public static float[] to(Entity e, AimPoint point) { return to(aimPoint(e, point)); }

    /** The point to aim at on an entity's hitbox. */
    public static Vec3 aimPoint(Entity e, AimPoint point) {
        AxisAlignedBB bb = e.getEntityBoundingBox();
        double cx = (bb.minX + bb.maxX) / 2, cz = (bb.minZ + bb.maxZ) / 2;
        switch (point) {
            case HEAD: return new Vec3(cx, e.posY + e.getEyeHeight(), cz);
            case FEET: return new Vec3(cx, bb.minY + 0.1, cz);
            case CENTER: return new Vec3(cx, (bb.minY + bb.maxY) / 2, cz);
            default:
                Vec3 eyes = PlayerUtil.eyes();
                return new Vec3(MathUtil.clamp(eyes.xCoord, bb.minX, bb.maxX), MathUtil.clamp(eyes.yCoord, bb.minY + 0.1, bb.maxY - 0.1),
                    MathUtil.clamp(eyes.zCoord, bb.minZ, bb.maxZ));
        }
    }

    /** Toward the centre of one face of a block. */
    public static float[] toFace(BlockPos pos, EnumFacing face) {
        return to(new Vec3(pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5, pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
            pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5));
    }

    /** The unit vector a rotation looks along. */
    public static Vec3 look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Your camera rotation. */
    public static float[] camera() { return new float[]{ RotationManager.cameraYaw(), RotationManager.cameraPitch() }; }

    /** The rotation the server gets this tick (silent one if active). */
    public static float[] server() { return new float[]{ RotationManager.yaw(), RotationManager.pitch() }; }

    /** Angular distance between two rotations, in degrees. */
    public static float distance(float[] a, float[] b) {
        return (float) Math.hypot(MathUtil.angleDiff(a[0], b[0]), b[1] - a[1]);
    }

    /** Moves from toward to by at most maxYaw / maxPitch degrees. */
    public static float[] limit(float[] from, float[] to, float maxYaw, float maxPitch) {
        float dy = MathUtil.clamp(MathUtil.angleDiff(from[0], to[0]), -maxYaw, maxYaw);
        float dp = MathUtil.clamp(to[1] - from[1], -maxPitch, maxPitch);
        return new float[]{ from[0] + dy, MathUtil.clamp(from[1] + dp, -90f, 90f) };
    }

    /** Moves a fraction (0..1) of the way from from to to. */
    public static float[] smooth(float[] from, float[] to, float fraction) {
        return new float[]{ from[0] + MathUtil.angleDiff(from[0], to[0]) * fraction, MathUtil.clamp(from[1] + (to[1] - from[1]) * fraction, -90f, 90f) };
    }

    /** Rounds the turn from from to to whole mouse steps, as a real mouse would make it. */
    public static float[] gcdFix(float[] from, float[] to) {
        float g = RotationManager.gcd();
        float dy = Math.round(MathUtil.angleDiff(from[0], to[0]) / g) * g;
        float dp = Math.round((to[1] - from[1]) / g) * g;
        return new float[]{ from[0] + dy, MathUtil.clamp(from[1] + dp, -90f, 90f) };
    }

    /** Whether an entity is within a field of view (degrees, whole cone) around where the camera looks. */
    public static boolean inFov(Entity e, float fov) {
        if (fov >= 360) return true;
        return Math.abs(MathUtil.angleDiff(RotationManager.cameraYaw(), to(e, AimPoint.CENTER)[0])) <= fov / 2;
    }

    /**
     * Turns the camera (not silently) by this much, keeping the frame smooth: what an aim assist uses. Rounded to mouse
     * steps when gcd is true.
     */
    public static void turnCamera(float dYaw, float dPitch, boolean gcd) {
        if (mc.thePlayer == null) return;
        if (gcd) {
            float g = RotationManager.gcd();
            dYaw = Math.round(dYaw / g) * g;
            dPitch = Math.round(dPitch / g) * g;
        }
        float pitch = MathUtil.clamp(mc.thePlayer.rotationPitch + dPitch, -90f, 90f);
        dPitch = pitch - mc.thePlayer.rotationPitch;
        mc.thePlayer.rotationYaw += dYaw;
        mc.thePlayer.rotationPitch = pitch;
        mc.thePlayer.prevRotationYaw += dYaw;
        mc.thePlayer.prevRotationPitch += dPitch;
    }
}
