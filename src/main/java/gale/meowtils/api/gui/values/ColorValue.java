package gale.meowtils.api.gui.values;

import gale.meowtils.api.gui.ColorLink;

/** Meowtils: the hue of a colour (0-360). Gale shows the colour as one colour setting. */
public class ColorValue {
    private final String name;
    private final ColorLink link;

    public ColorValue(String name, ColorLink link) {
        this.name = name;
        this.link = link;
    }

    public double get() { return link.getHue() * 360.0; }

    public String getName() { return name; }

    public void set(double hue) { link.apply((float) (Math.max(0, Math.min(360, hue)) / 360.0), link.getSaturation(), link.getBrightness()); }

    public ColorLink getLink() { return link; }
}
