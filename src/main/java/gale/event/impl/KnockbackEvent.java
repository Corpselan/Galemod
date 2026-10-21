package gale.event.impl;

import gale.event.CancellableEvent;

/**
 * The server's velocity packet (S12) for you is being applied: the moment the game sets your motion. <b>Game thread</b>,
 * before the tick it lands in (the game handles packets before its ticks). Change {@code x/y/z} (blocks per tick) to
 * change the knockback, or cancel to keep your motion as it is.
 * <p>{@code PacketEvent.Receive} sees the same packet earlier, on the network thread; this is the one to use for
 * "what motion do I end up with".
 */
public final class KnockbackEvent extends CancellableEvent {
    public double x, y, z;

    public KnockbackEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
