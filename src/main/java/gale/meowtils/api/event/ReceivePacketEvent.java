package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.network.Packet;

public class ReceivePacketEvent extends Event {
    private final Packet<?> packet;

    public ReceivePacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() { return packet; }
}
