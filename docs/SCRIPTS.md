# 给 B4 脚本用的 Gale API

B4 脚本做不到的，装了 Gale 之后脚本可以通过 `bridge.get("gale")` 做到。分成八块：

- 数据包：任意包的读写、造包、扣包；
- 战斗钩子；
- 实体和世界数据；
- 渲染；
- 输入；
- 自己的界面和聊天；
- 系统：文件、剪贴板、网页、网络；
- 准星。

每一块都能在 ClickGUI 的「辅助」里单独关掉。

完整的例子：[companion/GaleApiDemo.java](../companion/GaleApiDemo.java)（放进 B4 的脚本文件夹就能用，已经过 B4 沙箱检查）。

## 连上

```java
Map<String, Object> gale;

void onPreUpdate() {
    if (gale == null) {
        client.runForgeCommand("gale link");      // Gale 把它的 API 放进 B4 的 bridge
        Object g = bridge.get("gale");
        if (g instanceof Map) gale = (Map<String, Object>) g;
    }
}
```

- 没装 Gale 时 `bridge.get("gale")` 是 null，脚本照常运行。
- 只用到 `Map`、`Object[]`、`Comparator`，都是 B4 允许的，不会碰到 B4 的禁用词。
- B4 用 windows-1252 读脚本文件，脚本里别写中文（聊天文字、注释都一样），否则会乱码。

## 调用函数

- `gale.get("名字 参数 参数")`：参数写成一段文字，用空格隔开。
- `gale.put("名字", new Object[]{ 参数, ... })`：参数是对象，可以是数字、文字、true/false，也可以是 B4 的 Entity、Vec3、ItemStack、CPacket、SPacket。
- 返回值：
  - 游戏对象会变成 B4 的对象（Entity、Vec3、ItemStack、Block、CPacket / SPacket，有专门包装的包就是 C02、S12 这些）；
  - 其余是 Integer / Double / Boolean / String、List、Map、数组。
- 出错时返回 null，聊天栏里写原因；同一个错误 5 秒内只说一次。出错的情况有：
  - 参数不对；
  - 该在游戏线程调用的没在游戏线程调用；
  - 这一块辅助关着。
- `gale.get("help")` 列出所有函数，`gale.get("help packet.set")` 看一个函数的说明，`gale.get("events")` 列出所有事件。

## 监听事件

```java
gale.put("on", new Object[]{ "packetIn", getScriptName(), (Comparator<Object>) (e, a) -> {
    Object[] args = (Object[]) a;   // 事件的值：改了就是改事件
    return 0;                       // 不是 0：取消（能取消的事件才有用）
} });

void onDisable() {
    if (gale != null) gale.put("off", new Object[]{ getScriptName() });   // 取消这个脚本的全部监听
}
```

- 第二个值是监听者的名字，同一个名字再注册会替换旧的回调，所以重载脚本不会留下旧回调。
- 回调连续出错 10 次会被自动移除。
- 每个事件都写了在哪个线程上触发（见下面的参考）：
  - 网络线程上的事件（`packetIn`、`packetOut`、`entityStatusIn`）里只读状态、改包、取消包，不要改世界，也不要在聊天栏打字；先记下来，到 `onPreUpdate` 里再处理。
- 只有在有脚本监听时，Gale 才接上对应的钩子。粒子、鼠标、准星这三个事件要接管游戏的对象，没人监听时也不接管。

## 在 ClickGUI 里管理

ClickGUI（右 Ctrl）→「辅助」里，每一块是一个模块：

| 模块 | 内容 | 分开关 |
|---|---|---|
| Script Packets | 数据包 | Hold |
| Script Combat | 战斗钩子 | |
| Script Data | 实体和世界数据 | |
| Script Render | 渲染 | Particles |
| Script Input | 输入 | Keys、Mouse |
| Script Screens | 界面和聊天 | |
| Script System | 系统 | Files、Clipboard、Browser、Network |
| Script Crosshair | 准星 | |

- 关掉一块之后：
  - 这一块的函数返回 null，聊天栏会说是哪个开关关的；
  - 它的事件不再触发。
- 模块名后面会显示：
  - 哪个脚本在监听这一块的事件；
  - 或者「刚有脚本用过」。
- `/gale api` 在聊天栏列出同样的信息。

## 例子

### 读 B4 读不到的包：实体状态（S19）

```java
gale.put("on", new Object[]{ "packetIn", getScriptName(), (Comparator<Object>) (e, a) -> {
    Object[] args = (Object[]) a;
    if (!"S19PacketEntityStatus".equals(args[1])) return 0;
    Map<String, Object> f = (Map<String, Object>) gale.put("packet.fields", new Object[]{ args[0] });
    int id = (Integer) f.get("entityId"), status = (Integer) f.get("logicOpcode");   // 2 = hurt
    return 0;
} });
```

### 在 B4 自己的 onPacketReceived 里改包

`packet.set` 改的是包本身，所以在 B4 的钩子里改，游戏收到的就是改过的包：

```java
boolean onPacketReceived(SPacket packet) {
    if (gale != null && packet instanceof S12) gale.put("packet.set", new Object[]{ packet, "motionY", 0 });
    return true;
}
```

### 扣包（blink）

```java
gale.put("packet.hold", new Object[]{ "out", true, "C03,C04,C05,C06" });   // 开始：只扣移动包
gale.put("packet.hold", new Object[]{ "out", false });                     // 停止：按顺序全部发出
```

### Reach

```java
gale.put("on", new Object[]{ "pick", getScriptName(), (Comparator<Object>) (e, a) -> {
    Object[] args = (Object[]) a;
    if ("entity".equals(args[0])) return 0;                                  // 原版已经指着实体
    Object hit = gale.put("pick.entity", new Object[]{ 3.3 });
    if (hit != null) {
        args[0] = "entity";
        args[1] = ((Object[]) hit)[0];
        args[2] = ((Object[]) hit)[1];
    }
    return 0;
} });
```

### 自己的界面

```java
gale.put("screen.field", new Object[]{ "cfg", "name", 20, 70, 120, 20, "" });
gale.put("screen.button", new Object[]{ "cfg", "save", 20, 100, 120, 20, "Save" });
gale.put("on", new Object[]{ "screenButton", getScriptName(), (Comparator<Object>) (e, a) -> {
    if ("save".equals(((Object[]) a)[1])) gale.put("file.write", new Object[]{ "cfg.txt", gale.get("screen.text cfg name") });
    return 0;
} });
gale.get("screen.open cfg &eSettings");
```

### WebSocket

```java
gale.put("on", new Object[]{ "socket", getScriptName(), (Comparator<Object>) (e, a) -> {
    Object[] args = (Object[]) a;            // {name, type, text}
    if ("message".equals(args[1])) client.print("&b" + args[2]);
    return 0;
} });
gale.get("ws.open chat wss://example.com/chat");
gale.put("ws.send", new Object[]{ "chat", "hi" });
```

## 不提供的

- 运行程序；
- 反射；
- 账号的登录令牌；
- 读写启动器的账号文件（`launcher_accounts.json` 这类）。

<!-- 下面是自动生成的（gradlew runClient -Pscripttest 写到 run/SCRIPTS-reference.md），别手改 -->

## 参考

### 基础（一直开着）

版本、帮助、监听事件，GaleLink 报来的 B4 状态和事件。

| 函数 | 参数 | 说明 |
|---|---|---|
| `b4.event` | `<bedwars\|anticheat\|locraw\|irc> <文字> [玩家]` | GaleLink 转来的 B4 事件：BedWars 提醒（类型）、AntiCheat 标记（检查名）、/locraw 的回答、IRC 消息。变成 Gale 的事件。 |
| `b4.report` | `<KillAura目标> <AimAssist目标> <Backtrack目标> <LagRange目标> <BedAura方块> <BedAura进度float[]> <在搭路> <在搭高> <自家床>` | GaleLink 每 tick 报告 B4 自己的状态（目标是实体或 null，方块是 Vec3 或 null）。Gale 的模块和 Gale 里跑的 B4 脚本用这些值。 |
| `events` | `[事件名]` | 不带参数：所有事件（List<String>，名字和参数）。带事件名：那一个的说明（String）。 |
| `help` | `[函数名]` | 不带参数：所有函数的用法（List<String>）。带函数名：那一个的用法和说明（String）。 |
| `linked` |  | 总是 true（能调用就说明已经连上）。 |
| `off` | `[事件] <脚本名>` | 取消监听：给事件和脚本名就只取消那一个，只给脚本名就取消这个脚本的全部。返回取消了几个。 |
| `on` | `<事件> <脚本名> <回调>` | 监听事件：回调写成 (Comparator<Object>) (e, a) -> { Object[] args = (Object[]) a; ...; return 0; }。返回非 0 = 取消。同一个脚本名再注册会替换旧的。 |
| `version` |  | Gale 的版本，例如 "1.0"。 |

### Script Packets

B4 脚本读、改、造、扣任何数据包，看到所有收发的包。开关：ClickGUI → 辅助 → Script Packets，里面还能分开关：Hold（扣包和放包（packet.hold / release / drop））。

| 函数 | 参数 | 说明 |
|---|---|---|
| `c17` | `<频道> [十六进制数据或 byte[]]` | 立刻发一个 C17 插件消息包（正常发送：B4 和脚本的 onPacketSent 都看得到）。 |
| `packet.create` | `<名字> [Map 字段] 或 [字段 值 字段 值...]` | 造任意一个包，名字可以写全名、"EntityStatus" 或编号前缀 "C0F"。返回 B4 的 CPacket / SPacket，可以给 client.sendPacket、packet.send、packet.receive。没给的字段是 0 / null。 |
| `packet.drop` | `<in\|out>` | 扔掉扣下的包（永远不给游戏或不发出去）。返回扔了几个。（分开关：Hold） |
| `packet.fields` | `<包>` | 包的全部字段：Map<String, Object>，字段名 -> 值。数字是 Integer/Long/Double，文字是 String，物品、坐标、方块是 B4 的对象，聊天文字是带颜色码的 String，其余是 List / Map。 |
| `packet.get` | `<包> <字段>` | 读一个字段（名字见 packet.fields）。 |
| `packet.held` | `<in\|out>` | 现在扣着几个包。（分开关：Hold） |
| `packet.hold` | `<in\|out> [true\|false] [只扣这些包，例如 C03,C0F]` | 开始扣下收到（in）或发出（out）的包，不给游戏或不发出去；给了包名就只扣这些。false：停止并按顺序放出全部。in 扣在 Gale 的 packetIn 之后、B4 的 onPacketReceived 之前；out 扣在 packetOut 之后。（分开关：Hold） |
| `packet.holding` | `<in\|out>` | 是不是正在扣包。（分开关：Hold） |
| `packet.list` |  | 所有包和它们的编号（List<String>，"S12PacketEntityVelocity 0x12"）。 |
| `packet.name` | `<包>` | 包的类名，例如 "S19PacketEntityStatus"。 |
| `packet.receive` | `<包> [now=false]` | 当作服务器发来的包处理。默认从 Gale 在管道里的位置往后走：B4 的 onPacketReceived 看得到，游戏在自己的线程里处理；now 为 true：现在就在这里处理（要在游戏线程）。 |
| `packet.release` | `<in\|out> [个数=全部]` | 按顺序放出最早扣下的几个包（不停止扣包）。返回放出了几个。（分开关：Hold） |
| `packet.send` | `<包> [silent=false]` | 发一个包。默认和 client.sendPacket 一样（B4 和 Gale 的钩子都看得到）；silent 为 true：谁都看不到，直接进编码器。 |
| `packet.set` | `<包> <字段> <值>` | 改一个字段，改的是包本身：在 packetIn / packetOut 和 B4 的 onPacketReceived / onPacketSent 里改，游戏收到或发出的就是改过的。数字、文字、true/false、B4 的物品和坐标都行；聊天文字给 String（& 颜色码）或 JSON。 |

| 事件 | args | 说明 |
|---|---|---|
| `packetIn` | `{SPacket 包, String 名字}` | 收到一个包，网络线程，在游戏和 B4 的 onPacketReceived 之前，所有包都有（B4 没有包装的也是 SPacket）。args[0] 换成别的包就是替换；返回非 0 = 丢掉。读改字段用 packet.get / packet.set。 |
| `packetOut` | `{CPacket 包, String 名字}` | 一个包真的发出去了，网络线程，在 B4 的 onPacketSent 之后：B4 模块和脚本用 sendPacketNoEvent 发的也看得到。args[0] 换成别的包就是替换；返回非 0 = 不发。 |

### Script Combat

攻击、击退、爆炸、实体状态的钩子，只在本地的攻击、挥手和格挡。开关：ClickGUI → 辅助 → Script Combat。

| 函数 | 参数 | 说明 |
|---|---|---|
| `attackLocal` | `<实体> [减速=0.6] [保持疾跑=false]` | 只做你这边的一下攻击（原版打中后客户端做的全部：击退时减速、停疾跑、暴击粒子、掉耐久……），什么包都不发。给自己用 sendPacket 发 C02 的脚本用。游戏线程。 |
| `fakeUse` | `[off\|render\|full]` | 只在你这边“正在使用手里的物品”（剑就是格挡），不发任何包。render：只有动画；full：还像真格挡一样减速，别的端在你更新时问“是不是在用物品”也会得到是。不带参数：返回现在的模式。 |
| `fakeUsing` |  | 现在游戏看到的“正在使用”是不是 Gale 的 fakeUse 放的（boolean）。 |
| `swingLocal` |  | 挥手动画（第一和第三人称），不发 C0A。游戏线程。 |

| 事件 | args | 说明 |
|---|---|---|
| `attack` | `{Entity 目标, Double 减速, Boolean 保持疾跑}` | 你打了一下（点击、B4 的 KillAura、client.attack 都算），游戏线程。C02 已经发出，接下来是你这边的效果。改 args[1] 减速（原版 0.6：打到玩家且有击退时你的水平速度乘它）、args[2] 保持疾跑（原版 false）。返回非 0 = 你这边什么都不发生（不减速、不停疾跑、没有暴击粒子、不掉耐久）。 |
| `entityStatus` | `{Integer 实体id, Integer 状态, Entity 实体或null}` | 实体状态包（S19）正在生效，游戏线程。常见状态：2 受伤、3 死亡、9 吃完/用完。返回非 0 = 丢掉这个包。自己受伤：args[0] 等于 client.getPlayer().entityId 且 args[1] 是 2。 |
| `entityStatusIn` | `{Integer 实体id, Integer 状态}` | 实体状态包（S19）刚收到，网络线程，游戏还没应用它；在 B4 和脚本的 onPacketReceived 收到同一个包之前。返回非 0 = 丢掉这个包。和 entityStatus 的区别：那个在游戏应用它的时候（游戏线程）。 |
| `explosion` | `{Double x, Double y, Double z, Double 强度, Vec3 爆炸位置}` | 爆炸包（S27）正在生效，游戏线程。args[0..2] 是加到你速度上的推力，可改；返回非 0 = 不被推（爆炸本身照常）。 |
| `velocity` | `{Double x, Double y, Double z}` | 服务器给你的击退（S12）正在生效，游戏线程，在它所在的 tick 之前。单位 格/tick。改 args 就是改击退，返回非 0 = 不吃这次击退（速度不变）。和 Leader 的 KnockbackEvent 同一时刻。 |

### Script Data

服务器位置、别人的速度、朝向、用物品时间、方块硬度和碰撞箱、任意起点射线、移动和抛射物预测。开关：ClickGUI → 辅助 → Script Data。

| 函数 | 参数 | 说明 |
|---|---|---|
| `block.boxes` | `<x> <y> <z>` | 方块的碰撞箱（List<double[6]>，可能有几个，比如楼梯；没有碰撞箱是空的）。 |
| `block.breakTicks` | `<x> <y> <z> [快捷栏格子 0-8=手上的]` | 用那一格的东西挖这个方块要几 tick（算上效率、急迫、挖掘疲劳、在水里、不在地上），挖不动是 -1。游戏线程。 |
| `block.hardness` | `<x> <y> <z>` | 方块硬度（石头 1.5，基岩 -1）。 |
| `block.light` | `<x> <y> <z>` | 那里的亮度 0-15。 |
| `block.state` | `<x> <y> <z>` | 方块状态：Map，"name" 是方块名（"minecraft:stone"），其余是属性（"facing" -> "north"...）。 |
| `combo` |  | 你现在的连击数。 |
| `entity.attackedTicks` | `<实体>` | 距离你上次打它多少 tick。 |
| `entity.bodyYaw` | `<实体>` | 身体的朝向（renderYawOffset，Double）。 |
| `entity.box` | `<实体>` | 碰撞箱 double[]{minX, minY, minZ, maxX, maxY, maxZ}。 |
| `entity.canSee` | `<实体>` | 你的眼睛能不能看到它（中间没有方块）。 |
| `entity.data` | `<实体>` | 实体的同步数据（DataWatcher）：Map，编号 -> 值。0 号是状态位（着火、蹲、疾跑、用物品、隐身）。 |
| `entity.headYaw` | `<实体>` | 头的朝向（rotationYawHead，Double）。 |
| `entity.history` | `<实体>` | 最近 40 tick 的位置（List<Vec3>，新的在前）。 |
| `entity.hitsByYou` | `<实体>` | 你连续打中它几下（它打你或者断了就清零）。 |
| `entity.hurtTicks` | `<实体>` | 距离它上次受伤多少 tick（看到受伤动画开始算）。 |
| `entity.isBot` | `<实体>` | 是不是假人：不在 Tab 列表里、Hypixel NPC、名字不合法。 |
| `entity.isTeammate` | `<实体>` | 是不是队友（计分板队伍、名字颜色或盔甲颜色，按 Targets 模块的设置）。 |
| `entity.position` | `<实体> <几 tick 前>` | 几 tick 前的位置（Vec3，最多 40 tick）。 |
| `entity.predict` | `<实体> <tick 数>` | 按现在的速度推算几 tick 后的位置（Vec3）。 |
| `entity.reach` | `<实体>` | 服务器判断够不够得着时用的距离（从你的眼睛到它碰撞箱最近的点）。 |
| `entity.serverPos` | `<实体>` | 服务器那边的位置（Vec3）：别人是服务器最后发来的位置（没有客户端的平滑），你自己是最后发给服务器的位置。可以用来做 backtrack。 |
| `entity.useTicks` | `<实体>` | 玩家已经用（吃、喝、拉弓、格挡）手里的东西多少 tick，没在用是 0。 |
| `entity.velocity` | `<实体> [几 tick 平均=1]` | 速度（Vec3，格/tick），别的玩家也能算（服务器不发他们的速度，这是按位置变化算的）。 |
| `mining.at` | `<x> <y> <z>` | 这个方块正在被挖的话，挖得最快的那个人（Map，同 mining.list），没人挖是 null。游戏线程。 |
| `mining.by` | `<实体>` | 这个人正在挖的方块（Map，同 mining.list），没在挖是 null。游戏线程。 |
| `mining.list` |  | 现在所有正在被挖的方块（List<Map>，你自己挖的也在）。每个 Map："entity" 在挖的人（不在你的世界里是 null）、"entityId"、"name" 名字、"pos" 方块 Vec3、"stage" 0-9、"progress" 0.1-1.0、"ticks" 从开始挖这个方块过了几 tick、"idle" 服务器上次更新后过了几 tick（一直不更新可能是人走远了）。游戏线程。 |
| `projectile.path` | `[yaw] [pitch] [弓蓄力 0..1] [种类 arrow\|thrown\|potion\|bottle\|rod]` | 手里的弓、雪球、蛋、珍珠、喷溅药水、附魔瓶、鱼竿从你眼睛射出去会怎么飞（不算随机偏差）。不给朝向就用你现在的朝向，不给蓄力就按现在拉弓的时间（没拉就是满）。返回 Map："path" List<Vec3>、"hit" "block"/"entity"/null、"entity" 被打中的实体、"pos" 落点 Vec3、"ticks" 飞了几 tick。手里不是这些东西又没给种类就是 null。 |
| `raycast` | `<x> <y> <z> <yaw> <pitch> <距离> [算实体=true]` | 从任意一点朝一个方向看，先碰到的方块或实体（实体按准星的规则：碰撞箱加上边框，不算你自己）。没碰到 null；碰到 Object[]{ String "block"/"entity", Vec3 碰撞点, 方块坐标 Vec3 或 Entity, String 面（实体是 null） }。 |
| `rayTraceBlocks` | `<x1> <y1> <z1> <x2> <y2> <z2> [停在液体=false] [忽略无碰撞箱方块=false] [返回最后一个无碰撞箱方块=false]` | 和游戏的 World.rayTraceBlocks 完全一样：从第一个点到第二个点碰到的第一个方块。没碰到返回 null，碰到返回 Object[]{ Vec3 碰撞点, Vec3 方块坐标, String 面 }。 |
| `sim.landTicks` | `[最多 tick=60] [前后 左右 跳 蹲]` | 推算再过几 tick 落地，在地上是 0，算不到是 -1。 |
| `sim.path` | `<tick 数> [前后 -1..1 左右 -1..1 跳 蹲]` | 按 1.8.9 的移动规则和真实方块推算你接下来每 tick 的位置（List<Vec3>）。不给按键就用这 tick 的按键。不算水、岩浆、梯子、蜘蛛网和还没到的击退。 |
| `timer` |  | 游戏速度（Timer 的 timerSpeed，正常是 1.0）。 |
| `youHurtTicks` |  | 距离你上次受伤多少 tick。 |

| 事件 | args | 说明 |
|---|---|---|
| `mining` | `{Entity 在挖的人或null, Integer 实体id, Vec3 方块, Integer 阶段}` | 服务器告诉你有人挖方块的进度变了（S25，就是别人挖方块时你看到的裂纹），游戏线程。阶段 0-9（9 快挖掉了），-1 = 停了或者挖掉了。实体不在你的世界里时是 null（实体 id 还在）。你自己挖的不走这里（服务器不发给挖的人）。返回非 0 = 不显示这道裂纹。 |

### Script Render

隐藏或透视实体、名字、手、HUD 的部分、方块框，改视野和相机，游戏选项。开关：ClickGUI → 辅助 → Script Render，里面还能分开关：Particles（接管粒子系统，让脚本能去掉粒子）。

| 函数 | 参数 | 说明 |
|---|---|---|
| `option.get` | `<名字>` | 读游戏选项（option.list 看全部名字）。 |
| `option.list` |  | 所有能读写的选项和现在的值（Map）。 |
| `option.set` | `<名字> <值>` | 改游戏选项，马上生效（不写进 options.txt，除非你之后在选项界面里保存）。 |

| 事件 | args | 说明 |
|---|---|---|
| `blockHighlight` | `{Vec3 方块}` | 要画准星指着的方块的框之前。返回非 0 = 不画。 |
| `camera` | `{Double yaw, Double pitch, Double roll}` | 每帧摆相机的时候，改 args 只改画面的角度（不改你的朝向）。 |
| `fov` | `{Double 倍数}` | 视野倍数在变（疾跑、速度药水、拉弓会变）。args[0] 改成 1.0 = 不让它变。 |
| `hand` | `{Double partialTicks}` | 要画第一人称的手之前。返回非 0 = 不画手。 |
| `nametag` | `{Entity 实体, Double x, Double y, Double z}` | 要画生物头上的名字之前。返回非 0 = 不画原版名字。 |
| `overlay` | `{String 部分}` | 要画 HUD 的一部分之前：CROSSHAIRS、HEALTH、FOOD、ARMOR、AIR、HOTBAR、EXPERIENCE、BOSSHEALTH、HELMET、PORTAL、CHAT、PLAYER_LIST、DEBUG、TEXT、JUMPBAR、HEALTHMOUNT。返回非 0 = 不画这部分。 |
| `particle` | `{String 种类, Vec3 位置}` | 要加一个粒子（暴击、方块碎屑、药水...），种类是类名，例如 "EntityCrit2FX"。返回非 0 = 不加。只在有脚本监听时接管粒子系统。（分开关：Particles） |
| `renderLiving` | `{Entity 实体, Double x, Double y, Double z, Boolean 透视}` | 要画一个生物（玩家、怪物、动物）之前，每帧每个生物一次，游戏线程。x/y/z 是相对相机的位置。返回非 0 = 不画它；args[4] 设成 true = 这一次隔着墙也画出来（透视）。 |
| `renderLivingPost` | `{Entity 实体, Double x, Double y, Double z}` | 画完一个生物之后（可以接着画自己的东西）。 |

### Script Input

吞掉按键、改鼠标移动。开关：ClickGUI → 辅助 → Script Input，里面还能分开关：Keys（按键事件和吞键）、Mouse（接管鼠标移动）。

| 函数 | 参数 | 说明 |
|---|---|---|
| `mouse.grabbed` |  | 鼠标是不是被游戏抓着（在游戏里、没开界面）。 |

| 事件 | args | 说明 |
|---|---|---|
| `key` | `{Integer 键码, String 键名, Boolean 按下, String 按键绑定或null}` | 在游戏里（没开界面）按下或松开一个键，游戏线程，在游戏处理这个键之前（数字键换格子、Q 丢东西、E 背包、T 聊天、F5、攻击和使用键……）。按下时返回非 0 = 吞掉这次按键：游戏和 Gale 的模块都当没按过。B4 自己的 onKey 还是会收到。（分开关：Keys） |
| `mouseMove` | `{Integer dx, Integer dy}` | 每帧鼠标移动了多少（原始像素，还没乘灵敏度），游戏线程。改 args 就是改这一帧转镜头用的移动（可以加上自己的移动来转镜头），返回非 0 = 这一帧镜头不动。只在有脚本监听时接管鼠标。（分开关：Mouse） |

### Script Screens

脚本自己的界面（按钮、输入框），能点的聊天消息，填聊天框。开关：ClickGUI → 辅助 → Script Screens。

| 函数 | 参数 | 说明 |
|---|---|---|
| `chat.delete` | `<行号>` | 删掉用这个行号显示的那一行。游戏线程。 |
| `chat.link` | `<文字> <command\|url\|suggest\|callback> <值> [悬停文字] [行号]` | 显示一条能点的消息。command：点了执行这条命令（"/..."，Gale 和别的客户端命令也行）；url：打开网页；suggest：把值填进聊天框；callback：点了触发 chatClick 事件，args[0] 就是这个值。游戏线程。 |
| `chat.open` | `[文字]` | 打开聊天框，里面先填好文字（不发送）。游戏线程。 |
| `chat.print` | `<文字或 JSON> [行号]` | 在聊天栏显示一条只有你看得到的消息：普通文字（& 颜色码）或原版的 JSON 文字组件（可以带点击、悬停）。给了行号：再用同一个行号显示会替换那一行（做会变的状态行）。游戏线程。 |
| `chat.sent` |  | 你发过的消息和命令（聊天框里按上键翻到的那些，List<String>，旧的在前）。 |
| `screen.background` | `<界面> <true\|false>` | 要不要画原版的暗背景（默认要）。 |
| `screen.button` | `<界面> <名字> <x> <y> <宽> <高> <文字>` | 在界面上放一个按钮（同名就改它）。点了触发 screenButton。 |
| `screen.close` | `[界面]` | 关掉现在开着的脚本界面（给了名字就只在开着的是它时关）。 |
| `screen.current` |  | 现在开着的脚本界面的名字，没开是 null。 |
| `screen.enable` | `<界面> <名字> <true\|false>` | 按钮或输入框能不能用。 |
| `screen.field` | `<界面> <名字> <x> <y> <宽> <高> [文字] [最多几个字=256]` | 在界面上放一个输入框（同名就改位置）。 |
| `screen.open` | `<界面> [标题] [暂停游戏=false]` | 打开（没有就先建）一个脚本界面。游戏线程。 |
| `screen.remove` | `<界面> [名字]` | 去掉一个按钮或输入框；不给名字就把整个界面的定义删掉。 |
| `screen.size` |  | 界面坐标下屏幕的宽高 int[]{宽, 高}。 |
| `screen.text` | `<界面> <名字> [新文字]` | 读（或改）输入框的文字、按钮的文字。 |

| 事件 | args | 说明 |
|---|---|---|
| `chatClick` | `{String 值}` | 点了 chat.link 用 callback 方式做的聊天链接，args[0] 是当时给的值。 |
| `screenButton` | `{String 界面, String 按钮}` | 脚本的界面里点了一个按钮。 |
| `screenClick` | `{String 界面, Integer x, Integer y, Integer 鼠标键}` | 在脚本的界面里按下鼠标。返回非 0 = 按钮和输入框不处理这次点击。 |
| `screenClose` | `{String 界面}` | 脚本的界面关了（Esc、screen.close、或者开了别的界面）。 |
| `screenDraw` | `{String 界面, Integer 鼠标x, Integer 鼠标y, Double partialTicks}` | 脚本的界面在画，每帧一次，游戏线程：在背景和标题之后、按钮和输入框之前。用 B4 的 render.* 画自己的东西（坐标是界面坐标，screen.size 给宽高）。 |
| `screenKey` | `{String 界面, String 字符, Integer 键码}` | 在脚本的界面里按键。返回非 0 = 输入框不处理（Esc 也不关界面）。 |

### Script System

文件、剪贴板、打开网页、TCP 和 WebSocket。开关：ClickGUI → 辅助 → Script System，里面还能分开关：Files（读写文件）、Clipboard（读写剪贴板）、Browser（用浏览器打开网页）、Network（TCP 和 WebSocket 连接）。

| 函数 | 参数 | 说明 |
|---|---|---|
| `browse` | `<网址>` | 用系统浏览器打开网页（只能 http:// 或 https://）。（分开关：Browser） |
| `clipboard.get` |  | 剪贴板里的文字。（分开关：Clipboard） |
| `clipboard.set` | `<文字>` | 把文字放进剪贴板。（分开关：Clipboard） |
| `file.append` | `<路径> <文字>` | 在文本文件后面接着写（没有就新建）。（分开关：Files） |
| `file.delete` | `<路径>` | 删一个文件或空文件夹。返回删掉没有。（分开关：Files） |
| `file.dir` |  | 脚本数据文件夹的完整路径（.minecraft/galemod/script-data，没有会建好）。相对路径都从这里算。（分开关：Files） |
| `file.exists` | `<路径>` | 文件或文件夹在不在。（分开关：Files） |
| `file.list` | `[文件夹]` | 文件夹里的东西（List<String>，文件夹名后面带 /），不给就是脚本数据文件夹。（分开关：Files） |
| `file.mkdirs` | `<文件夹>` | 建文件夹（连同上面没有的）。（分开关：Files） |
| `file.read` | `<路径>` | 读文本文件（UTF-8），没有这个文件是 null。（分开关：Files） |
| `file.readBytes` | `<路径>` | 读文件的字节（byte[]），没有是 null。（分开关：Files） |
| `file.write` | `<路径> <文字>` | 写文本文件（UTF-8，覆盖），文件夹没有会建好。（分开关：Files） |
| `file.writeBytes` | `<路径> <byte[] 或十六进制>` | 写字节（覆盖）。（分开关：Files） |
| `net.close` | `<连接名>` | 关掉一个连接（TCP 或 WebSocket）。（分开关：Network） |
| `net.closeAll` |  | 关掉所有脚本连接。返回关了几个。（分开关：Network） |
| `net.list` |  | 开着的连接名（List<String>）。（分开关：Network） |
| `socket.open` | `<连接名> <主机> <端口> [tls=false]` | 在后台连一个 TCP 服务器，收到的每一行都触发 socket 事件。同名的旧连接会先关掉。（分开关：Network） |
| `socket.send` | `<连接名> <文字> [加换行=true]` | 往 TCP 连接发文字（UTF-8）。（分开关：Network） |
| `ws.open` | `<连接名> <ws:// 或 wss:// 地址>` | 在后台连一个 WebSocket，每条消息都触发 socket 事件。（分开关：Network） |
| `ws.send` | `<连接名> <文字>` | 往 WebSocket 发一条文字消息。（分开关：Network） |

| 事件 | args | 说明 |
|---|---|---|
| `socket` | `{String 连接名, String 类型, String 内容}` | 脚本的网络连接有事了，游戏线程。类型："open" 连上了、"line" TCP 收到一行、"message" WebSocket 收到一条消息（二进制消息是 Base64）、"error" 出错（内容是原因）、"close" 断了。（分开关：Network） |

### Script Crosshair

改这一 tick 准星指着谁（Reach、Hitbox）。开关：ClickGUI → 辅助 → Script Crosshair。

| 函数 | 参数 | 说明 |
|---|---|---|
| `pick.current` |  | 现在准星指着什么：Object[]{ 类型, 实体, 碰撞点, 方块, 面 }，和 pick 事件的 args 一样。 |
| `pick.entity` | `<距离> [碰撞箱放大=0] [yaw] [pitch]` | 按原版准星的规则找实体，但用你给的距离和放大的碰撞箱，被方块挡住的不算。从你的眼睛出发，不给朝向就用你现在的朝向。返回 Object[]{ Entity 实体, Vec3 碰撞点 } 或 null。游戏线程。 |

| 事件 | args | 说明 |
|---|---|---|
| `pick` | `{String 类型, Entity 实体, Vec3 碰撞点, Vec3 方块, String 面}` | 每 tick 游戏刚选好准星指着什么（这 tick 的左右键就按它算），游戏线程，在点击之前。类型是 "entity"、"block" 或 "miss"。改它：args[0]="entity" 加 args[1]=实体（args[2] 碰撞点可不给）；args[0]="block" 加 args[3] 方块坐标、args[4] 面；args[0]="miss" = 什么都没指着。pick.entity 可以按更远的距离、更大的碰撞箱找实体。 |

