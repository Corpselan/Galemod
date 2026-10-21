package gale.script.api;

import gale.Gale;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.ScriptErrors;
import gale.script.ScriptEvents;
import gale.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * What the crosshair points at for this tick's clicks (Reach, Hitbox). The game picks it at the start of each tick,
 * just before it handles your clicks; while a script listens to "pick", Gale's copy of the game's player controller
 * passes that pick to the script right after it is made, and the clicks use what the script left.
 */
public final class PickApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "pick";

    /** Gale's own code that changes the pick (gale.b4port's AimAssist silent aim): before the scripts see it */
    private static final java.util.List<Runnable> HOOKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private PickApi() { }

    /** Runs every tick right after the game's pick, before the clicks (and before the scripts' "pick"). Game thread. */
    public static void hook(Runnable r) {
        if (!HOOKS.contains(r)) HOOKS.add(r);
    }

    public static void unhook(Runnable r) {
        HOOKS.remove(r);
    }

    public static void install() {
        ScriptEvents.define(AREA, "pick", "{String 类型, Entity 实体, Vec3 碰撞点, Vec3 方块, String 面}",
            "每 tick 游戏刚选好准星指着什么（这 tick 的左右键就按它算），游戏线程，在点击之前。类型是 \"entity\"、\"block\" 或 \"miss\"。"
                + "改它：args[0]=\"entity\" 加 args[1]=实体（args[2] 碰撞点可不给）；args[0]=\"block\" 加 args[3] 方块坐标、args[4] 面；"
                + "args[0]=\"miss\" = 什么都没指着。pick.entity 可以按更远的距离、更大的碰撞箱找实体。");
        Functions.add(AREA, "pick.entity", "<距离> [碰撞箱放大=0] [yaw] [pitch]",
            "按原版准星的规则找实体，但用你给的距离和放大的碰撞箱，被方块挡住的不算。从你的眼睛出发，不给朝向就用你现在的朝向。"
                + "返回 Object[]{ Entity 实体, Vec3 碰撞点 } 或 null。游戏线程。", a -> {
                CombatApi.gameThread("pick.entity");
                float yaw = a.has(2) ? (float) a.num(2) : mc.thePlayer.rotationYaw, pitch = a.has(3) ? (float) a.num(3) : mc.thePlayer.rotationPitch;
                return pickEntity(a.num(0), a.num(1, 0), yaw, pitch);
            });
        Functions.add(AREA, "pick.current", "", "现在准星指着什么：Object[]{ 类型, 实体, 碰撞点, 方块, 面 }，和 pick 事件的 args 一样。", a -> describe(mc.objectMouseOver));
    }

    /** Every tick: puts Gale's copy of the player controller in while a script listens to "pick". */
    static void tick() {
        if (!ScriptEvents.has("pick") && HOOKS.isEmpty() || mc.playerController == null || mc.playerController instanceof HookedController) return;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return;
        try {
            mc.playerController = HookedController.copy(mc.playerController, net);
        } catch (Exception e) {
            Gale.LOG.warn("Could not hook the player controller", e);
        }
    }

    static Object[] describe(MovingObjectPosition m) {
        if (m == null) return new Object[]{ "miss", null, null, null, null };
        switch (m.typeOfHit) {
            case ENTITY: return new Object[]{ "entity", B4Link.wrap(m.entityHit), B4Link.wrap(m.hitVec), null, null };
            case BLOCK: return new Object[]{ "block", null, B4Link.wrap(m.hitVec), B4Link.wrap(m.getBlockPos()), m.sideHit == null ? null : m.sideHit.name() };
            default: return new Object[]{ "miss", null, m.hitVec == null ? null : B4Link.wrap(m.hitVec), null, null };
        }
    }

    /** Right after the game's pick: the script sees it and may change it. */
    private static void afterPick() {
        if (!ScriptEvents.has("pick") || mc.thePlayer == null) return;
        Object[] before = describe(mc.objectMouseOver), a = before.clone();
        boolean miss = ScriptEvents.fire("pick", a);
        if (miss) a[0] = "miss";
        boolean changed = false;
        for (int i = 0; i < a.length; i++) if (a[i] != before[i] && (a[i] == null || !a[i].equals(before[i]))) changed = true;
        if (!changed) return;
        String type = String.valueOf(a[0]).toLowerCase();
        if (type.equals("entity")) {
            Object e = B4Link.unwrap(a[1]);
            if (!(e instanceof Entity)) throw new IllegalArgumentException("pick：args[0] 是 \"entity\" 时 args[1] 要是实体");
            Entity target = (Entity) e;
            Object v = B4Link.unwrap(a[2]);
            Vec3 hit = v instanceof Vec3 ? (Vec3) v : closest(target);
            mc.objectMouseOver = new MovingObjectPosition(target, hit);
            mc.pointedEntity = target;
        } else if (type.equals("block")) {
            Object p = B4Link.unwrap(a[3]);
            if (!(p instanceof Vec3)) throw new IllegalArgumentException("pick：args[0] 是 \"block\" 时 args[3] 要是方块坐标");
            BlockPos pos = new BlockPos((Vec3) p);
            Object v = B4Link.unwrap(a[2]);
            EnumFacing face = a[4] == null ? EnumFacing.UP : EnumFacing.valueOf(String.valueOf(a[4]).toUpperCase());
            Vec3 hit = v instanceof Vec3 ? (Vec3) v : new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            mc.objectMouseOver = new MovingObjectPosition(hit, face, pos);
            mc.pointedEntity = null;
        } else {
            Vec3 eye = mc.thePlayer.getPositionEyes(1f);
            mc.objectMouseOver = new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS, eye, EnumFacing.UP, new BlockPos(eye));
            mc.pointedEntity = null;
        }
    }

    private static Vec3 closest(Entity e) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        net.minecraft.util.AxisAlignedBB b = e.getEntityBoundingBox();
        return new Vec3(Math.max(b.minX, Math.min(eye.xCoord, b.maxX)), Math.max(b.minY, Math.min(eye.yCoord, b.maxY)), Math.max(b.minZ, Math.min(eye.zCoord, b.maxZ)));
    }

    /** Like the game's entity pick: the nearest entity the ray hits before a block does, with boxes grown by expand. */
    static Object[] pickEntity(double range, double expand, float yaw, float pitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f), look = RotationUtil.look(yaw, pitch);
        Vec3 to = eye.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        MovingObjectPosition block = mc.theWorld.rayTraceBlocks(eye, to, false, false, true);
        double max = block == null ? range : eye.distanceTo(block.hitVec);
        Entity best = null;
        Vec3 bestHit = null;
        double bestDist = max;
        net.minecraft.util.AxisAlignedBB area = mc.thePlayer.getEntityBoundingBox().addCoord(look.xCoord * range, look.yCoord * range, look.zCoord * range).expand(1 + expand, 1 + expand, 1 + expand);
        for (Entity e : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, area)) {
            if (!e.canBeCollidedWith()) continue;
            double grow = e.getCollisionBorderSize() + expand;
            net.minecraft.util.AxisAlignedBB box = e.getEntityBoundingBox().expand(grow, grow, grow);
            MovingObjectPosition m = box.calculateIntercept(eye, to);
            if (box.isVecInside(eye)) {
                best = e;
                bestHit = m == null ? eye : m.hitVec;
                bestDist = 0;
            } else if (m != null && eye.distanceTo(m.hitVec) < bestDist) {
                best = e;
                bestHit = m.hitVec;
                bestDist = eye.distanceTo(m.hitVec);
            }
        }
        return best == null ? null : new Object[]{ B4Link.wrap(best), B4Link.wrap(bestHit) };
    }

    /** The game's player controller with every field copied from the one it replaces; it calls afterPick each tick. */
    private static final class HookedController extends PlayerControllerMP {
        private HookedController(NetHandlerPlayClient net) { super(mc, net); }

        static HookedController copy(PlayerControllerMP old, NetHandlerPlayClient net) throws IllegalAccessException {
            HookedController h = new HookedController(net);
            for (Field f : PlayerControllerMP.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(h, f.get(old));
            }
            return h;
        }

        @Override
        public void updateController() {
            super.updateController();
            for (Runnable r : HOOKS) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Gale.LOG.warn("A pick hook threw", t);
                }
            }
            try {
                afterPick();
            } catch (Throwable t) {
                ScriptErrors.report("pick 事件", null, t);
            }
        }
    }
}
