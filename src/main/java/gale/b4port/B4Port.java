package gale.b4port;

import gale.Gale;
import gale.b4port.client.Chat;
import gale.b4port.client.Hypixel;
import gale.b4port.client.InfoHud;
import gale.b4port.client.Overlay;
import gale.b4port.client.Sounds;
import gale.b4port.hook.Hooks;
import gale.b4port.module.Module;
import gale.b4port.module.ModuleManager;
import gale.b4port.module.SettingsModule;
import gale.b4port.module.client.LocrawModule;
import gale.b4port.module.combat.AimAssistModule;
import gale.b4port.module.combat.KillAuraModule;
import gale.b4port.module.minigames.BedWarsModule;
import gale.b4port.module.network.BacktrackModule;
import gale.b4port.module.network.LagRangeModule;
import gale.b4port.module.other.AntiCheatModule;
import gale.b4port.module.other.LatencyAlertsModule;
import gale.b4port.module.player.BedAuraModule;
import gale.b4port.module.player.NoRotateModule;
import gale.b4port.module.player.ScaffoldModule;
import gale.b4port.module.player.TowerModule;
import gale.b4port.module.render.ChamsModule;
import gale.b4port.module.render.NametagsModule;
import gale.b4port.module.render.PlayerESPModule;
import gale.b4port.module.world.AntiBotModule;
import gale.b4port.util.PacketQueue;
import gale.b4port.util.PacketUtils;
import gale.b4port.util.PlayerUtils;
import gale.b4port.util.Render3D;
import gale.b4port.util.RotationManager;
import gale.core.FakeUse;
import gale.core.MotionHook;
import gale.event.Priority;
import gale.event.impl.MotionEvent;
import gale.event.impl.PacketEvent;
import gale.event.impl.TickEvent;
import gale.event.impl.UpdateEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import gale.b4port.module.setting.ButtonSetting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Gale's ports of B4's modules (from OpenB4): KillAura, AimAssist, Backtrack, Lag Range, BedAura, Scaffold, Tower,
 * Bed Wars, AntiCheat, Nametags, PlayerESP, Chams, with AntiBot, B4's shared Settings and Hypixel's /locraw. Each gives way to B4's own module of
 * the same name while B4 runs it (GaleLink reports which are on), and then the others see B4's (its target, whether
 * it places) through what GaleLink reports. This class does what B4's hooks do around the modules each tick.
 */
public final class B4Port {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private B4Port() { }

    public static void install() {
        Module[] modules = { new SettingsModule(), new AntiBotModule(0), new KillAuraModule(0), new AimAssistModule(0), new BacktrackModule(0),
            new LagRangeModule(0), new BedAuraModule(0), new ScaffoldModule(0), new TowerModule(0), new BedWarsModule(0), new AntiCheatModule(0),
            new NametagsModule(0), new PlayerESPModule(0), new ChamsModule(0) };
        for (Module m : modules) {
            ModuleManager.add(m);
            Gale.modules().register(m);
        }
        for (Module m : modules) {
            try {
                m.link();
            } catch (Throwable t) {
                Gale.LOG.error("{} could not link its settings", m.getName(), t);
            }
        }
        Gale.modules().register(new LocrawModule());

        // start of the tick: start or stop B4's side of each module (giving way to B4 or not)
        Gale.BUS.listen(TickEvent.Pre.class, Priority.HIGHEST, e -> {
            for (Module m : ModuleManager.ports()) m.syncRunning();
        }, "b4port");
        // B4's preUpdate starts with its bookkeeping
        Gale.BUS.listen(UpdateEvent.class, Priority.HIGHEST + 40, e -> {
            RotationManager.beginTick();
            Hypixel.tick();
            PlayerUtils.onPlayerUpdate();
        }, "b4port");
        // B4's safe walk: Scaffold stops you at edges (unless Tower says not to)
        Gale.BUS.listen(UpdateEvent.class, Priority.LOWEST, e -> safeWalk(), "b4port");
        Gale.BUS.listen(MotionEvent.class, Priority.HIGHEST, PlayerUtils::onPreMotion, "b4port");
        Gale.BUS.listen(MotionEvent.class, Priority.MONITOR, e -> RotationManager.sent(e.yaw, e.pitch), "b4port");
        // sent packets: B4 notes them first, and checks slot changes last
        Gale.BUS.listen(PacketEvent.Send.class, Priority.HIGH + 50, e -> PacketUtils.trackSent(e.getPacket()), "b4port");
        Gale.BUS.listen(PacketEvent.Send.class, Priority.LOWEST - 50, e -> {
            if (!e.isCancelled() && !Hooks.finalSendCheck(e.getPacket())) e.cancel();
        }, "b4port");
        // received packets: B4's notes, Scaffold's "Cancel knockback", knockback for KillAura
        Gale.BUS.listen(PacketEvent.Receive.class, Priority.HIGHEST + 100, e -> received(e.getPacket()), "b4port");
        Gale.BUS.listen(PacketEvent.Receive.class, Priority.HIGH - 15, e -> {
            if (!e.isCancelled() && cancelsKnockback(e.getPacket())) e.cancel();
        }, "b4port");
        Gale.BUS.listen(PacketEvent.Receive.class, Priority.NORMAL + 30, e -> {
            if (!e.isCancelled()) knockback(e.getPacket());
        }, "b4port");
        Gale.BUS.listen(TickEvent.Post.class, Priority.NORMAL, e -> {
            if (PacketQueue.active) PacketQueue.tick();
            Sounds.flush();
            fakeBlock();
        }, "b4port");
        MinecraftForge.EVENT_BUS.register(new B4Port.Forge());
    }

    private static void safeWalk() {
        if (mc.thePlayer == null || !mc.thePlayer.onGround || !PlayerUtils.isAirBelow()) return;
        Module tower = ModuleManager.get("Tower");
        if (tower != null && !tower.allowsSafeWalk()) return;
        Module scaffold = ModuleManager.get("Scaffold");
        if (scaffold != null && scaffold.stopsOnEdge()) MotionHook.safeWalk();
    }

    private static void received(Packet<?> packet) {
        LatencyAlertsModule.lastPacket = System.currentTimeMillis();
        if (mc.thePlayer == null) return;
        PacketUtils.trackReceived(packet);
        if (packet instanceof S08PacketPlayerPosLook) {
            PacketUtils.lastPosLook = System.currentTimeMillis();
            NoRotateModule.onS08(((S08PacketPlayerPosLook) packet).getPitch());
        }
    }

    private static boolean fakeBlocking;

    /** B4 shows KillAura's fake block (the sword held up) by answering "is blocking" for you; Gale's FakeUse does that. */
    private static void fakeBlock() {
        Module killAura = ModuleManager.get("KillAura");
        boolean want = killAura instanceof KillAuraModule && killAura.isRunning() && KillAuraModule.fakeBlocking && mc.thePlayer != null
            && PlayerUtils.holdingSword() && !mc.thePlayer.isUsingItem();
        if (want == fakeBlocking) return;
        fakeBlocking = want;
        FakeUse.set(want ? FakeUse.Mode.RENDER : FakeUse.Mode.OFF);
    }

    // B4: Scaffold's "Cancel knockback" while placing and moving (or towering)
    private static boolean cancelsKnockback(Packet<?> packet) {
        if (mc.thePlayer == null) return false;
        if (packet instanceof S12PacketEntityVelocity) {
            if (((S12PacketEntityVelocity) packet).getEntityID() != mc.thePlayer.getEntityId()) return false;
        } else if (!(packet instanceof S27PacketExplosion)) {
            return false;
        }
        // only Gale's Scaffold: B4 cancels it itself for its own
        Module scaffold = ModuleManager.get("Scaffold");
        if (!(scaffold instanceof ScaffoldModule) || !scaffold.isScaffolding() || ScaffoldModule.cancelKnockback == null
            || !ScaffoldModule.cancelKnockback.isToggled()) return false;
        Module tower = ModuleManager.get("Tower");
        return tower != null && tower.isTowering() || PlayerUtils.isMoving();
    }

    private static void knockback(Packet<?> packet) {
        if (!(packet instanceof S12PacketEntityVelocity) || mc.thePlayer == null) return;
        S12PacketEntityVelocity p = (S12PacketEntityVelocity) packet;
        if (p.getEntityID() != mc.thePlayer.getEntityId() || !PacketUtils.isKnockback(p)) return;
        long now = System.currentTimeMillis();
        Module killAura = ModuleManager.enabled("KillAura");
        if (killAura instanceof KillAuraModule && killAura.isRunning()) killAura.onKnockback(p, now);
        PacketUtils.lastVelocity = now;
    }

    /** B4's own Forge handlers that are not a module's: chat (AntiBot, Bed Wars' game start, /locraw), world join. */
    public static final class Forge {
        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent e) {
            if (e.type == 2 || !Chat.inWorld()) return;
            String s = Chat.strip(e.message.getUnformattedText());
            if (s.isEmpty()) return;
            Module antiBot = ModuleManager.enabled("AntiBot");
            if (antiBot instanceof AntiBotModule) antiBot.onChat(e, s);
            if (s.charAt(0) == ' ') {
                Module bedWars = ModuleManager.enabled("Bed Wars");
                if (bedWars instanceof BedWarsModule && bedWars.isRunning()) BedWarsModule.onGameStartChat(e, s, true, false, false);
            }
            if (Hypixel.onChat(s) && LocrawModule.hideAnswer()) e.setCanceled(true);
        }

        // B4's RenderDispatcher: before the frame, the view for "is it on screen"; per frame, KillAura times its hits;
        // after the frame, Scaffold's count and the info HUD
        @SubscribeEvent
        public void onRenderTick(net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent e) {
            if (e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START) {
                Render3D.updateFrustum(e.renderTickTime);
                return;
            }
            if (!Chat.inWorld()) return;
            KillAuraModule.onRenderTick();
            boolean visible = Overlay.canRender();
            if (visible && ModuleManager.get("Scaffold") instanceof ScaffoldModule) ScaffoldModule.renderCount();
            InfoHud.render(visible);
        }

        // B4's RenderDispatcher: PlayerESP (the outlines first, all players in one pass), then each player's box and
        // nametag, then Scaffold's placed blocks
        @SubscribeEvent
        public void onRenderWorld(RenderWorldLastEvent e) {
            if (!Chat.inWorld()) return;
            float pt = e.partialTicks;
            boolean esp = playerEsp();
            boolean tags = nametags();
            if (tags) Render3D.updateCameraPos();
            if (esp && PlayerESPModule.isShader()) PlayerESPModule.renderShader(pt);
            if (esp || tags) {
                EntityPlayer self = null;
                for (EntityPlayer p : mc.theWorld.playerEntities) {
                    if (p == mc.thePlayer) {
                        self = p;
                        continue;
                    }
                    if (p.deathTime != 0) continue;
                    if (esp && visible(p, PlayerESPModule.showInvis)) PlayerESPModule.render(p, pt);
                    if (tags && visible(p, NametagsModule.showInvis)) NametagsModule.render(p, pt);
                }
                if (self != null && mc.gameSettings.thirdPersonView != 0 && !self.isPlayerSleeping()) {
                    if (esp && PlayerESPModule.renderSelf.isToggled()) PlayerESPModule.render(self, pt);
                    if (tags && NametagsModule.renderSelf.isToggled()) NametagsModule.render(self, pt);
                }
            }
            ScaffoldModule.renderPlaced();
        }

        // B4: no vanilla name over players while Nametags draws its own (and none in PlayerESP's outline pass)
        @SubscribeEvent
        public void onSpecials(RenderLivingEvent.Specials.Pre<?> e) {
            if (PlayerESPModule.shaderPass) {
                e.setCanceled(true);
            } else if (nametags() && Chat.inWorld() && (e.x == 0.0 && e.y == 0.0 && e.z == 0.0 || visible(e.entity, NametagsModule.showInvis))) {
                e.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onJoin(EntityJoinWorldEvent e) {
            if (e.entity == mc.thePlayer) {
                PacketQueue.onJoinWorld();
                Hypixel.joinedAt = System.currentTimeMillis();
                Hypixel.pendingLocraw = true;
            }
        }
    }

    // B4's RenderDispatcher: PlayerESP / Nametags draw while on (in Gale, not giving way to B4's), unless "Disable in lobby"
    static boolean playerEsp() {
        return PlayerESPModule.instance != null && PlayerESPModule.instance.isRunning()
            && (!PlayerESPModule.disableInLobby.isToggled() || Hypixel.state != Hypixel.State.LOBBY);
    }

    static boolean nametags() {
        return NametagsModule.instance != null && NametagsModule.instance.isRunning()
            && (!NametagsModule.disableInLobby.isToggled() || Hypixel.state != Hypixel.State.LOBBY);
    }

    static boolean visible(EntityLivingBase e, ButtonSetting showInvis) {
        if (showInvis != null && !showInvis.isToggled() && e.isInvisible()) return false;
        return !gale.b4port.client.BotCheck.aboveSpawn(e) && Render3D.isVisible(e) && !gale.b4port.client.BotCheck.isBot(e);
    }

    /** Ported modules running in Gale (not giving way to B4), for the B4 script runtime's answers. */
    public static Module running(String name) {
        Module m = ModuleManager.get(name);
        return m != null && m.isRunning() ? m : null;
    }
}
