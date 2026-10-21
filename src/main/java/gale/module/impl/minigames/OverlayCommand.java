package gale.module.impl.minigames;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.network.play.client.C01PacketChatMessage;

import java.util.Arrays;
import java.util.List;

/**
 * Jappylay's /ov, as the script has it (sc, hide, clearhidden, reload). While Jappylay is off, or gives way to the
 * Jappylay script running in B4, the command goes on as chat, so the script (or the server) gets it.
 */
public final class OverlayCommand extends CommandBase {
    @Override
    public String getCommandName() { return "ov"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/ov [sc|hide|clearhidden|reload]"; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, net.minecraft.util.BlockPos pos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "sc", "hide", "clearhidden", "reload") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Jappylay j = Jappylay.get();
        if (j != null && j.isEnabled() && !j.isDeferred()) {
            j.command(args);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        String line = "/ov" + (args.length == 0 ? "" : " " + String.join(" ", Arrays.asList(args)));
        mc.thePlayer.sendQueue.addToSendQueue(new C01PacketChatMessage(line));
    }
}
