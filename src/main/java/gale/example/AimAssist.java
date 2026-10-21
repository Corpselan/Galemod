package gale.example;

import gale.event.impl.FrameEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.util.MathUtil;
import gale.util.PlayerUtil;
import gale.util.RotationUtil;
import gale.util.TargetFinder;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.input.Mouse;

/**
 * Turns the camera toward the nearest target in view, smoothly, per frame. Example of TargetFinder, RotationUtil and
 * FrameEvent (camera movement belongs in frames, not ticks, to look smooth).
 */
public final class AimAssist extends Module {
    private final NumberSetting range = number("Range", 4.5, 3, 8, 0.1).suffix(" blocks");
    private final NumberSetting fov = number("FOV", 90, 15, 360, 5).suffix("°");
    private final NumberSetting speed = number("Speed", 90, 10, 360, 5).suffix("°/s");
    private final BoolSetting vertical = bool("Vertical", false).describe("也上下瞄");
    private final BoolSetting clicking = bool("Only clicking", true).describe("只在按住左键时");
    private final BoolSetting weaponOnly = bool("Weapon only", false);
    private final ModeSetting point = mode("Aim at", "Closest", "Closest", "Center", "Head");
    private long lastFrame;

    public AimAssist() {
        super("Aim Example", Category.COMBAT, "镜头慢慢转向范围内最近的目标");
        complements("AimAssist", "Aim Assist");
        on(FrameEvent.class, e -> aim());
    }

    @Override
    public String getSuffix() { return String.valueOf(speed.getInt()); }

    private void aim() {
        long now = System.nanoTime();
        float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (mc.currentScreen != null || seconds == 0) return;
        if (clicking.get() && !Mouse.isButtonDown(0)) return;
        if (weaponOnly.get() && !PlayerUtil.isHoldingWeapon()) return;
        EntityLivingBase target = TargetFinder.create().range(range.get()).fov(fov.getFloat()).sort(TargetFinder.Sort.ANGLE).first();
        if (target == null) return;
        RotationUtil.AimPoint p = point.is("Head") ? RotationUtil.AimPoint.HEAD : point.is("Center") ? RotationUtil.AimPoint.CENTER : RotationUtil.AimPoint.CLOSEST;
        float[] want = RotationUtil.to(target, p);
        float[] now2 = RotationUtil.camera();
        float step = speed.getFloat() * seconds * (float) MathUtil.random(0.85, 1.15);
        float dYaw = MathUtil.clamp(MathUtil.angleDiff(now2[0], want[0]), -step, step);
        float dPitch = vertical.get() ? MathUtil.clamp(want[1] - now2[1], -step * 0.6f, step * 0.6f) : 0;
        RotationUtil.turnCamera(dYaw, dPitch, true);
    }
}
