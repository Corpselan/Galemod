package gale.util;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Clicking like the mouse does. {@link #leftClick()} runs vanilla's click at once (miss cooldown, swing, attack or
 * mine as the crosshair says); {@link #queueLeftClick()} counts a press of the attack key so the game clicks on its
 * next input pass, exactly as a real click would.
 */
public final class ClickUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Method CLICK = ReflectUtil.method(Minecraft.class, new String[]{ "clickMouse", "func_147116_af" });
    private static final Method RIGHT_CLICK = ReflectUtil.method(Minecraft.class, new String[]{ "rightClickMouse", "func_147121_ag" });
    private static final Field LEFT_COUNTER = ReflectUtil.field(Minecraft.class, "leftClickCounter", "field_71429_W");
    private static final Field RIGHT_DELAY = ReflectUtil.field(Minecraft.class, "rightClickDelayTimer", "field_71467_ac");

    private ClickUtil() { }

    /** Vanilla left click now (game thread). */
    public static void leftClick() { ReflectUtil.invoke(CLICK, mc); }

    /** Vanilla right click now (game thread). */
    public static void rightClick() { ReflectUtil.invoke(RIGHT_CLICK, mc); }

    /** A left click the game handles on its next input pass (like a real mouse press). */
    public static void queueLeftClick() { KeyUtil.press(mc.gameSettings.keyBindAttack); }

    public static void queueRightClick() { KeyUtil.press(mc.gameSettings.keyBindUseItem); }

    /** Ticks left of the 10-tick pause after a missed click (0: can click). */
    public static int leftClickCounter() { return ReflectUtil.getInt(LEFT_COUNTER, mc, 0); }

    public static void setLeftClickCounter(int ticks) { ReflectUtil.set(LEFT_COUNTER, mc, ticks); }

    /** Ticks until a held right click repeats. */
    public static int rightClickDelay() { return ReflectUtil.getInt(RIGHT_DELAY, mc, 0); }

    public static void setRightClickDelay(int ticks) { ReflectUtil.set(RIGHT_DELAY, mc, ticks); }
}
