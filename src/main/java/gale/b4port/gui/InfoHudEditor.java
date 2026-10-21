package gale.b4port.gui;

import gale.b4port.util.RenderUtils;

/** Where B4's info HUD sits (B4 lets you drag it; here it stays at B4's default place, left, a third down). */
public final class InfoHudEditor {
    public static int hudX = 4;

    private InfoHudEditor() {
    }

    public static int defaultY() {
        return RenderUtils.scaledHeight / 3 - 30;
    }
}
