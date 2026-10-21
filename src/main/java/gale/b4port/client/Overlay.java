package gale.b4port.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;

/** B4's "is a screen open" checks; Gale's own screens (ClickGUI) count as B4's own. */
public final class Overlay {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Overlay() {
    }

    public static boolean canRender() {
        return Chat.inWorld() && !mc.gameSettings.showDebugInfo && (mc.currentScreen == null || mc.currentScreen instanceof GuiChat);
    }

    public static boolean screenOpen() {
        return !(mc.currentScreen == null || ownScreen() || mc.currentScreen instanceof GuiChat);
    }

    public static boolean ownScreen() {
        return mc.currentScreen instanceof gale.ui.ClickGui;
    }
}
