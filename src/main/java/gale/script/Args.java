package gale.script;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.util.Vec3;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The arguments of one script call. They come either as text ({@code gale.get("name a b c")}) or as objects
 * ({@code gale.put("name", new Object[]{ a, b, c })}); the getters accept both: numbers from text, entities from B4
 * Entity objects or entity ids, and so on. A missing or wrong argument throws, and the script sees the message in chat.
 */
public final class Args {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final String function;
    private final Object[] values;

    Args(String function, Object[] values) {
        this.function = function;
        this.values = values == null ? new Object[0] : values;
    }

    public int size() { return values.length; }

    public boolean has(int i) { return i < values.length && values[i] != null; }

    /** The argument as the script gave it (B4 objects still wrapped). */
    public Object raw(int i) { return i < values.length ? values[i] : null; }

    /** The argument with B4 objects turned into the game's. */
    public Object get(int i) { return B4Link.unwrap(raw(i)); }

    private IllegalArgumentException missing(int i, String what) {
        return new IllegalArgumentException(function + " 的第 " + (i + 1) + " 个参数要" + what + (has(i) ? "，收到的是 " + raw(i) : "，但没有给"));
    }

    public String str(int i) {
        if (!has(i)) throw missing(i, "文字");
        return String.valueOf(raw(i));
    }

    public String str(int i, String fallback) { return has(i) ? String.valueOf(raw(i)) : fallback; }

    /** Everything from argument i on, as one text with spaces. */
    public String rest(int i) {
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < values.length; k++) sb.append(k > i ? " " : "").append(values[k]);
        return sb.toString();
    }

    public double num(int i) {
        Object o = raw(i);
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw missing(i, "数字");
    }

    public double num(int i, double fallback) { return has(i) ? num(i) : fallback; }

    public int integer(int i) { return (int) Math.round(num(i)); }

    public int integer(int i, int fallback) { return has(i) ? integer(i) : fallback; }

    public boolean bool(int i) {
        Object o = raw(i);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        if (o instanceof String) {
            String s = ((String) o).trim().toLowerCase();
            if (s.equals("true") || s.equals("on") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("off") || s.equals("0") || s.equals("no")) return false;
        }
        throw missing(i, "true/false");
    }

    public boolean bool(int i, boolean fallback) { return has(i) ? bool(i) : fallback; }

    /** A B4 Entity, or an entity id (number or text). */
    public Entity entity(int i) {
        Object o = get(i);
        if (o instanceof Entity) return (Entity) o;
        if ((o instanceof Number || o instanceof String) && mc.theWorld != null) {
            Entity e = mc.theWorld.getEntityByID(integer(i));
            if (e != null) return e;
        }
        throw missing(i, "实体（Entity 或实体 id）");
    }

    public Entity entityOrNull(int i) { return has(i) ? entity(i) : null; }

    public ItemStack item(int i) {
        Object o = get(i);
        if (o instanceof ItemStack) return (ItemStack) o;
        throw missing(i, "物品（ItemStack）");
    }

    /** A B4 Vec3, or a double[]{x, y, z}. */
    public Vec3 vec(int i) {
        Object o = get(i);
        if (o instanceof Vec3) return (Vec3) o;
        if (o instanceof double[] && ((double[]) o).length >= 3) {
            double[] d = (double[]) o;
            return new Vec3(d[0], d[1], d[2]);
        }
        throw missing(i, "坐标（Vec3 或 double[]{x,y,z}）");
    }

    public Packet<?> packet(int i) {
        Object o = get(i);
        if (o instanceof Packet) return (Packet<?>) o;
        throw missing(i, "包（CPacket / SPacket）");
    }

    /** A callback: a lambda {@code (Comparator<Object>) (event, args) -> ...}. */
    @SuppressWarnings("unchecked")
    public Comparator<Object> callback(int i) {
        Object o = raw(i);
        if (o instanceof Comparator) return (Comparator<Object>) o;
        throw missing(i, "回调（(Comparator<Object>) (e, a) -> { ... return 0; }）");
    }

    /** A list of texts (from a List, a String[] or one comma-separated text). */
    public List<String> strings(int i) {
        Object o = raw(i);
        if (o instanceof String[]) return Arrays.asList((String[]) o);
        if (o instanceof List) {
            String[] out = new String[((List<?>) o).size()];
            for (int k = 0; k < out.length; k++) out[k] = String.valueOf(((List<?>) o).get(k));
            return Arrays.asList(out);
        }
        if (o instanceof String) return Arrays.asList(((String) o).split("\\s*,\\s*"));
        throw missing(i, "文字列表");
    }

    @Override
    public String toString() { return function + Arrays.toString(values); }
}
