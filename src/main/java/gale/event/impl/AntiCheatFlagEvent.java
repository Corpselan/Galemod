package gale.event.impl;

import gale.event.Event;
import net.minecraft.entity.player.EntityPlayer;

/**
 * B4's AntiCheat: another player looks like they cheat (the check's name: "Scaffold", "AutoBlock", "NoSlow",
 * "Bed Nuker", "AutoClicker", "Blink"...). Fired by Gale's AntiCheat module, or forwarded from B4 by GaleLink
 * ({@link #fromB4}). Game thread.
 */
public final class AntiCheatFlagEvent extends Event {
    public final String check;
    public final EntityPlayer player;
    public final boolean fromB4;

    public AntiCheatFlagEvent(String check, EntityPlayer player, boolean fromB4) {
        this.check = check;
        this.player = player;
        this.fromB4 = fromB4;
    }
}
