package gale.b4port.module;

import gale.b4port.module.setting.ButtonSetting;
import gale.b4port.module.setting.SliderSetting;
import gale.b4port.util.Theme;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** B4's Settings module: the settings the ported modules share (weapons, hurt time, rotations, theme). Always on. */
public class SettingsModule extends Module {
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("Custom cape", "Hide first person self ESP", "Middle click friends",
        "Set chat as inventory", "Show health as hearts", "Show heart symbol", "Background", "Edit position", "Rotate body", "Full body",
        "Disable modules on load", "Send message on enable", "General", "Info HUD", "Profiles"));

    public static SliderSetting defaultTheme, themeOffset;
    public static ButtonSetting addBracketsToDistance;
    public static ButtonSetting movementFix;
    public static SliderSetting jitterYaw, aimClosestPoint, hittableHurtTime;
    public static ButtonSetting hideFirstPersonSelfEsp = new ButtonSetting("Hide first person self ESP", false);
    public static ButtonSetting healthAsHearts = new ButtonSetting("Show health as hearts", false);
    public static ButtonSetting heartSymbol = new ButtonSetting("Show heart symbol", false);

    /** B4's info HUD background (B4's default: rounded) */
    public enum Background {
        None, Rect, Round
    }

    public static Background background = Background.Round;
    public static ButtonSetting weaponAxe, weaponEnchanted, weaponFist, weaponHoe, weaponRod, weaponShovel, weaponStick;

    public SettingsModule() {
        super("B4 Settings", Category.client, false);
        alwaysOn();
    }

    @Override
    protected String specName() {
        return "Settings";
    }

    @Override
    protected boolean skipSetting(String name) {
        return SKIP.contains(name);
    }

    @Override
    public void link() {
        defaultTheme = (SliderSetting) ModuleManager.setting(this, "Default theme");
        addBracketsToDistance = (ButtonSetting) ModuleManager.setting(this, "Add brackets to distance");
        themeOffset = (SliderSetting) ModuleManager.setting(this, "Offset");
        movementFix = (ButtonSetting) ModuleManager.setting(this, "Movement fix");
        jitterYaw = (SliderSetting) ModuleManager.setting(this, "Jitter yaw");
        aimClosestPoint = (SliderSetting) ModuleManager.setting(this, "Aim closest point");
        hittableHurtTime = (SliderSetting) ModuleManager.setting(this, "Hittable hurt time");
        weaponAxe = (ButtonSetting) ModuleManager.setting(this, "Axe");
        weaponEnchanted = (ButtonSetting) ModuleManager.setting(this, "Enchanted");
        weaponFist = (ButtonSetting) ModuleManager.setting(this, "Fist");
        weaponHoe = (ButtonSetting) ModuleManager.setting(this, "Hoe");
        weaponRod = (ButtonSetting) ModuleManager.setting(this, "Rod");
        weaponShovel = (ButtonSetting) ModuleManager.setting(this, "Shovel");
        weaponStick = (ButtonSetting) ModuleManager.setting(this, "Stick");
        Theme.timeMultiplier = (SliderSetting) ModuleManager.setting(this, "Time multiplier");
    }
}
