package gale.example;

import gale.core.RotationManager;
import gale.event.impl.InputEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;
import gale.util.PlayerSim;
import gale.util.PlayerUtil;
import net.minecraft.util.AxisAlignedBB;

/**
 * Sneaks for you right before you would walk off an edge with a real drop below. Example of PlayerSim: it runs this
 * tick's movement ahead of time and sneaks when that movement would end with nothing under your feet.
 */
public final class SafeWalk extends Module {
    private final NumberSetting drop = number("Min drop", 2, 0.5, 10, 0.5).suffix(" blocks").describe("下面至少这么深才蹲");
    private final BoolSetting blocksOnly = bool("Holding blocks", false).describe("只在手里拿方块时");
    private final BoolSetting lookingDown = bool("Looking down", false).describe("只在往下看时（搭路）");

    public SafeWalk() {
        super("SafeWalk", Category.MOVEMENT, "走到边缘时自动蹲下，不会掉下去");
        complements("SafeWalk", "Safe Walk");
        on(InputEvent.class, this::onInput);
    }

    private void onInput(InputEvent e) {
        // also when no key is held: momentum alone can carry you off the edge
        if (!player().onGround || e.sneak || e.jump) return;
        if (blocksOnly.get() && !PlayerUtil.isHoldingBlock()) return;
        if (lookingDown.get() && RotationManager.cameraPitch() < 60) return;
        PlayerSim sim = PlayerSim.of(player());
        float[] keys = RotationManager.toServerInput(e.forward, e.strafe);
        sim.forward = keys[0];
        sim.strafe = keys[1];
        sim.jump = false;
        sim.sneak = false;
        sim.tick();
        // what matters is the ground under where this tick ends: the game moves you down before sideways, so the tick
        // that carries you past the edge still ends "on ground", and on the next tick sneaking is too late
        AxisAlignedBB feet = sim.box();
        AxisAlignedBB below = new AxisAlignedBB(feet.minX, feet.minY - drop.get(), feet.minZ, feet.maxX, feet.minY, feet.maxZ);
        if (world().getCollidingBoundingBoxes(player(), below).isEmpty()) e.setSneak(true);
    }
}
