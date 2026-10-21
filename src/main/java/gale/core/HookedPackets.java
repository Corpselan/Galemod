package gale.core;

import gale.Gale;
import gale.event.impl.BlockBreakAnimEvent;
import gale.event.impl.EntityStatusEvent;
import gale.event.impl.ExplosionEvent;
import gale.event.impl.KnockbackEvent;
import gale.event.impl.PacketEvent;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Game-thread hooks on received packets, without mixins. The game handles a packet twice: once on the network thread,
 * where it only queues it, and once on the game thread, where it really applies it. While someone listens for
 * {@link KnockbackEvent}, {@link ExplosionEvent}, {@link EntityStatusEvent} or {@link BlockBreakAnimEvent}, Gale swaps
 * the packet (after every {@code PacketEvent.Receive} listener) for a copy of its own subclass, whose game-thread half
 * fires the event first. Other clients still see a normal S12 / S27 / S19 / S25: the subclass is one, and has the
 * same short class name (B4 tells its scripts a packet's name from it).
 */
public final class HookedPackets {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Field S12_X = ReflectUtil.field(net.minecraft.network.play.server.S12PacketEntityVelocity.class, "motionX", "field_149415_b");
    private static final Field S12_Y = ReflectUtil.field(net.minecraft.network.play.server.S12PacketEntityVelocity.class, "motionY", "field_149416_c");
    private static final Field S12_Z = ReflectUtil.field(net.minecraft.network.play.server.S12PacketEntityVelocity.class, "motionZ", "field_149414_d");
    private static final Field S27_X = ReflectUtil.field(net.minecraft.network.play.server.S27PacketExplosion.class, "field_149152_f");
    private static final Field S27_Y = ReflectUtil.field(net.minecraft.network.play.server.S27PacketExplosion.class, "field_149153_g");
    private static final Field S27_Z = ReflectUtil.field(net.minecraft.network.play.server.S27PacketExplosion.class, "field_149159_h");
    private static final Field ENTITY_ID = ReflectUtil.field(net.minecraft.network.play.server.S19PacketEntityStatus.class, "entityId", "field_149164_a");
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private HookedPackets() { }

    static void install() {
        // after every other Receive listener, so the copy carries their changes
        Gale.BUS.listen(PacketEvent.Receive.class, Integer.MIN_VALUE, HookedPackets::swap, "core");
    }

    private static void swap(PacketEvent.Receive e) {
        if (e.isCancelled()) return;
        Packet<?> p = e.getPacket(), h = hooked(p);
        if (h != p) e.setPacket(h);
    }

    /**
     * The packet as Gale lets it reach the game: its hooked copy while someone listens for its event, else itself.
     * For a packet held back and let go past Gale's listeners (gale.b4port's Backtrack).
     */
    public static Packet<?> hooked(Packet<?> p) {
        if (p.getClass() == net.minecraft.network.play.server.S12PacketEntityVelocity.class) {
            if (Gale.BUS.hasListeners(KnockbackEvent.class) && mc.thePlayer != null
                && ((net.minecraft.network.play.server.S12PacketEntityVelocity) p).getEntityID() == mc.thePlayer.getEntityId())
                return copy(p, new S12PacketEntityVelocity());
        } else if (p.getClass() == net.minecraft.network.play.server.S27PacketExplosion.class) {
            if (Gale.BUS.hasListeners(ExplosionEvent.class)) return copy(p, new S27PacketExplosion());
        } else if (p.getClass() == net.minecraft.network.play.server.S19PacketEntityStatus.class) {
            if (Gale.BUS.hasListeners(EntityStatusEvent.class)) return copy(p, new S19PacketEntityStatus());
        } else if (p.getClass() == net.minecraft.network.play.server.S25PacketBlockBreakAnim.class) {
            if (Gale.BUS.hasListeners(BlockBreakAnimEvent.class)) return copy(p, new S25PacketBlockBreakAnim());
        }
        return p;
    }

    /** Copies every field of the game's packet into Gale's subclass; the original when that fails. */
    private static Packet<?> copy(Packet<?> from, Packet<?> to) {
        try {
            for (Field f : fields(from.getClass())) f.set(to, f.get(from));
            return to;
        } catch (Exception ex) {
            Gale.LOG.warn("Could not copy {}", from.getClass().getSimpleName(), ex);
            return from;
        }
    }

    private static List<Field> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    out.add(f);
                }
            return out;
        });
    }

    private static boolean onGameThread() { return mc.isCallingFromMinecraftThread(); }

    // Each copy is named like the packet it stands in for: a script asking a packet's name gets the usual one.

    private static final class S12PacketEntityVelocity extends net.minecraft.network.play.server.S12PacketEntityVelocity {
        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            if (onGameThread()) {
                KnockbackEvent ev = Gale.BUS.post(new KnockbackEvent(getMotionX() / 8000.0, getMotionY() / 8000.0, getMotionZ() / 8000.0));
                if (ev.isCancelled()) return;
                ReflectUtil.set(S12_X, this, (int) Math.round(ev.x * 8000.0));
                ReflectUtil.set(S12_Y, this, (int) Math.round(ev.y * 8000.0));
                ReflectUtil.set(S12_Z, this, (int) Math.round(ev.z * 8000.0));
            }
            super.processPacket(handler);
        }
    }

    private static final class S27PacketExplosion extends net.minecraft.network.play.server.S27PacketExplosion {
        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            if (onGameThread()) {
                ExplosionEvent ev = Gale.BUS.post(new ExplosionEvent(func_149149_c(), func_149144_d(), func_149147_e(), getStrength(), getX(), getY(), getZ()));
                boolean none = ev.isCancelled();
                ReflectUtil.set(S27_X, this, none ? 0f : (float) ev.x);
                ReflectUtil.set(S27_Y, this, none ? 0f : (float) ev.y);
                ReflectUtil.set(S27_Z, this, none ? 0f : (float) ev.z);
            }
            super.processPacket(handler);
        }
    }

    private static final class S19PacketEntityStatus extends net.minecraft.network.play.server.S19PacketEntityStatus {
        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            if (onGameThread() && mc.theWorld != null) {
                int id = ReflectUtil.getInt(ENTITY_ID, this, -1);
                if (Gale.BUS.post(new EntityStatusEvent(id, getOpCode(), getEntity(mc.theWorld))).isCancelled()) return;
            }
            super.processPacket(handler);
        }
    }

    private static final class S25PacketBlockBreakAnim extends net.minecraft.network.play.server.S25PacketBlockBreakAnim {
        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            if (onGameThread() && mc.theWorld != null) {
                int stage = getProgress();
                if (Gale.BUS.post(new BlockBreakAnimEvent(getBreakerId(), getPosition(), stage >= 0 && stage < 10 ? stage : -1)).isCancelled()) return;
            }
            super.processPacket(handler);
        }
    }
}
