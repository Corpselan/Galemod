package gale.event.impl;

import gale.event.CancellableEvent;

/**
 * An explosion packet (S27) is being applied. <b>Game thread.</b> {@code x/y/z} is the push added to your motion
 * (blocks per tick); change it, or cancel to take no push. The explosion itself (blocks, sound, particles) still happens.
 */
public final class ExplosionEvent extends CancellableEvent {
    public double x, y, z;
    public final float strength;
    public final double posX, posY, posZ;

    public ExplosionEvent(double x, double y, double z, float strength, double posX, double posY, double posZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.strength = strength;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
    }
}
