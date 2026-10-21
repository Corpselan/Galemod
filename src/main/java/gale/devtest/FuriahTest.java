package gale.devtest;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.FrameEvent;
import gale.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.function.IntConsumer;

/**
 * Furiah's Visuals' weapon models and kill effects under Gale (gradlew runClient -Pfvtest), with the FV config in
 * run/config/furiahsvisuals.json: a sword with Gale's SwordModel on and off, an axe and a bow, then FV's own test kill
 * on its test dummy. Screenshots in run/screenshots/fv-*.png; FV's log lines tell what it did.
 */
final class FuriahTest {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static String shot;
    private static boolean swordsWere, bowsWere;

    private FuriahTest() { }

    private static void check(String name, boolean ok, Object detail) { DevTest.check(name, ok, detail == null ? "" : String.valueOf(detail)); }

    private static void phase(String name, int ticks, Runnable enter, IntConsumer tick, Runnable exit) {
        DevTest.PHASES.add(new DevTest.Phase("FV " + name, ticks, enter, tick, exit));
    }

    private static void command(String c) {
        DevTest.line("command " + c);
        ClientCommandHandler.instance.executeCommand(mc.thePlayer, c);
    }

    static void phases() {
        Gale.BUS.listen(FrameEvent.class, Priority.MONITOR, e -> {
            if (shot == null) return;
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, shot + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            DevTest.line("screenshot screenshots/" + shot + ".png");
            shot = null;
        }, "fv-test");

        phase("setup", 60, () -> {
            Module fv = Gale.modules().get("Furiah's Visuals");
            check("Furiah's Visuals is loaded", fv != null, null);
            if (fv != null) fv.setEnabled(true);
            Module swords = Gale.modules().get("SwordModel"), bows = Gale.modules().get("BowModel");
            swordsWere = swords.isEnabled();
            bowsWere = bows.isEnabled();
            DevTest.line("Gale SwordModel " + swordsWere + ", BowModel " + bowsWere);
            mc.gameSettings.showInventoryAchievementHint = false;
            mc.guiAchievement.clearAchievements();
            DevTest.server(sp -> {
                sp.inventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
                sp.inventory.setInventorySlotContents(1, new ItemStack(Items.iron_axe));
                sp.inventory.setInventorySlotContents(2, new ItemStack(Items.bow));
                sp.inventory.setInventorySlotContents(3, new ItemStack(Items.stick));
                sp.inventory.currentItem = 0;
                sp.inventoryContainer.detectAndSendChanges();
            });
            mc.thePlayer.inventory.currentItem = 0;
            mc.thePlayer.rotationPitch = 10;
            mc.thePlayer.rotationYaw = 0;
        }, t -> { }, () -> check("holding the sword", mc.thePlayer.getHeldItem() != null && mc.thePlayer.getHeldItem().getItem() == Items.diamond_sword,
            mc.thePlayer.getHeldItem()));

        phase("sword, Gale's SwordModel as it is", 40, () -> { }, t -> { if (t == 35) shot = "fv-1-sword-gale-swordmodel-" + (swordsWere ? "on" : "off"); }, () -> { });

        phase("sword, Gale's SwordModel off", 40, () -> {
            Gale.modules().get("SwordModel").setEnabled(false);
            Gale.modules().get("BowModel").setEnabled(false);
        }, t -> { if (t == 35) shot = "fv-2-sword-gale-models-off"; }, () -> { });

        phase("axe", 30, () -> mc.thePlayer.inventory.currentItem = 1, t -> { if (t == 25) shot = "fv-3-axe"; }, () -> { });

        phase("bow", 30, () -> mc.thePlayer.inventory.currentItem = 2, t -> { if (t == 25) shot = "fv-4-bow"; }, () -> { });

        phase("stick", 30, () -> mc.thePlayer.inventory.currentItem = 3, t -> { if (t == 25) shot = "fv-5-stick"; }, () -> { });

        phase("test dummy", 60, () -> {
            mc.thePlayer.inventory.currentItem = 0;
            mc.thePlayer.rotationPitch = 0;
            command("/furiah dummy");
        }, t -> { if (t == 50) shot = "fv-6-dummy"; }, () -> { });

        phase("test kill", 80, () -> command("/furiah dummy kill"), t -> {
            if (t == 4) shot = "fv-7-kill-a";
            if (t == 12) shot = "fv-7-kill-b";
            if (t == 25) shot = "fv-7-kill-c";
            if (t == 45) shot = "fv-7-kill-d";
        }, () -> { });

        phase("cleanup", 5, () -> {
            command("/furiah dummy remove");
            Gale.modules().get("SwordModel").setEnabled(swordsWere);
            Gale.modules().get("BowModel").setEnabled(bowsWere);
        }, t -> { }, () -> { });
    }
}
