package gale.event.impl;

import gale.event.Event;

/**
 * Every tick in a world, after you moved and right before the movement packet goes out (B4's onPreMotion): what that
 * packet will carry. Change the position, rotation or ground and the packet carries the new values (your client stays
 * where it is); change sprinting or sneaking and the server is told so for this tick. The rotation is the server
 * rotation (a silent one if a module asked for it). Game thread.
 */
public final class MotionEvent extends Event {
    public double x, y, z;
    public float yaw, pitch;
    public boolean onGround, sprinting, sneaking;

    public MotionEvent(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean sprinting, boolean sneaking) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.sprinting = sprinting;
        this.sneaking = sneaking;
    }

    /** {x, y, z, yaw, pitch, onGround, sprinting, sneaking}, the way B4's scripts take it. */
    public Object[] toArray() { return new Object[]{ x, y, z, yaw, pitch, onGround, sprinting, sneaking }; }

    public void set(Object[] v) {
        x = (Double) v[0];
        y = (Double) v[1];
        z = (Double) v[2];
        yaw = (Float) v[3];
        pitch = (Float) v[4];
        onGround = (Boolean) v[5];
        sprinting = (Boolean) v[6];
        sneaking = (Boolean) v[7];
    }
}
