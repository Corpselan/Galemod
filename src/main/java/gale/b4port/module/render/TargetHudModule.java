package gale.b4port.module.render;

/** B4's Target HUD is not ported (ModuleManager has no "TargetHUD", so KillAura never draws one). */
public final class TargetHudModule {
    public enum Mode {
        Disabled, Modern, Legacy
    }

    public static Mode mode = Mode.Disabled;

    private TargetHudModule() {
    }

    public static void render(gale.b4port.util.Animation fade, String name, float health, double ratio) {
    }
}
