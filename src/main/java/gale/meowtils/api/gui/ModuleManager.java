package gale.meowtils.api.gui;

import java.util.ArrayList;
import java.util.List;

/** Meowtils' module list, as extensions see it: the modules extensions made. */
public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();

    private ModuleManager() { }

    public static List<Module> getModules() { return MODULES; }

    public static void register(Module m) { if (!MODULES.contains(m)) MODULES.add(m); }

    public static void unregister(Module m) { MODULES.remove(m); }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T get(Class<T> type) {
        for (Module m : MODULES) if (type.isInstance(m)) return (T) m;
        return null;
    }

    public static Module get(String name) {
        for (Module m : MODULES) if (m.getName().equalsIgnoreCase(name)) return m;
        return null;
    }
}
