package gale.b4script;

import gale.Gale;
import gale.core.RotationManager;
import gale.core.ServerState;
import gale.event.Listener;
import gale.event.Priority;
import gale.event.impl.AntiCheatFlagEvent;
import gale.event.impl.BedWarsAlertEvent;
import gale.event.impl.InputEvent;
import gale.event.impl.IrcMessageEvent;
import gale.event.impl.LocrawEvent;
import gale.event.impl.MotionEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.PostUpdateEvent;
import gale.event.impl.UpdateEvent;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent.KeyInputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Feeds the scripts B4's events, at the moments B4 fires them. While a script is on this object is on Forge's bus
 * (chat, keys, mouse, screens, world join, render tick, world render: B4's own handlers, copied) and on Gale's bus:
 * <ul>
 * <li>{@link UpdateEvent}: onPreUpdate, then getRotations / getMovementYaw (a rotation becomes Gale's silent
 * rotation for the tick; a movement yaw turns the keys so you move along it)</li>
 * <li>{@link MotionEvent} (after you moved, right before the movement packet): onPreMotion with the values the
 * packet will carry; changed position, rotation or ground go into that packet, sprint and sneak into the player for
 * this tick</li>
 * <li>{@link PostUpdateEvent}: onPostMotion</li>
 * <li>{@link InputEvent}: onPostPlayerInput (client.getForward / setForward work on this tick's keys)</li>
 * <li>{@link PacketEvent}: onPacketSent / onPacketReceived (network thread, as in B4)</li>
 * </ul>
 * onBedWarsAlert, onAntiCheatFlag and onLocRaw come from Gale's Bed Wars, AntiCheat and Locraw modules, or from B4's
 * own (GaleLink passes them on); onIRCMessage only from B4's IRC, through GaleLink.
 */
public class ScriptEvents {
    private static final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
    static Runnable preUpdate;
    static Function<Object[], Object[]> preMotion;
    static Predicate<Packet> packetSent;
    static Predicate<Packet> packetReceived;
    static Consumer<Entity> worldJoin;
    static BiConsumer<String, EntityPlayer> antiCheatFlag;
    static BiConsumer<String, EntityPlayer> bedWarsAlert;
    static GuiScreen openedScreen;

    /** the movement yaw the scripts gave this tick, or null */
    private static volatile Float tickMovementYaw;

    private static final List<Listener<?>> GALE = new ArrayList<>();

    public static boolean bind(Class<?> clazz) {
        preUpdate = ScriptInvoker.runnable(clazz, "m7a3f19c8e42bd6");
        preMotion = ScriptInvoker.function(clazz, "mc5810fa29d73e4", Object[].class, Object[].class);
        packetSent = ScriptInvoker.predicate(clazz, "mf2946c1e83ab75", Packet.class);
        packetReceived = ScriptInvoker.predicate(clazz, "m63b8ed2f901ca4", Packet.class);
        worldJoin = ScriptInvoker.consumer(clazz, "m18de7b40ac926f", Entity.class);
        antiCheatFlag = ScriptInvoker.biConsumer(clazz, "ma047d95c3e68bf", String.class, EntityPlayer.class);
        bedWarsAlert = ScriptInvoker.biConsumer(clazz, "m9e2c74a1fd5038", String.class, EntityPlayer.class);
        return preUpdate != null && preMotion != null && packetSent != null && packetReceived != null && worldJoin != null && antiCheatFlag != null && bedWarsAlert != null;
    }

    // ------------------------------------------------------------------ Gale's bus (on while a script is on)

    static void attachGale() {
        if (GALE.isEmpty()) {
            GALE.add(Gale.BUS.listener(UpdateEvent.class, Priority.LOW, e -> update(), "b4scripts"));
            // right after the tick's movement packet (MotionHook has put sprint / sneak back): onPostMotion
            GALE.add(Gale.BUS.listener(PostUpdateEvent.class, Priority.HIGHEST + 100, e -> postMotion(), "b4scripts"));
            GALE.add(Gale.BUS.listener(InputEvent.class, Priority.LOWEST, ScriptEvents::input, "b4scripts"));
            // after Gale's own B4 modules, as in B4 (only Sprint comes later there)
            GALE.add(Gale.BUS.listener(MotionEvent.class, Priority.LOW, ScriptEvents::motion, "b4scripts"));
            GALE.add(Gale.BUS.listener(PacketEvent.Send.class, Priority.LOW, e -> {
                if (!e.isCancelled() && !packetSent(e.getPacket())) e.cancel();
            }, "b4scripts"));
            GALE.add(Gale.BUS.listener(PacketEvent.Receive.class, Priority.HIGH, e -> {
                if (!e.isCancelled() && !packetReceived(e.getPacket())) e.cancel();
            }, "b4scripts"));
            GALE.add(Gale.BUS.listener(BedWarsAlertEvent.class, Priority.NORMAL, e -> asCamera(() -> bedWarsAlert(e.type, e.player)), "b4scripts"));
            GALE.add(Gale.BUS.listener(AntiCheatFlagEvent.class, Priority.NORMAL, e -> asCamera(() -> antiCheatFlag(e.check, e.player)), "b4scripts"));
            GALE.add(Gale.BUS.listener(LocrawEvent.class, Priority.NORMAL, e -> locraw(e.json), "b4scripts"));
            GALE.add(Gale.BUS.listener(IrcMessageEvent.class, Priority.NORMAL, e -> ircMessage(e.message), "b4scripts"));
        }
        for (Listener<?> l : GALE) Gale.BUS.register(l);
    }

    static void detachGale() {
        for (Listener<?> l : GALE) Gale.BUS.unregister(l);
        tickMovementYaw = null;
    }

    /** The movement yaw the scripts gave this tick (B4's RotationManager.movementYaw), or null. */
    static Float movementYawNow() { return tickMovementYaw; }

    private static void update() {
        preUpdate();
        // B4 asks for rotations with the rotation the server has
        float sy = ServerState.lastMovementPacket() == 0 ? mc.thePlayer.rotationYaw : ServerState.yaw();
        float sp = ServerState.lastMovementPacket() == 0 ? mc.thePlayer.rotationPitch : ServerState.pitch();
        float[] r = rotations(sy, sp);
        Float mv = movementYaw(r != null ? r[0] : mc.thePlayer.rotationYaw);
        tickMovementYaw = mv;
        // with a movement yaw the scripts steer themselves (keys turned in input()); else you go where the camera looks
        if (r != null && r.length >= 2) RotationManager.request("b4scripts", r[0], r[1], 40, mv != null ? RotationManager.MoveFix.NONE : null);
    }

    private static void input(InputEvent e) {
        if (mc.thePlayer == null) return;
        MovementInput mi = mc.thePlayer.movementInput;
        if (mi != null) {
            mi.moveForward = e.forward;
            mi.moveStrafe = e.strafe;
            mi.jump = e.jump;
            mi.sneak = e.sneak;
            asCamera(ScriptEvents::postPlayerInput);
            e.forward = mi.moveForward;
            e.strafe = mi.moveStrafe;
            e.jump = mi.jump;
            e.sneak = mi.sneak;
        }
        Float m = tickMovementYaw;
        if (m != null && (e.forward != 0 || e.strafe != 0)) {
            // keys meant along the script's yaw, turned to the yaw the game moves you along
            double d = Math.toRadians(MathHelper.wrapAngleTo180_float(m - mc.thePlayer.rotationYaw));
            float f = (float) (e.forward * Math.cos(d) + e.strafe * Math.sin(d));
            float s = (float) (e.strafe * Math.cos(d) - e.forward * Math.sin(d));
            e.forward = f;
            e.strafe = s;
        }
    }

    // B4's motion event (Gale's MotionEvent: the packet's values; changes go into the packet)
    private static void motion(MotionEvent e) {
        Object[][] result = new Object[1][];
        try {
            asCamera(() -> result[0] = preMotion.apply(e.toArray()));
        } catch (Throwable t) {
            return;
        }
        Object[] out = result[0];
        if (out != null && out.length >= 8) e.set(out);
    }

    // ------------------------------------------------------------------ B4's handlers (Forge's bus)

    @SubscribeEvent
    public void onMouse(MouseEvent mouseEvent) {
        if (mouseEvent.button == -1 && mouseEvent.dwheel == 0) return;
        boolean bl = mouseEvent.buttonstate;
        if (mouseEvent.dwheel > 0) bl = true;
        if (ScriptInvoker.dispatchBoolean(ScriptEvent.MOUSE, mouseEvent.button, bl, mouseEvent.x, mouseEvent.y) == 0) mouseEvent.setCanceled(true);
    }

    public static void antiCheatFlag(String string, EntityPlayer entityPlayer) { antiCheatFlag.accept(string, entityPlayer); }

    public static void postMotion() {
        asCamera(() -> ScriptInvoker.dispatch(ScriptEvent.POST_MOTION));
    }

    /**
     * Runs script code the way B4 shows it the player: with your camera's rotation. Between Gale's silent rotation
     * going on and the end of the tick the player holds the server rotation (that is how Gale rotates without mixins),
     * but B4 keeps the two apart, and scripts take player.getYaw() for where you look (a script's own move fix turns
     * your keys toward it). A turn the script makes here is kept.
     */
    static void asCamera(Runnable r) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || !RotationManager.isActive()) {
            r.run();
            return;
        }
        float sy = p.rotationYaw, sp = p.rotationPitch, cy = RotationManager.cameraYaw(), cp = RotationManager.cameraPitch();
        if (sy == cy && sp == cp) {
            r.run();
            return;
        }
        p.rotationYaw = cy;
        p.rotationPitch = cp;
        try {
            r.run();
        } finally {
            float dy = p.rotationYaw - cy, dp = p.rotationPitch - cp;
            p.rotationYaw = sy + dy;
            p.rotationPitch = sp + dp;
        }
    }

    @SubscribeEvent
    public void onGuiKey(GuiScreenEvent.KeyboardInputEvent.Post post) {
        int n = Keyboard.getEventKey();
        ScriptInvoker.dispatch(ScriptEvent.KEY, Keyboard.getKeyName(n), n, Keyboard.getEventKeyState(), true);
    }

    @SubscribeEvent
    public void onGuiMouse(GuiScreenEvent.MouseInputEvent.Pre pre) {
        int n = Mouse.getEventButton();
        int n2 = Mouse.getEventDWheel();
        if (n == -1 && n2 == 0) return;
        boolean bl = Mouse.getEventButtonState();
        if (n2 > 0) bl = true;
        if (ScriptInvoker.dispatchBoolean(ScriptEvent.MOUSE, n, bl, Mouse.getEventX(), Mouse.getEventY()) == 0) pre.setCanceled(true);
    }

    public static Float movementYaw(float f) { return ScriptInvoker.dispatchFloat(ScriptEvent.GET_MOVEMENT_YAW, f); }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!ScriptUtil.inGame()) return;
        if (ScriptInvoker.dispatchBoolean(ScriptEvent.CHAT, event.message.getFormattedText(), event.type) == 0) event.setCanceled(true);
    }

    public static boolean ircMessage(String string) { return ScriptInvoker.dispatchBoolean(ScriptEvent.IRC_MESSAGE, string) != 0; }

    @SubscribeEvent
    public void onKey(KeyInputEvent event) {
        int n = Keyboard.getEventKey();
        ScriptInvoker.dispatch(ScriptEvent.KEY, Keyboard.getKeyName(n), n, Keyboard.getEventKeyState(), false);
    }

    public static float[] rotations(float f, float f2) { return ScriptInvoker.dispatchFloatArray(ScriptEvent.GET_ROTATIONS, f, f2); }

    public static void bedWarsAlert(String string, EntityPlayer entityPlayer) { bedWarsAlert.accept(string, entityPlayer); }

    public static void preUpdate() {
        preUpdate.run();
        if (openedScreen != null) {
            ScriptInvoker.dispatch(ScriptEvent.GUI_UPDATE, B4Helpers.screenName(openedScreen), true);
            openedScreen = null;
        }
        ScriptInvoker.dispatch(ScriptEvent.PRE_UPDATE);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ScriptUtil.inGame()) return;
        ScriptInvoker.dispatch(ScriptEvent.RENDER_TICK, event.renderTickTime);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!ScriptUtil.inGame()) return;
        ScriptInvoker.dispatch(ScriptEvent.RENDER_WORLD, event.partialTicks);
    }

    public static void postPlayerInput() { ScriptInvoker.dispatch(ScriptEvent.POST_PLAYER_INPUT); }

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent event) {
        if (event.world.isRemote) worldJoin.accept(event.entity);
    }

    public static boolean packetSent(Packet packet) { return packetSent.test(packet); }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui != null) {
            openedScreen = event.gui;
        } else if (mc.currentScreen != null) {
            openedScreen = null;
            ScriptInvoker.dispatch(ScriptEvent.GUI_UPDATE, B4Helpers.screenName(mc.currentScreen), false);
        }
    }

    public static boolean packetReceived(Packet packet) { return packetReceived.test(packet); }

    public static void locraw(String string) { ScriptInvoker.dispatch(ScriptEvent.LOCRAW, string); }
}
