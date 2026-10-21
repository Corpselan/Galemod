package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Locale;

/**
 * Keys and key bindings. Key codes are LWJGL keyboard codes; mouse buttons are button - 100 (left -100, right -99,
 * middle -98, side buttons -97, -96), as Minecraft binds them; 0 is "none".
 */
public final class KeyUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private KeyUtil() { }

    public static String name(int code) {
        if (code == 0) return "NONE";
        if (code < 0) return "MOUSE" + (code + 100);
        String n = Keyboard.getKeyName(code);
        return n == null ? "KEY" + code : n;
    }

    /** A key code from a name ("R", "RSHIFT", "MOUSE3", "none"); -1 when unknown. */
    public static int code(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.isEmpty() || n.equals("NONE") || n.equals("0")) return 0;
        if (n.startsWith("MOUSE")) {
            try {
                return Integer.parseInt(n.substring(5)) - 100;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int k = Keyboard.getKeyIndex(n);
        return k == Keyboard.KEY_NONE ? -1 : k;
    }

    /** Whether the physical key or mouse button is held now. */
    public static boolean isDown(int code) {
        if (code == 0) return false;
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    /**
     * A key binding by B4-style name: forward, back, left, right, jump, sneak, sprint, attack, use, drop, inventory,
     * chat, playerlist, pickItem, command, screenshot, perspective, smoothCamera, hotbar.1 .. hotbar.9. Null if unknown.
     */
    public static KeyBinding binding(String name) {
        GameSettings s = mc.gameSettings;
        switch (name) {
            case "forward": return s.keyBindForward;
            case "back": return s.keyBindBack;
            case "left": return s.keyBindLeft;
            case "right": return s.keyBindRight;
            case "jump": return s.keyBindJump;
            case "sneak": return s.keyBindSneak;
            case "sprint": return s.keyBindSprint;
            case "attack": return s.keyBindAttack;
            case "use": return s.keyBindUseItem;
            case "drop": return s.keyBindDrop;
            case "inventory": return s.keyBindInventory;
            case "chat": return s.keyBindChat;
            case "playerlist": return s.keyBindPlayerList;
            case "pickItem": return s.keyBindPickBlock;
            case "command": return s.keyBindCommand;
            case "screenshot": return s.keyBindScreenshot;
            case "perspective": return s.keyBindTogglePerspective;
            case "smoothCamera": return s.keyBindSmoothCamera;
            default:
                if (name.startsWith("hotbar.")) {
                    try {
                        int i = Integer.parseInt(name.substring(7)) - 1;
                        if (i >= 0 && i < 9) return s.keyBindsHotbar[i];
                    } catch (NumberFormatException ignored) {
                    }
                }
                return null;
        }
    }

    /** Whether the binding's own key is physically held (not what modules set). */
    public static boolean isPhysicallyDown(KeyBinding b) { return isDown(b.getKeyCode()); }

    /** Sets whether the game thinks the binding is held (as Minecraft does when a key goes down or up). */
    public static void setPressed(KeyBinding b, boolean pressed) { KeyBinding.setKeyBindState(b.getKeyCode(), pressed); }

    /** One press of the binding: held, and counted once (a click for attack and use, processed next tick). */
    public static void press(KeyBinding b) {
        KeyBinding.setKeyBindState(b.getKeyCode(), true);
        KeyBinding.onTick(b.getKeyCode());
    }

    /** Puts the binding back to what the physical key says. */
    public static void restore(KeyBinding b) { setPressed(b, isPhysicallyDown(b)); }
}
