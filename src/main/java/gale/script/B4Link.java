package gale.script;

import gale.Gale;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The link to B4's scripts.
 * <p>B4 gives its scripts one shared map, {@code bridge}; Gale puts its API ({@link ScriptApi}) in it under
 * {@code "gale"}. Gale finds that map while a script runs {@code client.runForgeCommand("gale link")}: the script's
 * class and B4's script classes are on the call stack then, and the map is the static Map field of their nested class
 * named {@code bridge}. Found by shape, not by B4's own class names, and only the map scripts use anyway is touched.
 * <p>Also turns B4's script objects (Entity, Vec3, ItemStack, Block, CPacket/SPacket and their subclasses) into the
 * game's objects and back, the same way: each B4 wrapper keeps the game object in one field of that type.
 */
public final class B4Link {
    public static final String KEY = "gale";

    private static volatile Map<String, Object> bridge;
    private static volatile Class<?> defaults;
    private static String linkedBy = "";

    private static Class<?> entityType, vecType, itemType, blockType, cPacketType, sPacketType;
    private static Field entityField, itemField, blockField, cPacketField, sPacketField, vecX, vecY, vecZ;
    private static Method entityOf, itemOf;
    private static Constructor<?> entityCtor, vecCtor, itemCtor, blockCtor, cPacketCtor, sPacketCtor;
    /** game packet class -> B4's wrapper constructor for it (C02, S12...) */
    private static final Map<Class<?>, Constructor<?>> PACKET_WRAPPERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> PACKET_LOOKUP = new ConcurrentHashMap<>();
    private static final Constructor<?> NONE;

    static {
        Constructor<?> none = null;
        try {
            none = Object.class.getConstructor();
        } catch (NoSuchMethodException ignored) {
        }
        NONE = none;
    }

    private B4Link() { }

    public static boolean isLinked() { return bridge != null; }

    /** The class that B4's scripts extend (B4's ScriptDefaults), once linked. */
    public static Class<?> defaults() { return defaults; }

    /** The class name of the script that linked first (for /gale api). */
    public static String linkedBy() { return linkedBy; }

    /**
     * Called by {@code /gale link}: finds B4's bridge from the classes on the call stack and puts the API in.
     * True when the API is in the bridge.
     */
    public static synchronized boolean link() {
        if (bridge == null) {
            for (Class<?> c : new StackClasses().get()) {
                if (c == null || c.getName().startsWith("java.") || c.getName().startsWith("sun.")) continue;
                // Gale's own copy of B4's script API (gale.b4script, for the scripts Gale runs) is not B4's bridge
                for (Class<?> k = c; k != null && k != Object.class && !k.getName().startsWith("gale.b4script."); k = k.getSuperclass()) if (tryDefaults(k, c)) break;
                if (bridge != null) break;
                for (Class<?> k = enclosing(c); k != null && !k.getName().startsWith("gale.b4script."); k = enclosing(k)) if (tryDefaults(k, c)) break;
                if (bridge != null) break;
            }
            if (bridge == null) return false;
        }
        install();
        return true;
    }

    /** Puts the API back if a script cleared the bridge. Game thread, every tick. */
    public static void tick() {
        Map<String, Object> b = bridge;
        if (b != null && b.get(KEY) != ScriptApi.INSTANCE) install();
    }

    private static void install() {
        try {
            bridge.put(KEY, ScriptApi.INSTANCE);
        } catch (Exception e) {
            Gale.LOG.warn("Could not put the script API into B4's bridge", e);
        }
    }

    private static Class<?> enclosing(Class<?> c) {
        try {
            return c.getEnclosingClass();
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean tryDefaults(Class<?> k, Class<?> caller) {
        Class<?>[] nested;
        try {
            nested = k.getDeclaredClasses();
        } catch (Throwable t) {
            return false;
        }
        for (Class<?> n : nested) {
            if (!"bridge".equals(n.getSimpleName())) continue;
            for (Field f : n.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object m = f.get(null);
                    if (m == null) continue;
                    bridge = (Map<String, Object>) m;
                    defaults = k;
                    linkedBy = caller.getName();
                    mapWrappers(nested);
                    Gale.LOG.info("Linked with B4's scripts ({} has the bridge)", k.getName());
                    return true;
                } catch (Exception e) {
                    Gale.LOG.warn("Could not read B4's bridge map", e);
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ B4's wrapper classes

    private static void mapWrappers(Class<?>[] nested) {
        for (Class<?> n : nested) {
            switch (n.getSimpleName()) {
                case "Entity":
                    entityType = n;
                    entityField = instanceField(n, Entity.class);
                    entityOf = staticFactory(n, Entity.class);
                    entityCtor = ctorEndingWith(n, Entity.class);
                    break;
                case "Vec3":
                    vecType = n;
                    vecX = named(n, "x");
                    vecY = named(n, "y");
                    vecZ = named(n, "z");
                    try {
                        vecCtor = n.getDeclaredConstructor(double.class, double.class, double.class);
                        vecCtor.setAccessible(true);
                    } catch (Exception ignored) {
                    }
                    break;
                case "ItemStack":
                    itemType = n;
                    itemField = instanceField(n, ItemStack.class);
                    itemOf = staticFactory(n, ItemStack.class);
                    itemCtor = ctorEndingWith(n, ItemStack.class);
                    break;
                case "Block":
                    blockType = n;
                    blockField = instanceField(n, IBlockState.class);
                    blockCtor = ctorEndingWith(n, IBlockState.class);
                    break;
                case "CPacket":
                    cPacketType = n;
                    cPacketField = instanceField(n, Packet.class);
                    cPacketCtor = ctorEndingWith(n, Packet.class);
                    break;
                case "SPacket":
                    sPacketType = n;
                    sPacketField = instanceField(n, Packet.class);
                    sPacketCtor = ctorEndingWith(n, Packet.class);
                    break;
                default:
            }
        }
        // the packet subclasses: C02(byte, C02PacketUseEntity), S12(S12PacketEntityVelocity)...
        for (Class<?> n : nested) {
            if (n.getSuperclass() == null || (n.getSuperclass() != cPacketType && n.getSuperclass() != sPacketType)) continue;
            for (Constructor<?> c : n.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 0) continue;
                Class<?> last = ps[ps.length - 1];
                if (!Packet.class.isAssignableFrom(last) || last == Packet.class) continue;
                if (ps.length > 2 || ps.length == 2 && ps[0] != byte.class) continue;
                c.setAccessible(true);
                PACKET_WRAPPERS.put(last, c);
            }
        }
    }

    private static Field instanceField(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
        return null;
    }

    private static Field named(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static Method staticFactory(Class<?> owner, Class<?> param) {
        Method found = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != owner || m.getParameterCount() != 1 || m.getParameterTypes()[0] != param) continue;
            m.setAccessible(true);
            if (found == null || Modifier.isPublic(m.getModifiers())) found = m;
        }
        return found;
    }

    private static Constructor<?> ctorEndingWith(Class<?> owner, Class<?> param) {
        for (Constructor<?> c : owner.getDeclaredConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length == 0 || ps[ps.length - 1] != param) continue;
            if (ps.length > 2 || ps.length == 2 && ps[0] != byte.class) continue;
            c.setAccessible(true);
            return c;
        }
        return null;
    }

    private static Object create(Constructor<?> c, Object value) throws Exception {
        return c.getParameterCount() == 2 ? c.newInstance((byte) 0, value) : c.newInstance(value);
    }

    // ------------------------------------------------------------------ converting

    /** Game object -> what a B4 script works with. Numbers, strings, lists and maps go through (lists element by element). */
    public static Object wrap(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Entity) {
                if (entityOf != null) return entityOf.invoke(null, o);
                if (entityCtor != null) return create(entityCtor, o);
            } else if (o instanceof Vec3) {
                Vec3 v = (Vec3) o;
                if (vecCtor != null) return vecCtor.newInstance(v.xCoord, v.yCoord, v.zCoord);
            } else if (o instanceof BlockPos) {
                BlockPos b = (BlockPos) o;
                if (vecCtor != null) return vecCtor.newInstance((double) b.getX(), (double) b.getY(), (double) b.getZ());
            } else if (o instanceof ItemStack) {
                if (itemOf != null) return itemOf.invoke(null, o);
                if (itemCtor != null) return create(itemCtor, o);
            } else if (o instanceof IBlockState) {
                if (blockCtor != null) return create(blockCtor, o);
            } else if (o instanceof Packet) {
                return wrapPacket((Packet<?>) o);
            } else if (o instanceof Collection && !(o instanceof Map)) {
                List<Object> out = new ArrayList<>();
                for (Object x : (Collection<?>) o) out.add(wrap(x));
                return out;
            }
        } catch (Exception e) {
            Gale.LOG.debug("Could not wrap {}", o.getClass().getName(), e);
        }
        return o;
    }

    /** A game packet as B4's script packet: its own wrapper (C02, S12...) when B4 has one, else CPacket / SPacket. */
    public static Object wrapPacket(Packet<?> p) {
        Constructor<?> c = PACKET_LOOKUP.computeIfAbsent(p.getClass(), B4Link::findPacketWrapper);
        try {
            if (c != NONE) return create(c, p);
            boolean client = p.getClass().getName().contains(".client.") || p.getClass().getSimpleName().startsWith("C");
            Constructor<?> base = client ? cPacketCtor : sPacketCtor;
            if (base != null) return base.newInstance(p);
        } catch (Exception e) {
            Gale.LOG.debug("Could not wrap {}", p.getClass().getName(), e);
        }
        return p;
    }

    private static Constructor<?> findPacketWrapper(Class<?> type) {
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            Constructor<?> c = PACKET_WRAPPERS.get(t);
            if (c != null) return c;
        }
        return NONE;
    }

    /** What a B4 script passed -> the game object (its Entity, Vec3, ItemStack, Block state, packet); anything else as it is. */
    public static Object unwrap(Object o) {
        if (o == null) return null;
        try {
            Class<?> t = o.getClass();
            if (entityType != null && entityType.isAssignableFrom(t) && entityField != null) return entityField.get(o);
            if (vecType != null && vecType.isAssignableFrom(t) && vecX != null)
                return new Vec3(vecX.getDouble(o), vecY.getDouble(o), vecZ.getDouble(o));
            if (itemType != null && itemType.isAssignableFrom(t) && itemField != null) return itemField.get(o);
            if (blockType != null && blockType.isAssignableFrom(t) && blockField != null) return blockField.get(o);
            if (cPacketType != null && cPacketType.isAssignableFrom(t) && cPacketField != null) return cPacketField.get(o);
            if (sPacketType != null && sPacketType.isAssignableFrom(t) && sPacketField != null) return sPacketField.get(o);
        } catch (Exception e) {
            Gale.LOG.debug("Could not unwrap {}", o.getClass().getName(), e);
        }
        return o;
    }

    /** The classes on the current call stack, innermost first. */
    private static final class StackClasses extends SecurityManager {
        Class<?>[] get() { return getClassContext(); }
    }
}
