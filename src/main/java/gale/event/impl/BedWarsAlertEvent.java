package gale.event.impl;

import gale.event.Event;
import net.minecraft.entity.player.EntityPlayer;

/**
 * B4's Bed Wars alert: an enemy got something worth knowing about (B4's names: FIREBALL, ENDERPEARL, DIA_ARMOR,
 * INVISIBLE, OBSIDIAN_PLACED, KB_STICK...). Fired by Gale's BedWars module, or forwarded from B4 by GaleLink
 * ({@link #fromB4}). Game thread.
 */
public final class BedWarsAlertEvent extends Event {
    public final String type;
    public final EntityPlayer player;
    public final boolean fromB4;

    public BedWarsAlertEvent(String type, EntityPlayer player, boolean fromB4) {
        this.type = type;
        this.player = player;
        this.fromB4 = fromB4;
    }
}
