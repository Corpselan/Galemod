package gale.script.api;

import gale.script.Functions;
import gale.script.ScriptEvents;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;

/**
 * Input: swallow a key press before the game acts on it, and change (or make) mouse movement each frame.
 */
public final class InputApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "input";
    private static final Field PRESSED = ReflectUtil.field(KeyBinding.class, "pressed", "field_74513_e");
    private static final Field PRESS_TIME = ReflectUtil.field(KeyBinding.class, "pressTime", "field_151474_i");

    private InputApi() { }

    public static void install() {
        ScriptEvents.define(AREA + ".keys", "key", "{Integer 键码, String 键名, Boolean 按下, String 按键绑定或null}",
            "在游戏里（没开界面）按下或松开一个键，游戏线程，在游戏处理这个键之前（数字键换格子、Q 丢东西、E 背包、T 聊天、F5、攻击和使用键……）。"
                + "按下时返回非 0 = 吞掉这次按键：游戏和 Gale 的模块都当没按过。B4 自己的 onKey 还是会收到。");
        ScriptEvents.define(AREA + ".mouse", "mouseMove", "{Integer dx, Integer dy}",
            "每帧鼠标移动了多少（原始像素，还没乘灵敏度），游戏线程。改 args 就是改这一帧转镜头用的移动（可以加上自己的移动来转镜头），"
                + "返回非 0 = 这一帧镜头不动。只在有脚本监听时接管鼠标。");
        Functions.add(AREA, "mouse.grabbed", "", "鼠标是不是被游戏抓着（在游戏里、没开界面）。", a -> mc.inGameHasFocus);
    }

    /**
     * Called by Gale's key hook for every key event outside screens, before Gale's modules: true when a script
     * swallowed it (the key's bindings are then let go, with no press counted).
     */
    public static boolean onKey(int key, boolean down) {
        if (!ScriptEvents.has("key")) return false;
        String binding = null;
        for (KeyBinding b : mc.gameSettings.keyBindings) {
            if (b.getKeyCode() == key) {
                binding = b.getKeyDescription();
                break;
            }
        }
        boolean swallow = ScriptEvents.fire("key", new Object[]{ key, Keyboard.getKeyName(key), down, binding });
        if (!swallow || !down) return false;
        for (KeyBinding b : mc.gameSettings.keyBindings) {
            if (b.getKeyCode() != key) continue;
            ReflectUtil.set(PRESSED, b, false);
            ReflectUtil.set(PRESS_TIME, b, 0);
        }
        return true;
    }

    /** Every tick: takes over the mouse while a script listens to "mouseMove", gives it back when none does. */
    static void tick() {
        MouseHelper current = mc.mouseHelper;
        boolean want = ScriptEvents.has("mouseMove");
        if (want && !(current instanceof HookedMouse) && current != null) mc.mouseHelper = new HookedMouse(current);
        else if (!want && current instanceof HookedMouse) mc.mouseHelper = ((HookedMouse) current).inner;
    }

    /** The game's mouse reader, wrapped: the movement it read goes past the scripts first. */
    private static final class HookedMouse extends MouseHelper {
        final MouseHelper inner;

        HookedMouse(MouseHelper inner) { this.inner = inner; }

        @Override
        public void grabMouseCursor() { inner.grabMouseCursor(); }

        @Override
        public void ungrabMouseCursor() { inner.ungrabMouseCursor(); }

        @Override
        public void mouseXYChange() {
            inner.mouseXYChange();
            deltaX = inner.deltaX;
            deltaY = inner.deltaY;
            if (!ScriptEvents.has("mouseMove")) return;
            Object[] a = { deltaX, deltaY };
            if (ScriptEvents.fire("mouseMove", a)) {
                deltaX = deltaY = 0;
                return;
            }
            if (a[0] instanceof Number) deltaX = (int) Math.round(((Number) a[0]).doubleValue());
            if (a[1] instanceof Number) deltaY = (int) Math.round(((Number) a[1]).doubleValue());
        }
    }
}
