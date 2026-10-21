package gale.b4port.module.render;

import gale.b4port.module.setting.ButtonSetting;
import gale.b4port.util.Theme;

/** B4's BedESP is not ported; BedAura's outline uses its defaults (theme Default, the bed's own height). */
public final class BedESPModule {
    public static ButtonSetting renderFullBlock = new ButtonSetting("Render full block", false);
    public static Theme theme = Theme.DEFAULT;

    private BedESPModule() {
    }
}
