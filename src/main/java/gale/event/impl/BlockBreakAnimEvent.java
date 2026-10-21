package gale.event.impl;

import gale.event.CancellableEvent;
import net.minecraft.util.BlockPos;

/**
 * The server moved someone's breaking of a block on (S25, the crack animation it shows other players). <b>Game
 * thread</b>, as the game applies it. {@code stage} is 0-9, or -1 when they stopped or the block broke. Cancel: your
 * game does not show it (and does not remember it).
 */
public final class BlockBreakAnimEvent extends CancellableEvent {
    public final int breakerId;
    public final BlockPos pos;
    public final int stage;

    public BlockBreakAnimEvent(int breakerId, BlockPos pos, int stage) {
        this.breakerId = breakerId;
        this.pos = pos;
        this.stage = stage;
    }
}
