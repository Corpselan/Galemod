package gale.b4port.module.combat;

import gale.b4port.module.setting.ButtonSetting;
import net.minecraft.entity.Entity;

/** B4's Displace is not ported (ModuleManager has no "Displace"): nothing is ever queued. */
public final class DisplaceModule {
    public static ButtonSetting killAura = new ButtonSetting("KillAura", false);
    public static ButtonSetting overrideAttacks = new ButtonSetting("Override attacks", false);
    public static boolean queued, holding;
    public static long lastAt;

    private DisplaceModule() {
    }

    public static void rearm() {
    }

    public static boolean canDisplace(Entity e, boolean a, boolean b) {
        return false;
    }

    public static float offset() {
        return 0f;
    }

    public static void startBlink() {
    }

    public static void clear() {
    }
}
