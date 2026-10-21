package gale.core;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.AttackEvent;
import gale.event.impl.WorldEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what happened to the living entities around you, tick by tick: where they were (the last
 * {@link #HISTORY} ticks), how fast they move, when they last got hurt, when you last hit them, and your current combo.
 * Updated every tick after everything moved (before PostUpdateEvent); emptied on world change. Game thread.
 */
public final class EntityTracker {
    public static final int HISTORY = 40;
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<Integer, Track> TRACKS = new HashMap<>();
    private static long tick;
    private static int combo;
    private static long lastHurtSelf = -1000;

    private EntityTracker() { }

    private static final class Track {
        final double[][] positions = new double[HISTORY][3];
        int count, head;
        long lastHurt = -1000, lastAttacked = -1000, seen;
        int prevHurtTime;
        int hitsByYou;
    }

    static void install() {
        Gale.BUS.listen(AttackEvent.class, Priority.MONITOR, e -> {
            if (e.isCancelled()) return;
            Track t = track(e.target);
            t.lastAttacked = tick;
            t.hitsByYou++;
        }, "EntityTracker");
        Gale.BUS.listen(WorldEvent.Load.class, Priority.HIGHEST, e -> clear(), "EntityTracker");
    }

    static void clear() {
        TRACKS.clear();
        combo = 0;
    }

    /** Called by the hooks once a tick after everything moved. */
    static void update() {
        if (mc.theWorld == null || mc.thePlayer == null) return;
        tick++;
        if (mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0) {
            lastHurtSelf = tick;
            combo = 0;
        }
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof EntityLivingBase)) continue;
            EntityLivingBase l = (EntityLivingBase) e;
            Track t = track(l);
            t.seen = tick;
            double[] p = t.positions[t.head];
            p[0] = l.posX;
            p[1] = l.posY;
            p[2] = l.posZ;
            t.head = (t.head + 1) % HISTORY;
            if (t.count < HISTORY) t.count++;
            if (l.hurtTime > t.prevHurtTime && l.hurtTime >= l.maxHurtTime - 1) {
                t.lastHurt = tick;
                if (l != mc.thePlayer && tick - t.lastAttacked <= 3) combo++;
            }
            t.prevHurtTime = l.hurtTime;
        }
        if (tick % 100 == 0) TRACKS.values().removeIf(t -> tick - t.seen > 200);
    }

    private static Track track(Entity e) { return TRACKS.computeIfAbsent(e.getEntityId(), k -> new Track()); }

    /** Where the entity was this many ticks ago (0: now); null if not tracked that long. */
    public static Vec3 position(Entity e, int ticksAgo) {
        Track t = TRACKS.get(e.getEntityId());
        if (t == null || ticksAgo < 0 || ticksAgo >= t.count) return null;
        double[] p = t.positions[((t.head - 1 - ticksAgo) % HISTORY + HISTORY) % HISTORY];
        return new Vec3(p[0], p[1], p[2]);
    }

    /** Its positions, newest first. */
    public static List<Vec3> history(Entity e) {
        List<Vec3> out = new ArrayList<>();
        Track t = TRACKS.get(e.getEntityId());
        if (t == null) return out;
        for (int i = 0; i < t.count; i++) out.add(position(e, i));
        return out;
    }

    /** Movement per tick, averaged over the last few ticks (works for other players, whose motion fields are not sent). */
    public static Vec3 velocity(Entity e, int overTicks) {
        Track t = TRACKS.get(e.getEntityId());
        if (t == null || t.count < 2) return new Vec3(0, 0, 0);
        int n = Math.max(1, Math.min(overTicks, t.count - 1));
        Vec3 now = position(e, 0), then = position(e, n);
        return new Vec3((now.xCoord - then.xCoord) / n, (now.yCoord - then.yCoord) / n, (now.zCoord - then.zCoord) / n);
    }

    /** Where it will be in some ticks if it keeps its horizontal speed (vertical ignored). */
    public static Vec3 predict(Entity e, int ticks) {
        Vec3 v = velocity(e, 3);
        return new Vec3(e.posX + v.xCoord * ticks, e.posY, e.posZ + v.zCoord * ticks);
    }

    /** Ticks since it last took damage (large when never seen hurt). */
    public static long ticksSinceHurt(Entity e) {
        Track t = TRACKS.get(e.getEntityId());
        return t == null ? Long.MAX_VALUE / 2 : tick - t.lastHurt;
    }

    /** Ticks since you last attacked it. */
    public static long ticksSinceAttacked(Entity e) {
        Track t = TRACKS.get(e.getEntityId());
        return t == null ? Long.MAX_VALUE / 2 : tick - t.lastAttacked;
    }

    /** How many of your attacks on it were sent. */
    public static int hitsByYou(Entity e) {
        Track t = TRACKS.get(e.getEntityId());
        return t == null ? 0 : t.hitsByYou;
    }

    /** Hits you landed in a row without being hurt yourself. */
    public static int combo() { return combo; }

    public static long ticksSinceYouWereHurt() { return tick - lastHurtSelf; }
}
