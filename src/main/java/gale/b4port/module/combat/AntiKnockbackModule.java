package gale.b4port.module.combat;

import gale.b4port.module.setting.ButtonSetting;

import java.util.concurrent.atomic.AtomicLong;

/** B4's AntiKnockback is not ported (it never delays). */
public final class AntiKnockbackModule {
    public static final AtomicLong delayUntil = new AtomicLong();
    public static ButtonSetting disableAttackWhileDelay = new ButtonSetting("Disable attack while delay", false);

    private AntiKnockbackModule() {
    }
}
