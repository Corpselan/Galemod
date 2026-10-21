# 从 B4 搬过来的模块

Gale 自带了一份 B4 的这些模块，代码照 OpenB4 搬（包 `gale.b4port`），设置的名字和默认值也和 B4 一样（取自 OpenB4 的 `modules.json`）：

| 模块 | 分类 | 做什么 |
|---|---|---|
| KillAura | 战斗 | 自动打范围内的目标，自动格挡（Manual / Vanilla / Damage / Fake / Interact A–D / Swap / Dual），静默转头 |
| AimAssist | 战斗 | 把准星慢慢拉向目标；Silent aim 时只改这一 tick 的点击指向 |
| Backtrack | 战斗 | 延迟目标的移动包，打它刚才的位置 |
| Lag Range | 战斗 | 靠近目标前压住自己的移动包，再一次放出 |
| BedAura | 玩家 | 自动挖范围内的床（Legit / Instant / Swap） |
| Scaffold | 玩家 | 脚下自动搭路，带 Fast scaffold 的各种模式 |
| Tower | 玩家 | 配合 Scaffold 往上搭（和 B4 一样不能单独开关，由 Scaffold 的 "Tower" 打开） |
| Bed Wars | 小游戏 | 敌人拿出火球、珍珠、隐身药、黑曜石等时提醒；商店点一下就买；滚轮存取资源；龙的距离、最近的敌人、魔法牛奶计时 |
| AntiCheat | 小游戏 | 发现别人开挂（AutoBlock、Scaffold、NoSlow、拆床……）时提醒，可以自动举报 |
| Nametags | 渲染 | 玩家头上换成 B4 的名牌：名字、血量（可选距离、几刀打死、潜行标记 S），好友绿框、敌人红框；Show items 时上面一排手里的东西和盔甲，带附魔缩写、耐久、数量 |
| PlayerESP | 渲染 | 标出玩家：Outline（描边）、Outline (Light Glow) / (Heavy Glow)（描边加发光）、Box、Shaded、2D、Ring，可加血条；颜色用 RGB、彩虹或队伍颜色，受伤变红 |
| Chams | 渲染 | 隔着墙也画出玩家（可以不画假人、不画自己） |
| AntiBot | 世界 | 搬来的模块不打 Hypixel 的假人 |
| B4 Settings | 客户端 | B4 的 Settings：算武器的物品、受伤多久算能打、移动修正、抖动…… |
| Locraw | 客户端 | 进 Hypixel 的每个世界时自动 `/locraw`（B4 在时由 B4 问） |

分类按 Gale 的分：B4 的 "network" 放在战斗里，"minigames" 和 "other" 放在新加的「小游戏」里。

## 和 B4 一起用：自动交接

B4 里开着 [GaleLink](../companion/GaleLink.java) 时：

- **B4 开着自己的某个模块，Gale 的同名模块就让路**（Nametags、PlayerESP、Chams 也一样：B4 在画，Gale 就不画，不会画两遍）（每个模块的 "Defer" 设置，默认开）。Tower 跟着 Scaffold 让路。AntiBot、B4 Settings 不让路，它们只是给 Gale 自己的模块用的设置。
- **让路时，Gale 这边看到的是 B4 的值。** GaleLink 每 tick 用 `b4.report` 把 B4 的状态报过来：KillAura / AimAssist / Backtrack / Lag Range 的目标，BedAura 在挖的床和进度，Scaffold / Tower 在不在放方块，你的床。Gale 的其他模块（比如 Gale 的 Scaffold 在 B4 的 KillAura 有目标时让一让）、灵动岛的拆床进度、Gale 里跑的 B4 脚本，都用这些值。
- **B4 的事件也转过来**：Bed Wars 提醒、AntiCheat 标记、`/locraw` 的回答、IRC 消息变成 Gale 的事件（`BedWarsAlertEvent`、`AntiCheatFlagEvent`、`LocrawEvent`、`IrcMessageEvent`），Gale 里跑的 B4 脚本因此能收到 onBedWarsAlert、onAntiCheatFlag、onLocRaw、onIRCMessage。
- **没有 B4（比如只装 Gale 的 1.8.9-Forge），就是 Gale 自己的模块在跑**，这些值和事件由 Gale 的模块给。

B4 关掉同名模块后，Gale 的模块（如果开着）下一 tick 就接着跑。GaleLink 停了以后，B4 的报告 1 秒后就不算数，让路 3 秒后结束。

GaleLink 新加了两个开关：

- **Share state**：每 tick 报 B4 的状态（默认开）；
- **Share events**：转发 B4 的事件（默认开）。

要用这些，把 B4 脚本文件夹里旧的 GaleLink.java 换成新的。旧的 GaleLink 只报模块开关和 BedAura 的进度，Gale 也认。

## 和 B4 不一样的地方

Gale 不用 mixin，B4 的几个钩子只能换个办法做：

- **pre-motion**（onPreMotion）：Gale 在你移动完、发移动包之前发 `MotionEvent`。改了位置、朝向、着地，就改这一 tick 的移动包；改了疾跑、潜行，这一 tick 告诉服务器。
- **发包钩子在网络线程上**。B4 的发包钩子在包发出的那一刻、在游戏线程上跑，它靠"正在发"的标记认出自己发的包。Gale 的在网络线程上、稍后才跑，所以搬来的代码改成给自己发的包做记号（KillAura 的攻击、交互、松开格挡，BedAura 的挖方块，换槽位）。
  - KillAura 的 Manual 格挡时右键会顺便打一下：这件事要在游戏线程上做，所以那个右键包会等到游戏线程上处理完再发出，最多晚一 tick。
- **Backtrack 压住的包**：从网络线程放出时和 B4 一样立刻进游戏；从游戏线程放出时（按延迟到时间）要等下一 tick 才处理，比 B4 晚最多 50 ms。
- **安全走路**（Scaffold 的 SafeWalk、Float 模式）：B4 直接改边缘判断。Gale 在你移动的那一下把你算成潜行（不减速），发移动包前改回来，服务器看不到潜行。停下的位置和原版潜行一样，比 B4 稍微靠里一点。
- **移动修正**：B4 Settings 的 "Movement fix" 开着时，用 Gale 的 LEGIT 修正（从八个方向里挑最接近的按键，和 B4 一样）；关着时用 EXACT（照样往镜头方向走）。
- **假格挡的动画**（KillAura 的 Fake、各种 Interact 格挡）：用 Gale 的 FakeUse 在你这边画举剑。
- **AimAssist 的 Silent aim**：B4 替换游戏选准星的那一步；Gale 在游戏选完之后改它（`PickApi.hook`），这一 tick 的点击照样打到目标。

Nametags、PlayerESP 和 B4 一样在世界画完之后画（Forge 的 RenderWorldLast），描边和发光用 B4 的着色器；Nametags 开着时藏掉原版名牌，"Disable in lobby" 看的是 Gale 读到的 Hypixel 大厅状态。好友是 Gale 的好友（`/gale friend`），敌人是 Gale 里 B4 脚本加的敌人。Nametags 不显示空岛战争的力量药水倒计时（Sky Wars 模块没有搬）；B4 的 BedESP、Murder Mystery、`.track` 的方框也没有搬。

这些模块用到的 B4 其他模块，Gale 没有搬，按它们关着算：Hit Select、Displace、NoSlow（KillAura 格挡时按原版 0.2 减速）、Freeze、Blink、Target HUD、BedESP（BedAura 的方框用默认主题）、AutoSwap 模块（Scaffold 的 "Auto swap" 用 AutoSwap 的默认设置）。Scaffold 的 "Rotation (fake)" 只管 B4 画头和身体，Gale 里不起作用，头和身体由 Gale 的 Rotations 模块画。

和 B4 一样：Backtrack、Lag Range、AntiCheat 在单人世界里什么都不做；Bed Wars 只在 Hypixel 的起床战争里提醒。

## 自测

```
gradlew runClient -Pb4porttest
```

在超平坦世界里：检查模块和默认设置；`MotionEvent` 改移动包；安全走路（还有不开时的对照）；KillAura 打一个铁傀儡、转头；"B4" 报告它开着 KillAura 时 Gale 的让路、看到 B4 的目标，B4 关了再接上；AimAssist 转镜头；Scaffold 搭路；BedAura 拆床；GaleLink 转来的四种事件；其余模块开着不报错。

```
gradlew runClient -Pb4rendertest
```

Nametags、PlayerESP、Chams：面前站两个假玩家（一个穿盔甲、拿附魔钻石剑），每个模块轮流打开，读回画面的像素看画了什么——B4 的名牌（背景调到 100% 时是纯黑的）、原版名牌被藏掉，PlayerESP 七种样式都画出自己的颜色、None 只画血条、队伍颜色（红队描红边）、描边那一遍不画名牌，Chams 隔着石墙看得见人；每个模块在 "B4" 报告开着同名模块时让路、什么都不画。截图在 `run/screenshots/b4render-*.png`。
