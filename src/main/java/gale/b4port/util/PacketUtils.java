package gale.b4port.util;

import gale.b4port.hook.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;

public final class PacketUtils {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static Packet<?> sending;
    public static Packet<?> sendingNoEvent;
    public static Packet<?> sendingQueued;
    public static Packet<?> sendingSkipScripts;

    public static volatile Packet<?> reprocessing;
    public static boolean sentCloseWindow;
    public static boolean sentOpenInventory;
    public static boolean sentClickWindow;
    public static boolean sentDigging;
    public static boolean sentAnyDigging;
    public static boolean sentBlockPlace;
    public static boolean sentAnyPlacement;
    public static boolean sentInteractAt;
    public static boolean sentUseEntity;
    public static boolean sentAttack;
    public static boolean sentSwing;
    public static int slotChanges;
    public static int serverSlot;
    public static int previousServerSlot;
    public static int lastUse = -1;

    public static boolean sentPlacementPost;
    public static boolean sentUseEntityPost;
    public static int slotPost;
    public static int previousSlotPost;
    public static int slotChangesPost;

    public static long lastPosLook;

    public static long lastExplosion;
    public static long lastVelocity;

    private PacketUtils() {
    }

    /** A set of packets that lets them go when nothing else holds them. */
    public static java.util.Set<Packet<?>> weakSet() {
        return java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Packet<?>, Boolean>()));
    }

    /** packets B4's bookkeeping saw already (sent from here, on the game thread, as B4 sees them) */
    private static final java.util.Set<Packet<?>> TRACKED = weakSet();
    /**
     * Gale: packets the ported code sent itself. B4's send hooks run while the packet is being sent and tell their own
     * packets by "currently sending" flags; Gale's run later on the network thread, so the packets carry the mark.
     */
    public static final java.util.Set<Packet<?>> SENT_HERE = weakSet();
    /** while set, every packet sent from here also goes into it (B4's "attacking", "releasing"... flags) */
    public static java.util.Set<Packet<?>> tag;

    public static void send(Packet<?> packet) {
        if (mc.getNetHandler() == null) {
            return;
        }
        sending = packet;
        SENT_HERE.add(packet);
        if (tag != null) {
            tag.add(packet);
        }
        if (TRACKED.add(packet)) {
            track(packet);
        }
        mc.getNetHandler().addToSendQueue(sending);
        sending = null;
    }

    /** Sends with the packet in the given set (see {@link #tag}). */
    public static void send(Packet<?> packet, java.util.Set<Packet<?>> into) {
        java.util.Set<Packet<?>> was = tag;
        tag = into;
        try {
            send(packet);
        } finally {
            tag = was;
        }
    }

    /** Gale: every sent packet, on the network thread; B4's bookkeeping for those it did not see sent from here. */
    public static void trackSent(Packet<?> packet) {
        if (!TRACKED.remove(packet)) {
            track(packet);
        }
    }

    /** Past Gale's listeners (so past the ported modules' and the scripts' hooks); other clients still see it. */
    public static void sendNoEvent(Packet<?> packet) {
        if (mc.getNetHandler() == null) {
            return;
        }
        sendingNoEvent = packet;
        SENT_HERE.add(packet);
        if (tag != null) {
            tag.add(packet);
        }
        // B4 still makes its last check on these
        if (Hooks.finalSendCheck(packet)) {
            gale.util.PacketUtil.sendNoEvent(packet);
        }
        sendingNoEvent = null;
    }

    public static void sendQueued(Packet<?> packet) {
        if (mc.getNetHandler() == null) {
            return;
        }
        sendingQueued = packet;
        gale.util.PacketUtil.sendNoEvent(packet);
        sendingQueued = null;
    }


    public static void sendThroughLag(Packet<?> packet) {
        if (!Hooks.lagPassesOutbound(packet)) {
            return;
        }
        sendNoEvent(packet);
    }

    public static void receive(Packet<?> packet) {
        Hooks.onReceive(packet, null);
    }

    public static void resetLastUse() {
        slotChangesPost = 0;
        lastUse = -1;
    }

    public static void track(Packet<?> packet) {
        if (packet instanceof C03PacketPlayer) {
            sentCloseWindow = false;
            sentOpenInventory = false;
            sentClickWindow = false;
            sentDigging = false;
            sentAnyDigging = false;
            sentBlockPlace = false;
            sentAnyPlacement = false;
            sentInteractAt = false;
            sentUseEntity = false;
            sentAttack = false;
            sentSwing = false;
            slotChanges = 0;
        } else if (packet instanceof C0APacketAnimation) {
            sentSwing = true;
        } else if (packet instanceof C02PacketUseEntity) {
            sentUseEntity = true;
            C02PacketUseEntity.Action action = ((C02PacketUseEntity) packet).getAction();
            if (action == C02PacketUseEntity.Action.ATTACK) {
                sentAttack = true;
            } else if (action == C02PacketUseEntity.Action.INTERACT_AT) {
                sentInteractAt = true;
            }
        } else if (packet instanceof C08PacketPlayerBlockPlacement) {
            sentAnyPlacement = true;
            if (((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255) {
                lastUse = 1;
            } else {
                sentBlockPlace = true;
            }
        } else if (packet instanceof C07PacketPlayerDigging) {
            sentAnyDigging = true;
            C07PacketPlayerDigging.Action action = ((C07PacketPlayerDigging) packet).getStatus();
            if (action == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                lastUse = 2;
            } else if (action == C07PacketPlayerDigging.Action.START_DESTROY_BLOCK || action == C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK || action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK) {
                sentDigging = true;
            }
        } else if (packet instanceof C09PacketHeldItemChange) {
            previousServerSlot = serverSlot;
            serverSlot = ((C09PacketHeldItemChange) packet).getSlotId();
            lastUse = 3;
            ++slotChanges;
            gale.b4port.module.dev.DebugAcModule.updateHeldItem(serverSlot);
        } else if (packet instanceof C0DPacketCloseWindow) {
            sentCloseWindow = true;
        } else if (packet instanceof C0EPacketClickWindow) {
            sentClickWindow = true;
        } else if (packet instanceof C16PacketClientStatus && ((C16PacketClientStatus) packet).getStatus() == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT) {
            sentOpenInventory = true;
        }
    }

    public static void trackPost(Packet<?> packet) {
        if (packet instanceof C03PacketPlayer) {
            sentPlacementPost = false;
            sentUseEntityPost = false;
        } else if (packet instanceof C02PacketUseEntity) {
            sentUseEntityPost = true;
        } else if (packet instanceof C08PacketPlayerBlockPlacement) {
            sentPlacementPost = true;
        } else if (packet instanceof C09PacketHeldItemChange) {
            previousSlotPost = slotPost;
            slotPost = ((C09PacketHeldItemChange) packet).getSlotId();
            ++slotChangesPost;
        }
    }

    public static void trackReceived(Packet<?> packet) {
        if (packet instanceof S09PacketHeldItemChange) {
            previousServerSlot = serverSlot;
            serverSlot = ((S09PacketHeldItemChange) packet).getHeldItemHotbarIndex();
        } else if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion p = (S27PacketExplosion) packet;
            if (mc.thePlayer.getDistanceSq(p.getX(), p.getY(), p.getZ()) <= 25.0) {
                lastExplosion = System.currentTimeMillis();
            }
        }
    }

    public static boolean noUseOrDig() {
        return !sentUseEntity && !sentAnyDigging;
    }

    public static boolean noInteraction() {
        return !(sentUseEntity || sentAnyPlacement || sentAnyDigging);
    }

    public static boolean noInteractionOrBreak() {
        return !(sentUseEntity || sentAnyPlacement || sentDigging);
    }

    public static boolean noAttackPlaceOrDig() {
        return !(sentAttack || sentAnyPlacement || sentAnyDigging);
    }

    public static boolean isKnockback(S12PacketEntityVelocity p) {
        return p.getMotionY() > 0 && p.getMotionY() < 4500;
    }
}
