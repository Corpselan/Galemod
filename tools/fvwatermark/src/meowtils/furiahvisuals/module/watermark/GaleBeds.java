package meowtils.furiahvisuals.module.watermark;

import gale.Gale;
import gale.bedwars.BedTracker;
import gale.event.impl.PacketEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Everything the Bed Tracker needs from Gale: its Bed Wars tracking (your bed, your team) and the block-breaking packets.
 * Kept apart so that without Gale (in Meowtils itself) only the Bed Tracker stops, not the whole extension.
 * Minecraft names are SRG, as in the rest of the extension.
 */
final class GaleBeds {
    /** {breaker entity id, x, y, z, stage 0-9 (anything else: stopped)} as they arrive */
    static final ConcurrentLinkedQueue<int[]> BREAKS = new ConcurrentLinkedQueue<int[]>();
    private static boolean listening;

    private GaleBeds() { }

    static void listen() {
        if (listening) return;
        listening = true;
        Gale.BUS.listen(PacketEvent.Receive.class, new java.util.function.Consumer<PacketEvent.Receive>() {
            @Override
            public void accept(PacketEvent.Receive e) {
                if (!(e.getPacket() instanceof S25PacketBlockBreakAnim)) return;
                S25PacketBlockBreakAnim p = (S25PacketBlockBreakAnim) e.getPacket();
                BlockPos pos = p.func_179821_b();
                if (pos == null) return;
                BREAKS.add(new int[]{ p.func_148845_c(), pos.func_177958_n(), pos.func_177956_o(), pos.func_177952_p(), p.func_148846_g() });
                while (BREAKS.size() > 64) BREAKS.poll();
            }
        });
    }

    static boolean inGame() { return BedTracker.inGame(); }

    /** The middle of your bed, or null when it is not known. */
    static double[] bed() {
        Vec3 c = BedTracker.bedCenter();
        return c == null ? null : new double[]{ c.field_72450_a, c.field_72448_b, c.field_72449_c };
    }

    static boolean destroyed() { return BedTracker.bedDestroyed(); }

    static String destroyedDetail() {
        String d = BedTracker.destroyedDetail();
        return d == null ? "" : BedTracker.plain(d);
    }

    static boolean enemy(EntityPlayer p, boolean teammates) { return BedTracker.isEnemy(p, teammates); }
}
