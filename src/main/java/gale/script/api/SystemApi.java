package gale.script.api;

import gale.Gale;
import gale.script.Args;
import gale.script.Functions;
import gale.script.PacketData;
import gale.script.ScriptEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The computer: files (relative paths are in .minecraft/galemod/script-data; absolute paths work too), the
 * clipboard, opening web pages, and TCP / WebSocket connections. Never: running programs, reflection, or the
 * account's login token; launcher account files cannot be read or written either.
 */
public final class SystemApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "system";
    /** launcher files that hold accounts or login tokens */
    private static final List<String> SECRET = Arrays.asList("launcher_accounts", "launcher_profiles", "msa_credentials", "latestlaunch.bat",
        "accounts.json", "account.json", "hmcl.json", "credentials", "session.json", "token");

    private SystemApi() { }

    public static void install() {
        String files = AREA + ".files", clip = AREA + ".clipboard", web = AREA + ".browser", net = AREA + ".network";

        Functions.add(files, "file.dir", "", "脚本数据文件夹的完整路径（.minecraft/galemod/script-data，没有会建好）。相对路径都从这里算。", a -> base().getAbsolutePath());
        Functions.add(files, "file.read", "<路径>", "读文本文件（UTF-8），没有这个文件是 null。", a -> {
            File f = file(a, 0);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        });
        Functions.add(files, "file.readBytes", "<路径>", "读文件的字节（byte[]），没有是 null。", a -> {
            File f = file(a, 0);
            return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
        });
        Functions.add(files, "file.write", "<路径> <文字>", "写文本文件（UTF-8，覆盖），文件夹没有会建好。", a -> {
            File f = file(a, 0);
            mkParent(f);
            Files.write(f.toPath(), a.str(1).getBytes(StandardCharsets.UTF_8));
            return true;
        });
        Functions.add(files, "file.append", "<路径> <文字>", "在文本文件后面接着写（没有就新建）。", a -> {
            File f = file(a, 0);
            mkParent(f);
            Files.write(f.toPath(), a.str(1).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        });
        Functions.add(files, "file.writeBytes", "<路径> <byte[] 或十六进制>", "写字节（覆盖）。", a -> {
            File f = file(a, 0);
            mkParent(f);
            Files.write(f.toPath(), a.raw(1) instanceof byte[] ? (byte[]) a.raw(1) : PacketData.hex(a.str(1)));
            return true;
        });
        Functions.add(files, "file.exists", "<路径>", "文件或文件夹在不在。", a -> file(a, 0).exists());
        Functions.add(files, "file.list", "[文件夹]", "文件夹里的东西（List<String>，文件夹名后面带 /），不给就是脚本数据文件夹。", a -> {
            File d = a.has(0) ? file(a, 0) : base();
            List<String> out = new ArrayList<>();
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) out.add(f.getName() + (f.isDirectory() ? "/" : ""));
            out.sort(String::compareToIgnoreCase);
            return out;
        });
        Functions.add(files, "file.delete", "<路径>", "删一个文件或空文件夹。返回删掉没有。", a -> file(a, 0).delete());
        Functions.add(files, "file.mkdirs", "<文件夹>", "建文件夹（连同上面没有的）。", a -> {
            File d = file(a, 0);
            return d.isDirectory() || d.mkdirs();
        });

        Functions.add(clip, "clipboard.get", "", "剪贴板里的文字。", a -> GuiScreen.getClipboardString());
        Functions.add(clip, "clipboard.set", "<文字>", "把文字放进剪贴板。", a -> {
            GuiScreen.setClipboardString(a.str(0));
            return true;
        });

        Functions.add(web, "browse", "<网址>", "用系统浏览器打开网页（只能 http:// 或 https://）。", a -> {
            URI uri = URI.create(a.str(0).trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("只能打开 http:// 或 https:// 的网址");
            Thread t = new Thread(() -> {
                try {
                    java.awt.Desktop.getDesktop().browse(uri);
                } catch (Exception e) {
                    Gale.LOG.warn("Could not open {}", uri, e);
                }
            }, "Gale browse");
            t.setDaemon(true);
            t.start();
            return true;
        });

        ScriptEvents.define(net, "socket", "{String 连接名, String 类型, String 内容}",
            "脚本的网络连接有事了，游戏线程。类型：\"open\" 连上了、\"line\" TCP 收到一行、\"message\" WebSocket 收到一条消息"
                + "（二进制消息是 Base64）、\"error\" 出错（内容是原因）、\"close\" 断了。");
        Functions.add(net, "socket.open", "<连接名> <主机> <端口> [tls=false]",
            "在后台连一个 TCP 服务器，收到的每一行都触发 socket 事件。同名的旧连接会先关掉。", a -> {
                ScriptNet.tcp(a.str(0), a.str(1), a.integer(2), a.bool(3, false));
                return true;
            });
        Functions.add(net, "socket.send", "<连接名> <文字> [加换行=true]", "往 TCP 连接发文字（UTF-8）。", a -> {
            ScriptNet.send(a.str(0), a.str(1) + (a.bool(2, true) ? "\n" : ""));
            return true;
        });
        Functions.add(net, "ws.open", "<连接名> <ws:// 或 wss:// 地址>", "在后台连一个 WebSocket，每条消息都触发 socket 事件。", a -> {
            ScriptNet.websocket(a.str(0), a.str(1));
            return true;
        });
        Functions.add(net, "ws.send", "<连接名> <文字>", "往 WebSocket 发一条文字消息。", a -> {
            ScriptNet.send(a.str(0), a.str(1));
            return true;
        });
        Functions.add(net, "net.close", "<连接名>", "关掉一个连接（TCP 或 WebSocket）。", a -> ScriptNet.close(a.str(0)));
        Functions.add(net, "net.closeAll", "", "关掉所有脚本连接。返回关了几个。", a -> ScriptNet.closeAll());
        Functions.add(net, "net.list", "", "开着的连接名（List<String>）。", a -> ScriptNet.names());
    }

    static File base() {
        File d = new File(new File(mc.mcDataDir, "galemod"), "script-data");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File file(Args a, int i) throws IOException {
        String p = a.str(i).trim();
        if (p.isEmpty()) throw new IllegalArgumentException("路径是空的");
        File f = new File(p);
        if (!f.isAbsolute()) f = new File(base(), p);
        f = f.getCanonicalFile();
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String s : SECRET) if (name.contains(s)) throw new SecurityException("不能读写启动器的账号文件（" + f.getName() + "）");
        return f;
    }

    private static void mkParent(File f) {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
    }
}
