package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/** Teams the way Hypixel shows them: scoreboard team, name colour, or leather armour colour. */
public final class TeamUtil {
    public enum Check { SCOREBOARD, NAME_COLOR, ARMOR_COLOR }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private TeamUtil() { }

    /** Whether the player is on your team by any of the checks. */
    public static boolean isTeammate(EntityPlayer other) {
        return isTeammate(other, Check.SCOREBOARD) || isTeammate(other, Check.NAME_COLOR) || isTeammate(other, Check.ARMOR_COLOR);
    }

    public static boolean isTeammate(EntityPlayer other, Check check) {
        EntityPlayer me = mc.thePlayer;
        if (me == null || other == null || other == me) return false;
        switch (check) {
            case SCOREBOARD:
                return me.getTeam() != null && me.isOnSameTeam(other);
            case NAME_COLOR: {
                char a = nameColor(me), b = nameColor(other);
                return a != 0 && a == b;
            }
            default: {
                int a = armorColor(me), b = armorColor(other);
                return a != -1 && a == b;
            }
        }
    }

    /** The first colour code of the player's shown name ('c' for red...), 0 if none. */
    public static char nameColor(EntityPlayer p) { return ChatUtil.firstColor(p.getDisplayName().getFormattedText()); }

    /** The colour of the player's leather chestplate or helmet, -1 if not leather. */
    public static int armorColor(EntityPlayer p) {
        for (int slot : new int[]{ 2, 3 }) {
            ItemStack s = p.inventory.armorInventory[slot];
            if (s != null && s.getItem() instanceof ItemArmor && ((ItemArmor) s.getItem()).getArmorMaterial() == ItemArmor.ArmorMaterial.LEATHER)
                return ((ItemArmor) s.getItem()).getColor(s);
        }
        return -1;
    }
}
