package gale.module.impl.client;

import gale.core.RotationManager;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;

/** How silent rotations behave for every module (RotationManager). */
public final class Rotations extends Module {
    public final BoolSetting gcd = bool("GCD fix", true).describe("转动量取鼠标灵敏度的整数倍，像真鼠标");
    public final NumberSetting release = number("Release speed", 45, 5, 180, 1).suffix("°/tick").describe("没模块要转头时，回到相机方向的速度");
    public final ModeSetting moveFix = mode("Move fix", "Legit", "Legit", "Exact", "None").describe("静默转头时移动按键怎么跟着转");

    public Rotations() {
        super("Rotations", Category.CLIENT, "静默转头的规则");
        alwaysOn();
        gcd.onChange(v -> apply());
        release.onChange(v -> apply());
        moveFix.onChange(v -> apply());
        apply();
    }

    private void apply() {
        RotationManager.gcdFix = gcd.get();
        RotationManager.releaseSpeed = release.getFloat();
        RotationManager.defaultFix = moveFix.is("Exact") ? RotationManager.MoveFix.EXACT : moveFix.is("None") ? RotationManager.MoveFix.NONE : RotationManager.MoveFix.LEGIT;
    }
}
