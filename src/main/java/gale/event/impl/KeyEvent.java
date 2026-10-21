package gale.event.impl;

import gale.event.Event;
import gale.util.KeyUtil;

/**
 * A key or mouse button pressed or released while no screen is open. Mouse buttons use B4's numbering, button - 100
 * (left -100, right -99, middle -98). Game thread.
 */
public final class KeyEvent extends Event {
    public final int key;
    public final boolean pressed;

    public KeyEvent(int key, boolean pressed) {
        this.key = key;
        this.pressed = pressed;
    }

    public boolean isMouse() { return key < 0; }

    public String name() { return KeyUtil.name(key); }
}
