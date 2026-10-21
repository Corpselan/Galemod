package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.settings.KeyBinding;

public class KeyInputEvent extends Event {
    private final KeyBinding key;

    public KeyInputEvent(KeyBinding key) {
        this.key = key;
    }

    public KeyBinding getKey() { return key; }
}
