package gale.b4port.module.movement;

import gale.b4port.client.Chat;
import gale.b4port.module.combat.KillAuraModule;
import gale.b4port.util.MathUtils;
import net.minecraft.client.Minecraft;

/** B4's NoSlow is not ported: KillAura's blocking slows you as vanilla does (0.2x, once a tick). */
public final class NoSlowModule {
    private static int lastSlowTick;

    private NoSlowModule() {
    }

    public static boolean slowdown() {
        Minecraft mc = Minecraft.getMinecraft();
        int n = mc.thePlayer.ticksExisted;
        if (KillAuraModule.slowedThisTick && lastSlowTick == n) {
            return false;
        }
        if (lastSlowTick == n) {
            Chat.print(MathUtils.timeDigits() + " &cnoslow e1");
        }
        lastSlowTick = n;
        mc.thePlayer.movementInput.moveStrafe *= 0.2f;
        mc.thePlayer.movementInput.moveForward *= 0.2f;
        return true;
    }
}
