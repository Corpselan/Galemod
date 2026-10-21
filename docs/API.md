# Gale API：写模块用的东西

（给 B4 脚本用的 API 在 [SCRIPTS.md](SCRIPTS.md)。）

一个新功能 = 一个 `Module` 子类：在构造函数里声明设置和监听的事件，再在 `Gale.java` 里 `MODULES.register(new 你的模块())`。

```java
package gale.module.impl.combat;

public final class Example extends Module {
    private final NumberSetting range = number("Range", 3.0, 1.0, 6.0, 0.1).suffix(" blocks");
    private final ModeSetting mode = mode("Mode", "Legit", "Legit", "Blatant");
    private final BoolSetting silent = bool("Silent", true).visibleWhen(() -> mode.is("Blatant"));

    public Example() {
        super("Example", Category.COMBAT, "一句话说明");
        complements("KillAura");                       // B4 的 KillAura 开着时，这个模块先停
        on(UpdateEvent.class, e -> { ... });           // 只在：开着、在世界里、没在让路时运行
        on(PostUpdateEvent.class, e -> { ... });
    }

    @Override protected void onEnable() { ... }        // 可能在世界外被调用：先判断 player() 是不是 null
    @Override public String getSuffix() { return mode.get(); }   // HUD 列表名字后面的小字
}
```

[gale/example](../src/main/java/gale/example) 里有四个完整的小例子：Sprint、SafeWalk、AimAssist、Velocity。它们只在开发时用，不打进 jar。

---

## 一个 tick 里的顺序

| 顺序 | 发生什么 | 能用的事件 |
|---|---|---|
| 1 | tick 开始：定时任务、重新接上钩子、上一 tick 的静默槽位放回 | `TickEvent.Pre` |
| 2 | 原版处理按键和点击 | `KeyEvent`、`MouseEvent`（可取消） |
| 3 | **你开始更新**：决定这 tick 做什么，请求转头和槽位 | `UpdateEvent` |
| 4 | Gale 放上静默转头和槽位 | |
| 5 | 原版读移动按键 | `InputEvent`（可改），然后做移动修正 |
| 6 | 原版移动；**发移动包之前**：这个包要带的位置、朝向、着地、疾跑、潜行（B4 的 onPreMotion） | `MotionEvent`（可改） |
| 6½ | 原版发移动包（带这 tick 的转头，和 `MotionEvent` 里改过的值） | |
| 7 | 其他实体移动；EntityTracker 记录 | |
| 8 | **移动包已发出**，`player.rotationYaw` 仍是服务器朝向：在这里打和放 | `PostUpdateEvent` |
| 9 | 槽位和镜头朝向换回你自己的 | `TickEvent.Post` |

每帧：画世界前 `FrameEvent`，画完世界 `Render3DEvent`，画完原版 HUD `Render2DEvent`。

收发包 `PacketEvent.Send` / `PacketEvent.Receive` **在网络线程上**（发包时比你调用发送晚一点才到这里；要认出自己发的包，给包做记号，不要用"正在发"的标记）。在这里可以改包、取消包、读游戏状态，但不要改世界；要回到游戏线程就用 `Scheduler.main(...)`。

其他事件：`ChatEvent`（可取消、可替换）、`AttackEvent`（可取消：C02 已经发出，取消的是你这边的减速和停疾跑）、`WorldEvent.Load/Unload`、`ServerEvent.Connect/Disconnect`、`EntityJoinEvent`、`GuiEvent`（可取消、可换界面）。

### 监听

```java
on(UpdateEvent.class, e -> ...);                    // 模块里：模块激活时才运行
on(PacketEvent.Receive.class, Priority.HIGH, e -> ...);
onAlways(TickEvent.Pre.class, Priority.NORMAL, e -> ...);   // 模块开着就运行（世界外、让路时也运行）
Gale.BUS.listen(UpdateEvent.class, Priority.NORMAL, e -> ..., "名字");   // 不属于任何模块的监听
```

优先级数字越大越先运行：`HIGHEST 200`、`HIGH 100`、`NORMAL 0`、`LOW -100`、`LOWEST -200`、`MONITOR -1000`（只看结果）。监听器抛出的异常会被记录，不影响其他监听器。

---

## 设置

在模块的字段里声明：

| 方法 | 类型 | 读取 |
|---|---|---|
| `bool(名, 默认)` | `BoolSetting` | `get()` |
| `number(名, 默认, 最小, 最大, 步长)` | `NumberSetting` | `get()` `getInt()` `getFloat()`；`.suffix("ms")` |
| `range(名, 低, 高, 最小, 最大, 步长)` | `RangeSetting` | `low()` `high()` `random()` |
| `mode(名, 默认, 选项...)` | `ModeSetting` | `get()` `is("Legit")` `index()` |
| `color(名, 0xAARRGGBB)` | `ColorSetting` | `get()`（彩虹模式也会动） |
| `text(名, 默认)` | `TextSetting` | `get()` |

每个设置都能接着链式调用：`.describe("说明")`、`.visibleWhen(() -> 条件)`、`.onChange(v -> ...)`。设置会自动出现在设置界面和 `/gale set` 里，也会自动保存。

## 和别的端互补

- `complements("Velocity", "AntiKnockback")`：B4（或别的报告状态的端）这些模块开着时，这个模块的监听不运行。HUD 里显示成灰色，模块会自动多一个 `Defer` 开关。
- `External.anyEnabled("KillAura")`、`External.enabled("b4")`：自己判断别的端开着什么。
- `Bridge.request("b4", "Velocity", false)`：请 B4 关掉它的 Velocity，GaleLink 脚本会照做。
- `Bridge.publish(键, 值)`：发布数据给脚本读；`Bridge.get(键)`：读取脚本发来的数据。

---

## 转头：RotationManager（静默）、RotationUtil（计算）

```java
on(UpdateEvent.class, e -> {
    float[] want = RotationUtil.to(target);                          // 从眼睛看向目标
    RotationManager.requestSmooth(this, want, 50, 30f);              // 优先级 50，每 tick 最多转 30°
});
on(PostUpdateEvent.class, e -> {
    if (CombatUtil.serverAimsAt(target, 3.0)) CombatUtil.attack(target);  // 移动包（带转头）已发出，再打
});
```

- 请求只在当前 tick 有效，要保持就每 tick 再请求一次。几个模块同时请求时，优先级高的生效。
- 没有模块请求时，服务器朝向会按 Rotations 模块里的 `Release speed` 慢慢转回镜头方向，不会一下跳回去。
- `MoveFix`：`LEGIT` 把移动按键转成最接近的 8 个方向，`EXACT` 精确转，`NONE` 不修正。这样服务器朝向变了，你仍然往镜头方向走。
- `GCD fix`：每次转动都是鼠标最小步长的整数倍。
- 服务器把你拉回（S08）时，镜头不会被转到静默朝向；回给服务器的仍然是它期望的朝向。
- 查询：`RotationManager.isActive()`、`yaw()`/`pitch()`/`current()`（服务器朝向）、`cameraYaw()`/`cameraPitch()`（镜头朝向）、`owner()`、`gcd()`。

RotationUtil 的方法：

| 方法 | 作用 |
|---|---|
| `to(Entity)` / `to(e, AimPoint.HEAD/CENTER/FEET/CLOSEST)` | 看向实体 |
| `to(Vec3)` / `toFace(BlockPos, EnumFacing)` | 看向一个点、方块的某个面 |
| `limit(from, to, maxYaw, maxPitch)` | 限制转速 |
| `smooth(from, to, 比例)` | 平滑 |
| `gcdFix(from, to)` | 取鼠标步长的整数倍 |
| `distance(a, b)` | 两个朝向差多少度 |
| `inFov(e, fov)` | 实体在不在视野里 |
| `look(yaw, pitch)` | 朝向对应的方向向量 |
| `camera()` / `server()` | 镜头朝向 / 服务器朝向 |
| `turnCamera(dYaw, dPitch, gcd)` | 直接转镜头（不是静默），每帧调用画面也是平滑的 |

## 槽位：SlotManager（静默切换）

```java
on(UpdateEvent.class, e -> SlotManager.request(this, InventoryUtil.bestBlocks(), 50));
on(PostUpdateEvent.class, e -> BlockUtil.place(info));   // 用的是请求到的槽位里的方块
```

- 每个 tick 里你拿着请求的槽位，服务器也只被告知一次。
- 两个 tick 之间画快捷栏和手的时候，显示的是你自己的槽位。
- 不再请求时，槽位自动换回来。
- 你自己按数字键或滚轮换槽，换到的就成为你自己的槽位。
- 查询：`SlotManager.isActive()`、`ownSlot()`。

## 移动

- **移动包**：`MotionEvent` 里改 `x/y/z`、`yaw/pitch`、`onGround`，这一 tick 的移动包就带改过的值（你这边不动）；改 `sprinting`/`sneaking`，这一 tick 就这样告诉服务器。之后原版"上次发的值"也是改过的值，和 B4 一样。
- **安全走路**：在 `UpdateEvent` 里调 `MotionHook.safeWalk()`，这一 tick 走到边缘会像潜行一样停住，但不减速，服务器也看不到潜行。
- **输入**：在 `InputEvent` 里改 `forward`/`strafe`/`jump`/`sneak`，用 `setSneak(true)` 会和原版一样把速度降到 0.3。`sprint = true/false` 表示开始或停止疾跑。方向按镜头算，静默转头的修正由 Gale 做。
- **预测** `PlayerSim`：按 1.8.9 的规则和世界里真实的方块往前算你的移动，自测里和游戏的误差是 0。
  ```java
  PlayerSim s = PlayerSim.of(player());       // 现在的状态和这 tick 的按键
  s.input(1, 0, false, false).tick();         // 往前走一 tick
  s.onGround; s.x; s.box(); s.path(20); s.ticksToLand(40);
  ```
  不处理水、岩浆、梯子、蜘蛛网、灵魂沙，以及还没到的击退。
- **MoveUtil**：`strafe(速度)`、`setSpeed(速度, yaw)`、`stop()`、`multiply(倍数)`、`moveYaw()`、`baseSpeed()`、`jumpMotion()`，以及常量 `GROUND_FRICTION`、`AIR_FRICTION`。
- **PlayerUtil**：`isMoving()`、`speed()`、`bps()`、`health()`、`effectLevel(Potion)`、`isOverVoid()`、`isOnEdge()`、`distanceToGround()`、`blockUnder()`、`jumpTicks()`/`setJumpTicks()`、`isInWeb()`、`ping()`、`direction()`、`isHoldingSword/Weapon/Block()`、`slot()`/`setSlot()`。

## 战斗

- **CombatUtil**：
  - `attack(实体)`：和左键一样，挥手、发 C02，再走你这边的流程。
  - `inReach(e, 距离)`、`serverAimsAt(e, 距离)`、`cameraAimsAt(e, 距离)`。
  - `wouldCrit()`、`canDamage(目标)`（是否过了受伤保护）、`nextHitSprintKnockback()`、`ticksSinceLastAttack()`。
- **TargetFinder**：默认用 Targets 模块的规则（玩家/怪物/动物/隐身/队友/机器人/好友/隔墙），每次查找时可以单独改：
  ```java
  EntityLivingBase t = TargetFinder.create().range(4).fov(120).sort(TargetFinder.Sort.ANGLE).first();
  List<EntityLivingBase> all = TargetFinder.create().players(true).mobs(true).walls(true).list();
  ```
  排序可选 `DISTANCE`、`ANGLE`、`HEALTH`、`HURT_TIME`；`filter(e -> ...)` 加自己的条件。
- **EntityTracker**（每 tick 记录周围的生物）：
  - `position(e, 几 tick 前)`、`history(e)`（最近 40 tick 的位置）
  - `velocity(e, 几 tick 平均)`：别的玩家也能算，服务器不发他们的速度
  - `predict(e, tick 数)`
  - `ticksSinceHurt(e)`、`ticksSinceAttacked(e)`、`hitsByYou(e)`、`combo()`、`ticksSinceYouWereHurt()`
- **EntityUtil**：
  - `players()`、`living()`、`isAlive()`
  - `isBot(p)`：不在 Tab 列表里、Hypixel NPC、名字不合法
  - `reachDistance(e)`：服务器判定距离用的值
  - `interpolated(e, pt)`、`interpolatedBox(e, pt)`、`health(e)`、`speed(e)`
- **TeamUtil**：`isTeammate(p)`，或用 `Check.SCOREBOARD` / `NAME_COLOR` / `ARMOR_COLOR` 指定判断方式；`nameColor(p)`、`armorColor(p)`。
- **Friends**：`add`、`remove`、`is(名字或实体)`、`all()`。

## 世界和方块

- **BlockUtil**：
  - 读方块：`block`、`state`、`isAir`、`isReplaceable`、`isSolid`、`isFullCube`、`isLiquid`、`collisionBox`、`isInteractable`
  - 位置：`center`、`faceCenter`、`distanceSqToEyes`
  - 查找：`find(中心, 半径, 条件)`（近的在前）、`beds(半径)`
  - 放方块：`placeInfo(目标位置)` 返回要点哪个方块的哪个面、点在哪里，用 `.rotation()` 取得看向那里的朝向；`place(info)` 和右键一样放下。
- **RaycastUtil**：
  - `block(yaw, pitch, 距离)`、`entity(yaw, pitch, 距离)`
  - `mouseOver(yaw, pitch, 实体距离, 方块距离)`：和准星选中的规则一样
  - `serverMouseOver(...)`：用服务器朝向
  - `isLookingAt(e, yaw, pitch, 距离)`、`canSee(e)`
- **InventoryUtil**：
  - 读格子：`stack(格子)`（0–8 快捷栏，9–35 背包，36–39 盔甲）
  - 查找：`findHotbar(条件)`、`count(条件)`、`bestWeapon()`、`damage(物品)`（包括锋利）
  - 方块：`bestBlocks()`、`isGoodBlock(物品)`（会排除 TNT、蜘蛛网、箱子等）、`blockCount()`
  - 点格子：`click(窗口, 格子, 按键, 模式)`、`clickOwn(...)`
- **ServerUtil**：`address()`、`isHypixel()`、`hypixelGame()`（BEDWARS、SKYWARS…）、`scoreboardTitle()`、`scoreboard()`（去掉颜色，从上到下）、`scoreboardHas(文字)`、`tabHeader()`、`tabFooter()`。
- **ServerState**（从真正发出的包读，包括别的端改过的）：服务器上的 `x/y/z`、`yaw/pitch`、`onGround`、`sprinting`、`sneaking`、`slot`、`lastAttack`。

## 输入和包

- **KeyUtil**：
  - `binding("forward"|"jump"|"attack"|"hotbar.3"…)`：取按键绑定
  - `setPressed(b, 按下)`、`press(b)`（按一次，attack/use 算一次点击）、`restore(b)`
  - `isDown(键码)`（鼠标键用 -100 + 按钮号）、`name(键码)`、`code("RSHIFT")`
- **ClickUtil**：
  - `leftClick()` / `rightClick()`：马上执行原版点击
  - `queueLeftClick()` / `queueRightClick()`：下一次处理输入时点击，和真的按鼠标一样
  - `leftClickCounter()` / `setLeftClickCounter()`：打空后的 10 tick 冷却
  - `rightClickDelay()` / `setRightClickDelay()`
- **Clicker**：按 CPS 范围给出像人一样的点击间隔。每帧调用 `due(最小, 最大)`，返回现在该点几下。
- **PacketUtil**：
  - `send(包)`：别的端和 Gale 都看得到
  - `sendNoEvent(包)`：Gale 自己的 PacketEvent 看不到
  - `receive(包)`：当作服务器发来的包处理
  - `name(包)`
  - `new PacketUtil.Holder()`：扣住包，之后用 `release()` 按顺序发出，或用 `drop()` 扔掉。可以用来做 blink 和延迟。
- 在 PacketEvent 里：`e.as(S12PacketEntityVelocity.class)` 得到这个类型的包，不是就返回 null；`e.setPacket(新包)` 换成别的包；`e.cancel()` 取消。

## 画

- **RenderUtil**（2D，用 GUI 坐标）：
  - 形状：`rect`、`box`、`gradient`、`gradientSideways`、`outline`、`roundedRect`、`circle`
  - 文字：`text`（支持 `&` 颜色码）、`centeredText`、`textWidth`、`fontHeight`
  - 其他：`item(物品, x, y)`、`scissor(...)` / `endScissor()`、`hovered(...)`
- **Render3D**（在 `Render3DEvent` 里用世界坐标画）：`box`、`block`、`entity`、`line`、`tracer`、`camera()`。
- **Projection.toScreen(x, y, z)**：把世界坐标换成屏幕坐标，在 Render2D 里画名牌、2D 框时用。
- **ColorUtil**：`argb`、`withAlpha`、`blend`、`darker`、`rainbow`、`pulse`、`health(血, 满血)`、`ofCode('c')`、`toFloats`。

## 其他

- **Scheduler**：
  - `main(r)`：任何线程都能调用，在下一 tick 开头放到游戏线程上执行
  - `now(r)`：已经在游戏线程上就马上执行
  - `later(tick 数, r)`、`every(tick 数, r)`，返回的任务可以 `cancel()`
- **Notifications**：`info`、`success`、`warn`、`error`、`show(标题, 文字, 类型, 毫秒)`，显示在右下角。HUD 关掉时改发到聊天栏。
- **ChatUtil**：`print`（带前缀，只有自己看得到）、`raw`、`info`/`warn`/`error`、`send`（发到服务器）、`color("&a…")`、`strip`、`firstColor`。
- **MathUtil**：`clamp`、`lerp`、`interpolate`、`wrap180`、`angleDiff`、`approach`、`round`、`random`、`randomInt`、`chance(百分比)`、`gaussian`。
- **Stopwatch**：`passed(毫秒)`、`passedAndReset(毫秒)`、`elapsed()`、`reset()`。
- **Mc**：`player()`、`world()`、`net()`、`controller()`、`inGame()`、`timer()`、`partialTicks()`、`setTimerSpeed()`、`fps()`。
- **ReflectUtil**：`field(类, "开发名", "SRG名")`、`method(...)`、`get`/`set`/`invoke`。私有字段要写两个名字，开发环境和正式游戏里的名字不一样。

## 按键绑定

每个模块可以绑一个键，`TOGGLE` 是按一下开关，`HOLD` 是按住才开。设置方式：

- 命令：`/gale bind <模块> <键> [hold]`
- 设置界面：左键点 Bind 选键，右键点 Bind 切换模式
- 代码：`setKey()`、`setBindMode()`

## 自测

`gradlew runClient -Pdevtest` 会启动游戏、新建超平坦世界，把上面这些都跑一遍，写出 `run/galemod-devtest.txt`，然后退出。写了新的基础功能，就在 [DevTest](../src/main/java/gale/devtest/DevTest.java) 里加一段检查。
