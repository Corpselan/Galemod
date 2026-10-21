package gale.util;

import gale.core.EntityTracker;
import gale.core.RotationManager;
import gale.core.ServerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.MovingObjectPosition;

/**
 * Hitting things the way the game does, and the facts around a hit: reach, whether the server rotation is on the
 * target, critical hits, the target's hurt time. Hit in PostUpdateEvent when you rotated this tick, so the movement
 * packet with the rotation goes out before the hit.
 */
public final class CombatUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** vanilla survival reach for hitting entities */
    public static final double REACH = 3.0;

    private CombatUtil() { }

    /**
     * Attacks like a left click on the entity: swing, attack packet, then the game's own side of the hit (AttackEvent
     * fires; sprint slowdown). Game thread.
     */
    public static void attack(Entity target) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || target == null) return;
        p.swingItem();
        mc.playerController.attackEntity(p, target);
    }

    /** Within reach as the server measures it (eyes to the nearest point of the hitbox). */
    public static boolean inReach(Entity target, double reach) { return EntityUtil.reachDistance(target) <= reach; }

    /** Whether the rotation the server has now aims at the target within reach (no block in the way). */
    public static boolean serverAimsAt(Entity target, double reach) {
        MovingObjectPosition hit = RaycastUtil.serverMouseOver(reach, reach);
        return hit != null && hit.entityHit == target;
    }

    /** Whether the camera's crosshair is on the target within reach. */
    public static boolean cameraAimsAt(Entity target, double reach) {
        return RaycastUtil.isLookingAt(target, RotationManager.cameraYaw(), RotationManager.cameraPitch(), reach);
    }

    /** A hit now would be a critical hit (falling, not on ground, ladder, water, blind or riding). */
    public static boolean wouldCrit() {
        EntityPlayerSP p = mc.thePlayer;
        return p != null && p.fallDistance > 0 && !p.onGround && !p.isOnLadder() && !p.isInWater() && !p.isPotionActive(Potion.blindness) && p.ridingEntity == null;
    }

    /** Whether the server will apply sprint knockback to your next hit (its sprint flag is on). */
    public static boolean nextHitSprintKnockback() { return ServerState.sprinting(); }

    /**
     * Whether the target can take full damage now: it is not in the 10-tick protection after its last hurt (as far as
     * the client sees it; the server keeps its own count).
     */
    public static boolean canDamage(EntityLivingBase target) {
        return target.hurtTime == 0 || EntityTracker.ticksSinceHurt(target) >= 10;
    }

    /** Ticks since your last attack packet (from what really left). */
    public static long ticksSinceLastAttack() {
        long last = ServerState.lastAttack();
        return last == 0 ? Long.MAX_VALUE / 2 : (System.currentTimeMillis() - last) / 50;
    }
}
