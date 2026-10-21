package gale.command;

import gale.Gale;
import gale.bridge.Bridge;
import gale.bridge.External;
import gale.core.Scheduler;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.render.KillEffects;
import gale.module.setting.Setting;
import gale.script.B4Link;
import gale.script.api.ScreenApi;
import gale.script.Functions;
import gale.script.ScriptEvents;
import gale.ui.ClickGui;
import gale.util.ChatUtil;
import gale.util.Friends;
import gale.util.KeyUtil;
import gale.util.PacketUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /gale ...}: a Forge client command, so it never reaches the server. B4 scripts can run it too with
 * {@code client.runForgeCommand("gale ...")}. Type {@code /gale} for the list.
 */
public final class GaleCommand extends CommandBase {
    private static final String[] SUBS = { "list", "toggle", "bind", "set", "get", "reset", "hide", "friend", "config", "bridge", "c17", "link", "api", "perf", "killfx", "info", "gui" };

    @Override
    public String getCommandName() { return "gale"; }

    @Override
    public String getCommandUsage(ICommandSender sender) { return "/gale help"; }

    @Override
    public List<String> getCommandAliases() { return Collections.singletonList("gl"); }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "list": list(rest); break;
            case "t":
            case "toggle": toggle(rest); break;
            case "bind": bind(rest); break;
            case "set": set(rest); break;
            case "get": get(rest); break;
            case "reset": reset(rest); break;
            case "hide": hide(rest); break;
            case "friend":
            case "f": friend(rest); break;
            case "config":
            case "c": config(rest); break;
            case "bridge": bridge(rest); break;
            case "c17": c17(rest); break;
            case "link": B4Link.link(); break;
            case "chatcb": if (rest.length > 0) try { ScreenApi.chatCallback(Integer.parseInt(rest[0])); } catch (NumberFormatException ignored) { } break;
            case "api": api(rest); break;
            case "perf": perf(); break;
            case "killfx": killfx(rest); break;
            case "info": info(); break;
            case "gui": Scheduler.main(gale.module.impl.client.ClickGuiModule::open); break;
            case "ext":
            case "extensions": extensions(rest); break;
            case "hud":
                if (rest.length > 0 && rest[0].equalsIgnoreCase("reset")) {
                    gale.hud.HudLayout.resetAll();
                    ChatUtil.print("&aHUD 的位置和大小都还原了");
                } else {
                    ChatUtil.print("&7打开聊天框（T）就能调 HUD：拖动移动，拖角或滚轮缩放，右键还原一个，按住 Shift 不吸附；&f/gale hud reset&7 全部还原");
                }
                break;
            default:
                // "/gale Sprint" toggles a module
                if (Gale.modules().get(args[0]) != null) toggle(args);
                else ChatUtil.error("不认识 \"" + args[0] + "\"，输入 /gale 看用法");
        }
    }

    private static void help() {
        ChatUtil.print("&b/gale&7 用法：");
        String[][] lines = {
            { "list [分类]", "列出模块" }, { "<模块>  或  toggle <模块>", "开关模块" }, { "bind <模块> <键|none> [hold]", "绑定按键（鼠标侧键写 MOUSE3；加 hold 就是按住时才开）" },
            { "set <模块> <设置> <值>", "改设置" }, { "get <模块>", "看模块的设置" }, { "reset <模块>", "设置恢复默认" },
            { "hide <模块>", "在 HUD 列表里隐藏/显示" }, { "friend add|remove|list [名字]", "好友" },
            { "config save|load|list [名字]", "保存/读取配置" }, { "bridge ...", "和别的端交换数据（脚本用）" },
            { "c17 <频道> [十六进制数据]", "立刻发一个 C17 插件消息包（脚本用，B4 脚本自己造不出 C17）" },
            { "link", "B4 脚本调用：把 Gale 的脚本 API 放进 B4 的 bridge（脚本里 bridge.get(\"gale\")）" },
            { "perf", "帧时间分析：最近 10 秒每帧的时间花在哪（要先开 Profiler 模块），也存到 galemod/perf-report.txt" },
            { "killfx preview <1～6|all> | reset", "本地预览当前年份的击杀特效，或重置连杀" },
            { "api [off <脚本名>]", "脚本 API 的状态：连上没有、多少函数、哪些脚本在监听什么；off 取消某个脚本的全部监听" },
            { "info", "版本、检测到的端、桥接状态" },
            { "gui", "打开设置界面" },
            { "extensions [reload|folder]", "Meowtils 扩展（.meowtils）：列出、重新加载、打开文件夹" },
            { "hud [reset]", "HUD 的位置和大小（打开聊天框拖动）；reset 全部还原" } };
        for (String[] l : lines) ChatUtil.raw("  &f/gale " + l[0] + " &8- &7" + l[1]);
    }

    private static Module module(String[] args) {
        if (args.length == 0) { ChatUtil.error("要写模块名"); return null; }
        Module m = Gale.modules().get(args[0]);
        if (m == null) ChatUtil.error("没有模块叫 \"" + args[0] + "\"");
        return m;
    }

    private static void list(String[] args) {
        for (Category c : Category.values()) {
            if (args.length > 0 && !c.display.equalsIgnoreCase(args[0]) && !c.chinese.equals(args[0])) continue;
            List<Module> ms = Gale.modules().in(c);
            if (ms.isEmpty()) continue;
            StringBuilder sb = new StringBuilder("&b" + c.display + "&8: ");
            for (Module m : ms) {
                String state = m.isAlwaysOn() ? "&7" : m.isDeferred() ? "&e" : m.isEnabled() ? "&a" : "&c";
                sb.append(state).append(m.getName()).append(m.getKey() != 0 ? "&8[" + KeyUtil.name(m.getKey()) + "]" : "").append("  ");
            }
            ChatUtil.raw(sb.toString());
        }
        ChatUtil.raw("&8绿=开 红=关 黄=开着但在让另一个端 灰=设置模块");
    }

    private static void toggle(String[] args) {
        Module m = module(args);
        if (m == null) return;
        if (m.isAlwaysOn()) { ChatUtil.warn(m.getName() + " 一直开着（它只放设置），用 /gale get " + m.getName() + " 看设置"); return; }
        m.toggle();
        ChatUtil.print(m.getName() + (m.isEnabled() ? " &a开启" : " &c关闭") + (m.isDeferred() ? " &e（另一个端的同类功能开着，先让它）" : ""));
    }

    private static void bind(String[] args) {
        Module m = module(args);
        if (m == null) return;
        if (args.length < 2) { ChatUtil.info(m.getName() + " 绑定在 " + KeyUtil.name(m.getKey()) + (m.getBindMode() == Module.BindMode.HOLD ? "（按住）" : "")); return; }
        int key = KeyUtil.code(args[1]);
        if (key == -1) { ChatUtil.error("不认识按键 \"" + args[1] + "\""); return; }
        m.setKey(key);
        if (args.length > 2) m.setBindMode(args[2].equalsIgnoreCase("hold") ? Module.BindMode.HOLD : Module.BindMode.TOGGLE);
        ChatUtil.print(m.getName() + " 绑定到 &b" + KeyUtil.name(key) + (m.getBindMode() == Module.BindMode.HOLD ? " &7（按住时开）" : " &7（按一下开关）"));
    }

    private static void set(String[] args) {
        Module m = module(args);
        if (m == null) return;
        if (args.length < 3) { ChatUtil.error("写成 /gale set <模块> <设置> <值>"); return; }
        // setting names may have spaces: try the longest name that matches
        for (int split = args.length - 1; split >= 2; split--) {
            String name = String.join(" ", Arrays.copyOfRange(args, 1, split));
            Setting<?> s = m.getSetting(name);
            if (s == null) continue;
            String value = String.join(" ", Arrays.copyOfRange(args, split, args.length));
            if (s.parse(value)) ChatUtil.print(m.getName() + " 的 " + s.getName() + " = &b" + s.display());
            else ChatUtil.error(s.getName() + " 不接受 \"" + value + "\"" + hint(s));
            return;
        }
        ChatUtil.error(m.getName() + " 没有这个设置，有：" + names(m));
    }

    private static String hint(Setting<?> s) {
        if (s instanceof gale.module.setting.ModeSetting) return "，可选：" + String.join(", ", ((gale.module.setting.ModeSetting) s).getModes());
        if (s instanceof gale.module.setting.NumberSetting) {
            gale.module.setting.NumberSetting n = (gale.module.setting.NumberSetting) s;
            return "，范围 " + n.min + " ~ " + n.max;
        }
        return "";
    }

    private static String names(Module m) {
        List<String> l = new ArrayList<>();
        for (Setting<?> s : m.getSettings()) l.add(s.getName());
        return String.join(", ", l);
    }

    private static void get(String[] args) {
        Module m = module(args);
        if (m == null) return;
        ChatUtil.print("&b" + m.getName() + "&7 " + m.getDescription());
        for (Setting<?> s : m.getSettings())
            ChatUtil.raw("  &f" + s.getName() + " &8= &b" + s.display() + (s.getDescription().isEmpty() ? "" : " &8" + s.getDescription()));
        if (m.getComplements().length > 0) ChatUtil.raw("  &8和别的端的 " + String.join(", ", m.getComplements()) + " 互补");
    }

    private static void reset(String[] args) {
        Module m = module(args);
        if (m == null) return;
        for (Setting<?> s : m.getSettings()) s.reset();
        ChatUtil.print(m.getName() + " 的设置已恢复默认");
    }

    private static void hide(String[] args) {
        Module m = module(args);
        if (m == null) return;
        m.setHidden(!m.isHidden());
        ChatUtil.print(m.getName() + (m.isHidden() ? " 已从列表隐藏" : " 会显示在列表里"));
    }

    private static void friend(String[] args) {
        String op = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        if (op.equals("list")) { ChatUtil.print("好友：" + (Friends.all().isEmpty() ? "（没有）" : String.join(", ", Friends.all()))); return; }
        if (args.length < 2) { ChatUtil.error("写成 /gale friend add|remove <名字>"); return; }
        if (op.equals("add")) ChatUtil.print(Friends.add(args[1]) ? "加了好友 " + args[1] : args[1] + " 已经是好友");
        else if (op.equals("remove") || op.equals("del")) ChatUtil.print(Friends.remove(args[1]) ? "删了好友 " + args[1] : args[1] + " 不是好友");
    }

    private static void config(String[] args) {
        String op = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        String name = args.length > 1 ? args[1] : null;
        switch (op) {
            case "save":
                if (name == null) { Gale.config().save(); ChatUtil.print("已保存"); }
                else ChatUtil.print(Gale.config().save(name) ? "已保存配置 " + name : "保存失败");
                break;
            case "load":
                if (name == null) { ChatUtil.error("写成 /gale config load <名字>"); break; }
                ChatUtil.print(Gale.config().load(name) ? "已读取配置 " + name : "没有配置 " + name);
                Gale.config().markDirty();
                break;
            default:
                ChatUtil.print("配置：" + (Gale.config().profiles().isEmpty() ? "（没有）" : String.join(", ", Gale.config().profiles())) + " &8（在 " + Gale.config().dir() + "）");
        }
    }

    /** For scripts: quiet unless asked to show something. */
    private static void bridge(String[] args) {
        if (args.length == 0) { ChatUtil.info("bridge set <键> <值> | b4 <开着的模块,逗号分隔> | get <键> | list"); return; }
        String op = args[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "set":
                if (args.length >= 2) Bridge.receive(args[1], args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "");
                break;
            case "b4":
                Bridge.receive("b4.enabled", args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "");
                break;
            case "done":
                if (args.length >= 2) Bridge.receive(args[1] + ".request.done", "");
                break;
            case "get":
                if (args.length >= 2) ChatUtil.info(args[1] + " = " + Bridge.get(args[1]));
                break;
            default:
                ChatUtil.info("收到：" + Bridge.inbox());
                ChatUtil.info("发布：" + Bridge.published());
        }
    }

    /**
     * For scripts: sends a C17 plugin message right away, the normal way (B4 and its scripts see it, so a script's blink
     * can hold it in order). B4 scripts cannot build a C17 themselves. Quiet unless the arguments are wrong.
     */
    private static void c17(String[] args) {
        if (args.length == 0) { ChatUtil.error("写成 /gale c17 <频道> [十六进制数据]"); return; }
        if (args[0].length() > 20) { ChatUtil.error("频道名最多 20 个字符"); return; }
        byte[] data = hex(args.length > 1 ? args[1] : "");
        if (data == null) { ChatUtil.error("数据要写成十六进制，例如 0a1b"); return; }
        PacketUtil.send(new C17PacketCustomPayload(args[0], new PacketBuffer(Unpooled.wrappedBuffer(data))));
    }

    private static void api(String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("off")) {
            ChatUtil.print("取消了 " + args[1] + " 的 " + ScriptEvents.off(null, args[1]) + " 个监听");
            return;
        }
        ChatUtil.print("&b脚本 API&7：" + (B4Link.isLinked() ? "&a已连上 B4" + " &8（" + B4Link.linkedBy() + " 先连的）" : "&c还没有 B4 脚本连上（脚本里 client.runForgeCommand(\"gale link\")）"));
        ChatUtil.raw("  &7函数 &f" + Functions.all().size() + " &7个，事件 &f" + ScriptEvents.all().size() + " &7个（脚本里 gale.get(\"help\") / gale.get(\"events\")，说明见 docs/SCRIPTS.md）");
        for (Module m : Gale.modules().in(gale.module.Category.ASSIST)) {
            String info = m.getInfo();
            ChatUtil.raw("  " + (m.isEnabled() ? "&a" : "&c") + m.getName() + (info == null ? "" : " &7" + info));
        }
        for (ScriptEvents.Kind k : ScriptEvents.all())
            if (k.handlerCount() > 0) ChatUtil.raw("  &f" + k.name + " &7← " + String.join(", ", k.owners()));
    }

    private static byte[] hex(String s) {
        if (s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    private static void killfx(String[] args) {
        KillEffects effect = Gale.modules().get(KillEffects.class);
        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            effect.resetStreak();
            ChatUtil.info("击杀特效计数已清零");
            return;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("preview") || args.length > 2) {
            ChatUtil.info("/gale killfx preview <1～6|all> 或 /gale killfx reset");
            return;
        }
        if (Minecraft.getMinecraft().thePlayer == null) { ChatUtil.warn("先进入世界再预览"); return; }
        String stage = args.length == 2 ? args[1] : "all";
        if (!stage.equalsIgnoreCase("all") && !stage.matches("[1-6]")) {
            ChatUtil.warn("预览等级写 1～6 或 all");
            return;
        }
        effect.setEnabled(true);
        if (stage.equalsIgnoreCase("all")) effect.previewAll();
        else effect.preview(Integer.parseInt(stage));
        ChatUtil.info("本地预览，不计入实际连杀");
    }

    private static void perf() {
        gale.core.FrameSampler.Report r = gale.core.FrameSampler.longer();
        if (!gale.core.FrameSampler.isRunning()) {
            ChatUtil.warn("先打开 Profiler 模块（ClickGUI → 优化，或 /gale Profiler），等几秒再看");
            return;
        }
        if (r == null) {
            ChatUtil.info("还在取样，过一两秒再试");
            return;
        }
        List<String> lines = gale.core.FrameSampler.describe(r);
        ChatUtil.print("&b帧时间分析");
        int shown = 0;
        for (String l : lines) {
            if (l.startsWith("      ")) continue;
            ChatUtil.raw("&7" + l);
            if (++shown >= 14) break;
        }
        java.io.File out = new java.io.File(Gale.config().dir(), "perf-report.txt");
        try {
            java.nio.file.Files.write(out.toPath(), String.join(System.lineSeparator(), lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ChatUtil.raw("&7详细（每部分里是哪些方法）存在 &f" + out.getPath());
        } catch (Exception e) {
            ChatUtil.error("存不了 " + out + "：" + e.getMessage());
        }
    }

    private static void extensions(String[] args) {
        String what = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (what.equals("reload")) {
            gale.meowtils.ExtensionLoader.reload();
            ChatUtil.print("&a扩展已重新加载");
        } else if (what.equals("folder")) {
            java.io.File dir = gale.meowtils.ExtensionLoader.galeDir();
            try {
                java.awt.Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                ChatUtil.info(dir.getAbsolutePath());
            }
            return;
        }
        List<String> lines = gale.meowtils.ExtensionLoader.describe();
        ChatUtil.print("&7扩展文件夹：&f" + gale.meowtils.ExtensionLoader.galeDir().getAbsolutePath()
            + (gale.meowtils.ExtensionLoader.meowtilsInstalled() ? "" : "&7（还有 meowtils/extensions）"));
        if (lines.isEmpty()) ChatUtil.raw("  &7（没有加载扩展：把 .meowtils 文件放进文件夹，再 /gale extensions reload）");
        for (String l : lines) ChatUtil.raw("  " + l);
    }

    private static void info() {
        ChatUtil.print("&b" + Gale.NAME + " " + Gale.VERSION + "&7，" + Gale.modules().all().size() + " 个模块，开着 " + Gale.modules().enabled().size() + " 个");
        ChatUtil.raw("  &7一起加载的 mod：&f" + (External.loadedMods().isEmpty() ? "（没有）" : String.join(", ", External.loadedMods())));
        List<String> clients = External.clients();
        ChatUtil.raw("  &7在报告状态的端：&f" + (clients.isEmpty() ? "（没有，B4 里开 GaleLink 脚本就会有）" : String.join(", ", clients)));
        for (String c : clients) ChatUtil.raw("    &f" + c + " 开着：&7" + String.join(", ", External.enabled(c)));
        ChatUtil.raw("  &7桥接：" + (Bridge.isListening() ? "&a127.0.0.1:" + Bridge.port() : "&cHTTP 没开"));
        ChatUtil.raw("  &7网络钩子：" + (gale.core.NetHook.isInstalled() ? "&a已接上" : "&7（进服务器后接上）"));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            List<String> all = new ArrayList<>(Arrays.asList(SUBS));
            for (Module m : Gale.modules().all()) all.add(m.getName());
            return getListOfStringsMatchingLastWord(args, all);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("killfx")) {
            if (args.length == 2) return getListOfStringsMatchingLastWord(args, "preview", "reset");
            if (args.length == 3 && args[1].equalsIgnoreCase("preview"))
                return getListOfStringsMatchingLastWord(args, "all", "1", "2", "3", "4", "5", "6");
        }
        if (args.length == 2 && Arrays.asList("toggle", "t", "bind", "set", "get", "reset", "hide").contains(sub)) {
            List<String> names = new ArrayList<>();
            for (Module m : Gale.modules().all()) names.add(m.getName());
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (args.length == 3 && sub.equals("set")) {
            Module m = Gale.modules().get(args[1]);
            if (m != null) {
                List<String> names = new ArrayList<>();
                for (Setting<?> s : m.getSettings()) names.add(s.getName().replace(" ", ""));
                return getListOfStringsMatchingLastWord(args, names);
            }
        }
        if (args.length == 2 && sub.equals("friend")) return getListOfStringsMatchingLastWord(args, "add", "remove", "list");
        if (args.length == 2 && sub.equals("config")) return getListOfStringsMatchingLastWord(args, "save", "load", "list");
        return null;
    }
}
