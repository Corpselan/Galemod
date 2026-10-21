package gale.b4port.module.player;

import net.minecraft.client.entity.EntityOtherPlayerMP;

/** B4's Freecam is not ported (no fake bodies). */
public final class FreecamModule {
    public static EntityOtherPlayerMP camera, body;

    private FreecamModule() {
    }
}
