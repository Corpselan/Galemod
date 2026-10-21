package gale.bridge;

import gale.Gale;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads FPSMaster's Performance module while it runs next to Gale, so Gale's own optimizations give way to the ones
 * FPSMaster already does (two cullers or two particle limits would only do the same work twice). Its settings are
 * public static fields of top.fpsmaster.features.impl.optimizes.Performance; if FPSMaster is missing or looks
 * different, everything here says "not doing it".
 */
public final class FpsMaster {
    private static final String PERFORMANCE = "top.fpsmaster.features.impl.optimizes.Performance";
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static Boolean loaded;
    private static Class<?> performance;
    private static boolean warned;

    private FpsMaster() { }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Loader.isModLoaded("fpsmaster");
            if (loaded) {
                try {
                    performance = Class.forName(PERFORMANCE);
                } catch (Throwable t) {
                    performance = null;
                }
            }
        }
        return loaded && performance != null;
    }

    /** Whether FPSMaster's Performance module is on. */
    public static boolean performanceOn() { return isLoaded() && Boolean.TRUE.equals(value("using")); }

    /** A boolean setting of FPSMaster's Performance module (false when the module is off or the setting is unknown). */
    public static boolean setting(String field) { return performanceOn() && Boolean.TRUE.equals(settingValue(field)); }

    /** A number setting of FPSMaster's Performance module (0 when the module is off or the setting is unknown). */
    public static double number(String field) {
        if (!performanceOn()) return 0;
        Object v = settingValue(field);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static Object settingValue(String field) {
        Object s = value(field);
        if (s == null) return null;
        try {
            Method get = s.getClass().getMethod("getValue");
            return get.invoke(s);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    private static Object value(String field) {
        try {
            Field f = FIELDS.get(field);
            if (f == null) {
                f = performance.getField(field);
                FIELDS.put(field, f);
            }
            return f.get(null);
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    private static void warn(Throwable t) {
        if (warned) return;
        warned = true;
        Gale.LOG.warn("FPSMaster's Performance module looks different than expected; Gale will not give way to it", t);
    }
}
