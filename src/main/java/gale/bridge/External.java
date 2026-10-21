package gale.bridge;

import gale.module.ModuleManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Gale knows about the other clients running with it. A client reports which of its modules are on (B4 through
 * the GaleLink script); reports older than {@link #STALE_MILLIS} count as gone, so a closed client stops holding
 * Gale's modules back. Only public information is used: what the other client reports and Forge's mod list.
 */
public final class External {
    public static volatile long STALE_MILLIS = 3000;
    private static final Map<String, Report> REPORTS = new ConcurrentHashMap<>();

    private External() { }

    private static final class Report {
        final Set<String> enabled;
        final List<String> names;
        final long time = System.currentTimeMillis();

        Report(Collection<String> modules) {
            names = new ArrayList<>(modules);
            enabled = new HashSet<>();
            for (String m : modules) enabled.add(ModuleManager.key(m));
        }

        boolean fresh() { return System.currentTimeMillis() - time <= STALE_MILLIS; }
    }

    /** A client says these of its modules are on now (replaces its last report). */
    public static void report(String client, Collection<String> enabledModules) {
        REPORTS.put(client.toLowerCase(), new Report(enabledModules));
    }

    /** Whether any client reports one of these modules as on (names compared ignoring case and spaces). */
    public static boolean anyEnabled(String... modules) {
        if (REPORTS.isEmpty()) return false;
        for (Report r : REPORTS.values()) {
            if (!r.fresh()) continue;
            for (String m : modules) if (r.enabled.contains(ModuleManager.key(m))) return true;
        }
        return false;
    }

    public static boolean isEnabled(String client, String module) {
        Report r = REPORTS.get(client.toLowerCase());
        return r != null && r.fresh() && r.enabled.contains(ModuleManager.key(module));
    }

    /** Clients that reported recently. */
    public static List<String> clients() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Report> e : REPORTS.entrySet()) if (e.getValue().fresh()) out.add(e.getKey());
        return out;
    }

    /** The modules a client last reported as on (empty when it has not reported recently). */
    public static List<String> enabled(String client) {
        Report r = REPORTS.get(client.toLowerCase());
        return r == null || !r.fresh() ? new ArrayList<>() : new ArrayList<>(r.names);
    }

    /** Other Forge mods loaded with Gale ("name (modid)"). */
    public static List<String> loadedMods() {
        List<String> out = new ArrayList<>();
        for (ModContainer c : Loader.instance().getActiveModList()) {
            String id = c.getModId();
            if (id.equals("mcp") || id.equals("FML") || id.equals("Forge") || id.equals(gale.Gale.ID)) continue;
            out.add(c.getName() + " (" + id + ")");
        }
        return out;
    }
}
