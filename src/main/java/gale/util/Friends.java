package gale.util;

import gale.Gale;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Your friend list (names, case does not matter). Saved in the config. Targeting skips friends by default. */
public final class Friends {
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();

    private Friends() { }

    public static boolean add(String name) {
        boolean added = NAMES.add(name.toLowerCase(Locale.ROOT));
        if (added) Gale.config().markDirty();
        return added;
    }

    public static boolean remove(String name) {
        boolean removed = NAMES.remove(name.toLowerCase(Locale.ROOT));
        if (removed) Gale.config().markDirty();
        return removed;
    }

    public static boolean is(String name) { return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT)); }

    public static boolean is(Entity e) { return e != null && is(e.getName()); }

    public static List<String> all() { return new ArrayList<>(NAMES); }

    public static void set(Collection<String> names) {
        NAMES.clear();
        for (String n : names) NAMES.add(n.toLowerCase(Locale.ROOT));
    }
}
