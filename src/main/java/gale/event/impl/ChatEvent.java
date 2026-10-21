package gale.event.impl;

import gale.event.CancellableEvent;
import gale.util.ChatUtil;
import net.minecraft.util.IChatComponent;

/** A chat line from the server. Cancel to hide it, or replace it. type: 0 chat, 1 system, 2 action bar. Game thread. */
public final class ChatEvent extends CancellableEvent {
    public final byte type;
    private IChatComponent message;

    public ChatEvent(IChatComponent message, byte type) {
        this.message = message;
        this.type = type;
    }

    public IChatComponent getMessage() { return message; }

    public void setMessage(IChatComponent message) { this.message = message; }

    /** The text without colour codes. */
    public String text() { return ChatUtil.strip(message.getUnformattedText()); }

    /** The text with colour codes. */
    public String formatted() { return message.getFormattedText(); }
}
