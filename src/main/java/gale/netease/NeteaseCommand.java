package gale.netease;

import gale.core.Scheduler;
import gale.netease.model.NeteaseTrack;
import gale.netease.ui.NeteaseScreen;
import gale.spotify.ui.Icons;
import gale.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /netease (/ne, /wyy): the player, search and play, the controls, logging in. */
public final class NeteaseCommand extends CommandBase {
    @Override
    public String getCommandName() { return "netease"; }

    @Override
    public List<String> getCommandAliases() { return Arrays.asList("ne", "wyy", "163"); }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/netease help"; }

    @Override
    public int getRequiredPermissionLevel() { return 0; }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        NeteaseModule m = NeteaseModule.instance;
        if (m == null) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String rest = args.length < 2 ? "" : String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        NeteasePlayer p = NeteasePlayer.INSTANCE;
        switch (sub) {
            case "":
            case "help": help(); return;
            case "gui":
            case "player":
                enable(m);
                screen(m, NeteaseScreen.Section.NOW);
                return;
            case "login":
                enable(m);
                screen(m, NeteaseScreen.Section.LOGIN);
                return;
            case "logout":
                NeteaseApi.logout();
                NeteaseLogin.cancel();
                NeteaseLogin.state = NeteaseLogin.State.IDLE;
                ChatUtil.print("&7网易云已退出登录（这台电脑上的登录信息已删除）");
                return;
            case "cookie":
                if (rest.isEmpty()) {
                    ChatUtil.error("/netease cookie <MUSIC_U 的值>（浏览器登录 music.163.com 后在 Cookie 里找）");
                    return;
                }
                NeteaseApi.saveCookie(rest.contains("=") ? rest : "MUSIC_U=" + rest);
                background(() -> {
                    long uid = NeteaseApi.user();
                    Scheduler.main(() -> {
                        if (uid != 0) ChatUtil.print("&a网易云已登录：" + NeteaseApi.nickname);
                        else ChatUtil.error("这个 cookie 登录不上（过期了或者填错了）");
                    });
                });
                return;
            case "search":
                if (rest.isEmpty()) {
                    ChatUtil.error("/netease search <歌名或歌手>");
                    return;
                }
                enable(m);
                Scheduler.main(() -> {
                    NeteaseScreen s = new NeteaseScreen(m, NeteaseScreen.Section.SEARCH);
                    Minecraft.getMinecraft().displayGuiScreen(s);
                    s.search(rest);
                });
                return;
            case "play":
                if (rest.isEmpty()) {
                    if (p.state == NeteasePlayer.State.PAUSED) p.togglePause();
                    return;
                }
                enable(m);
                playFirst(rest);
                return;
            default: break;
        }
        switch (sub) {
            case "status":
            case "now": status(p); break;
            case "p":
            case "toggle": p.togglePause(); break;
            case "pause": if (p.state == NeteasePlayer.State.PLAYING) p.togglePause(); break;
            case "resume": if (p.state == NeteasePlayer.State.PAUSED) p.togglePause(); break;
            case "n":
            case "next": p.next(); break;
            case "prev":
            case "previous":
            case "back": p.previous(); break;
            case "stop": p.stop(); break;
            case "shuffle":
                p.shuffle = !p.shuffle;
                ChatUtil.info("随机播放：" + (p.shuffle ? "开" : "关"));
                break;
            case "repeat":
                p.cycleRepeat();
                ChatUtil.info("循环：" + (p.repeat == NeteasePlayer.Repeat.LIST ? "列表" : p.repeat == NeteasePlayer.Repeat.ONE ? "单曲" : "关"));
                break;
            case "vol":
            case "volume": volume(m, rest); break;
            case "seek": seek(p, rest); break;
            default: ChatUtil.error("不认识 \"" + sub + "\"，输入 /netease help");
        }
    }

    private static void enable(NeteaseModule m) {
        if (!m.isEnabled()) m.setEnabled(true);
    }

    private static void screen(NeteaseModule m, NeteaseScreen.Section s) {
        Scheduler.main(() -> Minecraft.getMinecraft().displayGuiScreen(new NeteaseScreen(m, s)));
    }

    private static void background(Runnable r) {
        Thread t = new Thread(r, "Gale NetEase command");
        t.setDaemon(true);
        t.start();
    }

    private static void playFirst(String q) {
        background(() -> {
            List<NeteaseTrack> found = NeteaseApi.search(q, 20);
            Scheduler.main(() -> {
                if (found.isEmpty()) {
                    ChatUtil.error("没搜到 \"" + q + "\"" + (NeteaseApi.lastSearchError.isEmpty() ? "" : "（" + NeteaseApi.lastSearchError + "）"));
                    return;
                }
                NeteasePlayer.INSTANCE.playQueue(found, 0);
                ChatUtil.info("播放：" + found.get(0));
            });
        });
    }

    private static void help() {
        ChatUtil.print("&c网易云音乐&7：在游戏里直接放（声音从游戏出来），歌词跟着走（Lyrics 模块）");
        String[][] lines = { { "gui", "打开播放器" }, { "play <歌名>", "搜一下，放第一首" }, { "search <歌名>", "搜索" }, { "login / logout", "扫码登录 / 退出" },
            { "cookie <MUSIC_U>", "用浏览器里的登录 cookie 登录" }, { "status", "现在放的是什么" }, { "p / pause / resume", "播放暂停" },
            { "n / prev", "下一首 / 上一首" }, { "vol <0-100|+10|-10>", "音量" }, { "seek <分:秒>", "跳到" }, { "shuffle / repeat", "随机 / 循环（列表、单曲、关）" },
            { "stop", "停止" } };
        for (String[] l : lines) ChatUtil.raw("  &f/netease " + l[0] + " &8- &7" + l[1]);
        ChatUtil.raw("  &7也可以 /ne 或 /wyy。不登录也能搜歌、听免费的歌；VIP 歌曲和自己的歌单要登录。");
    }

    private static void status(NeteasePlayer p) {
        NeteaseTrack t = p.current;
        if (t == null) {
            ChatUtil.info("没有在放的歌");
            return;
        }
        ChatUtil.print("&c" + (p.state == NeteasePlayer.State.PLAYING ? "▶ " : "❚❚ ") + "&f" + t.name + " &7- " + t.artists + " &8" + Icons.time(p.positionMs())
            + " / " + Icons.time(t.durationMs) + (p.message.isEmpty() ? "" : " &e" + p.message));
    }

    private static void volume(NeteaseModule m, String v) {
        if (v.isEmpty()) {
            ChatUtil.info("音量：" + m.volume.getInt() + "%");
            return;
        }
        try {
            String s = v.replace("%", "");
            if (s.startsWith("+") || s.startsWith("-")) m.changeVolume(Integer.parseInt(s.replace("+", "")));
            else m.volume.set(Math.max(0, Math.min(100, Integer.parseInt(s))));
        } catch (NumberFormatException e) {
            ChatUtil.error("/netease vol <0-100> 或 +10 / -10");
        }
    }

    private static void seek(NeteasePlayer p, String v) {
        try {
            String[] parts = v.split(":");
            long s = parts.length == 2 ? Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]) : Long.parseLong(parts[0]);
            p.seek(s * 1000);
        } catch (NumberFormatException e) {
            ChatUtil.error("/netease seek <分:秒> 或 <秒>");
        }
    }
}
