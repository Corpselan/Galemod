package gale.devtest;

import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ColorSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.RangeSetting;
import gale.module.setting.TextSetting;

/** Development only: one setting of every kind, for the GUI test. */
public final class GuiDemo extends Module {
    public final BoolSetting flag = bool("Flag", true).describe("一个开关");
    public final NumberSetting number = number("Number", 3.5, 0, 10, 0.5).suffix(" blocks");
    public final RangeSetting range = range("Range", 8, 12, 1, 20, 1).suffix(" cps").describe("一个范围");
    public final ModeSetting shortMode = mode("Mode", "Two", "One", "Two", "Three");
    public final ModeSetting longMode = mode("Long mode", "Scoreboard", "Any", "Scoreboard", "Name color", "Armor color");
    public final ModeSetting manyModes = mode("Many", "Fifth", "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth");
    public final ColorSetting color = color("Color", 0xC855AAFF);
    public final TextSetting text = text("Text", "hello");

    public GuiDemo() {
        super("GuiDemo", Category.WORLD, "测试界面用的模块，每种设置都有一个");
    }
}
