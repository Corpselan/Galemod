package gale.b4port.module.player;

import gale.b4port.client.Overlay;
import gale.b4port.module.setting.ButtonSetting;
import gale.b4port.module.setting.SliderSetting;
import gale.b4port.util.ItemUtils;
import gale.b4port.util.PlayerUtils;
import gale.b4port.util.SwapSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** B4's AutoSwap, as Scaffold's "Auto swap" uses it (with AutoSwap's default settings). */
public final class AutoSwapModule {
    public static SliderSetting switchAt = SliderSetting.fixed("Switch at", 1.0);
    public static ButtonSetting onlySameType = new ButtonSetting("Only same type", false);
    public static ButtonSetting swapToGreater = new ButtonSetting("Swap to greater stack", false);
    public static boolean swapped;

    private AutoSwapModule() {
    }

    public static void swap(SwapSlot origin) {
        Minecraft mc = Minecraft.getMinecraft();
        swapped = false;
        if (Overlay.screenOpen()) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean isBlock = ItemUtils.isScaffoldBlock(held);
        if (!swapToGreater.isToggled() && isBlock && (double) held.stackSize > switchAt.getInput()) {
            return;
        }
        PlayerUtils.clearEmptyStacks();
        int best = -1;
        int slot = -1;
        for (int i = InventoryPlayer.getHotbarSize() - 1; i >= 0; --i) {
            if (i == mc.thePlayer.inventory.currentItem) continue;
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s == null || !(s.getItem() instanceof ItemBlock)) continue;
            if (best >= s.stackSize) continue;
            if (isBlock) {
                if (held.stackSize >= s.stackSize) continue;
                if (onlySameType.isToggled() && held.getItem() != s.getItem()) continue;
            }
            if (!ItemUtils.isScaffoldBlock(s)) continue;
            best = s.stackSize;
            slot = i;
        }
        if (slot != -1 && mc.thePlayer.inventory.currentItem != slot) {
            PlayerUtils.setSlot(slot, false);
            swapped = true;
        }
    }

    public static void restore(SwapSlot origin, boolean swapBack) {
        swapped = false;
        if (origin.slot == -1) {
            return;
        }
        int n = origin.slot;
        origin.slot = -1;
        if (!swapBack || Overlay.screenOpen()) {
            return;
        }
        Minecraft.getMinecraft().thePlayer.inventory.currentItem = n;
    }

    public static int countBlocks() {
        int n = 0;
        for (int i = 0; i < InventoryPlayer.getHotbarSize(); ++i) {
            ItemStack s = Minecraft.getMinecraft().thePlayer.inventory.getStackInSlot(i);
            if (!ItemUtils.isScaffoldBlock(s)) continue;
            n += s.stackSize;
        }
        return n;
    }

    public static void remember(SwapSlot origin) {
        origin.slot = Minecraft.getMinecraft().thePlayer.inventory.currentItem;
    }
}
