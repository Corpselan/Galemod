package gale.script.api;

import gale.Gale;
import gale.bridge.B4State;
import gale.script.Args;
import gale.script.B4Link;
import gale.script.Functions;
import gale.script.ScriptEvents;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/** The basics, always on: version, help, listening to events, and GaleLink's reports of B4's own state. */
public final class CoreApi {
    private CoreApi() { }

    public static void install() {
        Functions.add("version", "", "Gale 的版本，例如 \"1.0\"。", a -> Gale.VERSION);
        Functions.add("linked", "", "总是 true（能调用就说明已经连上）。", a -> B4Link.isLinked());
        Functions.add("help", "[函数名]", "不带参数：所有函数的用法（List<String>）。带函数名：那一个的用法和说明（String）。", a -> {
            if (a.has(0)) {
                Functions.Entry e = Functions.get(a.str(0));
                return e == null ? null : e.name + " " + e.usage + " - " + e.doc;
            }
            List<String> out = new ArrayList<>();
            for (Functions.Entry e : Functions.all()) out.add(e.name + (e.usage.isEmpty() ? "" : " " + e.usage));
            return out;
        });
        Functions.add("events", "[事件名]", "不带参数：所有事件（List<String>，名字和参数）。带事件名：那一个的说明（String）。", a -> {
            if (a.has(0)) {
                ScriptEvents.Kind k = ScriptEvents.kind(a.str(0));
                return k == null ? null : k.name + " " + k.args + " - " + k.doc;
            }
            List<String> out = new ArrayList<>();
            for (ScriptEvents.Kind k : ScriptEvents.all()) out.add(k.name + " " + k.args);
            return out;
        });
        Functions.add("on", "<事件> <脚本名> <回调>",
            "监听事件：回调写成 (Comparator<Object>) (e, a) -> { Object[] args = (Object[]) a; ...; return 0; }。返回非 0 = 取消。"
                + "同一个脚本名再注册会替换旧的。", a -> {
                ScriptEvents.on(a.str(0), a.str(1), a.callback(2));
                return true;
            });
        Functions.add("off", "[事件] <脚本名>", "取消监听：给事件和脚本名就只取消那一个，只给脚本名就取消这个脚本的全部。返回取消了几个。",
            a -> a.size() >= 2 ? ScriptEvents.off(a.str(0), a.str(1)) : ScriptEvents.off(null, a.str(0)));

        // what B4 itself knows, from the GaleLink script (gale.bridge.B4State)
        Functions.add("b4.report", "<KillAura目标> <AimAssist目标> <Backtrack目标> <LagRange目标> <BedAura方块> <BedAura进度float[]> <在搭路> <在搭高> <自家床>",
            "GaleLink 每 tick 报告 B4 自己的状态（目标是实体或 null，方块是 Vec3 或 null）。Gale 的模块和 Gale 里跑的 B4 脚本用这些值。", a -> {
                B4State.report(entity(a, 0), entity(a, 1), entity(a, 2), entity(a, 3), pos(a, 4),
                    a.raw(5) instanceof float[] ? (float[]) a.raw(5) : null, a.bool(6, false), a.bool(7, false), pos(a, 8));
                return true;
            });
        Functions.add("b4.event", "<bedwars|anticheat|locraw|irc> <文字> [玩家]",
            "GaleLink 转来的 B4 事件：BedWars 提醒（类型）、AntiCheat 标记（检查名）、/locraw 的回答、IRC 消息。变成 Gale 的事件。", a -> {
                B4State.event(a.str(0), a.str(1, ""), entity(a, 2));
                return true;
            });
    }

    // a target that already left the world is just none, not an error in chat every tick
    private static Entity entity(Args a, int i) {
        try {
            return a.entityOrNull(i);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BlockPos pos(Args a, int i) {
        if (!a.has(i)) return null;
        try {
            Vec3 v = a.vec(i);
            return new BlockPos(Math.floor(v.xCoord), Math.floor(v.yCoord), Math.floor(v.zCoord));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
