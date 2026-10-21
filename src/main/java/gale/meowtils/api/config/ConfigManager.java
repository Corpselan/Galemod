package gale.meowtils.api.config;

import gale.Gale;

/** Meowtils: save the config. Gale saves its own config shortly after a change. */
public final class ConfigManager {
    private ConfigManager() { }

    public static void save() { Gale.config().markDirty(); }

    public static void load() { }
}
