package gale.b4port.module.combat;

import net.minecraft.entity.Entity;
import net.minecraft.network.play.client.C02PacketUseEntity;

/** B4's Hit Select is not ported: it never holds KillAura's hits. */
public final class HitSelectModule {
    private HitSelectModule() {
    }

    public static void onUseEntity(C02PacketUseEntity p) {
    }

    public static boolean holdForKillAura(Entity e) {
        return false;
    }
}
