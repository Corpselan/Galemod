package gale.core;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.BlockBreakAnimEvent;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is breaking which block right now: the game's own list of block cracks (what the server sent as S25, the crack
 * other players see), with when each breaker started on its block. The server only sends these to players within 32
 * blocks of the block, and never to the one breaking it. Game thread.
 */
public final class BlockBreaks {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field DAMAGED = ReflectUtil.field(RenderGlobal.class, "damagedBlocks", "field_72738_E");
    private static final Field CLOUD_TICK = ReflectUtil.field(RenderGlobal.class, "cloudTickCounter", "field_72773_u");
    private static final Field MINER = ReflectUtil.field(DestroyBlockProgress.class, "miningPlayerEntId", "field_73115_a");
    /** breaker id -> {x, y, z, tick it started on that block} */
    private static final Map<Integer, long[]> START = new HashMap<>();

    private BlockBreaks() { }

    /** One crack: who makes it, where, how far. */
    public static final class Break {
        public final int breakerId;
        /** null when the breaker is not in your world (too far, or gone) */
        public final Entity breaker;
        public final BlockPos pos;
        /** 0..9: the crack's step (9 is about to break) */
        public final int stage;
        /** ticks since this breaker started on this block */
        public final int ticks;
        /** ticks since the server last moved this crack on */
        public final int idle;

        Break(int breakerId, Entity breaker, BlockPos pos, int stage, int ticks, int idle) {
            this.breakerId = breakerId;
            this.breaker = breaker;
            this.pos = pos;
            this.stage = stage;
            this.ticks = ticks;
            this.idle = idle;
        }

        /** 0.1 (just started) .. 1.0 */
        public float progress() { return (stage + 1) / 10f; }
    }

    /** Listens for the cracks for good (Hooks.install), so the start of every break is seen. */
    static void install() {
        Gale.BUS.listen(BlockBreakAnimEvent.class, Priority.MONITOR, BlockBreaks::record, "core:mining");
    }

    private static void record(BlockBreakAnimEvent e) {
        if (e.isCancelled() || e.stage < 0) {
            START.remove(e.breakerId);
            return;
        }
        long[] s = START.get(e.breakerId);
        if (s == null || s[0] != e.pos.getX() || s[1] != e.pos.getY() || s[2] != e.pos.getZ())
            START.put(e.breakerId, new long[]{ e.pos.getX(), e.pos.getY(), e.pos.getZ(), Scheduler.ticks() });
    }

    /** Every block being broken now, as your game shows it (yours too, when the game draws one). Game thread. */
    public static List<Break> list() {
        List<Break> out = new ArrayList<>();
        if (mc.renderGlobal == null || mc.theWorld == null) return out;
        Map<Integer, DestroyBlockProgress> map = ReflectUtil.get(DAMAGED, mc.renderGlobal);
        if (map == null || map.isEmpty()) return out;
        int cloud = ReflectUtil.getInt(CLOUD_TICK, mc.renderGlobal, 0);
        long now = Scheduler.ticks();
        for (DestroyBlockProgress d : new ArrayList<>(map.values())) {
            int id = ReflectUtil.getInt(MINER, d, -1), stage = d.getPartialBlockDamage();
            BlockPos p = d.getPosition();
            long[] s = START.get(id);
            if (s == null || s[0] != p.getX() || s[1] != p.getY() || s[2] != p.getZ()) {
                s = new long[]{ p.getX(), p.getY(), p.getZ(), now };
                START.put(id, s);
            }
            out.add(new Break(id, mc.theWorld.getEntityByID(id), p, stage, (int) (now - s[3]), cloud - d.getCreationCloudUpdateTick()));
        }
        return out;
    }
}
