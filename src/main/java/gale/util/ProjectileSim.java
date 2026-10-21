package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemExpBottle;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a projectile thrown or shot from a point would fly, by 1.8.9's rules (no random spread): arrows, snowballs,
 * eggs and pearls, splash potions, bottles o' enchanting, the fishing rod's hook. Stops at the first block or entity.
 */
public final class ProjectileSim {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public enum Kind {
        /** speed = bow charge (0..1) * 3 */
        ARROW(0.05, 0.99, 0, 0),
        THROWN(0.03, 0.99, 1.5, 0),
        POTION(0.05, 0.99, 0.5, -20),
        EXP_BOTTLE(0.07, 0.99, 0.7, -20),
        ROD(0.04, 0.92, 1.5, 0);

        final double gravity, drag, speed;
        final float pitchOffset;

        Kind(double gravity, double drag, double speed, float pitchOffset) {
            this.gravity = gravity;
            this.drag = drag;
            this.speed = speed;
            this.pitchOffset = pitchOffset;
        }

        /** What the item throws or shoots, or null when it is not a projectile. */
        public static Kind of(ItemStack stack) {
            if (stack == null) return null;
            Item i = stack.getItem();
            if (i instanceof ItemBow) return ARROW;
            if (i instanceof ItemSnowball || i instanceof ItemEgg || i instanceof ItemEnderPearl) return THROWN;
            if (i instanceof ItemPotion) return ItemPotion.isSplash(stack.getMetadata()) ? POTION : null;
            if (i instanceof ItemExpBottle) return EXP_BOTTLE;
            if (i instanceof ItemFishingRod) return ROD;
            return null;
        }
    }

    public static final class Result {
        /** positions, starting where the projectile appears */
        public final List<Vec3> path = new ArrayList<>();
        /** the block or entity hit, null when it flew the whole time */
        public MovingObjectPosition hit;
        public int ticks;
    }

    private ProjectileSim() { }

    /** The bow's charge (0..1) after holding it this many ticks, as ItemBow works it out. */
    public static double bowCharge(int useTicks) {
        float f = useTicks / 20f;
        f = (f * f + f * 2) / 3;
        return Math.min(1, f);
    }

    /**
     * @param eye     the shooter's eye position (posY + eye height)
     * @param charge  for arrows: the bow charge 0..1 (ignored for the rest)
     * @param shooter left out of the entity hits for the first 5 ticks, like the game does
     */
    public static Result run(Kind kind, Vec3 eye, float yaw, float pitch, double charge, int maxTicks, Entity shooter) {
        Result r = new Result();
        float yr = yaw / 180f * (float) Math.PI, pr = pitch / 180f * (float) Math.PI;
        double x = eye.xCoord - MathHelper.cos(yr) * 0.16f, y = eye.yCoord - 0.1, z = eye.zCoord - MathHelper.sin(yr) * 0.16f;
        double mx = -MathHelper.sin(yr) * MathHelper.cos(pr), mz = MathHelper.cos(yr) * MathHelper.cos(pr);
        double my = -MathHelper.sin((pitch + kind.pitchOffset) / 180f * (float) Math.PI);
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        double speed = kind == Kind.ARROW ? Math.max(0, Math.min(1, charge)) * 3 : kind.speed;
        mx = mx / len * speed;
        my = my / len * speed;
        mz = mz / len * speed;
        r.path.add(new Vec3(x, y, z));
        for (int t = 0; t < maxTicks; t++) {
            Vec3 from = new Vec3(x, y, z), to = new Vec3(x + mx, y + my, z + mz);
            MovingObjectPosition block = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
            Vec3 end = block != null ? block.hitVec : to;
            MovingObjectPosition entity = entityHit(from, end, mx, my, mz, t, shooter);
            MovingObjectPosition hit = entity != null ? entity : block;
            if (hit != null) {
                r.hit = hit;
                r.path.add(hit.hitVec);
                r.ticks = t + 1;
                return r;
            }
            x += mx;
            y += my;
            z += mz;
            if (kind == Kind.ROD) {
                my -= kind.gravity;
                mx *= kind.drag;
                my *= kind.drag;
                mz *= kind.drag;
            } else {
                mx *= kind.drag;
                my *= kind.drag;
                mz *= kind.drag;
                my -= kind.gravity;
            }
            r.path.add(new Vec3(x, y, z));
            r.ticks = t + 1;
            if (y < -64) break;
        }
        return r;
    }

    private static MovingObjectPosition entityHit(Vec3 from, Vec3 to, double mx, double my, double mz, int tick, Entity shooter) {
        AxisAlignedBB area = new AxisAlignedBB(from.xCoord, from.yCoord, from.zCoord, from.xCoord, from.yCoord, from.zCoord).addCoord(mx, my, mz).expand(1, 1, 1);
        MovingObjectPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.theWorld.getEntitiesWithinAABBExcludingEntity(null, area)) {
            if (!e.canBeCollidedWith() || e == shooter && tick + 1 < 5) continue;
            AxisAlignedBB box = e.getEntityBoundingBox().expand(0.3, 0.3, 0.3);
            MovingObjectPosition m = box.calculateIntercept(from, to);
            if (m == null) continue;
            double d = from.squareDistanceTo(m.hitVec);
            if (d < bestDist) {
                bestDist = d;
                best = new MovingObjectPosition(e, m.hitVec);
            }
        }
        return best;
    }
}
