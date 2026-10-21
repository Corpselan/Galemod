package gale.event.impl;

import gale.event.CancellableEvent;
import net.minecraft.network.Packet;

/**
 * A packet going out or coming in, seen in the network pipeline. <b>Runs on the network thread</b>, not the game
 * thread: change or cancel the packet here and read game state, but do not change the world from here; use
 * {@code Scheduler.main(...)} for anything that must happen on the game thread.
 * <p>Outgoing packets are seen after other clients' hooks (what really leaves); incoming ones before theirs.
 */
public abstract class PacketEvent extends CancellableEvent {
    private Packet<?> packet;

    PacketEvent(Packet<?> packet) { this.packet = packet; }

    public Packet<?> getPacket() { return packet; }

    /** Sends or delivers a different packet in its place. */
    public void setPacket(Packet<?> packet) { this.packet = packet; }

    public boolean is(Class<?> type) { return type.isInstance(packet); }

    /** The packet as that type, or null when it is something else. */
    @SuppressWarnings("unchecked")
    public <P> P as(Class<P> type) { return type.isInstance(packet) ? (P) packet : null; }

    public static final class Send extends PacketEvent {
        public Send(Packet<?> packet) { super(packet); }
    }

    public static final class Receive extends PacketEvent {
        public Receive(Packet<?> packet) { super(packet); }
    }
}
