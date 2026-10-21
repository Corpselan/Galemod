package gale.script.api;

import gale.script.Functions;
import gale.script.PacketData;
import gale.script.ScriptEvents;
import gale.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Screens a script defines (buttons and text fields; the script draws the rest itself), and chat: messages with
 * links, hover text and click callbacks, lines that can be replaced, the chat box filled in.
 */
public final class ScreenApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "screens";
    private static final Map<String, ScriptScreen> SCREENS = new ConcurrentHashMap<>();
    /** chat callbacks: token -> what the script attached */
    private static final Map<Integer, String> CALLBACKS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private ScreenApi() { }

    public static void install() {
        ScriptEvents.define(AREA, "screenDraw", "{String 界面, Integer 鼠标x, Integer 鼠标y, Double partialTicks}",
            "脚本的界面在画，每帧一次，游戏线程：在背景和标题之后、按钮和输入框之前。用 B4 的 render.* 画自己的东西（坐标是界面坐标，screen.size 给宽高）。");
        ScriptEvents.define(AREA, "screenClick", "{String 界面, Integer x, Integer y, Integer 鼠标键}", "在脚本的界面里按下鼠标。返回非 0 = 按钮和输入框不处理这次点击。");
        ScriptEvents.define(AREA, "screenButton", "{String 界面, String 按钮}", "脚本的界面里点了一个按钮。");
        ScriptEvents.define(AREA, "screenKey", "{String 界面, String 字符, Integer 键码}", "在脚本的界面里按键。返回非 0 = 输入框不处理（Esc 也不关界面）。");
        ScriptEvents.define(AREA, "screenClose", "{String 界面}", "脚本的界面关了（Esc、screen.close、或者开了别的界面）。");
        ScriptEvents.define(AREA, "chatClick", "{String 值}", "点了 chat.link 用 callback 方式做的聊天链接，args[0] 是当时给的值。");

        Functions.add(AREA, "screen.open", "<界面> [标题] [暂停游戏=false]", "打开（没有就先建）一个脚本界面。游戏线程。", a -> {
            CombatApi.mainThread("screen.open");
            ScriptScreen s = screen(a.str(0), true);
            if (a.has(1)) s.title = a.str(1);
            s.pause = a.bool(2, false);
            mc.displayGuiScreen(s);
            return true;
        });
        Functions.add(AREA, "screen.button", "<界面> <名字> <x> <y> <宽> <高> <文字>", "在界面上放一个按钮（同名就改它）。点了触发 screenButton。", a -> {
            ScriptScreen s = screen(a.str(0), true);
            GuiButton old = s.buttons.get(a.str(1));
            GuiButton b = new GuiButton(old == null ? s.nextId++ : old.id, a.integer(2), a.integer(3), a.integer(4), a.integer(5), ChatUtil.color(a.str(6)));
            if (old != null) b.enabled = old.enabled;
            s.buttons.put(a.str(1), b);
            s.relayout();
            return true;
        });
        Functions.add(AREA, "screen.field", "<界面> <名字> <x> <y> <宽> <高> [文字] [最多几个字=256]", "在界面上放一个输入框（同名就改位置）。", a -> {
            ScriptScreen s = screen(a.str(0), true);
            GuiTextField f = s.fields.get(a.str(1));
            String text = f == null ? "" : f.getText();
            f = new GuiTextField(s.nextId++, mc.fontRendererObj, a.integer(2), a.integer(3), a.integer(4), a.integer(5));
            f.setMaxStringLength(a.integer(7, 256));
            f.setText(a.has(6) ? a.str(6) : text);
            s.fields.put(a.str(1), f);
            return true;
        });
        Functions.add(AREA, "screen.text", "<界面> <名字> [新文字]", "读（或改）输入框的文字、按钮的文字。", a -> {
            ScriptScreen s = screen(a.str(0), false);
            GuiTextField f = s.fields.get(a.str(1));
            GuiButton b = s.buttons.get(a.str(1));
            if (f == null && b == null) throw new IllegalArgumentException(a.str(0) + " 里没有 " + a.str(1));
            if (a.has(2)) {
                if (f != null) f.setText(a.str(2));
                else b.displayString = ChatUtil.color(a.str(2));
            }
            return f != null ? f.getText() : b.displayString;
        });
        Functions.add(AREA, "screen.enable", "<界面> <名字> <true|false>", "按钮或输入框能不能用。", a -> {
            ScriptScreen s = screen(a.str(0), false);
            boolean on = a.bool(2);
            if (s.buttons.containsKey(a.str(1))) s.buttons.get(a.str(1)).enabled = on;
            if (s.fields.containsKey(a.str(1))) s.fields.get(a.str(1)).setEnabled(on);
            return true;
        });
        Functions.add(AREA, "screen.remove", "<界面> [名字]", "去掉一个按钮或输入框；不给名字就把整个界面的定义删掉。", a -> {
            if (!a.has(1)) return SCREENS.remove(a.str(0)) != null;
            ScriptScreen s = screen(a.str(0), false);
            boolean removed = s.buttons.remove(a.str(1)) != null | s.fields.remove(a.str(1)) != null;
            s.relayout();
            return removed;
        });
        Functions.add(AREA, "screen.background", "<界面> <true|false>", "要不要画原版的暗背景（默认要）。", a -> {
            screen(a.str(0), true).background = a.bool(1);
            return true;
        });
        Functions.add(AREA, "screen.close", "[界面]", "关掉现在开着的脚本界面（给了名字就只在开着的是它时关）。", a -> {
            CombatApi.mainThread("screen.close");
            if (!(mc.currentScreen instanceof ScriptScreen)) return false;
            if (a.has(0) && !((ScriptScreen) mc.currentScreen).id.equals(a.str(0))) return false;
            mc.displayGuiScreen(null);
            return true;
        });
        Functions.add(AREA, "screen.current", "", "现在开着的脚本界面的名字，没开是 null。", a -> mc.currentScreen instanceof ScriptScreen ? ((ScriptScreen) mc.currentScreen).id : null);
        Functions.add(AREA, "screen.size", "", "界面坐标下屏幕的宽高 int[]{宽, 高}。", a -> {
            ScaledResolution r = new ScaledResolution(mc);
            return new int[]{ r.getScaledWidth(), r.getScaledHeight() };
        });

        Functions.add(AREA, "chat.print", "<文字或 JSON> [行号]",
            "在聊天栏显示一条只有你看得到的消息：普通文字（& 颜色码）或原版的 JSON 文字组件（可以带点击、悬停）。"
                + "给了行号：再用同一个行号显示会替换那一行（做会变的状态行）。游戏线程。", a -> {
                CombatApi.mainThread("chat.print");
                print(component(a.str(0)), a.integer(1, 0));
                return true;
            });
        Functions.add(AREA, "chat.link", "<文字> <command|url|suggest|callback> <值> [悬停文字] [行号]",
            "显示一条能点的消息。command：点了执行这条命令（\"/...\"，Gale 和别的客户端命令也行）；url：打开网页；"
                + "suggest：把值填进聊天框；callback：点了触发 chatClick 事件，args[0] 就是这个值。游戏线程。", a -> {
                CombatApi.mainThread("chat.link");
                IChatComponent c = component(a.str(0));
                String action = a.str(1).trim().toLowerCase(), value = a.str(2);
                ClickEvent click;
                switch (action) {
                    case "command": click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, value); break;
                    case "url": click = new ClickEvent(ClickEvent.Action.OPEN_URL, value); break;
                    case "suggest": click = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, value); break;
                    case "callback": {
                        int token = NEXT.getAndIncrement();
                        CALLBACKS.put(token, value);
                        click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gale chatcb " + token);
                        break;
                    }
                    default: throw new IllegalArgumentException("方式只有 command、url、suggest、callback");
                }
                c.getChatStyle().setChatClickEvent(click);
                if (a.has(3)) c.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(ChatUtil.color(a.str(3)))));
                print(c, a.integer(4, 0));
                return true;
            });
        Functions.add(AREA, "chat.delete", "<行号>", "删掉用这个行号显示的那一行。游戏线程。", a -> {
            CombatApi.mainThread("chat.delete");
            mc.ingameGUI.getChatGUI().deleteChatLine(a.integer(0));
            return true;
        });
        Functions.add(AREA, "chat.open", "[文字]", "打开聊天框，里面先填好文字（不发送）。游戏线程。", a -> {
            CombatApi.mainThread("chat.open");
            mc.displayGuiScreen(new GuiChat(a.str(0, "")));
            return true;
        });
        Functions.add(AREA, "chat.sent", "", "你发过的消息和命令（聊天框里按上键翻到的那些，List<String>，旧的在前）。",
            a -> new ArrayList<>(mc.ingameGUI.getChatGUI().getSentMessages()));
    }

    /** /gale chatcb: a chat callback was clicked. */
    public static void chatCallback(int token) {
        String value = CALLBACKS.get(token);
        if (value != null) ScriptEvents.fire("chatClick", new Object[]{ value });
    }

    private static IChatComponent component(String text) {
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) return (IChatComponent) PacketData.fromScript(text, IChatComponent.class, "文字");
        return new ChatComponentText(ChatUtil.color(text));
    }

    private static void print(IChatComponent c, int line) {
        if (mc.ingameGUI == null) return;
        if (line != 0) mc.ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(c, line);
        else mc.ingameGUI.getChatGUI().printChatMessage(c);
    }

    private static ScriptScreen screen(String id, boolean create) {
        ScriptScreen s = SCREENS.get(id);
        if (s == null) {
            if (!create) throw new IllegalArgumentException("没有界面 " + id + "（先 screen.open 或 screen.button）");
            s = new ScriptScreen(id);
            SCREENS.put(id, s);
        }
        return s;
    }

    /** A screen made by a script: its buttons and text fields; the script draws the rest in screenDraw. */
    public static final class ScriptScreen extends GuiScreen {
        final String id;
        String title = "";
        boolean pause, background = true;
        int nextId;
        final Map<String, GuiButton> buttons = new LinkedHashMap<>();
        final Map<String, GuiTextField> fields = new LinkedHashMap<>();

        ScriptScreen(String id) { this.id = id; }

        void relayout() {
            buttonList.clear();
            buttonList.addAll(buttons.values());
        }

        @Override
        public void initGui() {
            Keyboard.enableRepeatEvents(true);
            relayout();
        }

        @Override
        public void updateScreen() {
            for (GuiTextField f : fields.values()) f.updateCursorCounter();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            if (background) drawDefaultBackground();
            if (!title.isEmpty()) drawCenteredString(fontRendererObj, ChatUtil.color(title), width / 2, 12, 0xFFFFFF);
            ScriptEvents.fire("screenDraw", new Object[]{ id, mouseX, mouseY, (double) partialTicks });
            for (GuiTextField f : new ArrayList<>(fields.values())) f.drawTextBox();
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) throws java.io.IOException {
            if (ScriptEvents.fire("screenClick", new Object[]{ id, mouseX, mouseY, button })) return;
            for (GuiTextField f : new ArrayList<>(fields.values())) f.mouseClicked(mouseX, mouseY, button);
            super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            for (Map.Entry<String, GuiButton> e : buttons.entrySet())
                if (e.getValue() == button) ScriptEvents.fire("screenButton", new Object[]{ id, e.getKey() });
        }

        @Override
        protected void keyTyped(char c, int key) throws java.io.IOException {
            if (ScriptEvents.fire("screenKey", new Object[]{ id, String.valueOf(c), key })) return;
            for (GuiTextField f : new ArrayList<>(fields.values())) {
                if (f.isFocused()) {
                    f.textboxKeyTyped(c, key);
                    if (key != Keyboard.KEY_ESCAPE) return;
                }
            }
            super.keyTyped(c, key);
        }

        @Override
        public void onGuiClosed() {
            Keyboard.enableRepeatEvents(false);
            ScriptEvents.fire("screenClose", new Object[]{ id });
        }

        @Override
        public boolean doesGuiPauseGame() { return pause; }

        /** For the dev test. */
        public String id() { return id; }

        public List<String> widgets() {
            List<String> out = new ArrayList<>(buttons.keySet());
            out.addAll(fields.keySet());
            return out;
        }

        public void pressButton(String name) {
            GuiButton b = buttons.get(name);
            if (b != null) actionPerformed(b);
        }
    }
}
