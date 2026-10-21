package gale.script.api;

import gale.core.FakeUse;
import gale.event.Priority;
import gale.event.impl.AttackEvent;
import gale.event.impl.EntityStatusEvent;
import gale.event.impl.ExplosionEvent;
import gale.event.impl.KnockbackEvent;
import gale.event.impl.PacketEvent;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.ScriptEvents;
import gale.util.ClientAttack;
import gale.util.ReflectUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.util.Locale;

/** Hooks into your hits, your knockback and entity statuses; your side of a hit; swinging and blocking on your side only. */
public final class CombatApi {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final String AREA = "combat";
    private static final Field STATUS_ENTITY = ReflectUtil.field(S19PacketEntityStatus.class, "entityId", "field_149164_a");

    private CombatApi() { }

    public static void install() {
        ScriptEvents.define(AREA, "attack", "{Entity 目标, Double 减速, Boolean 保持疾跑}",
            "你打了一下（点击、B4 的 KillAura、client.attack 都算），游戏线程。C02 已经发出，接下来是你这边的效果。"
                + "改 args[1] 减速（原版 0.6：打到玩家且有击退时你的水平速度乘它）、args[2] 保持疾跑（原版 false）。"
                + "返回非 0 = 你这边什么都不发生（不减速、不停疾跑、没有暴击粒子、不掉耐久）。",
            AttackEvent.class, Priority.LOW, e -> {
                if (e.isCancelled()) return;
                Object[] a = { B4Link.wrap(e.target), e.slowdown, e.keepSprint };
                if (ScriptEvents.fire("attack", a)) e.cancel();
                e.slowdown = ScriptEvents.num(a, 1, e.slowdown);
                e.keepSprint = ScriptEvents.bool(a, 2, e.keepSprint);
            });
        ScriptEvents.define(AREA, "velocity", "{Double x, Double y, Double z}",
            "服务器给你的击退（S12）正在生效，游戏线程，在它所在的 tick 之前。单位 格/tick。改 args 就是改击退，返回非 0 = 不吃这次击退（速度不变）。"
                + "和 Leader 的 KnockbackEvent 同一时刻。",
            KnockbackEvent.class, Priority.LOW, e -> {
                if (e.isCancelled()) return;
                Object[] a = { e.x, e.y, e.z };
                if (ScriptEvents.fire("velocity", a)) e.cancel();
                e.x = ScriptEvents.num(a, 0, e.x);
                e.y = ScriptEvents.num(a, 1, e.y);
                e.z = ScriptEvents.num(a, 2, e.z);
            });
        ScriptEvents.define(AREA, "explosion", "{Double x, Double y, Double z, Double 强度, Vec3 爆炸位置}",
            "爆炸包（S27）正在生效，游戏线程。args[0..2] 是加到你速度上的推力，可改；返回非 0 = 不被推（爆炸本身照常）。",
            ExplosionEvent.class, Priority.LOW, e -> {
                if (e.isCancelled()) return;
                Object[] a = { e.x, e.y, e.z, (double) e.strength, B4Link.wrap(new Vec3(e.posX, e.posY, e.posZ)) };
                if (ScriptEvents.fire("explosion", a)) e.cancel();
                e.x = ScriptEvents.num(a, 0, e.x);
                e.y = ScriptEvents.num(a, 1, e.y);
                e.z = ScriptEvents.num(a, 2, e.z);
            });
        ScriptEvents.define(AREA, "entityStatus", "{Integer 实体id, Integer 状态, Entity 实体或null}",
            "实体状态包（S19）正在生效，游戏线程。常见状态：2 受伤、3 死亡、9 吃完/用完。返回非 0 = 丢掉这个包。"
                + "自己受伤：args[0] 等于 client.getPlayer().entityId 且 args[1] 是 2。",
            EntityStatusEvent.class, Priority.LOW, e -> {
                if (e.isCancelled()) return;
                Object[] a = { e.entityId, (int) e.status, B4Link.wrap(e.entity) };
                if (ScriptEvents.fire("entityStatus", a)) e.cancel();
            });

        ScriptEvents.define(AREA, "entityStatusIn", "{Integer 实体id, Integer 状态}",
            "实体状态包（S19）刚收到，网络线程，游戏还没应用它；在 B4 和脚本的 onPacketReceived 收到同一个包之前。"
                + "返回非 0 = 丢掉这个包。和 entityStatus 的区别：那个在游戏应用它的时候（游戏线程）。",
            PacketEvent.Receive.class, Priority.LOW, e -> {
                if (e.isCancelled()) return;
                S19PacketEntityStatus s = e.as(S19PacketEntityStatus.class);
                if (s == null) return;
                Object[] a = { ReflectUtil.getInt(STATUS_ENTITY, s, -1), (int) s.getOpCode() };
                if (ScriptEvents.fire("entityStatusIn", a)) e.cancel();
            });

        Functions.add(AREA, "attackLocal", "<实体> [减速=0.6] [保持疾跑=false]",
            "只做你这边的一下攻击（原版打中后客户端做的全部：击退时减速、停疾跑、暴击粒子、掉耐久……），什么包都不发。"
                + "给自己用 sendPacket 发 C02 的脚本用。游戏线程。", a -> {
                gameThread("attackLocal");
                ClientAttack.perform(mc.thePlayer, a.entity(0), a.num(1, AttackEvent.VANILLA_SLOWDOWN), a.bool(2, false));
                return true;
            });
        Functions.add(AREA, "swingLocal", "", "挥手动画（第一和第三人称），不发 C0A。游戏线程。", a -> {
            gameThread("swingLocal");
            ClientAttack.swingLocal(mc.thePlayer);
            return true;
        });
        Functions.add(AREA, "fakeUse", "[off|render|full]",
            "只在你这边“正在使用手里的物品”（剑就是格挡），不发任何包。render：只有动画；full：还像真格挡一样减速，"
                + "别的端在你更新时问“是不是在用物品”也会得到是。不带参数：返回现在的模式。", a -> {
                if (a.has(0)) {
                    String m = a.str(0).toUpperCase(Locale.ROOT);
                    try {
                        FakeUse.set(FakeUse.Mode.valueOf(m));
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("fakeUse 的模式只有 off、render、full");
                    }
                }
                return FakeUse.mode().name().toLowerCase(Locale.ROOT);
            });
        Functions.add(AREA, "fakeUsing", "", "现在游戏看到的“正在使用”是不是 Gale 的 fakeUse 放的（boolean）。", a -> FakeUse.isApplied());
    }

    static void gameThread(String function) {
        mainThread(function);
        if (mc.thePlayer == null) throw new IllegalStateException(function + " 要在世界里调用");
    }

    /** On the game thread (in a world or not). */
    static void mainThread(String function) {
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException(function + " 要在游戏线程调用（不要在 onPacketReceived 或 client.async 里）");
    }
}
