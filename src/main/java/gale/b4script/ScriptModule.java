package gale.b4script;

import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;

/**
 * A B4 script running in Gale, as a module in the Scripts category: switching it on and off calls the script's
 * onEnable / onDisable, and the settings its onLoad registered (buttons, sliders, mode sliders) are Gale settings.
 */
public final class ScriptModule extends Module {
    public final Script script;

    ScriptModule(Script script, String description) {
        super(script.name, Category.SCRIPTS, description);
        this.script = script;
    }

    BoolSetting button(String name, boolean def) { return bool(name, def); }

    NumberSetting slider(String name, String suffix, double def, double min, double max, double step) {
        NumberSetting s = number(name, def, min, max, step);
        if (suffix != null && !suffix.isEmpty()) s.suffix(suffix);
        return s;
    }

    ModeSetting modes(String name, int def, String[] modes) {
        return mode(name, modes[Math.max(0, Math.min(modes.length - 1, def))], modes);
    }

    @Override
    protected void onEnable() { ScriptManager.onEnable(script); }

    @Override
    protected void onDisable() { ScriptManager.onDisable(script); }

    @Override
    public String getInfo() { return script.failed ? "加载出错" : null; }
}
