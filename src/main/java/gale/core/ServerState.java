package gale.core;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0BPacketEntityAction;

/**
 * What the server has been told, read from the packets that really left (so it includes what other clients changed):
 * last position and rotation sent, ground flag, sprint and sneak state, held slot, last attack.
 */
public final class ServerState {
    private static volatile double x, y, z;
    private static volatile float yaw, pitch;
    private static volatile boolean onGround, sprinting, sneaking;
    private static volatile int slot = -1;
    private static volatile long lastAttack, lastMovementPacket;

    private ServerState() { }

    public static double x() { return x; }

    public static double y() { return y; }

    public static double z() { return z; }

    public static float yaw() { return yaw; }

    public static float pitch() { return pitch; }

    public static boolean onGround() { return onGround; }

    /**
     * The server's sprint flag: set by your C0B packets, and taken as off after you attack (a landed sprint hit turns it
     * off on the server; a hit into hurt resistance does not, which the client cannot see).
     */
    public static boolean sprinting() { return sprinting; }

    public static boolean sneaking() { return sneaking; }

    /** The hotbar slot the server thinks you hold; -1 before the first C09. */
    public static int slot() { return slot; }

    /** System time of your last attack packet (0: none yet). */
    public static long lastAttack() { return lastAttack; }

    public static long lastMovementPacket() { return lastMovementPacket; }

    static void sent(Packet<?> p) {
        if (p instanceof C03PacketPlayer) {
            C03PacketPlayer c = (C03PacketPlayer) p;
            if (c.isMoving()) { x = c.getPositionX(); y = c.getPositionY(); z = c.getPositionZ(); }
            if (c.getRotating()) { yaw = c.getYaw(); pitch = c.getPitch(); }
            onGround = c.isOnGround();
            lastMovementPacket = System.currentTimeMillis();
        } else if (p instanceof C0BPacketEntityAction) {
            switch (((C0BPacketEntityAction) p).getAction()) {
                case START_SPRINTING: sprinting = true; break;
                case STOP_SPRINTING: sprinting = false; break;
                case START_SNEAKING: sneaking = true; break;
                case STOP_SNEAKING: sneaking = false; break;
                default: break;
            }
        } else if (p instanceof C09PacketHeldItemChange) {
            slot = ((C09PacketHeldItemChange) p).getSlotId();
        } else if (p instanceof C02PacketUseEntity && ((C02PacketUseEntity) p).getAction() == C02PacketUseEntity.Action.ATTACK) {
            lastAttack = System.currentTimeMillis();
            sprinting = false;
        }
    }

    static void reset() {
        sprinting = sneaking = onGround = false;
        slot = -1;
        lastAttack = lastMovementPacket = 0;
    }
}
