package gale.spotify;

import gale.core.Scheduler;
import gale.spotify.model.Device;
import gale.spotify.model.Playback;
import gale.spotify.ui.SpotifyScreen;
import gale.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /spotify: log in, see and control what is playing, open the player. */
public final class SpotifyCommand extends CommandBase {
    @Override
    public String getCommandName() { return "spotify"; }

    @Override
    public List<String> getCommandAliases() { return Arrays.asList("sp"); }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/spotify help"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        SpotifyModule m = SpotifyModule.instance;
        if (m == null) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        SpotifyPlayer p = m.spotify();
        boolean loggedIn = p.api().loggedIn();
        switch (sub) {
            case "":
            case "help":
                help(m);
                return;
            case "login":
                if (!m.isEnabled()) m.setEnabled(true);
                m.login();
                return;
            case "logout":
                m.logout();
                return;
            case "gui":
            case "player":
                Scheduler.main(() -> Minecraft.getMinecraft().displayGuiScreen(new SpotifyScreen(m)));
                return;
            default:
                break;
        }
        if (!loggedIn) {
            ChatUtil.error("先 /spotify login 登录");
            return;
        }
        if (!p.running()) m.setEnabled(true);
        switch (sub) {
            case "status":
            case "now": status(p); break;
            case "play": p.play(); break;
            case "pause": p.pause(); break;
            case "toggle":
            case "p": p.togglePlay(); break;
            case "next":
            case "n": p.next(); break;
            case "prev":
            case "previous":
            case "back": p.previous(); break;
            case "shuffle": p.toggleShuffle(); break;
            case "repeat": p.cycleRepeat(); break;
            case "vol":
            case "volume": volume(p, args); break;
            case "seek": seek(p, args); break;
            case "search":
                if (args.length < 2) ChatUtil.error("/spotify search <歌名或歌手>");
                else {
                    String q = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    Scheduler.main(() -> {
                        SpotifyScreen s = new SpotifyScreen(m, SpotifyScreen.Section.SEARCH);
                        Minecraft.getMinecraft().displayGuiScreen(s);
                        s.search(q);
                    });
                }
                break;
            case "devices":
            case "device": devices(p, args); break;
            default: ChatUtil.error("不认识 \"" + sub + "\"，输入 /spotify help");
        }
    }

    private static void help(SpotifyModule m) {
        ChatUtil.print("&bSpotify&7：在游戏里看和控制 Spotify 在放的歌（声音还是 Spotify 自己放）");
        if (m.clientId.get().trim().isEmpty()) {
            ChatUtil.raw("  &e第一次用：");
            ChatUtil.raw("  &f1. &7打开 &fdeveloper.spotify.com/dashboard&7，登录，Create app");
            ChatUtil.raw("  &f2. &7Redirect URI 填 &f" + m.redirectUri() + "&7，API 勾 &fWeb API&7，保存");
            ChatUtil.raw("  &f3. &7在 Settings 里复制 &fClient ID&7，填进 Gale 的 Spotify 模块设置");
            ChatUtil.raw("  &f4. &7/spotify login");
        }
        String[][] lines = { { "login / logout", "登录 / 退出" }, { "gui", "打开播放器" }, { "status", "现在放的是什么" },
            { "p / play / pause", "播放暂停" }, { "n / prev", "下一首 / 上一首" }, { "vol <0-100|+10|-10>", "音量" }, { "seek <分:秒>", "跳到" },
            { "shuffle / repeat", "随机 / 循环（关、列表、单曲）" }, { "search <歌名>", "搜索" }, { "devices [序号]", "设备列表 / 换到这个设备上放" } };
        for (String[] l : lines) ChatUtil.raw("  &f/spotify " + l[0] + " &8- &7" + l[1]);
        ChatUtil.raw("  &7控制播放要 Spotify &fPremium&7；免费账号只能看在放什么。");
    }

    private static void status(SpotifyPlayer p) {
        Playback pb = p.playback;
        if (pb == null || pb.track == null) {
            ChatUtil.info("没有在放的歌" + (p.message.isEmpty() ? "" : "（" + p.message + "）"));
            return;
        }
        ChatUtil.print("&a" + (pb.playing ? "▶ " : "❚❚ ") + "&f" + pb.track.name + " &7- " + pb.track.artists + " &8" + gale.spotify.ui.Icons.time(pb.progressNow())
            + " / " + gale.spotify.ui.Icons.time(pb.track.durationMs) + (pb.device == null ? "" : " &7在 " + pb.device.name + (pb.volume() >= 0 ? "，音量 " + pb.volume() + "%" : "")));
    }

    private static void volume(SpotifyPlayer p, String[] args) {
        if (args.length < 2) {
            Playback pb = p.playback;
            ChatUtil.info("音量：" + (pb == null || pb.volume() < 0 ? "不知道" : pb.volume() + "%"));
            return;
        }
        try {
            String v = args[1].replace("%", "");
            if (v.startsWith("+") || v.startsWith("-")) p.changeVolume(Integer.parseInt(v.replace("+", "")));
            else p.setVolume(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            ChatUtil.error("/spotify vol <0-100> 或 +10 / -10");
        }
    }

    private static void seek(SpotifyPlayer p, String[] args) {
        if (args.length < 2) {
            ChatUtil.error("/spotify seek <分:秒> 或 <秒>");
            return;
        }
        try {
            String[] parts = args[1].split(":");
            long s = parts.length == 2 ? Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]) : Long.parseLong(parts[0]);
            p.seek(s * 1000);
        } catch (NumberFormatException e) {
            ChatUtil.error("/spotify seek <分:秒> 或 <秒>");
        }
    }

    private static void devices(SpotifyPlayer p, String[] args) {
        List<Device> list = p.devices;
        if (args.length >= 2 && !list.isEmpty()) {
            try {
                Device d = list.get(Integer.parseInt(args[1]) - 1);
                p.transfer(d.id);
                ChatUtil.info("换到 " + d.name + " 上放");
            } catch (RuntimeException e) {
                ChatUtil.error("没有这个设备，先 /spotify devices 看序号");
            }
            return;
        }
        p.loadDevices(() -> Scheduler.main(() -> {
            List<Device> l = p.devices;
            if (l.isEmpty()) ChatUtil.info("没有设备：打开电脑或手机上的 Spotify");
            for (int i = 0; i < l.size(); i++) {
                Device d = l.get(i);
                ChatUtil.raw("  &f" + (i + 1) + ". " + (d.active ? "&a" : "&7") + d.name + " &8" + d.type + (d.active ? " &a(在放)" : ""));
            }
            if (!l.isEmpty()) ChatUtil.raw("  &7/spotify devices <序号> 换到它上面放");
        }));
    }
}
