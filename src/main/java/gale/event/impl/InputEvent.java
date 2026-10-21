package gale.event.impl;

import gale.event.Event;

/**
 * Your movement keys for this tick, after the game read them and before you move (like B4's onPostPlayerInput).
 * Change them here to steer. Values are as the game uses them: forward and strafe are -1..1, already times 0.3 when
 * sneaking. Directions are relative to where the camera looks; if a silent rotation is active, Gale turns them
 * afterwards so you still go that way. Game thread.
 */
public final class InputEvent extends Event {
    public float forward, strafe;
    public boolean jump, sneak;
    /** null: leave sprinting to the game; true: start sprinting now if allowed; false: stop now (a held sprint key can start it again this tick) */
    public Boolean sprint;

    public InputEvent(float forward, float strafe, boolean jump, boolean sneak) {
        this.forward = forward;
        this.strafe = strafe;
        this.jump = jump;
        this.sneak = sneak;
    }

    public boolean isMoving() { return forward != 0 || strafe != 0; }

    /** Sets sneaking the way the game does it, which also slows the movement keys to 0.3. */
    public void setSneak(boolean sneak) {
        if (sneak == this.sneak) return;
        float scale = sneak ? 0.3f : 1 / 0.3f;
        forward *= scale;
        strafe *= scale;
        this.sneak = sneak;
    }

    public void stop() { forward = strafe = 0; }
}
