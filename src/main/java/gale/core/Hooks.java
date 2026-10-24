package gale.core;

import gale.Gale;
import gale.event.impl.AttackEvent;
import gale.event.impl.ChatEvent;
import gale.event.impl.EntityJoinEvent;
import gale.event.impl.FrameEvent;
import gale.event.impl.GuiEvent;
import gale.event.impl.KeyEvent;
import gale.event.impl.MouseEvent;
import gale.event.impl.PostUpdateEvent;
import gale.event.impl.Render2DEvent;
import gale.event.impl.Render3DEvent;
import gale.event.impl.ServerEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.UpdateEvent;
import gale.event.impl.WorldEvent;
import gale.script.B4Link;
import gale.script.api.InputApi;
import gale.script.api.ScriptApis;
import gale.util.ClientAttack;
import gale.util.Projection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Turns Forge events into Gale events, and runs the core services at the right moments of a tick:
 * <ol>
 * <li>tick start: scheduled tasks, re-attach the network and input hooks, {@link TickEvent.Pre}</li>
 * <li>keys and clicks (vanilla), {@link KeyEvent} / {@link MouseEvent}</li>
 * <li>your update starts: {@link UpdateEvent}, then the requested silent rotation is put in place</li>
 * <li>movement input read: {@link gale.event.impl.InputEvent} (from {@link InputHook}), then the movement fix</li>
 * <li>you move, then {@link gale.event.impl.MotionEvent} (from {@link MotionHook}), then the movement packet is sent (vanilla)</li>
 * <li>other entities move (vanilla)</li>
 * <li>tick end: {@link EntityTracker} records, {@link PostUpdateEvent} (still the server rotation), the held slot and
 * the camera rotation go back to yours, {@link TickEvent.Post}, config autosave</li>
 * </ol>
 * Frames: {@link FrameEvent} before the world is drawn, {@link Render3DEvent} after, {@link Render2DEvent} after the HUD.
 */
public final class Hooks {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Hooks() { }

    private boolean updated;

    public static void install() {
        MinecraftForge.EVENT_BUS.register(new Hooks());
        EntityTracker.install();
        RotationManager.install();
        MotionHook.install();
        HookedPackets.install();
        BlockBreaks.install();
    }

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent e) {
        if (e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) {
            gale.security.ReleaseIntegrity.requireVerified();
            // before the game's "right click is up: stop using the item" check later in this tick
            FakeUse.tickStart();
            Scheduler.tick();
            B4Link.tick();
            ScriptApis.tick();
            HookedEffects.tick();
            updated = false;
            if (mc.thePlayer != null) {
                NetHook.ensure();
                InputHook.ensure(mc.thePlayer);
                SlotManager.startTick();
            }
            Gale.modules().tick();
            Gale.BUS.post(new TickEvent.Pre());
        } else {
            if (updated && mc.thePlayer != null) {
                EntityTracker.update();
                Gale.BUS.post(new PostUpdateEvent());
            }
            SlotManager.endTick();
            RotationManager.endTick();
            Gale.BUS.post(new TickEvent.Post());
            Gale.config().tick();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent e) {
        if (e.entity != mc.thePlayer || mc.thePlayer == null) return;
        updated = true;
        RotationManager.beginTick();
        SlotManager.beginUpdate();
        Gale.BUS.post(new UpdateEvent());
        RotationManager.apply();
        SlotManager.apply();
        // the keys are read right after this: wrapped now if this tick's rotation needs the movement fix
        InputHook.ensure(mc.thePlayer);
        FakeUse.update();
    }

    @SubscribeEvent
    public void onRenderTick(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent e) {
        if (e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) {
            FakeUse.frameStart();
            Gale.BUS.post(new FrameEvent(e.renderTickTime));
        } else {
            FakeUse.frameEnd();
        }
    }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL || mc.thePlayer == null) return;
        GlStateManager.pushMatrix();
        Gale.BUS.post(new Render2DEvent(e.partialTicks, e.resolution));
        Notifications.render(e.resolution);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
    }

    @SubscribeEvent
    public void onWorldRender(RenderWorldLastEvent e) {
        if (mc.thePlayer == null) return;
        Projection.capture(e.partialTicks);
        GlStateManager.pushMatrix();
        Gale.BUS.post(new Render3DEvent(e.partialTicks));
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ input

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent e) {
        if (mc.currentScreen != null) return;
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE) return;
        boolean down = Keyboard.getEventKeyState();
        // a script may swallow the key first (then the game and Gale's modules never see it)
        if (!Keyboard.isRepeatEvent() && InputApi.onKey(key, down)) return;
        Gale.BUS.post(new KeyEvent(key, down));
        if (!Keyboard.isRepeatEvent()) Gale.modules().onKey(key, down);
    }

    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent e) {
        int button = Mouse.getEventButton();
        if (button < 0 || mc.currentScreen != null) return;
        boolean down = Mouse.getEventButtonState();
        Gale.BUS.post(new KeyEvent(button - 100, down));
        Gale.modules().onKey(button - 100, down);
    }

    @SubscribeEvent
    public void onMouse(net.minecraftforge.client.event.MouseEvent e) {
        MouseEvent ev = Gale.BUS.post(new MouseEvent(e.button, e.buttonstate, e.dx, e.dy, e.dwheel));
        if (ev.isCancelled()) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ game

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        ChatEvent ev = Gale.BUS.post(new ChatEvent(e.message, e.type));
        if (ev.isCancelled()) e.setCanceled(true);
        else e.message = ev.getMessage();
    }

    /**
     * Last of Forge's listeners (if another mod cancels the hit, Gale does nothing). Cancelled: your side of the hit
     * does not happen. Slowdown or sprint changed: Gale does your side itself with those values (ClientAttack).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAttack(AttackEntityEvent e) {
        if (e.entityPlayer != mc.thePlayer || mc.thePlayer == null) return;
        AttackEvent ev = Gale.BUS.post(new AttackEvent(e.target));
        if (ev.isCancelled()) {
            e.setCanceled(true);
        } else if (ev.isModified()) {
            e.setCanceled(true);
            ClientAttack.perform(mc.thePlayer, e.target, ev.slowdown, ev.keepSprint);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(net.minecraftforge.event.world.WorldEvent.Load e) {
        if (e.world instanceof WorldClient) Gale.BUS.post(new WorldEvent.Load((WorldClient) e.world));
    }

    @SubscribeEvent
    public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload e) {
        if (e.world instanceof WorldClient) Gale.BUS.post(new WorldEvent.Unload((WorldClient) e.world));
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent e) {
        if (e.world.isRemote) Gale.BUS.post(new EntityJoinEvent(e.entity));
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent e) {
        GuiEvent ev = Gale.BUS.post(new GuiEvent(e.gui));
        if (ev.isCancelled()) e.setCanceled(true);
        else e.gui = ev.getGui();
    }

    // ------------------------------------------------------------------ connection (network thread)

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        NetHook.install(e.manager);
        final String address = String.valueOf(e.manager.getRemoteAddress());
        Scheduler.main(() -> Gale.BUS.post(new ServerEvent.Connect(address)));
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        Scheduler.main(() -> {
            ServerState.reset();
            Gale.BUS.post(new ServerEvent.Disconnect());
        });
    }
}
