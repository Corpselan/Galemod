package gale.util;

import com.google.common.collect.Multimap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Finding items, the best weapon, blocks to build with, and clicking inventory slots. */
public final class InventoryUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** blocks that are bad to build with (fall, interact, slow you, explode) */
    private static final Set<Block> BAD_BLOCKS = new HashSet<>(Arrays.asList(Blocks.tnt, Blocks.web, Blocks.chest, Blocks.trapped_chest,
        Blocks.ender_chest, Blocks.crafting_table, Blocks.furnace, Blocks.enchanting_table, Blocks.anvil, Blocks.soul_sand, Blocks.ladder,
        Blocks.torch, Blocks.redstone_torch, Blocks.carpet, Blocks.snow_layer, Blocks.waterlily, Blocks.vine, Blocks.dispenser,
        Blocks.dropper, Blocks.noteblock, Blocks.jukebox, Blocks.beacon, Blocks.skull, Blocks.cactus, Blocks.slime_block));

    private InventoryUtil() { }

    /** The stack in an inventory slot: 0..8 hotbar, 9..35 the rest, 36..39 armour (boots first). */
    public static ItemStack stack(int slot) {
        if (slot >= 36) return mc.thePlayer.inventory.armorInventory[slot - 36];
        return mc.thePlayer.inventory.getStackInSlot(slot);
    }

    /** The first hotbar slot whose stack passes the test, or -1. */
    public static int findHotbar(Predicate<ItemStack> test) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = stack(i);
            if (s != null && test.test(s)) return i;
        }
        return -1;
    }

    /** How many items in the whole inventory pass the test. */
    public static int count(Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = stack(i);
            if (s != null && test.test(s)) n += s.stackSize;
        }
        return n;
    }

    /** Attack damage of an item including Sharpness (1 for bare hands and non-weapons). */
    public static double damage(ItemStack s) {
        if (s == null) return 1;
        double d = 1;
        Multimap<String, AttributeModifier> mods = s.getAttributeModifiers();
        for (AttributeModifier m : mods.get("generic.attackDamage")) d += m.getAmount();
        return d + EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, s) * 1.25;
    }

    /** The hotbar slot with the most damage, or -1 if nothing beats bare hands. */
    public static int bestWeapon() {
        int best = -1;
        double bestDamage = 1;
        for (int i = 0; i < 9; i++) {
            double d = damage(stack(i));
            if (d > bestDamage) { best = i; bestDamage = d; }
        }
        return best;
    }

    /** A full block that is good to build with. */
    public static boolean isGoodBlock(ItemStack s) {
        if (s == null || !(s.getItem() instanceof ItemBlock) || s.stackSize <= 0) return false;
        Block b = ((ItemBlock) s.getItem()).getBlock();
        return b.isFullCube() && !(b instanceof BlockFalling) && !BAD_BLOCKS.contains(b);
    }

    /** The hotbar slot with the most good blocks, or -1. */
    public static int bestBlocks() {
        int best = -1, most = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = stack(i);
            if (isGoodBlock(s) && s.stackSize > most) { best = i; most = s.stackSize; }
        }
        return best;
    }

    public static int blockCount() { return count(InventoryUtil::isGoodBlock); }

    /** Clicks a slot of the open container, as a mouse click would (mode 0 click, 1 shift-click, 2 hotbar swap...). */
    public static void click(int windowId, int slot, int button, int mode) {
        mc.playerController.windowClick(windowId, slot, button, mode, mc.thePlayer);
    }

    /** Clicks a slot of your own inventory (container slot numbers: 9..35 inventory, 36..44 hotbar). */
    public static void clickOwn(int slot, int button, int mode) {
        click(mc.thePlayer.inventoryContainer.windowId, slot, button, mode);
    }
}
