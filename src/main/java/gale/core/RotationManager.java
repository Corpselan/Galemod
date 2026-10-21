package gale.core;

import gale.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.util.Set;

/**
 * Silent rotations without mixins. A module asks for a rotation in {@link gale.event.impl.UpdateEvent}
 * ({@link #request}); for that tick Gale puts it on the player before the player moves, so the movement packet carries
 * it, turns the movement keys so you still walk where the camera looks ({@link MoveFix}), and gives the camera its own
 * rotation back at the end of the tick, before anything is drawn. A request lasts one tick: ask again every tick to
 * keep it. When nobody asks any more, the rotation goes back to the camera at {@link #releaseSpeed} degrees a tick.
 * <p>Between the swap and the end of the tick, {@code player.rotationYaw} is the server rotation (other code reading
 * it then, including other clients, sees where you look for the server).
 */
public final class RotationManager {
    /** How the movement keys follow a silent rotation. */
    public enum MoveFix {
        /** keys stay as pressed: you walk along the server rotation */
        NONE,
        /** keys turned exactly: you walk where the camera looks, with in-between key values */
        EXACT,
        /** keys turned to the nearest of the 8 directions real keys can make */
        LEGIT
    }

    private static final Minecraft mc = Minecraft.getMinecraft();

    // settings (the Rotations module sets these)
    public static volatile boolean gcdFix = true;
    public static volatile float releaseSpeed = 45f;
    public static volatile MoveFix defaultFix = MoveFix.LEGIT;

    private static Object owner;
    private static float wantYaw, wantPitch, wantTurn;
    private static int wantPriority = Integer.MIN_VALUE;
    private static MoveFix wantFix;

    private static volatile boolean active;
    private static boolean swapped;
    private static float serverYaw, serverPitch;
    private static float cameraYaw, cameraPitch;
    private static EntityPlayerSP swappedPlayer;
    private static MoveFix fix = MoveFix.LEGIT;
    private static Object activeOwner;

    private RotationManager() { }

    /** Asks for a server rotation this tick (call in UpdateEvent). The highest priority wins; on a tie the first. */
    public static void request(Object owner, float yaw, float pitch, int priority) { request(owner, yaw, pitch, priority, defaultFix); }

    public static void request(Object owner, float[] rotation, int priority) { request(owner, rotation[0], rotation[1], priority, defaultFix); }

    public static void request(Object owner, float yaw, float pitch, int priority, MoveFix fix) { request(owner, yaw, pitch, priority, fix, 0); }

    /**
     * Like the others, with a turn limit: the server rotation moves toward the asked one by at most maxTurn degrees a
     * tick (yaw and pitch each), so it never snaps. 0 means no limit.
     */
    public static void request(Object owner, float yaw, float pitch, int priority, MoveFix fix, float maxTurn) {
        if (priority <= wantPriority) return;
        RotationManager.owner = owner;
        wantYaw = yaw;
        wantPitch = MathUtil.clamp(pitch, -90f, 90f);
        wantPriority = priority;
        wantFix = fix == null ? defaultFix : fix;
        wantTurn = maxTurn;
    }

    /** A limited-speed request with the default movement fix. */
    public static void requestSmooth(Object owner, float[] rotation, int priority, float maxTurn) {
        request(owner, rotation[0], rotation[1], priority, defaultFix, maxTurn);
    }

    /** The server rotation as {yaw, pitch}: the silent one while active, else the player's own. */
    public static float[] current() { return new float[]{ yaw(), pitch() }; }

    /** Whether a silent rotation is in use (asked for, or still going back to the camera). */
    public static boolean isActive() { return active; }

    /** Whose request is in use this tick (null: none, or going back to the camera). */
    public static Object owner() { return activeOwner; }

    /** The rotation the server gets: the silent one while active, else the player's own. */
    public static float yaw() { return active ? serverYaw : mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0; }

    public static float pitch() { return active ? serverPitch : mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0; }

    /** Where the camera looks (the player's own rotation, also while it is swapped for the tick). */
    public static float cameraYaw() { return swapped ? cameraYaw : mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0; }

    public static float cameraPitch() { return swapped ? cameraPitch : mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0; }

    /** The smallest rotation step the mouse can make with the current sensitivity (degrees). */
    public static float gcd() {
        float s = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        return s * s * s * 8.0f * 0.15f;
    }

    // ------------------------------------------------------------------ called by the hooks

    static void beginTick() {
        owner = null;
        wantPriority = Integer.MIN_VALUE;
        wantFix = null;
        wantTurn = 0;
    }

    /** After UpdateEvent: puts the rotation for this tick on the player. */
    static void apply() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) { active = false; return; }
        float camYaw = p.rotationYaw, camPitch = p.rotationPitch;
        float targetYaw, targetPitch;
        if (wantFix != null) {
            targetYaw = wantYaw;
            targetPitch = wantPitch;
            fix = wantFix;
            activeOwner = owner;
        } else if (active) {
            float dy = MathUtil.wrap180(camYaw - serverYaw), dp = camPitch - serverPitch;
            if (Math.abs(dy) <= releaseSpeed && Math.abs(dp) <= releaseSpeed) {
                active = false;
                activeOwner = null;
                return;
            }
            targetYaw = serverYaw + MathUtil.clamp(dy, -releaseSpeed, releaseSpeed);
            targetPitch = serverPitch + MathUtil.clamp(dp, -releaseSpeed, releaseSpeed);
            activeOwner = null;
        } else {
            return;
        }
        float baseYaw = active ? serverYaw : camYaw, basePitch = active ? serverPitch : camPitch;
        float dYaw = MathUtil.wrap180(targetYaw - baseYaw), dPitch = MathUtil.clamp(targetPitch, -90f, 90f) - basePitch;
        if (wantFix != null && wantTurn > 0) {
            dYaw = MathUtil.clamp(dYaw, -wantTurn, wantTurn);
            dPitch = MathUtil.clamp(dPitch, -wantTurn, wantTurn);
        }
        if (gcdFix) {
            float g = gcd();
            dYaw = Math.round(dYaw / g) * g;
            dPitch = Math.round(dPitch / g) * g;
        }
        serverYaw = baseYaw + dYaw;
        serverPitch = MathUtil.clamp(basePitch + dPitch, -90f, 90f);
        active = true;
        cameraYaw = camYaw;
        cameraPitch = camPitch;
        swappedPlayer = p;
        p.rotationYaw = serverYaw;
        p.rotationPitch = serverPitch;
        swapped = true;
    }

    /** End of tick: the camera gets its rotation back, plus whatever others turned it by during the tick. */
    static void endTick() {
        if (!swapped) return;
        swapped = false;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || p != swappedPlayer) return;
        float dYaw = p.rotationYaw - serverYaw, dPitch = p.rotationPitch - serverPitch;
        p.rotationYaw = cameraYaw + dYaw;
        p.rotationPitch = MathUtil.clamp(cameraPitch + dPitch, -90f, 90f);
        p.prevRotationYaw = cameraYaw;
        p.prevRotationPitch = cameraPitch;
    }

    /** Turns movement keys pressed relative to the camera into keys relative to the server rotation. */
    static float[] fixInput(float forward, float strafe) {
        if (!swapped || fix == MoveFix.NONE || (forward == 0 && strafe == 0)) return new float[]{ forward, strafe };
        double d = Math.toRadians(MathUtil.wrap180(cameraYaw - serverYaw));
        double f = forward * Math.cos(d) + strafe * Math.sin(d);
        double s = strafe * Math.cos(d) - forward * Math.sin(d);
        if (fix == MoveFix.LEGIT) {
            float magnitude = Math.max(Math.abs(forward), Math.abs(strafe));
            double angle = Math.round(Math.atan2(s, f) / (Math.PI / 4)) * (Math.PI / 4);
            f = Math.round(Math.cos(angle)) * magnitude;
            s = Math.round(Math.sin(angle)) * magnitude;
        }
        return new float[]{ (float) f, (float) s };
    }

    /** The same turn as the movement fix, for predictions (PlayerSim) made before the fix is applied. */
    public static float[] toServerInput(float forward, float strafe) { return fixInput(forward, strafe); }

    // ------------------------------------------------------------------ teleports (setbacks)

    /**
     * A teleport from the server carries the rotation the server last got, which is the silent one; vanilla would put
     * the camera there. While a silent rotation is active the S08 is swapped for {@link Teleport}, which lets the game
     * handle it as usual (it answers with that rotation, as the server expects) and then gives the camera its own back.
     */
    static void install() {
        gale.Gale.BUS.listen(gale.event.impl.PacketEvent.Receive.class, gale.event.Priority.LOWEST, e -> {
            S08PacketPlayerPosLook p = e.as(S08PacketPlayerPosLook.class);
            if (p != null && !e.isCancelled() && active && !(p instanceof Teleport))
                e.setPacket(new Teleport(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), p.func_179834_f()));
        }, "RotationManager");
    }

    static final class Teleport extends S08PacketPlayerPosLook {
        Teleport(double x, double y, double z, float yaw, float pitch, Set<EnumFlags> flags) { super(x, y, z, yaw, pitch, flags); }

        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            EntityPlayerSP p = mc.thePlayer;
            boolean apply = mc.isCallingFromMinecraftThread() && p != null && active && !swapped;
            float camYaw = apply ? p.rotationYaw : 0, camPitch = apply ? p.rotationPitch : 0;
            super.processPacket(handler);
            if (!apply || p != mc.thePlayer) return;
            serverYaw = p.rotationYaw;
            serverPitch = p.rotationPitch;
            p.rotationYaw = p.prevRotationYaw = camYaw;
            p.rotationPitch = p.prevRotationPitch = camPitch;
        }
    }
}
