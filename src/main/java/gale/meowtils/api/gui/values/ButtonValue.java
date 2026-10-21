package gale.meowtils.api.gui.values;

public class ButtonValue {
    private final String name;
    private final Runnable action;
    private final float scale;

    public ButtonValue(String name, float scale, Runnable action) {
        this.name = name;
        this.scale = scale;
        this.action = action;
    }

    public String getName() { return name; }

    public float getScale() { return scale; }

    public Runnable getAction() { return action; }

    public void press() { if (action != null) action.run(); }
}
