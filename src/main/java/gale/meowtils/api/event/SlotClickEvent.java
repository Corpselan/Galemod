package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;

/** Meowtils sees inventory clicks before they happen; Gale never posts this (extensions that listen for it still load). */
public class SlotClickEvent extends Event {
    public static final int CLICK_NORMAL = 0, CLICK_SHIFT = 1, CLICK_HOTBAR = 2, CLICK_MIDDLE = 3, CLICK_DROP = 4, CLICK_DRAG = 5, CLICK_DOUBLE = 6;
    public static final int BUTTON_LEFT = 0, BUTTON_RIGHT = 1, BUTTON_MIDDLE = 2;
    private final GuiContainer guiContainer;
    private final Slot slot;
    private final int slotId;
    private int clickedButton, clickType;
    private boolean replaceClick;

    public SlotClickEvent(GuiContainer guiContainer, Slot slot, int slotId, int clickedButton, int clickType) {
        this.guiContainer = guiContainer;
        this.slot = slot;
        this.slotId = slotId;
        this.clickedButton = clickedButton;
        this.clickType = clickType;
    }

    public GuiContainer getGuiContainer() { return guiContainer; }

    public Slot getSlot() { return slot; }

    public int getSlotId() { return slotId; }

    public int getClickedButton() { return clickedButton; }

    public int getClickType() { return clickType; }

    public boolean getReplaceClick() { return replaceClick; }

    public void setClickedButton(int clickedButton) { this.clickedButton = clickedButton; }

    public void setClickType(int clickType) { this.clickType = clickType; }

    public void setReplaceClick() { this.replaceClick = true; }
}
