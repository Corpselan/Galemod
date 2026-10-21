package gale.script;

import com.mojang.authlib.GameProfile;
import gale.Gale;
import gale.util.ChatUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Vec3;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every play packet for scripts: found by name ("S19PacketEntityStatus", "EntityStatus", or "S12" when only one has
 * that number), its fields by their readable names (the real game has obfuscated field names; packet-fields.txt maps
 * them back), and field values turned into what a script can use and back.
 */
public final class PacketData {
    /** "S19PacketEntityStatus field_149164_a" -> "entityId" */
    private static final Map<String, String> NAMES = new HashMap<>();
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();
    /** lower-case name, part after "Packet", or the number prefix -> the packets with it */
    private static final Map<String, List<Class<?>>> BY_NAME = new HashMap<>();
    private static final List<String> LISTING = new ArrayList<>();
    private static boolean loaded;

    private PacketData() { }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = PacketData.class.getResourceAsStream("/gale/script/packet-fields.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 3 && !line.startsWith("#")) NAMES.put(p[0] + " " + p[1], p[2]);
                }
            }
        } catch (Exception e) {
            Gale.LOG.warn("Could not read the packet field names", e);
        }
        for (EnumPacketDirection dir : EnumPacketDirection.values()) {
            for (int id = 0; id < 256; id++) {
                Packet<?> p;
                try {
                    p = EnumConnectionState.PLAY.getPacket(dir, id);
                } catch (Exception e) {
                    continue;
                }
                if (p == null) continue;
                Class<?> c = p.getClass();
                String simple = c.getSimpleName();
                LISTING.add(String.format("%s 0x%02X", simple, id));
                key(simple, c);
                int at = simple.indexOf("Packet");
                if (at > 0) {
                    key(simple.substring(at + 6), c);
                    key(simple.substring(0, at), c);
                }
            }
        }
    }

    private static void key(String k, Class<?> c) {
        List<Class<?>> l = BY_NAME.computeIfAbsent(k.toLowerCase(Locale.ROOT), x -> new ArrayList<>());
        if (!l.contains(c)) l.add(c);
    }

    /** Every play packet with its id, "S12PacketEntityVelocity 0x12". */
    public static List<String> listing() {
        load();
        return Collections.unmodifiableList(LISTING);
    }

    /** A packet class by name; throws with what is wrong (unknown, or more than one with that number). */
    public static Class<?> find(String name) {
        load();
        List<Class<?>> l = BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
        if (l == null || l.isEmpty()) throw new IllegalArgumentException("没有叫 " + name + " 的包（gale.get(\"packet.list\") 看全部）");
        if (l.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> c : l) sb.append(sb.length() == 0 ? "" : "、").append(c.getSimpleName());
            throw new IllegalArgumentException(name + " 不止一个：" + sb + "，写全名");
        }
        return l.get(0);
    }

    public static String name(Object packet) { return packet == null ? null : packet.getClass().getSimpleName(); }

    /** The non-static fields of the packet's class, its superclasses' first. */
    public static List<Field> fields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, t -> {
            List<Class<?>> chain = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
            List<Field> out = new ArrayList<>();
            for (Class<?> c : chain)
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (Exception ignored) {
                    }
                }
            return out;
        });
    }

    /** The readable name of a field ("entityId"), or its own name when it has no readable one. */
    public static String fieldName(Field f) {
        load();
        String owner = f.getDeclaringClass().getName();
        String n = NAMES.get(owner.substring(owner.lastIndexOf('.') + 1) + " " + f.getName());
        return n != null ? n : f.getName();
    }

    public static Field field(Class<?> type, String name) {
        for (Field f : fields(type)) if (fieldName(f).equalsIgnoreCase(name) || f.getName().equalsIgnoreCase(name)) return f;
        StringBuilder sb = new StringBuilder();
        for (Field f : fields(type)) sb.append(sb.length() == 0 ? "" : ", ").append(fieldName(f));
        throw new IllegalArgumentException(type.getSimpleName() + " 没有字段 " + name + "（有：" + sb + "）");
    }

    /** All fields of the packet: name -> value as scripts see it. */
    public static Map<String, Object> read(Object packet) { return readObject(packet, 0); }

    private static Map<String, Object> readObject(Object o, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields(o.getClass())) {
            try {
                out.put(fieldName(f), toScript(f.get(o), depth + 1));
            } catch (Exception e) {
                out.put(fieldName(f), null);
            }
        }
        return out;
    }

    public static Object get(Object packet, String field) throws IllegalAccessException { return toScript(field(packet.getClass(), field).get(packet), 1); }

    public static void set(Object packet, String field, Object value) throws IllegalAccessException {
        Field f = field(packet.getClass(), field);
        Object v = fromScript(value, f.getType(), fieldName(f));
        if (v == null && f.getType().isPrimitive()) throw new IllegalArgumentException(fieldName(f) + " 不能是 null");
        f.set(packet, v);
    }

    /** A new, empty packet of the class (the way the game makes one before reading it from the network). */
    public static Packet<?> create(Class<?> type) throws Exception { return (Packet<?>) type.newInstance(); }

    /** A game value as a script sees it: numbers, text, B4 objects for game objects, lists and maps for the rest. */
    public static Object toScript(Object v, int depth) {
        if (v == null || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer) return ((Number) v).intValue();
        if (v instanceof Float || v instanceof Double) return ((Number) v).doubleValue();
        if (v instanceof Long) return v;
        if (v instanceof Character) return String.valueOf(v);
        if (v instanceof Enum) return ((Enum<?>) v).name();
        if (v instanceof IChatComponent) return ((IChatComponent) v).getFormattedText();
        if (v instanceof ItemStack || v instanceof BlockPos || v instanceof Vec3 || v instanceof IBlockState || v instanceof Entity || v instanceof Packet)
            return B4Link.wrap(v);
        if (v instanceof UUID) return v.toString();
        if (v instanceof GameProfile) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ((GameProfile) v).getName());
            m.put("id", ((GameProfile) v).getId() == null ? null : ((GameProfile) v).getId().toString());
            return m;
        }
        if (v instanceof ByteBuf) {
            ByteBuf b = (ByteBuf) v;
            byte[] out = new byte[b.writerIndex()];
            b.getBytes(0, out);
            return out;
        }
        if (v instanceof DataWatcher.WatchableObject) {
            DataWatcher.WatchableObject w = (DataWatcher.WatchableObject) v;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", w.getDataValueId());
            m.put("type", w.getObjectType());
            m.put("value", toScript(w.getObject(), depth + 1));
            return m;
        }
        if (v instanceof byte[] || v instanceof int[] || v instanceof short[] || v instanceof long[] || v instanceof float[] || v instanceof double[])
            return v;
        if (v.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(v); i++) out.add(toScript(Array.get(v, i), depth + 1));
            return out;
        }
        if (v instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object x : (Collection<?>) v) out.add(toScript(x, depth + 1));
            return out;
        }
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) out.put(String.valueOf(toScript(e.getKey(), depth + 1)), toScript(e.getValue(), depth + 1));
            return out;
        }
        // the game's small data classes inside packets (player list entries, map data...): their fields
        if (depth <= 3 && v.getClass().getName().startsWith("net.minecraft.")) return readObject(v, depth);
        return v.toString();
    }

    /** What a script gave, made into a value for a field of this type; throws when it does not fit. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static Object fromScript(Object value, Class<?> type, String what) {
        Object u = B4Link.unwrap(value);
        if (u == null) return null;
        if (type == int.class || type == Integer.class) return (int) Math.round(number(u, what));
        if (type == double.class || type == Double.class) return number(u, what);
        if (type == float.class || type == Float.class) return (float) number(u, what);
        if (type == long.class || type == Long.class) return Math.round(number(u, what));
        if (type == short.class || type == Short.class) return (short) Math.round(number(u, what));
        if (type == byte.class || type == Byte.class) return (byte) Math.round(number(u, what));
        if (type == boolean.class || type == Boolean.class) {
            if (u instanceof Boolean) return u;
            if (u instanceof Number) return ((Number) u).doubleValue() != 0;
            return Boolean.parseBoolean(String.valueOf(u).trim());
        }
        if (type == char.class || type == Character.class) return String.valueOf(u).isEmpty() ? '\0' : String.valueOf(u).charAt(0);
        if (type == String.class) return String.valueOf(u);
        if (type.isEnum()) {
            for (Object c : type.getEnumConstants()) if (((Enum) c).name().equalsIgnoreCase(String.valueOf(u).trim())) return c;
            throw new IllegalArgumentException(what + " 要是这些之一：" + java.util.Arrays.toString(type.getEnumConstants()));
        }
        if (IChatComponent.class.isAssignableFrom(type)) {
            if (u instanceof IChatComponent) return u;
            String s = String.valueOf(u);
            String t = s.trim();
            if (t.startsWith("{") || t.startsWith("[")) return IChatComponent.Serializer.jsonToComponent(t);
            return new ChatComponentText(ChatUtil.color(s));
        }
        if (type == BlockPos.class && u instanceof Vec3) return new BlockPos((Vec3) u);
        if (type == Vec3.class && u instanceof BlockPos) return new Vec3((BlockPos) u);
        if (type == UUID.class) return u instanceof UUID ? u : UUID.fromString(String.valueOf(u));
        if (type == byte[].class) return u instanceof byte[] ? u : hex(String.valueOf(u));
        if (PacketBuffer.class.isAssignableFrom(type) || type == ByteBuf.class) {
            byte[] b = u instanceof byte[] ? (byte[]) u : hex(String.valueOf(u));
            return new PacketBuffer(Unpooled.wrappedBuffer(b));
        }
        if (type.isInstance(u)) return u;
        throw new IllegalArgumentException(what + " 要 " + type.getSimpleName() + "，收到的是 " + u.getClass().getSimpleName());
    }

    private static double number(Object u, String what) {
        if (u instanceof Number) return ((Number) u).doubleValue();
        if (u instanceof Boolean) return (Boolean) u ? 1 : 0;
        try {
            return Double.parseDouble(String.valueOf(u).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " 要数字，收到的是 " + u);
        }
    }

    public static byte[] hex(String s) {
        s = s.trim();
        if (s.length() % 2 != 0) throw new IllegalArgumentException("十六进制要成对写，例如 0a1b");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("不是十六进制：" + s);
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}
