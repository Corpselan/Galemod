package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Entities: lists, alive and bot checks, distances as the server measures reach, interpolated positions. */
public final class EntityUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private EntityUtil() { }

    /** Other players in the world. */
    public static List<EntityPlayer> players() {
        List<EntityPlayer> out = new ArrayList<>();
        for (EntityPlayer p : mc.theWorld.playerEntities) if (p != mc.thePlayer) out.add(p);
        return out;
    }

    /** Living entities in the world other than you. */
    public static List<EntityLivingBase> living() {
        List<EntityLivingBase> out = new ArrayList<>();
        for (Entity e : mc.theWorld.loadedEntityList) if (e instanceof EntityLivingBase && e != mc.thePlayer) out.add((EntityLivingBase) e);
        return out;
    }

    public static boolean isAlive(Entity e) {
        return e != null && !e.isDead && (!(e instanceof EntityLivingBase) || ((EntityLivingBase) e).getHealth() > 0);
    }

    /**
     * A likely fake player (NPC, anti-cheat bot): not in the tab list, a Hypixel NPC id (UUID version 2), or a name a
     * real account cannot have.
     */
    public static boolean isBot(EntityPlayer p) {
        if (p == mc.thePlayer) return false;
        if (mc.getNetHandler() == null) return false;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(p.getUniqueID());
        if (info == null) return true;
        if (p.getUniqueID().version() == 2) return true;
        return !VALID_NAME.matcher(p.getName()).matches();
    }

    /** Distance from your eyes to the nearest point of the entity's hitbox: what reach is measured against. */
    public static double reachDistance(Entity e) {
        Vec3 eyes = PlayerUtil.eyes();
        AxisAlignedBB bb = e.getEntityBoundingBox();
        double x = MathUtil.clamp(eyes.xCoord, bb.minX, bb.maxX) - eyes.xCoord;
        double y = MathUtil.clamp(eyes.yCoord, bb.minY, bb.maxY) - eyes.yCoord;
        double z = MathUtil.clamp(eyes.zCoord, bb.minZ, bb.maxZ) - eyes.zCoord;
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Distance between feet positions. */
    public static double distance(Entity e) { return mc.thePlayer.getDistanceToEntity(e); }

    /** Where the entity is drawn in this frame. */
    public static Vec3 interpolated(Entity e, float partialTicks) {
        return new Vec3(MathUtil.interpolate(e.lastTickPosX, e.posX, partialTicks), MathUtil.interpolate(e.lastTickPosY, e.posY, partialTicks),
            MathUtil.interpolate(e.lastTickPosZ, e.posZ, partialTicks));
    }

    /** The entity's hitbox where it is drawn in this frame. */
    public static AxisAlignedBB interpolatedBox(Entity e, float partialTicks) {
        Vec3 at = interpolated(e, partialTicks);
        return e.getEntityBoundingBox().offset(at.xCoord - e.posX, at.yCoord - e.posY, at.zCoord - e.posZ);
    }

    /** Health plus absorption. */
    public static float health(EntityLivingBase e) { return e.getHealth() + e.getAbsorptionAmount(); }

    /** Horizontal speed in blocks per tick (from the last two positions: works for other players too). */
    public static double speed(Entity e) { return Math.hypot(e.posX - e.lastTickPosX, e.posZ - e.lastTickPosZ); }
}
