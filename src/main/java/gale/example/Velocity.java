package gale.example;

import gale.event.impl.PacketEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.NumberSetting;
import gale.util.MathUtil;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.Vec3;

/**
 * Takes less knockback. Example of PacketEvent: the incoming velocity packet is replaced by a smaller one on the
 * network thread. Gives way to another client's Velocity.
 */
public final class Velocity extends Module {
    private final NumberSetting horizontal = number("Horizontal", 80, 0, 100, 1).suffix("%");
    private final NumberSetting vertical = number("Vertical", 100, 0, 100, 1).suffix("%");
    private final NumberSetting chance = number("Chance", 100, 0, 100, 1).suffix("%");
    private final BoolSetting explosions = bool("Explosions", true);

    public Velocity() {
        super("Velocity", Category.COMBAT, "减少受到的击退");
        complements("Velocity", "AntiKnockback");
        on(PacketEvent.Receive.class, this::onPacket);
    }

    @Override
    public String getSuffix() { return horizontal.getInt() + "% " + vertical.getInt() + "%"; }

    private void onPacket(PacketEvent.Receive e) {
        double h = horizontal.get() / 100.0, v = vertical.get() / 100.0;
        S12PacketEntityVelocity s12 = e.as(S12PacketEntityVelocity.class);
        if (s12 != null && s12.getEntityID() == player().getEntityId()) {
            if (!MathUtil.chance(chance.get())) return;
            if (h == 0 && v == 0) { e.cancel(); return; }
            e.setPacket(new S12PacketEntityVelocity(s12.getEntityID(), s12.getMotionX() / 8000.0 * h, s12.getMotionY() / 8000.0 * v, s12.getMotionZ() / 8000.0 * h));
            return;
        }
        S27PacketExplosion s27 = e.as(S27PacketExplosion.class);
        if (s27 != null && explosions.get() && MathUtil.chance(chance.get())) {
            Vec3 motion = new Vec3(s27.func_149149_c() * h, s27.func_149144_d() * v, s27.func_149147_e() * h);
            e.setPacket(new S27PacketExplosion(s27.getX(), s27.getY(), s27.getZ(), s27.getStrength(), s27.getAffectedBlockPositions(), motion));
        }
    }
}
