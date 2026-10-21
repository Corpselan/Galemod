package gale.event.impl;

import gale.event.CancellableEvent;

/**
 * Raw mouse input (button, movement, wheel) as the game reads it. Cancel to keep the game from seeing it: a cancelled
 * left press is a click that never happens. button is -1 for movement and wheel. Game thread.
 */
public final class MouseEvent extends CancellableEvent {
    public final int button, dx, dy, wheel;
    public final boolean pressed;

    public MouseEvent(int button, boolean pressed, int dx, int dy, int wheel) {
        this.button = button;
        this.pressed = pressed;
        this.dx = dx;
        this.dy = dy;
        this.wheel = wheel;
    }

    public boolean isButton() { return button >= 0; }
}
