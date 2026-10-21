package gale.meowtils.api.gui.values;

/** Meowtils: an opacity 0-100 % in a float field. */
public class OpacityValue extends SliderValue {
    public OpacityValue(String name, String fieldName, Object owner) {
        super(name, 0, 100, 5, "%", fieldName, owner, float.class);
    }
}
