package gale.core;

import gale.Gale;
import gale.event.Listener;
import gale.event.Priority;
import gale.event.impl.InputEvent;
import gale.event.impl.MotionEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.PostUpdateEvent;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;

/**
 * B4's pre-motion moment without mixins ({@link MotionEvent}), and safe walk.
 * <p>Forge's player tick ends after you moved and before the game sends the movement packet: Gale fires
 * {@link MotionEvent} there with the values the packet will carry. A changed position, rotation or ground goes into
 * that packet on its way out (network thread); changed sprinting or sneaking is put on the player until the packet has
 * gone, so the game tells the server; the game's "last sent" values become what was really sent, as in B4.
 * <p>{@link #safeWalk}: for this tick the player counts as sneaking while moving (so the game stops you at edges, as
 * sneaking does) without the sneak slowdown, and is back to your own sneak before the movement packet, so the server
 * never hears of it.
 */
public final class MotionHook {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field[] LAST_REPORTED = {
        ReflectUtil.field(EntityPlayerSP.class, "lastReportedPosX", "field_175172_bI"),
        ReflectUtil.field(EntityPlayerSP.class, "lastReportedPosY", "field_175166_bJ"),
        ReflectUtil.field(EntityPlayerSP.class, "lastReportedPosZ", "field_175167_bK"),
        ReflectUtil.field(EntityPlayerSP.class, "lastReportedYaw", "field_175164_bL"),
        ReflectUtil.field(EntityPlayerSP.class, "lastReportedPitch", "field_175165_bM") };

    /** changes for this tick's movement packet: {x, y, z, yaw, pitch, ground, moved, turned}, applied to the next C03 */
    private static volatile Object[] pending;
    private static volatile long pendingAt;
    /** what the packet carried, for the game's bookkeeping after it went: {x, y, z, yaw, pitch, moved, turned} */
    private static Object[] reported;
    /** {sprinting, sneaking} to put back after the packet */
    private static boolean[] restore;

    private static long safeWalkTick = -1;
    private static Boolean realSneak;
    private static Listener<InputEvent> safeWalkListener;

    private MotionHook() { }

    static void install() {
        MinecraftForge.EVENT_BUS.register(new MotionHook());
        Gale.BUS.listen(PacketEvent.Send.class, Priority.HIGHEST + 100, MotionHook::packet, "MotionHook");
        Gale.BUS.listen(PostUpdateEvent.class, Priority.HIGHEST + 300, e -> putBack(), "MotionHook");
        safeWalkListener = Gale.BUS.listener(InputEvent.class, Priority.MONITOR + 1, MotionHook::input, "MotionHook");
    }

    /**
     * Safe walk for this tick (call in UpdateEvent, before the keys are read): you stop at edges as if sneaking, at
     * full speed, and the server is not told you sneak.
     */
    public static void safeWalk() {
        safeWalkTick = Scheduler.ticks();
        if (safeWalkListener != null) Gale.BUS.register(safeWalkListener);
    }

    /** Whether safe walk is on for this tick. */
    public static boolean isSafeWalking() { return safeWalkTick == Scheduler.ticks(); }

    private static void input(InputEvent e) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || !isSafeWalking()) {
            Gale.BUS.unregister(safeWalkListener);
            return;
        }
        if (e.sneak || p.capabilities.isFlying) return;
        realSneak = false;
        // sneaking without the slowdown: InputHook takes the keys as they are
        e.sneak = true;
    }

    // ------------------------------------------------------------------ the movement packet

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.player != mc.thePlayer || mc.theWorld == null) return;
        EntityPlayerSP p = mc.thePlayer;
        if (realSneak != null) {
            if (p.movementInput != null) p.movementInput.sneak = realSneak;
            realSneak = null;
        }
        if (!Gale.BUS.hasListeners(MotionEvent.class)) return;
        double x = p.posX, y = p.getEntityBoundingBox().minY, z = p.posZ;
        float yaw = p.rotationYaw, pitch = p.rotationPitch;
        boolean ground = p.onGround, sprint = p.isSprinting(), sneak = p.isSneaking();
        MotionEvent ev = Gale.BUS.post(new MotionEvent(x, y, z, yaw, pitch, ground, sprint, sneak));
        boolean moved = ev.x != x || ev.y != y || ev.z != z, turned = ev.yaw != yaw || ev.pitch != pitch;
        if (moved || turned || ev.onGround != ground) {
            pending = new Object[]{ ev.x, ev.y, ev.z, ev.yaw, ev.pitch, ev.onGround, moved, turned };
            pendingAt = System.currentTimeMillis();
            reported = new Object[]{ ev.x, ev.y, ev.z, ev.yaw, ev.pitch, moved, turned };
        }
        if (ev.sprinting != sprint || ev.sneaking != sneak) {
            restore = new boolean[]{ sprint, sneak };
            if (ev.sprinting != sprint) p.setSprinting(ev.sprinting);
            if (ev.sneaking != sneak && p.movementInput != null) p.movementInput.sneak = ev.sneaking;
        }
    }

    // network thread: the tick's movement packet, as the listeners left it
    private static void packet(PacketEvent.Send e) {
        Object[] m = pending;
        if (m == null || !(e.getPacket() instanceof C03PacketPlayer)) return;
        pending = null;
        if (System.currentTimeMillis() - pendingAt > 1000) return;
        C03PacketPlayer c = (C03PacketPlayer) e.getPacket();
        double x = (Double) m[0], y = (Double) m[1], z = (Double) m[2];
        float yaw = (Float) m[3], pitch = (Float) m[4];
        boolean ground = (Boolean) m[5], moving = c.isMoving() || (Boolean) m[6], rotating = c.getRotating() || (Boolean) m[7];
        if (moving && rotating) e.setPacket(new C03PacketPlayer.C06PacketPlayerPosLook(x, y, z, yaw, pitch, ground));
        else if (moving) e.setPacket(new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, ground));
        else if (rotating) e.setPacket(new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, ground));
        else e.setPacket(new C03PacketPlayer(ground));
    }

    private static void putBack() {
        Object[] rep = reported;
        reported = null;
        EntityPlayerSP p = mc.thePlayer;
        if (rep != null && p != null) {
            // the server got the changed values: the game compares the next tick with those (B4 does the same)
            if ((Boolean) rep[5]) for (int i = 0; i < 3; i++) ReflectUtil.set(LAST_REPORTED[i], p, rep[i]);
            if ((Boolean) rep[6]) for (int i = 3; i < 5; i++) ReflectUtil.set(LAST_REPORTED[i], p, rep[i]);
        }
        boolean[] r = restore;
        restore = null;
        if (r == null || p == null) return;
        if (p.isSprinting() != r[0]) p.setSprinting(r[0]);
        if (p.movementInput != null) p.movementInput.sneak = r[1];
    }
}
