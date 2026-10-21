package gale.util;

import gale.core.NetHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Sending packets, faking received ones, and holding packets back. */
public final class PacketUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private PacketUtil() { }

    /** Sends a packet the normal way (other clients' hooks and Gale's PacketEvent see it). */
    public static void send(Packet<?> packet) {
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h != null) h.addToSendQueue(packet);
    }

    /** Sends a packet that Gale's own PacketEvent listeners skip (other clients' hooks still see it). */
    public static void sendNoEvent(Packet<?> packet) {
        NetHook.skip(packet);
        send(packet);
    }

    /** Handles a packet as if the server had sent it. Game thread. */
    @SuppressWarnings("unchecked")
    public static void receive(Packet<? extends INetHandler> packet) {
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h != null) ((Packet<INetHandlerPlayClient>) packet).processPacket(h);
    }

    /** The packet's short name: "C03PacketPlayer", "S12PacketEntityVelocity"... */
    public static String name(Packet<?> packet) { return packet == null ? "null" : packet.getClass().getSimpleName(); }

    /**
     * Holds packets back and lets them go later, in order (blink, lag): {@code hold.add(packet)} from a Send listener
     * after cancelling it, then {@code hold.release()}. Safe from any thread.
     */
    public static final class Holder {
        private final ConcurrentLinkedQueue<Packet<?>> queue = new ConcurrentLinkedQueue<>();

        public void add(Packet<?> packet) { queue.add(packet); }

        public int size() { return queue.size(); }

        public boolean isEmpty() { return queue.isEmpty(); }

        /** Sends everything held, in order, without Gale's listeners seeing them again. */
        public void release() {
            Packet<?> p;
            while ((p = queue.poll()) != null) sendNoEvent(p);
        }

        /** Throws the held packets away (they are never sent). */
        public List<Packet<?>> drop() {
            List<Packet<?>> out = new ArrayList<>(queue);
            queue.clear();
            return out;
        }
    }
}
