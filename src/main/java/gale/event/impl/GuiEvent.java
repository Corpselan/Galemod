package gale.event.impl;

import gale.event.CancellableEvent;
import net.minecraft.client.gui.GuiScreen;

/** A screen is about to open (null: the current one closes). Cancel to keep it from opening, or open another. Game thread. */
public final class GuiEvent extends CancellableEvent {
    private GuiScreen gui;

    public GuiEvent(GuiScreen gui) { this.gui = gui; }

    public GuiScreen getGui() { return gui; }

    public void setGui(GuiScreen gui) { this.gui = gui; }

    public boolean isClosing() { return gui == null; }
}
