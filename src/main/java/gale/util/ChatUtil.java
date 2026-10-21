package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.util.regex.Pattern;

/** Chat: messages only you see, sending chat, colour codes. Use '&' for colours in your own text. */
public final class ChatUtil {
    public static final char SECTION = '§';
    public static final String PREFIX = "&8[&bGale&8]&r ";
    private static final Pattern CODES = Pattern.compile("(?i)§[0-9A-FK-OR]");
    private static final Minecraft mc = Minecraft.getMinecraft();

    private ChatUtil() { }

    /** A message only you see, with the Gale prefix. */
    public static void print(Object message) { raw(PREFIX + message); }

    /** A message only you see, no prefix. */
    public static void raw(String message) {
        if (mc.ingameGUI == null) return;
        Runnable r = () -> mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(color(message)));
        if (mc.isCallingFromMinecraftThread()) r.run();
        else mc.addScheduledTask(r);
    }

    public static void info(Object message) { print("&7" + message); }

    public static void warn(Object message) { print("&e" + message); }

    public static void error(Object message) { print("&c" + message); }

    /** Sends a chat message (or command, starting with /) to the server as if typed. */
    public static void send(String message) {
        if (mc.thePlayer != null) mc.thePlayer.sendChatMessage(message);
    }

    /** '&' colour codes to the game's section codes. */
    public static String color(String text) {
        char[] c = text.toCharArray();
        for (int i = 0; i < c.length - 1; i++)
            if (c[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c[i + 1]) >= 0) c[i] = SECTION;
        return new String(c);
    }

    /** Text without colour codes. */
    public static String strip(String text) { return text == null ? null : CODES.matcher(text).replaceAll(""); }

    /** The first colour code in the text ('c' for red...), or 0 when there is none. */
    public static char firstColor(String text) {
        if (text == null) return 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != SECTION) continue;
            char c = Character.toLowerCase(text.charAt(i + 1));
            if ("0123456789abcdef".indexOf(c) >= 0) return c;
        }
        return 0;
    }
}
