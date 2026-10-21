package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.gui.GuiScreen;

public class GuiOpenEvent extends Event {
    private final GuiScreen gui;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }

    public GuiScreen getGui() { return gui; }
}
