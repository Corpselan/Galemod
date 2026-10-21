package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.util.IChatComponent;

public class ChatReceivedEvent extends Event {
    private final IChatComponent component;
    private final byte type;

    public ChatReceivedEvent(IChatComponent component, byte type) {
        this.component = component;
        this.type = type;
    }

    public IChatComponent getComponent() { return component; }

    public byte getType() { return type; }

    public static final byte CHAT = 0;
    public static final byte SYSTEM = 1;
    public static final byte ACTION_BAR = 2;
}
