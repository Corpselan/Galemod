package gale.meowtils.api;

import gale.Gale;
import gale.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.io.File;

/** Meowtils' static helpers that extensions call: chat messages and the log. */
public final class Meowtils {
    public static final String VERSION = "2.0.1";
    public static final File MEOWTILS_DIR = new File(Minecraft.getMinecraft().mcDataDir, "meowtils");
    public static final File EXTENSION_DIR = new File(MEOWTILS_DIR, "extensions");
    public static boolean loadedExtensions;

    private Meowtils() { }

    public static void info(String msg) { Gale.LOG.info("[extension] {}", msg); }

    public static void warn(String msg) { Gale.LOG.warn("[extension] {}", msg); }

    public static void error(String msg) { Gale.LOG.error("[extension] {}", msg); }

    public static void fatal(String msg) { Gale.LOG.fatal("[extension] {}", msg); }

    public static void addMessage(String message) { ChatUtil.print(message); }

    public static void addCleanMessage(String message) { ChatUtil.raw(message); }

    public static void debugMessage(String message) { Gale.LOG.debug("[extension] {}", message); }

    public static boolean isLunar() { return false; }
}
