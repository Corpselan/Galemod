package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

public class AttackEntityEvent extends Event {
    private final EntityPlayer player;
    private final Entity target;

    public AttackEntityEvent(EntityPlayer player, Entity target) {
        this.player = player;
        this.target = target;
    }

    public EntityPlayer getPlayer() { return player; }

    public Entity getTarget() { return target; }
}
