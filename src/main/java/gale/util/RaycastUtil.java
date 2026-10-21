package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.List;

/** What a rotation looks at: blocks, entities, or both as the crosshair would see them. */
public final class RaycastUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private RaycastUtil() { }

    /** The block a ray from your eyes hits within reach, or null. */
    public static MovingObjectPosition block(float yaw, float pitch, double reach) {
        Vec3 eyes = PlayerUtil.eyes(), look = RotationUtil.look(yaw, pitch);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes, eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach), false, false, true);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? hit : null;
    }

    /** The nearest entity a ray from your eyes hits within reach (hitboxes grown as the game grows them), or null. */
    public static MovingObjectPosition entity(float yaw, float pitch, double reach) {
        Vec3 eyes = PlayerUtil.eyes(), look = RotationUtil.look(yaw, pitch);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        List<Entity> list = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer,
            mc.thePlayer.getEntityBoundingBox().addCoord(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach).expand(1, 1, 1));
        Entity best = null;
        Vec3 bestHit = null;
        double bestDist = reach;
        for (Entity e : list) {
            if (!e.canBeCollidedWith()) continue;
            float border = e.getCollisionBorderSize();
            AxisAlignedBB bb = e.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = bb.calculateIntercept(eyes, end);
            if (bb.isVecInside(eyes)) {
                if (bestDist >= 0) { best = e; bestHit = hit == null ? eyes : hit.hitVec; bestDist = 0; }
            } else if (hit != null) {
                double d = eyes.distanceTo(hit.hitVec);
                if (d < bestDist) { best = e; bestHit = hit.hitVec; bestDist = d; }
            }
        }
        return best == null ? null : new MovingObjectPosition(best, bestHit);
    }

    /** What the crosshair would show for this rotation: an entity within entityReach if no block is closer, else the block. */
    public static MovingObjectPosition mouseOver(float yaw, float pitch, double entityReach, double blockReach) {
        MovingObjectPosition block = block(yaw, pitch, blockReach);
        MovingObjectPosition ent = entity(yaw, pitch, entityReach);
        if (ent != null && (block == null || PlayerUtil.eyes().distanceTo(ent.hitVec) <= PlayerUtil.eyes().distanceTo(block.hitVec))) return ent;
        return block;
    }

    /**
     * What the server rotation looks at (the silent one while active): what a hit or a placement made this tick
     * would aim at as the server checks it.
     */
    public static MovingObjectPosition serverMouseOver(double entityReach, double blockReach) {
        return mouseOver(gale.core.RotationManager.yaw(), gale.core.RotationManager.pitch(), entityReach, blockReach);
    }

    /** Whether this rotation looks at the entity within reach (and no block is in the way). */
    public static boolean isLookingAt(Entity target, float yaw, float pitch, double reach) {
        MovingObjectPosition hit = mouseOver(yaw, pitch, reach, reach);
        return hit != null && hit.entityHit == target;
    }

    /** Whether you can see the entity (a clear line from your eyes to its eyes). */
    public static boolean canSee(Entity e) { return mc.thePlayer.canEntityBeSeen(e); }
}
