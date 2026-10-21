package gale.util;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;

/** Facts about you: movement, health, held item, ground below, ping. */
public final class PlayerUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field IN_WEB = ReflectUtil.field(Entity.class, "isInWeb", "field_70134_J");
    private static final Field JUMP_TICKS = ReflectUtil.field(net.minecraft.entity.EntityLivingBase.class, "jumpTicks", "field_70773_bE");

    private PlayerUtil() { }

    private static EntityPlayerSP p() { return mc.thePlayer; }

    /** Whether movement keys are pressed (this tick's input). */
    public static boolean isMoving() { return p() != null && (p().movementInput.moveForward != 0 || p().movementInput.moveStrafe != 0); }

    /** Horizontal speed in blocks per tick. */
    public static double speed() { return p() == null ? 0 : Math.hypot(p().motionX, p().motionZ); }

    /** Horizontal distance moved last tick, in blocks per second. */
    public static double bps() { return p() == null ? 0 : Math.hypot(p().posX - p().lastTickPosX, p().posZ - p().lastTickPosZ) * 20; }

    public static boolean isInLiquid() { return p() != null && (p().isInWater() || p().isInLava()); }

    public static boolean isInWeb() { Boolean b = ReflectUtil.get(IN_WEB, p()); return b != null && b; }

    /** Ticks before you can jump again while holding jump (vanilla: 10 after a jump). */
    public static int jumpTicks() { return ReflectUtil.getInt(JUMP_TICKS, p(), 0); }

    public static void setJumpTicks(int ticks) { ReflectUtil.set(JUMP_TICKS, p(), ticks); }

    /** Health plus absorption hearts. */
    public static float health() { return p() == null ? 0 : p().getHealth() + p().getAbsorptionAmount(); }

    public static boolean hasEffect(Potion potion) { return p() != null && p().isPotionActive(potion); }

    /** Level of an effect: 0 when absent, 1 for level I. */
    public static int effectLevel(Potion potion) {
        return p() != null && p().isPotionActive(potion) ? p().getActivePotionEffect(potion).getAmplifier() + 1 : 0;
    }

    public static Vec3 eyes() { return p().getPositionEyes(1f); }

    public static ItemStack held() { return p() == null ? null : p().getHeldItem(); }

    public static boolean isHolding(Class<? extends Item> type) {
        ItemStack s = held();
        return s != null && type.isInstance(s.getItem());
    }

    public static boolean isHoldingSword() { return isHolding(ItemSword.class); }

    public static boolean isHoldingWeapon() { return isHolding(ItemSword.class) || isHolding(ItemAxe.class); }

    public static boolean isHoldingBlock() { return isHolding(ItemBlock.class); }

    /** The block you stand on (or would land on directly below your feet). */
    public static BlockPos blockUnder() {
        return new BlockPos(p().posX, p().getEntityBoundingBox().minY - 0.01, p().posZ);
    }

    /**
     * Distance from your feet down to the first block surface below (a ray straight down, 256 blocks); -1 when there is
     * nothing (void).
     */
    public static double distanceToGround() {
        if (p() == null) return -1;
        Vec3 from = new Vec3(p().posX, p().posY, p().posZ);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, from.addVector(0, -256, 0), false, true, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return -1;
        return Math.max(0, p().posY - hit.hitVec.yCoord);
    }

    /** Nothing solid anywhere under your hitbox down to the bottom of the world. */
    public static boolean isOverVoid() {
        if (p() == null) return false;
        AxisAlignedBB bb = p().getEntityBoundingBox();
        AxisAlignedBB column = new AxisAlignedBB(bb.minX, 0, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
        return mc.theWorld.getCollidingBoundingBoxes(p(), column).isEmpty();
    }

    /** B4's isOnEdge: the block right under the centre of your feet is air. */
    public static boolean isOnEdge() {
        if (p() == null) return false;
        double y = p().posY - (p().posY % 1.0 == 0.0 ? 1 : 0);
        return mc.theWorld.getBlockState(new BlockPos(p().posX, y, p().posZ)).getBlock().getMaterial() == Material.air;
    }

    /** Your ping from the tab list, in ms (0 when unknown). */
    public static int ping() {
        if (p() == null || mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(p().getUniqueID());
        return info == null ? 0 : info.getResponseTime();
    }

    /** B4-style movement direction from the keys: NONE, FORWARD, FORWARD_LEFT, LEFT, BACK_RIGHT... */
    public static String direction() {
        if (p() == null) return "NONE";
        float f = p().movementInput.moveForward, s = p().movementInput.moveStrafe;
        if (f == 0) return s == 0 ? "NONE" : s > 0 ? "LEFT" : "RIGHT";
        String head = f > 0 ? "FORWARD" : "BACK";
        return s == 0 ? head : head + (s > 0 ? "_LEFT" : "_RIGHT");
    }

    /** The hotbar slot you hold (0..8). */
    public static int slot() { return p().inventory.currentItem; }

    /** Holds another hotbar slot; the game tells the server on its next tick. */
    public static void setSlot(int slot) {
        if (slot >= 0 && slot < 9) p().inventory.currentItem = slot;
    }
}
