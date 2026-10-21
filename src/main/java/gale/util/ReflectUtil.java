package gale.util;

import gale.Gale;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection for private game fields and methods. Give the development name and the SRG name (the name in the real
 * game), e.g. {@code field(Minecraft.class, "leftClickCounter", "field_71429_W")}. Lookups are cached; a field or
 * method that cannot be found gives null and is logged once.
 */
public final class ReflectUtil {
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private ReflectUtil() { }

    public static Field field(Class<?> owner, String... names) {
        String key = owner.getName() + "#" + Arrays.toString(names);
        Object c = CACHE.get(key);
        if (c == null) {
            c = MISSING;
            for (String n : names) {
                try {
                    Field f = owner.getDeclaredField(n);
                    f.setAccessible(true);
                    if (Modifier.isFinal(f.getModifiers())) {
                        Field mods = Field.class.getDeclaredField("modifiers");
                        mods.setAccessible(true);
                        mods.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                    }
                    c = f;
                    break;
                } catch (Exception ignored) {
                }
            }
            if (c == MISSING) Gale.LOG.warn("No field {} in {}", Arrays.toString(names), owner.getName());
            CACHE.put(key, c);
        }
        return c == MISSING ? null : (Field) c;
    }

    public static Method method(Class<?> owner, String[] names, Class<?>... params) {
        String key = owner.getName() + "." + Arrays.toString(names) + Arrays.toString(params);
        Object c = CACHE.get(key);
        if (c == null) {
            c = MISSING;
            for (String n : names) {
                try {
                    Method m = owner.getDeclaredMethod(n, params);
                    m.setAccessible(true);
                    c = m;
                    break;
                } catch (Exception ignored) {
                }
            }
            if (c == MISSING) Gale.LOG.warn("No method {} in {}", Arrays.toString(names), owner.getName());
            CACHE.put(key, c);
        }
        return c == MISSING ? null : (Method) c;
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Field f, Object target) {
        if (f == null) return null;
        try {
            return (T) f.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean set(Field f, Object target, Object value) {
        if (f == null) return false;
        try {
            f.set(target, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static int getInt(Field f, Object target, int fallback) {
        Integer v = get(f, target);
        return v == null ? fallback : v;
    }

    @SuppressWarnings("unchecked")
    public static <T> T invoke(Method m, Object target, Object... args) {
        if (m == null) return null;
        try {
            return (T) m.invoke(target, args);
        } catch (Exception e) {
            Gale.LOG.error("Calling {} failed", m.getName(), e);
            return null;
        }
    }
}
