package gale.b4port;

import gale.b4port.module.Module;
import gale.b4port.module.ModuleManager;
import gale.b4port.module.minigames.BedWarsModule;
import gale.b4port.module.player.BedAuraModule;
import gale.bridge.B4State;
import gale.bridge.Bridge;
import gale.bridge.External;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;

/**
 * What B4's modules are doing, wherever they run: Gale's ports when they run in Gale, B4's own (as GaleLink reports
 * it) while Gale's give way to them. For Gale's own code (the island, the B4 script runtime).
 */
public final class B4Values {
    private B4Values() { }

    /** KillAura / AimAssist / Backtrack / Lag Range: the module's target, or null. */
    public static Entity target(String module) {
        Module m = ModuleManager.get(module);
        return m != null ? m.getTarget() : null;
    }

    public static boolean scaffolding() {
        Module m = ModuleManager.get("Scaffold");
        return m != null && m.isScaffolding();
    }

    public static boolean towering() {
        Module m = ModuleManager.get("Tower");
        return m != null && m.isTowering();
    }

    /** The bed block BedAura is breaking, or null. */
    public static BlockPos bedAuraPosition() {
        return BedAuraModule.target;
    }

    /** {BedAura's progress, the vanilla progress}; an old GaleLink (only "b4.bedaura") gives the first. */
    public static float[] bedAuraProgress() {
        if (BedAuraModule.target != null) return new float[]{ BedAuraModule.progress, BedAuraModule.vanillaProgress };
        if (!B4State.fresh() && External.isEnabled("b4", "BedAura")) {
            try {
                float p = Float.parseFloat(Bridge.get("b4.bedaura"));
                return new float[]{ p, p };
            } catch (Exception ignored) {
            }
        }
        return new float[]{ 0f, 0f };
    }

    /** Your bed as Bed Wars knows it (Gale's port, else B4's through GaleLink), or null. */
    public static BlockPos ownBed() {
        Module bedWars = ModuleManager.get("Bed Wars");
        if (bedWars instanceof BedWarsModule && bedWars.isRunning() && BedWarsModule.ownBed != null) return BedWarsModule.ownBed;
        return B4State.ownBed();
    }
}
