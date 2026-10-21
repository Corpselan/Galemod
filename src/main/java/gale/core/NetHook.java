package gale.core;

import gale.Gale;
import gale.event.impl.PacketEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Gale's handler in the connection's Netty pipeline, just before vanilla's packet handler. It fires
 * {@link PacketEvent.Send} for every packet that really leaves (after other clients' send hooks) and
 * {@link PacketEvent.Receive} for every packet that arrives (before other clients' receive hooks). Both on the network
 * thread. Reattached on every connection.
 * <p>It can also hold packets back ({@link #IN}, {@link #OUT}) and let them go later, in order, from where it holds
 * them: received ones go on to the game (and other clients' hooks), sent ones go out. And it can send or deliver a
 * packet past everything ({@link #sendSilent}, {@link #deliver}).
 */
public final class NetHook extends ChannelDuplexHandler {
    public static final String NAME = "gale_packet_handler";
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** packets sent with PacketUtil.sendNoEvent: Gale's listeners do not see them */
    static final Set<Packet<?>> SKIP = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static boolean warned;

    private NetHook() { }

    public static void install(NetworkManager manager) {
        try {
            if (manager == null || manager.channel() == null) return;
            ChannelPipeline p = manager.channel().pipeline();
            if (p.get(NAME) != null) return;
            // a new connection: nothing from the old one is held any more
            IN.reset();
            OUT.reset();
            if (p.get("packet_handler") != null) p.addBefore("packet_handler", NAME, new NetHook());
            else p.addLast(NAME, new NetHook());
        } catch (Throwable t) {
            if (!warned) Gale.LOG.error("Could not add the packet handler", t);
            warned = true;
        }
    }

    /** Puts the handler back if the current connection lost it. Called every tick. */
    static void ensure() {
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h != null) install(h.getNetworkManager());
    }

    public static boolean isInstalled() {
        NetHandlerPlayClient h = mc.getNetHandler();
        return h != null && h.getNetworkManager().channel() != null && h.getNetworkManager().channel().pipeline().get(NAME) != null;
    }

    /** Marks a packet so Gale's listeners skip it (used by PacketUtil.sendNoEvent). */
    public static void skip(Packet<?> packet) { SKIP.add(packet); }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Packet && Gale.BUS.hasListeners(PacketEvent.Receive.class)) {
            PacketEvent.Receive ev = Gale.BUS.post(new PacketEvent.Receive((Packet<?>) msg));
            if (ev.isCancelled()) return;
            msg = ev.getPacket();
        }
        if (msg instanceof Packet && IN.takes((Packet<?>) msg)) {
            IN.queue.add(new Object[]{ msg, null });
            return;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Packet) {
            Packet<?> packet = (Packet<?>) msg;
            if (!SKIP.remove(packet) && Gale.BUS.hasListeners(PacketEvent.Send.class)) {
                PacketEvent.Send ev = Gale.BUS.post(new PacketEvent.Send(packet));
                if (ev.isCancelled()) {
                    promise.trySuccess();
                    return;
                }
                packet = ev.getPacket();
                msg = packet;
            }
            if (OUT.takes(packet)) {
                OUT.queue.add(new Object[]{ packet, promise });
                return;
            }
            ServerState.sent(packet);
        }
        super.write(ctx, msg, promise);
    }

    // ------------------------------------------------------------------ holding, silent sending

    /** Received packets held back (they have been through Gale's Receive listeners already). */
    public static final Hold IN = new Hold(true);
    /** Sent packets held back (after Gale's Send listeners). */
    public static final Hold OUT = new Hold(false);

    /** Packets held back in one direction while active; all of them, or only the given classes. */
    public static final class Hold {
        private final boolean in;
        private volatile boolean active;
        private volatile Set<Class<?>> only;
        final ConcurrentLinkedQueue<Object[]> queue = new ConcurrentLinkedQueue<>();

        Hold(boolean in) { this.in = in; }

        boolean takes(Packet<?> p) {
            if (!active) return false;
            Set<Class<?>> o = only;
            return o == null || o.contains(p.getClass());
        }

        public boolean isActive() { return active; }

        public int size() { return queue.size(); }

        /** Starts holding: every packet (only == null) or only these classes. */
        public void start(Collection<Class<?>> only) {
            this.only = only == null || only.isEmpty() ? null : Collections.unmodifiableSet(new java.util.HashSet<>(only));
            active = true;
        }

        /** Stops holding and lets everything held go. */
        public void stop() {
            active = false;
            release(-1);
        }

        /** Lets the oldest held packets go, in order (all for a negative count); how many. */
        public int release(int count) {
            ChannelHandlerContext ctx = context();
            if (ctx == null) {
                reset();
                return 0;
            }
            int n = count < 0 ? queue.size() : Math.min(count, queue.size());
            if (n == 0) return 0;
            ctx.channel().eventLoop().execute(() -> {
                for (int i = 0; i < n; i++) {
                    Object[] e = queue.poll();
                    if (e == null) break;
                    if (in) {
                        ctx.fireChannelRead(e[0]);
                    } else {
                        ServerState.sent((Packet<?>) e[0]);
                        ctx.write(e[0], (ChannelPromise) e[1]);
                    }
                }
                if (!in) ctx.flush();
            });
            return n;
        }

        /** Throws the held packets away; how many. */
        public int drop() {
            int n = 0;
            Object[] e;
            while ((e = queue.poll()) != null) {
                if (e[1] != null) ((ChannelPromise) e[1]).trySuccess();
                n++;
            }
            return n;
        }

        void reset() {
            active = false;
            only = null;
            drop();
        }
    }

    /** Gale's place in the current connection's pipeline, or null outside a server. */
    static ChannelHandlerContext context() {
        NetHandlerPlayClient h = mc.getNetHandler();
        if (h == null || h.getNetworkManager().channel() == null) return null;
        return h.getNetworkManager().channel().pipeline().context(NAME);
    }

    /** Sends a packet that no hook sees: not other clients', not Gale's; it goes straight to the encoder. */
    public static boolean sendSilent(Packet<?> packet) {
        ChannelHandlerContext ctx = context();
        if (ctx == null) return false;
        ctx.channel().eventLoop().execute(() -> {
            ServerState.sent(packet);
            ctx.writeAndFlush(packet);
        });
        return true;
    }

    /** Delivers a packet as if the server had sent it, from Gale's place on: other clients' receive hooks see it, then the game. */
    public static boolean deliver(Packet<?> packet) {
        ChannelHandlerContext ctx = context();
        if (ctx == null) return false;
        ctx.channel().eventLoop().execute(() -> ctx.fireChannelRead(packet));
        return true;
    }

    /**
     * Like {@link #deliver}, but on the network thread at once, before the packet being read goes on (for packets
     * held back and let go in their order); from another thread on the network's next turn.
     */
    public static boolean deliverInOrder(Packet<?> packet) {
        ChannelHandlerContext ctx = context();
        if (ctx == null) return false;
        if (ctx.channel().eventLoop().inEventLoop()) ctx.fireChannelRead(packet);
        else ctx.channel().eventLoop().execute(() -> ctx.fireChannelRead(packet));
        return true;
    }
}
