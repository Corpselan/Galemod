package gale.devtest.fakeb4;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * For the self test only: shaped like B4's ScriptDefaults in the parts Gale's link looks at (a nested class "bridge"
 * with a static Map; "client.runForgeCommand"; the Entity / Vec3 / ItemStack / packet wrappers keeping the game object
 * in a field of its type). Names of fields and methods differ on purpose where Gale must not rely on them.
 */
public class FakeDefaults {
    public static class bridge {
        private static Map<String, Object> m4p = new HashMap<>();

        public static Object get(String key) { return m4p.get(key); }

        public static void add(String key, Object value) { m4p.put(key, value); }

        public static boolean has(String key) { return m4p.containsKey(key); }

        public static Object remove(String key) { return m4p.remove(key); }

        public static void clear() { m4p.clear(); }
    }

    public static class client {
        public static boolean runForgeCommand(String command) {
            return ClientCommandHandler.instance.executeCommand(Minecraft.getMinecraft().thePlayer, "/" + command) != 0;
        }
    }

    public static class Entity {
        public boolean isPlayer;
        public int entityId;
        private net.minecraft.entity.Entity e1;

        protected Entity(net.minecraft.entity.Entity entity) {
            e1 = entity;
            entityId = entity.getEntityId();
            isPlayer = entity instanceof EntityPlayer;
        }

        protected static Entity of(net.minecraft.entity.Entity entity) { return entity == null ? null : new Entity(entity); }

        public static Entity protected_of(net.minecraft.entity.Entity entity) { return of(entity); }
    }

    public static class Vec3 {
        public double z, y, x;

        public Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class ItemStack {
        protected net.minecraft.item.ItemStack s1;
        public String name;

        protected ItemStack(byte unused, net.minecraft.item.ItemStack stack) {
            s1 = stack;
            name = stack.getUnlocalizedName();
        }
    }

    public static class CPacket {
        public String name;
        protected Packet p1;

        public CPacket(Packet packet) {
            p1 = packet;
            name = packet.getClass().getSimpleName();
        }
    }

    public static class SPacket {
        public String name;
        protected Packet p1;

        public SPacket(Packet packet) {
            p1 = packet;
            name = packet.getClass().getSimpleName();
        }
    }

    public static class C02 extends CPacket {
        public String action;

        public C02(byte unused, C02PacketUseEntity packet) {
            super(packet);
            action = packet.getAction().name();
        }
    }

    public static class S12 extends SPacket {
        public int entityId;

        public S12(S12PacketEntityVelocity packet) {
            super(packet);
            entityId = packet.getEntityID();
        }
    }
}
