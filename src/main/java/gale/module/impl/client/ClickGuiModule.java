package gale.module.impl.client;

import gale.Gale;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import gale.ui.ClickGui;
import gale.ui.raven.RavenGui;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/** Its key opens the settings screen (right Ctrl by default; /gale gui works too): Gale's own, or B4's look (Raven B4). */
public final class ClickGuiModule extends Module {
    public final ModeSetting scale = mode("Scale", "Auto", "Auto", "1", "2", "3", "4").describe("界面大小：Auto 按窗口高度选，数字是一格几个像素");
    public final ModeSetting glass = mode("Glass", "Framed", "Framed", "Tiled").describe("透明玻璃：Framed 无缝连接，Tiled 保留每块玻璃的边框");
    public final ModeSetting style = mode("Style", "Gale", "Gale", "Raven B4").describe("界面样式：Gale 自己的，或 Raven B4 的（一个个可拖动的分类框）");
    // B4's Gui module, for the Raven B4 style (its names and defaults)
    public final ModeSetting ravenScale = mode("Raven scale", "Normal", "Small", "Normal", "Large").describe("Raven B4 界面的大小（B4 的 Scale）");
    public final NumberSetting backgroundBlur = number("Background blur", 30, 0, 100, 5).suffix("%").describe("背景模糊");
    public final NumberSetting scrollSpeed = number("Scroll speed", 35, 10, 90, 1).describe("滚轮滚动分类框的速度");
    public final BoolSetting allowMovement = bool("Allow movement", true).describe("开着界面也能走路、跳、潜行");
    public final BoolSetting allowRotating = bool("Allow rotating", true).describe("开着界面时用方向键转头");
    public final BoolSetting darkBackground = bool("Dark background", true).describe("背景变暗");
    public final BoolSetting hidePlayerModel = bool("Hide player model", false).describe("不画右下角的人物");
    public final BoolSetting hideWatermark = bool("Hide watermark", false).describe("不画中间的 galedev 标题");
    public final BoolSetting rainbowOutlines = bool("Rainbow outlines", true).describe("打开的分类框用彩虹边框");
    public final BoolSetting resetPositions = bool("Reset positions", false).describe("把分类框都放回左上角（点一下就执行）");

    public ClickGuiModule() {
        super("ClickGUI", Category.CLIENT, "打开设置界面的按键");
        alwaysOn();
        setKey(Keyboard.KEY_RCONTROL);
        scale.visibleWhen(() -> style.is("Gale"));
        glass.visibleWhen(() -> style.is("Gale"));
        for (Setting<?> s : new Setting<?>[]{ ravenScale, backgroundBlur, scrollSpeed, allowMovement, allowRotating, darkBackground,
            hidePlayerModel, hideWatermark, rainbowOutlines, resetPositions })
            s.visibleWhen(() -> style.is("Raven B4"));
        // B4's "Reset positions" is a button: it does it and stays off
        resetPositions.onChange(on -> {
            if (!on) return;
            RavenGui.resetPositions();
            resetPositions.set(false);
        });
    }

    @Override
    protected void onKeyPressed() {
        if (mc.currentScreen == null) open();
    }

    /** Opens the settings screen in the chosen style. */
    public static void open() {
        ClickGuiModule m = Gale.modules().get(ClickGuiModule.class);
        if (m != null && m.style.is("Raven B4")) RavenGui.open();
        else Minecraft.getMinecraft().displayGuiScreen(new ClickGui());
    }
}
