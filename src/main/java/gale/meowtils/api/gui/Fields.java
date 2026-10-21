package gale.meowtils.api.gui;

import java.lang.reflect.Field;

/** Meowtils binds each value to a field of the module by name; this finds and converts them. */
public final class Fields {
    private Fields() { }

    public static Field bind(Object owner, String name) {
        if (owner == null || name == null) return null;
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field f, Object owner, T fallback) {
        if (f == null) return fallback;
        try {
            Object v = f.get(owner);
            return v == null ? fallback : (T) v;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void set(Field f, Object owner, Object value) {
        if (f == null) return;
        try {
            Class<?> t = f.getType();
            if (value instanceof Number && t != value.getClass()) value = cast(((Number) value).doubleValue(), t);
            f.set(owner, value);
        } catch (Exception ignored) {
        }
    }

    public static Object cast(double v, Class<?> t) {
        if (t == int.class || t == Integer.class) return (int) Math.round(v);
        if (t == long.class || t == Long.class) return Math.round(v);
        if (t == float.class || t == Float.class) return (float) v;
        if (t == short.class || t == Short.class) return (short) Math.round(v);
        if (t == byte.class || t == Byte.class) return (byte) Math.round(v);
        return v;
    }
}
