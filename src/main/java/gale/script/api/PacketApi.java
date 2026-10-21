package gale.script.api;

import gale.core.NetHook;
import gale.event.Priority;
import gale.event.impl.PacketEvent;
import gale.script.Args;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.PacketData;
import gale.script.ScriptEvents;
import gale.util.PacketUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Packets: read and change every field of any packet (also inside B4's own onPacketReceived / onPacketSent), make any
 * packet, send or deliver it with or without anyone seeing, hold packets back and let them go, and see every packet
 * that really comes in or goes out.
 */
public final class PacketApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "packets";

    private PacketApi() { }

    public static void install() {
        ScriptEvents.define(AREA, "packetIn", "{SPacket 包, String 名字}",
            "收到一个包，网络线程，在游戏和 B4 的 onPacketReceived 之前，所有包都有（B4 没有包装的也是 SPacket）。"
                + "args[0] 换成别的包就是替换；返回非 0 = 丢掉。读改字段用 packet.get / packet.set。",
            PacketEvent.Receive.class, Priority.NORMAL, e -> {
                if (e.isCancelled()) return;
                Object wrapped = B4Link.wrap(e.getPacket());
                Object[] a = { wrapped, PacketData.name(e.getPacket()) };
                if (ScriptEvents.fire("packetIn", a)) e.cancel();
                else if (a[0] != wrapped) replace(e, a[0]);
            });
        ScriptEvents.define(AREA, "packetOut", "{CPacket 包, String 名字}",
            "一个包真的发出去了，网络线程，在 B4 的 onPacketSent 之后：B4 模块和脚本用 sendPacketNoEvent 发的也看得到。"
                + "args[0] 换成别的包就是替换；返回非 0 = 不发。",
            PacketEvent.Send.class, Priority.NORMAL, e -> {
                if (e.isCancelled()) return;
                Object wrapped = B4Link.wrap(e.getPacket());
                Object[] a = { wrapped, PacketData.name(e.getPacket()) };
                if (ScriptEvents.fire("packetOut", a)) e.cancel();
                else if (a[0] != wrapped) replace(e, a[0]);
            });

        Functions.add(AREA, "packet.name", "<包>", "包的类名，例如 \"S19PacketEntityStatus\"。", a -> PacketData.name(a.packet(0)));
        Functions.add(AREA, "packet.list", "", "所有包和它们的编号（List<String>，\"S12PacketEntityVelocity 0x12\"）。", a -> PacketData.listing());
        Functions.add(AREA, "packet.fields", "<包>",
            "包的全部字段：Map<String, Object>，字段名 -> 值。数字是 Integer/Long/Double，文字是 String，物品、坐标、方块是 B4 的对象，"
                + "聊天文字是带颜色码的 String，其余是 List / Map。", a -> PacketData.read(a.packet(0)));
        Functions.add(AREA, "packet.get", "<包> <字段>", "读一个字段（名字见 packet.fields）。", a -> PacketData.get(a.packet(0), a.str(1)));
        Functions.add(AREA, "packet.set", "<包> <字段> <值>",
            "改一个字段，改的是包本身：在 packetIn / packetOut 和 B4 的 onPacketReceived / onPacketSent 里改，游戏收到或发出的就是改过的。"
                + "数字、文字、true/false、B4 的物品和坐标都行；聊天文字给 String（& 颜色码）或 JSON。", a -> {
                PacketData.set(a.packet(0), a.str(1), a.raw(2));
                return true;
            });
        Functions.add(AREA, "packet.create", "<名字> [Map 字段] 或 [字段 值 字段 值...]",
            "造任意一个包，名字可以写全名、\"EntityStatus\" 或编号前缀 \"C0F\"。返回 B4 的 CPacket / SPacket，可以给 client.sendPacket、"
                + "packet.send、packet.receive。没给的字段是 0 / null。", a -> {
                Packet<?> p = PacketData.create(PacketData.find(a.str(0)));
                fill(p, a);
                return p;
            });
        Functions.add(AREA, "packet.send", "<包> [silent=false]",
            "发一个包。默认和 client.sendPacket 一样（B4 和 Gale 的钩子都看得到）；silent 为 true：谁都看不到，直接进编码器。", a -> {
                Packet<?> p = a.packet(0);
                if (a.bool(1, false)) return NetHook.sendSilent(p);
                PacketUtil.send(p);
                return true;
            });
        Functions.add(AREA, "packet.receive", "<包> [now=false]",
            "当作服务器发来的包处理。默认从 Gale 在管道里的位置往后走：B4 的 onPacketReceived 看得到，游戏在自己的线程里处理；"
                + "now 为 true：现在就在这里处理（要在游戏线程）。", a -> {
                Packet<?> p = a.packet(0);
                if (!a.bool(1, false)) return NetHook.deliver(p);
                CombatApi.gameThread("packet.receive now");
                PacketUtil.receive(p);
                return true;
            });

        String hold = AREA + ".hold";
        Functions.add(hold, "packet.hold", "<in|out> [true|false] [只扣这些包，例如 C03,C0F]",
            "开始扣下收到（in）或发出（out）的包，不给游戏或不发出去；给了包名就只扣这些。false：停止并按顺序放出全部。"
                + "in 扣在 Gale 的 packetIn 之后、B4 的 onPacketReceived 之前；out 扣在 packetOut 之后。", a -> {
                NetHook.Hold h = side(a, 0);
                if (!a.bool(1, true)) {
                    h.stop();
                    return true;
                }
                List<Class<?>> only = new ArrayList<>();
                if (a.has(2)) for (String n : a.strings(2)) if (!n.trim().isEmpty()) only.add(PacketData.find(n));
                h.start(only);
                return true;
            });
        Functions.add(hold, "packet.release", "<in|out> [个数=全部]", "按顺序放出最早扣下的几个包（不停止扣包）。返回放出了几个。",
            a -> side(a, 0).release(a.integer(1, -1)));
        Functions.add(hold, "packet.drop", "<in|out>", "扔掉扣下的包（永远不给游戏或不发出去）。返回扔了几个。", a -> side(a, 0).drop());
        Functions.add(hold, "packet.held", "<in|out>", "现在扣着几个包。", a -> side(a, 0).size());
        Functions.add(hold, "packet.holding", "<in|out>", "是不是正在扣包。", a -> side(a, 0).isActive());

        Functions.add(AREA, "c17", "<频道> [十六进制数据或 byte[]]",
            "立刻发一个 C17 插件消息包（正常发送：B4 和脚本的 onPacketSent 都看得到）。", a -> {
                String channel = a.str(0);
                if (channel.length() > 20) throw new IllegalArgumentException("频道名最多 20 个字符");
                byte[] data = a.raw(1) instanceof byte[] ? (byte[]) a.raw(1) : PacketData.hex(a.str(1, ""));
                PacketUtil.send(new C17PacketCustomPayload(channel, new PacketBuffer(Unpooled.wrappedBuffer(data))));
                return true;
            });
    }

    private static void replace(PacketEvent e, Object scriptPacket) {
        Object p = B4Link.unwrap(scriptPacket);
        if (p instanceof Packet) e.setPacket((Packet<?>) p);
        else if (p == null) e.cancel();
        else throw new IllegalArgumentException("args[0] 要换成一个包，收到的是 " + p.getClass().getSimpleName());
    }

    private static NetHook.Hold side(Args a, int i) {
        String s = a.str(i).trim().toLowerCase();
        if (s.equals("in")) return NetHook.IN;
        if (s.equals("out")) return NetHook.OUT;
        throw new IllegalArgumentException("第 " + (i + 1) + " 个参数要 in 或 out");
    }

    @SuppressWarnings("unchecked")
    private static void fill(Packet<?> p, Args a) throws IllegalAccessException {
        if (a.size() == 2 && a.raw(1) instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) a.raw(1)).entrySet()) PacketData.set(p, e.getKey(), e.getValue());
            return;
        }
        if ((a.size() - 1) % 2 != 0) throw new IllegalArgumentException("字段和值要成对给：packet.create 名字 字段 值 字段 值...");
        for (int i = 1; i + 1 < a.size(); i += 2) PacketData.set(p, a.str(i), a.raw(i + 1));
    }
}
