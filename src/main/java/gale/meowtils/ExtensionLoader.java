package gale.meowtils;

import gale.Gale;
import gale.event.Priority;
import gale.event.impl.PacketEvent;
import gale.event.impl.TickEvent;
import gale.meowtils.api.event.AttackEntityEvent;
import gale.meowtils.api.event.ChatReceivedEvent;
import gale.meowtils.api.event.ClientTickEvent;
import gale.meowtils.api.event.EntityJoinWorldEvent;
import gale.meowtils.api.event.GuiOpenEvent;
import gale.meowtils.api.event.KeyInputEvent;
import gale.meowtils.api.event.MouseInputEvent;
import gale.meowtils.api.event.PlayerInteractEvent;
import gale.meowtils.api.event.ReceivePacketEvent;
import gale.meowtils.api.event.RenderGameOverlayEvent;
import gale.meowtils.api.event.RenderPlayerEvent;
import gale.meowtils.api.event.RenderTickEvent;
import gale.meowtils.api.event.RenderWorldLastEvent;
import gale.meowtils.api.event.SendPacketEvent;
import gale.meowtils.api.event.WorldEvent;
import gale.meowtils.api.event.api.Event;
import gale.meowtils.api.event.api.EventManager;
import gale.meowtils.api.gui.ModuleManager;
import gale.meowtils.api.handler.command.ClientCommand;
import gale.meowtils.api.handler.command.CommandHandler;
import gale.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Meowtils extensions (.meowtils files) the way Meowtils does: the file's META-INF/meowtils.extension names a
 * class whose static init() registers modules and listeners through Meowtils' API, which Gale provides
 * (gale.meowtils.api). Read from .minecraft/galemod/extensions, and from Meowtils' own folder
 * (.minecraft/meowtils/extensions) unless Meowtils itself is installed (then it loads them). Their modules show up in
 * Gale's Extensions category; their commands are chat commands; Forge's and Gale's events reach them as Meowtils'.
 */
public final class ExtensionLoader {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final List<Loaded> LOADED = new ArrayList<>();
    private static final List<ExtensionModule> MODULES = new ArrayList<>();
    private static final Map<String, Object> COMMANDS = new HashMap<>();
    private static Loaded current;
    private static Map<String, String> methods, fields;
    private static boolean installed, started;

    private ExtensionLoader() { }

    /** One extension file: what it made, or why it failed. */
    public static final class Loaded {
        public final File file;
        public final List<ExtensionModule> modules = new ArrayList<>();
        public String error;
        ExtensionClassLoader loader;

        Loaded(File file) { this.file = file; }

        /** The class loader its classes came from (null if it failed to load). */
        public ClassLoader loader() { return loader; }
    }

    public static File galeDir() { return new File(Gale.config().dir(), "extensions"); }

    public static File meowtilsDir() { return new File(mc.mcDataDir, "meowtils" + File.separator + "extensions"); }

    /** Meowtils itself is installed: it loads the extensions in its folder. */
    public static boolean meowtilsInstalled() { return ExtensionLoader.class.getClassLoader().getResource("wtf/tatp/meowtils/Meowtils.class") != null; }

    public static List<Loaded> loaded() { return Collections.unmodifiableList(LOADED); }

    /** At start, before the config is read (it then switches the modules as saved). */
    public static void loadAll() {
        File gale = galeDir();
        if (!gale.isDirectory()) gale.mkdirs();
        Map<String, File> files = new LinkedHashMap<>();
        if (!meowtilsInstalled()) for (File f : list(meowtilsDir())) files.put(f.getName().toLowerCase(), f);
        for (File f : list(gale)) files.put(f.getName().toLowerCase(), f);
        for (File f : files.values()) load(f);
        if (!LOADED.isEmpty()) install();
    }

    private static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) if (f.isFile() && f.getName().toLowerCase().endsWith(".meowtils")) out.add(f);
        Collections.sort(out);
        return out;
    }

    private static void load(File file) {
        Loaded l = new Loaded(file);
        LOADED.add(l);
        current = l;
        try {
            Map<String, byte[]> entries = read(Files.readAllBytes(file.toPath()));
            byte[] spec = entries.get("META-INF/meowtils.extension");
            if (spec == null) throw new IllegalStateException("没有 META-INF/meowtils.extension，不是 Meowtils 扩展");
            Properties p = new Properties();
            p.load(new ByteArrayInputStream(spec));
            String main = p.getProperty("main");
            if (main == null) throw new IllegalStateException("META-INF/meowtils.extension 里没有 main=");
            mappings();
            l.loader = new ExtensionClassLoader(file.getName(), entries, ExtensionLoader.class.getClassLoader(), methods, fields);
            Class<?> c = Class.forName(main.trim(), true, l.loader);
            c.getMethod("init").invoke(null);
            Gale.LOG.info("Loaded extension {}: {}", file.getName(), l.modules);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            l.error = cause.toString();
            Gale.LOG.error("Could not load extension {}", file.getName(), cause);
            undo(l);
        } finally {
            current = null;
        }
    }

    private static Map<String, byte[]> read(byte[] jar) throws java.io.IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry e;
            byte[] buf = new byte[8192];
            while ((e = in.getNextJarEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                entries.put(e.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    /**
     * Extensions are built for the game, where Minecraft's methods and fields have SRG names (func_..., field_...). The
     * development environment runs with MCP names: there they are renamed with MCP's tables; in the game nothing is.
     */
    private static void mappings() {
        if (methods != null) return;
        methods = new HashMap<>();
        fields = new HashMap<>();
        Object dev = Launch.blackboard == null ? null : Launch.blackboard.get("fml.deobfuscatedEnvironment");
        if (!Boolean.TRUE.equals(dev)) return;
        File jar = mcpJar();
        if (jar == null) {
            Gale.LOG.warn("Extensions: MCP's names were not found; extensions may not run in the development environment");
            return;
        }
        try (ZipFile z = new ZipFile(jar)) {
            csv(z, "methods.csv", methods);
            csv(z, "fields.csv", fields);
        } catch (Exception e) {
            Gale.LOG.warn("Extensions: could not read {}", jar, e);
        }
    }

    private static File mcpJar() {
        String prop = System.getProperty("gale.mcp");
        if (prop != null && new File(prop).isFile()) return new File(prop);
        File base = new File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/de.oceanlabs.mcp/mcp_stable/22-1.8.9");
        File[] hashes = base.listFiles();
        if (hashes != null) for (File h : hashes) {
            File j = new File(h, "mcp_stable-22-1.8.9.jar");
            if (j.isFile()) return j;
        }
        return null;
    }

    private static void csv(ZipFile z, String name, Map<String, String> out) throws java.io.IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) return;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            String[] lines = new String(b.toByteArray(), StandardCharsets.UTF_8).split("\r?\n");
            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].split(",", 3);
                if (cols.length >= 2) out.put(cols[0].trim(), cols[1].trim());
            }
        }
    }

    // ------------------------------------------------------------------ what extensions call (through gale.meowtils.api)

    /** Extension.registerModule: the module, as a Gale module. */
    public static void registerModule(gale.meowtils.api.gui.Module m) {
        if (ModuleManager.getModules().contains(m)) throw new IllegalStateException("Duplicate module name: " + m.getName());
        String name = m.getName();
        if (Gale.modules().get(name) != null) name = name + " (ext)";
        ExtensionModule gm = new ExtensionModule(m, name, current == null ? "?" : current.file.getName());
        Gale.modules().register(gm);
        ModuleManager.register(m);
        MODULES.add(gm);
        if (current != null) current.modules.add(gm);
        if (started) Gale.config().applySaved(gm);
    }

    /** CommandHandler.register: a chat command (once per name; it runs whichever extension's command has it now). */
    public static void registerCommand(ClientCommand command) {
        List<String> names = new ArrayList<>();
        names.add(command.getName());
        names.addAll(command.getAliases());
        for (String n : names) {
            String key = n.toLowerCase();
            if (COMMANDS.containsKey(key)) continue;
            Command c = new Command(key);
            COMMANDS.put(key, c);
            ClientCommandHandler.instance.registerCommand(c);
        }
    }

    private static final class Command extends CommandBase {
        private final String name;

        Command(String name) { this.name = name; }

        @Override
        public String getCommandName() { return name; }

        @Override
        public String getCommandUsage(ICommandSender sender) { return "/" + name; }

        @Override
        public int getRequiredPermissionLevel() { return 0; }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) throws CommandException {
            ClientCommand c = CommandHandler.getCommand(name);
            if (c == null) {
                sender.addChatMessage(new ChatComponentText("§c/" + name + " 的扩展没有加载"));
                return;
            }
            c.process(args);
        }
    }

    // ------------------------------------------------------------------ reload

    /** Unloads every extension (switching its modules off, keeping their settings) and loads the folders again. */
    public static void reload() {
        for (Loaded l : new ArrayList<>(LOADED)) undo(l);
        LOADED.clear();
        started = true;
        loadAll();
    }

    private static void undo(Loaded l) {
        for (ExtensionModule m : l.modules) {
            // kept as it is now (on stays on after a reload), then switched off
            Gale.config().keep(m);
            try {
                m.setEnabled(false);
            } catch (Throwable ignored) {
            }
            Gale.modules().unregister(m);
            ModuleManager.unregister(m.ext);
            MODULES.remove(m);
        }
        l.modules.clear();
        if (l.loader != null) {
            EventManager.unregisterAll(l.loader);
            CommandHandler.unregisterAll(l.loader);
            l.loader.close();
        }
    }

    /** After the config was read at start: modules extensions register later get their saved state. */
    public static void started() { started = true; }

    // ------------------------------------------------------------------ events, as Meowtils posts them

    private static void install() {
        if (installed) return;
        installed = true;
        MinecraftForge.EVENT_BUS.register(new ForgeEvents());
        Gale.BUS.listen(PacketEvent.Send.class, Priority.NORMAL, e -> {
            if (e.isCancelled() || !EventManager.wants(SendPacketEvent.class)) return;
            if (post(new SendPacketEvent(e.getPacket()))) e.cancel();
        }, "extensions");
        Gale.BUS.listen(PacketEvent.Receive.class, Priority.NORMAL, e -> {
            if (e.isCancelled() || !EventManager.wants(ReceivePacketEvent.class)) return;
            if (post(new ReceivePacketEvent(e.getPacket()))) e.cancel();
        }, "extensions");
        Gale.BUS.listen(TickEvent.Post.class, Priority.LOW, e -> {
            for (ExtensionModule m : MODULES) m.pull();
        }, "extensions");
    }

    /** Posts to the extensions; whether one cancelled it. */
    private static boolean post(Event e) {
        EventManager.post(e);
        return e.isCancelled();
    }

    public static final class ForgeEvents {
        @SubscribeEvent
        public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent e) {
            post(new ClientTickEvent(e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START ? ClientTickEvent.Phase.PRE : ClientTickEvent.Phase.POST));
        }

        @SubscribeEvent
        public void onRenderTick(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent e) {
            if (!EventManager.wants(RenderTickEvent.class)) return;
            post(new RenderTickEvent(e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START ? RenderTickEvent.Phase.PRE : RenderTickEvent.Phase.POST, e.renderTickTime));
        }

        @SubscribeEvent
        public void onRenderWorld(net.minecraftforge.client.event.RenderWorldLastEvent e) {
            if (EventManager.wants(RenderWorldLastEvent.class)) post(new RenderWorldLastEvent(e.partialTicks));
        }

        @SubscribeEvent
        public void onOverlay(net.minecraftforge.client.event.RenderGameOverlayEvent.Post e) {
            if (e.type == net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL && EventManager.wants(RenderGameOverlayEvent.class))
                post(new RenderGameOverlayEvent());
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onAttack(net.minecraftforge.event.entity.player.AttackEntityEvent e) {
            if (e.entityPlayer != mc.thePlayer || !EventManager.wants(AttackEntityEvent.class)) return;
            if (post(new AttackEntityEvent(e.entityPlayer, e.target))) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent e) {
            if (!EventManager.wants(ChatReceivedEvent.class)) return;
            if (post(new ChatReceivedEvent(e.message, e.type))) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onKey(InputEvent.KeyInputEvent e) {
            if (!EventManager.wants(KeyInputEvent.class)) return;
            KeyBinding pressed = null;
            int key = Keyboard.getEventKey();
            if (Keyboard.getEventKeyState()) for (KeyBinding k : mc.gameSettings.keyBindings) if (k.getKeyCode() == key) pressed = k;
            post(new KeyInputEvent(pressed));
        }

        @SubscribeEvent
        public void onMouse(InputEvent.MouseInputEvent e) {
            if (!Mouse.getEventButtonState() || !EventManager.wants(MouseInputEvent.class)) return;
            int b = Mouse.getEventButton();
            MouseInputEvent.Action a = b == 0 ? MouseInputEvent.Action.LEFT_CLICK : b == 1 ? MouseInputEvent.Action.RIGHT_CLICK : b == 2 ? MouseInputEvent.Action.MIDDLE_CLICK : null;
            if (a != null) post(new MouseInputEvent(a));
        }

        @SubscribeEvent
        public void onJoin(net.minecraftforge.event.entity.EntityJoinWorldEvent e) {
            if (e.world.isRemote && EventManager.wants(EntityJoinWorldEvent.class) && post(new EntityJoinWorldEvent(e.entity, e.world))) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onGui(net.minecraftforge.client.event.GuiOpenEvent e) {
            if (EventManager.wants(GuiOpenEvent.class) && post(new GuiOpenEvent(e.gui))) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent e) {
            if (!e.world.isRemote || e.entityPlayer != mc.thePlayer || e.pos == null || !EventManager.wants(PlayerInteractEvent.class)) return;
            PlayerInteractEvent.Action a;
            if (e.action == net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) a = PlayerInteractEvent.Action.LEFT_CLICK_BLOCK;
            else if (e.action == net.minecraftforge.event.entity.player.PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) a = PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK;
            else return;
            if (post(new PlayerInteractEvent(mc.thePlayer, mc.theWorld, a, e.pos, e.face))) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onRenderPlayerPre(net.minecraftforge.client.event.RenderPlayerEvent.Pre e) { renderPlayer(e, RenderPlayerEvent.Stage.PRE); }

        @SubscribeEvent
        public void onRenderPlayerPost(net.minecraftforge.client.event.RenderPlayerEvent.Post e) { renderPlayer(e, RenderPlayerEvent.Stage.POST); }

        private void renderPlayer(net.minecraftforge.client.event.RenderPlayerEvent e, RenderPlayerEvent.Stage stage) {
            if (!(e.entityPlayer instanceof AbstractClientPlayer) || !EventManager.wants(RenderPlayerEvent.class)) return;
            float pt = e.partialRenderTick;
            float yaw = e.entityPlayer.prevRotationYaw + (e.entityPlayer.rotationYaw - e.entityPlayer.prevRotationYaw) * pt;
            double x = e instanceof net.minecraftforge.client.event.RenderPlayerEvent.Pre ? ((net.minecraftforge.client.event.RenderPlayerEvent.Pre) e).x
                : ((net.minecraftforge.client.event.RenderPlayerEvent.Post) e).x;
            double y = e instanceof net.minecraftforge.client.event.RenderPlayerEvent.Pre ? ((net.minecraftforge.client.event.RenderPlayerEvent.Pre) e).y
                : ((net.minecraftforge.client.event.RenderPlayerEvent.Post) e).y;
            double z = e instanceof net.minecraftforge.client.event.RenderPlayerEvent.Pre ? ((net.minecraftforge.client.event.RenderPlayerEvent.Pre) e).z
                : ((net.minecraftforge.client.event.RenderPlayerEvent.Post) e).z;
            boolean cancel = post(new RenderPlayerEvent(e.renderer, (AbstractClientPlayer) e.entityPlayer, x, y, z, yaw, pt, stage));
            if (cancel && e.isCancelable()) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onWorldLoad(net.minecraftforge.event.world.WorldEvent.Load e) {
            if (e.world instanceof WorldClient && EventManager.wants(WorldEvent.class)) post(new WorldEvent((WorldClient) e.world, WorldEvent.Type.LOAD));
        }

        @SubscribeEvent
        public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload e) {
            if (e.world instanceof WorldClient && EventManager.wants(WorldEvent.class)) post(new WorldEvent((WorldClient) e.world, WorldEvent.Type.UNLOAD));
        }
    }

    /** For /gale extensions: each file and what it made. */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Loaded l : LOADED) {
            if (l.error != null) out.add("&c" + l.file.getName() + "&7: " + l.error);
            else {
                List<String> names = new ArrayList<>();
                for (Module m : l.modules) names.add((m.isEnabled() ? "&a" : "&7") + m.getName());
                out.add("&b" + l.file.getName() + "&7: " + String.join("&7, ", names));
            }
        }
        return out;
    }
}
