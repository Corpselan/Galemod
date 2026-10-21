package gale.b4port.hook;

import gale.b4port.module.Module;
import gale.b4port.module.ModuleManager;
import gale.b4port.module.SettingsModule;
import gale.b4port.util.PacketQueue;
import gale.b4port.util.PacketUtils;
import gale.core.HookedPackets;
import gale.core.NetHook;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** The parts of B4's Hooks the ported modules call themselves. */
public final class Hooks {
    private Hooks() {
    }

    /** B4's last check before a packet leaves: KillAura can hold back slot changes. */
    public static boolean finalSendCheck(Packet<?> packet) {
        PacketUtils.trackPost(packet);
        Module killAura;
        return !(packet instanceof C09PacketHeldItemChange && (killAura = ModuleManager.enabled("KillAura")) != null && !killAura.allowHeldItemPacket());
    }

    /** Lag Range (and B4's packet queue) on a packet sent past the other hooks. */
    public static boolean lagPassesOutbound(Packet<?> packet) {
        Module m = ModuleManager.enabled("Lag Range");
        if (m != null && !m.onSendPacket(packet)) {
            return false;
        }
        return !PacketQueue.active || PacketQueue.hold(packet);
    }

    /**
     * A received packet held back (Backtrack) goes on to the game now: from Gale's place in the connection on, so other
     * clients' hooks see it as it arrives; at once on the network thread (keeping its place before the packet being
     * read), on the next network turn from the game thread.
     */
    public static void onReceive(Packet<?> packet, INetHandler handler) {
        NetHook.deliverInOrder(HookedPackets.hooked(packet));
    }

    public static boolean movementFixSetting() {
        return SettingsModule.movementFix != null && SettingsModule.movementFix.isToggled();
    }
}
