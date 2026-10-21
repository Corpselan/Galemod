package gale.b4port.module.setting;

import gale.module.setting.BoolSetting;

/** B4's on/off setting over a Gale {@link BoolSetting}. */
public class ButtonSetting extends Setting {
    private final BoolSetting value;
    public final boolean defaultValue;

    public ButtonSetting(String name, BoolSetting value) {
        super(name);
        this.value = value;
        this.defaultValue = value.getDefault();
    }

    /** A setting nobody can change (for a module Gale only knows by name): always its default. */
    public ButtonSetting(String name, boolean fixed) {
        this(name, new BoolSetting(name, fixed));
    }

    public boolean isToggled() {
        return value.get();
    }

    public void setEnabled(boolean b) {
        value.set(b);
    }

    public void set(boolean b) {
        value.set(b);
    }

    public void toggle() {
        value.toggle();
    }

    @Override
    public BoolSetting gale() {
        return value;
    }
}
