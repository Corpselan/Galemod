package gale.hud;

import gale.module.Module;

/**
 * Something on the HUD that can be moved and resized with the chat open (see {@link HudLayout}). Most are
 * {@link HudBox}es; a few keep their place in their module's settings and say how they are moved.
 */
public abstract class HudElement {
    public final String id, name;
    protected final Module owner;
    private long drawnAt;

    protected HudElement(String id, String name, Module owner) {
        this.id = id;
        this.name = name;
        this.owner = owner;
    }

    private java.util.function.BooleanSupplier when = () -> true;

    /** Shown in the editor: its module is on (and it is switched on in it). */
    public boolean listed() { return (owner == null || owner.isEnabled()) && when.getAsBoolean(); }

    /** Only listed while this is true too (a part of a module that can be switched off). */
    public HudElement showWhen(java.util.function.BooleanSupplier when) {
        this.when = when;
        return this;
    }

    /** x, y, width, height on screen (GUI pixels): where it was last drawn, or where it would be. */
    public abstract float[] bounds(float sw, float sh);

    /** Moves its top-left corner here. */
    public abstract void moveTo(float x, float y, float sw, float sh);

    public abstract float scale();

    public abstract void scaleTo(float scale, float sw, float sh);

    public float minScale() { return 0.5f; }

    public float maxScale() { return 3f; }

    /** Back to where it starts and its size. */
    public abstract void reset();

    /** The corner whose handle resizes it: right / bottom (the corner away from where it is anchored). */
    public boolean handleRight() { return true; }

    public boolean handleBottom() { return true; }

    /** Call when it is drawn (the editor shows a placeholder for one that has nothing to show now). */
    public final void drawn() { drawnAt = System.currentTimeMillis(); }

    public final boolean drawnRecently() { return System.currentTimeMillis() - drawnAt < 300; }
}
