package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

public class PlayerInteractEvent extends Event {
    private final EntityPlayerSP player;
    private final WorldClient world;
    private final Action action;
    private final BlockPos pos;
    private final EnumFacing facing;

    public PlayerInteractEvent(EntityPlayerSP player, WorldClient world, Action action, BlockPos pos, EnumFacing facing) {
        this.player = player;
        this.world = world;
        this.action = action;
        this.pos = pos;
        this.facing = facing;
    }

    public EntityPlayerSP getPlayer() { return player; }

    public WorldClient getWorld() { return world; }

    public Action getAction() { return action; }

    public BlockPos getPos() { return pos; }

    public EnumFacing getFacing() { return facing; }

    public enum Action { LEFT_CLICK_BLOCK, RIGHT_CLICK_BLOCK }
}
