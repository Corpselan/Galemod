package gale.b4port.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gale.Gale;
import gale.b4port.module.setting.ButtonSetting;
import gale.b4port.module.setting.DescriptionSetting;
import gale.b4port.module.setting.Setting;
import gale.b4port.module.setting.SliderSetting;
import gale.b4port.util.RotationManager;
import gale.event.Priority;
import gale.event.impl.InputEvent;
import gale.event.impl.MotionEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.PostUpdateEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.UpdateEvent;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B4's module, as a Gale module: OpenB4's modules run on this unchanged (gale.b4port). Settings come from OpenB4's
 * modules.json (same names and defaults) as Gale settings. B4's hooks are Gale events, in B4's order:
 * <ul>
 * <li>onPreUpdate, then getRotations (a Gale silent rotation): {@link UpdateEvent}</li>
 * <li>onPreMotion: {@link MotionEvent}; onPostMotion: {@link PostUpdateEvent}</li>
 * <li>onPostPlayerInput: {@link InputEvent} (the player's movement input holds this tick's keys meanwhile)</li>
 * <li>onSendPacket / onReceivePacket: {@link PacketEvent} (network thread, as in B4); false cancels</li>
 * <li>onUpdate and guiUpdate: the end of every tick; Forge listeners ({@code @SubscribeEvent}): on Forge's bus while running</li>
 * </ul>
 * B4's onEnable / onDisable run when the module starts and stops running: switched on and not giving way to B4's own
 * module of the same name (Gale's "Defer"). Hooks named ...Always run whenever the module is not giving way.
 */
public class Module extends gale.module.Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();
    private static JsonArray specs;

    public final String name;
    public final Category category;
    public final ArrayList<Setting> settings = new ArrayList<>();
    public long enabledAt, disabledAt;
    /** B4: run afterDisable() on the tick after the module stops */
    public boolean queueAfterDisable;
    /** B4's onEnable has run and onDisable not yet */
    private boolean running, onForgeBus;

    public Module(String name, Category category, int keycode) {
        this(name, category, true);
    }

    /** complement: give way to B4's module of this name while it is on */
    protected Module(String name, Category category, boolean complement) {
        super(name, category.galeCategory, describe(name));
        this.name = name;
        this.category = category;
        loadSettings(specName());
        if (complement) complements(name);
        hook();
    }

    /** B4's own module of this name (ModuleManager.B4Side): no settings, no hooks, never on in Gale. */
    Module(String name) {
        super(name, Category.client.galeCategory, "");
        this.name = name;
        this.category = Category.client;
    }

    // ------------------------------------------------------------------ settings from modules.json

    private static synchronized JsonArray specs() {
        if (specs == null) {
            try (java.io.Reader r = new java.io.InputStreamReader(Module.class.getResourceAsStream("/assets/galemod/b4port/modules.json"),
                java.nio.charset.StandardCharsets.UTF_8)) {
                specs = new com.google.gson.JsonParser().parse(r).getAsJsonArray();
            } catch (Exception e) {
                Gale.LOG.error("Could not read the B4 module settings", e);
                specs = new JsonArray();
            }
        }
        return specs;
    }

    private static JsonObject spec(String name) {
        for (JsonElement e : specs()) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("name").getAsString().equals(name)) return o;
        }
        return null;
    }

    private static String describe(String name) {
        return Descriptions.of(name);
    }

    /** The module's entry in modules.json (B4's name). */
    protected String specName() { return getName(); }

    /** Settings of B4's this module has that Gale leaves out. */
    protected boolean skipSetting(String name) { return false; }

    private void loadSettings(String specName) {
        JsonObject spec = spec(specName);
        if (spec == null) return;
        String section = null;
        for (JsonElement e : spec.getAsJsonArray("settings")) {
            JsonObject o = e.getAsJsonObject();
            String t = o.get("t").getAsString(), n = o.get("name").getAsString();
            if (skipSetting(n)) continue;
            switch (t) {
                case "desc":
                    settings.add(new DescriptionSetting(n));
                    section = n;
                    break;
                case "toggle": {
                    BoolSetting b = bool(n, o.get("value").getAsBoolean());
                    if (section != null) b.describe(section);
                    ButtonSetting bs = new ButtonSetting(n, b);
                    b.onChange(v -> guiButtonToggled(bs));
                    settings.add(bs);
                    break;
                }
                case "slider":
                case "mode": {
                    HashMap<Double, String> labels = null;
                    if (o.has("labels") && o.get("labels").isJsonObject()) {
                        labels = new HashMap<>();
                        for (Map.Entry<String, JsonElement> l : o.getAsJsonObject("labels").entrySet())
                            labels.put(Double.parseDouble(l.getKey()), l.getValue().getAsString());
                    }
                    double value = o.get("value").getAsDouble();
                    if (o.has("options") && o.get("options").isJsonArray()) {
                        List<String> options = new ArrayList<>();
                        for (JsonElement x : o.getAsJsonArray("options")) options.add(x.getAsString());
                        int i = (int) Math.round(value);
                        ModeSetting m = mode(n, options.get(Math.max(0, Math.min(options.size() - 1, i))), options.toArray(new String[0]));
                        if (section != null) m.describe(section);
                        settings.add(new SliderSetting(n, m, labels));
                    } else {
                        NumberSetting ns = number(n, value, o.get("min").getAsDouble(), o.get("max").getAsDouble(), o.get("step").getAsDouble());
                        String suffix = o.has("suffix") && !o.get("suffix").isJsonNull() ? o.get("suffix").getAsString() : "";
                        if (!suffix.isEmpty()) ns.suffix(suffix);
                        if (section != null) ns.describe(section);
                        String prefix = o.has("prefix") && !o.get("prefix").isJsonNull() ? o.get("prefix").getAsString() : "";
                        settings.add(new SliderSetting(n, ns, prefix, suffix, labels));
                    }
                    break;
                }
                default:
                    // B4's method buttons ("Edit position"...) have nothing to do in Gale
            }
        }
    }

    public ArrayList<Setting> getB4Settings() {
        return this.settings;
    }

    // ------------------------------------------------------------------ B4's hooks on Gale's events

    private boolean overrides(String method, Class<?>... params) {
        try {
            return getClass().getMethod(method, params).getDeclaringClass() != Module.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Listens whenever this module is not giving way (B4's ...Always hooks), on or off, in a world. */
    private <E> void always(Class<E> type, int priority, java.util.function.Consumer<? super E> action) {
        Gale.BUS.listen(type, priority, e -> {
            if (mc.thePlayer != null && mc.theWorld != null && !isDeferred()) action.accept(e);
        }, getName());
    }

    /** Listens while B4's side runs (on, in a world, not giving way, and onEnable done). */
    private <E> void running(Class<E> type, int priority, java.util.function.Consumer<? super E> action) {
        on(type, priority, e -> {
            if (running) action.accept(e);
        });
    }

    private void hook() {
        String n = name;
        if (overrides("onPreUpdate")) running(UpdateEvent.class, Order.preUpdate(n), e -> onPreUpdate());
        if (overrides("getRotations", float.class, float.class)) {
            java.util.function.Consumer<UpdateEvent> rotate = e -> {
                float[] r = getRotations(RotationManager.lastYaw, RotationManager.lastPitch);
                if (r != null && r.length >= 2) {
                    gale.core.RotationManager.request(this, r[0], r[1], Order.rotation(n), RotationManager.fix());
                    RotationManager.source = this;
                }
            };
            // Scaffold and KillAura answer while off too (B4 asks them either way)
            if (Order.rotatesWhenOff(n)) always(UpdateEvent.class, Priority.LOW - 50, rotate);
            else running(UpdateEvent.class, Priority.LOW - 50, rotate);
        }
        if (overrides("onPreMotion", MotionEvent.class)) running(MotionEvent.class, Order.preMotion(n), this::onPreMotion);
        if (overrides("onPreMotionAlways", MotionEvent.class)) always(MotionEvent.class, Order.preMotion(n), this::onPreMotionAlways);
        if (overrides("onPostMotion")) running(PostUpdateEvent.class, Order.postMotion(n), e -> onPostMotion());
        if (overrides("onPostPlayerInput")) running(InputEvent.class, Order.postInput(n), e -> withInput(e, this::onPostPlayerInput));
        if (overrides("onPostPlayerInputAlways")) always(InputEvent.class, Order.postInput(n), e -> withInput(e, this::onPostPlayerInputAlways));
        if (overrides("onSendPacket", Packet.class)) running(PacketEvent.Send.class, Order.send(n), e -> {
            if (!e.isCancelled() && !onSendPacket(e.getPacket())) e.cancel();
        });
        if (overrides("onReceivePacket", Packet.class, INetHandler.class)) running(PacketEvent.Receive.class, Order.receive(n), e -> {
            if (!e.isCancelled() && !onReceivePacket(e.getPacket(), handler())) e.cancel();
        });
        if (overrides("onUpdate")) running(TickEvent.Post.class, Priority.NORMAL, e -> onUpdate());
        // B4 reads the settings into its fields every tick (while its GUI is open); here every tick, on or off
        Gale.BUS.listen(TickEvent.Post.class, Priority.NORMAL + 1, e -> guiUpdate(), getName());
    }

    private static INetHandler handler() {
        return mc.getNetHandler() == null ? null : mc.getNetHandler().getNetworkManager().getNetHandler();
    }

    /** B4's onPostPlayerInput reads and changes the player's movement input: this tick's keys are put there meanwhile. */
    private static void withInput(InputEvent e, Runnable r) {
        MovementInput mi = mc.thePlayer.movementInput;
        if (mi == null) {
            r.run();
            return;
        }
        mi.moveForward = e.forward;
        mi.moveStrafe = e.strafe;
        mi.jump = e.jump;
        mi.sneak = e.sneak;
        r.run();
        e.forward = mi.moveForward;
        e.strafe = mi.moveStrafe;
        e.jump = mi.jump;
        e.sneak = mi.sneak;
    }

    // ------------------------------------------------------------------ running (on and not giving way)

    /** Called by B4Port every tick: starts or stops B4's side as giving way to B4 begins or ends. */
    public final void syncRunning() {
        boolean want = isEnabled() && !isDeferred();
        // B4 switches its modules on in a world only (it loads them on the first tick there); off at any time
        if (want && !running && mc.thePlayer == null) return;
        if (want != running) setRunning(want);
        if (isDeferred()) deferredTick();
        else if (running != onForgeBus) setForge(running);
    }

    private void setRunning(boolean on) {
        running = on;
        setForge(on);
        try {
            if (on) {
                enabledAt = System.currentTimeMillis();
                b4Enable();
            } else {
                disabledAt = System.currentTimeMillis();
                b4Disable();
                if (queueAfterDisable) ModuleManager.afterDisableQueue.add(this);
            }
        } catch (Throwable t) {
            if (mc.thePlayer == null) Gale.LOG.debug("{} {} outside a world", name, on ? "onEnable" : "onDisable", t);
            else Gale.LOG.error("{} threw in {}", name, on ? "onEnable" : "onDisable", t);
        }
    }

    private void setForge(boolean on) {
        if (on == onForgeBus) return;
        onForgeBus = on;
        try {
            if (on) MinecraftForge.EVENT_BUS.register(this);
            else MinecraftForge.EVENT_BUS.unregister(this);
        } catch (Throwable t) {
            // a module without @SubscribeEvent methods: Forge does not mind either way
        }
    }

    public final boolean isRunning() { return running; }

    @Override
    protected final void onEnable() {
        if (!isDeferred() && mc.thePlayer != null) setRunning(true);
    }

    @Override
    protected final void onDisable() {
        if (running) setRunning(false);
    }

    /** B4's own switching: enable(), disable(), toggle(). */
    public void enable() {
        if (canBeEnabled()) setEnabled(true);
    }

    public void disable() {
        setEnabled(false);
    }

    @Override
    public String getSuffix() {
        String info = getInfo();
        return info == null ? null : gale.b4port.client.Chat.strip(info);
    }

    // ------------------------------------------------------------------ B4's module API (overridden by the modules)

    /** While giving way to B4's module: take on its state (targets...) from what GaleLink reports. */
    protected void deferredTick() { }

    public void link() { }

    public boolean canBeEnabled() { return true; }

    /** B4's onEnable, renamed (Gale's onEnable is when the switch flips). */
    public void b4Enable() { }

    /** B4's onDisable, renamed. */
    public void b4Disable() { }

    public void afterDisable() { }

    public void onUpdate() { }

    public void guiUpdate() { }

    public void guiButtonToggled(ButtonSetting b) { }

    public void onPreUpdate() { }

    public void onPreMotion(MotionEvent e) { }

    public void onPreMotionAlways(MotionEvent e) { }

    public void onPostMotion() { }

    public void onPostPlayerInput() { }

    public void onPostPlayerInputAlways() { }

    public float[] getRotations(float yaw, float pitch) { return null; }

    public Float getMovementYaw() { return null; }

    public boolean allowsSafeWalk() { return true; }

    public boolean stopsOnEdge() { return false; }

    public boolean isFakeBlocking() { return false; }

    public boolean overrideMouseOver() { return false; }

    public boolean onSendPacket(Packet<?> packet) { return true; }

    public boolean allowHeldItemPacket() { return true; }

    public boolean onReceivePacket(Packet packet, INetHandler handler) { return true; }

    public void onKnockback(S12PacketEntityVelocity packet, long sinceLast) { }

    public boolean isScaffolding() { return false; }

    public boolean isTowering() { return false; }

    public boolean isBlinking() { return false; }

    public gale.b4port.util.FakeRotation fakeRotation() { return gale.b4port.util.FakeRotation.None; }

    public long fakeRotationStart() { return 0L; }

    public float fakeRotationStartPitch() { return 0.0f; }

    public Entity getTarget() { return null; }

    public boolean consumeSlotResync() { return false; }

    public void onChat(ClientChatReceivedEvent e, String s) { }

    public int spoofedSlot() { return -1; }

    public void onPacketQueueTick() { }

    public void onPacketHeld(Packet<?> packet) { }
}
