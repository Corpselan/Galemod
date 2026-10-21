package gale.ui.raven;

import gale.Gale;
import gale.b4port.util.GuiColor;
import gale.module.impl.client.ClickGuiModule;

/** B4's Gui module: its colours, and its settings (in Gale they are ClickGUI's, shown when the style is Raven B4). */
public final class RavenStyle {
    public static final GuiColor watermarkColor = new GuiColor(57, 146, 229, 255);
    public static final GuiColor closedOutlineLeft = new GuiColor(81, 99, 149, 255);
    public static final GuiColor closedOutlineRight = new GuiColor(97, 67, 133, 255);
    public static final GuiColor categoryNameColor = new GuiColor(220, 220, 220, 255);
    public static final GuiColor enabledColor = new GuiColor(24, 154, 255, 255);
    public static final GuiColor disabledColor = new GuiColor(192, 192, 192, 255);
    public static final GuiColor errorColor = new GuiColor(255, 80, 80, 255);
    public static final GuiColor darkFill = new GuiColor(0, 0, 0, 110);
    public static final GuiColor lightFill = new GuiColor(0, 0, 0, 130);
    public static final GuiColor rainbowLeft = new GuiColor(2L, 0L, 130);
    public static final GuiColor rainbowRight = new GuiColor(2L, 700L, 130);
    public static final GuiColor whiteOutline = new GuiColor(255, 255, 255, 150);

    private RavenStyle() { }

    static ClickGuiModule settings() { return Gale.modules().get(ClickGuiModule.class); }

    static GuiColor categoryFill() { return settings().darkBackground.get() ? darkFill : lightFill; }

    static GuiColor outlineLeft() { return settings().rainbowOutlines.get() ? rainbowLeft : whiteOutline; }

    static GuiColor outlineRight() { return settings().rainbowOutlines.get() ? rainbowRight : whiteOutline; }

    static float blurStrength() { return (float) (3.4 * (settings().backgroundBlur.get() / 100.0)); }

    /** B4's GUI scale: Small, Normal, Large are Minecraft's GUI scale 1, 2, 3. */
    static int guiScale() { return settings().ravenScale.index() + 1; }
}
