package gale.meowtils.api.handler.command;

import gale.meowtils.ExtensionLoader;

import java.util.HashMap;
import java.util.Map;

/** Meowtils: registers chat commands. In Gale each one is a Forge client command. */
public class CommandHandler {
    private static final Map<String, ClientCommand> COMMANDS = new HashMap<>();

    public static void register(ClientCommand command) {
        COMMANDS.put(command.getName().toLowerCase(), command);
        for (String alias : command.getAliases()) COMMANDS.put(alias.toLowerCase(), command);
        ExtensionLoader.registerCommand(command);
    }

    public static ClientCommand getCommand(String name) { return name == null ? null : COMMANDS.get(name.toLowerCase()); }

    /** Forgets the commands an extension's class loader made (when extensions are unloaded). */
    public static void unregisterAll(ClassLoader loader) {
        COMMANDS.values().removeIf(c -> c.getClass().getClassLoader() == loader);
    }
}
