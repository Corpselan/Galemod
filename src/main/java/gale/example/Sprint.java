package gale.example;

import gale.event.impl.InputEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;

/**
 * Sprints whenever you walk forward. Example of InputEvent: it only asks to sprint, and the game still applies its
 * own rules (enough food, not using an item, not against a wall). Gives way to another client's Sprint.
 */
public final class Sprint extends Module {
    private final BoolSetting whileUsing = bool("While using items", false).describe("吃东西、举剑时也保持疾跑（原版会停）");

    public Sprint() {
        super("Sprint", Category.MOVEMENT, "往前走时自动疾跑");
        complements("Sprint");
        on(InputEvent.class, e -> {
            if (e.forward > 0 && !e.sneak && (whileUsing.get() || !player().isUsingItem())) e.sprint = true;
        });
    }
}
