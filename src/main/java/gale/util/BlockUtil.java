package gale.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCake;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Reading blocks, finding blocks, and working out where to place one. */
public final class BlockUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private BlockUtil() { }

    public static IBlockState state(BlockPos pos) { return mc.theWorld.getBlockState(pos); }

    public static Block block(BlockPos pos) { return state(pos).getBlock(); }

    public static boolean isAir(BlockPos pos) { return mc.theWorld.isAirBlock(pos); }

    /** Air, water, grass and the like: a block placed there replaces it. */
    public static boolean isReplaceable(BlockPos pos) { return block(pos).isReplaceable(mc.theWorld, pos); }

    public static boolean isLiquid(BlockPos pos) { return block(pos).getMaterial().isLiquid(); }

    /** Solid material (you can stand on it or place against it). */
    public static boolean isSolid(BlockPos pos) {
        Block b = block(pos);
        return b.getMaterial().isSolid() && b != Blocks.barrier;
    }

    public static boolean isFullCube(BlockPos pos) { return block(pos).isFullCube(); }

    /** The block's collision box in world coordinates, or null when you can walk through it. */
    public static AxisAlignedBB collisionBox(BlockPos pos) {
        IBlockState s = state(pos);
        return s.getBlock().getCollisionBoundingBox(mc.theWorld, pos, s);
    }

    /** Right-clicking it opens or uses it instead of placing against it (chests, beds, doors, tables...). */
    public static boolean isInteractable(BlockPos pos) {
        Block b = block(pos);
        return b instanceof BlockContainer || b instanceof BlockBed || b instanceof BlockWorkbench || b instanceof BlockDoor
            || b instanceof BlockTrapDoor || b instanceof BlockFenceGate || b instanceof BlockButton || b instanceof BlockLever
            || b instanceof BlockAnvil || b instanceof BlockCake || b == Blocks.noteblock || b == Blocks.jukebox;
    }

    public static Vec3 center(BlockPos pos) { return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }

    /** The centre of one face of a block. */
    public static Vec3 faceCenter(BlockPos pos, EnumFacing face) {
        return new Vec3(pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5, pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
            pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
    }

    /** Squared distance from your eyes to the nearest point of the block. */
    public static double distanceSqToEyes(BlockPos pos) {
        Vec3 e = PlayerUtil.eyes();
        double x = MathUtil.clamp(e.xCoord, pos.getX(), pos.getX() + 1) - e.xCoord;
        double y = MathUtil.clamp(e.yCoord, pos.getY(), pos.getY() + 1) - e.yCoord;
        double z = MathUtil.clamp(e.zCoord, pos.getZ(), pos.getZ() + 1) - e.zCoord;
        return x * x + y * y + z * z;
    }

    /** Positions within a cube of radius r around center that pass the test, nearest first. */
    public static List<BlockPos> find(BlockPos center, int r, Predicate<BlockPos> test) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    BlockPos p = center.add(x, y, z);
                    if (test.test(p)) out.add(p);
                }
        out.sort(Comparator.comparingDouble(p -> p.distanceSq(center)));
        return out;
    }

    /** Beds within r blocks of you (each bed is two blocks), nearest first. */
    public static List<BlockPos> beds(int r) {
        return find(new BlockPos(mc.thePlayer), r, p -> block(p) instanceof BlockBed);
    }

    /** Where to click to put a block at a position: the block to click, its face, and the point on that face. */
    public static final class PlaceInfo {
        public final BlockPos against;
        public final EnumFacing face;
        public final Vec3 hitVec;
        public final BlockPos target;

        PlaceInfo(BlockPos target, BlockPos against, EnumFacing face) {
            this.target = target;
            this.against = against;
            this.face = face;
            this.hitVec = faceCenter(against, face);
        }

        /** The rotation that looks at the middle of the face to click. */
        public float[] rotation() { return RotationUtil.to(hitVec); }

        @Override
        public String toString() { return "place at " + target + " against " + against + " " + face; }
    }

    /**
     * How to place a block at target: a solid, non-interactable neighbour and the face of it that touches target,
     * preferring the block below. Null when target is not replaceable or has no neighbour to place against.
     */
    public static PlaceInfo placeInfo(BlockPos target) {
        if (!isReplaceable(target)) return null;
        EnumFacing[] order = { EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST, EnumFacing.UP };
        for (EnumFacing dir : order) {
            BlockPos against = target.offset(dir);
            if (isSolid(against) && !isInteractable(against)) return new PlaceInfo(target, against, dir.getOpposite());
        }
        return null;
    }

    /** Right-clicks the face to place the held block, as the game does; true when it placed. Swings the arm. */
    public static boolean place(PlaceInfo info) {
        if (info == null || mc.thePlayer.getHeldItem() == null) return false;
        boolean ok = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), info.against, info.face, info.hitVec);
        if (ok) mc.thePlayer.swingItem();
        return ok;
    }
}
