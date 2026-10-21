package gale.b4port.module.setting;

/** B4's setting, as the ported modules use it; the value lives in a Gale setting (so Gale's GUI and config show it). */
public class Setting {
    public String name;

    public Setting(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    /** The Gale setting behind this one, or null (descriptions). */
    public gale.module.setting.Setting<?> gale() {
        return null;
    }
}
