package gale.script.api;

import gale.core.BlockBreaks;
import gale.core.EntityTracker;
import gale.event.Priority;
import gale.event.impl.BlockBreakAnimEvent;
import gale.script.ScriptEvents;
import gale.core.ServerState;
import gale.script.Args;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.PacketData;
import gale.util.EntityUtil;
import gale.util.Mc;
import gale.util.PlayerSim;
import gale.util.ProjectileSim;
import gale.util.TeamUtil;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the game knows that B4 scripts cannot read: where the server has entities, how fast others move, head
 * turning, item use time, hits; blocks' hardness, collision boxes and states; rays from any point; where you will
 * move and where a projectile would fly.
 */
public final class DataApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "data";

    private DataApi() { }

    public static void install() {
        // ---------------------------------------------------------------- the game
        Functions.add(AREA, "timer", "", "游戏速度（Timer 的 timerSpeed，正常是 1.0）。", a -> (double) Mc.timerSpeed());

        // ---------------------------------------------------------------- entities
        Functions.add(AREA, "entity.serverPos", "<实体>",
            "服务器那边的位置（Vec3）：别人是服务器最后发来的位置（没有客户端的平滑），你自己是最后发给服务器的位置。可以用来做 backtrack。", a -> {
                Entity e = a.entity(0);
                if (e == mc.thePlayer) return new Vec3(ServerState.x(), ServerState.y(), ServerState.z());
                return new Vec3(e.serverPosX / 32.0, e.serverPosY / 32.0, e.serverPosZ / 32.0);
            });
        Functions.add(AREA, "entity.velocity", "<实体> [几 tick 平均=1]",
            "速度（Vec3，格/tick），别的玩家也能算（服务器不发他们的速度，这是按位置变化算的）。", a -> EntityTracker.velocity(a.entity(0), a.integer(1, 1)));
        Functions.add(AREA, "entity.position", "<实体> <几 tick 前>", "几 tick 前的位置（Vec3，最多 40 tick）。", a -> EntityTracker.position(a.entity(0), a.integer(1)));
        Functions.add(AREA, "entity.history", "<实体>", "最近 40 tick 的位置（List<Vec3>，新的在前）。", a -> EntityTracker.history(a.entity(0)));
        Functions.add(AREA, "entity.predict", "<实体> <tick 数>", "按现在的速度推算几 tick 后的位置（Vec3）。", a -> EntityTracker.predict(a.entity(0), a.integer(1)));
        Functions.add(AREA, "entity.headYaw", "<实体>", "头的朝向（rotationYawHead，Double）。", a -> (double) living(a, 0).rotationYawHead);
        Functions.add(AREA, "entity.bodyYaw", "<实体>", "身体的朝向（renderYawOffset，Double）。", a -> (double) living(a, 0).renderYawOffset);
        Functions.add(AREA, "entity.useTicks", "<实体>", "玩家已经用（吃、喝、拉弓、格挡）手里的东西多少 tick，没在用是 0。", a -> {
            Entity e = a.entity(0);
            return e instanceof EntityPlayer && ((EntityPlayer) e).isUsingItem() ? ((EntityPlayer) e).getItemInUseDuration() : 0;
        });
        Functions.add(AREA, "entity.hurtTicks", "<实体>", "距离它上次受伤多少 tick（看到受伤动画开始算）。", a -> EntityTracker.ticksSinceHurt(a.entity(0)));
        Functions.add(AREA, "entity.attackedTicks", "<实体>", "距离你上次打它多少 tick。", a -> EntityTracker.ticksSinceAttacked(a.entity(0)));
        Functions.add(AREA, "entity.hitsByYou", "<实体>", "你连续打中它几下（它打你或者断了就清零）。", a -> EntityTracker.hitsByYou(a.entity(0)));
        Functions.add(AREA, "combo", "", "你现在的连击数。", a -> EntityTracker.combo());
        Functions.add(AREA, "youHurtTicks", "", "距离你上次受伤多少 tick。", a -> EntityTracker.ticksSinceYouWereHurt());
        Functions.add(AREA, "entity.box", "<实体>", "碰撞箱 double[]{minX, minY, minZ, maxX, maxY, maxZ}。", a -> box(a.entity(0).getEntityBoundingBox()));
        Functions.add(AREA, "entity.reach", "<实体>", "服务器判断够不够得着时用的距离（从你的眼睛到它碰撞箱最近的点）。", a -> EntityUtil.reachDistance(a.entity(0)));
        Functions.add(AREA, "entity.isBot", "<实体>", "是不是假人：不在 Tab 列表里、Hypixel NPC、名字不合法。", a -> {
            Entity e = a.entity(0);
            return e instanceof EntityPlayer && EntityUtil.isBot((EntityPlayer) e);
        });
        Functions.add(AREA, "entity.isTeammate", "<实体>", "是不是队友（计分板队伍、名字颜色或盔甲颜色，按 Targets 模块的设置）。", a -> {
            Entity e = a.entity(0);
            return e instanceof EntityPlayer && TeamUtil.isTeammate((EntityPlayer) e);
        });
        Functions.add(AREA, "entity.canSee", "<实体>", "你的眼睛能不能看到它（中间没有方块）。", a -> mc.thePlayer.canEntityBeSeen(a.entity(0)));
        Functions.add(AREA, "entity.data", "<实体>", "实体的同步数据（DataWatcher）：Map，编号 -> 值。0 号是状态位（着火、蹲、疾跑、用物品、隐身）。", a -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<DataWatcher.WatchableObject> all = a.entity(0).getDataWatcher().getAllWatched();
            if (all != null) for (DataWatcher.WatchableObject w : all) out.put(String.valueOf(w.getDataValueId()), PacketData.toScript(w.getObject(), 1));
            return out;
        });

        // ---------------------------------------------------------------- blocks
        Functions.add(AREA, "block.hardness", "<x> <y> <z>", "方块硬度（石头 1.5，基岩 -1）。", a -> {
            BlockPos p = pos(a, 0);
            return (double) mc.theWorld.getBlockState(p).getBlock().getBlockHardness(mc.theWorld, p);
        });
        Functions.add(AREA, "block.breakTicks", "<x> <y> <z> [快捷栏格子 0-8=手上的]",
            "用那一格的东西挖这个方块要几 tick（算上效率、急迫、挖掘疲劳、在水里、不在地上），挖不动是 -1。游戏线程。", a -> {
                CombatApi.gameThread("block.breakTicks");
                BlockPos p = pos(a, 0);
                EntityPlayerSP me = mc.thePlayer;
                int own = me.inventory.currentItem, slot = a.integer(3, own);
                if (slot < 0 || slot > 8) throw new IllegalArgumentException("格子要 0 到 8");
                me.inventory.currentItem = slot;
                try {
                    float perTick = mc.theWorld.getBlockState(p).getBlock().getPlayerRelativeBlockHardness(me, mc.theWorld, p);
                    return perTick <= 0 ? -1 : perTick >= 1 ? 1 : (int) Math.ceil(1 / perTick);
                } finally {
                    me.inventory.currentItem = own;
                }
            });
        Functions.add(AREA, "block.boxes", "<x> <y> <z>", "方块的碰撞箱（List<double[6]>，可能有几个，比如楼梯；没有碰撞箱是空的）。", a -> {
            BlockPos p = pos(a, 0);
            IBlockState s = mc.theWorld.getBlockState(p);
            List<AxisAlignedBB> boxes = new ArrayList<>();
            AxisAlignedBB mask = new AxisAlignedBB(p.getX() - 1, p.getY() - 1, p.getZ() - 1, p.getX() + 2, p.getY() + 2, p.getZ() + 2);
            s.getBlock().addCollisionBoxesToList(mc.theWorld, p, s, mask, boxes, mc.thePlayer);
            List<Object> out = new ArrayList<>();
            for (AxisAlignedBB b : boxes) out.add(box(b));
            return out;
        });
        Functions.add(AREA, "block.state", "<x> <y> <z>", "方块状态：Map，\"name\" 是方块名（\"minecraft:stone\"），其余是属性（\"facing\" -> \"north\"...）。", a -> {
            IBlockState s = mc.theWorld.getBlockState(pos(a, 0));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", String.valueOf(Block.blockRegistry.getNameForObject(s.getBlock())));
            for (Map.Entry<IProperty, Comparable> e : s.getProperties().entrySet()) out.put(e.getKey().getName(), String.valueOf(e.getValue()));
            return out;
        });
        Functions.add(AREA, "block.light", "<x> <y> <z>", "那里的亮度 0-15。", a -> mc.theWorld.getLightFromNeighbors(pos(a, 0)));
        Functions.add(AREA, "rayTraceBlocks", "<x1> <y1> <z1> <x2> <y2> <z2> [停在液体=false] [忽略无碰撞箱方块=false] [返回最后一个无碰撞箱方块=false]",
            "和游戏的 World.rayTraceBlocks 完全一样：从第一个点到第二个点碰到的第一个方块。没碰到返回 null，"
                + "碰到返回 Object[]{ Vec3 碰撞点, Vec3 方块坐标, String 面 }。", a -> {
                world();
                Vec3 from = new Vec3(a.num(0), a.num(1), a.num(2)), to = new Vec3(a.num(3), a.num(4), a.num(5));
                MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, to, a.bool(6, false), a.bool(7, false), a.bool(8, false));
                if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
                return new Object[]{ B4Link.wrap(hit.hitVec), B4Link.wrap(hit.getBlockPos()), hit.sideHit.name() };
            });
        Functions.add(AREA, "raycast", "<x> <y> <z> <yaw> <pitch> <距离> [算实体=true]",
            "从任意一点朝一个方向看，先碰到的方块或实体（实体按准星的规则：碰撞箱加上边框，不算你自己）。没碰到 null；"
                + "碰到 Object[]{ String \"block\"/\"entity\", Vec3 碰撞点, 方块坐标 Vec3 或 Entity, String 面（实体是 null） }。", a -> {
                world();
                Vec3 from = new Vec3(a.num(0), a.num(1), a.num(2));
                Vec3 look = gale.util.RotationUtil.look((float) a.num(3), (float) a.num(4));
                double range = a.num(5);
                Vec3 to = from.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
                MovingObjectPosition block = mc.theWorld.rayTraceBlocks(from, to, false, false, true);
                double blockDist = block == null ? range : from.distanceTo(block.hitVec);
                if (a.bool(6, true)) {
                    Object[] e = entityRay(from, to, blockDist);
                    if (e != null) return e;
                }
                if (block == null || block.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
                return new Object[]{ "block", B4Link.wrap(block.hitVec), B4Link.wrap(block.getBlockPos()), block.sideHit.name() };
            });

        // ---------------------------------------------------------------- who is breaking which block (core: BlockBreaks)
        ScriptEvents.define(AREA, "mining", "{Entity 在挖的人或null, Integer 实体id, Vec3 方块, Integer 阶段}",
            "服务器告诉你有人挖方块的进度变了（S25，就是别人挖方块时你看到的裂纹），游戏线程。阶段 0-9（9 快挖掉了），"
                + "-1 = 停了或者挖掉了。实体不在你的世界里时是 null（实体 id 还在）。你自己挖的不走这里（服务器不发给挖的人）。"
                + "返回非 0 = 不显示这道裂纹。",
            BlockBreakAnimEvent.class, Priority.LOW, e -> {
                if (e.isCancelled() || mc.theWorld == null) return;
                Object[] a = { B4Link.wrap(mc.theWorld.getEntityByID(e.breakerId)), e.breakerId, B4Link.wrap(e.pos), e.stage };
                if (ScriptEvents.fire("mining", a)) e.cancel();
            });
        Functions.add(AREA, "mining.list", "",
            "现在所有正在被挖的方块（List<Map>，你自己挖的也在）。每个 Map：\"entity\" 在挖的人（不在你的世界里是 null）、\"entityId\"、"
                + "\"name\" 名字、\"pos\" 方块 Vec3、\"stage\" 0-9、\"progress\" 0.1-1.0、\"ticks\" 从开始挖这个方块过了几 tick、"
                + "\"idle\" 服务器上次更新后过了几 tick（一直不更新可能是人走远了）。游戏线程。", a -> {
                CombatApi.gameThread("mining.list");
                return breaking();
            });
        Functions.add(AREA, "mining.at", "<x> <y> <z>", "这个方块正在被挖的话，挖得最快的那个人（Map，同 mining.list），没人挖是 null。游戏线程。", a -> {
            CombatApi.gameThread("mining.at");
            BlockPos p = pos(a, 0);
            Map<String, Object> best = null;
            for (Map<String, Object> m : breaking()) {
                if (!p.equals(new BlockPos((net.minecraft.util.Vec3) B4Link.unwrap(m.get("pos"))))) continue;
                if (best == null || (Integer) m.get("stage") > (Integer) best.get("stage")) best = m;
            }
            return best;
        });
        Functions.add(AREA, "mining.by", "<实体>", "这个人正在挖的方块（Map，同 mining.list），没在挖是 null。游戏线程。", a -> {
            CombatApi.gameThread("mining.by");
            int id = a.entity(0).getEntityId();
            for (Map<String, Object> m : breaking()) if ((Integer) m.get("entityId") == id) return m;
            return null;
        });

        // ---------------------------------------------------------------- prediction
        Functions.add(AREA, "sim.path", "<tick 数> [前后 -1..1 左右 -1..1 跳 蹲]",
            "按 1.8.9 的移动规则和真实方块推算你接下来每 tick 的位置（List<Vec3>）。不给按键就用这 tick 的按键。不算水、岩浆、梯子、蜘蛛网和还没到的击退。",
            a -> sim(a).path(Math.min(200, a.integer(0))));
        Functions.add(AREA, "sim.landTicks", "[最多 tick=60] [前后 左右 跳 蹲]", "推算再过几 tick 落地，在地上是 0，算不到是 -1。", a -> {
            PlayerSim s = a.size() > 1 ? sim(a) : PlayerSim.of(mc.thePlayer);
            return s.onGround ? 0 : s.ticksToLand(Math.min(200, a.integer(0, 60)));
        });
        Functions.add(AREA, "projectile.path", "[yaw] [pitch] [弓蓄力 0..1] [种类 arrow|thrown|potion|bottle|rod]",
            "手里的弓、雪球、蛋、珍珠、喷溅药水、附魔瓶、鱼竿从你眼睛射出去会怎么飞（不算随机偏差）。不给朝向就用你现在的朝向，"
                + "不给蓄力就按现在拉弓的时间（没拉就是满）。返回 Map：\"path\" List<Vec3>、\"hit\" \"block\"/\"entity\"/null、"
                + "\"entity\" 被打中的实体、\"pos\" 落点 Vec3、\"ticks\" 飞了几 tick。手里不是这些东西又没给种类就是 null。", a -> {
                CombatApi.gameThread("projectile.path");
                EntityPlayerSP me = mc.thePlayer;
                ProjectileSim.Kind kind = a.has(3) ? kind(a.str(3)) : ProjectileSim.Kind.of(me.getHeldItem());
                if (kind == null) return null;
                float yaw = a.has(0) ? (float) a.num(0) : me.rotationYaw, pitch = a.has(1) ? (float) a.num(1) : me.rotationPitch;
                double charge = a.has(2) ? a.num(2) : me.isUsingItem() ? ProjectileSim.bowCharge(me.getItemInUseDuration()) : 1;
                ProjectileSim.Result r = ProjectileSim.run(kind, me.getPositionEyes(1f), yaw, pitch, charge, 200, me);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("path", r.path);
                out.put("hit", r.hit == null ? null : r.hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY ? "entity" : "block");
                out.put("entity", r.hit == null ? null : r.hit.entityHit);
                out.put("pos", r.path.get(r.path.size() - 1));
                out.put("ticks", r.ticks);
                return wrapAll(out);
            });
    }

    private static ProjectileSim.Kind kind(String s) {
        switch (s.trim().toLowerCase()) {
            case "arrow": case "bow": return ProjectileSim.Kind.ARROW;
            case "thrown": case "snowball": case "egg": case "pearl": return ProjectileSim.Kind.THROWN;
            case "potion": return ProjectileSim.Kind.POTION;
            case "bottle": case "xp": return ProjectileSim.Kind.EXP_BOTTLE;
            case "rod": return ProjectileSim.Kind.ROD;
            default: throw new IllegalArgumentException("种类只有 arrow、thrown、potion、bottle、rod");
        }
    }

    private static Map<String, Object> wrapAll(Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) e.setValue(B4Link.wrap(e.getValue()));
        return m;
    }

    private static PlayerSim sim(Args a) {
        CombatApi.gameThread("sim");
        PlayerSim s = PlayerSim.of(mc.thePlayer);
        if (a.size() > 1) s.input((float) a.num(1, 0), (float) a.num(2, 0), a.bool(3, false), a.bool(4, false));
        return s;
    }

    /** The nearest entity the ray goes through (before maxDist), by the crosshair's rules; null for none. */
    static Object[] entityRay(Vec3 from, Vec3 to, double maxDist) {
        Vec3 dir = to.subtract(from);
        AxisAlignedBB area = new AxisAlignedBB(from.xCoord, from.yCoord, from.zCoord, from.xCoord, from.yCoord, from.zCoord).addCoord(dir.xCoord, dir.yCoord, dir.zCoord).expand(1, 1, 1);
        Entity best = null;
        Vec3 bestHit = null;
        double bestDist = maxDist;
        for (Entity e : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, area)) {
            if (!e.canBeCollidedWith()) continue;
            float border = e.getCollisionBorderSize();
            AxisAlignedBB box = e.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition m = box.calculateIntercept(from, to);
            if (box.isVecInside(from)) {
                if (bestDist >= 0) {
                    best = e;
                    bestHit = m == null ? from : m.hitVec;
                    bestDist = 0;
                }
            } else if (m != null) {
                double d = from.distanceTo(m.hitVec);
                if (d < bestDist) {
                    best = e;
                    bestHit = m.hitVec;
                    bestDist = d;
                }
            }
        }
        return best == null ? null : new Object[]{ "entity", B4Link.wrap(bestHit), B4Link.wrap(best), null };
    }

    /** The game's own list of block cracks (what you see), with who makes each. */
    private static List<Map<String, Object>> breaking() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BlockBreaks.Break b : BlockBreaks.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entity", B4Link.wrap(b.breaker));
            m.put("entityId", b.breakerId);
            m.put("name", b.breaker == null ? null : b.breaker.getName());
            m.put("pos", B4Link.wrap(b.pos));
            m.put("stage", b.stage);
            m.put("progress", (b.stage + 1) / 10.0);
            m.put("ticks", b.ticks);
            m.put("idle", b.idle);
            out.add(m);
        }
        return out;
    }

    private static void world() {
        if (mc.theWorld == null || mc.thePlayer == null) throw new IllegalStateException("要在世界里调用");
    }

    private static BlockPos pos(Args a, int i) {
        world();
        return new BlockPos(Math.floor(a.num(i)), Math.floor(a.num(i + 1)), Math.floor(a.num(i + 2)));
    }

    private static EntityLivingBase living(Args a, int i) {
        Entity e = a.entity(i);
        if (!(e instanceof EntityLivingBase)) throw new IllegalArgumentException("要生物（玩家、怪物、动物）");
        return (EntityLivingBase) e;
    }

    static double[] box(AxisAlignedBB b) { return new double[]{ b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ }; }

    static ItemStack held() { return mc.thePlayer == null ? null : mc.thePlayer.getHeldItem(); }
}
